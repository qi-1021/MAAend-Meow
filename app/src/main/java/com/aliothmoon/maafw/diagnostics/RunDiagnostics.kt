package com.aliothmoon.maafw.diagnostics

import com.aliothmoon.maafw.third.Ln
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 真机运行的**结构化诊断报告**（JSONL，一行一事件）。
 *
 * 背景：本项目在真机上「任务在哪一步失败、当时识别看到了什么」一直缺证据，
 * MaaFramework 自己的 `maafw.log` 与 `run/` 下的 jsonl 太底层，掺着框架内部噪音，
 * 定位我们的识别/动作时要在几千行里翻。这里只做一件事：把事件与我们在关键决策点
 * 补的「人话」按发生顺序落到一份独立文件，事后 grep 即可。
 *
 * 设计要点：
 *  - **事件原样保留**：`details` 直接带 MaaFramework 传来的 `detailsJson` 字符串。
 *    它的 shape 随版本变，任何解析都会在升级后悄悄丢字段，原样留着最保险。
 *  - **debug 才落盘**：release 构建里 [start] 传 false，所有方法立刻 return，
 *    不建目录、不建文件、不起任务。
 *  - **不阻塞主流程**：写盘全在单线程后台 executor；任何异常都吞掉并降级为 no-op，
 *    诊断不能反过来把任务搞挂。
 *  - **硬上限**：单文件 / 份数 / 目录总量三条线由 [RunDiagnosticsPolicy] 决定，
 *    写满落一行 `{"truncated":true}` 标记，不静默截断。
 *
 * 生命周期：[start] 建文件 → 期间 [event] / [note] 追加 → [finish] 收尾关闭。
 * 全部调用都直接受 [enabled] 闸门控制，进程内串行，不会出现半开半关。
 */
object RunDiagnostics {

    /** 报告文件里的字段名集中在这里，方便事后按 key grep。 */
    private const val KEY_TS = "ts"
    private const val KEY_EVENT = "event"
    private const val KEY_DETAILS = "details"
    private const val KEY_TAG = "tag"
    private const val KEY_MESSAGE = "message"
    private const val KEY_EXTRA = "extra"
    private const val KEY_RESULT = "result"
    private const val EVENT_NOTE = "note"
    private const val EVENT_FINISH = "RunDiagnostics.finish"

    /**
     * 单线程后台写盘。daemon：特权进程被系统收割时不额外拽住它。
     * 所有文件状态（writer / truncated / writtenBytes）只在这个线程里碰，天然串行。
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "run-diagnostics").apply { isDaemon = true }
    }

    /** 闸门：false 时所有 API 直接 no-op。跨线程读，用 atomic。 */
    private val enabled = AtomicBoolean(false)

    /**
     * 当前诊断根目录（`<pkg>/files/log/`），[saveDump] 的落点基准。
     *
     * 只在 worker 线程的 [beginLocked] 里写、在调用线程（[saveDump]）里读，
     * 用 `@Volatile` 保证可见性；release / 未 [start] 时为 null，[saveDump] 直接拒绝。
     */
    @Volatile
    private var dumpRoot: String? = null

    /** 当前已写字节数；只在 worker 线程读写，Atomic 只为跨线程可见性兜底。 */
    private val writtenBytes = AtomicLong(0L)

    // ── worker 线程独占 ──
    private var writer: BufferedWriter? = null
    private var truncated = false

    /**
     * 任务开始。[debug] 为 false（release）时不做任何事——不建目录、不建文件、不起任务。
     *
     * [logDir] 是特权进程写日志用的目录（`<pkg>/files/log/`），报告落在其下的 report 子目录。
     * 传空说明目录不可知，直接降级。
     */
    fun start(debug: Boolean, logDir: String? = null) {
        if (!debug) {
            // 同进程里上一轮开过的话顺手收掉；release 下本来就没有
            dumpRoot = null
            if (enabled.getAndSet(false)) {
                runCatching { worker.execute { closeLocked() } }
            }
            return
        }
        if (logDir.isNullOrBlank()) {
            enabled.set(false)
            return
        }
        // 先放行再排队：begin 排在队首，之后的 event 一定落在它后面，不会丢
        enabled.set(true)
        runCatching { worker.execute { beginLocked(logDir) } }
            .onFailure { enabled.set(false) }
    }

