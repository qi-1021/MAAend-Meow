package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.ItemTransferSupport.FallbackParams
import com.aliothmoon.maafw.remote.ItemTransferSupport.GridItem
import com.aliothmoon.maafw.remote.ItemTransferSupport.IndexedName
import com.aliothmoon.maafw.remote.ItemTransferSupport.ItemInfo
import com.aliothmoon.maafw.remote.ItemTransferSupport.OcrActionParams
import com.aliothmoon.maafw.remote.ItemTransferSupport.SameItemParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物品转移纯逻辑测试（对齐上游 itemtransfer，1078 行）。
 *
 * 重点钉住三类「写错只会静默判错分支」的规则：
 *  1. Levenshtein 是 **rune 级**（emoji 代理对算一个字符）；
 *  2. `fuzzyIndexOf` 的 **Go 字节长度预筛**（中文下与字符长度不同，剪枝更狠）；
 *  3. `parseSelectedItemId` 必须恰好一个非空 ID，多/少/空都判不命中。
 */
class ItemTransferSupportTest {

    // ───────────────────────── levenshtein ─────────────────────────

    @Test
    fun `levenshtein 基本与经典用例`() {
        assertEquals(0, ItemTransferSupport.levenshteinDistance("", ""))
        assertEquals(0, ItemTransferSupport.levenshteinDistance("abc", "abc"))
        assertEquals(3, ItemTransferSupport.levenshteinDistance("", "abc"))
        assertEquals(3, ItemTransferSupport.levenshteinDistance("abc", ""))
        assertEquals(3, ItemTransferSupport.levenshteinDistance("kitten", "sitting"))
    }

    @Test
    fun `levenshtein 多字节按码点比较`() {
        assertEquals(1, ItemTransferSupport.levenshteinDistance("矿石", "矿石x"))
        assertEquals(1, ItemTransferSupport.levenshteinDistance("矿石", "矿"))
        assertEquals(2, ItemTransferSupport.levenshteinDistance("矿石", "木材"))
    }

    @Test
    fun `levenshtein 代理对算一个字符`() {
        // 若按 UTF-16 码元算，长度 2 vs 4 会得到 2；按码点则是 1
        assertEquals(1, ItemTransferSupport.levenshteinDistance("😀", "😀😀"))
        assertEquals(1, ItemTransferSupport.levenshteinDistance("a😀", "a😁"))
    }

    @Test
    fun `levenshtein 对称`() {
        assertEquals(
            ItemTransferSupport.levenshteinDistance("矿石电池", "矿石"),
            ItemTransferSupport.levenshteinDistance("矿石", "矿石电池"),
        )
    }

    // ───────────────────────── fuzzyIndexOf ─────────────────────────

    private val order = listOf("矿石", "木材", "铁矿")

    @Test
    fun `fuzzyIndexOf 精确距离零命中`() {
        assertEquals(0, ItemTransferSupport.fuzzyIndexOf(order, "矿石", 1))
        assertEquals(1, ItemTransferSupport.fuzzyIndexOf(order, "木材", 1))
        assertEquals(2, ItemTransferSupport.fuzzyIndexOf(order, "铁矿", 1))
    }

    @Test
    fun `fuzzyIndexOf 阈值内取最近`() {
        assertEquals(0, ItemTransferSupport.fuzzyIndexOf(order, "矿右", 1))
        assertEquals(0, ItemTransferSupport.fuzzyIndexOf(order, "矿石x", 1))
        assertEquals(1, ItemTransferSupport.fuzzyIndexOf(listOf("axxx", "abce"), "abcx", 2))
    }

    @Test
    fun `fuzzyIndexOf maxDistance 非正或空名判失败`() {
        assertEquals(-1, ItemTransferSupport.fuzzyIndexOf(order, "矿石", 0))
        assertEquals(-1, ItemTransferSupport.fuzzyIndexOf(order, "矿石", -3))
        assertEquals(-1, ItemTransferSupport.fuzzyIndexOf(order, "", 2))
        assertEquals(-1, ItemTransferSupport.fuzzyIndexOf(order, "   ", 2))
    }

    @Test
    fun `fuzzyIndexOf 超出阈值判失败`() {
        assertEquals(-1, ItemTransferSupport.fuzzyIndexOf(order, "完全不同", 1))
        assertEquals(-1, ItemTransferSupport.fuzzyIndexOf(order, "abcdef", 1))
    }

