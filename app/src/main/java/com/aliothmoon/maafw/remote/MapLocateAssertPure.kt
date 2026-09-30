package com.aliothmoon.maafw.remote

/**
 * `MapLocateAssertLocation` 的**裁决纯逻辑**。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MapLocateAction.cpp:404-471`：
 * 重置追踪状态 → 最多 [ASSERT_LOCATE_MAX_FRAMES] 帧、每帧间隔
 * [ASSERT_LOCATE_POLL_DELAY_MS] 轮询定位（`force_global_search=true`、
 * `expected_zone_id=zone_id`）→ 定位成功且位置落在 `target` 矩形内即 matched。
 *
 * 与上游的差异（上游没有，真机代价过高）：加了两道**提前收束**，都保留「传送后等地图出现」
 * 的语义——帧数上限与 [ASSERT_LOCATE_MAX_WALL_MS] 时间上限**先到者**生效；连续
 * [ASSERT_LOCATE_DETERMINISTIC_FAIL_FRAMES] 帧「确定性失败」也会提前结束。详见各常量注释。
 *
 * 真机实测质疑了上一轮「画面静止 → 快速失败」的可行性：真实 3D 游戏画面每帧都在变
 * （渲染 / 动画），逐像素指纹几乎永不相等，[isScreenStatic] 从不触发。因此这一轮改用
 * 「定位结果是否**确定性失败**」（[classifyAssertFailure]）而非「画面是否静止」来早停，
 * 并以墙钟上限做保底。
 *
 * 这里只保留**可离线判定**的部分：逐帧观测序列 + 目标矩形 → 是否命中、命中在第几帧、
 * 最后一帧的定位结果（上游 detail 用的是最后一次 `locate` 的 result）。真正的
 * 截图 / YOLO / 匹配 / 追踪状态机留在 MaaRunner。
 *
 * 几何（`TryBuildAssertRect` / `IsPositionInsideRect`）直接复用 [MapLocateActionPure]，
 * 不重复实现；常量与 [LocateStatus] 复用 [MapLocatorTypes]。
 *
 * 本文件不碰 Android / JNA / 文件系统，可在 [scripts/verify_pure_logic.sh] 本机回归。
 */
object MapLocateAssertPure {

    /**
     * 上游 `kAssertLocateMaxFrames`（`MapLocateAction.cpp:229`）的**帧数上限**。
     *
     * 与 [ASSERT_LOCATE_MAX_WALL_MS] 的关系：二者**取先到者**——调用方每帧后都问一次
     * [decideAssertStop]，命中 [AssertStopReason.MAX_FRAMES] 或
     * [AssertStopReason.WALL_CLOCK_BUDGET] 谁先满足就停。
     *
     * 需说清的量级：60 × [ASSERT_LOCATE_POLL_DELAY_MS] = 15s 只是**睡眠**预算；真机每帧还要
     * 截图 + YOLO + 模板匹配 + 热图（实测 ~0.9~1.0s/帧），于是 60 帧真实墙钟 ~54~105s。
     * 所以当每帧计算时间远大于 250ms 时，**时间上限才是真正生效的那道闸**，帧数上限只是兜底。
     * 默认值：帧数 60、时间 [ASSERT_LOCATE_MAX_WALL_MS]=20s。
     */
    const val ASSERT_LOCATE_MAX_FRAMES = 60

    /** 上游 `kAssertLocatePollDelay`（`MapLocateAction.cpp:230`），单位毫秒。 */
    const val ASSERT_LOCATE_POLL_DELAY_MS = 250L

    /**
     * 整个 assert 的**墙钟上限**（毫秒，保底项）。
     *
     * 上游只约束帧数（见 [ASSERT_LOCATE_MAX_FRAMES]），而帧数 × 每帧真实耗时在真机上会到
     * ~54~105s（真机观测 61756ms / 104965ms 两例），远超「等地图/传送切画面」的合理等待。
     *
     * 上游注释（`MapLocateAction.cpp:228`）说预算要覆盖「传送落地后区域横幅压住小地图」的
     * 那段等待；20s 足以覆盖这类过场，同时把最坏情况从 ~105s 压到 ~20s。它**不砍**轮询帧数，
     * 只是给整轮设一个到点即止的上限，因此不破坏「等地图出现」的正常场景。
     *
     * 一帧的计算无法中途打断，故最坏墙钟 ≈ 该常量 + 一帧耗时（~1s）。
     */
    const val ASSERT_LOCATE_MAX_WALL_MS = 20_000L

