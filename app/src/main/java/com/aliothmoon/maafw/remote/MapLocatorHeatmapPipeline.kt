package com.aliothmoon.maafw.remote

/**
 * PathHeatmap 接入**粗定位**的纯逻辑管道：搜索热图缓存、模板按掩膜外接框裁剪、
 * 掩膜外填充（选项 a）、局部窗口内的真掩膜 ZNCC 精排（选项 b）。
 *
 * ## 为什么单独一层
 *
 * 真机链路是 `MaaImageBufferSetRawData`（搜索热图作帧图）→ `MaaContextOverrideImage`
 * （模板热图作运行时模板）→ `TemplateMatch(method=5, green_mask=false)`，再在 Kotlin 里
 * 用 `MapLocatorPathHeatmap.matchGlobal` 做掩膜精排。框架/DLL 部分本机编不了、跑不了，
 * 但「外接框算得对不对、掩膜外填充得对不对、缓存键对不对、窗口裁得对不对、精排坐标
 * 有没有换算回搜索图坐标系」这些**可以脱离 Android 断言**。把它们钉死，真机失败时能
 * 快速区分「逻辑错」还是「框架前提不成立（覆盖模板没被读到 / 通道不对）」。
 *
 * ## 掩膜落地：为什么选 (a) 粗排 + (b) 精排，而不是只选一个
 *
 * 框架 `TemplateMatch` 的 `method=5`（`TM_CCOEFF_NORMED`）**签名里没有 mask**，
 * 上游 `cv::matchTemplate(..., weightMask)` 的带掩膜版本框架不暴露。两条落地各有偏：
 *
 *  - **(a) 掩膜外填充**：把裁剪后模板的掩膜外像素填成「掩膜内均值」。ZNCC 分子
 *    `Σ(T-meanT)(I-meanI)` 里，均值填充使 `T-meanT` 在掩膜外恒为 0（且整体均值等于
 *    掩膜内均值），于是**分子与模板范数 `‖T-meanT‖` 恰好退化成掩膜内统计**；只有分母的
 *    `meanI` 仍按整窗计算，是唯一的偏差来源。相比填 0（`T-meanT` 在掩膜外 = `-meanT`
 *    ≠ 0，会污染分子与模板范数），均值填充偏差更小。代价是框架仍按**整窗**统计，
 *    非掩膜 ZNCC，只是「近似掩膜」。
 *  - **(b) Kotlin 真掩膜 ZNCC**：`MapLocatorPathHeatmap.matchGlobal` 逐位复刻上游
 *    `matchTemplate(TM_CCOEFF_NORMED, mask)`，分子/分母都只用掩膜内像素，**分数准**，
 *    但朴素扫是 `O((W-w)(H-h)·有效像素)`，全图不可接受。
 *
 * **本实现选组合 (a) 粗排 + (b) 精排**：框架用 (a) 的均值填充模板在**整个搜索 ROI** 上
 * 求相关面、给出候选粗框（快、交给 native `matchTemplate`）；Kotlin 只在粗框邻域
 * [REFINE_RADIUS] 的小窗口里跑 (b) 的真掩膜 ZNCC（准、窗口小可控），精排分作为**最终
 * 分**。若精排退化（掩膜有效像素不足 / 窗口放不下模板），回退到框架 (a) 的粗分与粗框，
 * 保证热图路始终有一份可报告的分数。
 *
 * 上游对应：
 *  - `MapLocator.cpp:413-419`：模板按掩膜外接框裁剪（`cv::boundingRect` + `scaledTemplate(valid)`）；
 *  - `MapLocator.cpp:926-948` `getGlobalSearchFeature`：搜索特征按
 *    `(zoneId, kind, roi, generation)` 缓存，且 `isPathHeatmap ? 1 : 0` 分两条独立 slot；
 *  - `MatchStrategy.cpp:486-511`：模板/搜索特征提取；
 *  - `MatchStrategy.cpp:222-317` `CoreMatchPrepared`：带掩膜 ZNCC 与 PSR/delta（在
 *    [MapLocatorPathHeatmap.matchGlobal] 里已复刻）。
 *
 * 本文件不依赖 Android / JNA / 文件系统 / `cv::Mat`，可在 [scripts/verify_pure_logic.sh] 本机回归。
 */
