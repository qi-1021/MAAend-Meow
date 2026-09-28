package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.IntelArchiveSupport.CatalogOutcome
import com.aliothmoon.maafw.remote.IntelArchiveSupport.TruncatedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * 情报档案库纯逻辑测试（对齐上游 `agent/go-service/intelarchive/` 904 行）。
 *
 * 重点钉住三类「错了只会静默判错分支」的规则：
 *  1. `normalizeTitle` 的 OCR 折平与 `uniquePrefixIds` 的唯一前缀：歧义宁可判失败；
 *  2. `stripTrailingEllipsis` 只剥结尾、多页判定必须点开列表项；
 *  3. 组合识别 detail 的解析（顶层数组 → 第 N 段 `detail.filtered`）与导入链接的
 *     gzip+base64url 往返。
 */
class IntelArchiveSupportTest {

    private val catalog = """
        {"version":1,"categories":[
            {"id":"text","name":"见闻辑录"},
            {"id":"multi_media","name":"音像存档"},
            {"id":"document","name":"中枢档案"}
        ]}
    """.trimIndent()

    private val items = """
        {"version":1,"items":{
            "a":{"id":"a","names":{"zh_cn":"阿尔法","zh_tw":"阿爾法"},"page":"text",
                 "fileCategory":"paper","tagIds":["text"]},
            "b":{"id":"b","names":{"zh_cn":"贝塔"},"page":"document","fileCategory":"document",
                 "tagIds":["document"],"pages":[
                    {"id":"b_p1","names":{"zh_cn":"贝塔上"}},
                    {"id":"b_p2","names":{"zh_cn":"贝塔下"}}]},
            "c":{"id":"c","names":{"zh_cn":"伽马"},"page":"multi_media","fileCategory":"media",
                 "tagIds":["multi_media"],"pages":[{"id":"c_p1","names":{"zh_cn":"伽马上"}}]}
        }}
    """.trimIndent()

    private fun indexOf(catalogJson: String, itemsJson: String): IntelArchiveSupport.CatalogIndex {
        val outcome = IntelArchiveSupport.buildCatalogIndex(
            MaaJsonTree.parse(catalogJson),
            MaaJsonTree.parse(itemsJson),
        )
        assertTrue("期望目录构建成功，实际 $outcome", outcome is CatalogOutcome.Ok)
        return (outcome as CatalogOutcome.Ok).index
    }

    private fun index(): IntelArchiveSupport.CatalogIndex = indexOf(catalog, items)

    // ───────────────────────── normalizeTitle ─────────────────────────

    @Test
    fun `normalizeTitle 折平全角括号与破折号形近字符`() {
        assertEquals("(甲)", IntelArchiveSupport.normalizeTitle("（甲）"))
        assertEquals("甲-乙", IntelArchiveSupport.normalizeTitle("甲—乙"))
        assertEquals("甲-乙", IntelArchiveSupport.normalizeTitle("甲——乙"))
        assertEquals("甲-乙", IntelArchiveSupport.normalizeTitle("甲–乙"))
        assertEquals("甲-乙", IntelArchiveSupport.normalizeTitle("甲－乙"))
        assertEquals("甲乙", IntelArchiveSupport.normalizeTitle("甲　乙"))
        assertEquals("梦魇", IntelArchiveSupport.normalizeTitle("梦魔"))
        assertEquals("-", IntelArchiveSupport.normalizeTitle("一一"))
        assertEquals("甲", IntelArchiveSupport.normalizeTitle("  甲  "))
        assertEquals("", IntelArchiveSupport.normalizeTitle("   "))
        assertEquals("", IntelArchiveSupport.normalizeTitle(null))
    }

    // ───────────────────────── itemIDLess / 排序 ─────────────────────────

    @Test
    fun `itemIdLess 数字按数值字符串按字典序`() {
        assertTrue(IntelArchiveSupport.itemIdLess("2", "10"))
        assertFalse(IntelArchiveSupport.itemIdLess("10", "2"))
        assertTrue(IntelArchiveSupport.itemIdLess("a", "b"))
        assertTrue(IntelArchiveSupport.itemIdLess("2", "a"))
        assertFalse(IntelArchiveSupport.itemIdLess("a", "2"))
    }

    @Test
    fun `allUnlockIds 按 itemIdLess 排序且带 pages 的只收 page`() {
        val idx = index()
        assertEquals(listOf("a", "b_p1", "b_p2", "c_p1"), idx.allUnlockIds)
    }

