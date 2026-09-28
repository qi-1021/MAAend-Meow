package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * 情报档案库（对齐上游 `agent/go-service/intelarchive/`，整包 904 行）的纯逻辑层。
 *
 * 上游有 2 个 custom recognition + 3 个 custom action：
 *  - `IntelArchiveScanItemsRecognition`：列表页跑两段组合识别（标题 OCR / 无名密文框），
 *    与目录比对入库；多页、省略号对不上、无名卡片交给 `ResolveTruncAction` 点开细看。
 *  - `IntelArchiveScanDetailRecognition`：详情页标题 OCR，与目录比对入库。
 *  - `IntelArchiveResolveTruncAction`：逐个点开待处理条目，跑详情解析子流水线。
 *  - `IntelArchiveResetSessionAction` / `IntelArchiveShowInventoryAction`：清会话 / 生成导入链接。
 *
 * 目录来自 `data/IntelArchive/catalog.json`（分类页签）与 `items.json`（条目到「解锁 ID」的映射）。
 * 「解锁 ID」在有条目的 pages 时是 page.id，否则是 item.id；导入链接就是已解锁集合的补集。
 *
 * 这里刻意不依赖 android.util.Log / JNA / pkg/maafocus：吃通用 JSON 树，吐数据或失败原因，
 * 于是可以脱离设备本地单测。文件读取与 MaaFramework 调用留在 MaaRunner。
 */
object IntelArchiveSupport {

    // ───────────────────────── 常量（对齐上游 store.go / scan.go）─────────────────────────

    /** 上游 `fileCategories`：`items[].fileCategory` 的合法取值集合。 */
    val FILE_CATEGORIES: Set<String> = setOf(
        "investigate",
        "paper",
        "digital",
        "media",
        "collection",
        "document",
        "report",
    )

    /** 上游 `itemTextNode`：列表页标题组合识别节点。 */
    const val ITEM_TEXT_NODE = "IntelArchiveRecognitionItemText"

    /** 上游 `itemSecretNode`：列表页无名密文组合识别节点。 */
    const val ITEM_SECRET_NODE = "IntelArchiveRecognitionItemSecretWithoutPageCount"

    /** 上游 `itemOCRIndex`：`IntelArchiveRecognitionItemText.all_of` 里 OCR 是第 5 段（下标 4）。 */
    const val ITEM_OCR_INDEX = 4

    /** 上游 `itemSecretBoxIndex`：密文框识别里取框的子项下标。 */
    const val ITEM_SECRET_BOX_INDEX = 4

    /** 上游 `detailRecognitionNode`：详情页标题组合识别节点。 */
    const val DETAIL_RECOGNITION_NODE = "IntelArchiveRecognitionDetailTitleText"

    /** 上游 `detailOCRIndex`：详情页标题 `all_of` 里 OCR 是第 3 段（下标 2）。 */
    const val DETAIL_OCR_INDEX = 2

    /** 上游 `truncatedItemNode`：点开待处理条目的子流水线入口。 */
    const val TRUNCATED_ITEM_NODE = "IntelArchiveResolveTrunc"

    /** 上游 `secretUnlockID` / `secretUnlockName`：无名密文卡片强制解锁的固定条目。 */
    const val SECRET_UNLOCK_ID = "nar_digital_map02_13003_1"
    const val SECRET_UNLOCK_NAME = "文明保护协定"

    /** 上游 `data/IntelArchive/` 下的两个数据文件（打包链路 data 目录白名单已带入 pi.zip）。 */
    const val CATALOG_RELATIVE = "data/IntelArchive/catalog.json"
    const val ITEMS_RELATIVE = "data/IntelArchive/items.json"

    private const val IMPORT_URL_PREFIX = "https://oem.re/i/MAE-0-"

    // ───────────────────────── 数据模型 ─────────────────────────

