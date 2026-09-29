package com.aliothmoon.maafw.remote

import kotlin.math.roundToInt

/**
 * MapLocator「第一次端到端粗定位」这一步的**纯逻辑**：
 * 地图资产文件名 → zone key、小地图 ROI 选择与裁剪几何、搜索 ROI 的最终约束、
 * 以及命中框是否落在地图/搜索窗内的判定。
 *
 * 上游对应：
 *  - `MapLocator.cpp:876-924 loadAvailableZones`（文件名 → zone key 规则）；
 *  - `MapTypes.h:151-172 TryExtractMinimap` + `GetMinimapRoiConfig`（小地图裁剪）；
 *  - `MapLocateAction.cpp:268-277 UsesAdbMinimapRoi`（adb/playcover 走 0.8 缩放 + y−7 变体）；
 *  - `MapLocator.cpp:1268-1332 startGlobalSearch`（ROI_FINE 时额外扩 `GlobalSearchRoiPad` 再裁到地图边界）。
 *
 * 本文件只吃像素尺寸/数组，不碰 Android / JNA / 文件系统，可在
 * [scripts/verify_pure_logic.sh] 本机回归。真正读 PNG、跑框架识别的部分在 MaaRunner。
 */
object MapLocatorCoarsePure {

    /**
     * 上游 `layerFileRegex`（`MapLocator.cpp:884`）：
     * `R"(Lv(\d+)Tier(\d+)\.(png|jpg|webp)$)"` + `boost::regex::icase`，用 `regex_search`
     * （子串搜索、只锚定结尾）。
     *
     * Kotlin [Regex.find] 与 `regex_search` 同义（任意位置起匹配），
     * [RegexOption.IGNORE_CASE] 对应 `icase`。
     */
    val LAYER_FILE_REGEX = Regex("""Lv(\d+)Tier(\d+)\.(png|jpg|webp)$""", RegexOption.IGNORE_CASE)

    /**
     * 上游 `loadAvailableZones` 的 key 规则（`MapLocator.cpp:894-909`）。
     *
     * 逐条照搬，注意三个容易踩的点：
     *  1. `base.png` 判定用的是**整个文件名小写后全等** `"base.png"`（所以 `Base.PNG` 命中、
     *     `base.jpeg` 不命中）；
     *  2. 层级文件的正则对**原始大小写**文件名做 `icase` 搜索，要求以 `.png/.jpg/.webp` 结尾；
     *  3. 其余取 `path.stem()`（最后一个点之前；隐藏文件/无扩展名按原样）。
     *
     * @param parentName 文件所在**父目录名**（上游 `parent_path().filename()`）。
     * @param fileName 文件名（含扩展名，原始大小写）。
     */
    fun mapZoneKey(parentName: String, fileName: String): String {
        if (fileName.lowercase() == "base.png") {
            return "${parentName}_Base"
        }
        val match = LAYER_FILE_REGEX.find(fileName)
        if (match != null) {
            val level = MapLocatorPure.trimLeadingZeros(match.groupValues[1])
            val tier = MapLocatorPure.trimLeadingZeros(match.groupValues[2])
            return "${parentName}_L${level}_${tier}"
        }
        return fileStem(fileName)
    }

