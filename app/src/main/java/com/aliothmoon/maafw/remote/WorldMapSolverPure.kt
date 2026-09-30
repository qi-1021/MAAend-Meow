package com.aliothmoon.maafw.remote

import kotlin.math.max
import kotlin.math.min

/**
 * 世界地图视口求解的**纯逻辑编排**：粗解尺度阶梯、粗解→细解搜索窗、细解尺度阶梯、
 * 视口组装与置信裁决。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/WorldMap/WorldMapSolver.cpp`：
 *  - `ScanViewport`（367-470）：粗解扫全尺度带 → 细解在粗解邻域定尺度 → 算 rival/delta
 *    → 组装 `Viewport`；
 *  - `ScanScales`（303-363）的尺寸闸与最优档选择；
 *  - `SolveViewport`（839-878）的降采样与 voteGrid 回退。
 *
 * 本文件**不跑匹配**：每个尺度上的 `cv::matchTemplate` 由 [MaaRunner] 通过框架
 * `TemplateMatch`（运行时模板）逐档调用，把 [WorldMapPeak] 交回这里的纯函数组装/裁决。
 * 这样「窗口算得对不对、置信门放不放行、坐标变换对不对」都能脱离设备单测。
 */
object WorldMapSolverPure {

    /** 一次粗解的记录：某档尺度上的峰（可能为 null，表示该档被尺寸闸筛掉或无解）。 */
    data class CoarseProbe(val scale: Double, val peak: WorldMapPeak?)

    /** 上游 `ScanViewport` 的粗解结果：最优峰与它的尺度。 */
    data class CoarseBest(val scale: Double, val peak: WorldMapPeak)

    /** 上游 `ScanViewport` 的细解计划。 */
    data class FinePlan(
        val window: MapRect,
        val scales: List<Double>,
    )

    /**
     * 上游 `ScanViewport` 的粗解尺度阶梯（`WorldMapSolver.cpp:381-383`）：
     *  - 有 `scaleHint` → `GeometricLadder(hint/ratio, hint*ratio, ratio)`；
     *  - 否则 `GeometricLadder(scaleMin, scaleMax, ratio)`。
     */
    fun coarseScales(cfg: WorldMapViewportConfig): List<Double> {
        val hint = cfg.scaleHint
        return if (hint != null) {
            WorldMapTypes.geometricLadder(hint / cfg.coarseRatio, hint * cfg.coarseRatio, cfg.coarseRatio)
        } else {
            WorldMapTypes.geometricLadder(cfg.scaleMin, cfg.scaleMax, cfg.coarseRatio)
        }
    }

    /**
     * 上游 `ScanScales` 在粗解降采样图上的尺寸闸（`WorldMapSolver.cpp:319-362`）：
     * 模板 = 降采样 ROI 按 `scale` 缩放；`slack = round(scanSlack/down)`。
     */
    fun coarseScalesFiltered(
        cfg: WorldMapViewportConfig,
        roiSmallWidth: Int,
        roiSmallHeight: Int,
        baseSmallWidth: Int,
        baseSmallHeight: Int,
    ): List<Double> {
        val down = max(1, cfg.coarseDownscale)
        val slack = max(1, WorldMapTypes.lround(cfg.scanSlack.toDouble() / down))
        return coarseScales(cfg).filter { scale ->
            WorldMapTypes.scaleFits(
                templateWidth = WorldMapTypes.lround(roiSmallWidth * scale),
                templateHeight = WorldMapTypes.lround(roiSmallHeight * scale),
                haystackWidth = baseSmallWidth,
                haystackHeight = baseSmallHeight,
                minSide = WorldMapTypes.MIN_TEMPLATE_SIDE,
                slack = slack,
            )
        }
    }

    /** 上游 `ScanScales` 取分最高一档（`WorldMapSolver.cpp:351-359`）。 */
    fun bestCoarse(probes: List<CoarseProbe>): CoarseBest? {
        var best: CoarseBest? = null
        for (probe in probes) {
            val peak = probe.peak ?: continue
            if (best == null || peak.score > best.peak.score) {
                best = CoarseBest(probe.scale, peak)
            }
        }
        return best
    }

    /** 上游 `ScanScales` 的粗解 rungs 收集（只含有解的档）。 */
    fun coarseRungs(probes: List<CoarseProbe>): List<WorldMapRung> =
        probes.mapNotNull { probe -> probe.peak?.let { WorldMapRung(probe.scale, it.score) } }