    /** 上游 `truncatedItem`。`box` 为 `[x, y, w, h]`；无名密文条目的 `text` 为空。 */
    data class TruncatedItem(val text: String, val box: List<Int>)

    /** 上游 `catalogIndex`，加上几个查询方法。 */
    class CatalogIndex internal constructor(
        private val nameToIds: Map<String, List<String>>,
        private val nameToItems: Map<String, List<String>>,
        private val pageToItem: Map<String, String>,
        private val itemPageCount: Map<String, Int>,
        private val normToOrig: Map<String, String>,
        private val unlockCategory: Map<String, String>,
        /** 全部解锁 ID（叶序：条目本身 + pages），顺序由 itemIdLess 排序决定，与上游一致。 */
        val allUnlockIds: List<String>,
    ) {
        /** 上游 `displayName`：规范化键还原成首次登记时的原串。 */
        fun displayName(norm: String): String = normToOrig[norm] ?: norm

        /** 上游 `matchOCR`：唯一前缀匹配，再按 `fileCategory` 过滤解锁 ID。 */
        fun matchOcr(ocr: String?, fileCategory: String): Pair<List<String>, String> {
            val normalized = normalizeTitle(ocr)
            if (normalized.isEmpty()) return emptyList<String>() to ""
            val (ids, matchedName) = uniquePrefixIds(nameToIds, normalized)
            val filtered = filterUnlockIds(ids, fileCategory)
            if (filtered.isEmpty()) return emptyList<String>() to ""
            val display = if (matchedName.isEmpty()) normalized else matchedName
            return filtered to displayName(display)
        }

        /** 上游 `shouldOpenFromList`：命中多页条目才需要点开列表项。 */
        fun shouldOpenFromList(ocr: String?, fileCategory: String): Boolean {
            val normalized = normalizeTitle(ocr)
            if (normalized.isEmpty()) return false
            val (itemIds, _) = uniquePrefixIds(nameToItems, normalized)
            for (itemId in filterUnlockIds(itemIds, fileCategory)) {
                if ((itemPageCount[itemId] ?: 0) > 1) return true
            }
            val (unlockIds, _) = matchOcr(normalized, fileCategory)
            for (id in unlockIds) {
                val itemId = pageToItem[id]?.takeIf { it.isNotEmpty() } ?: id
                if ((itemPageCount[itemId] ?: 0) > 1) return true
            }
            return false
        }

        fun filterUnlockIds(ids: List<String>, fileCategory: String): List<String> {
            if (fileCategory.isEmpty()) return ids
            return ids.filter { unlockCategory[it] == fileCategory }
        }
    }

    sealed interface CatalogOutcome {
        data class Ok(val index: CatalogIndex) : CatalogOutcome
        data class Invalid(val reason: String) : CatalogOutcome
    }

    /** `unlockByNames` 的匹配结果：命中的解锁 ID、ID→展示名、未命中的 OCR 名。 */
    data class NameMatch(
        val ids: List<String>,
        val idToName: Map<String, String>,
        val misses: List<String>,
    )

    /** `ScanItemsRecognition` 的分类结果。 */
    data class ScanClassification(
        /** 上游：无名密文存在且 `fileCategory` 为空或 digital 时强制解锁固定条目。 */
        val secretForceUnlock: Boolean,
        /** 直接按名入库的标题。 */
        val names: List<String>,
        /** 交给 `ResolveTruncAction` 点开的条目。 */
        val truncated: List<TruncatedItem>,
    )

    // ───────────────────────── 目录加载缓存 ─────────────────────────

    @Volatile
    private var catalogCache: CatalogIndex? = null

    @Volatile
    private var catalogError: String? = null

