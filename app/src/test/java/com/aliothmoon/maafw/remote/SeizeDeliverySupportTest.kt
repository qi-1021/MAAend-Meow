package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抢占送货任务纯逻辑测试（对齐上游
 * `agent/go-service/seizedeliveryjobs/find_target.go` + `scan_target.go`）。
 *
 * 覆盖：奖励解析（万/K/M）、下限解析（V1/V2）、链式 ROI 偏移、候选过滤/去重/排序、
 * 找目标取框、扫描会话状态机与覆写 JSON。正常/边界/退化（空列表、无匹配、重复目标、数量不足）。
 */
class SeizeDeliverySupportTest {

    private fun box(x: Int, y: Int, w: Int = 30, h: Int = 20) = SeizeDeliverySupport.Box(x, y, w, h)

    /** 一行「全部命中」的观测：奖励 16.3 万，各框有效。 */
    private fun row(
        rewardText: String = "16.3万",
        rewardY: Int = 230,
        rewardBox: SeizeDeliverySupport.Box? = box(840, rewardY, 30, 10),
        originBox: SeizeDeliverySupport.Box? = box(611, rewardY - 38, 80, 24),
        acceptBox: SeizeDeliverySupport.Box? = box(1066, rewardY - 4, 100, 22),
        viewBox: SeizeDeliverySupport.Box? = box(625, rewardY - 8, 64, 20),
        originText: String? = "武陵城",
    ) = SeizeDeliverySupport.RowObservation(
        rewardText = rewardText,
        rewardBox = rewardBox,
        originText = originText,
        originBox = originBox,
        acceptBox = acceptBox,
        viewLocationBox = viewBox,
    )

    // ── parseRewardFloat ──

    @Test
    fun `奖励 纯数字按万`() {
        assertEquals(15.9, SeizeDeliverySupport.parseRewardFloat("15.9")!!, 1e-9)
    }

    @Test
    fun `奖励 万后缀`() {
        assertEquals(16.3, SeizeDeliverySupport.parseRewardFloat("16.3万")!!, 1e-9)
    }

    @Test
    fun `奖励 繁体萬后缀`() {
        assertEquals(16.3, SeizeDeliverySupport.parseRewardFloat("16.3萬")!!, 1e-9)
    }

    @Test
    fun `奖励 K后缀换算为万`() {
        assertEquals(11.9, SeizeDeliverySupport.parseRewardFloat("119K")!!, 1e-9)
    }

    @Test
    fun `奖励 小写k后缀`() {
        assertEquals(11.9, SeizeDeliverySupport.parseRewardFloat("119k")!!, 1e-9)
    }

    @Test
    fun `奖励 M后缀换算为万`() {
        assertEquals(120.0, SeizeDeliverySupport.parseRewardFloat("1.2M")!!, 1e-9)
    }

    @Test
    fun `奖励 小写m后缀`() {
        assertEquals(120.0, SeizeDeliverySupport.parseRewardFloat("1.2m")!!, 1e-9)
    }

    @Test
    fun `奖励 前后空白被裁剪`() {
        assertEquals(12.0, SeizeDeliverySupport.parseRewardFloat("  12万 ")!!, 1e-9)
    }

    @Test
    fun `奖励 空串与null返回null`() {
        assertNull(SeizeDeliverySupport.parseRewardFloat(null))
        assertNull(SeizeDeliverySupport.parseRewardFloat(""))
        assertNull(SeizeDeliverySupport.parseRewardFloat("   "))
    }

    @Test
    fun `奖励 非数字返回null`() {
        assertNull(SeizeDeliverySupport.parseRewardFloat("abc"))
        assertNull(SeizeDeliverySupport.parseRewardFloat("万"))
        assertNull(SeizeDeliverySupport.parseRewardFloat("K"))
        assertNull(SeizeDeliverySupport.parseRewardFloat("1,200"))
    }

    // ── parseMinReward ──

