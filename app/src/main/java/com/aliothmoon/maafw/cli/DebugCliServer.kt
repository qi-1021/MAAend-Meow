package com.aliothmoon.maafw.cli

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.third.Ln
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 调试 CLI 的 **socket 与执行层**：只做 I/O、线程，以及把 [DebugCliSupport] 解析出的意图
 * 接到真实能力上（由 [DebugCliHost] 提供，实际实现挂在特权进程的 MaaRunner）。
 *
 * 只在 debug 构建启动（启动点用 `BuildConfig.DEBUG` 门控），且只绑 `127.0.0.1`——
 * 用 `adb forward tcp:7777 tcp:7777` + `nc 127.0.0.1 7777` 访问。
 *
 * 任何连接/读写异常都吞掉并记 [Ln]，绝不让调试接口把 App 搞崩；进程/线程都是 daemon。
 * 协议：一行一条命令；每次响应最后一行固定为 [DEBUG_CLI_END_MARKER]。
 */
object DebugCliServer {

    private const val HOST = "127.0.0.1"

    /** 单次 tail 最多回读的字节数，避免把一份几百 MiB 的日志整份读进来。 */
    private const val TAIL_MAX_BYTES = 512 * 1024

    private val started = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /**
     * 启动监听。重复调用是空操作（返回 false）。绑定失败不抛，只记日志并返回 false。
     */
    fun start(host: DebugCliHost, port: Int = DEBUG_CLI_PORT): Boolean {
        // 硬门控：release 构建里 BuildConfig.DEBUG 是编译期 false，这段永不监听端口
        if (!BuildConfig.DEBUG) return false
        if (!started.compareAndSet(false, true)) return false
        return try {
            val socket = ServerSocket(port, 16, InetAddress.getByName(HOST))
            serverSocket = socket
            acceptThread = Thread({ acceptLoop(socket, host) }, "debug-cli-accept").apply {
                isDaemon = true
                start()
            }
            Ln.i("DebugCli: listening on $HOST:$port")
            true
        } catch (t: Throwable) {
            started.set(false)
            serverSocket = null
            Ln.w("DebugCli: start failed on $HOST:$port: ${t.message}", t)
            false
        }
    }

