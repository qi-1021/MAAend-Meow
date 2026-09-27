package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动囤货决策的纯逻辑测试。
 *
 * 真机回归之前，这里锁住三件最容易悄悄错掉的事：价格绑到哪个名字、挑货的排序、
 * 以及同一 region 的价格校正重试不会把 tried 清空（清空就会在同一件货上死循环）。
 *
 * 坐标按上游 bindPriceToOCRGoods 的几何规则给：价格在名字的右上方，
 * 欧氏距离不超过 120px（[farPrice] 那种摆法就该绑不上）。
 */
class AutoStockpileSupportTest {

    private fun ocr(text: String, x: Int, y: Int, w: Int = 200, h: Int = 40) =
        GoodsSupport.OcrItem(text, intArrayOf(x, y, w, h))

    /** 名字在 (100, y)、价格在它右上 80/25 的典型摆法 */
    private fun row(name: String, price: String?, y: Int) = buildList {
        add(ocr(name, 100, y))
        if (price != null) add(ocr(price, 180, y - 25))
    }

    @Before
    fun resetSession() {
        AutoStockpileSupport.Session.reset()
    }

    @Test
    fun `价格按右上就近绑到商品名`() {
        val items = row("岳研避瘴茶货组", "1200", 200) + row("冬虫夏草货组", "800", 320)

        val candidates = AutoStockpileSupport.scan(items, "Wuling")

        assertEquals(2, candidates.size)
        assertEquals("岳研避瘴茶货组", candidates[0].name)
        assertEquals(1200, candidates[0].price)
        assertEquals("冬虫夏草货组", candidates[1].name)
        assertEquals(800, candidates[1].price)
    }

    @Test
    fun `一条价格不会被两个名字重复占用`() {
        val items = listOf(
            ocr("岳研避瘴茶货组", 100, 200),
            ocr("冬虫夏草货组", 100, 240),
            ocr("1200", 180, 175),
        )

        val candidates = AutoStockpileSupport.scan(items, "Wuling")

        assertEquals(2, candidates.size)
        assertEquals(1, candidates.count { it.price != null })
    }

    @Test
    fun `价格离名字太远不绑定`() {
        val items = listOf(ocr("岳研避瘴茶货组", 100, 200), ocr("1200", 900, 700))

        assertNull(AutoStockpileSupport.scan(items, "Wuling").single().price)
    }

    @Test
    fun `认不出或不同区的货不进候选`() {
        val items = listOf(ocr("边角料积木货组", 100, 200), ocr("随便什么商品", 100, 320))

        assertTrue(AutoStockpileSupport.scan(items, "Wuling").isEmpty())
        assertEquals(1, AutoStockpileSupport.scan(items, "ValleyIV").size)
    }

    @Test
    fun `挑价低的，同价取 tier 高的`() {
        val items = row("源石树幼苗货组", "1200", 200) + row("星体晶块货组", "900", 320)

        val pick = AutoStockpileSupport.pick(AutoStockpileSupport.scan(items, "ValleyIV"), emptySet())
        assertEquals("星体晶块货组", pick!!.name)

        val samePrice = row("源石树幼苗货组", "900", 200) + row("星体晶块货组", "900", 320)
        val tierPick = AutoStockpileSupport.pick(AutoStockpileSupport.scan(samePrice, "ValleyIV"), emptySet())
        // 同为 900 时 Tier3 的源石树幼苗优先于 Tier2 的星体晶块
        assertEquals("源石树幼苗货组", tierPick!!.name)
    }

    @Test
    fun `没读到价格的候选排在有价格的后面`() {
        val items = row("岳研避瘴茶货组", null, 200) + row("武侠电影货组", "5000", 320)

        val pick = AutoStockpileSupport.pick(AutoStockpileSupport.scan(items, "Wuling"), emptySet())
        assertEquals("武侠电影货组", pick!!.name)
    }

    @Test
    fun `reject 记入 tried，同 region 重试换下一个候选而不是重选同一件`() {
        val items = row("岳研避瘴茶货组", "1200", 200) + row("冬虫夏草货组", "800", 320)
        val candidates = AutoStockpileSupport.scan(items, "Wuling")

        val first = AutoStockpileSupport.Session.decide("Wuling", "AutoStockpileDecisionWuling", candidates)
        assertEquals("冬虫夏草货组", first!!.name)
        AutoStockpileSupport.Session.select(first)
        AutoStockpileSupport.Session.reject()
        assertNull(AutoStockpileSupport.Session.selection())

        val second = AutoStockpileSupport.Session.decide("Wuling", "AutoStockpileDecisionWuling", candidates)
        assertEquals("岳研避瘴茶货组", second!!.name)
    }

    @Test
    fun `候选试完返回 null，换 region 才重开 tried`() {
        val items = row("岳研避瘴茶货组", null, 200) + row("冬虫夏草货组", null, 320)
        val candidates = AutoStockpileSupport.scan(items, "Wuling")

        listOf(1, 2).forEach {
            val pick = AutoStockpileSupport.Session.decide("Wuling", "AutoStockpileDecisionWuling", candidates)
            assertTrue("第 $it 轮应有候选", pick != null)
            AutoStockpileSupport.Session.select(pick!!)
            AutoStockpileSupport.Session.reject()
        }

        assertNull(AutoStockpileSupport.Session.decide("Wuling", "AutoStockpileDecisionWuling", candidates))

        AutoStockpileSupport.Session.decide("ValleyIV", "AutoStockpileDecisionValleyIV", emptyList())
        assertNotNull(AutoStockpileSupport.Session.decide("Wuling", "AutoStockpileDecisionWuling", candidates))
    }
}