    /**
     * 连续「确定性失败」帧数阈值：连续这么多帧都是 [AssertFailKind.DETERMINISTIC] 失败、
     * 且 `debugMessage` 完全相同，就认定「画面已切过去了、但定位就是失败」，提前结束轮询。
     *
     * 取值 6（任务建议 5~8）。它同时是「等待过场被误杀」与「空转省时」的折中：真机每帧
     * ~1s，6 帧 ≈ 6s，短于 [ASSERT_LOCATE_MAX_WALL_MS]=20s；即使判据偶有误伤，墙钟上限
     * 仍能兜底，不会无限等。可调常量，真机复测若发现过场被误杀可上调。
     */
    const val ASSERT_LOCATE_DETERMINISTIC_FAIL_FRAMES = 6

    /**
     * 快速失败阈值：末尾连续这么多帧画面指纹完全一致，就认定「画面已静止」，
     * 立即结束轮询并判未命中。
     *
     * 上游没有这个短路：真机上「传送后地图已开 / 卡在静态全屏地图页」时，60 帧里画面
     * 可能一帧都没变，而每帧仍要跑一遍截图 + YOLO + 模板匹配 + 热图（实测 ~0.9s/帧），
     * `cost≈54s` 纯属空转，还持续产生 on_error 失败帧。画面不再变化就意味着再等下去
     * 也不会有地图出现，可以立刻判未命中。
     *
     * 它同时保住了「传送后等地图加载出来」：只要画面仍在变化（加载动画 / 渐入），
     * [isScreenStatic] 就为 false，照常轮询到 [ASSERT_LOCATE_MAX_FRAMES]。
     *
     * 取值沿用任务建议的 2~3 帧上界；这是「快速失败」与「静态加载页误判」之间的折中，
     * 是纯逻辑常量，真机复测若发现加载页被误杀可下调/上调。
     */
    const val ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL = 3

    /**
     * 单帧定位观测。
     *
     * [status] 对齐上游 [LocateStatus]；[position] 是追踪状态机稳定化后的绝对地图坐标。
     * 上游判「本帧定位到了」的条件是 `status == Success && position.has_value()`
     * （`MapLocateAction.cpp:444`）。
     */
    data class AssertFrame(
        val status: LocateStatus,
        val position: MapPosition?,
        val debugMessage: String = "",
    ) {
        /** 上游 `located`（`MapLocateAction.cpp:444`）。 */
        val located: Boolean get() = status == LocateStatus.SUCCESS && position != null
    }

    /** 轮询裁决结果。 */
    data class AssertOutcome(
        val matched: Boolean,
        /** 命中帧下标；未命中为 -1。 */
        val matchedFrame: Int,
        /** 实际轮询到的帧数。 */
        val framesPolled: Int,
        /** 最后一帧（上游 detail 取自最后一次 `locate` 的 result）。 */
        val finalFrame: AssertFrame,
    )

    /**
     * 逐帧裁决（`MapLocateAction.cpp:438-454` 的循环体）。
     *
     * 扫描 [frames]，首个「定位到且落在 [targetRect] 内」的帧即命中并停止；
     * 未命中时 [AssertOutcome.finalFrame] 是最后一帧。[frames] 为空时返回未命中，
     * finalFrame 用 `NOT_INITIALIZED` 占位。
     */
    fun evaluateAssertFrames(frames: List<AssertFrame>, targetRect: MapRect): AssertOutcome {
        var matchedFrame = -1
        for ((index, frame) in frames.withIndex()) {
            val pos = frame.position
            if (frame.located && pos != null && MapLocateActionPure.isPositionInsideRect(pos, targetRect)) {
                matchedFrame = index
                break
            }
        }
        val finalFrame = frames.lastOrNull() ?: AssertFrame(LocateStatus.NOT_INITIALIZED, null)
        return AssertOutcome(
            matched = matchedFrame >= 0,
            matchedFrame = matchedFrame,
            framesPolled = frames.size,
            finalFrame = finalFrame,
        )
    }