    @Test
    fun `下限 V1顶层expected`() {
        assertEquals(15.9, SeizeDeliverySupport.parseMinReward("""{"expected":["15.9"]}""")!!, 1e-9)
    }

    @Test
    fun `下限 V2 recognition_param_expected`() {
        val json = """{"recognition":{"type":"OCR","param":{"expected":["16.3万"]}}}"""
        assertEquals(16.3, SeizeDeliverySupport.parseMinReward(json)!!, 1e-9)
    }

    @Test
    fun `下限 顶层为空时回退V2`() {
        val json = """{"expected":[],"recognition":{"param":{"expected":["20万"]}}}"""
        assertEquals(20.0, SeizeDeliverySupport.parseMinReward(json)!!, 1e-9)
    }

    @Test
    fun `下限 缺失expected返回null`() {
        assertNull(SeizeDeliverySupport.parseMinReward("{}"))
        assertNull(SeizeDeliverySupport.parseMinReward("""{"expected":[]}"""))
        assertNull(SeizeDeliverySupport.parseMinReward(""))
        assertNull(SeizeDeliverySupport.parseMinReward(null))
    }

    @Test
    fun `下限 非法JSON返回null`() {
        assertNull(SeizeDeliverySupport.parseMinReward("not-json"))
        assertNull(SeizeDeliverySupport.parseMinReward("""{"expected":["oops"]}"""))
    }

    // ── offsetBox / 链式 ROI ──

    @Test
    fun `偏移 正常相加`() {
        assertEquals(box(840, 230, 30, 10), SeizeDeliverySupport.offsetBox(box(840, 200, 30, 30), intArrayOf(0, 30, 0, -20)))
    }

    @Test
    fun `偏移 含负分量`() {
        val r = SeizeDeliverySupport.offsetBox(box(840, 230, 30, 10), intArrayOf(-229, -38, 50, 14))
        assertEquals(box(611, 192, 80, 24), r)
    }

