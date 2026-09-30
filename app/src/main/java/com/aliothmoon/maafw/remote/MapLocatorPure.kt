package com.aliothmoon.maafw.remote

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `MapLocator.cpp` 里的几何/映射/裁决纯逻辑。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MapLocator.cpp`，
 * 以及 `MapTypes.h` 中的模板缩放/热力图分区判定。全部不碰 `cv::Mat`：唯一会
 * 出现像素尺寸的地方是 [buildSearchConstraint]（只用 zone 底图的 cols/rows）。
 *
 * 所有函数注释标注上游行号。`buildSearchConstraint` 的 `zones` 入参用
 * [MapDimensions]（cols/rows）代替上游 `zones` 里的 `cv::Mat`。
 */
object MapLocatorPure {

    /**
     * 上游 `TrimLeadingZeros`（`MapLocator.cpp:42-46`）。
     *
     * `erase(0, min(find_first_not_of('0'), size()-1))` 的语义：
     *  - 非全零：切到第一个非 '0' 字符；
     *  - 全零串：保留**最后一个** '0'（`"000" -> "0"`）；
     *  - 空串：仍为空。
     */
    fun trimLeadingZeros(value: String): String {
        if (value.isEmpty()) return value
        val firstNonZero = value.indexOfFirst { it != '0' }
        val cut = if (firstNonZero == -1) value.length - 1 else min(firstNonZero, value.length - 1)
        return value.substring(cut)
    }

    /**
     * 上游 `MatchesExpectedZoneSelector`（`MapLocator.cpp:56-66`）。
     *
     * selector 为空视为匹配；否则 zone_id / base_class / raw_class 任一全等，
     * 或 raw_class 以 selector 为前缀即匹配。注意：**不做** zone_id 前缀匹配。
     */
    fun matchesExpectedZoneSelector(expectedZoneSelector: String, coarse: YoloCoarseResult): Boolean {
        if (expectedZoneSelector.isEmpty()) return true
        if (coarse.zoneId == expectedZoneSelector ||
            coarse.baseClass == expectedZoneSelector ||
            coarse.rawClass == expectedZoneSelector
        ) {
            return true
        }
        return coarse.rawClass.startsWith(expectedZoneSelector)
    }

    /**
     * `YOLO 约束未通过` 的**可诊断消息**。
     *
     * 上游这条失败只记 `"YOLO is confident but zone validation failed"`（`MapLocator.cpp:2010`），
     * 不带实际识别到的 zone。真机排查时「期望 base、画面却是另一个 region」与「期望 base、
     * 画面是同一 base 的 tier」都落进同一条消息，无法区分，只能回头翻帧。
     *
     * 这里把 `expected` / `actual`（`coarse.zoneId`）/ `class`（`coarse.rawClass`）/
     * `base`（`coarse.baseClass`）一并写进消息。**前缀保持 `YOLO 约束未通过`**，
     * 以对齐 [MapLocateAssertPure] 的确定性失败前缀判定与既有测试。
     */
    fun describeZoneValidationFailure(targetZoneId: String, coarse: YoloCoarseResult): String =
        "YOLO 约束未通过（zone 不匹配 selector: expected=$targetZoneId actual=${coarse.zoneId} " +
            "class=${coarse.rawClass} base=${coarse.baseClass}）"

    /**
     * 上游 `NormalizeExpectedZoneId`（`MapLocator.cpp:68-74`）。
     *
     * selector 为空或转换器缺失时原样返回，否则走 YOLO 名字→zoneId 的转换。
     * 上游以 `YoloPredictor*` 为载体，这里解耦成 [converter]（YoloPredictor 的
     * 纯逻辑替身见 `YoloMapping.kt`）。
     */
    fun normalizeExpectedZoneId(expectedZoneSelector: String, converter: ((String) -> String)?): String {
        if (expectedZoneSelector.isEmpty() || converter == null) return expectedZoneSelector
        return converter(expectedZoneSelector)
    }

    /**
     * 上游 `GlobalSearchRoiPad`（`MapLocator.cpp:143-148`）。
     *
     * `disc = min(w,h) - 2*kMinMaskBorderMargin(8) + 1`，clamp 到 `[1, max(w,h)]`，
     * 结果 `ceil(side/2.0) + 1`。
     */
    fun globalSearchRoiPad(templWidth: Int, templHeight: Int): Int {
        val discSide = min(templWidth, templHeight) - 2 * MIN_MASK_BORDER_MARGIN + 1
        val side = discSide.coerceIn(1, max(templWidth, templHeight))
        return ceil(side / 2.0).toInt() + 1
    }

    /**
     * 上游 `SearchConstraintsEqual`（`MapLocator.cpp:515-518`）。
     * 纯逻辑下就是 data class 的结构相等（mode + roi + yolo_validated）。
     */
    fun searchConstraintsEqual(lhs: SearchConstraint, rhs: SearchConstraint): Boolean =
        lhs.mode == rhs.mode && lhs.roi == rhs.roi && lhs.yoloValidated == rhs.yoloValidated

    /**
     * 上游 `IsTightCluster`（`MapLocator.cpp:680-689`）。
     *
     * 至少 [COLD_START_CONSENSUS_FRAMES] 帧才判定；以最后一帧为参考，
     * **末尾 3 帧**全部落在 [radius] 内（含参考帧自身）才算紧簇。
     */
    fun isTightCluster(buf: List<MapPosition>, radius: Double): Boolean {
        if (buf.size < COLD_START_CONSENSUS_FRAMES) return false
        val ref = buf.last()
        return buf.subList(buf.size - COLD_START_CONSENSUS_FRAMES, buf.size)
            .all { kotlin.math.hypot(it.x - ref.x, it.y - ref.y) <= radius }
    }

    /**
     * 上游 `QuantizeToHundredth`（`MapLocator.cpp:691-694`）。
     *
     * `std::round(value * 100.0) / 100.0`。**取整方向**是与 Kotlin 最易踩坑处：
     * `std::round` 是「四舍五入、遇 .5 远离零」，而 `kotlin.math.round` 是
     * 银行家舍入（ties-to-even），`Math.round` 又是 ties-toward-positive。
     * 这里显式用 `floor(x+0.5)` / `ceil(x-0.5)` 精确复刻 `std::round`。
     */
    fun quantizeToHundredth(value: Double): Double {
        val scaled = value * 100.0
        val rounded = if (scaled >= 0.0) floor(scaled + 0.5) else ceil(scaled - 0.5)
        return rounded / 100.0
    }

    /**
     * 上游 `QuantizePosition`（`MapLocator.cpp:696-701`）：只量化 x/y，其余字段原样。
     */
    fun quantizePosition(position: MapPosition): MapPosition =
        position.copy(
            x = quantizeToHundredth(position.x),
            y = quantizeToHundredth(position.y),
        )

    /** 上游 `kMinMaskBorderMargin`（`MapLocator.cpp:140`）。 */
    const val MIN_MASK_BORDER_MARGIN = 8
}

/** 上游 `zones` 里底图的尺寸（`cv::Mat::cols/rows`），[buildSearchConstraint] 只用到这两个。 */
data class MapDimensions(val cols: Int, val rows: Int)

/**
 * 上游 `MapLocator::Impl::buildSearchConstraint`（`MapLocator.cpp:1568-1627`）。
 *
 * 只用 YOLO 结果、selector 与目标 zone 的 `cols/rows`，不碰像素，故可单测。
 * `zones` 对应上游成员 `zones`（zoneId -> cv::Mat），这里退化为尺寸表。
 *
 * @param emitLog 上游控制日志开关，纯逻辑下无副作用，仅为签名对齐。
 */
fun buildSearchConstraint(
    expectedZoneSelector: String,
    targetZoneId: String,
    coarse: YoloCoarseResult,
    zones: Map<String, MapDimensions>,
    emitLog: Boolean = false,
): SearchConstraint {
    var constraint = SearchConstraint()
    if (!coarse.valid) {
        return constraint
    }

    constraint = constraint.copy(
        yoloValidated = coarse.zoneId == targetZoneId &&
            MapLocatorPure.matchesExpectedZoneSelector(expectedZoneSelector, coarse),
    )
    if (!constraint.yoloValidated) {
        return constraint
    }

    if (isPathHeatmapZone(targetZoneId)) {
        return constraint.copy(mode = GlobalSearchMode.FULL_MAP_FINE)
    }

    if (!coarse.hasRoi) {
        return constraint.copy(mode = GlobalSearchMode.FULL_MAP_FINE)
    }

    val zone = zones[targetZoneId] ?: return constraint

    val mapBounds = MapRect(0, 0, zone.cols, zone.rows)
    val expandedRoi = MapRect(
        x = coarse.roiX - coarse.inferMargin,
        y = coarse.roiY - coarse.inferMargin,
        width = coarse.roiW + coarse.inferMargin * 2,
        height = coarse.roiH + coarse.inferMargin * 2,
    )
    val constrainedRoi = expandedRoi.intersect(mapBounds)
    if (constrainedRoi.isEmpty) {
        return constraint
    }

    return constraint.copy(mode = GlobalSearchMode.ROI_FINE, roi = constrainedRoi)
}

/**
 * 上游 `MapLocator::Impl::stabilizePosition` / `acceptPosition`
 * （`MapLocator.cpp:959-993`）。
 *
 * `stablePosition` 是跨帧状态；`forceLost` 时会 `reset()`（`MapLocator.cpp:1397` 等）。
 */
class PositionStabilizer {

    /** 对应上游成员 `std::optional<MapPosition> stablePosition`。 */
    var stablePosition: MapPosition? = null
        private set

    /** 上游各处的 `stablePosition.reset()`。 */
    fun reset() {
        stablePosition = null
    }

    /**
     * 上游 `stabilizePosition`（`MapLocator.cpp:959-986`）。
     *
     * - `zoneId == "None"`：**原样返回且不写 stablePosition**；
     * - 首次或换区：量化后重锚点；
     * - 同区：`score >= kStableMinScore(0.80)` 时，距离 `< kStableReleaseDist(0.40)`
     *   就把坐标钉回 stablePosition（注意 `dist <= kStableDeadband(0.15)` 的分支
     *   与它输出完全一致，被包含；等价于「< 0.40」），其余字段用本帧 raw；
     * - 否则量化后重锚点。
     */
    fun stabilize(raw: MapPosition): MapPosition {
        if (raw.zoneId == "None") {
            return raw
        }

        val stable = stablePosition
        if (stable == null || stable.zoneId != raw.zoneId) {
            val quantized = MapLocatorPure.quantizePosition(raw)
            stablePosition = quantized
            return quantized
        }

        val dist = kotlin.math.hypot(raw.x - stable.x, raw.y - stable.y)
        // 死区分支：与下面的 < release 分支输出相同（都是钉回 stable 的 x/y）。
        if (raw.score >= STABLE_MIN_SCORE && dist <= STABLE_DEADBAND) {
            return raw.copy(x = stable.x, y = stable.y)
        }
        if (raw.score >= STABLE_MIN_SCORE && dist < STABLE_RELEASE_DIST) {
            return raw.copy(x = stable.x, y = stable.y)
        }

        val quantized = MapLocatorPure.quantizePosition(raw)
        stablePosition = quantized
        return quantized
    }

    /**
     * 上游 `acceptPosition`（`MapLocator.cpp:988-993`）：先稳定化，再把稳定结果喂给追踪器。
     */
    fun accept(raw: MapPosition, motionTracker: MotionTracker, now: Double): MapPosition {
        val stable = stabilize(raw)
        motionTracker.update(stable, now)
        return stable
    }
}
