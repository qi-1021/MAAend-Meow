package com.aliothmoon.maafw.remote

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * MapLocator **局部亚像素精修**的纯逻辑层。
 *
 * 背景：粗搜已改为框架 `TemplateMatch`（C++/OpenCV，真机验证可用），但它只给**整数像素**
 * 位置。上游在拿到粗位置后还会做一步「局部相关面 + 亚像素峰值精修」，本文件补上这个缺口。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MatchStrategy.cpp`：
 *  - `CoreMatchPrepared`（222-317）：`cv::matchTemplate(..., TM_CCOEFF_NORMED, weightMask)`
 *    （249）+ `patchNaNs`（251-259）+ `minMaxLoc`（266-268）+ `RefinePeakSubpixel`（270）
 *    + 可选 `RefinePeakContinuous`（273-278）+ PSR/delta（280-315）。
 *  - `RefinePeakSubpixel`（79-95）与 `RefinePeakOffset`（68-77）：三点抛物线峰值。
 *  - `RefinePeakContinuous`（109-201）：在亚像素位置**重采样底图**后按**同一 ZNCC 目标函数**
 *    连续求极大（`cv::remap(..., INTER_CUBIC, BORDER_REPLICATE)`，160）。
 *  - 掩膜来自模板有效像素：`GenerateMinimapMask`（`MapAlgorithm.cpp:10-20`）的外接圆盘。
 *
 * ## 与上游的差异（必须明确）
 *  1. 上游 `matchTemplate` 只在「模板完整落在搜索图内」的格点上出值；本实现按任务要求
 *     **边界 clamp**：候选位置允许模板部分越界，越界的模板像素不读数、直接跳过，局部积分
 *     窗口按「模板 ∩ 地图 ∩ 掩膜」的有效区域统计。因此贴边候选的得分与上游不完全逐位相同
 *     （上游根本不会在这些位置出值）。
 *  2. 上游连续精调用 `cv::remap`（三次插值）重采样；这里没有 remap，改用**双线性插值**
 *     （`sampleBilinear`）在整数峰邻域加密采样。目标函数与上游 `RefinePeakContinuous`
 *     的 `evaluate`（157-163）在数学上同式，差异只在插值核（三次 vs 双线性）。
 *  3. `GenerateMinimapMask` 还叠了 UI 白/彩色掩膜与中心箭头遮蔽、暗部剔除；本文件只移植
 *     **外接圆盘**这一层（任务允许「全有效 / 圆形有效」两种），其余 UI 层未移植。
 *  4. 上游 PSR 在整个相关面上统计；本实现只在 `(2r+1)×(2r+1)` 的局部面上统计，样本数很少，
 *     `psr` 仅供参考，**不参与** `valid` 的门限（上游全局搜索的门限也只比分 `score`，
 *     见 `Standard::validateGlobalSearch` 452-459、`PathHeatmap::validateGlobalSearch` 565-572）。
 *
 * 本文件不依赖 Android、不引图像库，只用 `ByteArray`/`DoubleArray`/`kotlin.math`。
 *
 * ## 将来如何被粗搜调用（接口形状）
 * 粗搜 `TemplateMatch` 的命中框是 `[x, y, w, h]`（模板左上角 + 模板尺寸）。调用方把
 * `map`（zone 底图 BGR）、`template`（同一次识别用的小地图裁剪 BGR）、
 * `coarseX = box[0]`、`coarseY = box[1]`、`templateWidth/Height = box[2]/box[3]` 传进来；
 * 返回的 [RefineResult.x]/[RefineResult.y] 是**地图坐标系下**的亚像素**模板左上角**，
 * 模板中心 = `x + w/2, y + h/2`（对齐 `validateTracking` 的
 * `absX = searchRect.x + loc.x + templCols/2`，`MatchStrategy.cpp:424-425`）。
 */
object MapLocatorRefinePure {

    /** 默认局部邻域半径（任务示例 4）。 */
    const val DEFAULT_SEARCH_RADIUS = 4

    /** 连续模式加密倍数 N：局部相关面按 `1/N` px 步长加密采样。 */
    const val DEFAULT_DENSIFY = 4

    /**
     * 上游 `countNonZero(weightMask) < 5`（`MatchStrategy.cpp:245`）即拒绝。
     * 本实现把阈值下移到「有效积分像素数」：某候选的有效像素少于它 → 相关面无定义。
     */
    const val MIN_VALID_PIXELS = 5

    /**
     * 上游 `kRefineMaxOffset = 1.0`（`MatchStrategy.cpp:97`）：连续精修的相对整数峰最大位移。
     */
    const val CONTINUOUS_MAX_OFFSET = 1.0

    /** 相关面无定义时的哨兵分（低于任何合法 ZNCC ∈ [-1,1]）。 */
    const val NO_SCORE = -2.0

    /** 上游 `RefinePeakContinuous` 分母的 `1e-12`（`MatchStrategy.cpp:162`）。 */
    const val FEATURE_EPS = 1e-12

    /** 模板掩膜类型。 */
    enum class TemplateMaskKind {
        /** 模板所有像素都有效（上游非 alpha 分支的退化情形）。 */
        ALL_VALID,

        /**
         * 上游 `GenerateMinimapMask`（`MapAlgorithm.cpp:10-20`）的外接圆盘：
         * `radius = min(w,h)/2 - borderMargin`（`< 0` 时取 0），圆心 `(w/2, h/2)`。
         */
        CIRCLE,
    }

    /** 峰值精修模式（对齐上游 `PeakRefineMode`，`MatchStrategy.h:102-108`）。 */
    enum class PeakRefineMode {
        /** `RefinePeakSubpixel`（79-95）：三点抛物线，精度上限是相关面格点。 */
        PARABOLA,

        /** `RefinePeakContinuous`（109-201）的 Kotlin 等价物：亚像素重采样后连续求极大。 */
        CONTINUOUS,
    }

    /**
     * 一次局部精修的结果。
     *
     * [x]/[y] 是地图坐标系下的亚像素**模板左上角**；[score] 是精修后峰值的 ZNCC 分；
     * [peakX]/[peakY] 是局部相关面上的整数峰值（模板左上角）；
     * [atBoundary] 表示峰值贴局部搜索窗边缘、无法取满邻点做抛物线；
     * [psr]/[delta] 只是局部面的旁瓣统计（局部面很小，仅参考）。
     */
    data class RefineResult(
        val valid: Boolean,
        val x: Double,
        val y: Double,
        val score: Double,
        val peakX: Int,
        val peakY: Int,
        val atBoundary: Boolean,
        val psr: Double,
        val delta: Double,
        val secondScore: Double,
    ) {
        /** 转成 [MatchResultRaw]，供 [MatchStrategy.validateGlobalSearch] 等既有验证链复用。 */
        fun toMatchResult(): MatchResultRaw = MatchResultRaw(
            score = score,
            locX = x,
            locY = y,
            secondScore = secondScore,
            delta = delta,
            psr = psr,
        )
    }

    /**
     * BGR → 灰度，复刻 OpenCV `cv::cvtColor(COLOR_BGR2GRAY)` 的 14 位定点系数
     * （`modules/imgproc/src/color.simd_helpers.hpp`：`B2Y=1868, G2Y=9617, R2Y=4899,
     * yuv_shift=14, offset=1<<13`）。
     *
     * 上游 `CoreMatchPrepared` 对搜索图与模板都做 BGR2GRAY（`MatchStrategy.cpp:210-217`、
     * 233-241），本函数是那一步的纯逻辑替身。
     *
     * @return `width*height` 的灰度；尺寸非法或 [bgr] 长度不符时 null。
     */
    fun bgrToGray(bgr: ByteArray, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (bgr.size != width * height * 3) return null
        val out = ByteArray(width * height)
        var si = 0
        for (i in out.indices) {
            val b = bgr[si].toInt() and 0xFF
            val g = bgr[si + 1].toInt() and 0xFF
            val r = bgr[si + 2].toInt() and 0xFF
            out[i] = ((b * 1868 + g * 9617 + r * 4899 + 8192) ushr 14).toByte()
            si += 3
        }
        return out
    }

    /**
     * 生成 `width×height` 的实心圆盘掩膜（`true` = 有效）。
     *
     * 对应上游 `cv::circle(baseMask, (centerX, centerY), radius, 255, -1)`
     * （`MapAlgorithm.cpp:20`）。栅格化算法与 `YoloPreprocess.circleMask` 相同（同一
     * `cv::circle` LINE_8 实心分支），只是这里支持**非正方形** W×H，因为小地图模板会
     * 随 ROI 变形成非方形。
     */
    fun circleValidMask(width: Int, height: Int, centerX: Int, centerY: Int, radius: Int): BooleanArray {
        require(width > 0 && height > 0) { "width/height must be positive: ${width}x$height" }
        val r = if (radius < 0) 0 else radius
        val mask = BooleanArray(width * height)

        var dx = r.toLong()
        var dy = 0L
        var plus = 1L
        var minus = (r.toLong() shl 1) - 1L
        var err = 0L

        while (dx >= dy) {
            fillSpan(mask, width, height, (centerY - dy).toInt(), (centerX - dx).toInt(), (centerX + dx).toInt())
            fillSpan(mask, width, height, (centerY + dy).toInt(), (centerX - dx).toInt(), (centerX + dx).toInt())
            fillSpan(mask, width, height, (centerY - dx).toInt(), (centerX - dy).toInt(), (centerX + dy).toInt())
            fillSpan(mask, width, height, (centerY + dx).toInt(), (centerX - dy).toInt(), (centerX + dy).toInt())

            dy++
            err += plus
            plus += 2
            // C++ `mask = (err <= 0) - 1`：err<=0 → 0，否则 → -1（全 1）。
            val m = if (err <= 0) 0L else -1L
            err -= minus and m
            dx += m
            minus -= m and 2L
        }
        return mask
    }

    /** 在 W×H 上把行 [yRow] 的 `[left, right]`（含端点）置 true，越界裁剪。 */
    private fun fillSpan(mask: BooleanArray, width: Int, height: Int, yRow: Int, left: Int, right: Int) {
        if (yRow < 0 || yRow >= height) return
        val l = if (left < 0) 0 else left
        val rr = if (right >= width) width - 1 else right
        if (l > rr) return
        val base = yRow * width
        for (x in l..rr) mask[base + x] = true
    }

    /**
     * 模板左上角放在 `(x, y)` 时，按 `cv::remap` 的语义做**双线性**亚像素采样。
     *
     * @return 灰度值；`(x,y)` 落在地图外时返回 [Double.NaN]（调用方跳过该像素）。
     */
    private fun sampleBilinear(gray: ByteArray, width: Int, height: Int, x: Double, y: Double): Double {
        if (x < 0.0 || y < 0.0 || x > (width - 1).toDouble() || y > (height - 1).toDouble()) {
            return Double.NaN
        }
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = if (x0 + 1 < width) x0 + 1 else x0
        val y1 = if (y0 + 1 < height) y0 + 1 else y0
        val ax = x - x0
        val ay = y - y0
        val i00 = gray[y0 * width + x0].toInt() and 0xFF
        val i10 = gray[y0 * width + x1].toInt() and 0xFF
        val i01 = gray[y1 * width + x0].toInt() and 0xFF
        val i11 = gray[y1 * width + x1].toInt() and 0xFF
        return (1.0 - ax) * (1.0 - ay) * i00 +
            ax * (1.0 - ay) * i10 +
            (1.0 - ax) * ay * i01 +
            ax * ay * i11
    }

    /**
     * 局部亚像素精修主干。
     *
     * @param mapBgr 地图底图 BGR 交错裸像素，长度须 `mapWidth*mapHeight*3`。
     * @param templateBgr 小地图裁剪 BGR 交错裸像素，长度须 `templateWidth*templateHeight*3`。
     * @param coarseX/coarseY 粗位置（地图坐标系，整数，模板左上角）。
     * @param searchRadius 局部邻域半径 r：在 `[coarseX-r, coarseX+r] × [coarseY-r, coarseY+r]`
     *   上逐点算相关面（越界读数按 [sampleBilinear] 跳过）。
     * @param maskKind 模板掩膜类型（`ALL_VALID` / `CIRCLE`）。
     * @param borderMargin `CIRCLE` 时 `radius = min(w,h)/2 - borderMargin`（上游 base=10、tier=8）。
     * @param refineMode 抛物线或连续精修。
     * @param densify 连续模式加密倍数 N（`<=0` 按 1 处理）。
     * @param passThreshold 全局搜索通过阈值，默认 [MatchConfig.passThreshold]（0.55）。
     *
     * @return 结构非法（尺寸/长度不符、模板比地图大、或尺寸非正）时 **null**；
     *   相关面无定义（如全黑模板方差为 0）时返回 `valid=false`、`score=-1` 的结果。
     */
    fun refineSubpixel(
        mapBgr: ByteArray,
        mapWidth: Int,
        mapHeight: Int,
        templateBgr: ByteArray,
        templateWidth: Int,
        templateHeight: Int,
        coarseX: Int,
        coarseY: Int,
        searchRadius: Int = DEFAULT_SEARCH_RADIUS,
        maskKind: TemplateMaskKind = TemplateMaskKind.CIRCLE,
        borderMargin: Int = 8,
        refineMode: PeakRefineMode = PeakRefineMode.CONTINUOUS,
        densify: Int = DEFAULT_DENSIFY,
        passThreshold: Double = MatchConfig().passThreshold,
    ): RefineResult? {
        if (mapWidth <= 0 || mapHeight <= 0 || templateWidth <= 0 || templateHeight <= 0) return null
        if (mapBgr.size != mapWidth * mapHeight * 3) return null
        if (templateBgr.size != templateWidth * templateHeight * 3) return null
        // 上游 CoreMatch 前置：搜索图必须不小于模板（MatchStrategy.cpp:228-230）。
        if (templateWidth > mapWidth || templateHeight > mapHeight) return null

        val gray = bgrToGray(mapBgr, mapWidth, mapHeight) ?: return null
        val templGray = bgrToGray(templateBgr, templateWidth, templateHeight) ?: return null

        val mask = when (maskKind) {
            TemplateMaskKind.ALL_VALID -> BooleanArray(templateWidth * templateHeight) { true }
            TemplateMaskKind.CIRCLE -> circleValidMask(
                templateWidth,
                templateHeight,
                templateWidth / 2,
                templateHeight / 2,
                minOf(templateWidth, templateHeight) / 2 - borderMargin,
            )
        }

        // 压缩成「有效模板像素」列表，避免逐候选扫描掩膜外的像素。
        var n = 0
        for (v in mask) if (v) n++
        val tdx = IntArray(n)
        val tdy = IntArray(n)
        val tval = DoubleArray(n)
        var k = 0
        for (ty in 0 until templateHeight) {
            for (tx in 0 until templateWidth) {
                if (!mask[ty * templateWidth + tx]) continue
                tdx[k] = tx
                tdy[k] = ty
                tval[k] = (templGray[ty * templateWidth + tx].toInt() and 0xFF).toDouble()
                k++
            }
        }

        // 一次评估：模板左上角在 (fx, fy) 时，对「模板 ∩ 地图 ∩ 掩膜」做带掩膜 ZNCC。
        // 与上游 RefinePeakContinuous 的 evaluate（MatchStrategy.cpp:157-163）同式：
        //   w=1（掩膜内），T' = w*(T - mean_T)，I' = w*(I - mean_I)，
        //   score = dot(T', I') / (||T'|| * ||I'|| + 1e-12)。
        fun evaluate(fx: Double, fy: Double): Double {
            var sumW = 0.0
            var sumWT = 0.0
            var sumWI = 0.0
            var sumWTT = 0.0
            var sumWII = 0.0
            var sumWTI = 0.0
            for (i in 0 until n) {
                val v = sampleBilinear(gray, mapWidth, mapHeight, fx + tdx[i], fy + tdy[i])
                if (v.isNaN()) continue
                val t = tval[i]
                sumW += 1.0
                sumWT += t
                sumWI += v
                sumWTT += t * t
                sumWII += v * v
                sumWTI += t * v
            }
            if (sumW < MIN_VALID_PIXELS) return NO_SCORE
            val mT = sumWT / sumW
            val mI = sumWI / sumW
            val varT = sumWTT - sumW * mT * mT
            val varI = sumWII - sumW * mI * mI
            if (varT <= FEATURE_EPS || varI <= FEATURE_EPS) return NO_SCORE
            val cov = sumWTI - sumW * mT * mI
            return cov / (sqrt(varT) * sqrt(varI) + FEATURE_EPS)
        }

        val r = if (searchRadius < 0) 0 else searchRadius
        val side = 2 * r + 1
        val scores = DoubleArray(side * side) { NO_SCORE }
        var best = NO_SCORE
        var bi = 0
        var bj = 0
        for (j in 0 until side) {
            for (i in 0 until side) {
                val s = evaluate((coarseX - r + i).toDouble(), (coarseY - r + j).toDouble())
                scores[j * side + i] = s
                if (s > best) {
                    best = s
                    bi = i
                    bj = j
                }
            }
        }

        // 相关面完全无定义（例如模板全黑/全白、或有效区域全越界）。
        if (best <= NO_SCORE) {
            return RefineResult(
                valid = false,
                x = coarseX.toDouble(),
                y = coarseY.toDouble(),
                score = -1.0,
                peakX = coarseX,
                peakY = coarseY,
                atBoundary = true,
                psr = 0.0,
                delta = 0.0,
                secondScore = -1.0,
            )
        }

        val peakX = coarseX - r + bi
        val peakY = coarseY - r + bj

        // ── 亚像素峰值 ──────────────────────────────────────────────
        var finalX = peakX.toDouble()
        var finalY = peakY.toDouble()
        var finalScore = best
        var atBoundary = false

        when (refineMode) {
            PeakRefineMode.PARABOLA -> {
                // RefinePeakSubpixel（79-95）：每个轴各用一次 RefinePeakOffset（68-77），
                // 缺邻点时该轴保持整数并标记。
                if (bi in 1 until side - 1 &&
                    scores[bj * side + bi - 1] > NO_SCORE &&
                    scores[bj * side + bi + 1] > NO_SCORE
                ) {
                    finalX += refinePeakOffset(
                        scores[bj * side + bi - 1].toFloat(),
                        scores[bj * side + bi].toFloat(),
                        scores[bj * side + bi + 1].toFloat(),
                    )
                } else {
                    atBoundary = true
                }
                if (bj in 1 until side - 1 &&
                    scores[(bj - 1) * side + bi] > NO_SCORE &&
                    scores[(bj + 1) * side + bi] > NO_SCORE
                ) {
                    finalY += refinePeakOffset(
                        scores[(bj - 1) * side + bi].toFloat(),
                        scores[bj * side + bi].toFloat(),
                        scores[(bj + 1) * side + bi].toFloat(),
                    )
                } else {
                    atBoundary = true
                }
            }

            PeakRefineMode.CONTINUOUS -> {
                // 上游 RefinePeakContinuous 用 remap 重采样后连续求极大；这里用双线性插值
                // 在整数峰邻域 `[peak-1, peak+1]` 上以 1/N px 步长加密采样（任务指定的
                // 「相关面双线性加密 N 倍再取最大」的等价实现）。
                val steps = if (densify <= 0) 1 else densify
                val step = CONTINUOUS_MAX_OFFSET / steps
                var gridBest = best
                var gx = 0
                var gy = 0
                for (jj in -steps..steps) {
                    for (ii in -steps..steps) {
                        val s = evaluate(peakX + ii * step, peakY + jj * step)
                        if (s > gridBest) {
                            gridBest = s
                            gx = ii
                            gy = jj
                        }
                    }
                }
                var candX = peakX + gx * step
                var candY = peakY + gy * step
                var candScore = gridBest

                // 在加密面上再做一次抛物线，把误差压到步长以下（双线性目标函数在峰附近
                // 近似抛物）。加密面索引步长即 step px，故偏移量乘 step 还原为像素。
                val denseSide = 2 * steps + 1
                val di = gx + steps
                val dj = gy + steps
                fun denseAt(i: Int, j: Int): Double =
                    evaluate(peakX + (i - steps) * step, peakY + (j - steps) * step)
                if (di in 1 until denseSide - 1) {
                    candX += refinePeakOffset(
                        denseAt(di - 1, dj).toFloat(),
                        denseAt(di, dj).toFloat(),
                        denseAt(di + 1, dj).toFloat(),
                    ) * step
                }
                if (dj in 1 until denseSide - 1) {
                    candY += refinePeakOffset(
                        denseAt(di, dj - 1).toFloat(),
                        denseAt(di, dj).toFloat(),
                        denseAt(di, dj + 1).toFloat(),
                    ) * step
                }
                // 抛物线候选点必须真的更高才采纳，保持 score 与 loc 一致。
                val candEval = evaluate(candX, candY)
                if (candEval > candScore) {
                    candScore = candEval
                } else {
                    candX = peakX + gx * step
                    candY = peakY + gy * step
                }
                finalX = candX
                finalY = candY
                finalScore = candScore
                if (bi == 0 || bi == side - 1 || bj == 0 || bj == side - 1) atBoundary = true
            }
        }

        // ── PSR / delta（局部面，上游 MatchStrategy.cpp:280-315 的局部替身）──
        var sideSum = 0.0
        var sideSumSq = 0.0
        var sideCount = 0
        var secondScore = -1.0
        for (j in 0 until side) {
            for (i in 0 until side) {
                val s = scores[j * side + i]
                if (s <= NO_SCORE) continue
                val nearPeak = abs(i - bi) <= 1 && abs(j - bj) <= 1
                if (!nearPeak) {
                    sideSum += s
                    sideSumSq += s * s
                    sideCount++
                    if (s > secondScore) secondScore = s
                }
            }
        }
        val psr: Double = if (sideCount > 0) {
            val mean = sideSum / sideCount
            val variance = (sideSumSq / sideCount - mean * mean).coerceAtLeast(0.0)
            (best - mean) / (sqrt(variance) + 1e-6)
        } else {
            0.0
        }
        val delta = if (secondScore > NO_SCORE) finalScore - secondScore else 0.0

        val result = RefineResult(
            valid = false,
            x = finalX,
            y = finalY,
            score = finalScore,
            peakX = peakX,
            peakY = peakY,
            atBoundary = atBoundary,
            psr = psr,
            delta = delta,
            secondScore = secondScore,
        )

        // 可信判定接既有验证链：上游全局搜索只看 score 是否过 passThreshold
        //（Standard 452-459 / PathHeatmap 565-572），这里复用 Standard 的实现。
        val validator = StandardMatchStrategy(isBase = false, matchCfg = MatchConfig(passThreshold = passThreshold))
        val valid = validator.validateGlobalSearch(result.toMatchResult()) != null
        return result.copy(valid = valid)
    }
}
