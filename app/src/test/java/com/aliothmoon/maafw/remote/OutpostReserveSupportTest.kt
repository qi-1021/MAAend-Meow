package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OutpostTradingReserveSession` 纯逻辑测试（对齐上游 outposttrading/goods/reserve.go）。
 *
 * 覆盖保留规则状态机、`apply` 的三种分支（活动额度 / 配置保留 / 未配置全卖）与
 * `item_id <-> 商品名` 映射；这些正是此前移动端 noop 静默丢掉的语义。
 */
class OutpostReserveSupportTest {

    private fun param(map: Map<String, Any?>): OutpostReserveSupport.ActionParam? =
        OutpostReserveSupport.parseParam(map)

    // ───────────────────────── 参数解析 ─────────────────────────

    @Test
    fun `parseParam 接受五种 operation 并 trim 字段`() {
        assertEquals(
            OutpostReserveSupport.ActionParam("reset"),
            param(mapOf("operation" to " reset ")),
        )
        assertEquals(
            OutpostReserveSupport.ActionParam("register", "item_a", -1),
            param(mapOf("operation" to "register", "item_id" to " item_a ", "quantity" to -1)),
        )
        assertEquals(
            OutpostReserveSupport.ActionParam("select", "item_a"),
            param(mapOf("operation" to "select", "item_id" to "item_a")),
        )
        assertEquals(
            OutpostReserveSupport.ActionParam("apply", "", 0, "Slider", "Loc"),
            param(mapOf("operation" to "apply", "sliding_node" to " Slider ", "location" to "Loc")),
        )
        assertEquals(OutpostReserveSupport.ActionParam("satisfy"), param(mapOf("operation" to "satisfy")))
    }

    @Test
    fun `parseParam 拒绝非法输入`() {
        assertNull(param(mapOf("operation" to "nope")))
        assertNull(param(mapOf<String, Any?>("operation" to "register", "quantity" to -2)))
        assertNull(param(mapOf("operation" to "select", "item_id" to "  ")))
        assertNull(param(mapOf("operation" to "apply", "sliding_node" to "S")))
        assertNull(param(mapOf("operation" to "apply", "location" to "L")))
        assertNull(OutpostReserveSupport.parseParam("not-a-map"))
        assertNull(OutpostReserveSupport.parseParam(null))
    }

    @Test
    fun `parseParam 把缺省 quantity 当 0`() {
        assertEquals(0, param(mapOf("operation" to "register", "item_id" to "x"))?.quantity)
    }

    @Test
    fun `parseAttachItemId 从节点 attach 读取`() {
        val node = """{"action":"Custom","attach":{"item_id":" item_proc_battery_3 "}}"""
        assertEquals("item_proc_battery_3", OutpostReserveSupport.parseAttachItemId(node))
        // 没有 attach：返回空串（上游语义），而不是 null
        assertEquals("", OutpostReserveSupport.parseAttachItemId("""{"action":"Custom"}"""))
        assertNull(OutpostReserveSupport.parseAttachItemId(null))
        assertNull(OutpostReserveSupport.parseAttachItemId("not json"))
    }

    // ───────────────────────── 状态机 ─────────────────────────

    @Test
    fun `registerRule 返回是否覆盖 后注册覆盖先注册`() {
        val s = OutpostReserveSupport.Session()
        assertFalse(s.registerRule("甲", 5))
        assertTrue(s.registerRule("甲", 8))
        assertEquals(8, s.rulesSnapshot()["甲"])
    }

    @Test
    fun `selectedRule 只有数量大于 0 才算 configured`() {
        val s = OutpostReserveSupport.Session()
        s.registerRule("甲", 5)
        s.registerRule("乙", 0)
        s.registerRule("丙", -1)

        s.setSelected("甲")
        assertEquals(OutpostReserveSupport.SelectedRule("甲", 5, true), s.selectedRule())
        s.setSelected("乙")
        assertEquals(OutpostReserveSupport.SelectedRule("乙", 0, false), s.selectedRule())
        s.setSelected("丙")
        assertEquals(OutpostReserveSupport.SelectedRule("丙", -1, false), s.selectedRule())
        s.setSelected("未注册")
        assertEquals(OutpostReserveSupport.SelectedRule("未注册", 0, false), s.selectedRule())
    }

    @Test
    fun `markSatisfied 仅对有效保留规则成功 且只标一次`() {
        val s = OutpostReserveSupport.Session()
        s.registerRule("甲", 3)
        s.setSelected("甲")
        val first = s.markSatisfied()
        assertTrue(first.ok)
        assertTrue(first.marked)
        assertEquals(setOf("甲"), s.satisfiedItems())
        val second = s.markSatisfied()
        assertTrue(second.ok)
        assertFalse(second.marked)

        s.setSelected("乙")
        val invalid = s.markSatisfied()
        assertFalse(invalid.ok)
    }

    @Test
    fun `blacklistedItems 只认 -1`() {
        val s = OutpostReserveSupport.Session()
        s.registerRule("甲", -1)
        s.registerRule("乙", 0)
        s.registerRule("丙", 2)
        assertEquals(setOf("甲"), s.blacklistedItems())
    }

    @Test
    fun `reset 清空全部状态`() {
        val s = OutpostReserveSupport.Session()
        s.registerRule("甲", 3)
        s.setSelected("甲")
        s.markSatisfied()
        s.reset()
        assertEquals(emptyMap<String, Int>(), s.rulesSnapshot())
        assertEquals(emptySet<String>(), s.satisfiedItems())
        assertEquals("", s.selectedName())
    }

    // ───────────────────────── 额度换算 ─────────────────────────

    @Test
    fun `activityQuantity 整除 并对非法输入返回 null`() {
        assertEquals(10, OutpostReserveSupport.activityQuantity(1000, 100))
        assertEquals(0, OutpostReserveSupport.activityQuantity(99, 100))
        assertNull(OutpostReserveSupport.activityQuantity(100, 0))
        assertNull(OutpostReserveSupport.activityQuantity(100, -1))
        assertNull(OutpostReserveSupport.activityQuantity(-1, 10))
    }

    // ───────────────────────── apply 规划 ─────────────────────────

    @Test
    fun `planApply 黑名单直接失败 不回退全卖`() {
        val plan = OutpostReserveSupport.planApply(
            nodeName = "ApplyReserve",
            slidingNode = "Sliding",
            quantity = -1,
            configured = false,
            unitPrice = 10,
            activity = false,
            stockBills = 0,
        )
        assertTrue(plan is OutpostReserveSupport.ApplyPlan.Failed)
    }

    @Test
    fun `planApply 单价非正失败`() {
        val plan = OutpostReserveSupport.planApply("N", "S", 5, true, 0, false, 0)
        assertTrue(plan is OutpostReserveSupport.ApplyPlan.Failed)
    }

    @Test
    fun `planApply 配置保留 N 走 SellThenLoop 且 ReverseTarget=true`() {
        val plan = OutpostReserveSupport.planApply(
            nodeName = "ApplyReserve",
            slidingNode = "Sliding",
            quantity = 20,
            configured = true,
            unitPrice = 10,
            activity = false,
            stockBills = 0,
        )
        assertTrue(plan is OutpostReserveSupport.ApplyPlan.Override)
        val override = plan as OutpostReserveSupport.ApplyPlan.Override
        assertEquals("Sliding", override.next)
        val tree = MaaJsonTree.parse(override.overrideJson) as Map<*, *>
        val sliding = tree["Sliding"] as Map<*, *>
        assertEquals(
            listOf("OutpostTradingReserveAlreadySatisfied", "OutpostTradingSellThenLoop"),
            sliding["next"],
        )
        val attach = sliding["attach"] as Map<*, *>
        assertEquals(20, (attach["TargetQuantity"] as Number).toInt())
        assertEquals(true, attach["ReverseTarget"])
        assertEquals(mapOf("next" to listOf("Sliding")), tree["ApplyReserve"])
    }

    @Test
    fun `planApply 未配置保留走全卖`() {
        val plan = OutpostReserveSupport.planApply("ApplyReserve", "Sliding", 0, false, 10, false, 0)
        val override = plan as OutpostReserveSupport.ApplyPlan.Override
        val tree = MaaJsonTree.parse(override.overrideJson) as Map<*, *>
        val attach = (tree["Sliding"] as Map<*, *>)["attach"] as Map<*, *>
        assertEquals(999999, (attach["TargetQuantity"] as Number).toInt())
        assertEquals(false, attach["ReverseTarget"])
        assertEquals(listOf("OutpostTradingSell"), (tree["Sliding"] as Map<*, *>)["next"])
    }

    @Test
    fun `planApply 活动物品额度充足按额度整批卖`() {
        val plan = OutpostReserveSupport.planApply(
            nodeName = "ApplyReserve",
            slidingNode = "Sliding",
            quantity = 0,
            configured = false,
            unitPrice = 100,
            activity = true,
            stockBills = 2500,
        )
        val override = plan as OutpostReserveSupport.ApplyPlan.Override
        assertEquals("Sliding", override.next)
        val tree = MaaJsonTree.parse(override.overrideJson) as Map<*, *>
        val sliding = tree["Sliding"] as Map<*, *>
        assertEquals(listOf("OutpostTradingSell"), sliding["next"])
        assertEquals(25, ((sliding["attach"] as Map<*, *>)["TargetQuantity"] as Number).toInt())
        // 活动物品不套用保留规则：不会覆盖到 ReserveAlreadySatisfied
        assertEquals(mapOf("next" to listOf("Sliding")), tree["ApplyReserve"])
    }

    @Test
    fun `planApply 活动物品额度不足一件直接跳 SellLoopEnd`() {
        val plan = OutpostReserveSupport.planApply(
            nodeName = "ApplyReserve",
            slidingNode = "Sliding",
            quantity = 0,
            configured = false,
            unitPrice = 100,
            activity = true,
            stockBills = 99,
        )
        val override = plan as OutpostReserveSupport.ApplyPlan.Override
        assertEquals("OutpostTradingSellLoopEnd", override.next)
        val tree = MaaJsonTree.parse(override.overrideJson) as Map<*, *>
        // 不覆盖滑块节点，仅把当前节点 next 指向结束
        assertNull(tree["Sliding"])
        assertEquals(mapOf("next" to listOf("OutpostTradingSellLoopEnd")), tree["ApplyReserve"])
    }

    // ───────────────────────── 数据映射 ─────────────────────────

    @Test
    fun `OutpostData item_id 与商品名互逆`() {
        assertEquals("item_proc_battery_3", OutpostData.itemIdOfName("高容谷地电池"))
        assertEquals("高容谷地电池", OutpostData.nameOfItem("item_proc_battery_3"))
        assertEquals(OutpostData.nameByItemId.size, OutpostData.itemIdByName.size)
        assertNull(OutpostData.nameOfItem("item_not_exist"))
        assertNull(OutpostData.itemIdOfName("不存在的货物"))
    }

    @Test
    fun `OutpostData 活动物品是 Wuling 两个龙泡泡`() {
        assertEquals(setOf("重息壤龙泡泡", "息壤龙泡泡"), OutpostData.activityItemNames)
        assertTrue(OutpostData.isActivityItemName("息壤龙泡泡"))
        assertFalse(OutpostData.isActivityItemName("高容谷地电池"))
    }

    @Test
    fun `OutpostData 每个据点货物都能反查到单价`() {
        for ((location, names) in OutpostData.locationItems) {
            val prices = OutpostData.unitPriceByLocation[location]
            assertNotNull("location $location missing prices", prices)
            for (name in names) {
                assertNotNull("location $location item $name missing price", prices!![name])
                assertNotNull("item $name missing id", OutpostData.itemIdOfName(name))
            }
        }
    }
}
