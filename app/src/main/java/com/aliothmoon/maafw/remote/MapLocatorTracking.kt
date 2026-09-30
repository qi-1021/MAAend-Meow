package com.aliothmoon.maafw.remote

import java.util.Locale
import kotlin.math.hypot

/**
 * MapLocator 的**追踪状态机**：吃「本帧位置 + 分数 + 时间戳」，吐
 * accept / reject / hold / relocate 与当前稳定位置。
 *
 * 这是 MapLocator 移植剩下的最后一片：既有的 [MotionTracker]（速度 EMA / 丢失计数 /
 * 预测窗）、[PositionStabilizer]（位置稳定化）、[MapLocatorPure.isTightCluster]（冷启动
 * 共识）、[MatchStrategy.validateTracking]（追踪帧验证）都已经实现，本文件只把它们
 * **接成一条状态机**，不重复任何已有判定。
 *
 * 上游对应 `MapLocator.cpp` 里分居两处的状态：
 *  - **全局观测尾段**（`locate`，`MapLocator.cpp:2094-2137`）：远跳拒绝、高置信覆盖、
 *    冷启动共识、重锚点、`acceptPosition`。入口 [feed]。
 *  - **追踪帧**（`tryTracking`，`MapLocator.cpp:1069-1192`）：`validateTracking`、
 *    歧义 hold、离群 hold、`acceptPosition`。入口 [feedTracking]。
 *
 * 两条入口操作**同一份状态**（`MotionTracker` / `PositionStabilizer` / `coldStartBuffer`），
 * 正如上游 `tryTracking` 与 `locate` 共享同一组成员。丢失上限普通区
 * [MAX_LOST_TRACKING_COUNT]（3）、路径网区 [PATH_HEATMAP_MAX_LOST_TRACKING_COUNT]（10）。
 *
 * 时间统一用**单调时钟秒数** [Double]（对齐 [MotionTracker]）。
 *
 * 不碰 `cv::Mat` / Android / 文件系统，可在 [scripts/verify_pure_logic.sh] 本机回归。
 */

/** 状态机对一帧观测的裁决。 */
enum class TrackingAction {
    /** 接受：位置已稳定化并喂入 [MotionTracker]，交付 [TrackingDecision.position]。 */
    ACCEPT,

    /** 拒绝：本帧不可信且无位置可交付（远跳 / 冷启动未共识 / 无效追踪帧）。 */
    REJECT,

    /** 保持：沿用上一帧坐标交付（歧义 / 离群 / 遮挡），已记一次丢失。 */
    HOLD,

    /** 重新定位：丢失计数超上限，已清空状态，需本帧改走全局搜索重新观测。 */
    RELOCATE,
}

/**
 * 一帧裁决结果。
 *
 * [position]：ACCEPT 为稳定化后的位置；HOLD 为被保持的上一帧位置；REJECT / RELOCATE 为 null。
 */
data class TrackingDecision(
    val action: TrackingAction,
    val position: MapPosition?,
    val reason: String,
    val coldStartFrames: Int,
    val lostCount: Int,
) {
    val accepted: Boolean get() = action == TrackingAction.ACCEPT
}

/** 状态机只读快照，供调试 CLI 打印。 */
data class TrackingStatus(
    val zoneId: String,
    val stablePosition: MapPosition?,
    val lastKnownPosition: MapPosition?,
    val lostCount: Int,
    val maxAllowedLost: Int,
    val coldStartFrames: Int,
    val isTracking: Boolean,
)