    /**
     * Go 用 `len()`（字节）预筛。`"a"`（1 字节）与 `"中"`（3 字节）字节差 2 > 1，
     * 因此 maxDistance=1 时不考虑；若用字符长度（0）就会匹配成功。
     */
    @Test
    fun `fuzzyIndexOf 保留 Go 的字节长度预筛`() {
        assertEquals(-1, ItemTransferSupport.fuzzyIndexOf(listOf("中"), "a", 1))
        assertEquals(0, ItemTransferSupport.fuzzyIndexOf(listOf("中"), "a", 3))
    }

    // ───────────────────────── 物品名匹配 ─────────────────────────

    @Test
    fun `indexOf 与 reversed`() {
        assertEquals(1, ItemTransferSupport.indexOf(listOf("a", "b"), "b"))
        assertEquals(-1, ItemTransferSupport.indexOf(listOf("a", "b"), "c"))

        val original = listOf("a", "b", "c")
        assertEquals(listOf("c", "b", "a"), ItemTransferSupport.reversed(original))
        assertEquals(listOf("a", "b", "c"), original)
        assertEquals(emptyList<String>(), ItemTransferSupport.reversed(emptyList()))
    }

    @Test
    fun `cleanOCRNoise 只去空格与指定标点`() {
        assertEquals("abcdef", ItemTransferSupport.cleanOCRNoise("a b·c.d,e、f"))
        assertEquals("已盛装x", ItemTransferSupport.cleanOCRNoise("已盛装 x"))
        // 全角逗号与句号不在上游列表里，必须保留
        assertEquals("a，b。c", ItemTransferSupport.cleanOCRNoise("a，b。c"))
    }

    @Test
    fun `matchesTarget 精确与去噪匹配`() {
        assertTrue(ItemTransferSupport.matchesTarget("矿石", "矿石"))
        assertTrue(ItemTransferSupport.matchesTarget(" 矿石 ", "矿石"))
        assertTrue(ItemTransferSupport.matchesTarget("矿 石", "矿石"))
        assertTrue(ItemTransferSupport.matchesTarget("矿·石", "矿石"))
        assertFalse(ItemTransferSupport.matchesTarget("矿 石x", "矿石"))
        assertFalse(ItemTransferSupport.matchesTarget("已盛装", "矿石"))
        assertFalse(ItemTransferSupport.matchesTarget("", "矿石"))
        assertFalse(ItemTransferSupport.matchesTarget(null, "矿石"))
        assertFalse(ItemTransferSupport.matchesTarget("   ", "矿石"))
    }

    @Test
    fun `matchesAnyTarget 任一命中`() {
        assertTrue(ItemTransferSupport.matchesAnyTarget(listOf("木材", "矿石"), "矿石"))
        assertFalse(ItemTransferSupport.matchesAnyTarget(listOf("木材", "铁矿"), "矿石"))
        assertFalse(ItemTransferSupport.matchesAnyTarget(emptyList(), "矿石"))
    }

    // ───────────────────────── resolveOCRIndex ─────────────────────────

    @Test
    fun `resolveOCRIndex 精确优先`() {
        assertEquals(
            IndexedName("铁矿", 2),
            ItemTransferSupport.resolveOCRIndex(listOf("铁矿"), order, 1),
        )
        assertEquals(
            IndexedName("木材", 1),
            ItemTransferSupport.resolveOCRIndex(listOf("zzz", "木材"), order, 1),
        )
    }

    @Test
    fun `resolveOCRIndex 退回模糊匹配`() {
        assertEquals(
            IndexedName("铁 矿", 2),
            ItemTransferSupport.resolveOCRIndex(listOf("铁 矿"), order, 1),
        )
    }

    @Test
    fun `resolveOCRIndex 全不命中返回首个与负一`() {
        assertEquals(
            IndexedName("完全不存在", -1),
            ItemTransferSupport.resolveOCRIndex(listOf("完全不存在", "也不在"), order, 1),
        )
        assertEquals(
            IndexedName("", -1),
            ItemTransferSupport.resolveOCRIndex(emptyList(), order, 1),
        )
    }

    // ───────────────────────── inferSide ─────────────────────────

    @Test
    fun `inferSide 显式优先否则按任务名`() {
        assertEquals("bag", ItemTransferSupport.inferSide("bag", "ItemTransferFindForwardItemInRepo"))
        assertEquals("repo", ItemTransferSupport.inferSide("repo", "ItemTransferFindForwardItemInBag"))
        assertEquals("bag", ItemTransferSupport.inferSide(null, "ItemTransferFindForwardItemInBag"))
        assertEquals("bag", ItemTransferSupport.inferSide("", "XxxBagYyy"))
        assertEquals("repo", ItemTransferSupport.inferSide("", "ItemTransferFindForwardItemInRepo"))
        assertEquals("repo", ItemTransferSupport.inferSide(null, ""))
    }

