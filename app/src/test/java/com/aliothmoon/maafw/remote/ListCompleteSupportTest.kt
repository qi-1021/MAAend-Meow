package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表到底判定的纯逻辑测试。
 *
 * 锁住的核心：**判定必须由画面（模板相似度）决定**，不是调用计数。计数器只作为
 * 防死循环的硬上限安全阀。覆盖上游 `listcomplete/recognition.go` 的
 * 参数解析 / attach.ready 读写 / 首轮建模板 / 命中判定 / 阈值非法回落语义。
 */
class ListCompleteSupportTest {

    private fun decide(
        session: ListCompleteSupport.Session,
        node: String,
        ready: Boolean,
        score: Double?,
        threshold: Double = ListCompleteSupport.DEFAULT_THRESHOLD,
        maxAttempts: Int = ListCompleteSupport.DEFAULT_MAX_ATTEMPTS,
    ) = session.decide(node, ready, score, threshold, maxAttempts)

    /** 把 MaaJsonTree 解析出的数字列表（Long/Double）统一成 Int 列表再比对。 */
    private fun ints(value: Any?): List<Int>? =
        (value as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }

    // ───────────────── parseParams ─────────────────

    @Test
    fun `缺省参数回落到 0-9 与默认上限`() {
        for (raw in listOf(null, "", "   ", "{}")) {
            val p = ListCompleteSupport.parseParams(raw)
            assertEquals("raw=<$raw>", 0.9, p.threshold, 1e-9)
            assertEquals(ListCompleteSupport.DEFAULT_MAX_ATTEMPTS, p.maxAttempts)
            assertFalse(p.thresholdRejected)
            assertFalse(p.maxAttemptsRejected)
        }
    }

    @Test
    fun `显式阈值被采用_IntelArchive 的 0-98`() {
        val p = ListCompleteSupport.parseParams("""{"threshold":0.98}""")
        assertEquals(0.98, p.threshold, 1e-9)
        assertFalse(p.thresholdRejected)
    }

    @Test
    fun `阈值 0 回落到默认_对齐上游`() {
        val p = ListCompleteSupport.parseParams("""{"threshold":0}""")
        assertEquals(0.9, p.threshold, 1e-9)
        assertFalse(p.thresholdRejected)
    }

    @Test
    fun `阈值负或大于 1 回落默认并标记`() {
        val negative = ListCompleteSupport.parseParams("""{"threshold":-0.5}""")
        assertEquals(0.9, negative.threshold, 1e-9)
        assertTrue(negative.thresholdRejected)

        val tooBig = ListCompleteSupport.parseParams("""{"threshold":1.5}""")
        assertEquals(0.9, tooBig.threshold, 1e-9)
        assertTrue(tooBig.thresholdRejected)
    }

    @Test
    fun `阈值 1 合法_边界可到 1`() {
        val p = ListCompleteSupport.parseParams("""{"threshold":1}""")
        assertEquals(1.0, p.threshold, 1e-9)
        assertFalse(p.thresholdRejected)
    }

    @Test
    fun `非数字阈值回落默认并标记`() {
        val p = ListCompleteSupport.parseParams("""{"threshold":"0.98"}""")
        assertEquals(0.9, p.threshold, 1e-9)
        assertTrue(p.thresholdRejected)
    }

    @Test
    fun `坏 JSON 回落默认不抛`() {
        for (raw in listOf("{not json", "[1,2,3]", "\"0.5\"")) {
            val p = ListCompleteSupport.parseParams(raw)
            assertEquals("raw=<$raw>", 0.9, p.threshold, 1e-9)
            assertEquals(ListCompleteSupport.DEFAULT_MAX_ATTEMPTS, p.maxAttempts)
        }
    }

    @Test
    fun `max_attempts 被采用`() {
        val p = ListCompleteSupport.parseParams("""{"threshold":0.98,"max_attempts":7}""")
        assertEquals(7, p.maxAttempts)
        assertFalse(p.maxAttemptsRejected)
    }

    @Test
    fun `max_attempts 非正回落默认并标记`() {
        val p = ListCompleteSupport.parseParams("""{"max_attempts":0}""")
        assertEquals(ListCompleteSupport.DEFAULT_MAX_ATTEMPTS, p.maxAttempts)
        assertTrue(p.maxAttemptsRejected)

        val negative = ListCompleteSupport.parseParams("""{"max_attempts":-3}""")
        assertEquals(ListCompleteSupport.DEFAULT_MAX_ATTEMPTS, negative.maxAttempts)
        assertTrue(negative.maxAttemptsRejected)
    }

    // ───────────────── templateName ─────────────────

