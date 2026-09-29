package com.aliothmoon.maafw.cli

/**
 * 调试 CLI 的**纯逻辑层**：吃一行文本 + 一个只读上下文快照，返回命令意图或错误。
 *
 * 为什么抽出来：命令解析、参数边界、以及「controller 没好时该报什么」这些东西如果不依赖
 * Android/JNA，就能在 [scripts/verify_pure_logic.sh] 里本机跑断言。socket、线程、把意图接到
 * 真实能力那部分在 [DebugCliServer] 与特权进程侧，本机编不了，交给 CI。
 *
 * 协议约定（写死在这里，服务端与测试共用）：
 *  - 一行一条命令；
 *  - 每次响应的**最后一行固定是 [DEBUG_CLI_END_MARKER]**，脚本据此判断读完；
 *  - 只绑 [DEBUG_CLI_PORT] 对应的回环地址，用 `adb forward tcp:7777 tcp:7777` 访问。
 *
 * 这里**不碰任何文件、网络、Android**：只有数据类、密封接口与纯函数。
 */

/** 调试 CLI 监听端口；改这里即可。只绑回环，不绑 0.0.0.0。 */
const val DEBUG_CLI_PORT = 7777

/** 每次响应的结束标记行；调试脚本读完这一行就知道本次输出结束。 */
const val DEBUG_CLI_END_MARKER = "--END--"

/** `report` / `logtail` 不传行数时的默认值。 */
const val DEBUG_CLI_DEFAULT_TAIL = 50

/** `report` / `logtail` 行数上限，防止一条命令把整份日志拖进来。 */
const val DEBUG_CLI_MAX_TAIL = 2000

/**
 * 命令解析所需的**只读上下文快照**。
 *
 * 解析阶段只拿它做校验（controller 没好就拒绝 screenshot/ocr/run），不读活对象。
 * 服务端在每次处理命令时现取一份放进 [DebugCliSupport.parse]。
 */
data class DebugCliContext(
    /** PI 项目根路径；未设置时 null。 */
    val projectRoot: String?,
    /** controller 是否已连接、能取到缓存帧。 */
    val controllerReady: Boolean,
    /** 当前是否有任务在跑。 */
    val taskRunning: Boolean,
    /** RunDiagnostics 报告目录绝对路径；未知时 null。 */
    val reportDir: String?,
    /** 主日志所在目录绝对路径；未知时 null。 */
    val logDir: String?,
)

/** 解析成功后的命令意图；执行侧按类型分发。 */
sealed interface DebugCliIntent {
    data object Help : DebugCliIntent
    data object Status : DebugCliIntent
    data class Report(val lines: Int) : DebugCliIntent
    data class LogTail(val lines: Int) : DebugCliIntent
    data object Screenshot : DebugCliIntent
    data class Ocr(val nodeName: String) : DebugCliIntent
    data class Run(val nodeName: String) : DebugCliIntent

    /**
     * 路线 (b+) 前提验证：合成图 → `MaaContextOverrideImage` 覆盖运行时模板 →
     * `MaaContextRunRecognition(TemplateMatch)` → 校验返回框。不依赖游戏/补充包。
     */
    data object OverrideProbe : DebugCliIntent

    /**
     * YOLO 分区分类探针：读一张小地图图 → [com.aliothmoon.maafw.remote.YoloPreprocess] 预处理
     * （居中裁/贴 128×128 + 直径 106 圆 mask，BGR）→ `NeuralNetworkClassify(cls.onnx)` →
     * 输出 cls_index / 类名 / zone_id / tile ROI。
     *
     * [imagePath] 是设备上的图片路径（PNG/JPEG，Android `BitmapFactory` 可解）。
     */
    data class YoloProbe(val imagePath: String) : DebugCliIntent
}

/** 解析结果：要么是意图，要么是给用户看的错误。 */
sealed interface DebugCliParse {
    data class Ok(val intent: DebugCliIntent) : DebugCliParse
    data class Failure(val message: String) : DebugCliParse
}

object DebugCliSupport {

    private val WHITESPACE = Regex("\\s+")

    /** controller 未就绪时统一的错误文案，测试与实现共用一处。 */
    const val CONTROLLER_NOT_READY = "controller 未就绪（先跑一次任务建立 controller）"