    /**
     * 依据逐帧画面指纹判断「画面已静止到可以提前放弃」。
     *
     * 判定只看**末尾连续**的 [ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL] 帧：只要这窗口内指纹
     * 全部相等且非 null，就认为画面停止变化、再等也不会有地图，返回 true。
     *
     * @param fingerprints 按轮询顺序记录的每帧画面指纹。`null` 表示该帧取帧失败、没有
     *   可用画面：它既不算「相同」也不算「不同」，但会**打断连续计数**（落在窗口内即
     *   返回 false），避免把「连续取帧失败」误判成「画面静止」。
     * @return 画面已静止为 true；帧数不足阈值、或窗口内出现 null、或有差异时为 false。
     */
    fun isScreenStatic(fingerprints: List<Long?>): Boolean {
        val n = ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL
        if (n <= 0 || fingerprints.size < n) return false
        val first = fingerprints[fingerprints.size - n] ?: return false
        for (i in fingerprints.size - n + 1 until fingerprints.size) {
            if (fingerprints[i] != first) return false
        }
        return true
    }

    // ───────────────── 确定性失败 → 提前结束（真机画面恒变，改用定位结果判据） ─────────────────
    //
    // 上一轮用「画面逐像素指纹连续 N 帧不变」做早停，真机上失效：3D 游戏每帧都在渲染/动画，
    // 指纹几乎永不相等（`isScreenStatic` 从不返回 true）。这一轮改判**定位结果的语义**：
    // 区分「画面还没切过去（该继续等）」与「画面已经切过去了但定位就是失败（该早停）」。

    /**
     * 判为「确定性失败」的 `debugMessage` 前缀。
     *
     * **怎么区分两类**（本判据的核心，也是选它的依据）：
     *  - 「画面还没切过去」= 当前帧**没有可用的地图输入**，属等待态
     *    ([AssertFailKind.WAITING])：YOLO 报「本帧没有可定位区域」（None）、追踪状态机
     *    还没稳定（「全局搜索无有效峰」/ 观测被拒）、或这一帧取帧失败。这些正是加载过场 /
     *    追踪冷启动会产生的瞬态，等下去**可能变好**，必须继续轮询。
     *  - 「画面已经切过去了但定位就是失败」= 有输入、但失败原因**绑定目标 / 资产 / 设备几何**，
     *    与「再等多久」无关 ([AssertFailKind.DETERMINISTIC])：期望 zone 与实际 zone 不匹配、
     *    地图资产缺失或解码失败、搜索 ROI 退化、小地图裁剪 / 预处理在既定分辨率下不可能成功、
     *    模型输出无法解析等。**同一画面上再等也不会改变**，可以早停。
     *
     * 列表用的是**前缀**匹配（资产缺失等消息含动态 zone id）。原则是「拿不准的归 WAITING」：
     * 宁可多等（有墙钟上限兜底），也不误杀「等地图」。
     */
    private val ASSERT_LOCATE_DETERMINISTIC_FAILURE_PREFIXES = listOf(
        "YOLO 约束未通过", // zone 不匹配 selector：目标与画面 zone 明确冲突
        "地图资产缺", // 缺 zone=… 底图，等下去也不会凭空出现
        "地图资产解码失败", // 资产损坏
        "搜索 ROI 裁到边界后为空", // 约束 ROI 退化，几何确定
        "小地图 ROI 越界", // 设备分辨率/裁剪平面确定
        "小地图裁剪失败",
        "YOLO 预处理失败",
        "YOLO 分类无效", // cls_index 越界，模型配置确定
        "无法解析分类详情",
    )

    /**
     * 单帧失败的语义分类，用于区分「该继续等」与「等也没用」。
     *
     * 见 [ASSERT_LOCATE_DETERMINISTIC_FAILURE_PREFIXES] 顶部的两类判据说明。
     */
    fun classifyAssertFailure(frame: AssertFrame): AssertFailKind {
        if (frame.located) return AssertFailKind.SUCCESS
        return if (isDeterministicAssertFailureMessage(frame.debugMessage)) {
            AssertFailKind.DETERMINISTIC
        } else {
            AssertFailKind.WAITING
        }
    }

    /** [debugMessage][AssertFrame.debugMessage] 是否命中确定性失败前缀。空串不算失败。 */
    fun isDeterministicAssertFailureMessage(message: String): Boolean =
        message.isNotEmpty() && ASSERT_LOCATE_DETERMINISTIC_FAILURE_PREFIXES.any { message.startsWith(it) }