    @Test
    fun `模板名对齐上游命名`() {
        assertEquals(
            "ListCompleteRecognition/IntelArchiveSyncFindingsScrollFinishPaper.png",
            ListCompleteSupport.templateName("IntelArchiveSyncFindingsScrollFinishPaper"),
        )
    }

    // ───────────────── normalizeRoi ─────────────────

    @Test
    fun `ROI 缺失或宽高非正时退化为全屏`() {
        assertEquals(listOf(0, 0, 1280, 720), ListCompleteSupport.normalizeRoi(null, 1280, 720)!!.toList())
        assertEquals(
            listOf(0, 0, 1280, 720),
            ListCompleteSupport.normalizeRoi(intArrayOf(10, 20, 0, 40), 1280, 720)!!.toList(),
        )
    }

    @Test
    fun `合法 ROI 原样返回`() {
        assertEquals(
            listOf(280, 100, 960, 560),
            ListCompleteSupport.normalizeRoi(intArrayOf(280, 100, 960, 560), 1280, 720)!!.toList(),
        )
    }

    @Test
    fun `越界 ROI 按图像边界裁剪`() {
        // x+w=1500 越界到 1280；y+h=800 越界到 720
        assertEquals(
            listOf(200, 100, 1080, 620),
            ListCompleteSupport.normalizeRoi(intArrayOf(200, 100, 1300, 700), 1280, 720)!!.toList(),
        )
        // 负起点裁到 0
        assertEquals(
            listOf(0, 0, 100, 100),
            ListCompleteSupport.normalizeRoi(intArrayOf(-50, -50, 150, 150), 1280, 720)!!.toList(),
        )
    }

    @Test
    fun `完全落在画面外的 ROI 返回 null`() {
        assertNull(ListCompleteSupport.normalizeRoi(intArrayOf(2000, 2000, 100, 100), 1280, 720))
    }

    @Test
    fun `图像尺寸非正返回 null`() {
        assertNull(ListCompleteSupport.normalizeRoi(intArrayOf(0, 0, 10, 10), 0, 720))
        assertNull(ListCompleteSupport.normalizeRoi(intArrayOf(0, 0, 10, 10), 1280, -1))
    }

    // ───────────────── attach 读取 ─────────────────

    @Test
    fun `attach-ready 为真才算就绪`() {
        assertTrue(ListCompleteSupport.isReady("""{"attach":{"ready":true}}"""))
        assertFalse(ListCompleteSupport.isReady("""{"attach":{"ready":false}}"""))
    }

    @Test
    fun `缺 attach 或 ready 视为未就绪`() {
        assertFalse(ListCompleteSupport.isReady("""{"recognition":"Custom"}"""))
        assertFalse(ListCompleteSupport.isReady("""{"attach":{}}"""))
        assertFalse(ListCompleteSupport.isReady(null))
    }

    @Test
    fun `ready 类型不符视为未就绪不抛`() {
        assertFalse(ListCompleteSupport.isReady("""{"attach":{"ready":"true"}}"""))
        assertFalse(ListCompleteSupport.isReady("""{"attach":{"ready":1}}"""))
        assertFalse(ListCompleteSupport.isReady("""{"attach":{"ready":null}}"""))
    }

    @Test
    fun `坏 JSON 视为未就绪`() {
        assertFalse(ListCompleteSupport.isReady("{not json"))
    }

    @Test
    fun `attachMap 保留其它键`() {
        val map = ListCompleteSupport.attachMap("""{"attach":{"ready":false,"foo":"bar","n":3}}""")
        assertEquals(false, map["ready"])
        assertEquals("bar", map["foo"])
        assertEquals(3L, map["n"])
    }

    // ───────────────── buildReadyOverride ─────────────────

    @Test
    fun `写 ready 时合并保留其它 attach 键`() {
        val json = ListCompleteSupport.buildReadyOverride(
            "NodeA",
            """{"attach":{"ready":false,"foo":"bar"}}""",
            true,
        )
        val parsed = MaaJsonTree.parse(json) as Map<*, *>
        val node = parsed["NodeA"] as Map<*, *>
        val attach = node["attach"] as Map<*, *>
        assertEquals(true, attach["ready"])
        assertEquals("bar", attach["foo"])
    }

    @Test
    fun `没有 attach 时也能写入 ready`() {
        val json = ListCompleteSupport.buildReadyOverride("NodeA", """{"recognition":"Custom"}""", true)
        val parsed = MaaJsonTree.parse(json) as Map<*, *>
        val attach = (parsed["NodeA"] as Map<*, *>)["attach"] as Map<*, *>
        assertEquals(true, attach["ready"])
    }