    /** 上游 `loadCatalogIndex`：首次成功/失败都缓存，后续不再读盘。 */
    fun ensureLoaded(catalogText: String?, itemsText: String?): CatalogOutcome {
        catalogCache?.let { return CatalogOutcome.Ok(it) }
        catalogError?.let { return CatalogOutcome.Invalid(it) }
        synchronized(this) {
            catalogCache?.let { return CatalogOutcome.Ok(it) }
            catalogError?.let { return CatalogOutcome.Invalid(it) }
            val outcome = buildCatalogIndex(
                MaaJsonTree.parse(catalogText),
                MaaJsonTree.parse(itemsText),
            )
            when (outcome) {
                is CatalogOutcome.Ok -> catalogCache = outcome.index
                is CatalogOutcome.Invalid -> catalogError = outcome.reason
            }
            return outcome
        }
    }

    fun loadedCatalog(): CatalogIndex? = catalogCache

    /** 仅供测试：清掉进程内的目录缓存。 */
    fun clearCatalogCache() {
        synchronized(this) {
            catalogCache = null
            catalogError = null
        }
    }

    // ───────────────────────── 目录构建（对齐 buildCatalogIndex）─────────────────────────

    fun buildCatalogIndex(catalogTree: Any?, itemsTree: Any?): CatalogOutcome {
        val cat = catalogTree as? Map<*, *> ?: return CatalogOutcome.Invalid("catalog is nil")
        val itemsRoot = itemsTree as? Map<*, *> ?: return CatalogOutcome.Invalid("items is nil")
        val itemsNode = itemsRoot["items"] as? Map<*, *>
            ?: return CatalogOutcome.Invalid("items is nil")

        val tagIds = LinkedHashMap<String, String>()
        val categories = cat["categories"] as? List<*> ?: return CatalogOutcome.Invalid("catalog categories is nil")
        for (rawCategory in categories) {
            val category = rawCategory as? Map<*, *> ?: return CatalogOutcome.Invalid("category is not an object")
            val id = category["id"] as? String ?: ""
            if (id.isEmpty()) return CatalogOutcome.Invalid("category id is empty")
            val name = category["name"] as? String ?: ""
            if (name.isEmpty()) return CatalogOutcome.Invalid("category $id name is empty")
            val prev = tagIds[id]
            if (prev != null) return CatalogOutcome.Invalid("duplicate category id $id ($prev and $name)")
            tagIds[id] = name
        }

        val ids = itemsNode.keys.mapNotNull { it as? String }.sortedWith { a, b ->
            when {
                itemIdLess(a, b) -> -1
                a == b -> 0
                else -> 1
            }
        }

        val nameToIds = LinkedHashMap<String, MutableList<String>>()
        val nameToItems = LinkedHashMap<String, MutableList<String>>()
        val pageToItem = LinkedHashMap<String, String>()
        val itemPageCount = LinkedHashMap<String, Int>()
        val normToOrig = LinkedHashMap<String, String>()
        val unlockCategory = LinkedHashMap<String, String>()
        val allUnlockIds = mutableListOf<String>()

        for (id in ids) {
            val rawItem = itemsNode[id]
            val it = rawItem as? Map<*, *>
                ?: return CatalogOutcome.Invalid("item $id names is empty")
            val declaredId = it["id"] as? String ?: ""
            if (declaredId.isNotEmpty() && declaredId != id) {
                return CatalogOutcome.Invalid("item key $id mismatches id $declaredId")
            }
            val names = it["names"] as? Map<*, *>
                ?: return CatalogOutcome.Invalid("item $id names is empty")
            val zhCn = (names["zh_cn"] as? String).orEmpty().trim()
            if (zhCn.isEmpty()) return CatalogOutcome.Invalid("item $id names.zh_cn is empty")

            val fileCategory = it["fileCategory"] as? String ?: ""
            if (fileCategory !in FILE_CATEGORIES) {
                return CatalogOutcome.Invalid("item $id has unknown fileCategory $fileCategory")
            }
            val page = it["page"] as? String ?: ""
            if (page.isEmpty()) return CatalogOutcome.Invalid("item $id page is empty")
            if (!tagIds.containsKey(page)) {
                return CatalogOutcome.Invalid("item $id references unknown page $page")
            }

            var pageInTags = false
            val tagIdsList = it["tagIds"] as? List<*> ?: emptyList<Any?>()
            for (rawTag in tagIdsList) {
                val tagId = rawTag as? String ?: ""
                if (tagId.isEmpty()) return CatalogOutcome.Invalid("item $id has empty tag id")
                if (!tagIds.containsKey(tagId)) {
                    return CatalogOutcome.Invalid("item $id references unknown tagId $tagId")
                }
                if (tagId == page) pageInTags = true
            }
            if (!pageInTags) {
                return CatalogOutcome.Invalid("item $id tagIds does not include page $page")
            }

            val pages = it["pages"] as? List<*> ?: emptyList<Any?>()
            itemPageCount[id] = pages.size
            unlockCategory[id] = fileCategory
            indexItemName(nameToItems, normToOrig, zhCn, id)
            val zhTw = (names["zh_tw"] as? String).orEmpty().trim()
            if (zhTw.isNotEmpty()) indexItemName(nameToItems, normToOrig, zhTw, id)

            if (pages.isEmpty()) allUnlockIds += id

            for ((index, rawPage) in pages.withIndex()) {
                val pageMap = rawPage as? Map<*, *>
                    ?: return CatalogOutcome.Invalid("item $id pages[$index] is not an object")
                val pageId = pageMap["id"] as? String ?: ""
                if (pageId.isEmpty()) return CatalogOutcome.Invalid("item $id pages[$index] id is empty")
                val pageNames = pageMap["names"] as? Map<*, *>
                    ?: return CatalogOutcome.Invalid("item $id pages[$index] names is empty")
                val pageCn = (pageNames["zh_cn"] as? String).orEmpty().trim()
                if (pageCn.isEmpty()) {
                    return CatalogOutcome.Invalid("item $id pages[$index] names.zh_cn is empty")
                }
                pageToItem[pageId] = id
                unlockCategory[pageId] = fileCategory
                allUnlockIds += pageId
                indexItemName(nameToIds, normToOrig, pageCn, pageId)
                val pageTw = (pageNames["zh_tw"] as? String).orEmpty().trim()
                if (pageTw.isNotEmpty()) indexItemName(nameToIds, normToOrig, pageTw, pageId)
            }

            indexItemTitles(nameToIds, normToOrig, it, pages, zhCn)
        }

        return CatalogOutcome.Ok(
            CatalogIndex(
                nameToIds = nameToIds.mapValues { it.value.toList() },
                nameToItems = nameToItems.mapValues { it.value.toList() },
                pageToItem = pageToItem,
                itemPageCount = itemPageCount,
                normToOrig = normToOrig,
                unlockCategory = unlockCategory,
                allUnlockIds = allUnlockIds,
            ),
        )
    }