    /** 停止监听并关闭端口；幂等。 */
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
            .onFailure { Ln.w("DebugCli: close failed: ${it.message}") }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket, host: DebugCliHost) {
        while (started.get()) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                // stop() 关掉 serverSocket 会让 accept 抛异常，这是正常退出路径
                if (started.get()) Ln.w("DebugCli: accept failed: ${t.message}")
                return
            }
            Thread(
                { runCatching { handleClient(client, host) }.onFailure { Ln.w("DebugCli: client failed: ${it.message}") } },
                "debug-cli-client",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(socket: Socket, host: DebugCliHost) {
        socket.use { client ->
            val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
            writeResponse(writer, listOf("# Maaend debug CLI ready — type 'help' for commands"))
            while (true) {
                val line = reader.readLine() ?: break
                val output = runCatching { respond(line, host) }
                    .getOrElse { listOf("error: ${it.javaClass.simpleName}: ${it.message}") }
                writeResponse(writer, output)
            }
        }
    }

    private fun writeResponse(writer: BufferedWriter, lines: List<String>) {
        lines.forEach { writer.write(it); writer.newLine() }
        writer.write(DEBUG_CLI_END_MARKER)
        writer.newLine()
        writer.flush()
    }

    private fun respond(line: String, host: DebugCliHost): List<String> =
        when (val parsed = DebugCliSupport.parse(line, host.context())) {
            is DebugCliParse.Failure -> listOf("error: ${parsed.message}")
            is DebugCliParse.Ok -> dispatch(parsed.intent, host)
        }

    private fun dispatch(intent: DebugCliIntent, host: DebugCliHost): List<String> = when (intent) {
        DebugCliIntent.Help -> DebugCliSupport.helpText().lines()

        DebugCliIntent.Status -> DebugCliSupport.statusText(host.context()).lines()

        is DebugCliIntent.Report -> {
            val file = host.newestReportFile()
            if (file == null) {
                listOf("error: 还没有 RunDiagnostics 报告（先跑一次任务）")
            } else {
                listOf("report: ${file.absolutePath} (last ${intent.lines})") + tailLines(file, intent.lines)
            }
        }

        is DebugCliIntent.LogTail -> {
            val file = host.mainLogFile()
            if (file == null) {
                listOf("error: 找不到主日志文件")
            } else {
                listOf("log: ${file.absolutePath} (last ${intent.lines})") + tailLines(file, intent.lines)
            }
        }

        DebugCliIntent.Screenshot -> {
            val dir = host.screenshotDir()
            dir.mkdirs()
            val target = File(dir, "shot-${timestamp()}.png")
            val reason = host.screenshot(target)
            if (reason == null) listOf("saved: ${target.absolutePath}") else listOf("error: $reason")
        }

        is DebugCliIntent.Ocr -> {
            val result = host.ocr(intent.nodeName)
            if (result.text != null) {
                listOf("node: ${intent.nodeName}", "hit: ${result.ok}", "text: ${result.text}")
            } else {
                listOf("error: ${result.reason ?: "识别未返回文本"}")
            }
        }

        is DebugCliIntent.Run -> listOf("run: ${intent.nodeName} -> ${host.run(intent.nodeName)}")

        DebugCliIntent.ProbeResult -> host.probeResult()

        DebugCliIntent.OverrideProbe -> host.overrideProbe()

        is DebugCliIntent.YoloProbe -> host.yoloProbe(intent.imagePath)

        is DebugCliIntent.CoarseLocate -> host.coarseLocate(intent.imagePath, intent.zone)
    }

    /**
     * 取文件末 [lines] 行。只回读末尾 [TAIL_MAX_BYTES] 字节；截断处若落在半行上，丢掉残留半行。
     */
    private fun tailLines(file: File, lines: Int): List<String> {
        if (!file.isFile) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = (length - TAIL_MAX_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                var text = bytes.toString(Charsets.UTF_8)
                if (start > 0) text = text.substringAfter('\n', text)
                text.split('\n').filter { it.isNotEmpty() }.takeLast(lines)
            }
        } catch (t: Throwable) {
            Ln.w("DebugCli: tail ${file.name} failed: ${t.message}")
            emptyList()
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
}

/**
 * 调试 CLI 需要的真实能力。实现挂在特权进程（MaaRunner 持有 controller / resource / tasker），
 * [DebugCliServer] 只依赖这个接口，便于将来把实现换掉。
 *
 * 拿不到能力时**返回原因**，不要返回假数据：例如缓存帧为空时 [screenshot] 返回说明串。
 */
interface DebugCliHost {

    /** 取一份只读上下文快照，供命令解析与 status 渲染。 */
    fun context(): DebugCliContext

    /** 最新一份 RunDiagnostics 报告文件；没有返回 null。 */
    fun newestReportFile(): File?

    /** 主日志文件；找不到返回 null。 */
    fun mainLogFile(): File?

    /** 截图落盘目录（`files/cli/`）。 */
    fun screenshotDir(): File

    /** 把当前缓存帧写到 [target]；成功返回 null，失败返回原因。 */
    fun screenshot(target: File): String?

    /** 对当前帧跑识别节点 [nodeName]。 */
    fun ocr(nodeName: String): DebugCliOcrResult

    /** 跑一次节点 [nodeName]，返回结果描述。 */
    fun run(nodeName: String): String

    /**
     * 读回最近一次「排队执行」的调试结果（运行中 `run` 会在任务结束后补跑）。
     * 没有结果时返回一行说明。
     */
    fun probeResult(): List<String>

    /**
     * 路线 (b+) 前提验证：合成图 → OverrideImage → TemplateMatch → 校验返回框。
     * 返回多行结果（成功/失败与坐标），失败时给出原因。
     */
    fun overrideProbe(): List<String>

    /**
     * YOLO 分区分类探针：读 [imagePath] → 纯逻辑预处理 → `NeuralNetworkClassify(cls.onnx)`。
     * 返回多行结果（cls_index / 类名 / zone_id / tile ROI），失败时给出原因。
     */
    fun yoloProbe(imagePath: String): List<String>

    /**
     * 第一次端到端粗定位：读全帧截图 [imagePath] → 裁小地图 → YOLO 分类 → 算搜索 ROI →
     * 在地图资产上跑 `TemplateMatch`。返回多行结果（zone / tile / ROI / 命中框 / 是否在地图内）。
     *
     * [zone] 是可选 expected zone selector；为空用 YOLO 分类结果。
     */
    fun coarseLocate(imagePath: String, zone: String?): List<String>
}

/** [DebugCliHost.ocr] 的结果；[text] 为 null 时看 [reason]。 */
data class DebugCliOcrResult(
    val ok: Boolean,
    val text: String?,
    val reason: String?,
)