    /**
     * `std::filesystem::path::stem()` 的等价物：
     *  - 最后一个 `.` 之后是扩展名，`a.tar.gz` → `a.tar`；
     *  - `.hidden` 的点在开头（无 stem 可切）→ 原样返回；
     *  - 无点 → 原样返回。
     */
    fun fileStem(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * 上游 `UsesAdbMinimapRoi`（`MapLocateAction.cpp:268-277`）：
     * controller type 大小写不敏感地等于 `adb` / `playcover` / `play_cover` 时为 true。
     *
     * 末影控制器是 `MaaAndroidNativeControllerCreate`（type 不是 adb），走默认 ROI。
     */
    fun usesAdbMinimapRoi(controllerType: String?): Boolean {
        val type = controllerType ?: return false
        return type.equals("adb", ignoreCase = true) ||
            type.equals("playcover", ignoreCase = true) ||
            type.equals("play_cover", ignoreCase = true)
    }

    /**
     * 一次小地图裁剪的几何计划。[scaledWidth]/[scaledHeight] 是（可选缩放后的）整图尺寸，
     * [roi] 是要从该尺寸里裁的矩形；两者都由调用方拿去裁像素。
     *
     * 返回 null 表示上游 `TryExtractMinimap` 会判失败的情况：ROI 被图像边界裁掉
     * （`clipped_roi != roi`）或宽高非正。
     */
    data class MinimapExtractPlan(
        val scaledWidth: Int,
        val scaledHeight: Int,
        val roi: MapRect,
    )

    /**
     * 上游 `TryExtractMinimap`（`MapTypes.h:151-172`）的几何部分。
     *
     * adb/playcover 控制器先把整图按 [ADB_MINIMAP_FULL_IMAGE_SCALE]（0.8）缩放，
     * 再套 [getMinimapRoiConfig] 的 ROI；其余控制器不缩放、用默认 ROI。
     *
     * 缩放尺寸取 `round(dim * scale)`：0.8 与整数尺寸相乘不会落在 `.5` 上，
     * 因此与 OpenCV `saturate_cast<int>(dim * 0.8)`（`cvRound`）一致。
     */
    fun minimapExtractPlan(imageWidth: Int, imageHeight: Int, useAdbRoi: Boolean): MinimapExtractPlan? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val scale = if (useAdbRoi) ADB_MINIMAP_FULL_IMAGE_SCALE else 1.0
        val scaledWidth = if (scale == 1.0) imageWidth else (imageWidth * scale).roundToInt()
        val scaledHeight = if (scale == 1.0) imageHeight else (imageHeight * scale).roundToInt()
        if (scaledWidth <= 0 || scaledHeight <= 0) return null

        val config = getMinimapRoiConfig(useAdbRoi)
        val roi = MapRect(config.x, config.y, config.width, config.height)
        val bounds = MapRect(0, 0, scaledWidth, scaledHeight)
        val clipped = roi.intersect(bounds)
        if (clipped.width != roi.width || clipped.height != roi.height || clipped.isEmpty) {
            return null
        }
        return MinimapExtractPlan(scaledWidth, scaledHeight, roi)
    }

    /**
     * 从 ARGB（`0xAARRGGBB`，Android `Bitmap.getPixels` 的输出）整图裁出小地图。
     *
     * adb 变体需要先缩放整图；本函数只处理**无需缩放**的路径（末影的 Android 原生
     * 控制器即如此）。需要缩放时返回 null，调用方须先把图缩到
     * [MinimapExtractPlan.scaledWidth]×[MinimapExtractPlan.scaledHeight] 再裁。
     *
     * @return `roi.width * roi.height` 的 ARGB 数组；尺寸不符/越界时 null。
     */
    fun extractMinimapArgb(argb: IntArray, width: Int, height: Int, useAdbRoi: Boolean): IntArray? {
        val plan = minimapExtractPlan(width, height, useAdbRoi) ?: return null
        if (plan.scaledWidth != width || plan.scaledHeight != height) return null
        if (argb.size != width * height) return null
        val roi = plan.roi
        val out = IntArray(roi.width * roi.height)
        for (row in 0 until roi.height) {
            val srcOffset = (roi.y + row) * width + roi.x
            System.arraycopy(argb, srcOffset, out, row * roi.width, roi.width)
        }
        return out
    }