    /** 上游 `indexItemTitles`：无 pages 用条目名，单页用页名，多页标题不入标题表。 */
    private fun indexItemTitles(
        nameToIds: MutableMap<String, MutableList<String>>,
        normToOrig: MutableMap<String, String>,
        it: Map<*, *>,
        pages: List<*>,
        zhCn: String,
    ) {
        val zhTw = (it["names"] as? Map<*, *>)?.get("zh_tw")?.let { it as? String }.orEmpty().trim()
        val id = it["id"] as? String ?: ""
        when (pages.size) {
            0 -> {
                indexItemName(nameToIds, normToOrig, zhCn, id)
                if (zhTw.isNotEmpty()) indexItemName(nameToIds, normToOrig, zhTw, id)
            }

            1 -> {
                val pageId = (pages[0] as? Map<*, *>)?.get("id") as? String ?: ""
                if (pageId.isEmpty()) return
                indexItemName(nameToIds, normToOrig, zhCn, pageId)
                if (zhTw.isNotEmpty()) indexItemName(nameToIds, normToOrig, zhTw, pageId)
            }

            else -> {}
        }
    }

    /** 上游 `itemIDLess`：能转整数的按数值比，否则按字符串。 */
    fun itemIdLess(a: String, b: String): Boolean {
        val ai = a.toIntOrNull()
        val bi = b.toIntOrNull()
        if (ai != null && bi != null) return ai < bi
        return a < b
    }