    /**
     * 末尾是否已连续 [count] 帧「确定性失败且 `debugMessage` 完全相同」。
     *
     * 要求 message 逐帧相同（不只是同类）：过场途中偶发一帧同类失败不应算数，只有**稳定复现
     * 的同一条确定性失败**才说明状态已定。`count <= 0`、帧数不足、或窗口内出现任一非
     * [AssertFailKind.DETERMINISTIC] / 消息不同 → false。
     */
    fun hasDeterministicFailureStreak(
        frames: List<AssertFrame>,
        count: Int = ASSERT_LOCATE_DETERMINISTIC_FAIL_FRAMES,
    ): Boolean {
        if (count <= 0 || frames.size < count) return false
        val last = frames[frames.size - 1]
        if (classifyAssertFailure(last) != AssertFailKind.DETERMINISTIC) return false
        val message = last.debugMessage
        for (i in frames.size - count until frames.size) {
            val frame = frames[i]
            if (classifyAssertFailure(frame) != AssertFailKind.DETERMINISTIC) return false
            if (frame.debugMessage != message) return false
        }
        return true
    }

    /**
     * 单次轮询迭代后的终止裁决（纯逻辑，调用方每帧调一次）。
     *
     * 优先级：命中 → 连续确定性失败 → 画面静止 → 墙钟上限 → 帧数上限 → 继续。
     * [frames] 为已观测序列（含本帧），[elapsedMs] 为整轮已耗墙钟，[screenStatic] 由
     * [isScreenStatic] 给出。帧数上限与时间上限**先到者**生效。
     */
    fun decideAssertStop(
        frames: List<AssertFrame>,
        targetRect: MapRect,
        elapsedMs: Long,
        screenStatic: Boolean = false,
        maxFrames: Int = ASSERT_LOCATE_MAX_FRAMES,
        wallBudgetMs: Long = ASSERT_LOCATE_MAX_WALL_MS,
    ): AssertStopReason {
        val last = frames.lastOrNull() ?: return AssertStopReason.CONTINUE
        val pos = last.position
        if (last.located && pos != null && MapLocateActionPure.isPositionInsideRect(pos, targetRect)) {
            return AssertStopReason.MATCHED
        }
        if (hasDeterministicFailureStreak(frames)) return AssertStopReason.DETERMINISTIC_FAILURE
        if (screenStatic) return AssertStopReason.SCREEN_STATIC
        if (elapsedMs >= wallBudgetMs) return AssertStopReason.WALL_CLOCK_BUDGET
        if (frames.size >= maxFrames) return AssertStopReason.MAX_FRAMES
        return AssertStopReason.CONTINUE
    }

    /**
     * 上游 `MapLocateAssertLocationRun` 构造的 `LocateOptions`
     * （`MapLocateAction.cpp:431-433`）：`expected_zone_id = zone_id`、`force_global_search = true`。
     */
    fun buildAssertOptions(param: AssertLocationParam): LocateOptions =
        LocateOptions(expectedZoneId = param.zoneId, forceGlobalSearch = true)

    /** 上游 `TryBuildAssertRect`（`MapLocateAction.cpp:200-217`）。 */
    fun tryBuildAssertRect(param: AssertLocationParam): MapRect? = MapLocateActionPure.tryBuildAssertRect(param)
}

/** [MapLocateAssertPure.decideAssertStop] 的轮询终止原因。 */
enum class AssertStopReason {
    /** 尚未到停点，继续轮询。 */
    CONTINUE,

    /** 定位命中 `target` 矩形。 */
    MATCHED,

    /** 连续确定性失败，提前结束。 */
    DETERMINISTIC_FAILURE,

    /** 画面连续多帧静止，提前结束。 */
    SCREEN_STATIC,

    /** 墙钟上限到点。 */
    WALL_CLOCK_BUDGET,

    /** 帧数上限到点。 */
    MAX_FRAMES,
}

/**
 * 单帧失败的语义分类：区分「该继续等」与「等也没用」。
 *
 * 见 [MapLocateAssertPure.classifyAssertFailure]。
 */
enum class AssertFailKind {
    /** 该帧定位成功（成功且追踪接受）。 */
    SUCCESS,

    /** 等待态：当前画面还没有可定位地图 / 追踪未稳定 / 取帧暂失败，继续轮询可能变好。 */
    WAITING,

    /** 确定性失败：与目标 / 资产 / 设备几何绑定，同一画面再等也不会改变，可早停。 */
    DETERMINISTIC,
}