    /**
     * 上游 `startGlobalSearch`（`MapLocator.cpp:1300-1319`）里「约束 ROI → 最终搜索矩形」那一步：
     *  - `FULL_MAP_FINE` → 整张地图 `[0,0,cols,rows]`；
     *  - `ROI_FINE` → 在 [buildSearchConstraint] 给出的 ROI 上再外扩
     *    `GlobalSearchRoiPad(模板尺寸)`（`MapLocator.cpp:1302-1307`），裁到地图边界。
     *
     * 返回 null 表示裁到边界后为空（上游 `Global Search Aborted`）。
     */
    fun constrainedSearchRoi(
        constraint: SearchConstraint,
        mapCols: Int,
        mapRows: Int,
        templateWidth: Int,
        templateHeight: Int,
    ): MapRect? {
        if (mapCols <= 0 || mapRows <= 0) return null
        val mapBounds = MapRect(0, 0, mapCols, mapRows)
        if (constraint.mode != GlobalSearchMode.ROI_FINE) return mapBounds

        val pad = MapLocatorPure.globalSearchRoiPad(templateWidth, templateHeight)
        val padded = MapRect(
            x = constraint.roi.x - pad,
            y = constraint.roi.y - pad,
            width = constraint.roi.width + pad * 2,
            height = constraint.roi.height + pad * 2,
        )
        val clipped = padded.intersect(mapBounds)
        return if (clipped.isEmpty) null else clipped
    }

    /** 命中框（`[x,y,w,h]`）是否完整落在 `cols×rows` 的地图内（宽高须为正）。 */
    fun isBoxWithinMap(box: IntArray?, mapCols: Int, mapRows: Int): Boolean {
        if (box == null || box.size < 4) return false
        val x = box[0]
        val y = box[1]
        val w = box[2]
        val h = box[3]
        if (w <= 0 || h <= 0) return false
        return x >= 0 && y >= 0 && x + w <= mapCols && y + h <= mapRows
    }

    /** 命中框是否完整落在搜索窗 [roi] 内（上游粗搜窗）。 */
    fun isBoxWithinRoi(box: IntArray?, roi: MapRect): Boolean {
        if (box == null || box.size < 4) return false
        val r = MapRect(box[0], box[1], box[2], box[3])
        if (r.isEmpty) return false
        return r.x >= roi.x && r.y >= roi.y &&
            r.x + r.width <= roi.x + roi.width &&
            r.y + r.height <= roi.y + roi.height
    }

    /**
     * 一次粗定位的判定结果，供 debug 探针回显与断言。
     *
     * [inMap] 是任务要求的「是否落在该 zone 的地图范围内」；[inRoi] 额外看是否落在搜索窗内
     * （框架匹配本应只在 ROI 内找，越 ROI 说明 roi 参数没被框架采纳）。
     */
    data class CoarseOutcome(
        val hit: Boolean,
        val inMap: Boolean,
        val inRoi: Boolean,
    )

    /** 组合判定：`hit && isBoxWithinMap && isBoxWithinRoi`。 */
    fun evaluateCoarseOutcome(
        hit: Boolean,
        box: IntArray?,
        mapCols: Int,
        mapRows: Int,
        roi: MapRect,
    ): CoarseOutcome = CoarseOutcome(
        hit = hit,
        inMap = isBoxWithinMap(box, mapCols, mapRows),
        inRoi = isBoxWithinRoi(box, roi),
    )

    /**
     * 从框架识别 detail JSON 里取 `best.score`（TemplateMatch 与 NeuralNetworkClassify 都是
     * `{all, filtered, best}` 形状）。取不到/类型不符返回 null，不抛。
     */
    fun bestMatchScore(detailJson: String?): Double? {
        val root = MaaJsonTree.parse(detailJson) as? Map<*, *> ?: return null
        val best = root["best"] as? Map<*, *> ?: return null
        return when (val score = best["score"]) {
            is Long -> score.toDouble()
            is Int -> score.toDouble()
            is Double -> score
            else -> null
        }
    }

    /**
     * 把「文件列表」按 [mapZoneKey] 建成 zoneId → 文件名 的索引（供真机侧找地图资产）。
     *
     * 上游是 `zones[key] = img`（`MapLocator.cpp:921`），同名 key 后写覆盖前写；这里保持
     * 「后写覆盖」的语义。
     *
     * @param entries `(parentName, fileName)` 序列，顺序即扫描顺序。
     */
    fun zoneIndex(entries: List<Pair<String, String>>): Map<String, Pair<String, String>> {
        val out = LinkedHashMap<String, Pair<String, String>>(entries.size)
        for ((parent, name) in entries) {
            out[mapZoneKey(parent, name)] = parent to name
        }
        return out
    }
}