    private fun indexItemName(
        dst: MutableMap<String, MutableList<String>>,
        normToOrig: MutableMap<String, String>,
        name: String,
        id: String,
    ) = indexNamed(dst, normToOrig, name, id)

    /** 上游 `indexNamed`：规范化后登记，同一键下同一 ID 只留一次。 */
    private fun indexNamed(
        dst: MutableMap<String, MutableList<String>>,
        normToOrig: MutableMap<String, String>,
        name: String,
        id: String,
    ) {
        val key = normalizeTitle(name)
        if (key.isEmpty() || id.isEmpty()) return
        val list = dst.getOrPut(key) { mutableListOf() }
        if (list.contains(id)) return
        list += id
        if (normToOrig[key].isNullOrEmpty()) normToOrig[key] = name.trim()
    }

    /** 上游 `normalizeTitle`：折平 OCR 的全角括号与破折号形近字符。 */
    fun normalizeTitle(s: String?): String {
        var value = s.orEmpty().trim()
        if (value.isEmpty()) return ""
        value = value.replace("　", "")
        value = value.replace("一一", "-")
        value = value
            .replace("（", "(")
            .replace("）", ")")
            .replace("—", "-")
            .replace("–", "-")
            .replace("―", "-")
            .replace("－", "-")
            .replace("梦魔", "梦魇")
        while (value.contains("--")) value = value.replace("--", "-")
        return value
    }

    /** 上游 `uniquePrefixIDs`：精确优先；前缀命中多于一个不同名则判歧义。 */
    fun uniquePrefixIds(table: Map<String, List<String>>, ocr: String): Pair<List<String>, String> {
        if (ocr.isEmpty()) return emptyList<String>() to ""
        table[ocr]?.let { return it.toList() to ocr }
        var matchedName = ""
        for ((name, _) in table) {
            if (!name.startsWith(ocr)) continue
            if (matchedName.isEmpty()) {
                matchedName = name
                continue
            }
            if (name != matchedName) return emptyList<String>() to ""
        }
        if (matchedName.isEmpty()) return emptyList<String>() to ""
        return (table[matchedName] ?: emptyList()).toList() to matchedName
    }

    /** 上游 `unlockByNames` 的匹配部分：逐个 OCR 名查目录，返回命中 ID / 展示名 / 未命中名。 */
    fun matchNames(idx: CatalogIndex, names: List<String>, fileCategory: String): NameMatch {
        val ids = mutableListOf<String>()
        val idToName = LinkedHashMap<String, String>()
        val misses = mutableListOf<String>()
        for (name in names) {
            if (name.isEmpty()) continue
            val (matched, full) = idx.matchOcr(name, fileCategory)
            if (matched.isEmpty()) {
                misses += name
                continue
            }
            for (id in matched) {
                ids += id
                if (idToName[id].isNullOrEmpty()) idToName[id] = full
            }
        }
        return NameMatch(ids, idToName, misses)
    }

    /** 上游 `parseScanFileCategory`：空串放行；JSON 非法或字段类型不对返回空串（调用方照常扫描）。 */
    fun parseScanFileCategory(raw: String?): String {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return ""
        val root = MaaJsonTree.parse(text) as? Map<*, *> ?: return ""
        return (root["file_category"] as? String).orEmpty().trim()
    }

    // ───────────────────────── 列表分类（对齐 ScanItemsRecognition.Run 主循环）─────────────────────────