    // ───────────────────────── 类型解析 ─────────────────────────

    private val itemOrderJson = """
        {
          "items": {
            "0": {"name": "中容谷地电池", "category": "Product"},
            "14": {"name": "原木", "category": "Plant"}
          },
          "category_order": {
            "Product": ["中容谷地电池", "密制晶体"],
            "Plant": ["原木"]
          }
        }
    """.trimIndent()

    @Test
    fun `parseItemOrderData 解析 items 与 category_order`() {
        val data = ItemTransferSupport.parseItemOrderData(MaaJsonTree.parse(itemOrderJson))
        assertNotNull(data)
        data!!
        assertEquals(ItemInfo("中容谷地电池", "Product"), data.items["0"])
        assertEquals(ItemInfo("原木", "Plant"), data.items["14"])
        assertEquals(listOf("中容谷地电池", "密制晶体"), data.categoryOrder["Product"])
        assertEquals(listOf("原木"), data.categoryOrder["Plant"])
    }

    @Test
    fun `parseItemOrderData 缺字段返回 null`() {
        assertNull(ItemTransferSupport.parseItemOrderData(null))
        assertNull(ItemTransferSupport.parseItemOrderData("不是对象"))
        assertNull(ItemTransferSupport.parseItemOrderData(mapOf("items" to emptyMap<String, Any>())))
        assertNull(ItemTransferSupport.parseItemOrderData(mapOf("category_order" to emptyMap<String, Any>())))
    }

    @Test
    fun `parseItemOrderData 缺 name 字段按空串`() {
        val tree = MaaJsonTree.parse("""{"items":{"1":{"category":"X"}},"category_order":{}}""")
        val data = ItemTransferSupport.parseItemOrderData(tree)
        assertEquals(ItemInfo("", "X"), data?.items?.get("1"))
    }

    @Test
    fun `findCategoryByName`() {
        val data = ItemTransferSupport.parseItemOrderData(MaaJsonTree.parse(itemOrderJson))!!
        assertEquals("Product", ItemTransferSupport.findCategoryByName(data, "密制晶体"))
        assertEquals("Plant", ItemTransferSupport.findCategoryByName(data, "原木"))
        assertNull(ItemTransferSupport.findCategoryByName(data, "不存在"))
    }

    @Test
    fun `parseFallbackParams 缺省与显式`() {
        assertNull(ItemTransferSupport.parseFallbackParams(null))
        assertNull(ItemTransferSupport.parseFallbackParams(""))
        assertNull(ItemTransferSupport.parseFallbackParams("   "))
        assertEquals(
            FallbackParams(0, false, "", 0),
            ItemTransferSupport.parseFallbackParams("{}"),
        )
        assertEquals(
            FallbackParams(3, true, "bag", 2),
            ItemTransferSupport.parseFallbackParams(
                """{"target_class":3,"descending":true,"side":"bag","max_distance":2}""",
            ),
        )
    }

    @Test
    fun `parseFallbackParams 类型不对或非法 JSON 失败`() {
        assertNull(ItemTransferSupport.parseFallbackParams("不是 json"))
        assertNull(ItemTransferSupport.parseFallbackParams("[1,2,3]"))
        assertNull(ItemTransferSupport.parseFallbackParams("""{"target_class":"3"}"""))
        assertNull(ItemTransferSupport.parseFallbackParams("""{"descending":"true"}"""))
        assertNull(ItemTransferSupport.parseFallbackParams("""{"side":5}"""))
        assertNull(ItemTransferSupport.parseFallbackParams("""{"max_distance":"2"}"""))
    }

    @Test
    fun `parseOcrActionParams 缺省与显式`() {
        assertNull(ItemTransferSupport.parseOcrActionParams(null))
        assertEquals(
            OcrActionParams("", false, "", 0),
            ItemTransferSupport.parseOcrActionParams("{}"),
        )
        assertEquals(
            OcrActionParams("矿石", true, "repo", 3),
            ItemTransferSupport.parseOcrActionParams(
                """{"item_name":"矿石","descending":true,"side":"repo","max_distance":3}""",
            ),
        )
        assertNull(ItemTransferSupport.parseOcrActionParams("""{"item_name":1}"""))
    }

