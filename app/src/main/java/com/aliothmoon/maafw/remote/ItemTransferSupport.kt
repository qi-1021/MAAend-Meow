package com.aliothmoon.maafw.remote

/**
 * 物品转移（ItemTransfer）整包的纯逻辑（对齐上游
 * `agent/go-service/itemtransfer/`，1078 行）。
 *
 * 上游注册三个名字，但**只有** `ItemTransferSameItemRecognition` 仍被 pipeline 引用
 * （节点 `ItemTransferSkipSameItem`）。`ItemTransferFallbackAction` / `ItemTransferOCRAction`
 * 是 README 明说的「兼容实现」：pipeline 已不再引用，且它们依赖的
 * `ItemTransferDetectAllItems` / `ItemTransferDetectAllItemsBag` / `ItemTransferTooltipOCR`
 * 三个节点在当前上游 assets 里根本不存在。故本层只移植**可单测的纯逻辑**，不去伪造识别。
 *
 * 覆盖的文件与函数：
 *  - `pkg/levenshtein/levenshtein.go` → [levenshteinDistance]（rune 级）
 *  - `action.go` 的匹配/索引/几何 → [matchesTarget] / [fuzzyIndexOf] / [resolveOCRIndex] /
 *    [buildFullGrid] / [buildSyntheticGrid] / [snapToGrid] / [computeTooltipROI] 等
 *  - `ocr_action.go` 的类别查找与网格常量
 *  - `types.go` 的类型与 [parseItemOrderData] / [inferSide]
 *  - `same_item_recognition.go` 的节点 JSON 解析 → [parseSelectedItemId]
 *
 * 风格照 [ReceptionRoomSupport] 与 [AutoEcoFarmNearest]：吃通用 JSON 树（[MaaJsonTree] 转好的
 * Map/List/String/Number/Boolean），不依赖 Android 与 JNA，因而能脱离设备单测。
 */
object ItemTransferSupport {

    // ───────────────────────── types.go 常量 ─────────────────────────

    const val COMPONENT_NAME = "itemtransfer"

    const val REPO_NND_NODE = "ItemTransferDetectAllItems"
    const val BAG_NND_NODE = "ItemTransferDetectAllItemsBag"
    const val TOOLTIP_OCR_NODE = "ItemTransferTooltipOCR"

    const val TOOLTIP_OFFSET_X = 31
    const val TOOLTIP_OFFSET_Y = 6
    const val TOOLTIP_WIDTH = 117
    const val TOOLTIP_HEIGHT = 58

    /** MAA 规范截图尺寸；tooltip ROI 的边界钳制按它算（上游硬编 1280x720）。 */
    const val SCREEN_WIDTH = 1280
    const val SCREEN_HEIGHT = 720

    // ───────────────────────── ocr_action.go 网格常量 ─────────────────────────

    const val GRID_CELL_SPACING = 69
    const val REPO_COLS = 8
    const val BAG_COLS = 5

    const val REPO_ROI_X = 158
    const val REPO_ROI_Y = 203
    const val BAG_ROI_X = 768
    const val BAG_ROI_Y = 209

    const val REPO_GRID_START_X = 191
    const val REPO_GRID_START_Y = 246
    const val REPO_MAX_ROWS = 4
    const val BAG_GRID_START_X = 871
    const val BAG_GRID_START_Y = 247
    const val BAG_MAX_ROWS = 4

    /** 合成网格用的哨兵 class：Go 的 `^uint64(0)`，Kotlin Long 全 1 即 -1。 */
    const val NO_CLASS_ID = -1L

    /** hover 出 tooltip 后等待其渲染的时间（上游 `hoverAndOCR` 里 sleep 1500ms）。 */
    const val HOVER_SETTLE_MILLIS = 1500L

    /** tooltip 文本里会混入的状态词；带它的行要丢掉。 */
    const val TOOLTIP_NOISE = "已盛装"

    // ───────────────────────── 类型（types.go / 各 param）─────────────────────────

    /** 上游 `itemInfo`。 */
    data class ItemInfo(val name: String, val category: String)

    /** 上游 `itemOrderData`。`items` 的键是 class id 的字符串，`categoryOrder` 是类别 → 有序物品名。 */
    data class ItemOrderData(
        val items: Map<String, ItemInfo>,
        val categoryOrder: Map<String, List<String>>,
    )