object MapLocatorHeatmapPipeline {

    /**
     * 精排窗口相对粗排框的半径（px）。对齐上游 `MatchConfig.fineSearchRadius`（`MapTypes.h:276`）。
     * 窗口 = 模板尺寸 + 2·半径，模板必须能完整落位。
     */
    const val REFINE_RADIUS = 40

    /**
     * 粗排 `TemplateMatch` 的框架阈值（**仅用于求峰位，不用于采信**）。
     *
     * 框架的 `TemplateMatch` 在最高分低于 `threshold` 时会回 `[0,0,0,0]`、detail 无 `best`，
     * 于是峰位信息整个丢失。全局搜索需要的是「峰在哪」，采信与否由后续真掩膜精排
     * （[refineInWindow]）与追踪状态机裁决；故求峰位这一道阈值放到 0.0，保证
     * 只要峰分不小于 0 就能拿到位置。最终采信仍要求 `score > SEAM_FALLBACK_MIN_PEAK_SCORE`。
     */
    const val COARSE_SEED_THRESHOLD = 0.0

    /** 一次搜索热图缓存 key，对齐上游 `GlobalSearchFeatureCacheKey`（`MapLocator.cpp:939-944`）。 */
    data class SearchFeatureKey(
        val zoneId: String,
        val kind: TemplateFeatureKind,
        val roi: MapRect,
        val generation: Long,
    )

    /**
     * `getGlobalSearchFeature`（`MapLocator.cpp:926-948`）的**两槽缓存**复刻。
     *
     * slot 由 [MapLocatorPathHeatmap.cacheSlotIndex] 决定（Standard=0 / PathHeatmap=1），
     * 两槽互不覆盖；同一槽内 key 变了（换区 / 换 ROI / 资产代际变）才重算。
     */
    class SearchFeatureCache {

        private class Entry(val key: SearchFeatureKey, val feature: ByteArray)

        private val slots = arrayOfNulls<Entry>(2)

        /** 命中同键返回缓存；否则用 [compute] 重算并写入对应槽。 [compute] 返回 null 时不写缓存。 */
        fun getOrCompute(key: SearchFeatureKey, compute: () -> ByteArray?): ByteArray? {
            val slot = MapLocatorPathHeatmap.cacheSlotIndex(key.kind)
            val cur = slots[slot]
            if (cur != null && cur.key == key) return cur.feature
            val feature = compute() ?: return null
            slots[slot] = Entry(key, feature)
            return feature
        }

        /** 上游 `clearGlobalSearchFeatureCache`（`MapLocator.cpp:950-957`）。 */
        fun clear() {
            for (i in slots.indices) slots[i] = null
        }
    }

    /**
     * 资产代际：长度与修改时间混合。资产没变则代际稳定（缓存可命中），
     * 换了资产（长度/时间变）则 key 变、缓存自动失效。
     */
    fun assetGeneration(length: Long, lastModified: Long): Long =
        (length * 1_000_003L) xor lastModified