    fun classifyListItems(
        idx: CatalogIndex,
        titles: List<TruncatedItem>,
        secret: List<TruncatedItem>,
        fileCategory: String,
    ): ScanClassification {
        val truncated = mutableListOf<TruncatedItem>()
        val forceSecret = secret.isNotEmpty() && (fileCategory.isEmpty() || fileCategory == "digital")
        if (secret.isNotEmpty() && !forceSecret) {
            truncated += secret
        }

        val names = mutableListOf<String>()
        for (item in titles) {
            val (query, trunc) = stripTrailingEllipsis(item.text)
            val lookup = query.ifEmpty { item.text }
            if (idx.shouldOpenFromList(lookup, fileCategory)) {
                truncated += item
                continue
            }
            if (trunc && query.isNotEmpty()) {
                val (matched, _) = idx.matchOcr(query, fileCategory)
                if (matched.isNotEmpty()) {
                    names += query
                    continue
                }
                truncated += item
                continue
            }
            if (trunc) {
                truncated += item
                continue
            }
            names += item.text
        }
        return ScanClassification(forceSecret, names, truncated)
    }

    /** 上游 `stripTrailingEllipsis`：只剥结尾的 `.`/`．`/`。`/`…`，中间的点不动。 */
    fun stripTrailingEllipsis(s: String?): Pair<String, Boolean> {
        val value = s.orEmpty().trim()
        if (value.isEmpty()) return "" to false
        var end = value.length
        var dots = 0
        var ellipsis = 0
        while (end > 0) {
            when (value[end - 1]) {
                '.', '．', '。' -> {
                    end--
                    dots++
                }

                '…' -> {
                    end--
                    ellipsis++
                }

                else -> break
            }
        }
        if (ellipsis == 0 && dots == 0) return value to false
        return value.substring(0, end).trim() to true
    }

    // ───────────────────────── 识别 detail 解析 ─────────────────────────

    /**
     * 上游 `filteredItems`：组合识别（And）的 C detail JSON 是顶层数组，
     * 取第 [index] 段的 `detail`（OCR 为 `{all,best,filtered}`）里的 `filtered`。
     *
     * `detail` 在 C JSON 里通常是内嵌对象；若遇到被序列化成字符串的形态也兼容。
     */
    fun filteredItems(detailTree: Any?, index: Int): List<TruncatedItem> {
        if (index < 0) return emptyList()
        val combined = detailTree as? List<*> ?: return emptyList()
        if (index >= combined.size) return emptyList()
        val element = combined[index] as? Map<*, *> ?: return emptyList()
        val detailNode = element["detail"]
        val parsed = when (detailNode) {
            is String -> MaaJsonTree.parse(detailNode)
            else -> detailNode
        }
        val detailMap = parsed as? Map<*, *> ?: return emptyList()
        val filtered = detailMap["filtered"] as? List<*> ?: return emptyList()
        val out = mutableListOf<TruncatedItem>()
        for (raw in filtered) {
            val map = raw as? Map<*, *> ?: continue
            val text = (map["text"] as? String).orEmpty().trim()
            val box = readBox(map["box"])
            if (text.isEmpty() && !(box.size == 4 && box[2] > 0 && box[3] > 0)) continue
            out += TruncatedItem(text, box)
        }
        return out
    }