    /** 上游 `fallbackParams`。 */
    data class FallbackParams(
        val targetClass: Int,
        val descending: Boolean,
        val side: String,
        val maxDistance: Int,
    )

    /** 上游 `ocrActionParams`。 */
    data class OcrActionParams(
        val itemName: String,
        val descending: Boolean,
        val side: String,
        val maxDistance: Int,
    )

    /** 上游 `sameItemRecognitionParam`。 */
    data class SameItemParams(
        val forwardItemNode: String,
        val returnItemNode: String,
    )

    /** 上游 `gridItem`。`box` 允许为空（合成网格没有真实框）。 */
    data class GridItem(
        val box: List<Int> = emptyList(),
        val classId: Long = NO_CLASS_ID,
        val score: Double = 0.0,
        val centerX: Int = 0,
        val centerY: Int = 0,
    )

    /** [resolveOCRIndex] 的结果：命中的文本与其在类别顺序里的下标（未命中为 -1）。 */
    data class IndexedName(val name: String, val index: Int)

    // ───────────────────────── 参数解析 ─────────────────────────

    /**
     * 上游 `json.Unmarshal([]byte(arg.CustomActionParam), &fallbackParams{})`：
     * 类型不对整节点失败（返回 null）。空串在 Go 里是非法 JSON，同样返回 null。
     */
    fun parseFallbackParams(raw: String?): FallbackParams? {
        val map = asObject(raw) ?: return null
        return FallbackParams(
            targetClass = intOr(map, "target_class", 0) ?: return null,
            descending = boolOr(map, "descending", false) ?: return null,
            side = stringOr(map, "side", "") ?: return null,
            maxDistance = intOr(map, "max_distance", 0) ?: return null,
        )
    }

    /** 上游 `ocrActionParams` 的解析，语义同 [parseFallbackParams]。 */
    fun parseOcrActionParams(raw: String?): OcrActionParams? {
        val map = asObject(raw) ?: return null
        return OcrActionParams(
            itemName = stringOr(map, "item_name", "") ?: return null,
            descending = boolOr(map, "descending", false) ?: return null,
            side = stringOr(map, "side", "") ?: return null,
            maxDistance = intOr(map, "max_distance", 0) ?: return null,
        )
    }

    /** 上游 `sameItemRecognitionParam` 的解析，语义同 [parseFallbackParams]。 */
    fun parseSameItemParams(raw: String?): SameItemParams? {
        val map = asObject(raw) ?: return null
        return SameItemParams(
            forwardItemNode = stringOr(map, "forward_item_node", "") ?: return null,
            returnItemNode = stringOr(map, "return_item_node", "") ?: return null,
        )
    }

