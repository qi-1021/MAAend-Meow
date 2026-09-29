package com.aliothmoon.maafw.remote

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 匹配策略的「结果验证」与峰值精修的纯逻辑。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MatchStrategy.cpp` 与
 * `MatchStrategy.h`。这里只移植**不碰 `cv::Mat`** 的部分：
 *  - `RefinePeakOffset`（68-77）——抛物线三点精修的偏移量；
 *  - `StandardMatchStrategy::validateTracking`（407-450）、`validateGlobalSearch`（452-459）；
 *  - `PathHeatmapMatchStrategy::validateTracking`（518-563）、`validateGlobalSearch`（565-572）；
 *  - `MatchStrategyFactory::create`（584-606）——只由 zoneId 与 [MatchMode] 决定策略。
 *
 * **未移植**：`RefinePeakSubpixel`/`RefinePeakContinuous` 的 `remap` 亚像素重采样、
 * `ExtractPathHeatmapFeature` 的逐像素热力图、模板预处理（均依赖 OpenCV）。
 * 工厂上游还收 `ImageProcessingConfig`（base/tier），那两个参数只服务图像预处理，
 * 在验证与策略选择里从不读取，故 Kotlin 签名省略。
 */

/** 上游 `MatchResultRaw`（`MatchStrategy.h:21-30`）。`loc` 拆成 [locX]/[locY]。 */
data class MatchResultRaw(
    val score: Double = -1.0,
    val locX: Double = 0.0,
    val locY: Double = 0.0,
    val secondScore: Double = -1.0,
    val delta: Double = 0.0,
    val psr: Double = 0.0,
)

/** 上游 `TrackingValidation`（`MatchStrategy.h:45-52`）。 */
data class TrackingValidation(
    val isValid: Boolean,
    val isEdgeSnapped: Boolean,
    val isTeleported: Boolean,
    val isScreenBlocked: Boolean,
    val absX: Double,
    val absY: Double,
)

/** 上游 `TemplateFeatureKind`（`MatchStrategy.h:37-43`）。ordinal 顺序一致。 */
enum class TemplateFeatureKind {
    STANDARD_BASE,
    STANDARD_TIER,
    PATH_HEATMAP_BASE,
    PATH_HEATMAP_TIER,
}

/** 上游 `MatchMode`（`MatchStrategy.h:83-88`）。 */
enum class MatchMode {
    AUTO,
    FORCE_STANDARD,
    FORCE_PATH_HEATMAP,
}

/**
 * 上游 `RefinePeakOffset`（`MatchStrategy.cpp:68-77`）。
 *
 * 三点抛物线偏移，分母接近 0 时返回 0，结果 clamp 到 `[-0.5, 0.5]`。
 * 入参与上游一致是 `float`（相关面为 CV_32F），内部按 double 计算。
 */
fun refinePeakOffset(prev: Float, center: Float, next: Float): Double {
    val p = prev.toDouble()
    val c = center.toDouble()
    val n = next.toDouble()
    val denom = p - 2.0 * c + n
    if (abs(denom) < 1e-12) {
        return 0.0
    }
    val offset = 0.5 * (p - n) / denom
    return offset.coerceIn(-0.5, 0.5)
}

/** 上游 `IMatchStrategy`（`MatchStrategy.h:54-81`）的纯逻辑子集。 */
sealed interface MatchStrategy {
    val isBase: Boolean
    val kind: TemplateFeatureKind

    fun validateTracking(
        trackResult: MatchResultRaw,
        dtSeconds: Double,
        lastPos: MapPosition?,
        searchRect: MapRect,
        templCols: Int,
        templRows: Int,
    ): TrackingValidation

    /** 上游以 `double& outScore` 出参表示成功；这里 `Double?`：null 即返回 false。 */
    fun validateGlobalSearch(fineRes: MatchResultRaw): Double?

    val needsChamferCompensation: Boolean
}

/** 上游 `StandardMatchStrategy`（`MatchStrategy.cpp:329-467`）。 */
class StandardMatchStrategy(
    override val isBase: Boolean,
    private val trackingCfg: TrackingConfig = TrackingConfig(),
    private val matchCfg: MatchConfig = MatchConfig(),
) : MatchStrategy {

    override val kind: TemplateFeatureKind
        get() = if (isBase) TemplateFeatureKind.STANDARD_BASE else TemplateFeatureKind.STANDARD_TIER

    override val needsChamferCompensation: Boolean = false

    /**
     * 上游 `Standard::validateTracking`（`MatchStrategy.cpp:407-450`）。
     *
     * 顺序：边缘吸附 → 绝对坐标（模板中心）→ 帧间速度（dt<0.001 抬到 0.001）→
     * 传送判定（> maxNormalSpeed）→ 分数硬下限 / 歧义 → 遮挡。
     */
    override fun validateTracking(
        trackResult: MatchResultRaw,
        dtSeconds: Double,
        lastPos: MapPosition?,
        searchRect: MapRect,
        templCols: Int,
        templRows: Int,
    ): TrackingValidation {
        val maxX = searchRect.width - templCols
        val maxY = searchRect.height - templRows
        val hitEdgeX = trackResult.locX <= trackingCfg.edgeSnapMargin ||
            trackResult.locX >= maxX - trackingCfg.edgeSnapMargin
        val hitEdgeY = trackResult.locY <= trackingCfg.edgeSnapMargin ||
            trackResult.locY >= maxY - trackingCfg.edgeSnapMargin
        val isEdgeSnapped = hitEdgeX || hitEdgeY

        val absX = searchRect.x + trackResult.locX + templCols / 2.0
        val absY = searchRect.y + trackResult.locY + templRows / 2.0

        var currentSpeed = 0.0
        if (lastPos != null) {
            val dx = absX - lastPos.x
            val dy = absY - lastPos.y
            val distanceMoved = sqrt(dx * dx + dy * dy)
            var dtSec = dtSeconds
            if (dtSec < 0.001) {
                dtSec = 0.001
            }
            currentSpeed = distanceMoved / dtSec
        }
        val isTeleported = currentSpeed > trackingCfg.maxNormalSpeed

        val belowHardFloor = trackResult.score < TRACKING_HARD_SCORE_FLOOR
        val lowScore = trackResult.score < 0.80
        val ambiguous = belowHardFloor || (lowScore && (trackResult.psr < 6.0 || trackResult.delta < 0.02))
        val isScreenBlocked = trackResult.score < trackingCfg.screenBlockedThreshold

        return TrackingValidation(
            isValid = !isEdgeSnapped && !isTeleported && !isScreenBlocked && !ambiguous,
            isEdgeSnapped = isEdgeSnapped,
            isTeleported = isTeleported,
            isScreenBlocked = isScreenBlocked,
            absX = absX,
            absY = absY,
        )
    }

    /** 上游 `Standard::validateGlobalSearch`（`MatchStrategy.cpp:452-459`）。 */
    override fun validateGlobalSearch(fineRes: MatchResultRaw): Double? {
        if (fineRes.score < matchCfg.passThreshold) {
            return null
        }
        return fineRes.score
    }
}

/** 上游 `PathHeatmapMatchStrategy`（`MatchStrategy.cpp:469-582`）。 */
class PathHeatmapMatchStrategy(
    override val isBase: Boolean,
    private val trackingCfg: TrackingConfig = TrackingConfig(),
    private val matchCfg: MatchConfig = MatchConfig(),
) : MatchStrategy {

    override val kind: TemplateFeatureKind
        get() = if (isBase) TemplateFeatureKind.PATH_HEATMAP_BASE else TemplateFeatureKind.PATH_HEATMAP_TIER

    override val needsChamferCompensation: Boolean = true

    /**
     * 上游 `PathHeatmap::validateTracking`（`MatchStrategy.cpp:518-563`）。
     *
     * 边缘吸附/速度/传送与 Standard 相同；差异在分数裁决：
     * `accept` 四条豁免，`hold` 两条兜底，`ambiguous = !accept`，
     * `screenBlocked = !accept && !hold`。因为 `ambiguous = !accept`，
     * `isValid` 实质要求 `accept` 为真。
     */
    override fun validateTracking(
        trackResult: MatchResultRaw,
        dtSeconds: Double,
        lastPos: MapPosition?,
        searchRect: MapRect,
        templCols: Int,
        templRows: Int,
    ): TrackingValidation {
        val maxX = searchRect.width - templCols
        val maxY = searchRect.height - templRows
        val hitEdgeX = trackResult.locX <= trackingCfg.edgeSnapMargin ||
            trackResult.locX >= maxX - trackingCfg.edgeSnapMargin
        val hitEdgeY = trackResult.locY <= trackingCfg.edgeSnapMargin ||
            trackResult.locY >= maxY - trackingCfg.edgeSnapMargin
        val isEdgeSnapped = hitEdgeX || hitEdgeY

        val absX = searchRect.x + trackResult.locX + templCols / 2.0
        val absY = searchRect.y + trackResult.locY + templRows / 2.0

        var currentSpeed = 0.0
        if (lastPos != null) {
            val dx = absX - lastPos.x
            val dy = absY - lastPos.y
            val distanceMoved = sqrt(dx * dx + dy * dy)
            var dtSec = dtSeconds
            if (dtSec < 0.001) {
                dtSec = 0.001
            }
            currentSpeed = distanceMoved / dtSec
        }
        val isTeleported = currentSpeed > trackingCfg.maxNormalSpeed

        val s = trackResult.score
        val d = trackResult.delta
        val psr = trackResult.psr
        val accept = (s >= 0.85) ||
            (s >= 0.70 && d >= 0.25 && psr >= 2.0) ||
            (s >= 0.42 && d >= 0.04 && psr >= 3.8) ||
            (s >= 0.40 && d >= 0.05 && psr >= 3.8)
        val hold = (s >= 0.70 && d >= 0.25 && psr >= 2.0) || (s >= 0.35 && psr >= 4.0)

        val ambiguous = !accept
        val isScreenBlocked = !accept && !hold

        return TrackingValidation(
            isValid = !isEdgeSnapped && !isTeleported && !isScreenBlocked && !ambiguous,
            isEdgeSnapped = isEdgeSnapped,
            isTeleported = isTeleported,
            isScreenBlocked = isScreenBlocked,
            absX = absX,
            absY = absY,
        )
    }

    /** 上游 `PathHeatmap::validateGlobalSearch`（`MatchStrategy.cpp:565-572`）。 */
    override fun validateGlobalSearch(fineRes: MatchResultRaw): Double? {
        if (fineRes.score < matchCfg.passThreshold) {
            return null
        }
        return fineRes.score
    }
}

/** 上游 `MatchStrategyFactory`（`MatchStrategy.h:90-100` + `.cpp:584-606`）。 */
object MatchStrategyFactory {

    /**
     * 上游 `MatchStrategyFactory::create`（`MatchStrategy.cpp:584-606`）。
     *
     * `isBase = zoneId.contains("Base")`；`usePathHeatmap = IsPathHeatmapZone(zoneId)`，
     * 再被 [mode] 强制覆盖（ForcePathHeatmap → true，ForceStandard → false）。
     */
    fun create(
        zoneId: String,
        trackingCfg: TrackingConfig = TrackingConfig(),
        matchCfg: MatchConfig = MatchConfig(),
        mode: MatchMode = MatchMode.AUTO,
    ): MatchStrategy {
        val isBase = zoneId.contains("Base")
        var usePathHeatmap = isPathHeatmapZone(zoneId)

        if (mode == MatchMode.FORCE_PATH_HEATMAP) {
            usePathHeatmap = true
        }
        if (mode == MatchMode.FORCE_STANDARD) {
            usePathHeatmap = false
        }

        return if (usePathHeatmap) {
            PathHeatmapMatchStrategy(isBase, trackingCfg, matchCfg)
        } else {
            StandardMatchStrategy(isBase, trackingCfg, matchCfg)
        }
    }
}