    /**
     * 上游 `parseTruncated`：动作参数里的识别详情可能是
     * `{"best":{"detail":<对象或 JSON 字符串>}}` 包裹，也可能直接是 `{"truncated":[...]}`。
     */
    fun parseTruncated(detailTree: Any?): List<TruncatedItem> {
        val root = detailTree as? Map<*, *> ?: return emptyList()
        var payload: Any? = root
        val best = root["best"] as? Map<*, *>
        if (best != null) {
            val detail = best["detail"]
            val nonEmpty = when (detail) {
                is String -> detail.isNotEmpty()
                null -> false
                else -> true
            }
            if (nonEmpty) {
                payload = if (detail is String) (MaaJsonTree.parse(detail) ?: detail) else detail
            }
        }
        val payloadMap = payload as? Map<*, *> ?: return emptyList()
        val truncated = payloadMap["truncated"] as? List<*> ?: return emptyList()
        return truncated.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            TruncatedItem((map["text"] as? String).orEmpty(), readBox(map["box"]))
        }
    }

    private fun readBox(node: Any?): List<Int> {
        val list = node as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toInt() }
    }

    // ───────────────────────── 会话状态 ─────────────────────────

    @Volatile
    private var sessionUnlocked: MutableList<String> = mutableListOf()

    /** 上游全局 `listFileCategory`：`ScanItems` 记下本次的文件类别，`ScanDetail` 复用。 */
    @Volatile
    private var listFileCategory: String = ""

    /** 上游 `listFileCategory` 的读写。 */
    fun setListFileCategory(fileCategory: String) {
        listFileCategory = fileCategory
    }

    fun currentListFileCategory(): String = listFileCategory

    /**
     * 列表页识别与 `ResolveTruncAction` 同进程、相邻执行；上游靠识别详情的 `detail` 传递
     * 待点开条目，但本项目的 JNA 库未绑定 `MaaStringBufferSet`，无法回写 out_detail，
     * 改为同实例内存交接（见 MaaRunner 注释）。
     */
    @Volatile
    private var pendingTruncated: List<TruncatedItem> = emptyList()

    fun storePendingTruncated(items: List<TruncatedItem>) {
        pendingTruncated = items.toList()
    }

    /** 取走并清空待点开条目，避免下一轮误用上一屏结果。 */
    fun takePendingTruncated(): List<TruncatedItem> {
        val current = pendingTruncated
        pendingTruncated = emptyList()
        return current
    }

    fun resetSession() {
        sessionUnlocked = mutableListOf()
        pendingTruncated = emptyList()
    }

    fun sessionUnlockedIds(): List<String> = sessionUnlocked.toList()

    /** 上游 `dedupeStrings`：去空白、去空、保序去重；空输入返回空列表。 */
    fun dedupeStrings(ids: List<String>?): List<String> {
        if (ids.isNullOrEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (raw in ids) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            seen += id
        }
        return seen.toList()
    }

    /** 上游 `unlockItems`：把新解锁 ID 追加进会话，返回本次新增的部分。 */
    fun unlockItems(itemIds: List<String>): List<String> {
        if (itemIds.isEmpty()) return emptyList()
        synchronized(this) {
            val owned = sessionUnlocked.toMutableSet()
            val added = mutableListOf<String>()
            for (id in itemIds) {
                if (id.isEmpty()) continue
                if (!owned.add(id)) continue
                sessionUnlocked = sessionUnlocked.toMutableList().also { it += id }
                added += id
            }
            return added
        }
    }

    /** 上游 `buildIntelImportURL`：已解锁 + 补集 → gzip → base64url → OEA 导入链接。 */
    fun buildIntelImportUrl(collected: List<String>, allUnlockIds: List<String>): String {
        val collectedDedup = dedupeStrings(collected)
        val owned = collectedDedup.toSet()
        val notCollected = allUnlockIds.filter { it.isNotEmpty() && it !in owned }

        val payload = buildJsonObject {
            // 键序对齐 Go map 序列化的字典序（data < majorVersion < minorVersion）
            put(
                "data",
                buildJsonObject {
                    put("oeaVersion", "maaend")
                    put(
                        "prtsAllItems",
                        buildJsonObject {
                            put("collected", JsonArray(collectedDedup.map { JsonPrimitive(it) }))
                            put("notCollected", JsonArray(notCollected.map { JsonPrimitive(it) }))
                        },
                    )
                },
            )
            put("majorVersion", 0)
            put("minorVersion", 0)
        }

        val compressed = ByteArrayOutputStream().use { buffer ->
            GZIPOutputStream(buffer).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            buffer.toByteArray()
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return IMPORT_URL_PREFIX + encoded
    }
}
