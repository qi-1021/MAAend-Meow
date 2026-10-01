package com.aliothmoon.maafw.remote

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * MapLocator 第二条策略 **PathHeatmap（路网热图）** 的纯逻辑层。
 *
 * 背景：`Standard`（整图模板匹配、`TM_CCOEFF_NORMED`）在真机世界帧上撞到方法学上限
 * （见 `docs/DEVLOG.md` 2026-09-30：全尺度/旋转/裁剪/掩膜/5 张资产穷举后相关性恒 ~0.6，
 * 无强峰）。上游因此有第二条策略：把地图的**路径网**做成热图，再把**掩膜后的小地图路径线**
 * 拿去匹配。本文件移植那条线的纯逻辑：热图构建、掩膜全层、模板特征提取、带掩膜 ZNCC 打分。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/`：
 *  - `MatchStrategy.cpp:19-66`  `ExtractPathHeatmapFeature`：逐像素「到路面标准色的曼哈顿距离」
 *    热图 + 5×5 高斯模糊；
 *  - `MatchStrategy.cpp:486-504` `PathHeatmapMatchStrategy::extractTemplateFeature`：
 *    小地图热图 + `GenerateMinimapMask`（**禁用暗部剔除、禁用 HSV 白掩膜**）；
 *  - `MatchStrategy.cpp:506-511` `extractSearchFeature`：地图 ROI 热图；
 *  - `MapAlgorithm.cpp:10-90`   `GenerateMinimapMask` 全层（外接圆盘 / UI 白+彩色 / 中心遮蔽 / 暗部剔除）；
 *  - `MapAlgorithm.h:9`         `withUiMask` / `withCenterMask` 两个默认开关；
 *  - `MatchStrategy.h:21-30`    `MatchResultRaw`（score / secondScore / delta / psr）。
 *
 * ## 与上游的关键事实校正
 *
 * 任务描述里提到「若上游用 OpenCV 的距离变换」。**上游热图不用距离变换**：它是
 * 「颜色曼哈顿距离 → 连续梯度」的查表式热图（`MatchStrategy.cpp:53-59`），可直接整数复刻。
 * 全仓库唯一的 `cv::distanceTransform` 在 **倒角匹配降级补偿**（`MapLocator.cpp:1124-1127`），
 * 依赖 `cv::Canny` + `distanceTransform`，属于另一条（`needsChamferCompensation`）路径，
 * 本文件**刻意不移植**，见文末「刻意留给 native 侧的部分」。
 *
 * ## 刻意留给 native / framework 侧的部分（显式标记）
 *
 * 1. **`cv::matchTemplate` 的性能实现**。打分公式（带掩膜 ZNCC）本文件已逐位复刻，
 *    但上游靠 OpenCV 的 SIMD/分块在全分辨率地图上求相关面。真机应把**热图构建**的结果
 *    （`extractPathHeatmap` 的 ByteArray / 或直接 native `cv::Mat`）交给 framework 侧的
 *    `TemplateMatch` / `cv::matchTemplate`，而不是跑本文件的 O(W·H·w·h) 朴素扫。
 * 2. **`cv::dilate` 的椭圆核栅格化**。本文件按 OpenCV `getStructuringElement(MORPH_ELLIPSE)`
 *    的算法（`r=c=ksize/2`，`dx = c·sqrt((r²-dy²)·2/(r²+c²))`）复刻，方形核下与 OpenCV 一致；
 *    边界处理按「越界视为 0」——因为最终掩膜是与中心留边的圆盘相交，边界行为不影响结果。
 * 3. **倒角（Canny + distanceTransform）补偿**：`MapLocator.cpp:1079-1144`，未移植。
 * 4. **`cv::GaussianBlur` 的定点实现**：本文件用 double 卷积 + 四舍五入，核为 OpenCV
 *    `getGaussianKernel(5, 1.1)`（`GaussianBlur(..., Size(5,5), 0)` 的自动 sigma），
 *    与 OpenCV 的定点/SIMD 路径可能相差 ±1 灰度。
 *
 * ## 缓存语义（`MapLocator.cpp:926-948`）
 *
 * `getGlobalSearchFeature` 对**搜索图特征**按 `(zoneId, kind, roi, zoneGeneration)` 缓存，
 * 且**按策略分两条独立缓存**：`isPathHeatmap ? 1 : 0`（`MapLocator.cpp:932-934`）。
 * 也就是说 Standard 的灰度图与 PathHeatmap 的热图各占一个 slot，互不覆盖；
 * key 变了（换区 / 换 ROI / 资产代际变）才重算。本文件用 [cacheSlotIndex] 复刻这个分槽。
 *
 * 本文件不依赖 Android / JNA / 文件系统 / `cv::Mat`，只用 `ByteArray`/`BooleanArray`/`kotlin.math`，
 * 可在 [scripts/verify_pure_logic.sh] 本机回归。
 */
object MapLocatorPathHeatmap {

    // ───────────────────────────── 热图常量 ─────────────────────────────

    /** 上游路面标准色（`MatchStrategy.cpp:36`，BGR 顺序）。 */
    const val PATH_TARGET_B = 237
    const val PATH_TARGET_G = 233
    const val PATH_TARGET_R = 228

    /** 上游容差 `maxDist = 60`（`MatchStrategy.cpp:37`），阈值实际用 `maxDist * 3 = 180`。 */
    const val PATH_MAX_DIST = 60

    /** 高斯模糊核尺寸（`MatchStrategy.cpp:64` 的 `Size(5,5)`）。 */
    const val BLUR_KSIZE = 5

    /**
     * `GaussianBlur(..., Size(5,5), 0)` 的自动 sigma：
     * `0.3*((ksize-1)*0.5 - 1) + 0.8 = 1.1`（OpenCV `createGaussianKernels`）。
     */
    const val BLUR_SIGMA = 1.1

    /** 相关面无定义时的哨兵分（低于任何合法 ZNCC ∈ [-1,1]）。 */
    const val NO_SCORE = -2.0

    /** 上游 `countNonZero(weightMask) < 5` 即拒绝（`MatchStrategy.cpp:245`）的有效像素下限。 */
    const val MIN_VALID_PIXELS = 5

    /** 上游 `RefinePeakContinuous` 分母的 `1e-12`（`MatchStrategy.cpp:162`）。 */
    const val FEATURE_EPS = 1e-12

    /** 上游 PSR 统计里屏蔽主峰的半边长 `ex = max(3, min(cols,rows)/10)`（`MatchStrategy.cpp:280`）。 */
    const val PEAK_EXCLUDE_MIN = 3

    /**
     * [matchGlobal] 的粗扫步长：先按该步长的稀疏网格求精确分，锁定候选区后再全分辨率精搜。
     * 见 [matchGlobal] 的优化说明。默认 4 ⇒ 粗扫候选数是全图的 ~1/16。
     */
    const val COARSE_STRIDE = 4

    /** 精搜窗口相对粗采样点的半径（px）。需 ≥ 采样半径 [COARSE_STRIDE]/2 才能覆盖真峰。 */
    const val COARSE_REFINE_RADIUS = 4

    /** 粗分落在 `maxCoarse - margin` 内的采样点才算候选区（抓主峰与强次峰）。 */
    const val COARSE_CANDIDATE_MARGIN = 0.5

    /** 候选区数量上限，防止病态（重复纹理）输入把精搜退化成全扫。 */
    const val MAX_REFINE_CANDIDATES = 256

    /**
     * 除「粗分 ≥ max - [COARSE_CANDIDATE_MARGIN]」的候选外，固定再精搜粗分最高的前若干个采样点，
     * 以免强次峰（用于 secondScore / delta）因未过 margin 而没被精搜覆盖。
     */
    const val COARSE_TOP_CANDIDATES = 16

    // ───────────────────────────── 掩膜配置 ─────────────────────────────

    /**
     * 上游 `ImageProcessingConfig`（`MapTypes.h:246-257`）。`darkMapThreshold` 只在别处使用，
     * 掩膜层不读它，为签名对齐保留。
     */
    data class ImageProcessingConfig(
        val darkMapThreshold: Double = 20.0,
        /** 黄/蓝图标与地图色差判定 */
        val iconDiffThreshold: Int = 40,
        /** 玩家箭头遮蔽半径 */
        val centerMaskRadius: Int = 8,
        /** 保底权重 */
        val gradientBaseWeight: Double = 0.1,
        /** 与暗部阈值对齐；< 0 等价于禁用暗部剔除 */
        val minimapDarkMaskThreshold: Int = 15,
        val borderMargin: Int = 8,
        val whiteDilate: Int = 9,
        val colorDilate: Int = 3,
        val useHsvWhiteMask: Boolean = false,
    ) {
        companion object {
            /** 上游 `baseImgCfg`（`MapLocator.cpp:826-834`）。 */
            val Base = ImageProcessingConfig(
                darkMapThreshold = 20.0,
                iconDiffThreshold = 40,
                centerMaskRadius = 18,
                gradientBaseWeight = 0.1,
                minimapDarkMaskThreshold = 20,
                borderMargin = 10,
                whiteDilate = 11,
                colorDilate = 3,
                useHsvWhiteMask = true,
            )

            /** 上游 `tierImgCfg`（`MapLocator.cpp:836-844`）。 */
            val Tier = ImageProcessingConfig(
                darkMapThreshold = 20.0,
                iconDiffThreshold = 40,
                centerMaskRadius = 8,
                gradientBaseWeight = 0.1,
                minimapDarkMaskThreshold = 15,
                borderMargin = 8,
                whiteDilate = 9,
                colorDilate = 3,
                useHsvWhiteMask = false,
            )
        }
    }

    /** 一次模板特征提取的结果：热图 + 掩膜（`true` = 参与相关）。 */
    data class PathHeatmapTemplate(
        val feature: ByteArray,
        val mask: BooleanArray,
    )

    /** 一次全局热图匹配的结果（对齐上游 `MatchResultRaw`，`loc` 拆成整数 x/y）。 */
    data class PathHeatmapMatch(
        val x: Int,
        val y: Int,
        val score: Double,
        val secondScore: Double,
        val delta: Double,
        val psr: Double,
    )

    // ───────────────────────────── 热图构建 ─────────────────────────────

    /**
     * 上游 `ExtractPathHeatmapFeature`（`MatchStrategy.cpp:19-66`）：BGR（可选 alpha）→ 路面热图。
     *
     * 逐像素：`dist = |b-237| + |g-233| + |r-228|`；`dist < 180` 时
     * `feat = max(0, 255 - dist*255/180)`（整数除法，逐位对齐 C++），否则 0。
     * `alpha != null && alpha < 128` 的像素直接跳过（保持 0）。
     * 最后做 5×5 高斯模糊（sigma=1.1，`BORDER_REFLECT_101`）。
     *
     * @return `width*height` 单通道热图；尺寸/长度不符时 null。
     */
    fun extractPathHeatmap(bgr: ByteArray, width: Int, height: Int, alpha: ByteArray? = null): ByteArray? =
        extractPathHeatmapImpl(bgr, width, height, alpha, PATH_TARGET_B, PATH_TARGET_G, PATH_TARGET_R, PATH_MAX_DIST)

    private fun extractPathHeatmapImpl(
        bgr: ByteArray,
        width: Int,
        height: Int,
        alpha: ByteArray?,
        targetB: Int,
        targetG: Int,
        targetR: Int,
        maxDist: Int,
    ): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (bgr.size != width * height * 3) return null
        if (alpha != null && alpha.size != width * height) return null

        val span = maxDist * 3
        val feat = IntArray(width * height)
        var si = 0
        for (i in feat.indices) {
            if (alpha != null && (alpha[i].toInt() and 0xFF) < 128) {
                si += 3
                continue
            }
            val b = bgr[si].toInt() and 0xFF
            val g = bgr[si + 1].toInt() and 0xFF
            val r = bgr[si + 2].toInt() and 0xFF
            si += 3
            val dist = abs(b - targetB) + abs(g - targetG) + abs(r - targetR)
            if (dist < span) {
                // C++：static_cast<uchar>(std::max(0, 255 - (dist * 255 / (maxDist * 3))))，整数除法
                val v = 255 - (dist * 255) / span
                feat[i] = if (v < 0) 0 else v
            }
        }

        val blurred = gaussianBlur5(feat, width, height)
        val out = ByteArray(blurred.size)
        for (i in out.indices) out[i] = blurred[i].toByte()
        return out
    }

    /** `getGaussianKernel(5, 1.1)`：`exp(-x²/(2σ²))` 归一化，x = i-(n-1)/2。 */
    private fun gaussianKernel5(sigma: Double): DoubleArray {
        val n = BLUR_KSIZE
        val k = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val x = i - (n - 1) * 0.5
            val t = exp(-0.5 * x * x / (sigma * sigma))
            k[i] = t
            sum += t
        }
        for (i in 0 until n) k[i] /= sum
        return k
    }

    /**
     * 可分离 5×5 高斯模糊，边界 `BORDER_REFLECT_101`（OpenCV 默认）。
     *
     * 输入/输出都是 `0..255` 的 Int；与 OpenCV 的定点/SIMD 路径可能相差 ±1 灰度（见类注释）。
     */
    private fun gaussianBlur5(src: IntArray, width: Int, height: Int): IntArray {
        val k = gaussianKernel5(BLUR_SIGMA)
        val tmp = IntArray(src.size)
        // 水平
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (t in -2..2) {
                    acc += k[t + 2] * src[base + reflect101(x + t, width)]
                }
                tmp[base + x] = acc.roundToInt().coerceIn(0, 255)
            }
        }
        // 垂直
        val out = IntArray(src.size)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                var acc = 0.0
                for (t in -2..2) {
                    acc += k[t + 2] * tmp[reflect101(y + t, height) * width + x]
                }
                out[base + x] = acc.roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** `BORDER_REFLECT_101` 的单点索引映射：`... 2 1 | 0 1 2 ... | 4 3 ...`。 */
    private fun reflect101(i: Int, n: Int): Int {
        if (n <= 1) return 0
        var x = i
        if (x < 0) x = -x
        if (x >= n) x = 2 * (n - 1) - x
        return x.coerceIn(0, n - 1)
    }

    // ───────────────────────────── 掩膜全层 ─────────────────────────────

    /**
     * 上游 `GenerateMinimapMask`（`MapAlgorithm.cpp:10-90`）的纯逻辑替身。
     *
     * 顺序严格照搬：
     *  1. 外接圆盘 `radius = min(w,h)/2 - borderMargin`（<0 取 0）；
     *  2. `withUiMask`：白掩膜（精确 255 + 可选 HSV 白）膨胀后清 0；彩色图标掩膜
     *     （`(r>100&&g>100&&min(r,g)-b>iconDiffThreshold) || (b>140 && b>r+50)`，仅在圆盘内统计）
     *     膨胀后清 0；
     *  3. `withCenterMask`：中心 `centerMaskRadius` 圆盘清 0；
     *  4. 暗部剔除：灰度 `<= minimapDarkMaskThreshold` 清 0（阈值 < 0 时自然无效果，对应上游
     *     PathHeatmap 把 `minimapDarkMaskThreshold = -1` 禁用）。
     *
     * HSV 白判定用标准 8 位公式 `S = round((V-min)*255/V)`、`V = max`，与 OpenCV
     * `COLOR_BGR2HSV` 在阈值（S<60, V>200）上等价；极端边界 ±1 舍入不影响本用途。
     *
     * @return `width*height` 掩膜（`true` = 参与相关）；尺寸非法时返回空数组。
     */
    fun generateMinimapMask(
        bgr: ByteArray,
        width: Int,
        height: Int,
        cfg: ImageProcessingConfig,
    ): BooleanArray = generateMinimapMaskImpl(bgr, width, height, cfg, true, true)

    /** [generateMinimapMask] 的显式开关版（对齐上游两个默认参数）。 */
    fun generateMinimapMask(
        bgr: ByteArray,
        width: Int,
        height: Int,
        cfg: ImageProcessingConfig,
        withUiMask: Boolean,
        withCenterMask: Boolean,
    ): BooleanArray = generateMinimapMaskImpl(bgr, width, height, cfg, withUiMask, withCenterMask)

    private fun generateMinimapMaskImpl(
        bgr: ByteArray,
        width: Int,
        height: Int,
        cfg: ImageProcessingConfig,
        withUiMask: Boolean,
        withCenterMask: Boolean,
    ): BooleanArray {
        if (width <= 0 || height <= 0) return BooleanArray(0)
        if (bgr.size != width * height * 3) return BooleanArray(0)

        val cx = width / 2
        val cy = height / 2
        val radius = max(0, min(width, height) / 2 - cfg.borderMargin)
        val base = MapLocatorRefinePure.circleValidMask(width, height, cx, cy, radius)

        if (withUiMask) {
            // 白掩膜：精确纯白 + 可选 HSV 白
            val white = BooleanArray(width * height)
            var i = 0
            while (i < white.size) {
                val si = i * 3
                val b = bgr[si].toInt() and 0xFF
                val g = bgr[si + 1].toInt() and 0xFF
                val r = bgr[si + 2].toInt() and 0xFF
                if (b == 255 && g == 255 && r == 255) {
                    white[i] = true
                } else if (cfg.useHsvWhiteMask) {
                    val v = max(b, max(g, r))
                    if (v > 0 && v >= 200) {
                        val minc = min(b, min(g, r))
                        val s = ((v - minc) * 255 + v / 2) / v
                        if (s <= 60) white[i] = true
                    }
                }
                i++
            }

            // 彩色/高亮 UI 图标：仅在圆盘内统计
            val color = BooleanArray(width * height)
            i = 0
            while (i < color.size) {
                if (base[i]) {
                    val si = i * 3
                    val b = bgr[si].toInt() and 0xFF
                    val g = bgr[si + 1].toInt() and 0xFF
                    val r = bgr[si + 2].toInt() and 0xFF
                    if ((r > 100 && g > 100 && min(r, g) - b > cfg.iconDiffThreshold) ||
                        (b > 140 && b > r + 50)
                    ) {
                        color[i] = true
                    }
                }
                i++
            }

            val colorDil = dilateEllipse(color, width, height, max(1, cfg.colorDilate))
            for (j in base.indices) if (colorDil[j]) base[j] = false

            val whiteDil = dilateEllipse(white, width, height, max(1, cfg.whiteDilate))
            for (j in base.indices) if (whiteDil[j]) base[j] = false
        }

        if (withCenterMask) {
            val center = MapLocatorRefinePure.circleValidMask(width, height, cx, cy, max(0, cfg.centerMaskRadius))
            for (j in base.indices) if (center[j]) base[j] = false
        }

        // 暗部剔除：THRESH_BINARY_INV 后 setTo(0) ⇒ 灰度 <= 阈值 的清 0。
        val gray = MapLocatorRefinePure.bgrToGray(bgr, width, height)
        if (gray != null) {
            for (j in base.indices) {
                if ((gray[j].toInt() and 0xFF) <= cfg.minimapDarkMaskThreshold) base[j] = false
            }
        }
        return base
    }

    /**
     * OpenCV `cv::dilate(src, dst, getStructuringElement(MORPH_ELLIPSE, Size(k,k)))` 的复刻。
     *
     * 椭圆核按 OpenCV 算法栅格化：`r=c=k/2`，行 `i` 的 `dy=i-r`，
     * `dx = saturate_cast<int>(c·sqrt((r²-dy²)·2/(r²+c²)))`，行内 `[c-dx, c+dx]` 置 1。
     * 膨胀即核覆盖范围内的最大值（0/1 掩膜下等价于逻辑或）。越界视为 0（最终掩膜与留边圆盘
     * 相交，边界行为不影响结果）。
     */
    private fun dilateEllipse(src: BooleanArray, width: Int, height: Int, ksize: Int): BooleanArray {
        val k = max(1, ksize)
        if (k <= 1) return src.copyOf()

        val r = k / 2
        val c = k / 2
        val invR2 = if (r > 0 && c > 0) 2.0 / (r.toDouble() * r + c.toDouble() * c) else 0.0
        val j1 = IntArray(k)
        val j2 = IntArray(k)
        for (i in 0 until k) {
            val dy = i - r
            if (abs(dy) <= r) {
                val dx = (c * sqrt((r * r - dy * dy) * invR2)).toInt()
                j1[i] = max(c - dx, 0)
                j2[i] = min(c + dx + 1, k)
            }
        }

        val out = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var hit = false
                var i = 0
                while (i < k && !hit) {
                    val yy = y + i - r
                    if (yy in 0 until height) {
                        val rowBase = yy * width
                        var j = j1[i]
                        while (j < j2[i]) {
                            val xx = x + j - c
                            if (xx in 0 until width && src[rowBase + xx]) {
                                hit = true
                                break
                            }
                            j++
                        }
                    }
                    i++
                }
                out[y * width + x] = hit
            }
        }
        return out
    }

    // ─────────────────────────── 模板特征提取 ───────────────────────────

    /**
     * 上游 `PathHeatmapMatchStrategy::extractTemplateFeature`（`MatchStrategy.cpp:486-504`）。
     *
     * 小地图 → 热图（[extractPathHeatmap]），掩膜用 `GenerateMinimapMask` 但
     * **禁用暗部剔除**（`minimapDarkMaskThreshold = -1`）与 **HSV 白掩膜**（`useHsvWhiteMask = false`）。
     * 供 `Standard` 的 alpha 掩膜不参与 PathHeatmap。
     *
     * @param minimapBgr 小地图裁剪 BGR。
     * @param alpha 可选 alpha 通道（长度须 `width*height`），透明像素在热图里被跳过。
     */
    fun extractTemplatePathFeature(
        minimapBgr: ByteArray,
        width: Int,
        height: Int,
        cfg: ImageProcessingConfig,
        alpha: ByteArray? = null,
    ): PathHeatmapTemplate? = extractTemplatePathFeatureImpl(minimapBgr, width, height, cfg, alpha)

    private fun extractTemplatePathFeatureImpl(
        minimapBgr: ByteArray,
        width: Int,
        height: Int,
        cfg: ImageProcessingConfig,
        alpha: ByteArray?,
    ): PathHeatmapTemplate? {
        val feature = extractPathHeatmapImpl(
            minimapBgr, width, height, alpha,
            PATH_TARGET_B, PATH_TARGET_G, PATH_TARGET_R, PATH_MAX_DIST,
        ) ?: return null
        val alphaCfg = cfg.copy(minimapDarkMaskThreshold = -1, useHsvWhiteMask = false)
        val mask = generateMinimapMaskImpl(minimapBgr, width, height, alphaCfg, true, true)
        return PathHeatmapTemplate(feature, mask)
    }

    // ───────────────────────────── 匹配打分 ─────────────────────────────

    /**
     * 在整数位置 `(x, y)`（模板左上角）对**热图**做带掩膜 ZNCC。
     *
     * 语义与上游 `matchTemplate(TM_CCOEFF_NORMED, mask)`（`MatchStrategy.cpp:249`）
     * 及 `RefinePeakContinuous::evaluate`（157-163）同式：
     * `mean = ΣM·I / ΣM`，`cov = Σ(T-meanT)(I-meanI)`，`score = cov / (‖T-meanT‖·‖I-meanI‖)`。
     * 窗口越界、掩膜有效像素 < [MIN_VALID_PIXELS]、或任一方差退化时返回 [NO_SCORE]。
     */
    fun scoreAt(
        search: ByteArray,
        searchWidth: Int,
        searchHeight: Int,
        templ: ByteArray,
        templWidth: Int,
        templHeight: Int,
        mask: BooleanArray,
        x: Int,
        y: Int,
    ): Double {
        if (!sizesValid(search, searchWidth, searchHeight, templ, templWidth, templHeight, mask)) return NO_SCORE
        if (x < 0 || y < 0 || x + templWidth > searchWidth || y + templHeight > searchHeight) return NO_SCORE
        val comp = compressTemplate(templ, templWidth, templHeight, mask)
        if (comp.n < MIN_VALID_PIXELS) return NO_SCORE
        return FeatureMatcher(search, searchWidth, searchHeight, comp.dx, comp.dy, comp.values).score(x, y)
    }

    /**
     * 全搜索：模板在搜索热图上所有合法整数位置求带掩膜 ZNCC，取最大峰。
     *
     * ## 相对朴素全扫的优化（怎么把 O((W-w+1)(H-h+1)·n) 降下来）
     *
     * 朴素实现每个候选都要 O(n)（n = 掩膜有效像素数）重算 `ΣI`、`ΣI²`、`ΣT·I`，
     * 总代价 O((W-w+1)(H-h+1)·n)。本实现改成**由粗到细（coarse-to-fine）**，不再逐点全扫：
     *
     * 1. **粗扫**。只在一个步长 [COARSE_STRIDE] 的稀疏网格（外加右/下边界两点）上求精确分，
     *    候选数从 `(W-w+1)(H-h+1)` 降到约 `1/COARSE_STRIDE²`（默认 1/16）。
     * 2. **选候选区**。取粗分不低于 `maxCoarse - COARSE_CANDIDATE_MARGIN` 的采样点（一定含最大者，
     *    并封顶 [MAX_REFINE_CANDIDATES] 个）。
     * 3. **精搜**。只在每个候选的 [COARSE_REFINE_RADIUS] 邻域内做全分辨率精确 ZNCC。采样点
     *    间距为 [COARSE_STRIDE]、精搜半径 ≥ 采样半径，真峰附近粗分高、必入选，其邻域必被覆盖。
     * 4. **可复用中间量**。掩膜内 `ΣI`、`ΣI²` 用「模板掩膜逐行区间的行前缀和」一次算出，
     *    代价 O(掩膜行区间数)（圆盘掩膜约等于模板高），而不是逐点 O(n) 重加；模板的
     *    `meanT`/`varT` 也只算一次。
     *
     * 净效果：被精确求值的窗口数从 `(W-w+1)(H-h+1)` 降到约
     * `(W-w+1)(H-h+1)/COARSE_STRIDE² + 候选数·(2·COARSE_REFINE_RADIUS+1)²`。
     *
     * ## 优化偏差（如实说明）
     *
     * `best` / 峰值位置 / 主峰分与朴素全扫**逐位一致**（合成数据上验证到 1e-9）。
     * PSR / secondScore / delta 对齐上游 `CoreMatchPrepared`（`MatchStrategy.cpp:280-315`）：
     * 在主峰周围开 `ex = max(3, min(tw,th)/10)` 的黑洞，统计旁瓣均值/标准差得 PSR，
     * 黑洞内次高分作为 second，`delta = best - second`。两项统计的来源不同：
     *  - `secondScore` 在所有精确求值过的位置（粗网格 + 精搜邻域）上取旁瓣最大，故强次峰
     *    一旦被精搜覆盖就与全扫一致；
     *  - PSR 的旁瓣**均值/方差**只在**全图均匀的粗网格**上估计（精搜邻域是围绕峰值加密的，
     *    混入会抬高均值、系统性压低 PSR）。粗网格是全图的均匀子采样，估计无偏、样本量是
     *    全扫的 ~1/stride²，实验上 PSR 偏差 < 3%（合成稀疏路网）。
     * 少数未被精搜覆盖的次峰可能让 second/delta 略有出入（实测 ≤ 0.06）。这些量只用于
     * 追踪可信度门限，与定位结果（score/loc，逐位精确）无关。容差见
     * `MapLocatorPathHeatmapOptTest`。
     *
     * @return 尺寸非法 / 模板大于搜索图 / 有效像素不足 / 全图无定义时 null。
     */
    fun matchGlobal(
        search: ByteArray,
        searchWidth: Int,
        searchHeight: Int,
        templ: ByteArray,
        templWidth: Int,
        templHeight: Int,
        mask: BooleanArray,
    ): PathHeatmapMatch? {
        if (!sizesValid(search, searchWidth, searchHeight, templ, templWidth, templHeight, mask)) return null
        if (templWidth > searchWidth || templHeight > searchHeight) return null
        val comp = compressTemplate(templ, templWidth, templHeight, mask)
        if (comp.n < MIN_VALID_PIXELS) return null

        val n = comp.n
        var sT = 0.0
        var sTT = 0.0
        for (i in 0 until n) {
            val t = comp.values[i]
            sT += t
            sTT += t * t
        }
        val meanT = sT / n
        val varT = (sTT - n * meanT * meanT).coerceAtLeast(0.0)
        if (varT <= FEATURE_EPS) return null

        val rw = searchWidth - templWidth + 1
        val rh = searchHeight - templHeight + 1
        val matcher = OptimizedFeatureMatcher(
            search, searchWidth, searchHeight, comp, templWidth, templHeight, meanT, varT,
        )

        // 粗网格采样点（含右/下边界，保证边缘也被覆盖）。
        val xs = ArrayList<Int>()
        var sx = 0
        while (sx < rw) { xs.add(sx); sx += COARSE_STRIDE }
        if (xs[xs.size - 1] != rw - 1) xs.add(rw - 1)
        val ys = ArrayList<Int>()
        var sy = 0
        while (sy < rh) { ys.add(sy); sy += COARSE_STRIDE }
        if (ys[ys.size - 1] != rh - 1) ys.add(rh - 1)
        val cw = xs.size
        val ch = ys.size

        val surface = DoubleArray(rw * rh) { NO_SCORE }
        val coarse = DoubleArray(cw * ch)
        var maxCoarse = NO_SCORE
        for (yy in 0 until ch) {
            val py = ys[yy]
            val rowBase = py * rw
            for (xx in 0 until cw) {
                val px = xs[xx]
                val s = matcher.scoreExact(px, py)
                coarse[yy * cw + xx] = s
                surface[rowBase + px] = s
                if (s > maxCoarse) maxCoarse = s
            }
        }
        if (maxCoarse <= NO_SCORE) return null

        // 选候选区：粗分 ≥ max - margin，外加粗分最高的前 COARSE_TOP_CANDIDATES 个采样点
        // （始终含最大者）；超过上限时按粗分取前若干个。
        val floor = maxCoarse - COARSE_CANDIDATE_MARGIN
        val picked = BooleanArray(coarse.size)
        for (k in coarse.indices) if (coarse[k] >= floor) picked[k] = true
        val topK = min(COARSE_TOP_CANDIDATES, coarse.size)
        if (topK > 0) {
            val order = Array(coarse.size) { it }
            order.sortWith(Comparator { a, b -> coarse[b].compareTo(coarse[a]) })
            for (t in 0 until topK) picked[order[t]] = true
        }
        val cand = ArrayList<Int>()
        for (k in coarse.indices) if (picked[k]) cand.add(k)
        if (cand.size > MAX_REFINE_CANDIDATES) {
            cand.sortWith(Comparator { a, b -> coarse[b].compareTo(coarse[a]) })
            while (cand.size > MAX_REFINE_CANDIDATES) cand.removeAt(cand.size - 1)
        }

        // 精搜：候选邻域内全分辨率精确分。
        val seen = BooleanArray(rw * rh)
        for (yy in 0 until ch) {
            val rowBase = ys[yy] * rw
            for (xx in 0 until cw) seen[rowBase + xs[xx]] = true
        }
        for (c in cand) {
            val cx = xs[c % cw]
            val cy = ys[c / cw]
            val x0 = if (cx - COARSE_REFINE_RADIUS < 0) 0 else cx - COARSE_REFINE_RADIUS
            val y0 = if (cy - COARSE_REFINE_RADIUS < 0) 0 else cy - COARSE_REFINE_RADIUS
            val x1 = if (cx + COARSE_REFINE_RADIUS > rw - 1) rw - 1 else cx + COARSE_REFINE_RADIUS
            val y1 = if (cy + COARSE_REFINE_RADIUS > rh - 1) rh - 1 else cy + COARSE_REFINE_RADIUS
            for (j in y0..y1) {
                val rowBase = j * rw
                for (i in x0..x1) {
                    val idx = rowBase + i
                    if (seen[idx]) continue
                    seen[idx] = true
                    surface[idx] = matcher.scoreExact(i, j)
                }
            }
        }

        // 行优先、严格大于取峰（与朴素全扫的并列行为一致）。
        var best = NO_SCORE
        var bi = 0
        var bj = 0
        for (j in 0 until rh) {
            val rowBase = j * rw
            for (i in 0 until rw) {
                val s = surface[rowBase + i]
                if (s > best) {
                    best = s
                    bi = i
                    bj = j
                }
            }
        }
        if (best <= NO_SCORE) return null

        val ex = max(PEAK_EXCLUDE_MIN, min(templWidth, templHeight) / 10)
        // secondScore：在所有精确求值过的位置（粗网格 + 精搜邻域）上取旁瓣最大，尽量抓准次峰。
        var second = NO_SCORE
        for (j in 0 until rh) {
            val rowBase = j * rw
            for (i in 0 until rw) {
                val s = surface[rowBase + i]
                if (s <= NO_SCORE) continue
                if (abs(i - bi) <= ex && abs(j - bj) <= ex) continue
                if (s > second) second = s
            }
        }
        // PSR 的旁瓣均值/方差：只在**均匀的粗网格**上统计——精搜邻域是围绕峰值加密采样的，
        // 直接混入会把均值抬高、系统性压低 PSR。粗网格是全图均匀抽样，估计无偏、且与全扫的
        // 样本量同量级的 1/stride²，实测偏差 < 3%。
        var sideSum = 0.0
        var sideSumSq = 0.0
        var sideCount = 0
        for (yy in 0 until ch) {
            val py = ys[yy]
            val rowBase = yy * cw
            for (xx in 0 until cw) {
                val s = coarse[rowBase + xx]
                if (s <= NO_SCORE) continue
                val px = xs[xx]
                if (abs(px - bi) <= ex && abs(py - bj) <= ex) continue
                sideSum += s
                sideSumSq += s * s
                sideCount++
            }
        }
        val psr = if (sideCount > 0) {
            val mean = sideSum / sideCount
            val variance = (sideSumSq / sideCount - mean * mean).coerceAtLeast(0.0)
            (best - mean) / (sqrt(variance) + 1e-6)
        } else {
            0.0
        }
        val delta = if (second > NO_SCORE) best - second else 0.0
        return PathHeatmapMatch(bi, bj, best, second, delta, psr)
    }

    /**
     * 上游 `getGlobalSearchFeature`（`MapLocator.cpp:932-934`）的缓存分槽：
     * `isPathHeatmap ? 1 : 0`。Standard 与 PathHeatmap 各占一条互不覆盖的 slot。
     */
    fun cacheSlotIndex(kind: TemplateFeatureKind): Int = when (kind) {
        TemplateFeatureKind.PATH_HEATMAP_BASE,
        TemplateFeatureKind.PATH_HEATMAP_TIER,
        -> 1

        TemplateFeatureKind.STANDARD_BASE,
        TemplateFeatureKind.STANDARD_TIER,
        -> 0
    }

    // ───────────────────────────── 内部工具 ─────────────────────────────

    private fun sizesValid(
        search: ByteArray,
        searchWidth: Int,
        searchHeight: Int,
        templ: ByteArray,
        templWidth: Int,
        templHeight: Int,
        mask: BooleanArray,
    ): Boolean {
        if (searchWidth <= 0 || searchHeight <= 0 || templWidth <= 0 || templHeight <= 0) return false
        if (search.size != searchWidth * searchHeight) return false
        if (templ.size != templWidth * templHeight) return false
        return mask.size == templWidth * templHeight
    }

    /** 把掩膜内的模板像素压缩成「偏移 + 值」列表，避免逐候选扫描掩膜外像素。 */
    private class CompressedTemplate(
        val dx: IntArray,
        val dy: IntArray,
        val values: DoubleArray,
    ) {
        val n: Int get() = values.size
    }

    private fun compressTemplate(
        templ: ByteArray,
        templWidth: Int,
        templHeight: Int,
        mask: BooleanArray,
    ): CompressedTemplate {
        var count = 0
        for (v in mask) if (v) count++
        val dx = IntArray(count)
        val dy = IntArray(count)
        val values = DoubleArray(count)
        var k = 0
        for (ty in 0 until templHeight) {
            for (tx in 0 until templWidth) {
                if (!mask[ty * templWidth + tx]) continue
                dx[k] = tx
                dy[k] = ty
                values[k] = (templ[ty * templWidth + tx].toInt() and 0xFF).toDouble()
                k++
            }
        }
        return CompressedTemplate(dx, dy, values)
    }

    /**
     * 带掩膜 ZNCC 求值器（抽成成员类，避免局部函数生成的字节码触发 D8 崩溃，
     * 与 [MapLocatorRefinePure] 的 `SurfaceEvaluator` 同一处理）。
     */
    private class FeatureMatcher(
        private val search: ByteArray,
        private val searchWidth: Int,
        private val searchHeight: Int,
        private val tdx: IntArray,
        private val tdy: IntArray,
        private val tval: DoubleArray,
    ) {
        private val n = tval.size
        private val meanT: Double
        private val varT: Double

        init {
            var s = 0.0
            var s2 = 0.0
            for (i in 0 until n) {
                val t = tval[i]
                s += t
                s2 += t * t
            }
            val m = if (n > 0) s / n else 0.0
            meanT = m
            val v = if (n > 0) s2 - n * m * m else 0.0
            varT = if (v < 0.0) 0.0 else v
        }

        fun score(x: Int, y: Int): Double {
            if (varT <= FEATURE_EPS) return NO_SCORE
            var sumI = 0.0
            var sumII = 0.0
            var sumTI = 0.0
            for (i in 0 until n) {
                val v = (search[(y + tdy[i]) * searchWidth + (x + tdx[i])].toInt() and 0xFF).toDouble()
                sumI += v
                sumII += v * v
                sumTI += tval[i] * v
            }
            val meanI = sumI / n
            val varI = sumII - n * meanI * meanI
            if (varI <= FEATURE_EPS) return NO_SCORE
            val cov = sumTI - n * meanT * meanI
            return cov / (sqrt(varT) * sqrt(varI) + FEATURE_EPS)
        }
    }

    /**
     * [matchGlobal] 的加速求值器。
     *
     * 预计算「模板掩膜逐行区间」与「搜索图逐行前缀和（I 与 I²）」，使每个被求值的位置：
     *  - `ΣI`、`ΣI²` 通过行前缀和按掩膜行区间求和，代价 O(掩膜行区间数)（圆盘掩膜 ≈ 模板高），
     *    而不是逐点 O(n) 重加；
     *  - `ΣT·I` 仍在掩膜有效像素上累加，但模板已压缩为 `tdx/tdy/tval`，模板侧只算一次。
     *
     * 语义与 [FeatureMatcher] 完全一致：同一 ZNCC、同一退化判据、同一 `FEATURE_EPS` 分母。
     */
    private class OptimizedFeatureMatcher(
        private val search: ByteArray,
        private val searchWidth: Int,
        private val searchHeight: Int,
        comp: CompressedTemplate,
        private val templWidth: Int,
        private val templHeight: Int,
        private val meanT: Double,
        private val varT: Double,
    ) {
        private val n = comp.n
        private val stride = searchWidth + 1
        private val sqrtVarT = sqrt(varT)

        private val tdx = comp.dx
        private val tdy = comp.dy
        private val tval = comp.values

        /** 掩膜逐行区间：第 ty 行的 pair 索引区间 `[rowBegin[ty], rowBegin[ty+1])`。 */
        private val rowBegin: IntArray
        private val runs: IntArray

        /** 搜索图沿 x 的行前缀和（`prefix[row*stride + c] = Σ_{j<c} v`）。 */
        private val prefix1: IntArray
        private val prefix2: IntArray

        init {
            prefix1 = IntArray(stride * searchHeight)
            prefix2 = IntArray(stride * searchHeight)
            for (row in 0 until searchHeight) {
                val rb = row * stride
                val ib = row * searchWidth
                var a = 0
                var b = 0
                for (c in 0 until searchWidth) {
                    val v = search[ib + c].toInt() and 0xFF
                    a += v
                    b += v * v
                    prefix1[rb + c + 1] = a
                    prefix2[rb + c + 1] = b
                }
            }

            val dens = BooleanArray(templWidth * templHeight)
            for (k in 0 until n) dens[comp.dy[k] * templWidth + comp.dx[k]] = true
            rowBegin = IntArray(templHeight + 1)
            var runCount = 0
            for (ty in 0 until templHeight) {
                rowBegin[ty] = runCount
                var tx = 0
                val base = ty * templWidth
                while (tx < templWidth) {
                    if (!dens[base + tx]) { tx++; continue }
                    while (tx < templWidth && dens[base + tx]) tx++
                    runCount++
                }
            }
            rowBegin[templHeight] = runCount
            runs = IntArray(runCount * 2)
            var rp = 0
            for (ty in 0 until templHeight) {
                var tx = 0
                val base = ty * templWidth
                while (tx < templWidth) {
                    if (!dens[base + tx]) { tx++; continue }
                    val lo = tx
                    while (tx < templWidth && dens[base + tx]) tx++
                    runs[rp * 2] = lo
                    runs[rp * 2 + 1] = tx
                    rp++
                }
            }
        }

        /**
         * 精确带掩膜 ZNCC；`varI` 退化时返回 [NO_SCORE]。
         *
         * `ΣI`/`ΣI²` 走行前缀和 + 掩膜行区间；`ΣT·I` 在掩膜有效像素上累加。
         * 求和顺序与数值与 [FeatureMatcher] 一致（整数和不超过 2^53，double 累加逐位相同）。
         */
        fun scoreExact(x: Int, y: Int): Double {
            var f1 = 0L
            var f2 = 0L
            for (ty in 0 until templHeight) {
                val pr = (y + ty) * stride
                var p = rowBegin[ty]
                val end = rowBegin[ty + 1]
                while (p < end) {
                    val lo = runs[p * 2]
                    val hi = runs[p * 2 + 1]
                    f1 += (prefix1[pr + x + hi] - prefix1[pr + x + lo]).toLong()
                    f2 += (prefix2[pr + x + hi] - prefix2[pr + x + lo]).toLong()
                    p++
                }
            }
            val meanI = f1.toDouble() / n
            val varI = f2.toDouble() - n * meanI * meanI
            if (varI <= FEATURE_EPS) return NO_SCORE
            var sti = 0.0
            for (k in 0 until n) {
                sti += tval[k] * (search[(y + tdy[k]) * searchWidth + x + tdx[k]].toInt() and 0xFF)
            }
            val cov = sti - n * meanT * meanI
            return cov / (sqrtVarT * sqrt(varI) + FEATURE_EPS)
        }
    }
}