    @Test
    fun `parseSameItemParams 缺省与显式`() {
        assertNull(ItemTransferSupport.parseSameItemParams(null))
        assertEquals(
            SameItemParams("", ""),
            ItemTransferSupport.parseSameItemParams("{}"),
        )
        assertEquals(
            SameItemParams("ItemTransferFindForwardItemInRepo", "ItemTransferFindReturnItemInRepo"),
            ItemTransferSupport.parseSameItemParams(
                """{"forward_item_node":"ItemTransferFindForwardItemInRepo",""" +
                    """"return_item_node":"ItemTransferFindReturnItemInRepo"}""",
            ),
        )
        assertNull(ItemTransferSupport.parseSameItemParams("""{"forward_item_node":1}"""))
    }

    // ───────────────────────── 节点 item_ids 解析 ─────────────────────────

    private fun nodeJson(itemIds: String): String =
        """
        {"recognition":{"type":"Custom","param":{
          "custom_recognition":"IconRecognition",
          "custom_recognition_param":{"grid_type":"transfer","item_ids":[$itemIds]}
        }}}
        """.trimIndent()

    @Test
    fun `parseSelectedItemId 恰好一个有效 ID`() {
        assertEquals(
            "item_copper_ore",
            ItemTransferSupport.parseSelectedItemId(nodeJson(""""item_copper_ore"""")),
        )
    }

    @Test
    fun `parseSelectedItemId 保留原始未 trim 值`() {
        assertEquals(" item ", ItemTransferSupport.parseSelectedItemId(nodeJson("""" item """")))
    }

    @Test
    fun `parseSelectedItemId 数量不为 1 或空值判无效`() {
        assertNull(ItemTransferSupport.parseSelectedItemId(nodeJson(""""a","b"""")))
        assertNull(ItemTransferSupport.parseSelectedItemId(nodeJson(""""   """"")))
        assertNull(ItemTransferSupport.parseSelectedItemId(nodeJson("")))
    }

    @Test
    fun `parseSelectedItemId 结构缺失或非 JSON 判无效`() {
        assertNull(ItemTransferSupport.parseSelectedItemId(null))
        assertNull(ItemTransferSupport.parseSelectedItemId(""))
        assertNull(ItemTransferSupport.parseSelectedItemId("不是 json"))
        assertNull(
            ItemTransferSupport.parseSelectedItemId(
                """{"recognition":{"param":{"custom_recognition_param":{}}}}""",
            ),
        )
    }

    // ───────────────────────── 网格几何 ─────────────────────────

    @Test
    fun `buildSyntheticGrid 仓库与背包布局`() {
        val repo = ItemTransferSupport.buildSyntheticGrid("repo", ItemTransferSupport.REPO_COLS)
        assertEquals(ItemTransferSupport.REPO_COLS * ItemTransferSupport.REPO_MAX_ROWS, repo.size)
        assertEquals(191, repo[0].centerX)
        assertEquals(246, repo[0].centerY)
        assertEquals(260, repo[1].centerX)
        assertEquals(191, repo[ItemTransferSupport.REPO_COLS].centerX)
        assertEquals(315, repo[ItemTransferSupport.REPO_COLS].centerY)
        assertEquals(ItemTransferSupport.NO_CLASS_ID, repo[0].classId)

        val bag = ItemTransferSupport.buildSyntheticGrid("bag", ItemTransferSupport.BAG_COLS)
        assertEquals(ItemTransferSupport.BAG_COLS * ItemTransferSupport.BAG_MAX_ROWS, bag.size)
        assertEquals(871, bag[0].centerX)
        assertEquals(247, bag[0].centerY)
    }

    @Test
    fun `buildFullGrid 以检出项为锚补满整行`() {
        val items = listOf(GridItem(classId = 5, centerX = 260, centerY = 246))
        val grid = ItemTransferSupport.buildFullGrid(items, 8, "repo")
        assertEquals(8, grid.size)
        // 260 向左退一格到 191（>= roiX 158），再退就 <158
        assertEquals(191, grid[0].centerX)
        assertEquals(246, grid[0].centerY)
        assertEquals(674, grid[7].centerX)
    }

    @Test
    fun `buildFullGrid 保证最右检出被覆盖`() {
        val items = listOf(
            GridItem(classId = 5, centerX = 191, centerY = 246),
            GridItem(classId = 6, centerX = 800, centerY = 246),
        )
        val grid = ItemTransferSupport.buildFullGrid(items, 8, "repo")
        // endX=674 < 800，起点被右移为 800-7*69=317
        assertEquals(317, grid[0].centerX)
        assertEquals(317 + 7 * 69, grid[7].centerX)
    }

    @Test
    fun `buildFullGrid 按行聚类`() {
        val sameRow = listOf(
            GridItem(classId = 5, centerX = 191, centerY = 246),
            GridItem(classId = 6, centerX = 260, centerY = 250),
        )
        val twoRows = listOf(
            GridItem(classId = 5, centerX = 191, centerY = 246),
            GridItem(classId = 6, centerX = 191, centerY = 400),
        )
        assertEquals(8, ItemTransferSupport.buildFullGrid(sameRow, 8, "repo").size)
        assertEquals(16, ItemTransferSupport.buildFullGrid(twoRows, 8, "repo").size)
        assertEquals(emptyList<GridItem>(), ItemTransferSupport.buildFullGrid(emptyList(), 8, "repo"))
    }

    @Test
    fun `findByLowScoreTarget 取同类最高分`() {
        val items = listOf(
            GridItem(classId = 5, score = 0.3),
            GridItem(classId = 5, score = 0.9),
            GridItem(classId = 7, score = 0.99),
        )
        assertEquals(0.9, ItemTransferSupport.findByLowScoreTarget(items, 5)?.score ?: -1.0, 0.0001)
        assertNull(ItemTransferSupport.findByLowScoreTarget(items, 9))
    }

    @Test
    fun `snapToGrid 吸附最近中心`() {
        val grid = listOf(
            GridItem(centerX = 100, centerY = 100),
            GridItem(centerX = 200, centerY = 100),
            GridItem(centerX = 100, centerY = 200),
        )
        assertTrue(
            ItemTransferSupport.snapToGrid(190, 110, grid).contentEquals(intArrayOf(200, 100)),
        )
        assertTrue(
            ItemTransferSupport.snapToGrid(0, 0, grid).contentEquals(intArrayOf(100, 100)),
        )
    }

    @Test
    fun `computeTooltipROI 右侧翻转与边界钳制`() {
        assertEquals(
            listOf(131, 106, 117, 58),
            ItemTransferSupport.computeTooltipROI(100, 100),
        )
        assertEquals(
            listOf(1052, 106, 117, 58),
            ItemTransferSupport.computeTooltipROI(1200, 100),
        )
        assertEquals(
            listOf(131, 662, 117, 58),
            ItemTransferSupport.computeTooltipROI(100, 700),
        )
        assertEquals(
            listOf(0, 0, 117, 58),
            ItemTransferSupport.computeTooltipROI(-100, -100),
        )
    }

    // ───────────────────────── OCR 文本提取 ─────────────────────────

    private fun ocr(text: String) = mapOf("text" to text, "box" to listOf(0, 0, 1, 1))

    @Test
    fun `extractAllOCRTexts 优先 filtered 否则 all`() {
        val preferred = mapOf(
            "filtered" to listOf(ocr(" 矿石 ")),
            "all" to listOf(ocr("木材")),
        )
        assertEquals(listOf("矿石"), ItemTransferSupport.extractAllOCRTexts(preferred))

        val fallback = mapOf("all" to listOf(ocr("木材")))
        assertEquals(listOf("木材"), ItemTransferSupport.extractAllOCRTexts(fallback))
    }

    @Test
    fun `extractAllOCRTexts 去重与忽略空文本`() {
        val detail = mapOf(
            "filtered" to listOf(ocr("木材"), ocr(" 木材 "), ocr("")),
            "all" to listOf(ocr("铁矿")),
        )
        assertEquals(listOf("木材"), ItemTransferSupport.extractAllOCRTexts(detail))
    }

    @Test
    fun `extractAllOCRTexts 兼容 results 外层与无文本`() {
        val nested = mapOf("results" to mapOf("filtered" to listOf(ocr("矿石"))))
        assertEquals(listOf("矿石"), ItemTransferSupport.extractAllOCRTexts(nested))
        assertEquals(emptyList<String>(), ItemTransferSupport.extractAllOCRTexts(null))
        assertEquals(emptyList<String>(), ItemTransferSupport.extractAllOCRTexts("不是对象"))
        assertEquals(
            emptyList<String>(),
            ItemTransferSupport.extractAllOCRTexts(mapOf("filtered" to emptyList<Any>())),
        )
    }

    @Test
    fun `filterTooltipTexts 丢掉已盛装`() {
        assertEquals(
            listOf("矿石", "木材"),
            ItemTransferSupport.filterTooltipTexts(listOf("矿石", "已盛装 x", "木材")),
        )
        assertEquals(emptyList<String>(), ItemTransferSupport.filterTooltipTexts(emptyList()))
    }
}
