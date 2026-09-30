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
 *  - 默认只绑回环 [DEBUG_CLI_PORT]，用 `adb forward tcp:7777 tcp:7777` 访问；
 *    开启远程调试后改绑通配地址（同端口），非回环连接必须先 `auth`（见 [DebugCliRemote]）。
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
    /** 远程调试开关是否开启。关闭时不罚远程连接——远程连接本就不存在。 */
    val remoteEnabled: Boolean = false,
    /** 本次连接来源；默认回环，保持既有脚本免令牌。 */
    val connection: DebugCliConnection = DebugCliConnection.LOOPBACK,
    /** 本连接是否已 `auth` 成功；回环连接由服务端视为已鉴权。 */
    val authenticated: Boolean = false,
) {
    /**
     * 是否必须鉴权：开了远程调试、连接来自非回环、且尚未鉴权。
     *
     * 解析层据此把除 `auth` / `help` 外的命令一律拒掉。
     */
    val requiresAuth: Boolean
        get() = remoteEnabled && connection == DebugCliConnection.REMOTE && !authenticated
}

/** 解析成功后的命令意图；执行侧按类型分发。 */
sealed interface DebugCliIntent {
    data object Help : DebugCliIntent
    data object Status : DebugCliIntent

    /** 远程调试：提交令牌换一次本会话的鉴权。令牌校验在服务端，这里只带出候选值。 */
    data class Auth(val token: String) : DebugCliIntent

    /** 启动任务；[taskNames] 为空 = 跑当前激活配置的全部任务。 */
    data class Start(val taskNames: List<String>) : DebugCliIntent

    /** 停止当前任务；幂等。 */
    data object Stop : DebugCliIntent

    data class Report(val lines: Int) : DebugCliIntent
    data class LogTail(val lines: Int) : DebugCliIntent
    data object Screenshot : DebugCliIntent
    data class Ocr(val nodeName: String) : DebugCliIntent
    data class Run(val nodeName: String) : DebugCliIntent

    /**
     * 读回最近一次「排队执行」的调试结果。
     *
     * 任务运行中执行 `run` 会与运行中任务争抢 controller 输入，因此改为排到任务结束后独占执行、
     * 立刻返回；结果落到 RunDiagnostics/日志，并可由本命令读回。
     */
    data object ProbeResult : DebugCliIntent

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

    /**
     * 第一次端到端粗定位：全帧截图 → 裁小地图 → YOLO 得 zone+tile → 算搜索 ROI →
     * 在**地图资产图**上跑 `TemplateMatch`（小地图作为运行时模板）→ 返回粗位置。
     *
     * [imagePath] 是设备上的**全帧截图**路径（含小地图，PNG/JPEG）；
     * [zone] 是可选的 expected zone selector（上游 `options.expected_zone_id`），
     * 为空时用 YOLO 分类出的 zone。
     */
    data class CoarseLocate(val imagePath: String, val zone: String?) : DebugCliIntent

    /**
     * 追踪状态机单帧累加：读一张全帧截图 → 走与 [CoarseLocate] 相同的地图观测 →
     * 喂进 `MapLocatorTracking` 状态机（跨调用保留状态）→ 返回本帧裁决与累计状态。
     *
     * [imagePath] 为 null 表示 `tracklocate reset`：清空状态机，不读图。
     */
    data class TrackLocate(val imagePath: String?, val zone: String?) : DebugCliIntent
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

    /** 远程连接未鉴权时统一的错误文案。 */
    const val REMOTE_AUTH_REQUIRED = "远程连接未鉴权：请先执行 auth <令牌>"

