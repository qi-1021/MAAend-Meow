package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 干员列表扫描测试（对齐上游 outposttrading/operator/scan.go）。
 *
 * 重点：「连续两帧同签名才到底」的空帧语义、扫描状态的键与生命周期、
 * 以及 OCR 交接缓存「take 无论 key 是否匹配都清空」这条防泄漏规则。
 */
class OperatorScanTest {

    private fun candidate(name: String) =
        OperatorDataset.OperatorCandidate(name, listOf(name), 0, 0, 0)

    private fun item(text: String, y: Int = 0) =
        OperatorOcrMatch.Item(text, OcrBox(0, y, 10, 10))

    // ───────────────────── 本帧可见集合 ─────────────────────

    @Test
    fun `observedOperatorIds 输出字典序且每个名字只算一次`() {
        val items = listOf(item("Beta"), item("Alpha"), item("Alpha", y = 50))
        val result = OperatorScan.observedOperatorIds(items, listOf(candidate("Alpha"), candidate("Beta")))
        assertEquals(listOf("Alpha", "Beta"), result)
    }

    @Test
    fun `observedOperatorIds 忽略没命中的候选`() {
        val result = OperatorScan.observedOperatorIds(listOf(item("Alpha")), listOf(candidate("Alpha"), candidate("Gamma")))
        assertEquals(listOf("Alpha"), result)
    }

    // ───────────────────── 签名与到底 ─────────────────────

    @Test
    fun `signature 去空去重排序并用换行连接`() {
        assertEquals("a\nb", OperatorScan.signature(listOf(" b ", "a", "", "a", "  ")))
        assertEquals("", OperatorScan.signature(emptyList()))
        assertEquals("", OperatorScan.signature(listOf("   ")))
    }

    @Test
    fun `reachedBottom 要求前一帧非空`() {
        // 首帧永远不算到底
        assertFalse(OperatorScan.reachedBottom("", "a\nb"))
        assertFalse(OperatorScan.reachedBottom("", ""))
        assertTrue(OperatorScan.reachedBottom("a\nb", "a\nb"))
        assertFalse(OperatorScan.reachedBottom("a\nb", "a\nc"))
    }

    @Test
    fun `空签名帧会重置比较链 不会误判到底`() {
        // 上一帧有内容，本帧 OCR 瞬时失败得到空签名 -> 不是到底，且比较链被重置
        assertFalse(OperatorScan.reachedBottom("a\nb", ""))
        // 若实现成「与历史任意帧相同即到底」，这里就会误判
    }

    // ───────────────────── 命中规则 ─────────────────────

    @Test
    fun `shouldHit 按 result 与候选情况判定`() {
        assertTrue(OperatorScan.shouldHit("scan_done", hasCandidate = false))
        assertTrue(OperatorScan.shouldHit("scan_done", hasCandidate = true))
        assertTrue(OperatorScan.shouldHit("retry", hasCandidate = true))
        assertFalse(OperatorScan.shouldHit("retry", hasCandidate = false))
        assertTrue(OperatorScan.shouldHit("not_found", hasCandidate = false))
        assertFalse(OperatorScan.shouldHit("not_found", hasCandidate = true))
        // 其它/空值恒命中
        assertTrue(OperatorScan.shouldHit("", hasCandidate = false))
    }

    @Test
    fun `shouldWriteSnapshot 只认全量扫描`() {
        // 据点内的扫描永不写
        assertFalse(OperatorScan.shouldWriteSnapshot("target", "L1", "refresh", hasSnapshot = false))
        assertFalse(OperatorScan.shouldWriteSnapshot("all", "L1", "refresh", hasSnapshot = false))
        // 全量 + refresh 总写
        assertTrue(OperatorScan.shouldWriteSnapshot("all", "global", "refresh", hasSnapshot = true))
        // 全量 + cache 只在没有快照时写
        assertTrue(OperatorScan.shouldWriteSnapshot("all", "global", "cache", hasSnapshot = false))
        assertFalse(OperatorScan.shouldWriteSnapshot("all", "global", "cache", hasSnapshot = true))
    }

    // ───────────────────── 详情 JSON ─────────────────────

    @Test
    fun `outcomeDetailJson 的四种 reason`() {
        val state = OperatorScan.ScanState("k")
        // scan_error 由 state.error 决定，与 result 参数无关
        assertTrue(
            OperatorScan.outcomeDetailJson("not_found", "target", "L1", state.copy(error = "boom"))
                .contains("\"reason\":\"scan_error\""),
        )
        assertTrue(
            OperatorScan.outcomeDetailJson("not_found", "target", "L1", state)
                .contains("\"reason\":\"no_owned_candidate\""),
        )
        assertTrue(
            OperatorScan.outcomeDetailJson("not_found", "restore", "L1", state)
                .contains("\"reason\":\"no_available_candidate\""),
        )
        assertTrue(
            OperatorScan.outcomeDetailJson("not_found", "all", "global", state)
                .contains("\"reason\":\"no_candidate\""),
        )
        // scan_error 优先级最高
        val errored = state.copy(error = "boom")
        assertTrue(
            OperatorScan.outcomeDetailJson("not_found", "target", "L1", errored)
                .contains("\"reason\":\"scan_error\""),
        )
    }

