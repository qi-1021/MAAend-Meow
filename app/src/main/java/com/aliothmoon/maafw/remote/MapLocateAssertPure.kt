package com.aliothmoon.maafw.remote

/**
 * `MapLocateAssertLocation` 的**裁决纯逻辑**。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MapLocateAction.cpp:404-471`：
 * 重置追踪状态 → 最多 [ASSERT_LOCATE_MAX_FRAMES] 帧、每帧间隔
 * [ASSERT_LOCATE_POLL_DELAY_MS] 轮询定位（`force_global_search=true`、
 * `expected_zone_id=zone_id`）→ 定位成功且位置落在 `target` 矩形内即 matched。
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

    /** 上游 `kAssertLocateMaxFrames`（`MapLocateAction.cpp:229`）。 */
    const val ASSERT_LOCATE_MAX_FRAMES = 60

    /** 上游 `kAssertLocatePollDelay`（`MapLocateAction.cpp:230`），单位毫秒。 */
    const val ASSERT_LOCATE_POLL_DELAY_MS = 250L

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
     * 上游 `MapLocateAssertLocationRun` 构造的 `LocateOptions`
     * （`MapLocateAction.cpp:431-433`）：`expected_zone_id = zone_id`、`force_global_search = true`。
     */
    fun buildAssertOptions(param: AssertLocationParam): LocateOptions =
        LocateOptions(expectedZoneId = param.zoneId, forceGlobalSearch = true)

    /** 上游 `TryBuildAssertRect`（`MapLocateAction.cpp:200-217`）。 */
    fun tryBuildAssertRect(param: AssertLocationParam): MapRect? = MapLocateActionPure.tryBuildAssertRect(param)
}