    /**
     * 解析一行命令。
     *
     * 规则：命令名大小写不敏感（`HELP` 也可以），节点名大小写敏感；多余参数一律报错而不是忽略，
     * 免得调试者以为「加了个参数生效了」。
     */
    fun parse(line: String, context: DebugCliContext): DebugCliParse {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            return DebugCliParse.Failure("空命令（输入 help 查看可用命令）")
        }
        val tokens = trimmed.split(WHITESPACE)
        val command = tokens[0].lowercase()
        val args = tokens.drop(1)
        return when (command) {
            "help" -> noArgs(command, args) { DebugCliIntent.Help }
            "status" -> noArgs(command, args) { DebugCliIntent.Status }
            "report" -> tailIntent(command, args) { DebugCliIntent.Report(it) }
            "logtail" -> tailIntent(command, args) { DebugCliIntent.LogTail(it) }
            "screenshot" -> when {
                args.isNotEmpty() -> DebugCliParse.Failure("screenshot 不接受参数")
                !context.controllerReady -> DebugCliParse.Failure(CONTROLLER_NOT_READY)
                else -> DebugCliParse.Ok(DebugCliIntent.Screenshot)
            }

            "ocr" -> nodeIntent(command, args, context) { DebugCliIntent.Ocr(it) }
            "run" -> nodeIntent(command, args, context) { DebugCliIntent.Run(it) }
            "overrideprobe" -> when {
                args.isNotEmpty() -> DebugCliParse.Failure("overrideprobe 不接受参数")
                !context.controllerReady -> DebugCliParse.Failure(CONTROLLER_NOT_READY)
                else -> DebugCliParse.Ok(DebugCliIntent.OverrideProbe)
            }

            "yoloprobe" -> when {
                args.isEmpty() -> DebugCliParse.Failure("yoloprobe 需要一个小地图图片路径")
                args.size > 1 -> DebugCliParse.Failure("yoloprobe 只接受一个图片路径（路径不要带空格）")
                !context.controllerReady -> DebugCliParse.Failure(CONTROLLER_NOT_READY)
                else -> DebugCliParse.Ok(DebugCliIntent.YoloProbe(args[0]))
            }

            else -> DebugCliParse.Failure("未知命令：$command（输入 help 查看可用命令）")
        }
    }

    /** 命令帮助文本，多行。 */
    fun helpText(): String = buildString {
        appendLine("Maaend debug CLI（仅 debug 构建；只绑 127.0.0.1）")
        appendLine("help                列出命令")
        appendLine("status              项目根 / controller 就绪 / 当前任务状态")
        appendLine("report [n]          最新 RunDiagnostics JSONL 的末 n 行（默认 $DEBUG_CLI_DEFAULT_TAIL）")
        appendLine("logtail [n]         主日志的末 n 行（默认 $DEBUG_CLI_DEFAULT_TAIL）")
        appendLine("screenshot          把当前缓存帧存成 png，返回路径")
        appendLine("ocr <nodeName>      对当前帧跑该识别节点，返回 best 文本")
        appendLine("run <nodeName>      跑一次该节点")
        appendLine("overrideprobe       验证 OverrideImage+TemplateMatch 链（合成图，不依赖游戏/补充包）")
        appendLine("yoloprobe <img>     小地图预处理 → NeuralNetworkClassify(cls.onnx)，输出 zone/ROI")
    }.trimEnd()

    /** `status` 的渲染文本，多行。 */
    fun statusText(context: DebugCliContext): String = buildString {
        appendLine("project root : ${context.projectRoot?.takeIf { it.isNotBlank() } ?: "(未设置)"}")
        appendLine("controller   : ${if (context.controllerReady) "ready" else "not ready"}")
        appendLine("task         : ${if (context.taskRunning) "running" else "idle"}")
        appendLine("report dir   : ${context.reportDir ?: "-"}")
        appendLine("log dir      : ${context.logDir ?: "-"}")
    }.trimEnd()

    private fun noArgs(
        command: String,
        args: List<String>,
        build: () -> DebugCliIntent,
    ): DebugCliParse =
        if (args.isEmpty()) {
            DebugCliParse.Ok(build())
        } else {
            DebugCliParse.Failure("$command 不接受参数：${args.joinToString(" ")}")
        }

    /** `report` / `logtail`：可选一个正整数行数，超上限夹到 [DEBUG_CLI_MAX_TAIL]。 */
    private fun tailIntent(
        command: String,
        args: List<String>,
        build: (Int) -> DebugCliIntent,
    ): DebugCliParse {
        if (args.size > 1) return DebugCliParse.Failure("$command 最多接受一个行数参数")
        val lines = if (args.isEmpty()) {
            DEBUG_CLI_DEFAULT_TAIL
        } else {
            val parsed = args[0].toIntOrNull()
                ?: return DebugCliParse.Failure("$command 的行数必须是整数：${args[0]}")
            if (parsed <= 0) return DebugCliParse.Failure("$command 的行数必须大于 0：$parsed")
            parsed.coerceAtMost(DEBUG_CLI_MAX_TAIL)
        }
        return DebugCliParse.Ok(build(lines))
    }

    /** `ocr` / `run`：恰好一个节点名，且 controller 必须就绪。 */
    private fun nodeIntent(
        command: String,
        args: List<String>,
        context: DebugCliContext,
        build: (String) -> DebugCliIntent,
    ): DebugCliParse {
        if (args.isEmpty()) return DebugCliParse.Failure("$command 需要一个节点名")
        if (args.size > 1) return DebugCliParse.Failure("$command 只接受一个节点名")
        if (!context.controllerReady) return DebugCliParse.Failure(CONTROLLER_NOT_READY)
        return DebugCliParse.Ok(build(args[0]))
    }
}