    @Test
    fun `数字条目按数值排序`() {
        val idx = indexOf(
            catalog,
            """
            {"version":1,"items":{
                "10":{"id":"10","names":{"zh_cn":"十"},"page":"text","fileCategory":"paper","tagIds":["text"]},
                "2":{"id":"2","names":{"zh_cn":"二"},"page":"text","fileCategory":"paper","tagIds":["text"]},
                "x":{"id":"x","names":{"zh_cn":"艾克斯"},"page":"text","fileCategory":"paper","tagIds":["text"]}
            }}
            """.trimIndent(),
        )
        assertEquals(listOf("2", "10", "x"), idx.allUnlockIds)
    }

    // ───────────────────────── 目录构建校验 ─────────────────────────

    private fun invalid(catalogJson: String?, itemsJson: String?): String {
        val outcome = IntelArchiveSupport.buildCatalogIndex(
            MaaJsonTree.parse(catalogJson),
            MaaJsonTree.parse(itemsJson),
        )
        assertTrue("期望构建失败，实际 $outcome", outcome is CatalogOutcome.Invalid)
        return (outcome as CatalogOutcome.Invalid).reason
    }

    @Test
    fun `目录或条目为空判失败`() {
        invalid(null, null)
        invalid(catalog, null)
        invalid("""{"version":1}""", items)
        invalid(catalog, """{"version":1}""")
    }

    @Test
    fun `分类 id 重复或名为空判失败`() {
        invalid("""{"categories":[{"id":"x","name":"甲"},{"id":"x","name":"乙"}]}""", items)
        invalid("""{"categories":[{"id":"x","name":""}]}""", items)
        invalid("""{"categories":[{"id":"","name":"甲"}]}""", items)
    }

    @Test
    fun `条目名与文件类别与页签校验`() {
        val oneItem = { body: String -> """{"version":1,"items":{"a":{$body}}}""" }
        invalid(catalog, oneItem(""""id":"a","names":{"zh_tw":"阿爾法"},"page":"text","fileCategory":"paper","tagIds":["text"]"""))
        invalid(catalog, oneItem(""""id":"a","names":{"zh_cn":"阿尔法"},"page":"text","fileCategory":"bogus","tagIds":["text"]"""))
        invalid(catalog, oneItem(""""id":"a","names":{"zh_cn":"阿尔法"},"page":"nope","fileCategory":"paper","tagIds":["text"]"""))
        invalid(catalog, oneItem(""""id":"a","names":{"zh_cn":"阿尔法"},"page":"text","fileCategory":"paper","tagIds":["other"]"""))
        invalid(catalog, oneItem(""""id":"a","names":{"zh_cn":"阿尔法"},"page":"text","fileCategory":"paper","tagIds":[]"""))
        invalid(catalog, oneItem(""""id":"a","names":{"zh_cn":"阿尔法"},"page":"text","fileCategory":"paper","tagIds":["text"],"pages":[{"id":"p","names":{}}]"""))
        // 键与 id 不一致
        invalid(catalog, """{"version":1,"items":{"a":{"id":"b","names":{"zh_cn":"阿尔法"},"page":"text","fileCategory":"paper","tagIds":["text"]}}}""")
    }

    // ───────────────────────── matchOcr / 前缀匹配 ─────────────────────────

    @Test
    fun `matchOcr 精确与唯一前缀`() {
        val idx = index()
        val (exact, name) = idx.matchOcr("阿尔法", "")
        assertEquals(listOf("a"), exact)
        assertEquals("阿尔法", name)

        val (prefix, pname) = idx.matchOcr("阿尔", "")
        assertEquals(listOf("a"), prefix)
        assertEquals("阿尔法", pname)

        // 「阿」同时前缀命中 zh_cn 与 zh_tw 两个不同名，按唯一前缀规则判歧义
        val (ambiguous, _) = idx.matchOcr("阿", "")
        assertEquals(emptyList<String>(), ambiguous)

        val (tw, _) = idx.matchOcr("阿爾法", "")
        assertEquals(listOf("a"), tw)
    }

    @Test
    fun `matchOcr 前缀歧义判失败`() {
        val idx = indexOf(
            catalog,
            """
            {"version":1,"items":{
                "a":{"id":"a","names":{"zh_cn":"阿尔法"},"page":"text","fileCategory":"paper","tagIds":["text"]},
                "d":{"id":"d","names":{"zh_cn":"阿尔法2"},"page":"text","fileCategory":"paper","tagIds":["text"]}
            }}
            """.trimIndent(),
        )
        val (ids, name) = idx.matchOcr("阿", "")
        assertEquals(emptyList<String>(), ids)
        assertEquals("", name)
    }

