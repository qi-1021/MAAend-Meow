package com.aliothmoon.maafw.remote

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 世界地图（大地图）找图标的**纯逻辑类型与几何**。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/WorldMap/WorldMapTypes.h`：
 *  - `ScreenMapRoi`（19-24）、`ViewportConfig`（27-69）、`SpotConfig`（72-105）、
 *    `IconSpec`（108-116）、`PlayerMarkerConfig`（121-126）、`Viewport`（130-152）、
 *    `SpotHit`（154-166）、`PlayerMarkerHit`（168-172）。
 *
 * 本文件只放**不碰 `cv::Mat`/文件系统/框架**的部分：坐标、相似变换、尺度阶梯、
 * 安全区/平移/拖动几何、置信判定。真正的多尺度 `matchTemplate`（读底图、开运行时模板、
 * 发 `MaaContextRunRecognition`）留在 [MaaRunner]。
 *
 * 行号均指 `WorldMapTypes.h` / `WorldMapSolver.cpp` 的原始行号。
 */

/** 上游 `ScreenMapRoi`（`WorldMapTypes.h:19-24`）：比例矩形（相对屏幕宽高）。 */
data class WorldMapScreenRoi(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/** 上游 `ViewportConfig`（`WorldMapTypes.h:27-69`）。 */
data class WorldMapViewportConfig(
    val roi: WorldMapScreenRoi = DEFAULT_ROI,
    val coarseDownscale: Int = 4,
    val scaleMin: Double = 0.30,
    val scaleMax: Double = 4.00,
    val coarseRatio: Double = 1.10,
    val fineSteps: Int = 15,
    /** 给了尺度就只在它附近解；null 走整条阶梯。 */
    val scaleHint: Double? = null,
    val scanSlack: Int = 24,
    val minScore: Double = 0.50,
    val minDelta: Double = 0.02,
    val voteGrid: Int = 3,
    val voteMinBlockSide: Int = 16,
) {
    companion object {
        /** 上游 `ViewportConfig` 默认 ROI（`WorldMapTypes.h:21-23`）。 */
        val DEFAULT_ROI = WorldMapScreenRoi(left = 0.26, top = 0.12, right = 0.64, bottom = 0.82)

        /** 上游 `ViewportConfig{}.voteGrid` 默认 3。 */
        const val DEFAULT_VOTE_GRID = 3
    }
}

/**
 * 上游 `SpotConfig`（`WorldMapTypes.h:72-105`）：一类图标的认法（模板 + 只对这张图成立的阈值）。
 */
data class WorldMapSpotConfig(
    val templates: List<String> = emptyList(),
    /** 浮动搜索半径（底图像素）；> 0 走浮动搜索，否则在期望位置开定点小窗（屏幕像素）。 */
    val radiusBase: Double = 0.0,
    val radiusScreen: Int = 40,
    val scaleMin: Double = 0.90,
    val scaleMax: Double = 1.35,
    val scaleStep: Double = 0.025,
    val minScore: Double = 0.55,
    /** 命中与期望位置的偏差上限（底图像素）。 */
    val gateBase: Double = 10.0,
    val saturationFloor: Int = 60,
    val minGoldRatio: Double = 0.0,
)

/** 上游 `IconSpec`（`WorldMapTypes.h:108-116`）。 */
data class WorldMapIconSpec(
    val spot: WorldMapSpotConfig = WorldMapSpotConfig(),
    val occludedByPlayer: Boolean = false,
)

/** 上游 `PlayerMarkerConfig`（`WorldMapTypes.h:121-126`）。 */
data class WorldMapPlayerMarkerConfig(
    val searchRadius: Int = 32,
    val whiteFloor: Int = 240,
    val minArea: Int = 60,
    val maxArea: Int = 300,
    val minSolidity: Double = 0.80,
)

/**
 * 上游 `Viewport`（`WorldMapTypes.h:130-152`）：屏幕↔底图的相似变换。
 * `base = (screen - roiOrigin) * scale + baseOrigin`。
 */
data class WorldMapViewport(
    val scale: Double,
    val roiOriginX: Double,
    val roiOriginY: Double,
    val baseOriginX: Double,
    val baseOriginY: Double,
    val roiWidth: Int,
    val roiHeight: Int,
    val score: Double,
    val delta: Double,
    val psr: Double,
    val voteGrid: Int,
) {
    /** 上游 `Viewport::toBase`。 */
    fun toBase(screenX: Double, screenY: Double): DoubleArray = doubleArrayOf(
        (screenX - roiOriginX) * scale + baseOriginX,
        (screenY - roiOriginY) * scale + baseOriginY,
    )

    /** 上游 `Viewport::toScreen`。 */
    fun toScreen(baseX: Double, baseY: Double): DoubleArray = doubleArrayOf(
        (baseX - baseOriginX) / scale + roiOriginX,
        (baseY - baseOriginY) / scale + roiOriginY,
    )
}

/** 上游 `SpotHit`（`WorldMapTypes.h:154-166`）。 */
data class WorldMapSpotHit(
    val templateName: String,
    val centerX: Double,
    val centerY: Double,
    /** 交回框架点击的那一点（图标本体最厚实处），屏幕坐标。 */
    val hotspotX: Double,
    val hotspotY: Double,
    val sizeWidth: Int,
    val sizeHeight: Int,
    val score: Double,
    val matchScale: Double,
    val offsetBase: Double,
    val goldRatio: Double,
    val unlocked: Boolean,
)

/** 上游 `PlayerMarkerHit`（`WorldMapTypes.h:168-172`）。 */
data class WorldMapPlayerMarkerHit(
    val centerX: Double,
    val centerY: Double,
    val area: Int,
    val solidity: Double,
)

/** 上游 `ScanRung`（`WorldMapSolver.cpp:184-188`）：粗解一档的尺度与分数。 */
data class WorldMapRung(val scale: Double, val score: Double)

/** 上游 `ScanHit` 的简化（`WorldMapSolver.cpp:175-182`）：一次单尺度匹配的峰。 */
data class WorldMapPeak(
    val score: Double,
    val locX: Double,
    val locY: Double,
    val sizeWidth: Int,
    val sizeHeight: Int,
    val psr: Double = 0.0,
)

object WorldMapTypes {

    /** 上游 `WorldMapFind.cpp:68` 的 `kIconArea`：大地图上图标可落区域（比例）。 */
    val ICON_AREA = WorldMapScreenRoi(left = 0.02, top = 0.13, right = 0.80, bottom = 0.85)

    /** 上游 `kIconMargin`（`WorldMapFind.cpp:71`）。 */
    const val ICON_MARGIN = 30

    /** 上游 `kMinTemplateSide`（`WorldMapSolver.cpp:25`）。 */
    const val MIN_TEMPLATE_SIDE = 24

    /** 上游 `kMinAnchorSide`（`WorldMapSolver.cpp:26`）。 */
    const val MIN_ANCHOR_SIDE = 6

    /** 上游 `kMaxPans`（`WorldMapFind.cpp:90`）。 */
    const val MAX_PANS = 16

    /** 上游 `kMaxNudges`（`WorldMapFind.cpp:108`）。 */
    const val MAX_NUDGES = 6

    /** 上游 `kNudgeRatio`（`WorldMapFind.cpp:109`）。 */
    const val NUDGE_RATIO = 0.35

    /** 上游 `kSwipeSpeed` / 时长上下限（`WorldMapFind.cpp:77-79`）。 */
    const val SWIPE_SPEED = 2.0
    const val SWIPE_DURATION_MIN = 100
    const val SWIPE_DURATION_MAX = 600

    /** 上游 `kSwipeSpanRatio`（`WorldMapFind.cpp:83`）。 */
    const val SWIPE_SPAN_RATIO = 0.85

    /** 上游 `kIconMargin` 之外，重试间隔（`WorldMapFind.cpp:87`）。 */
    const val RETRY_MILLIS = 350

    /** 上游 `kSettleMillis`（`WorldMapFind.cpp:86`）。 */
    const val SETTLE_MILLIS = 250

    /**
     * 上游 `WorldMapSolver::SafeArea`（`WorldMapSolver.cpp:505-515`）。
     *
     * ROI 按屏幕比例展开后各边内缩 [margin]，再与屏幕相交。展开后为空返回空 [MapRect]。
     */
    fun safeArea(screenWidth: Int, screenHeight: Int, roi: WorldMapScreenRoi, margin: Int): MapRect {
        val x0 = lround(screenWidth * roi.left) + margin
        val y0 = lround(screenHeight * roi.top) + margin
        val x1 = lround(screenWidth * roi.right) - margin
        val y1 = lround(screenHeight * roi.bottom) - margin
        if (x1 <= x0 || y1 <= y0) return MapRect()
        return MapRect(x0, y0, x1 - x0, y1 - y0)
            .intersect(MapRect(0, 0, screenWidth, screenHeight))
    }

    /**
     * 上游 `GeometricLadder`（`WorldMapSolver.cpp:190-200`）：`lo` 起按 [ratio] 等比到 `hi`。
     * `lo <= 0` 或 `ratio <= 1` 返回空。
     */
    fun geometricLadder(lo: Double, hi: Double, ratio: Double): List<Double> {
        if (lo <= 0.0 || ratio <= 1.0) return emptyList()
        val out = ArrayList<Double>()
        var s = lo
        while (s <= hi * (1.0 + 1e-6)) {
            out += s
            s *= ratio
        }
        return out
    }

    /**
     * 上游 `LinearLadder`（`WorldMapSolver.cpp:202-216`）：`[lo, hi]` 等分 [steps] 档。
     * `steps <= 0` 返回空；`steps == 1` 只返回 `lo`。
     */
    fun linearLadder(lo: Double, hi: Double, steps: Int): List<Double> {
        if (steps <= 0) return emptyList()
        if (steps == 1) return listOf(lo)
        val out = ArrayList<Double>(steps)
        for (i in 0 until steps) {
            out += lo + (hi - lo) * i / (steps - 1)
        }
        return out
    }

    /**
     * 上游 `ConfirmSpot` 的尺度阶梯（`WorldMapSolver.cpp:590-591`）：
     * `LinearLadder(scaleMin, scaleMax, round((max-min)/step)+1)`。
     */
    fun spotScaleLadder(cfg: WorldMapSpotConfig): List<Double> {
        val steps = lround((cfg.scaleMax - cfg.scaleMin) / cfg.scaleStep) + 1
        return linearLadder(cfg.scaleMin, cfg.scaleMax, steps)
    }

    /**
     * 上游 `ScanScales` 的尺寸闸（`WorldMapSolver.cpp:325`）：
     * 缩放后模板的最小边 >= [minSide]，且 `w + slack <= haystack.cols`、`h + slack <= haystack.rows`。
     */
    fun scaleFits(
        templateWidth: Int,
        templateHeight: Int,
        haystackWidth: Int,
        haystackHeight: Int,
        minSide: Int,
        slack: Int,
    ): Boolean {
        if (templateWidth < minSide || templateHeight < minSide) return false
        if (templateWidth + slack > haystackWidth) return false
        if (templateHeight + slack > haystackHeight) return false
        return true
    }

    /**
     * 上游 `ScanViewport` 的接受条件（`WorldMapSolver.cpp:451`）：
     * `fine.score >= minScore && delta >= minDelta`。
     */
    fun viewportAccepted(score: Double, delta: Double, cfg: WorldMapViewportConfig): Boolean =
        score >= cfg.minScore && delta >= cfg.minDelta

    /**
     * 上游 `ScanViewport` 的 rival 统计（`WorldMapSolver.cpp:443-449`）：
     * 与细解尺度相差超过 `6%·fineScale` 的粗解档里取最高分。
     */
    fun rivalBest(rungs: List<WorldMapRung>, fineScale: Double, toleranceRatio: Double = 0.06): Double {
        var best = 0.0
        for (rung in rungs) {
            if (abs(rung.scale - fineScale) > toleranceRatio * fineScale) {
                best = max(best, rung.score)
            }
        }
        return best
    }

    /**
     * 上游 `PanDelta`（`WorldMapFind.cpp:265-276`）：只把出了安全区的那根轴挪回中心。
     * 返回 `[dx, dy]`。
     */
    fun panDelta(safe: MapRect, expectedX: Double, expectedY: Double): DoubleArray {
        val centerX = safe.x + safe.width / 2.0
        val centerY = safe.y + safe.height / 2.0
        var needX = 0.0
        var needY = 0.0
        if (expectedX < safe.x || expectedX > safe.x + safe.width) needX = centerX - expectedX
        if (expectedY < safe.y || expectedY > safe.y + safe.height) needY = centerY - expectedY
        return doubleArrayOf(needX, needY)
    }

    /**
     * 上游 `NudgeDelta`（`WorldMapFind.cpp:279-297`）：视口解不出来时先回拖一半，
     * 没拖过就绕四个方向轮着试。返回 `[dx, dy]`。
     */
    fun nudgeDelta(safe: MapRect, lastX: Double, lastY: Double, index: Int): DoubleArray {
        if (index == 0 && hypot(lastX, lastY) >= 1.0) {
            return doubleArrayOf(-lastX / 2.0, -lastY / 2.0)
        }
        val sx = safe.width * NUDGE_RATIO
        val sy = safe.height * NUDGE_RATIO
        return when (index % 4) {
            0 -> doubleArrayOf(sx, 0.0)
            1 -> doubleArrayOf(0.0, sy)
            2 -> doubleArrayOf(-sx, 0.0)
            else -> doubleArrayOf(0.0, -sy)
        }
    }

    /**
     * 上游 `DragMap` 的钳位部分（`WorldMapFind.cpp:238-241`）：
     * 单次位移不超过安全区尺寸的 [SWIPE_SPAN_RATIO]。返回实际发出的 `[dx, dy]`；
     * 长度 < 1 表示不发。钳位后的位移仍用于算时长。
     */
    fun dragClamp(safe: MapRect, dx: Double, dy: Double): DoubleArray {
        val maxX = safe.width * SWIPE_SPAN_RATIO
        val maxY = safe.height * SWIPE_SPAN_RATIO
        val cx = dx.coerceIn(-maxX, maxX)
        val cy = dy.coerceIn(-maxY, maxY)
        if (hypot(cx, cy) < 1.0) return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf(cx, cy)
    }

    /**
     * 上游 `DragMap` 的时长（`WorldMapFind.cpp:253-254`）：
     * `clamp(hypot(dx,dy)/speed, min, max)`。
     */
    fun swipeDuration(dx: Double, dy: Double): Int =
        hypot(dx, dy).div(SWIPE_SPEED)
            .coerceIn(SWIPE_DURATION_MIN.toDouble(), SWIPE_DURATION_MAX.toDouble())
            .roundToInt()

    /**
     * 上游 `PointBox`（`WorldMapFind.cpp:299-307`）：四舍五入成 1×1 矩形。
     */
    fun pointBox(x: Double, y: Double): MapRect = MapRect(lround(x), lround(y), 1, 1)

    /**
     * 上游 `SpotBox`（`WorldMapFind.cpp:311-314`）：交图标本体那一点，不交整框。
     */
    fun spotBox(hit: WorldMapSpotHit): MapRect = pointBox(hit.hotspotX, hit.hotspotY)

    /** 上游 `wantUnlocked = param.state != "locked"`（`WorldMapFind.cpp:410`）。 */
    fun wantsUnlocked(state: String): Boolean = state != "locked"

    /**
     * 上游 `ConfirmSpot` 的判定圈闸（`WorldMapFind.cpp:686-690`）：
     * 定点（非浮动）点位偏差超过 [gateBase] 即判认错。
     */
    fun gateReject(offsetBase: Double, gateBase: Double, floating: Boolean): Boolean =
        !floating && offsetBase > gateBase

    /**
     * 上游 `ConfirmSpot` 的确认窗半径（`WorldMapSolver.cpp:661-663`）：
     * 浮动按 `radiusBase / viewportScale`，定点取 `max(radiusScreen, kMinTemplateSide)`。
     */
    fun confirmRadius(cfg: WorldMapSpotConfig, viewportScale: Double): Int {
        val floating = cfg.radiusBase > 0.0
        return if (floating) {
            if (viewportScale <= 0.0) MIN_TEMPLATE_SIDE
            else max(MIN_TEMPLATE_SIDE, lround(cfg.radiusBase / viewportScale))
        } else {
            max(cfg.radiusScreen, MIN_TEMPLATE_SIDE)
        }
    }

    /**
     * 上游 `ConfirmSpot` 的偏差（`WorldMapSolver.cpp:656`）：
     * 命中中心与期望位置的屏幕距离 × 视口尺度 = 底图像素偏差。
     */
    fun offsetBase(centerX: Double, centerY: Double, expectedX: Double, expectedY: Double, viewportScale: Double): Double =
        hypot(centerX - expectedX, centerY - expectedY) * viewportScale

    /**
     * 上游 `ConfirmSpot` 的热点折算（`WorldMapSolver.cpp:652`）：
     * `hotspot = center + 模板热点偏移 × matchScale`。
     */
    fun hotspot(
        centerX: Double,
        centerY: Double,
        hotspotOffsetX: Double,
        hotspotOffsetY: Double,
        matchScale: Double,
    ): DoubleArray = doubleArrayOf(
        centerX + hotspotOffsetX * matchScale,
        centerY + hotspotOffsetY * matchScale,
    )

    /**
     * OpenCV/上游 `std::clamp` 风格的整型钳位，附带 OpenCV `saturate_cast<int>` 的
     * 四舍五入语义（`cvRound`：`floor(x+0.5)`，负数对称）。
     */
    fun lround(value: Double): Int {
        val r = if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)
        if (r > Int.MAX_VALUE) return Int.MAX_VALUE
        if (r < Int.MIN_VALUE) return Int.MIN_VALUE
        return r.toInt()
    }

    /** 上游 `min`/`max` 的直观包装，供调用方少写 `kotlin.math`。 */
    fun clampInt(v: Int, lo: Int, hi: Int): Int = min(max(v, lo), hi)
}