    /**
     * 诊断当前是否真正落盘（debug 且已成功 [start]）。
     *
     * 供调用方在 [note] 之外做**昂贵的诊断计算**前先判断：release/未启动时直接跳过，
     * 不为了一份不会写的报告去遍历 OCR 匹配。只读，不改变任何状态。
     */
    fun isEnabled(): Boolean = enabled.get()

    /** 记一条 MaaFramework 事件。`detailsJson` 原样保留，不解析。 */
    fun event(message: String, detailsJson: String) {
        if (!enabled.get()) return
        val line = runCatching {
            buildJsonObject {
                put(KEY_TS, System.currentTimeMillis())
                put(KEY_EVENT, message)
                put(KEY_DETAILS, detailsJson)
            }.toString()
        }.getOrNull() ?: return
        enqueue(line)
    }

    /**
     * 在关键决策点补一条人话。[tag] 用于按模块 grep（如 autostockpile / bettersliding / mapnavi），
     * [extra] 里的叶子类型支持 null / 布尔 / 数字 / 字符串 / Map / Iterable。
     */
    fun note(tag: String, message: String, extra: Map<String, Any?> = emptyMap()) {
        if (!enabled.get()) return
        val line = runCatching {
            buildJsonObject {
                put(KEY_TS, System.currentTimeMillis())
                put(KEY_EVENT, EVENT_NOTE)
                put(KEY_TAG, tag)
                put(KEY_MESSAGE, message)
                if (extra.isNotEmpty()) {
                    put(
                        KEY_EXTRA,
                        buildJsonObject {
                            extra.forEach { (key, value) -> put(key, toJsonElement(value)) }
                        },
                    )
                }
            }.toString()
        }.getOrNull() ?: return
        enqueue(line)
    }

    /** 任务结束：写下结果行后关闭文件。之后 [event] / [note] 不再落盘。 */
    fun finish(result: String?) {
        if (!enabled.getAndSet(false)) return
        val line = runCatching {
            buildJsonObject {
                put(KEY_TS, System.currentTimeMillis())
                put(KEY_EVENT, EVENT_FINISH)
                put(KEY_RESULT, result.orEmpty())
            }.toString()
        }.getOrNull()
        runCatching {
            worker.execute {
                if (line != null) writeLineLocked(line)
                closeLocked()
            }
        }
    }

    /**
     * 把一帧**二进制证据**（如货卡探针实际送去 OCR 的原图）写到
     * `<logDir>/<relativePath>`，返回是否已受理（不代表一定写成功）。
     *
     * 与 [event] / [note] 共用同一条后台队列，所以「先导图、后记路径」的顺序天然成立。
     * 落盘全程在 worker 线程，调用方（MAA 工作线程）不会被文件 IO 拖住。
     *
     * 只有诊断真正开启（[isEnabled]）且已成功 [start] 拿到根目录时才写；
     * release 下 [enabled] 为 false，这里直接返回 false，不建目录、不写文件。
     */
    fun saveDump(relativePath: String, bytes: ByteArray): Boolean {
        if (!enabled.get()) return false
        val root = dumpRoot ?: return false
        if (relativePath.isBlank() || bytes.isEmpty()) return false
        return runCatching {
            worker.execute {
                runCatching {
                    val file = File(root, relativePath)
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    // 导出成功后才清自己的目录；同队列串行，不拖慢 MAA 工作线程。
                    applyProbeRetentionLocked(root)
                    Ln.i("RunDiagnostics: dump -> ${file.absolutePath}")
                }.onFailure { Ln.w("RunDiagnostics: dump $relativePath failed: ${it.message}") }
            }
        }.isSuccess
    }

    // ────────────────────────── worker 线程内部 ──────────────────────────

    private fun enqueue(line: String) {
        runCatching { worker.execute { writeLineLocked(line) } }
    }

    private fun beginLocked(logDir: String) {
        // 同一进程里上一轮没走 finish 就开了下一轮时，先收掉旧文件
        closeLocked()
        try {
            val dir = File(logDir, RunDiagnosticsPolicy.REPORT_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) {
                Ln.w("RunDiagnostics: report dir unusable: ${dir.absolutePath}")
                enabled.set(false)
                return
            }
            // 报告目录已确认可用，二进制导出（如探针失败帧）才有落点
            dumpRoot = logDir
            val file = uniqueReportFile(dir)
            writer = BufferedWriter(FileWriter(file, false))
            writtenBytes.set(0L)
            truncated = false
            // 新文件已建出且最新，清理不会删到它
            applyRetentionLocked(dir)
            Ln.i("RunDiagnostics: report -> ${file.absolutePath}")
        } catch (t: Throwable) {
            closeLocked()
            enabled.set(false)
            Ln.w("RunDiagnostics: start failed, disabled: ${t.message}", t)
        }
    }