    /**
     * 掩膜 `true` 像素的外接框，等价 OpenCV `cv::boundingRect`（含端点，故宽高 = 跨度 + 1）。
     * 掩膜全 `false` / 尺寸非法返回空 [MapRect]。
     */
    fun maskBoundingBox(mask: BooleanArray, width: Int, height: Int): MapRect {
        if (width <= 0 || height <= 0 || mask.size != width * height) return MapRect()
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (!mask[base + x]) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return MapRect()
        return MapRect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** 从单通道图按 [rect] 裁出；越界 / 尺寸不符 / 空矩形返回 null。 */
    fun cropGray(src: ByteArray, srcWidth: Int, srcHeight: Int, rect: MapRect): ByteArray? {
        if (srcWidth <= 0 || srcHeight <= 0 || rect.isEmpty) return null
        if (rect.x < 0 || rect.y < 0 || rect.x + rect.width > srcWidth || rect.y + rect.height > srcHeight) return null
        if (src.size != srcWidth * srcHeight) return null
        val out = ByteArray(rect.width * rect.height)
        for (row in 0 until rect.height) {
            System.arraycopy(src, (rect.y + row) * srcWidth + rect.x, out, row * rect.width, rect.width)
        }
        return out
    }

    /** 从布尔掩膜按 [rect] 裁出；越界 / 尺寸不符 / 空矩形返回 null。 */
    fun cropMask(mask: BooleanArray, maskWidth: Int, maskHeight: Int, rect: MapRect): BooleanArray? {
        if (maskWidth <= 0 || maskHeight <= 0 || rect.isEmpty) return null
        if (rect.x < 0 || rect.y < 0 || rect.x + rect.width > maskWidth || rect.y + rect.height > maskHeight) return null
        if (mask.size != maskWidth * maskHeight) return null
        val out = BooleanArray(rect.width * rect.height)
        for (row in 0 until rect.height) {
            System.arraycopy(mask, (rect.y + row) * maskWidth + rect.x, out, row * rect.width, rect.width)
        }
        return out
    }

    /** 掩膜内像素的均值（四舍五入）；掩膜全 false 返回 0。 */
    fun maskedMean(values: ByteArray, mask: BooleanArray): Int {
        var sum = 0L
        var n = 0
        for (i in values.indices) {
            if (i < mask.size && mask[i]) {
                sum += values[i].toInt() and 0xFF
                n++
            }
        }
        if (n == 0) return 0
        return ((sum + n / 2) / n).toInt()
    }

    /**
     * **选项 (a)**：把掩膜外像素填成 [fill]（默认「掩膜内均值」）。
     *
     * 选均值而非 0 的理由见类注释：均值填充让 `T-meanT` 在掩膜外恒为 0，
     * 框架整窗 ZNCC 的分子与模板范数退化成掩膜内统计，偏差最小。
     * 长度不符时原样拷贝（不抛）。
     */
    fun fillOutsideMask(templ: ByteArray, mask: BooleanArray, fill: Int? = null): ByteArray {
        if (templ.size != mask.size) return templ.copyOf()
        val f = (fill ?: maskedMean(templ, mask)) and 0xFF
        val out = ByteArray(templ.size)
        for (i in templ.indices) {
            out[i] = if (mask[i]) templ[i] else f.toByte()
        }
        return out
    }

    /**
     * 单通道热图复制成 BGR 交错（每像素 3 字节同值）。
     *
     * 框架 `MaaImageBufferSetRawData` / `MaaContextOverrideImage` 在这套绑定里只暴露
     * `CV_8UC3`（`MaaImageType.CV_8UC3`），而框架 `TemplateMatch` 内部按灰度做匹配；
     * 三通道同值经 BGR2GRAY 后仍是该值，因此与直接送单通道灰度等价，且不需要新通道类型。
     */
    fun replicateToBgr(heat: ByteArray): ByteArray {
        val out = ByteArray(heat.size * 3)
        for (i in heat.indices) {
            val v = heat[i]
            out[i * 3] = v
            out[i * 3 + 1] = v
            out[i * 3 + 2] = v
        }
        return out
    }

    /**
     * 以粗排框左上角 `(coarseX, coarseY)` 为中心，在 `searchWidth×searchHeight` 的搜索热图上
     * 开一个「能放下 `templW×templH` 模板并四周留 [radius]」的局部窗口，裁到搜索图边界。
     *
     * 返回 null：模板比搜索图还大、或裁到边界后放不下模板（上游 `MatchStrategy.cpp:420-422`
     * 同样在模板大于搜索尺寸时放弃）。
     */
    fun refineWindow(
        coarseX: Int,
        coarseY: Int,
        templW: Int,
        templH: Int,
        searchWidth: Int,
        searchHeight: Int,
        radius: Int = REFINE_RADIUS,
    ): MapRect? {
        if (searchWidth <= 0 || searchHeight <= 0 || templW <= 0 || templH <= 0) return null
        if (templW > searchWidth || templH > searchHeight) return null
        val r = if (radius < 0) 0 else radius
        var w = templW + 2 * r
        var h = templH + 2 * r
        if (w < templW) w = templW
        if (h < templH) h = templH
        var x = coarseX - r
        var y = coarseY - r
        if (x < 0) x = 0
        if (y < 0) y = 0
        if (x + w > searchWidth) x = searchWidth - w
        if (y + h > searchHeight) y = searchHeight - h
        if (x < 0) x = 0
        if (y < 0) y = 0
        if (x + w > searchWidth) w = searchWidth - x
        if (y + h > searchHeight) h = searchHeight - y
        if (w < templW || h < templH) return null
        return MapRect(x, y, w, h)
    }

    /**
     * 粗排框是否**可用作精排种子**。
     *
     * 框架 `TemplateMatch` 未命中（分数低于 `threshold`）时会返回 `[0,0,0,0]`；把它当成有效
     * 观测传给 [refineInWindow] 会让 `coarseX - searchRoi.x = -searchRoi.x < 0`，窗口被夹到
     * 搜索 ROI 的**左上角**——「全局搜索」退化成角落局部搜索，报出的位置与真值相差
     * 搜索窗原点量级（真机实测 40~190px，且随 YOLO tile 变化**无规律**）。
     *
     * 因此只有「宽高为正且左上角非原点」的框才是有意义的峰位。宽高为负、长度不足或
     * `[0,0,0,0]` 一律判不可用，调用方改用搜索窗中心或另一条路的框。
     */
    fun isUsableSeedBox(box: IntArray?): Boolean {
        if (box == null || box.size < 4) return false
        if (box[2] <= 0 || box[3] <= 0) return false
        return box[0] != 0 || box[1] != 0
    }

    /**
     * **选项 (b)**：在 [refineWindow] 给出的局部窗口内，用真掩膜 ZNCC
     * （[MapLocatorPathHeatmap.matchGlobal]）精排。
     *
     * 返回的 [PathHeatmapMatchRaw.x]/[PathHeatmapMatchRaw.y] 已换算回**搜索图坐标系**
     * （即窗口左上角 + 窗口内峰值），可直接加搜索 ROI 原点得到地图坐标。
     * 窗口无解 / 掩膜有效像素不足时返回 null，调用方回退到 (a) 的粗分。
     */
    fun refineInWindow(
        searchHeat: ByteArray,
        searchWidth: Int,
        searchHeight: Int,
        templ: ByteArray,
        templW: Int,
        templH: Int,
        mask: BooleanArray,
        coarseX: Int,
        coarseY: Int,
        radius: Int = REFINE_RADIUS,
    ): PathHeatmapMatchRaw? {
        val win = refineWindow(coarseX, coarseY, templW, templH, searchWidth, searchHeight, radius)
            ?: return null
        val sub = cropGray(searchHeat, searchWidth, searchHeight, win) ?: return null
        val m = MapLocatorPathHeatmap.matchGlobal(sub, win.width, win.height, templ, templW, templH, mask)
            ?: return null
        return PathHeatmapMatchRaw(
            x = win.x + m.x,
            y = win.y + m.y,
            score = m.score,
            secondScore = m.secondScore,
            delta = m.delta,
            psr = m.psr,
        )
    }

    /**
     * 精排结果的轻量投影（x/y 为搜索图坐标系下模板左上角）。
     * 与 [MapLocatorPathHeatmap.PathHeatmapMatch] 字段一致，便于把窗口内结果换算后回传。
     */
    data class PathHeatmapMatchRaw(
        val x: Int,
        val y: Int,
        val score: Double,
        val secondScore: Double,
        val delta: Double,
        val psr: Double,
    )
}