    /**
     * 上游细解搜索窗（`WorldMapSolver.cpp:403-417`）：回到原尺度，只覆盖粗解落点附近，
     * 两侧各留一个降采样格的不确定度 `pad = down*3`。
     *
     * 返回 null 表示窗口退化（`< kMinTemplateSide`）。
     */
    fun finePlan(
        cfg: WorldMapViewportConfig,
        baseWidth: Int,
        baseHeight: Int,
        roiWidth: Int,
        roiHeight: Int,
        coarse: CoarseBest,
    ): FinePlan? {
        val down = max(1, cfg.coarseDownscale)
        val pad = down * 3
        val span = coarse.scale * (cfg.coarseRatio - 1.0) * 1.2
        val windowX = max(0, WorldMapTypes.lround(coarse.peak.locX * down) - pad)
        val windowY = max(0, WorldMapTypes.lround(coarse.peak.locY * down) - pad)
        val windowW = min(
            WorldMapTypes.lround(roiWidth * (coarse.scale + span)) + pad * 2,
            baseWidth - windowX,
        )
        val windowH = min(
            WorldMapTypes.lround(roiHeight * (coarse.scale + span)) + pad * 2,
            baseHeight - windowY,
        )
        if (windowW < WorldMapTypes.MIN_TEMPLATE_SIDE || windowH < WorldMapTypes.MIN_TEMPLATE_SIDE) {
            return null
        }

        // 钉尺度时细解钉的是给定值本身：缩放变了就该让分数掉下去被拒
        val scales = if (cfg.scaleHint != null) {
            listOf(cfg.scaleHint)
        } else {
            WorldMapTypes.linearLadder(
                max(cfg.scaleMin, coarse.scale - span),
                coarse.scale + span,
                cfg.fineSteps,
            )
        }
        val filtered = scales.filter { scale ->
            WorldMapTypes.scaleFits(
                templateWidth = WorldMapTypes.lround(roiWidth * scale),
                templateHeight = WorldMapTypes.lround(roiHeight * scale),
                haystackWidth = windowW,
                haystackHeight = windowH,
                minSide = WorldMapTypes.MIN_TEMPLATE_SIDE,
                slack = 0,
            )
        }
        if (filtered.isEmpty()) return null
        return FinePlan(MapRect(windowX, windowY, windowW, windowH), filtered)
    }

    /**
     * 上游 `ScanViewport` 的结果组装与置信裁决（`WorldMapSolver.cpp:441-469`）：
     * `delta = fine.score - rivalBest`；`score < minScore || delta < minDelta` 即拒。
     *
     * [finePeak].locX/locY 是**窗口坐标**下的模板左上角，这里平移到 base 坐标。
     */
    fun buildViewport(
        cfg: WorldMapViewportConfig,
        roiRect: MapRect,
        window: MapRect,
        fineScale: Double,
        finePeak: WorldMapPeak,
        rungs: List<WorldMapRung>,
        voteGrid: Int,
    ): WorldMapViewport? {
        val delta = finePeak.score - WorldMapTypes.rivalBest(rungs, fineScale)
        if (!WorldMapTypes.viewportAccepted(finePeak.score, delta, cfg)) return null
        return WorldMapViewport(
            scale = fineScale,
            roiOriginX = roiRect.x.toDouble(),
            roiOriginY = roiRect.y.toDouble(),
            baseOriginX = window.x + finePeak.locX,
            baseOriginY = window.y + finePeak.locY,
            roiWidth = roiRect.width,
            roiHeight = roiRect.height,
            score = finePeak.score,
            delta = delta,
            psr = finePeak.psr,
            voteGrid = voteGrid,
        )
    }

    /**
     * 上游「上一拍解出的尺度」钉住（`WorldMapFind.cpp:436-443`）：
     * 拖动只挪地图、缩放不变，于是只搜位置、省掉整条尺度阶梯。
     */
    fun pinnedConfig(cfg: WorldMapViewportConfig, previousScale: Double): WorldMapViewportConfig =
        cfg.copy(scaleHint = previousScale)

    /** 视口解出来后，目标底图坐标对应的屏幕坐标（上游 `viewport.toScreen(target.at)`）。 */
    fun expectedScreen(viewport: WorldMapViewport, targetX: Double, targetY: Double): DoubleArray =
        viewport.toScreen(targetX, targetY)
}