    /** 文件名带秒级时间戳；同一秒内再开就补序号，避免把上一份证据覆盖掉。 */
    private fun uniqueReportFile(dir: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = RunDiagnosticsPolicy.FILE_PREFIX + stamp
        var file = File(dir, base + RunDiagnosticsPolicy.FILE_SUFFIX)
        var seq = 1
        while (file.exists()) {
            file = File(dir, "$base-$seq${RunDiagnosticsPolicy.FILE_SUFFIX}")
            seq++
        }
        return file
    }

    /** 按 [RunDiagnosticsPolicy] 的两条上限清理旧报告；删失败不影响本次记录。 */
    private fun applyRetentionLocked(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && isReportName(it.name) }
            ?.map { RunDiagnosticsPolicy.ReportFile(it.name, it.length(), it.lastModified()) }
            ?: return
        RunDiagnosticsPolicy.selectDeletions(files).forEach { target ->
            runCatching { File(dir, target.name).delete() }
                .onFailure { Ln.w("RunDiagnostics: delete ${target.name} failed: ${it.message}") }
        }
    }

    private fun isReportName(name: String): Boolean =
        name.startsWith(RunDiagnosticsPolicy.FILE_PREFIX) &&
            name.endsWith(RunDiagnosticsPolicy.FILE_SUFFIX)

    /**
     * 按 [GoodsProbeDumpPolicy] 的两条上限清理旧探针帧；删失败不影响本次导出。
     *
     * 只 `listFiles` 探针自己的子目录、且只认 `goods-*.png`，所以 `report/`、`run/`、
     * `on_error/` 等旁的目录**根本不会被列到**，不存在误删可能。清理在 worker 线程、
     * 紧跟一次成功导出之后跑，正常路径（没有全 0 候选）完全不会触发目录扫描。
     */
    private fun applyProbeRetentionLocked(rootDir: String) {
        val dir = File(rootDir, GoodsProbeDumpPolicy.DUMP_DIR)
        val files = dir.listFiles()
            ?.filter { it.isFile && GoodsProbeDumpPolicy.isDumpName(it.name) }
            ?.map { RunDiagnosticsPolicy.ReportFile(it.name, it.length(), it.lastModified()) }
            ?: return
        GoodsProbeDumpPolicy.selectDeletions(files).forEach { target ->
            runCatching { File(dir, target.name).delete() }
                .onFailure { Ln.w("RunDiagnostics: delete probe dump ${target.name} failed: ${it.message}") }
        }
    }

    private fun writeLineLocked(line: String) {
        val w = writer ?: return
        if (truncated) return
        try {
            val payload = line + "\n"
            val bytes = payload.toByteArray(Charsets.UTF_8).size.toLong()
            if (RunDiagnosticsPolicy.shouldTruncate(writtenBytes.get(), bytes)) {
                // 不静默截断：落一行可辨识标记，事后一眼能看出这份报告不完整
                val marker = "{\"truncated\":true,\"ts\":${System.currentTimeMillis()}}\n"
                w.write(marker)
                w.flush()
                truncated = true
                writer = null
                w.close()
                enabled.set(false)
                Ln.w("RunDiagnostics: report truncated at ${RunDiagnosticsPolicy.MAX_FILE_BYTES} bytes")
                return
            }
            w.write(payload)
            w.flush()
            writtenBytes.addAndGet(bytes)
        } catch (t: Throwable) {
            // 单次写失败就收摊：诊断不能反过来影响任务
            closeLocked()
            enabled.set(false)
            Ln.w("RunDiagnostics: write failed, disabled: ${t.message}", t)
        }
    }

    private fun closeLocked() {
        val w = writer ?: return
        writer = null
        runCatching {
            w.flush()
            w.close()
        }.onFailure { Ln.w("RunDiagnostics: close failed: ${it.message}") }
    }

    // ────────────────────────── JSON 值转换 ──────────────────────────

    /** 把 [note] 的 extra 值转成 JSON 元素；不支持的叶子退化成字符串，绝不抛。 */
    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) -> put(k?.toString().orEmpty(), toJsonElement(v)) }
        }

        is Iterable<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        is Array<*> -> buildJsonArray { value.forEach { add(toJsonElement(it)) } }
        else -> JsonPrimitive(value.toString())
    }
}