    @Test
    fun `outcomeDetailJson 空字段按 omitempty 省略`() {
        val json = OperatorScan.outcomeDetailJson("not_found", "target", "L1", OperatorScan.ScanState("k"))
        assertFalse(json.contains("expected_candidates"))
        assertFalse(json.contains("observed_candidates"))
        assertFalse(json.contains("error"))
    }

    @Test
    fun `outcomeDetailJson 有值时带上候选与错误`() {
        val state = OperatorScan.ScanState("k").copy(
            expectedCandidates = listOf("A"),
            observedCandidates = listOf("B"),
            error = "boom",
        )
        val json = OperatorScan.outcomeDetailJson("error", "target", "L1", state)
        assertTrue(json.contains("\"expected_candidates\":[\"A\"]"))
        assertTrue(json.contains("\"observed_candidates\":[\"B\"]"))
        assertTrue(json.contains("\"error\":\"boom\""))
    }

    // ───────────────────── 集合函数顺序 ─────────────────────

    @Test
    fun `candidateIds 保候选项配置顺序`() {
        assertEquals(listOf("B", "A"), OperatorScan.candidateIds(listOf(candidate("B"), candidate("A"))))
        // 去重
        assertEquals(listOf("B", "A"), OperatorScan.candidateIds(listOf(candidate("B"), candidate("A"), candidate("B"))))
    }

    @Test
    fun `observedConfiguredNames 保候选项配置顺序而不是 observed 顺序`() {
        val result = OperatorScan.observedConfiguredNames(
            listOf(candidate("A"), candidate("B"), candidate("C")),
            observed = listOf("C", "A"),
        )
        assertEquals(listOf("A", "C"), result)
    }

    // ───────────────────── 扫描状态表 ─────────────────────

    @Test
    fun `ScanStates 复用同一 key 并支持删除与清空`() {
        val states = OperatorScan.ScanStates()
        val first = states.getOrCreate("k1")
        first.observed += "A"
        states.put(first)

        // 同 key 拿回同一份（getOrCreate 不覆盖已有）
        val again = states.getOrCreate("k1")
        assertEquals(listOf("A"), again.observed)

        // copyOf 是副本
        val copy = states.copyOf("k1")!!
        copy.observed += "B"
        assertEquals(listOf("A"), states.copyOf("k1")!!.observed)

        states.delete("k1")
        assertNull(states.copyOf("k1"))
        assertEquals(0, states.size())

        states.put(OperatorScan.ScanState("k2"))
        states.clear()
        assertEquals(0, states.size())
    }

    @Test
    fun `扫描状态 key 不含 taskID`() {
        assertEquals("u|cache|all|global", OperatorScan.scanStateKey("u", "cache", "all", "global"))
    }

    // ───────────────────── OCR 交接缓存 ─────────────────────

    @Test
    fun `OcrHandoff key 匹配时返回内容`() {
        val handoff = OperatorScan.OcrHandoff()
        val key = OperatorScan.HandoffKey(1L, "L1", listOf(0, 0, 10, 10))
        handoff.store(key, listOf(item("Alpha")))
        assertEquals(1, handoff.take(key)?.size)
    }

    @Test
    fun `OcrHandoff key 不匹配时返回空且照样清空`() {
        val handoff = OperatorScan.OcrHandoff()
        val stored = OperatorScan.HandoffKey(1L, "L1", listOf(0, 0, 10, 10))
        handoff.store(stored, listOf(item("Alpha")))

        // 换 taskID 取 -> 拿不到
        val other = OperatorScan.HandoffKey(2L, "L1", listOf(0, 0, 10, 10))
        assertNull(handoff.take(other))
        // 关键：原 key 也不能再取到（已被无条件清空），避免跨任务泄漏
        assertNull(handoff.take(stored))
    }

    @Test
    fun `OcrHandoff 空槽取回 null`() {
        assertNull(OperatorScan.OcrHandoff().take(OperatorScan.HandoffKey(1L, "L1", listOf(0, 0, 1, 1))))
    }

    @Test
    fun `OcrHandoff store 覆盖旧槽`() {
        val handoff = OperatorScan.OcrHandoff()
        val key = OperatorScan.HandoffKey(1L, "L1", listOf(0, 0, 10, 10))
        handoff.store(key, listOf(item("Alpha")))
        handoff.store(key, listOf(item("Beta")))
        assertEquals("Beta", handoff.take(key)?.first()?.text)
    }
}