    @Test
    fun `matchOcr 按文件类别过滤`() {
        val idx = index()
        val (paper, _) = idx.matchOcr("阿尔法", "paper")
        assertEquals(listOf("a"), paper)
        val (investigate, _) = idx.matchOcr("阿尔法", "investigate")
        assertEquals(emptyList<String>(), investigate)
    }

    @Test
    fun `matchOcr 归一化后匹配`() {
        val idx = index()
        val (ids, _) = idx.matchOcr("（阿尔法）", "")
        // 括号不是标题的一部分，前缀匹配不上完整的「(阿尔法)」
        assertEquals(emptyList<String>(), ids)
        val (dash, _) = idx.matchOcr("阿尔法—", "")
        assertEquals(emptyList<String>(), dash)
    }

    // ───────────────────────── shouldOpenFromList ─────────────────────────

    @Test
    fun `多页条目标题需要点开列表项`() {
        val idx = index()
        assertTrue(idx.shouldOpenFromList("贝塔", ""))
        assertTrue(idx.shouldOpenFromList("贝", ""))
    }

    @Test
    fun `单页与无页条目不需要点开`() {
        val idx = index()
        assertFalse(idx.shouldOpenFromList("伽马", ""))
        assertFalse(idx.shouldOpenFromList("阿尔法", ""))
        assertFalse(idx.shouldOpenFromList("", ""))
    }

    // ───────────────────────── stripTrailingEllipsis ─────────────────────────

    @Test
    fun `stripTrailingEllipsis 剥结尾省略号`() {
        assertEquals("标题" to true, IntelArchiveSupport.stripTrailingEllipsis("标题…"))
        assertEquals("标题" to true, IntelArchiveSupport.stripTrailingEllipsis("标题..."))
        assertEquals("标题" to true, IntelArchiveSupport.stripTrailingEllipsis("标题．．．"))
        assertEquals("标题" to true, IntelArchiveSupport.stripTrailingEllipsis("标题。."))
        assertEquals("标题" to true, IntelArchiveSupport.stripTrailingEllipsis(" 标题… "))
    }

    @Test
    fun `stripTrailingEllipsis 中间的点不动且无省略号返回 false`() {
        assertEquals("标.题" to false, IntelArchiveSupport.stripTrailingEllipsis("标.题"))
        assertEquals("标题.中" to false, IntelArchiveSupport.stripTrailingEllipsis("标题.中"))
        assertEquals("" to false, IntelArchiveSupport.stripTrailingEllipsis("   "))
        assertEquals("" to false, IntelArchiveSupport.stripTrailingEllipsis(null))
    }

    // ───────────────────────── filteredItems（组合识别 detail）─────────────────────────

