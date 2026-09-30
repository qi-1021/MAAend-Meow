package com.aliothmoon.maafw.cli

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.third.Ln
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 远程调试中继客户端（跑在**特权进程**里，与 [DebugCliServer] 同进程）。
 *
 * 职责：手机**出站**连公网桥 → `/attach` 握手拿 sid → 长轮询 `/pull` 取命令 →
 * 直接交给 [DebugCliServer.executeRelayCommand] 走既有解析+分发 → `/result` 回传输出。
 *
 * 只在用户开启「远程调试」并点「连接远端」后运行；关闭设置或点「断开」即停。
 * 断线按指数退避自动重连；`stop()` 会断开当前 HTTP 连接并中断轮询，不会卡在长轮询里。
 *
 * 为什么不用 shell/curl：特权进程里没有可靠 shell 环境，且 shell 版客户端（`scripts/relay_client.sh`）
 * 是给「无 App / 手工验证」场景用的；App 内客户端才能复用进程内的 controller/tasker。
 *
 * 注意：本地 CLI 的裸 socket 响应有「欢迎语 + auth 回执 + 命令结果」三段、每段以 `--END--` 结尾，
 * 只有第 3 段有用。App 内客户端**不**走那条 socket，而是直接 [executeRelayCommand]，
 * 所以返回的就是命令本身的行，天然没有那三段的问题。
 */
object DebugCliRelayClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val ATTACH_READ_TIMEOUT_MS = 20_000
    private const val RESULT_READ_TIMEOUT_MS = 30_000

    /** 长轮询的读超时要比服务端 timeout 宽裕，留给隧道与排队时间。 */
    private const val PULL_READ_TIMEOUT_MS = (DEBUG_RELAY_PULL_TIMEOUT_SECONDS + 20) * 1_000

    private val running = AtomicBoolean(false)
    private val state = AtomicInteger(DebugCliRelayStatus.IDLE.wire)
    private val handled = AtomicLong(0L)

    @Volatile
    private var detail: String = ""

    @Volatile
    private var sessionId: String = ""

    @Volatile
    private var worker: Thread? = null

    /** 当前正在使用的连接；`stop()` 用它把长轮询立刻掐断。 */
    @Volatile
    private var active: HttpURLConnection? = null

    private val lock = Any()

    /**
     * 启动（或重启）中继客户端。地址非法或 release 构建返回 false，并把失败原因写进 [detail]。
     * 重复调用会先停掉旧线程再起新的。
     */
    fun start(host: DebugCliHost, relayUrl: String, token: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        val normalized = normalizeRelayUrl(relayUrl)
        if (normalized == null) {
            synchronized(lock) {
                state.set(DebugCliRelayStatus.FAILED.wire)
                detail = "中继地址非法：$relayUrl"
            }
            return false
        }
        synchronized(lock) {
            stopLocked()
            detail = ""
            sessionId = ""
            handled.set(0L)
            state.set(DebugCliRelayStatus.CONNECTING.wire)
            running.set(true)
            worker = Thread({ runLoop(host, normalized, token) }, "debug-cli-relay").apply {
                isDaemon = true
                start()
            }
        }
        Ln.i("DebugCliRelay: started relay=$normalized")
        return true
    }

    /** 停止并断开；幂等。 */
    fun stop() {
        synchronized(lock) { stopLocked() }
        Ln.i("DebugCliRelay: stopped")
    }

    private fun stopLocked() {
        running.set(false)
        runCatching { active?.disconnect() }
        val old = worker
        worker = null
        old?.interrupt()
        // 等旧线程退出再起新的，避免旧线程在 `running` 被重新置 true 后又继续跑、或延迟把状态改回去
        runCatching { old?.join(1_500L) }
        active = null
        state.set(DebugCliRelayStatus.IDLE.wire)
    }

    fun state(): Int = state.get()

    fun handled(): Long = handled.get()

    fun detail(): String = detail

    fun sessionId(): String = sessionId

    // ─────────────────────────── 主循环 ───────────────────────────

    private fun runLoop(host: DebugCliHost, relayUrl: String, token: String) {
        var attempt = 0
        while (running.get()) {
            if (state.get() != DebugCliRelayStatus.CONNECTED.wire) {
                state.set(DebugCliRelayStatus.CONNECTING.wire)
            }
            try {
                val sid = attach(relayUrl, token)
                if (sid == null) {
                    fail("attach 失败：$detail")
                    sleepBackoff(attempt++)
                    continue
                }
                sessionId = sid
                state.set(DebugCliRelayStatus.CONNECTED.wire)
                detail = ""
                attempt = 0
                Ln.i("DebugCliRelay: attached sid=$sid")
                pullLoop(host, relayUrl, token, sid)
            } catch (t: Throwable) {
                if (!running.get()) break
                fail("连接中断：${t.javaClass.simpleName}: ${t.message}")
                sleepBackoff(attempt++)
            }
        }
        // 只有仍是"当前"worker 才允许清状态：被 start() 替换掉的旧线程不许动
        if (Thread.currentThread() === worker) {
            sessionId = ""
            state.set(DebugCliRelayStatus.IDLE.wire)
        }
    }

    private fun pullLoop(host: DebugCliHost, relayUrl: String, token: String, sid: String) {
        while (running.get()) {
            val command = pull(relayUrl, token, sid) ?: continue
            handled.incrementAndGet()
            Ln.i("DebugCliRelay: run rid=${command.rid} cmd=${command.command.take(80)}")
            val output = DebugCliServer.executeRelayCommand(host, command.command).joinToString("\n")
            postResult(relayUrl, token, sid, command.rid, output)
        }
    }

    // ─────────────────────────── HTTP 三步 ───────────────────────────

    private fun attach(relayUrl: String, token: String): String? {
        val conn = newConnection(URL("$relayUrl/attach"), "POST", token, ATTACH_READ_TIMEOUT_MS)
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.outputStream.use { /* 触发请求；无 body */ }
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code != HttpURLConnection.HTTP_OK) {
                detail = "attach HTTP $code: ${body.trim().take(120)}"
                return null
            }
            val sid = parseRelaySid(body)
            if (sid == null) detail = "attach 响应无法解析 sid: ${body.trim().take(120)}"
            return sid
        } finally {
            clearActive(conn)
        }
    }

    private fun pull(relayUrl: String, token: String, sid: String): DebugCliRelayCommand? {
        val url = URL("$relayUrl/pull?sid=$sid&timeout=$DEBUG_RELAY_PULL_TIMEOUT_SECONDS")
        val conn = newConnection(url, "GET", token, PULL_READ_TIMEOUT_MS)
        return try {
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_NO_CONTENT -> null
                HttpURLConnection.HTTP_OK -> parsePullResponse(readBody(conn, code))
                else -> throw IOException("pull HTTP $code")
            }
        } finally {
            clearActive(conn)
        }
    }

    private fun postResult(relayUrl: String, token: String, sid: String, rid: String, output: String) {
        val conn = newConnection(URL("$relayUrl/result?sid=$sid&rid=$rid"), "POST", token, RESULT_READ_TIMEOUT_MS)
        try {
            val bytes = output.toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) detail = "result HTTP $code"
            readBody(conn, code)
        } finally {
            clearActive(conn)
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private fun newConnection(url: URL, method: String, token: String, readTimeout: Int): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("X-Token", token)
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeout
        conn.useCaches = false
        active = conn
        return conn
    }

    /** 成功读 body，失败读 error body（不吞掉），读不到就空串。 */
    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = runCatching {
            if (code in 200..299) conn.inputStream else conn.errorStream
        }.getOrNull() ?: return ""
        return runCatching { stream.bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
    }

    private fun clearActive(conn: HttpURLConnection) {
        if (active === conn) active = null
        runCatching { conn.disconnect() }
    }

    private fun fail(message: String) {
        state.set(DebugCliRelayStatus.FAILED.wire)
        detail = message
        Ln.w("DebugCliRelay: $message")
    }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep(relayBackoffMillis(attempt))
        } catch (_: InterruptedException) {
            // stop() 会 interrupt；下一轮 while 条件即退出
        }
    }
}
