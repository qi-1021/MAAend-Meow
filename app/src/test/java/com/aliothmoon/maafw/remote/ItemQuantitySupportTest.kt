package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ItemQuantitySatisfied`（IMS R1）与 IMS 库存容器的纯逻辑测试。
 *
 * 重点钉住本轮审计根因：
 *  - 库存为空（记账仍是 noop）时，缺失物品按 0=未达标 求值；
 *  - 采集「目标库存模式」的门控/跳过表达式在空库存下必须**放行采集、绝不跳过**；
 *  - 非法参数/表达式必须报错，不能静默命中。
 */
class ItemQuantitySupportTest {

    private fun qty(vararg pairs: Pair<String, Int>): (String) -> Int {
        val map = pairs.toMap()
        return { map[it] ?: 0 }
    }

    private fun eval(expression: String, vararg pairs: Pair<String, Int>): ItemQuantitySupport.Evaluation =
        ItemQuantitySupport.evaluate(ItemQuantitySupport.Param(expression), qty(*pairs))

    @Test
    fun `parseParams 读取 expression notify_ui report_only`() {
        val p = ItemQuantitySupport.parseParams(
            """{"expression":"{a}>=1","notify_ui":true,"report_only":false}""",
        )
        assertEquals("{a}>=1", p.expression)
        assertTrue(p.notifyUi)
        assertFalse(p.reportOnly)

        val defaults = ItemQuantitySupport.parseParams("""{"expression":"  {a}>=1  "}""")
        assertEquals("{a}>=1", defaults.expression)
        assertFalse(defaults.notifyUi)
        assertFalse(defaults.reportOnly)
    }

    @Test
    fun `parseParams 缺参数或非法 JSON 报错`() {
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.parseParams(null)
        }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.parseParams("   ")
        }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.parseParams("not json")
        }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.parseParams("{}")
        }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.parseParams("""{"expression":"  "}""")
        }
    }

    @Test
    fun `report_only 要求恰好一个占位符`() {
        assertEquals("item_x", ItemQuantitySupport.singleItemId("{item_x}"))
        assertEquals("item_x", ItemQuantitySupport.singleItemId("!({item_x}>=0)"))
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.singleItemId("1==1")
        }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.singleItemId("{a}+{b}")
        }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.singleItemId("{}")
        }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) {
            ItemQuantitySupport.parseParams("""{"expression":"{a}+{b}","report_only":true}""")
        }
    }

    @Test
    fun `库存充足时表达式命中`() {
        assertTrue(eval("{a}>=5", "a" to 5).matched)
        assertTrue(eval("{a}>=5", "a" to 9).matched)
        assertFalse(eval("{a}>=5", "a" to 4).matched)
    }

    @Test
    fun `复合表达式按真实资产写法求值`() {
        // ({h}*10000+{m}*1000)>={t}
        assertTrue(eval("({h}*10000+{m}*1000)>={t}", "h" to 1, "m" to 6, "t" to 15000).matched)
        assertFalse(eval("({h}*10000+{m}*1000)>={t}", "h" to 1, "m" to 4, "t" to 15000).matched)
    }

    @Test
    fun `report_only 恒命中并回报数量`() {
        val e = ItemQuantitySupport.evaluate(
            ItemQuantitySupport.Param("{item_x}", reportOnly = true),
            qty("item_x" to 7),
        )
        assertTrue(e.matched)
        assertEquals("item_x", e.reportItemId)
        assertEquals(7, e.reportQuantity)
    }

    @Test
    fun `缺失物品按 0 处理即未达标`() {
        assertFalse(eval("{unknown}>=1").matched)
        assertTrue(eval("{unknown}<5").matched)
        assertTrue(eval("{unknown}==0").matched)
    }

    @Test
    fun `空库存下采集门控只会放行不会跳过`() {
        val limit = 5
        val gated = "{limit}==0 || {item}<{limit}"
        val skipped = "!({limit}==0 || {item}<{limit})"
        val empty = arrayOf("limit" to limit, "item" to 0)
        assertTrue("空库存必须放行采集", eval(gated, *empty).matched)
        assertFalse("空库存不能判已达标而跳过", eval(skipped, *empty).matched)

        // 已达标：库存 9 >= 阈值 5 → 放行不命中、跳过命中
        assertFalse(eval(gated, "limit" to 5, "item" to 9).matched)
        assertTrue(eval(skipped, "limit" to 5, "item" to 9).matched)

        // 阈值为 0：门控恒放行，跳过恒不命中
        assertTrue(eval(gated, "limit" to 0, "item" to 0).matched)
        assertFalse(eval(skipped, "limit" to 0, "item" to 0).matched)
    }

    @Test
    fun `结果非 bool 或表达式非法时报错`() {
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) { eval("1+1") }
        assertThrows(ItemQuantitySupport.ItemQuantityException::class.java) { eval("{a}+") }
    }

    @Test
    fun `ImsItemCache 容器语义`() {
        val cache = ImsItemCache()
        assertEquals(0, cache.quantity("missing"))
        assertFalse(cache.hasData())
        assertTrue(cache.isEmpty())

        cache.markSynced(1000L, mapOf("a" to 3))
        assertTrue(cache.hasData())
        assertEquals(3, cache.quantity("a"))
        assertEquals(1000L, cache.snapshot().lastSyncEpochMillis)

        val delta = cache.applyDelta("a", -5)
        assertEquals(3, delta.before)
        assertEquals(0, delta.after)
        assertTrue(delta.clamped)
        assertEquals(0, cache.quantity("a"))
        // 增量不改变就绪状态
        assertTrue(delta.hasData)

        cache.markSynced(2000L, mapOf("b" to 1))
        cache.setItemsOnly(mapOf("c" to 9))
        assertTrue("setItemsOnly 不动就绪状态", cache.hasData())
        assertEquals(2000L, cache.snapshot().lastSyncEpochMillis)
        assertEquals(9, cache.quantity("c"))
        assertEquals(0, cache.quantity("b"))

        cache.clear()
        assertFalse(cache.hasData())
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `ImsItemCache 快照与输入 map 隔离`() {
        val cache = ImsItemCache()
        val input = mutableMapOf("a" to 1)
        cache.markSynced(1L, input)
        input["a"] = 99
        assertEquals(1, cache.quantity("a"))
        assertEquals(1, cache.snapshot().items["a"])
    }
}