    @Test
    fun `ready 显式为 false 也会写出不给继承留余地`() {
        val json = ListCompleteSupport.buildReadyOverride("NodeA", """{"attach":{"ready":true}}""", false)
        val parsed = MaaJsonTree.parse(json) as Map<*, *>
        val attach = (parsed["NodeA"] as Map<*, *>)["attach"] as Map<*, *>
        assertEquals(false, attach["ready"])
    }

    // ───────────────── buildTemplateMatchOverride ─────────────────

    @Test
    fun `TemplateMatch 覆盖写出全部可变键`() {
        val json = ListCompleteSupport.buildTemplateMatchOverride(
            nodeName = "__ListCompleteTemplateMatch",
            templateName = "ListCompleteRecognition/NodeA.png",
            threshold = 0.98,
            roi = intArrayOf(280, 100, 960, 560),
        )
        val parsed = MaaJsonTree.parse(json) as Map<*, *>
        val node = parsed["__ListCompleteTemplateMatch"] as Map<*, *>
        val recognition = node["recognition"] as Map<*, *>
        assertEquals("TemplateMatch", recognition["type"])
        val param = recognition["param"] as Map<*, *>
        assertEquals(listOf("ListCompleteRecognition/NodeA.png"), param["template"])
        assertEquals(0.98, (param["threshold"] as Number).toDouble(), 1e-9)
        assertEquals(ListCompleteSupport.TEMPLATE_MATCH_METHOD, (param["method"] as Number).toInt())
        assertEquals(ListCompleteSupport.GREEN_MASK, param["green_mask"])
        assertEquals(listOf(280, 100, 960, 560), ints(param["roi"]))
        assertEquals("DoNothing", (node["action"] as Map<*, *>)["type"])
    }

    @Test
    fun `TemplateMatch 覆盖在 roi 缺失时显式写全屏而非省略`() {
        val json = ListCompleteSupport.buildTemplateMatchOverride(
            nodeName = "__ListCompleteTemplateMatch",
            templateName = "t.png",
            threshold = 0.9,
            roi = null,
        )
        val parsed = MaaJsonTree.parse(json) as Map<*, *>
        val param = ((parsed["__ListCompleteTemplateMatch"] as Map<*, *>)["recognition"] as Map<*, *>)["param"] as Map<*, *>
        assertEquals(listOf(0, 0, 0, 0), ints(param["roi"]))
        assertTrue("必须显式含 roi", param.containsKey("roi"))
    }

    // ───────────────── bestTemplateScore ─────────────────

    @Test
    fun `解析 best-score`() {
        val json = """{"all":[{"box":[1,2,3,4],"score":0.5}],"filtered":[],"best":{"box":[1,2,3,4],"score":0.9123}}"""
        assertEquals(0.9123, ListCompleteSupport.bestTemplateScore(json)!!, 1e-9)
    }

    @Test
    fun `best 缺失或类型不符返回 null`() {
        assertNull(ListCompleteSupport.bestTemplateScore(null))
        assertNull(ListCompleteSupport.bestTemplateScore("{}"))
        assertNull(ListCompleteSupport.bestTemplateScore("""{"best":null}"""))
        assertNull(ListCompleteSupport.bestTemplateScore("""{"best":{"score":"高"}}"""))
        assertNull(ListCompleteSupport.bestTemplateScore("{not json"))
    }

    // ───────────────── Session 状态机 ─────────────────

    @Test
    fun `首轮必不 complete 且要求截模板`() {
        val session = ListCompleteSupport.Session()
        val action = decide(session, "NodeA", ready = false, score = null)
        assertEquals(ListCompleteSupport.Action.CAPTURE_TEMPLATE, action)
        assertNotEquals(ListCompleteSupport.Action.COMPLETE, action)
    }

    @Test
    fun `首轮即使已有分数也不能判 complete`() {
        // ready=false 意味着模板还没建，分数无从谈起；必须以建模板为准
        val session = ListCompleteSupport.Session()
        val action = decide(session, "NodeA", ready = false, score = 0.999)
        assertEquals(ListCompleteSupport.Action.CAPTURE_TEMPLATE, action)
    }

    @Test
    fun `分数不低于阈值判 complete`() {
        val session = ListCompleteSupport.Session()
        decide(session, "NodeA", ready = false, score = null)
        assertEquals(
            ListCompleteSupport.Action.COMPLETE,
            decide(session, "NodeA", ready = true, score = 0.99, threshold = 0.98),
        )
    }

    @Test
    fun `分数恰好等于阈值判 complete`() {
        val session = ListCompleteSupport.Session()
        decide(session, "NodeA", ready = false, score = null)
        assertEquals(
            ListCompleteSupport.Action.COMPLETE,
            decide(session, "NodeA", ready = true, score = 0.98, threshold = 0.98),
        )
    }