    /**
     * 解析一行命令。
     *
     * 规则：命令名大小写不敏感（`HELP` 也可以），节点名大小写敏感；多余参数一律报错而不是忽略，
     * 免得调试者以为「加了个参数生效了」。
     *
     * 网络门控在最前面：远程调试开启且连接来自非回环且未鉴权时，除 `auth` / `help` 外一律拒绝。
     * 这条先于命令分派，未知命令也走同一句话，不泄露「这个命令存在但你没权限」。
     */
    fun parse(line: String, context: DebugCliContext): DebugCliParse {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            return DebugCliParse.Failure("空命令（输入 help 查看可用命令）")
        }
        val tokens = trimmed.split(WHITESPACE)
        val command = tokens[0].lowercase()
        val args = tokens.drop(1)
        if (context.requiresAuth && command != "auth" && command != "help") {
            return DebugCliParse.Failure(REMOTE_AUTH_REQUIRED)
        }
        return when (command) {
            "help" -> noArgs(command, args) { DebugCliIntent.Help }
            "status" -> noArgs(command, args) { DebugCliIntent.Status }
            "auth" -> when {
                args.isEmpty() -> DebugCliParse.Failure("auth 需要一个令牌：auth <token>")
                args.size > 1 -> DebugCliParse.Failure("auth 只接受一个令牌")
                else -> DebugCliParse.Ok(DebugCliIntent.Auth(args[0]))
            }

            // taskNames 为空 = 当前激活配置的全部任务；有值按声明序筛选
            "start" -> DebugCliParse.Ok(DebugCliIntent.Start(args))
            "stop" -> noArgs(command, args) { DebugCliIntent.Stop }
            "report" -> tailIntent(command, args) { DebugCliIntent.Report(it) }
            "logtail" -> tailIntent(command, args) { DebugCliIntent.LogTail(it) }
            "screenshot" -> when {
                args.isNotEmpty() -> DebugCliParse.Failure("screenshot 不接受参数")
                !context.controllerReady -> DebugCliParse.Failure(CONTROLLER_NOT_READY)
                else -> DebugCliParse.Ok(DebugCliIntent.Screenshot)
            }

            "ocr" -> nodeIntent(command, args, context) { DebugCliIntent.Ocr(it) }
            "run" -> nodeIntent(command, args, context) { DebugCliIntent.Run(it) }
            "probe-result" -> noArgs(command, args) { DebugCliIntent.ProbeResult }
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

            "coarselocate" -> when {
                args.isEmpty() -> DebugCliParse.Failure("coarselocate 需要一张全帧截图路径")
                args.size > 2 -> DebugCliParse.Failure("coarselocate 接受 <截图路径> [zone]（不要带空格）")
                !context.controllerReady -> DebugCliParse.Failure(CONTROLLER_NOT_READY)
                else -> DebugCliParse.Ok(DebugCliIntent.CoarseLocate(args[0], args.getOrNull(1)))
            }

            "tracklocate" -> when {
                args.isEmpty() -> DebugCliParse.Failure("tracklocate 需要 <截图路径>，或用 tracklocate reset 清空状态")
                args.size > 2 -> DebugCliParse.Failure("tracklocate 接受 <截图路径> [zone] 或 reset")
                args[0].equals("reset", ignoreCase = true) ->
                    if (args.size == 1) {
                        DebugCliParse.Ok(DebugCliIntent.TrackLocate(null, null))
                    } else {
                        DebugCliParse.Failure("tracklocate reset 不接受额外参数")
                    }

                !context.controllerReady -> DebugCliParse.Failure(CONTROLLER_NOT_READY)
                else -> DebugCliParse.Ok(DebugCliIntent.TrackLocate(args[0], args.getOrNull(1)))
            }

            else -> DebugCliParse.Failure("未知命令：$command（输入 help 查看可用命令）")
        }
    }

    /** 命令帮助文本，多行。 */
    fun helpText(): String = buildString {
        appendLine("Maaend debug CLI（仅 debug 构建；回环连接 127.0.0.1，远程需 auth）")
        appendLine("help                列出命令")
        appendLine("auth <token>        远程连接鉴权；回环连接无需令牌")
        appendLine("status              项目根 / controller 就绪 / 当前任务状态")
        appendLine("start [task...]     启动任务（不带参数=当前激活配置；带参数按 taskName 筛选）")
        appendLine("stop                停止当前任务")
        appendLine("report [n]          最新 RunDiagnostics JSONL 的末 n 行（默认 $DEBUG_CLI_DEFAULT_TAIL）")
        appendLine("logtail [n]         主日志的末 n 行（默认 $DEBUG_CLI_DEFAULT_TAIL）")
        appendLine("screenshot          把当前缓存帧存成 png，返回路径")
        appendLine("ocr <nodeName>      对当前帧跑该识别节点，返回 best 文本")
        appendLine("run <nodeName>      跑一次该节点（运行中会排到任务结束后执行，结果见 probe-result）")
        appendLine("probe-result        读回最近一次排队执行的调试结果")
        appendLine("overrideprobe       验证 OverrideImage+TemplateMatch 链（合成图，不依赖游戏/补充包）")
        appendLine("yoloprobe <img>     小地图预处理 → NeuralNetworkClassify(cls.onnx)，输出 zone/ROI")
        appendLine("coarselocate <frame> [zone]  全帧裁小地图 → YOLO → 地图资产上 TemplateMatch 粗定位")
        appendLine("tracklocate <frame> [zone] | reset  粗定位观测喂追踪状态机，打印 accept/reject/hold/relocate 与累计状态")
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