class MapLocatorTracking(
    private val trackingCfg: TrackingConfig = TrackingConfig(),
    private val matchCfg: MatchConfig = MatchConfig(),
    private val mode: MatchMode = MatchMode.AUTO,
) {

    private val motionTracker = MotionTracker(trackingCfg)
    private val stabilizer = PositionStabilizer()

    /** 上游成员 `std::vector<MapPosition> coldStartBuffer`（`MapLocator.cpp:817`）。 */
    private val coldStartBuffer = mutableListOf<MapPosition>()

    /** 上游成员 `std::string currentZoneId`（`MapLocator.cpp:799`）。 */
    private var zoneId: String = ""

    val stablePosition: MapPosition? get() = stabilizer.stablePosition

    val lostCount: Int get() = motionTracker.getLostCount()

    fun reset() {
        motionTracker.forceLost()
        motionTracker.clearVelocity()
        stabilizer.reset()
        coldStartBuffer.clear()
        zoneId = ""
    }

    /**
     * 上游各处的丢失上限（`MapLocator.cpp:1008` 用 `currentZoneId`；`2036` 用 `targetZoneId`）。
     * 纯逻辑下统一由传入 zone 决定：路径网区 10，其余 [MAX_LOST_TRACKING_COUNT]。
     */
    fun maxAllowedLost(zone: String): Int =
        if (isPathHeatmapZone(zone)) PATH_HEATMAP_MAX_LOST_TRACKING_COUNT else MAX_LOST_TRACKING_COUNT

    /**
     * **全局观测**入口：对应上游 `locate` 尾段的追踪裁决
     * （`MapLocator.cpp:2094-2137`）。
     *
     * 顺序：`None` 遮挡占位 → 相对上一帧的 `hasLast` / 换区 → 远跳拒绝（低分）→
     * 冷启动共识（无上一帧且非高置信）→ 重锚点（远跳 / 换区重置稳定器与速度）→
     * `acceptPosition`（稳定化 + 喂追踪器）。
     *
     * @param raw 本帧全局搜索得到的**绝对地图坐标**（模板中心），[MapPosition.score] 为匹配分。
     * @param now 单调时钟秒数。
     */
    fun feed(raw: MapPosition, now: Double): TrackingDecision {
        // 上游 None 分支（MapLocator.cpp:2010-2023）：遮挡占位，只保留上一帧，不推进。
        if (raw.zoneId == "None") {
            motionTracker.getLastPos()?.let { motionTracker.hold(it, now) }
            return decision(TrackingAction.HOLD, motionTracker.getLastPos(), "zone None (occluded)")
        }

        val maxLost = maxAllowedLost(raw.zoneId)
        val lastPos = motionTracker.getLastPos()
        val zoneChanged = zoneId != raw.zoneId
        val hasLast = lastPos != null && !zoneChanged
        val jumpDist = if (hasLast) hypot(raw.x - lastPos!!.x, raw.y - lastPos!!.y) else 0.0
        val farJump = hasLast && jumpDist > FAR_JUMP_REJECT_DISTANCE
        val highConf = raw.score >= HIGH_CONFIDENCE_OVERRIDE

        // 远跳拒绝：低置信的跨帧大跳（MapLocator.cpp:2098-2110）。
        if (farJump && !highConf) {
            motionTracker.markLost()
            if (motionTracker.getLostCount() > maxLost) {
                forceRelocate()
                return decision(TrackingAction.RELOCATE, null, "far-jump rejected, lost>$maxLost")
            }
            return decision(TrackingAction.REJECT, null, "far-jump rejected")
        }

        // 冷启动共识：无上一帧且非高置信时，攒末尾 N 帧紧簇（MapLocator.cpp:2112-2123）。
        if (!hasLast && !highConf) {
            if (coldStartBuffer.size >= COLD_START_CONSENSUS_FRAMES) {
                coldStartBuffer.removeAt(0)
            }
            coldStartBuffer.add(raw)
            if (!MapLocatorPure.isTightCluster(coldStartBuffer, POSITION_CONSENSUS_RADIUS)) {
                return decision(TrackingAction.REJECT, null, COLD_START_COLLECTING_MESSAGE)
            }
        }
        coldStartBuffer.clear()

        // 远跳 / 换区：重锚点并清速度，避免把修正当成一次高速位移（MapLocator.cpp:2126-2132）。
        if (farJump || zoneChanged) {
            stabilizer.reset()
            motionTracker.clearVelocity()
        }

        zoneId = raw.zoneId
        val accepted = stabilizer.accept(raw, motionTracker, now)
        return TrackingDecision(
            action = TrackingAction.ACCEPT,
            position = accepted,
            reason = if (farJump) "global high-conf reseed" else "global accepted",
            coldStartFrames = 0,
            lostCount = motionTracker.getLostCount(),
        )
    }

    /**
     * **追踪帧**入口：对应上游 `tryTracking` 的裁决尾段
     * （`MapLocator.cpp:1069-1192`）。
     *
     * 先要求 `currentZoneId` 非空且追踪器仍在容错内；再用 [MatchStrategy.validateTracking]
     * 验证（策略由 zone 经 [MatchStrategyFactory] 决定）。随后：
     *  - 仅歧义（非遮挡/非边缘/非传送）→ HOLD 上一帧并记丢失；
     *  - 完全无效 → REJECT；
     *  - 有效但低分离群（跳变 > [TRACKING_OUTLIER_DISTANCE]）→ HOLD 并记丢失；
     *  - 否则 `acceptPosition` → ACCEPT。
     *
     * @param trackResult 追踪窗内的原始匹配结果（loc 为窗内左上角）。
     * @param searchRect 追踪搜索窗（绝对坐标），用于算 [TrackingValidation.absX]/[absY]。
     * @param templCols 缩放后模板宽（上游 `scaledTempl.cols`）。
     * @param templRows 缩放后模板高。
     * @param now 单调时钟秒数。
     */
    fun feedTracking(
        trackResult: MatchResultRaw,
        searchRect: MapRect,
        templCols: Int,
        templRows: Int,
        now: Double,
    ): TrackingDecision {
        val maxLost = maxAllowedLost(zoneId)
        if (zoneId.isEmpty() || !motionTracker.isTracking(maxLost)) {
            return decision(TrackingAction.RELOCATE, null, "tracker unavailable")
        }

        val strategy = MatchStrategyFactory.create(zoneId, trackingCfg, matchCfg, mode)
        val dt = now - motionTracker.getLastTime()
        val validation = strategy.validateTracking(
            trackResult,
            dt,
            motionTracker.getLastPos(),
            searchRect,
            templCols,
            templRows,
        )

        val onlyAmbiguous = !validation.isScreenBlocked && !validation.isEdgeSnapped && !validation.isTeleported

        // 歧义帧：交付上一帧坐标 + 记丢失（MapLocator.cpp:1148-1159）。
        if (onlyAmbiguous && motionTracker.isTracking(maxLost) && !validation.isValid) {
            val held = motionTracker.getLastPos()!!.copy(score = trackResult.score)
            motionTracker.hold(held, now)
            motionTracker.markLost()
            return decision(TrackingAction.HOLD, held, "tracking ambiguous -> hold")
        }
        if (!validation.isValid) {
            return decision(TrackingAction.REJECT, null, "tracking invalid")
        }

        // 坐标离群：跳变大且分数不足以支撑（MapLocator.cpp:1166-1182）。
        val last = motionTracker.getLastPos()
        if (last != null && trackResult.score < TRACKING_OUTLIER_MIN_SCORE) {
            val jumpDist = hypot(validation.absX - last.x, validation.absY - last.y)
            if (jumpDist > TRACKING_OUTLIER_DISTANCE) {
                val held = last.copy(score = trackResult.score)
                motionTracker.hold(held, now)
                motionTracker.markLost()
                return decision(TrackingAction.HOLD, held, "tracking outlier -> hold")
            }
        }

        val pos = MapPosition(
            zoneId = zoneId,
            x = validation.absX,
            y = validation.absY,
            score = trackResult.score,
        )
        val accepted = stabilizer.accept(pos, motionTracker, now)
        return TrackingDecision(
            action = TrackingAction.ACCEPT,
            position = accepted,
            reason = "tracking accepted",
            coldStartFrames = 0,
            lostCount = motionTracker.getLostCount(),
        )
    }

    /** 只读状态快照。 */
    fun status(): TrackingStatus {
        val maxLost = maxAllowedLost(zoneId)
        return TrackingStatus(
            zoneId = zoneId,
            stablePosition = stabilizer.stablePosition,
            lastKnownPosition = motionTracker.getLastPos(),
            lostCount = motionTracker.getLostCount(),
            maxAllowedLost = maxLost,
            coldStartFrames = coldStartBuffer.size,
            isTracking = zoneId.isNotEmpty() && motionTracker.isTracking(maxLost),
        )
    }

    /** 调试用多行状态，供 `tracklocate` 直接打印。 */
    fun describe(): List<String> {
        val s = status()
        return listOf(
            "track state: zone=${s.zoneId.ifEmpty { "-" }} tracking=${s.isTracking} " +
                "lost=${s.lostCount}/${s.maxAllowedLost} cold_start=${s.coldStartFrames}/$COLD_START_CONSENSUS_FRAMES",
            "track stable: ${format(s.stablePosition)}",
            "track last: ${format(s.lastKnownPosition)}",
        )
    }

    /** 丢失超上限：清空全部状态，返回 RELOCATE（上游 `forceLost` + 各 reset）。 */
    private fun forceRelocate() {
        motionTracker.forceLost()
        motionTracker.clearVelocity()
        stabilizer.reset()
        coldStartBuffer.clear()
    }

    private fun decision(action: TrackingAction, position: MapPosition?, reason: String): TrackingDecision {
        return TrackingDecision(
            action = action,
            position = position,
            reason = reason,
            coldStartFrames = coldStartBuffer.size,
            lostCount = motionTracker.getLostCount(),
        )
    }

    private fun format(p: MapPosition?): String =
        p?.let { String.format(Locale.US, "(%.2f,%.2f,score=%.3f)", it.x, it.y, it.score) } ?: "-"
}