    @Test
    fun `filteredItems 从顶层数组第 N 段 detail 取 filtered`() {
        val combined = listOf(
            mapOf("name" to "icon"),
            mapOf("name" to "line"),
            mapOf("name" to "bg"),
            mapOf("name" to "color"),
            mapOf(
                "name" to "ocr",
                "detail" to mapOf(
                    "filtered" to listOf(
                        mapOf("text" to " 乙 ", "box" to listOf(1, 2, 3, 4)),
                        mapOf("text" to "", "box" to listOf(5, 6, 0, 4)),
                        mapOf("text" to "丙", "box" to emptyList<Int>()),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(TruncatedItem("乙", listOf(1, 2, 3, 4)), TruncatedItem("丙", emptyList())),
            IntelArchiveSupport.filteredItems(combined, 4),
        )
    }

    @Test
    fun `filteredItems detail 为字符串与越界形状`() {
        val asString = listOf(
            mapOf("detail" to """{"filtered":[{"text":"丁","box":[1,2,3,4]}]}"""),
        )
        assertEquals(
            listOf(TruncatedItem("丁", listOf(1, 2, 3, 4))),
            IntelArchiveSupport.filteredItems(asString, 0),
        )
        assertTrue(IntelArchiveSupport.filteredItems(asString, 5).isEmpty())
        assertTrue(IntelArchiveSupport.filteredItems(asString, -1).isEmpty())
        assertTrue(IntelArchiveSupport.filteredItems(null, 0).isEmpty())
        assertTrue(IntelArchiveSupport.filteredItems(mapOf("a" to 1), 0).isEmpty())
    }

    // ───────────────────────── parseTruncated ─────────────────────────

    @Test
    fun `parseTruncated 兼容 best_detial 对象与字符串`() {
        val asObject = mapOf(
            "best" to mapOf(
                "detail" to mapOf(
                    "truncated" to listOf(mapOf("text" to "甲", "box" to listOf(1, 2, 3, 4))),
                ),
            ),
        )
        assertEquals(
            listOf(TruncatedItem("甲", listOf(1, 2, 3, 4))),
            IntelArchiveSupport.parseTruncated(asObject),
        )

        val asString = mapOf(
            "best" to mapOf("detail" to """{"truncated":[{"text":"乙","box":[2,3,4,5]}]}"""),
        )
        assertEquals(
            listOf(TruncatedItem("乙", listOf(2, 3, 4, 5))),
            IntelArchiveSupport.parseTruncated(asString),
        )
    }

    @Test
    fun `parseTruncated 直接对象与空形状`() {
        val direct = mapOf(
            "truncated" to listOf(mapOf("text" to "丙", "box" to listOf(7, 8, 9, 10))),
        )
        assertEquals(
            listOf(TruncatedItem("丙", listOf(7, 8, 9, 10))),
            IntelArchiveSupport.parseTruncated(direct),
        )
        assertTrue(IntelArchiveSupport.parseTruncated(null).isEmpty())
        assertTrue(IntelArchiveSupport.parseTruncated("x").isEmpty())
        assertTrue(IntelArchiveSupport.parseTruncated(mapOf("best" to mapOf("detail" to ""))).isEmpty())
    }

    // ───────────────────────── classifyListItems ─────────────────────────

    @Test
    fun `无名密文在空类别或 digital 下强制解锁`() {
        val idx = index()
        val secret = listOf(TruncatedItem("", listOf(1, 2, 3, 4)))
        assertTrue(IntelArchiveSupport.classifyListItems(idx, emptyList(), secret, "").secretForceUnlock)
        assertTrue(IntelArchiveSupport.classifyListItems(idx, emptyList(), secret, "digital").secretForceUnlock)
        assertFalse(IntelArchiveSupport.classifyListItems(idx, emptyList(), secret, "document").secretForceUnlock)
    }

    @Test
    fun `非 digital 的无名密文并入待点开`() {
        val idx = index()
        val secret = listOf(TruncatedItem("", listOf(1, 2, 3, 4)))
        val result = IntelArchiveSupport.classifyListItems(idx, emptyList(), secret, "document")
        assertEquals(secret, result.truncated)
    }

    @Test
    fun `列表项按标题与省略号分流`() {
        val idx = index()
        val titles = listOf(
            TruncatedItem("阿尔法", listOf(1, 1, 1, 1)),
            TruncatedItem("贝塔", listOf(2, 2, 2, 2)),
            TruncatedItem("伽马…", listOf(3, 3, 3, 3)),
            TruncatedItem("未知…", listOf(4, 4, 4, 4)),
            TruncatedItem("未知", listOf(5, 5, 5, 5)),
        )
        val result = IntelArchiveSupport.classifyListItems(idx, titles, emptyList(), "")
        assertEquals(listOf("阿尔法", "伽马", "未知"), result.names)
        assertEquals(
            listOf(TruncatedItem("贝塔", listOf(2, 2, 2, 2)), TruncatedItem("未知…", listOf(4, 4, 4, 4))),
            result.truncated,
        )
    }

    @Test
    fun `省略号后能匹配的标题直接按名入库`() {
        val idx = index()
        val result = IntelArchiveSupport.classifyListItems(
            idx,
            listOf(TruncatedItem("阿尔法…", listOf(1, 1, 1, 1))),
            emptyList(),
            "",
        )
        assertEquals(listOf("阿尔法"), result.names)
        assertTrue(result.truncated.isEmpty())
    }

    // ───────────────────────── parseScanFileCategory ─────────────────────────

    @Test
    fun `parseScanFileCategory 解析与容错`() {
        assertEquals("", IntelArchiveSupport.parseScanFileCategory(null))
        assertEquals("", IntelArchiveSupport.parseScanFileCategory(""))
        assertEquals("", IntelArchiveSupport.parseScanFileCategory("  "))
        assertEquals("document", IntelArchiveSupport.parseScanFileCategory("""{"file_category":"document"}"""))
        assertEquals("media", IntelArchiveSupport.parseScanFileCategory("""{"file_category":"  media  "}"""))
        assertEquals("", IntelArchiveSupport.parseScanFileCategory("不是 json"))
        assertEquals("", IntelArchiveSupport.parseScanFileCategory("[1,2]"))
        assertEquals("", IntelArchiveSupport.parseScanFileCategory("""{"file_category":123}"""))
    }

    // ───────────────────────── 会话与导入链接 ─────────────────────────

    @Test
    fun `dedupeStrings 去空白去空保序`() {
        assertEquals(listOf("a", "b"), IntelArchiveSupport.dedupeStrings(listOf("a", " a ", "", "b", "a")))
        assertEquals(emptyList<String>(), IntelArchiveSupport.dedupeStrings(emptyList()))
        assertEquals(emptyList<String>(), IntelArchiveSupport.dedupeStrings(null))
        assertEquals(emptyList<String>(), IntelArchiveSupport.dedupeStrings(listOf(" ", "")))
    }

    @Test
    fun `会话解锁去重且 reset 清空`() {
        IntelArchiveSupport.resetSession()
        assertEquals(listOf("1", "2"), IntelArchiveSupport.unlockItems(listOf("1", "2", "1", "")))
        assertEquals(listOf("3"), IntelArchiveSupport.unlockItems(listOf("2", "3")))
        assertEquals(listOf("1", "2", "3"), IntelArchiveSupport.sessionUnlockedIds())
        IntelArchiveSupport.resetSession()
        assertEquals(emptyList<String>(), IntelArchiveSupport.sessionUnlockedIds())
    }

    @Test
    fun `pendingTruncated 取走即清空`() {
        IntelArchiveSupport.resetSession()
        IntelArchiveSupport.storePendingTruncated(listOf(TruncatedItem("甲", listOf(1, 2, 3, 4))))
        assertEquals(1, IntelArchiveSupport.takePendingTruncated().size)
        assertTrue(IntelArchiveSupport.takePendingTruncated().isEmpty())
    }

    @Test
    fun `buildIntelImportUrl 为 collected 与补集并可解压回读`() {
        val url = IntelArchiveSupport.buildIntelImportUrl(
            listOf("b_p1", "a", "b_p1"),
            listOf("a", "b_p1", "b_p2", "c_p1"),
        )
        assertTrue(url.startsWith("https://oem.re/i/MAE-0-"))
        val encoded = url.removePrefix("https://oem.re/i/MAE-0-")
        val gzip = Base64.getUrlDecoder().decode(encoded)
        val text = GZIPInputStream(ByteArrayInputStream(gzip)).bufferedReader().use { it.readText() }
        assertTrue(text.contains("\"collected\":[\"b_p1\",\"a\"]"))
        assertTrue(text.contains("\"notCollected\":[\"b_p2\",\"c_p1\"]"))
        assertTrue(text.contains("\"oeaVersion\":\"maaend\""))
        assertTrue(text.contains("\"majorVersion\":0"))
    }

    @Test
    fun `buildIntelImportUrl 空 collected 输出空数组`() {
        val url = IntelArchiveSupport.buildIntelImportUrl(emptyList(), listOf("a", "b"))
        val gzip = Base64.getUrlDecoder().decode(url.removePrefix("https://oem.re/i/MAE-0-"))
        val text = GZIPInputStream(ByteArrayInputStream(gzip)).bufferedReader().use { it.readText() }
        assertTrue(text.contains("\"collected\":[]"))
        assertTrue(text.contains("\"notCollected\":[\"a\",\"b\"]"))
    }

    // ───────────────────────── 目录缓存 ─────────────────────────

    @Test
    fun `ensureLoaded 首次解析并缓存失败原因`() {
        IntelArchiveSupport.clearCatalogCache()
        assertNotNull(IntelArchiveSupport.ensureLoaded(catalog, items))
        assertNotNull(IntelArchiveSupport.loadedCatalog())
        // 缓存后即使给坏数据也返回已加载的目录
        assertNotNull(IntelArchiveSupport.ensureLoaded(null, null))

        IntelArchiveSupport.clearCatalogCache()
        IntelArchiveSupport.ensureLoaded(null, null)
        assertEquals(null, IntelArchiveSupport.loadedCatalog())
        IntelArchiveSupport.clearCatalogCache()
    }
}