    /**
     * 上游 `types.go` 的 `item_order.json`（`itemOrderData`）解析。
     * 结构与上游 `resource.ReadJsonResource` 一致；缺 `items`/`category_order` 返回 null。
     */
    fun parseItemOrderData(tree: Any?): ItemOrderData? {
        val root = tree as? Map<*, *> ?: return null
        val itemsNode = root["items"] as? Map<*, *> ?: return null
        val orderNode = root["category_order"] as? Map<*, *> ?: return null

        val items = LinkedHashMap<String, ItemInfo>()
        for ((key, value) in itemsNode) {
            val classKey = key as? String ?: continue
            val info = value as? Map<*, *> ?: continue
            // Go 的 struct 反序列化：字段缺失 → 零值；这里保留同样的宽松度
            items[classKey] = ItemInfo(
                name = info["name"] as? String ?: "",
                category = info["category"] as? String ?: "",
            )
        }

        val categoryOrder = LinkedHashMap<String, List<String>>()
        for ((key, value) in orderNode) {
            val category = key as? String ?: continue
            categoryOrder[category] = (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        }

        return ItemOrderData(items, categoryOrder)
    }

    /**
     * 上游 `loadSelectedItemID`：从节点的
     * `recognition.param.custom_recognition_param.item_ids` 读**恰好一个**非空 ID。
     * 结构缺失、数量不为 1、或为空串都返回 null（整识别不命中）。
     *
     * 返回的是**未 trim** 的原始 ID，与上游一致（上游只 trim 后判空，比较时用原值）。
     */
    fun parseSelectedItemId(nodeJson: String?): String? {
        if (nodeJson.isNullOrBlank()) return null
        val root = MaaJsonTree.parse(nodeJson) as? Map<*, *> ?: return null
        var cursor: Any? = root
        for (key in listOf("recognition", "param", "custom_recognition_param")) {
            cursor = (cursor as? Map<*, *>)?.get(key) ?: return null
        }
        val itemIds = (cursor as? Map<*, *>)?.get("item_ids") as? List<*> ?: return null
        if (itemIds.size != 1) return null
        val id = itemIds[0] as? String ?: return null
        if (id.trim().isEmpty()) return null
        return id
    }

    /** 上游 `inferSide`：显式 side 优先，否则任务名含 `Bag` 判背包，其余仓库。 */
    fun inferSide(paramSide: String?, taskName: String?): String {
        if (!paramSide.isNullOrEmpty()) return paramSide
        if (taskName?.contains("Bag") == true) return "bag"
        return "repo"
    }

    // ───────────────────────── 物品名匹配 ─────────────────────────

    /** 上游 `indexOf`。 */
    fun indexOf(order: List<String>, name: String): Int {
        for (i in order.indices) {
            if (order[i] == name) return i
        }
        return -1
    }

    /**
     * 上游 `cleanOCRNoise`：去掉空格与 `· . , 、`。
     * 注意**不**去冒号、句号等其他标点，与上游逐字符列表一致。
     */
    fun cleanOCRNoise(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c == ' ' || c == '·' || c == '.' || c == ',' || c == '、') continue
            sb.append(c)
        }
        return sb.toString()
    }

    /** 上游 `matchesTarget`：精确相等，或去掉 OCR 噪声后相等。 */
    fun matchesTarget(ocrName: String?, targetName: String): Boolean {
        if (ocrName.isNullOrEmpty()) return false
        val trimmed = ocrName.trim()
        if (trimmed == targetName) return true
        val cleaned = cleanOCRNoise(trimmed)
        return cleaned.isNotEmpty() && cleaned == targetName
    }

    /** 上游 `matchesAnyTarget`。 */
    fun matchesAnyTarget(names: List<String>, targetName: String): Boolean =
        names.any { matchesTarget(it, targetName) }

    /**
     * 上游 `fuzzyIndexOf`：在有序候选里找编辑距离最小、且 ≤ `maxDistance` 的项。
     *
     * **必须保留 Go 的字节长度预筛**：上游先用 `len(n)-len(name)`（字节）快速剪枝，
     * 再用 rune 级 [levenshteinDistance]。对中文，字节差是字符差的 3 倍，剪枝更狠；
     * 若改成字符长度，会多匹配出上游根本不会考虑的候选。`maxDistance <= 0` 或名字空 → -1。
     */
    fun fuzzyIndexOf(order: List<String>, name: String, maxDistance: Int): Int {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || maxDistance <= 0) return -1
        var bestIdx = -1
        var bestDist = maxDistance + 1
        for (i in order.indices) {
            val candidate = order[i]
            val byteDiff = utf8Length(candidate) - utf8Length(trimmed)
            if (byteDiff > maxDistance || byteDiff < -maxDistance) continue
            val dist = levenshteinDistance(trimmed, candidate)
            if (dist <= maxDistance && dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    /**
     * 上游 `resolveOCRIndex`：先精确命中类别顺序，再退回模糊匹配；
     * 都没命中时返回第一个 OCR 名与 -1（调用方据此判断「本页没有」）。
     */
    fun resolveOCRIndex(names: List<String>, categoryOrder: List<String>, maxDistance: Int): IndexedName {
        for (n in names) {
            val i = indexOf(categoryOrder, n)
            if (i >= 0) return IndexedName(n, i)
        }
        for (n in names) {
            val i = fuzzyIndexOf(categoryOrder, n, maxDistance)
            if (i >= 0) return IndexedName(n, i)
        }
        if (names.isNotEmpty()) return IndexedName(names[0], -1)
        return IndexedName("", -1)
    }

    /** 上游 `findCategoryByName`：遍历 `category_order` 找物品属于哪个类别。 */
    fun findCategoryByName(data: ItemOrderData, name: String): String? {
        for ((category, order) in data.categoryOrder) {
            if (order.any { it == name }) return category
        }
        return null
    }

    /** 上游 `reversed`：返回拷贝并反转；空列表原样返回（上游返回同一 slice）。 */
    fun reversed(s: List<String>): List<String> {
        if (s.isEmpty()) return s
        return s.asReversed()
    }

    // ───────────────────────── levenshtein ─────────────────────────

    /**
     * 上游 `levenshtein.Distance`：rune 级编辑距离（多字节字符按码点比较）。
     *
     * Kotlin 的 `Char` 是 UTF-16 码元，代理对（emoji）会被算成两个；这里显式转码点，
     * 与 Go 的 `[]rune` 对齐。滚动数组，O(min) 空间。
     */
    fun levenshteinDistance(a: String, b: String): Int {
        val runesA = codePoints(a)
        val runesB = codePoints(b)
        val lenA = runesA.size
        val lenB = runesB.size

        var prev = IntArray(lenB + 1) { it }
        var curr = IntArray(lenB + 1)

        for (i in 1..lenA) {
            curr[0] = i
            for (j in 1..lenB) {
                val cost = if (runesA[i - 1] == runesB[j - 1]) 0 else 1
                val deletion = prev[j] + 1
                val insertion = curr[j - 1] + 1
                val substitution = prev[j - 1] + cost
                curr[j] = minOf(deletion, insertion, substitution)
            }
            val swap = prev
            prev = curr
            curr = swap
        }

        return prev[lenB]
    }

    // ───────────────────────── 网格几何（action.go / ocr_action.go）─────────────────────────

    /**
     * 上游 `buildSyntheticGrid`：NND 无检出时按固定布局生成整页网格。
     * 坐标是 1280x720 标准空间。
     */
    fun buildSyntheticGrid(side: String, cols: Int): List<GridItem> {
        val startX: Int
        val startY: Int
        val maxRows: Int
        if (side == "bag") {
            startX = BAG_GRID_START_X
            startY = BAG_GRID_START_Y
            maxRows = BAG_MAX_ROWS
        } else {
            startX = REPO_GRID_START_X
            startY = REPO_GRID_START_Y
            maxRows = REPO_MAX_ROWS
        }

        val grid = ArrayList<GridItem>(cols * maxRows)
        for (r in 0 until maxRows) {
            for (c in 0 until cols) {
                grid += GridItem(
                    classId = NO_CLASS_ID,
                    centerX = startX + c * GRID_CELL_SPACING,
                    centerY = startY + r * GRID_CELL_SPACING,
                )
            }
        }
        return grid
    }

    /**
     * 上游 `buildFullGrid`：以检出项为锚，向左走到 pipeline ROI 边界求网格原点，
     * 再保证最右检出被覆盖，最后每行补满 `cols` 列。
     *
     * 忠实保留上游那两个不太直观的写法：
     *  1. 行聚类判据是 `centerY - last.y/last.count <= spacing/2`（整数除法，累加均值）；
     *  2. 只按 centerY 排序（Kotlin 的稳定排序对相等 Y 的顺序与 Go 的 sort.Slice 可能不同，
     *     但相等 Y 本就属同一行，聚类结果一致）。
     */
    fun buildFullGrid(items: List<GridItem>, cols: Int, side: String): List<GridItem> {
        if (items.isEmpty()) return items

        val roiX = if (side == "bag") BAG_ROI_X else REPO_ROI_X

        val sorted = items.sortedBy { it.centerY }

        class Row(var y: Int, var count: Int)
        val rows = ArrayList<Row>()
        rows += Row(sorted[0].centerY, 1)
        for (i in 1 until sorted.size) {
            val last = rows.last()
            if (sorted[i].centerY - last.y / last.count <= GRID_CELL_SPACING / 2) {
                last.y += sorted[i].centerY
                last.count++
            } else {
                rows += Row(sorted[i].centerY, 1)
            }
        }
        val rowYs = rows.map { it.y / it.count }

        var minDetectedX = sorted[0].centerX
        var maxDetectedX = sorted[0].centerX
        for (i in 1 until sorted.size) {
            val it = sorted[i]
            if (it.centerX < minDetectedX) minDetectedX = it.centerX
            if (it.centerX > maxDetectedX) maxDetectedX = it.centerX
        }

        var startX = minDetectedX
        while (startX - GRID_CELL_SPACING >= roiX) {
            startX -= GRID_CELL_SPACING
        }

        val endX = startX + (cols - 1) * GRID_CELL_SPACING
        if (endX < maxDetectedX) {
            startX = maxDetectedX - (cols - 1) * GRID_CELL_SPACING
        }

        val grid = ArrayList<GridItem>(rowYs.size * cols)
        for (y in rowYs) {
            for (c in 0 until cols) {
                grid += GridItem(
                    classId = NO_CLASS_ID,
                    centerX = startX + c * GRID_CELL_SPACING,
                    centerY = y,
                )
            }
        }
        return grid
    }

    /** 上游 `findByLowScoreTarget`：找该 class 中分数最高的检出（同分取先出现的）。 */
    fun findByLowScoreTarget(items: List<GridItem>, targetClass: Int): GridItem? {
        var best: GridItem? = null
        for (item in items) {
            if (item.classId.toInt() == targetClass) {
                if (best == null || item.score > best.score) best = item
            }
        }
        return best
    }

    /**
     * 上游 `snapToGrid`：把任意点吸附到网格里欧氏距离平方最小的中心。
     * 用 Long 累加避免极端坐标下 Int 溢出（Go 的 int 是 64 位）。
     */
    fun snapToGrid(x: Int, y: Int, grid: List<GridItem>): IntArray {
        var bestX = x
        var bestY = y
        var bestDist = Long.MAX_VALUE
        for (g in grid) {
            val dx = (g.centerX - x).toLong()
            val dy = (g.centerY - y).toLong()
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                bestX = g.centerX
                bestY = g.centerY
            }
        }
        return intArrayOf(bestX, bestY)
    }

    /**
     * 上游 `computeTooltipROI`：hover 坐标 → tooltip 的 OCR ROI。
     * 右侧越界时翻到左侧；下侧越界时贴底；最后把负值钳到 0。
     */
    fun computeTooltipROI(hoverX: Int, hoverY: Int): List<Int> {
        var roiX = hoverX + TOOLTIP_OFFSET_X
        var roiY = hoverY + TOOLTIP_OFFSET_Y
        if (roiX + TOOLTIP_WIDTH > SCREEN_WIDTH) {
            roiX = hoverX - TOOLTIP_OFFSET_X - TOOLTIP_WIDTH
        }
        if (roiY + TOOLTIP_HEIGHT > SCREEN_HEIGHT) {
            roiY = SCREEN_HEIGHT - TOOLTIP_HEIGHT
        }
        if (roiX < 0) roiX = 0
        if (roiY < 0) roiY = 0
        return listOf(roiX, roiY, TOOLTIP_WIDTH, TOOLTIP_HEIGHT)
    }

    /**
     * 上游 `extractAllOCRTexts`：优先读 `filtered`，该序列一旦产出文本就返回；
     * 否则读 `all`。文本 trim 后去重（保持首次出现顺序）。
     */
    fun extractAllOCRTexts(detailTree: Any?): List<String> {
        val root = detailTree as? Map<*, *> ?: return emptyList()
        val container = (root["results"] as? Map<*, *>) ?: root
        for (key in listOf("filtered", "all")) {
            val list = container[key] as? List<*> ?: continue
            val seen = LinkedHashSet<String>()
            for (node in list) {
                val text = (node as? Map<*, *>)?.get("text") as? String ?: continue
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) seen += trimmed
            }
            if (seen.isNotEmpty()) return seen.toList()
        }
        return emptyList()
    }

    /** 上游 `hoverAndOCR` 末尾：丢掉带「已盛装」的状态行。 */
    fun filterTooltipTexts(texts: List<String>): List<String> =
        texts.filter { !it.contains(TOOLTIP_NOISE) }

    // ───────────────────────── 内部小工具 ─────────────────────────

    private fun asObject(raw: String?): Map<*, *>? = MaaJsonTree.parse(raw) as? Map<*, *>

    private fun stringOr(map: Map<*, *>, key: String, default: String): String? {
        if (!map.containsKey(key)) return default
        val value = map[key] ?: return default
        return value as? String ?: return null
    }

    private fun intOr(map: Map<*, *>, key: String, default: Int): Int? {
        if (!map.containsKey(key)) return default
        val value = map[key] ?: return default
        return (value as? Number)?.toInt() ?: return null
    }

    private fun boolOr(map: Map<*, *>, key: String, default: Boolean): Boolean? {
        if (!map.containsKey(key)) return default
        val value = map[key] ?: return default
        return value as? Boolean ?: return null
    }

    /** Go 的 `len(s)`：UTF-8 字节长度。 */
    private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** 显式按码点切分，对齐 Go 的 `[]rune`（代理对算一个字符）。 */
    private fun codePoints(s: String): IntArray {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out += cp
            i += Character.charCount(cp)
        }
        return out.toIntArray()
    }
}