    @Test
    fun `偏移 box为空返回null`() {
        assertNull(SeizeDeliverySupport.offsetBox(null, intArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun `偏移 offset不足四位返回null`() {
        assertNull(SeizeDeliverySupport.offsetBox(box(1, 2), intArrayOf(0, 0, 0)))
    }

    @Test
    fun `链式偏移 锚点到奖励再到出发地`() {
        val anchor = box(840, 200, 30, 30)
        val reward = SeizeDeliverySupport.offsetBox(anchor, SeizeDeliverySupport.OFFSET_TOKEN_TO_REWARD)!!
        val origin = SeizeDeliverySupport.offsetBox(reward, SeizeDeliverySupport.OFFSET_REWARD_TO_ORIGIN)!!
        assertEquals(box(840, 230, 30, 10), reward)
        assertEquals(box(611, 192, 80, 24), origin)
    }

    // ── detail 解析 ──

    @Test
    fun `filtered 框解析 正常`() {
        val tree = MaaJsonTree.parse(
            """{"all":[{"box":[10,20,30,40]}],"best":{"box":[10,20,30,40]},"filtered":[{"box":[10,20,30,40]},{"box":[10,200,30,40]}]}""",
        )
        assertEquals(listOf(box(10, 20, 30, 40), box(10, 200, 30, 40)), SeizeDeliverySupport.parseFilteredBoxes(tree))
    }

    @Test
    fun `filtered 框解析 缺filtered返回空`() {
        assertEquals(emptyList<SeizeDeliverySupport.Box>(), SeizeDeliverySupport.parseFilteredBoxes(MaaJsonTree.parse("{}")))
        assertEquals(emptyList<SeizeDeliverySupport.Box>(), SeizeDeliverySupport.parseFilteredBoxes(null))
        assertEquals(emptyList<SeizeDeliverySupport.Box>(), SeizeDeliverySupport.parseFilteredBoxes("not-map"))
    }

    @Test
    fun `filtered 框解析 坏条目跳过`() {
        val tree = MaaJsonTree.parse("""{"filtered":[{"box":[1,2]},{"box":[3,4,5,6]},{"nobox":true}]}""")
        assertEquals(listOf(box(3, 4, 5, 6)), SeizeDeliverySupport.parseFilteredBoxes(tree))
    }

    @Test
    fun `filtered first 文本与框`() {
        val tree = MaaJsonTree.parse("""{"filtered":[{"text":"16.3万","box":[840,230,30,10]},{"text":"x"}]}""")
        val hit = SeizeDeliverySupport.parseFilteredFirst(tree)!!
        assertEquals("16.3万", hit.text)
        assertEquals(box(840, 230, 30, 10), hit.box)
    }

    @Test
    fun `filtered first 空或缺失返回null`() {
        assertNull(SeizeDeliverySupport.parseFilteredFirst(MaaJsonTree.parse("""{"filtered":[]}""")))
        assertNull(SeizeDeliverySupport.parseFilteredFirst(MaaJsonTree.parse("{}")))
    }

    @Test
    fun `filtered first 缺text时为空串`() {
        val tree = MaaJsonTree.parse("""{"filtered":[{"box":[1,2,3,4]}]}""")
        assertEquals("", SeizeDeliverySupport.parseFilteredFirst(tree)!!.text)
    }

    // ── assembleJobs：过滤 ──

    @Test
    fun `装配 低于下限被过滤`() {
        val items = SeizeDeliverySupport.assembleJobs(listOf(row("15.8万"), row("16.3万", rewardY = 260)), 15.9)
        assertEquals(1, items.size)
        assertEquals(260, items[0].rewardBox.y)
    }

    @Test
    fun `装配 恰好等于下限被保留`() {
        val items = SeizeDeliverySupport.assembleJobs(listOf(row("15.9")), 15.9)
        assertEquals(1, items.size)
    }

    @Test
    fun `装配 空列表返回空`() {
        assertTrue(SeizeDeliverySupport.assembleJobs(emptyList(), 0.0).isEmpty())
    }

    @Test
    fun `装配 奖励框退化被过滤`() {
        val items = SeizeDeliverySupport.assembleJobs(listOf(row(rewardBox = box(840, 230, 0, 0))), 0.0)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `装配 奖励文本不可解析被过滤`() {
        assertTrue(SeizeDeliverySupport.assembleJobs(listOf(row(rewardText = "oops")), 0.0).isEmpty())
    }

    @Test
    fun `装配 缺接取框被过滤`() {
        assertTrue(SeizeDeliverySupport.assembleJobs(listOf(row(acceptBox = null)), 0.0).isEmpty())
    }

    @Test
    fun `装配 缺查看位置框被过滤`() {
        assertTrue(SeizeDeliverySupport.assembleJobs(listOf(row(viewBox = null)), 0.0).isEmpty())
    }

    @Test
    fun `装配 缺出发地框被过滤`() {
        assertTrue(SeizeDeliverySupport.assembleJobs(listOf(row(originBox = null)), 0.0).isEmpty())
    }

    // ── assembleJobs：去重 / 排序 ──

    @Test
    fun `装配 重复锚点去重`() {
        // 同一行被两个模板各命中一次：锚点框相同，应只保留一条
        val items = SeizeDeliverySupport.assembleJobs(listOf(row(), row()), 0.0)
        assertEquals(1, items.size)
    }

    @Test
    fun `装配 重复锚点保留先出现者`() {
        val firstAccept = box(100, 100, 10, 10)
        val secondAccept = box(999, 999, 10, 10)
        val items = SeizeDeliverySupport.assembleJobs(
            listOf(row(acceptBox = firstAccept), row(acceptBox = secondAccept)),
            0.0,
        )
        assertEquals(1, items.size)
        assertEquals(firstAccept, items[0].acceptBox)
    }

    @Test
    fun `装配 纵向排序`() {
        val items = SeizeDeliverySupport.assembleJobs(
            listOf(row(rewardY = 400), row(rewardY = 200), row(rewardY = 300)),
            0.0,
        )
        assertEquals(listOf(200, 300, 400), items.map { it.rewardBox.y })
    }

    @Test
    fun `装配 同行不同x按x次级排序`() {
        // centerY 相同（y 与 h 相同），按 centerX 排
        val items = SeizeDeliverySupport.assembleJobs(
            listOf(row(rewardY = 200, rewardBox = box(900, 200, 30, 10)), row(rewardY = 200, rewardBox = box(800, 200, 30, 10))),
            0.0,
        )
        assertEquals(listOf(800, 900), items.map { it.rewardBox.x })
    }

    @Test
    fun `装配 链式偏移构造的观测可命中`() {
        val anchor = box(840, 200, 30, 30)
        val reward = SeizeDeliverySupport.offsetBox(anchor, SeizeDeliverySupport.OFFSET_TOKEN_TO_REWARD)!!
        val observation = SeizeDeliverySupport.RowObservation(
            rewardText = "16.3万",
            rewardBox = reward,
            originText = "武陵城",
            originBox = SeizeDeliverySupport.offsetBox(reward, SeizeDeliverySupport.OFFSET_REWARD_TO_ORIGIN),
            acceptBox = SeizeDeliverySupport.offsetBox(reward, SeizeDeliverySupport.OFFSET_REWARD_TO_ACCEPT),
            viewLocationBox = SeizeDeliverySupport.offsetBox(reward, SeizeDeliverySupport.OFFSET_REWARD_TO_VIEW),
        )
        val items = SeizeDeliverySupport.assembleJobs(listOf(observation), 15.9)
        assertEquals(1, items.size)
        assertEquals(box(1066, 226, 100, 22), items[0].acceptBox)
    }

    // ── findTargetBox ──

    @Test
    fun `找目标 取首个接取框`() {
        val items = SeizeDeliverySupport.assembleJobs(listOf(row(rewardY = 400), row(rewardY = 200)), 0.0)
        assertEquals(items[0].acceptBox, SeizeDeliverySupport.findTargetBox(items))
        assertEquals(196, SeizeDeliverySupport.findTargetBox(items)!!.y)
    }

    @Test
    fun `找目标 空列表返回null`() {
        assertNull(SeizeDeliverySupport.findTargetBox(emptyList()))
    }

    // ── iou / dedupe ──

    @Test
    fun `iou 完全重合为1`() {
        assertEquals(1.0, SeizeDeliverySupport.iou(box(0, 0, 10, 10), box(0, 0, 10, 10)), 1e-9)
    }

    @Test
    fun `iou 不相交为0`() {
        assertEquals(0.0, SeizeDeliverySupport.iou(box(0, 0, 10, 10), box(100, 100, 10, 10)), 1e-9)
    }

    @Test
    fun `iou 零面积框为0`() {
        assertEquals(0.0, SeizeDeliverySupport.iou(box(0, 0, 0, 0), box(0, 0, 10, 10)), 1e-9)
    }

    @Test
    fun `iou 部分重叠`() {
        assertEquals(25.0 / 175.0, SeizeDeliverySupport.iou(box(0, 0, 10, 10), box(5, 5, 10, 10)), 1e-9)
    }

    @Test
    fun `去重 部分重叠未达阈值不去重`() {
        val a = SeizeDeliverySupport.JobItem(box(0, 0, 10, 10), "a", box(1, 1), box(2, 2))
        val b = SeizeDeliverySupport.JobItem(box(5, 5, 10, 10), "b", box(1, 1), box(2, 2))
        assertEquals(2, SeizeDeliverySupport.dedupeByAnchor(listOf(a, b)).size)
    }

    // ── ScanSession ──

    @Test
    fun `扫描会话 存储后可消费至用尽`() {
        val session = SeizeDeliverySupport.ScanSession()
        assertFalse(session.isScanned)
        assertNull(session.current())
        session.store(listOf(job(200), job(300)))
        assertTrue(session.isScanned)
        assertEquals(2, session.size)
        assertEquals(2, session.remaining)
        assertEquals(200, session.current()!!.rewardBox.y)
        assertTrue(session.advance())
        assertEquals(300, session.current()!!.rewardBox.y)
        assertTrue(session.advance())
        assertNull(session.current())
        assertFalse(session.advance())
        assertEquals(0, session.remaining)
    }

    @Test
    fun `扫描会话 二次存储被忽略`() {
        val session = SeizeDeliverySupport.ScanSession()
        session.store(listOf(job(200)))
        session.advance()
        session.store(listOf(job(300), job(400)))
        assertEquals(1, session.size)
        assertNull(session.current())
    }

    @Test
    fun `扫描会话 reset清空`() {
        val session = SeizeDeliverySupport.ScanSession()
        session.store(listOf(job(200)))
        session.advance()
        session.reset()
        assertFalse(session.isScanned)
        assertEquals(0, session.size)
        assertFalse(session.advance())
    }

    @Test
    fun `扫描会话 空列表存储后用尽`() {
        val session = SeizeDeliverySupport.ScanSession()
        session.store(emptyList())
        assertTrue(session.isScanned)
        assertNull(session.current())
        assertFalse(session.advance())
    }

    // ── scanTargets / override JSON ──

    @Test
    fun `扫描目标 有效项返回两个点击目标`() {
        val item = job(200)
        val targets = SeizeDeliverySupport.scanTargets(item)!!
        assertEquals(item.viewLocationBox, targets.viewLocation)
        assertEquals(item.acceptBox, targets.accept)
    }

    @Test
    fun `扫描目标 退化框返回null`() {
        val item = SeizeDeliverySupport.JobItem(box(840, 230, 30, 10), "x", box(0, 0, 0, 0), box(625, 222, 64, 20))
        assertNull(SeizeDeliverySupport.scanTargets(item))
    }

    @Test
    fun `扫描覆写JSON 含两个节点与坐标`() {
        val json = Json.parseToJsonElement(
            SeizeDeliverySupport.buildScanOverrideJson(
                SeizeDeliverySupport.ScanTargets(box(625, 222, 64, 20), box(1066, 226, 100, 22)),
            ),
        ).jsonObject
        val view = json[SeizeDeliverySupport.VIEW_LOCATION_CLICK_NODE]!!.jsonObject["target"]!!.jsonArray
        val accept = json[SeizeDeliverySupport.ACCEPT_CLICK_NODE]!!.jsonObject["target"]!!.jsonArray
        assertEquals(4, view.size)
        assertEquals(625, view[0].jsonPrimitive.int)
        assertEquals(222, view[1].jsonPrimitive.int)
        assertEquals(64, view[2].jsonPrimitive.int)
        assertEquals(20, view[3].jsonPrimitive.int)
        assertEquals(1066, accept[0].jsonPrimitive.int)
        assertEquals(100, accept[2].jsonPrimitive.int)
    }

    @Test
    fun `ROI覆写JSON 节点与roi正确`() {
        val json = Json.parseToJsonElement(
            SeizeDeliverySupport.roiOverrideJson(SeizeDeliverySupport.REWARD_NODE, box(840, 230, 30, 10)),
        ).jsonObject
        val roi = json[SeizeDeliverySupport.REWARD_NODE]!!.jsonObject["roi"]!!.jsonArray
        assertEquals(listOf(840, 230, 30, 10), roi.map { it.jsonPrimitive.int })
    }

    private fun job(y: Int): SeizeDeliverySupport.JobItem = SeizeDeliverySupport.JobItem(
        rewardBox = box(840, y, 30, 10),
        originText = "武陵城",
        acceptBox = box(1066, y - 4, 100, 22),
        viewLocationBox = box(625, y - 8, 64, 20),
    )
}
