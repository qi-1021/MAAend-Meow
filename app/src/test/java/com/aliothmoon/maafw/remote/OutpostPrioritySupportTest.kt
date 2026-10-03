package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OutpostTradingPrioritySession` / `OutpostTradingPriorityItem` 纯逻辑测试
 * （对齐上游 outposttrading/goods/session.go 与 selection.go）。
 *
 * 重点覆盖：operation 分发不再静默、enabled/only_preferred 真正生效、
 * 选品排除（永不售卖 / 已满足 / 缺货 / 已尝试）与 pending→commit 语义。
 */
class OutpostPrioritySupportTest {

    private fun param(map: Map<String, Any?>): OutpostPrioritySupport.ActionParam? =
        OutpostPrioritySupport.parseParam(map)

    // ───────────────────────── 参数解析 ─────────────────────────

    @Test
    fun `parseParam 接受全部 operation`() {
        assertEquals(
            OutpostPrioritySupport.ActionParam("configure", enabled = true, onlyPreferred = true),
            param(mapOf("operation" to "configure", "enabled" to true, "only_preferred" to true)),
        )
        assertEquals(
            OutpostPrioritySupport.ActionParam("configure_strategy", strategy = "price", minimumPrice = 50),
            param(mapOf("operation" to "configure_strategy", "strategy" to "price", "minimum_unit_price" to 50)),
        )
        assertEquals(
            OutpostPrioritySupport.ActionParam("reset_preferred", enabled = true),
            param(mapOf("operation" to "reset_preferred", "enabled" to true)),
        )
        assertEquals(
            OutpostPrioritySupport.ActionParam("reset_goods_selection"),
            param(mapOf("operation" to "reset_goods_selection")),
        )
        assertEquals(
            OutpostPrioritySupport.ActionParam("register", itemId = "item_a"),
            param(mapOf("operation" to "register", "item_id" to " item_a ")),
        )
        assertEquals(
            OutpostPrioritySupport.ActionParam("commit", location = "L"),
            param(mapOf("operation" to "commit", "location" to " L ")),
        )
        assertEquals(
            OutpostPrioritySupport.ActionParam("adopt", location = "L"),
            param(mapOf("operation" to "adopt", "location" to "L")),
        )
        assertEquals(
            OutpostPrioritySupport.ActionParam("out_of_stock", location = "L"),
            param(mapOf("operation" to "out_of_stock", "location" to "L")),
        )
    }

    @Test
    fun `parseParam 拒绝未知 operation 与非法 strategy`() {
        assertNull(param(mapOf("operation" to "unknown")))
        // 空串不再被静默接受（上游 sellstrategy.New("") 会失败）
        assertNull(param(mapOf("operation" to "configure_strategy", "strategy" to "")))
        assertNull(param(mapOf("operation" to "configure_strategy", "strategy" to "bogus")))
        assertNull(param(mapOf("operation" to "commit", "location" to " ")))
        assertNull(param(mapOf("operation" to "adopt")))
        assertNull(param(mapOf("operation" to "out_of_stock")))
        assertNull(OutpostPrioritySupport.parseParam("not-a-map"))
        assertNull(OutpostPrioritySupport.parseParam(null))
    }

    // ───────────────────────── enabled / only_preferred 语义 ─────────────────────────

    @Test
    fun `policy 在未 configure 或未启用地区时不套用优先`() {
        val s = OutpostPrioritySupport.Session()
        s.registerPreferred("甲")
        // enabled 默认 false
        assertTrue(s.policy().preferred.isEmpty())
        assertFalse(s.policy().onlyPreferred)

        s.configure(enabled = true, onlyPreferred = true)
        // regionEnabled 默认 false
        assertTrue(s.policy().preferred.isEmpty())
        assertFalse(s.policy().onlyPreferred)

        // 上游顺序：先 reset_preferred（会清空优先表），再逐个 register
        s.resetPreferred(enabled = true)
        s.registerPreferred("甲")
        assertEquals(listOf("甲"), s.policy().preferred)
        assertTrue(s.policy().onlyPreferred)
    }

    @Test
    fun `configure only_preferred 在 enabled 为假时被归一为假`() {
        val s = OutpostPrioritySupport.Session()
        s.configure(enabled = false, onlyPreferred = true)
        s.resetPreferred(enabled = true)
        assertFalse(s.policy().onlyPreferred)
    }

    @Test
    fun `resetPreferred 清空优先表但保留策略`() {
        val s = OutpostPrioritySupport.Session()
        s.configureStrategy("price", 30)
        s.resetPreferred(enabled = true)
        s.registerPreferred("甲")
        assertEquals(listOf("甲"), s.preferredNames())
        s.resetPreferred(enabled = true)
        assertTrue(s.preferredNames().isEmpty())
        assertEquals("price", s.strategy)
        assertEquals(30, s.minimumPrice)
    }

    @Test
    fun `registerPreferred 去重且保留首次顺序`() {
        val s = OutpostPrioritySupport.Session()
        assertTrue(s.registerPreferred("甲"))
        assertTrue(s.registerPreferred("乙"))
        assertFalse(s.registerPreferred("甲"))
        assertEquals(listOf("甲", "乙"), s.preferredNames())
        assertFalse(s.registerPreferred(""))
    }

    @Test
    fun `resetAll 重建为默认`() {
        val s = OutpostPrioritySupport.Session()
        s.configure(enabled = true, onlyPreferred = true)
        s.configureStrategy("stock", 10)
        s.resetPreferred(enabled = true)
        s.registerPreferred("甲")
        s.resetAll()
        assertFalse(s.enabled)
        assertEquals("rarity", s.strategy)
        assertEquals(0, s.minimumPrice)
        assertTrue(s.preferredNames().isEmpty())
        assertTrue(s.attemptedNames("L").isEmpty())
    }

    // ───────────────────────── pending / commit / adopt / 缺货 ─────────────────────────

    @Test
    fun `commit 把 pending 转正并记录当前`() {
        val s = OutpostPrioritySupport.Session()
        s.setPending("L", "甲")
        assertEquals("甲", s.pendingName("L"))
        assertEquals("甲", s.commit("L"))
        assertEquals("", s.pendingName("L"))
        assertEquals(setOf("甲"), s.attemptedNames("L"))
        assertEquals("甲", s.currentName("L"))
        // 再次 commit 没有 pending → null
        assertNull(s.commit("L"))
    }

    @Test
    fun `adopt 直接记录当前并清 pending`() {
        val s = OutpostPrioritySupport.Session()
        s.setPending("L", "甲")
        s.adopt("L", "乙")
        assertEquals("", s.pendingName("L"))
        // adopt 只记录被采用的当前货品，不会把旧 pending 也当已尝试（上游 session.go:370）
        assertEquals(setOf("乙"), s.attemptedNames("L"))
        assertEquals("乙", s.currentName("L"))
    }

    @Test
    fun `markOutOfStock 依赖 current`() {
        val s = OutpostPrioritySupport.Session()
        val missing = s.markOutOfStock("L")
        assertFalse(missing.ok)
        s.adopt("L", "甲")
        val first = s.markOutOfStock("L")
        assertTrue(first.ok)
        assertTrue(first.marked)
        assertEquals("甲", first.name)
        assertEquals(setOf("甲"), s.outOfStockNames())
        val second = s.markOutOfStock("L")
        assertTrue(second.ok)
        assertFalse(second.marked)
    }

    @Test
    fun `observeExhaustion 需要连续两次同一集合`() {
        val s = OutpostPrioritySupport.Session()
        assertFalse(s.observeExhaustion("L", listOf("甲", "乙")))
        assertTrue(s.observeExhaustion("L", listOf("乙", "甲"))) // 排序后同签名 → count 2
        assertTrue(s.observeExhaustion("L", listOf("甲", "乙")))
        // 集合变化后重新计数
        assertFalse(s.observeExhaustion("L", listOf("甲")))
        assertTrue(s.observeExhaustion("L", listOf("甲")))
    }

    @Test
    fun `setPending 清耗尽观察 commit adopt 也清`() {
        val s = OutpostPrioritySupport.Session()
        s.observeExhaustion("L", listOf("甲"))
        s.setPending("L", "乙")
        assertFalse(s.observeExhaustion("L", listOf("甲"))) // 被清后从 1 开始
        s.observeExhaustion("L", listOf("甲"))
        s.commit("L")
        assertFalse(s.observeExhaustion("L", listOf("甲")))
    }

    // ───────────────────────── 选品过滤 ─────────────────────────

    @Test
    fun `selectableNames 优先项排前 其余保持基础顺序`() {
        val order = OutpostPrioritySupport.selectableNames(
            baseOrder = listOf("甲", "乙", "丙", "丁"),
            preferred = listOf("丙"),
            onlyPreferred = false,
            excluded = emptySet(),
        )
        assertEquals(listOf("丙", "甲", "乙", "丁"), order)
    }

    @Test
    fun `selectableNames onlyPreferred 只保留优先项`() {
        val order = OutpostPrioritySupport.selectableNames(
            baseOrder = listOf("甲", "乙", "丙"),
            preferred = listOf("丙"),
            onlyPreferred = true,
            excluded = emptySet(),
        )
        assertEquals(listOf("丙"), order)
    }

    @Test
    fun `selectableNames 排除永不售卖 已满足 缺货 已尝试`() {
        val order = OutpostPrioritySupport.selectableNames(
            baseOrder = listOf("甲", "乙", "丙", "丁", "戊"),
            preferred = listOf("甲", "乙"),
            onlyPreferred = false,
            excluded = setOf("甲", "丙", "丁"),
        )
        // 甲被排除，乙优先，然后剩下的戊
        assertEquals(listOf("乙", "戊"), order)
    }

    @Test
    fun `selectableNames 优先项不在据点表内被跳过`() {
        val order = OutpostPrioritySupport.selectableNames(
            baseOrder = listOf("甲", "乙"),
            preferred = listOf("别据点货物", "乙"),
            onlyPreferred = false,
            excluded = emptySet(),
        )
        assertEquals(listOf("乙", "甲"), order)
    }

    @Test
    fun `isSelectable 反映 onlyPreferred 与排除集合`() {
        assertTrue(OutpostPrioritySupport.isSelectable("甲", emptyList(), false, emptySet()))
        assertFalse(OutpostPrioritySupport.isSelectable("甲", emptyList(), false, setOf("甲")))
        assertFalse(OutpostPrioritySupport.isSelectable("甲", listOf("乙"), true, emptySet()))
        assertTrue(OutpostPrioritySupport.isSelectable("甲", listOf("甲"), true, emptySet()))
    }

    // ───────────────────────── OCR 匹配 ─────────────────────────

    private fun ocr(text: String, box: IntArray = intArrayOf(1, 2, 3, 4)) =
        GoodsSupport.OcrItem(text, box)

    @Test
    fun `firstVisible 按顺序命中 不做阅读顺序兜底`() {
        val items = listOf(ocr("甲货"), ocr("丙货"))
        val hit = OutpostPrioritySupport.firstVisible(items, listOf("乙", "丙", "甲"))
        assertEquals("丙", hit?.first)
        // 全部候选都不在画面里 → null（不会退回阅读顺序选中甲）
        assertNull(OutpostPrioritySupport.firstVisible(items, listOf("乙")))
    }

    @Test
    fun `firstVisible 忽略没有包围盒的条目`() {
        val items = listOf(GoodsSupport.OcrItem("甲", null), ocr("乙"))
        val hit = OutpostPrioritySupport.firstVisible(items, listOf("甲", "乙"))
        assertEquals("乙", hit?.first)
    }

    @Test
    fun `visibleStandardNames 收集本帧可对应的据点货物`() {
        val names = listOf("荞愈胶囊", "柑实罐头")
        val items = listOf(ocr("荞愈胶囊"), ocr("无关文字"), ocr("柑实罐头"))
        assertEquals(listOf("荞愈胶囊", "柑实罐头"), OutpostPrioritySupport.visibleStandardNames(items, names))
    }

    @Test
    fun `resetGoodsSelection 清耗尽快照`() {
        val s = OutpostPrioritySupport.Session()
        s.setGoodsSelectionExhausted("L", listOf("甲"))
        assertEquals(listOf("甲"), s.goodsSelectionExhausted("L"))
        s.resetGoodsSelection()
        assertTrue(s.goodsSelectionExhausted("L").isEmpty())
    }

    @Test
    fun `OutpostData nameOfLocation 映射据点中文名`() {
        assertEquals("难民暂居处", OutpostData.nameOfLocation("RefugeeCamp"))
        assertEquals("基建前站", OutpostData.nameOfLocation("InfraStation"))
        assertEquals("重建指挥部", OutpostData.nameOfLocation("ReconstructionHQ"))
        assertEquals("天王坪援建点", OutpostData.nameOfLocation("SkyKingFlatsConstructionSite"))
        assertEquals("心脏修缮站", OutpostData.nameOfLocation("CardiacRemediationStation"))
        assertEquals("盈天台建设站", OutpostData.nameOfLocation("XiranflowCloudseederStation"))
        assertEquals("未知据点", OutpostData.nameOfLocation("未知据点"))
    }

    @Test
    fun `OutpostData formatCacheTime 格式化时间戳`() {
        assertEquals("未知", OutpostData.formatCacheTime(null))
        assertEquals("未知", OutpostData.formatCacheTime(""))
        assertEquals("未知", OutpostData.formatCacheTime("invalid-time"))
        val formatted = OutpostData.formatCacheTime("2026-10-03T07:00:00Z")
        assertTrue(formatted.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }
}