    @Test
    fun `分数低于阈值不 complete 且要求重截模板`() {
        val session = ListCompleteSupport.Session()
        decide(session, "NodeA", ready = false, score = null)
        assertEquals(
            ListCompleteSupport.Action.RECAPTURE_TEMPLATE,
            decide(session, "NodeA", ready = true, score = 0.5, threshold = 0.98),
        )
    }

    @Test
    fun `分数取不到时重截模板而非误判 complete`() {
        val session = ListCompleteSupport.Session()
        decide(session, "NodeA", ready = false, score = null)
        assertEquals(
            ListCompleteSupport.Action.RECAPTURE_TEMPLATE,
            decide(session, "NodeA", ready = true, score = null),
        )
    }

    @Test
    fun `同一 node 多次调用滚动到底的状态机`() {
        val session = ListCompleteSupport.Session()
        // 首轮建模板
        assertEquals(ListCompleteSupport.Action.CAPTURE_TEMPLATE, decide(session, "N", false, null))
        // 画面在动：逐轮重截
        assertEquals(ListCompleteSupport.Action.RECAPTURE_TEMPLATE, decide(session, "N", true, 0.1))
        assertEquals(ListCompleteSupport.Action.RECAPTURE_TEMPLATE, decide(session, "N", true, 0.4))
        assertEquals(2, session.attemptsFor("N"))
        // 画面不再变化：到底
        assertEquals(ListCompleteSupport.Action.COMPLETE, decide(session, "N", true, 0.97, threshold = 0.9))
        // 完成后计数清零
        assertEquals(0, session.attemptsFor("N"))
    }

    @Test
    fun `不同 node 计数互不串扰`() {
        val session = ListCompleteSupport.Session()
        decide(session, "A", false, null)
        decide(session, "A", true, 0.1)
        decide(session, "A", true, 0.1)
        decide(session, "B", false, null)

        assertEquals(2, session.attemptsFor("A"))
        assertEquals(0, session.attemptsFor("B"))
        // B 完成不影响 A
        assertEquals(ListCompleteSupport.Action.RECAPTURE_TEMPLATE, decide(session, "A", true, 0.1))
        assertEquals(ListCompleteSupport.Action.COMPLETE, decide(session, "B", true, 0.99))
    }

    @Test
    fun `安全阀达到上限强制 complete 防死循环`() {
        val session = ListCompleteSupport.Session()
        decide(session, "N", false, null)
        var action = ListCompleteSupport.Action.RECAPTURE_TEMPLATE
        var count = 0
        while (action == ListCompleteSupport.Action.RECAPTURE_TEMPLATE) {
            action = decide(session, "N", true, score = 0.1, threshold = 0.9, maxAttempts = 5)
            count++
            if (count > 100) break
        }
        assertEquals(ListCompleteSupport.Action.COMPLETE, action)
        // 上限是 5：第 5 次 ready 判定触发兜底
        assertEquals(5, count)
        assertEquals(0, session.attemptsFor("N"))
    }

    @Test
    fun `安全阀不影响画面一致时的正常完成`() {
        val session = ListCompleteSupport.Session()
        decide(session, "N", false, null)
        decide(session, "N", true, 0.1, maxAttempts = 5)
        assertEquals(
            ListCompleteSupport.Action.COMPLETE,
            decide(session, "N", true, 0.99, maxAttempts = 5),
        )
    }

    @Test
    fun `reset 清空全部计数`() {
        val session = ListCompleteSupport.Session()
        decide(session, "A", false, null)
        decide(session, "A", true, 0.1)
        decide(session, "B", false, null)
        decide(session, "B", true, 0.1)
        assertEquals(1, session.attemptsFor("A"))
        assertEquals(1, session.attemptsFor("B"))
        session.reset()
        assertEquals(0, session.attemptsFor("A"))
        assertEquals(0, session.attemptsFor("B"))
    }

    @Test
    fun `reset 指定 node 只清该节点`() {
        val session = ListCompleteSupport.Session()
        decide(session, "A", false, null)
        decide(session, "A", true, 0.1)
        decide(session, "B", false, null)
        decide(session, "B", true, 0.1)
        session.reset("A")
        assertEquals(0, session.attemptsFor("A"))
        assertEquals(1, session.attemptsFor("B"))
    }

    @Test
    fun `重建模板的首轮计数清零`() {
        // ready 被外部重置为 false（新任务/新模板），计数应清零重算而非续上旧的
        val session = ListCompleteSupport.Session()
        decide(session, "N", true, 0.1)
        decide(session, "N", true, 0.1)
        assertEquals(2, session.attemptsFor("N"))
        decide(session, "N", ready = false, score = null)
        assertEquals(0, session.attemptsFor("N"))
    }
}
