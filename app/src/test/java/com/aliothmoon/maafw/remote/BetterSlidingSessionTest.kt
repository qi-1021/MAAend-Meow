package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BetterSliding 编排层测试（对齐上游 handlers.go 的 handler 骨架）。
 *
 * 用假 host 把「调了哪些 override、路由到哪个节点」记下来断言。
 * 这类错误在真机上只表现为数量不对，日志里几乎看不出来，所以必须在这里锁。
 */
class BetterSlidingSessionTest {

    private class FakeHost : BetterSlidingHost {
        val overrides = mutableListOf<String>()
        val subTasks = mutableListOf<Pair<String, String>>()
        val callerNodes = mutableMapOf<String, String>()
        val details = mutableMapOf<Long, String>()
        var pipelineOk = true
        var subTaskOk = true
        var satisfyOk = true
        val logs = mutableListOf<String>()

        /** 记录手工 satisfy 的调用（真机应复用与结果节点同一份逻辑）。 */
        val satisfiedLabels = mutableListOf<String>()

        /** 模拟内部子流水线：真机 runSubTask 会重入 session 逐节点推进，测试里用钩子替代。 */
        var onRunSubTask: ((String, String) -> Unit)? = null

        override fun overridePipeline(overrideJson: String): Boolean {
            if (!pipelineOk) return false
            overrides += overrideJson
            return true
        }

        override fun runSubTask(nodeName: String, overrideJson: String): Boolean {
            subTasks += nodeName to overrideJson
            onRunSubTask?.invoke(nodeName, overrideJson)
            return subTaskOk
        }

        override fun callerNodeJson(nodeName: String): String? = callerNodes[nodeName]

        override fun recognitionDetailJson(recoId: Long): String? = details[recoId]

        override fun parseJson(text: String): Any? = MaaJsonTree.parse(text)

        override fun satisfyReserveOutcome(): Boolean {
            satisfiedLabels += "BetterSliding outcome override"
            return satisfyOk
        }

        override fun info(message: String) {
            logs += "I:$message"
        }

        override fun warn(message: String) {
            logs += "W:$message"
        }

        fun joined(): String = overrides.joinToString("\n")
    }

    private fun session(host: FakeHost) = BetterSlidingSession(host)

    /** 手柄框（模板识别命中的滑条手柄） */
    private fun boxDetail(x: Int, y: Int, w: Int = 20, h: Int = 20) =
        """{"name":"BetterSlidingSwipeButton","box":[$x,$y,$w,$h]}"""

    private fun textDetail(text: String) = """{"name":"BetterSlidingGetSliderQuantity","text":"$text"}"""

    private fun hostWith(details: Map<Long, String>) = FakeHost().also { it.details.putAll(details) }

    // ───────────── 外部调用 → 内部子流水线 ─────────────

    @Test
    fun `外部调用会覆盖七个驱动节点并运行内部子流水线`() {
        val host = FakeHost()
        val ok = session(host).run("AutoStockpileSwipeMax", """{"Direction":"right"}""", 0)

        assertTrue(ok)
        assertEquals(1, host.subTasks.size)
        assertEquals(BetterSlidingSupport.NODE_MAIN, host.subTasks[0].first)
        // 七个驱动节点每个都拿到同一份 custom_action_param
        for (node in BetterSlidingDecision.ACTION_NODES) {
            assertTrue("缺少节点 $node", host.joined().contains("\"$node\""))
        }
        assertTrue(host.joined().contains("custom_action_param"))
    }

    @Test
    fun `attach 里的 TargetQuantity 会并进参数`() {
        val host = FakeHost()
        host.callerNodes["SomeCaller"] = """{"attach":{"TargetQuantity":7}}"""
        val ok = session(host).run("SomeCaller", """{"Direction":"right"}""", 0)

        assertTrue(ok)
        // 合并后参数里应出现 TargetQuantity:7，并透传到子流水线覆盖里
        assertTrue(host.joined().contains("\"TargetQuantity\":7"))
    }

    // ───────────── handleMain ─────────────

    @Test
    fun `只滑动模式清空 SwipeToMax 的 next 并启用复位节点`() {
        val host = FakeHost()
        val ok = session(host).run(BetterSlidingSupport.NODE_MAIN, """{"Direction":"right"}""", 0)

        assertTrue(ok)
        // SwipeToMax 一次性跑完
        assertTrue(host.joined().contains("""{"BetterSlidingSwipeToMax":{"next":[]}}"""))
        // 复位流程被接进来（resetBeforeFindStart 默认 false，但节点仍被覆盖）
        assertTrue(host.joined().contains("BetterSlidingFindSwipeForReset"))
        assertTrue(host.joined().contains("""{"enabled":false}"""))
    }

    @Test
    fun `目标即最小值时短路到 Done`() {
        val host = hostWith(mapOf(1L to boxDetail(100, 500)))
        // TargetQuantity=1 + Value + 非反向 -> 短路
        val param = """{"TargetQuantity":1,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""
        val ok = session(host).run(BetterSlidingSupport.NODE_MAIN, param, 0)

        assertTrue(ok)
        // 短路分支：从 ClearMaxHit 直接收到 Done（resetBeforeFindStart=false）
        assertTrue(
            "期望短路路由到 Done，实际：\n${host.joined()}",
            host.joined().contains("""{"BetterSlidingClearMaxHit":{"next":["BetterSlidingDone"]}}"""),
        )
    }

    @Test
    fun `定量模式缺少滑条框会失败`() {
        val host = FakeHost()
        // 有 TargetQuantity 但没有 SliderQuantity.Box -> 定量模式校验不过
        val ok = session(host).run(BetterSlidingSupport.NODE_MAIN, """{"TargetQuantity":5}""", 0)
        assertEquals(false, ok)
    }

    // ───────────── handleFindEnd ─────────────

    @Test
    fun `handleFindEnd 写入精确点击目标并把 next 接到 JumpBackNode`() {
        val host = hostWith(mapOf(1L to boxDetail(600, 500)))
        val s = session(host)
        // 先让 handleMain 建好参数与状态
        val param = """{"TargetQuantity":5,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""
        s.run(BetterSlidingSupport.NODE_MAIN, param, 0)
        // FindStart 记下起点
        host.details[2L] = boxDetail(100, 500)
        assertTrue(s.run(BetterSlidingSupport.NODE_FIND_START, param, 2))
        // GetSliderMaxQuantity 读到上限 10
        host.details[3L] = textDetail("10")
        assertTrue(s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 3))

        host.overrides.clear()
        host.details[4L] = boxDetail(600, 500)
        assertTrue(s.run(BetterSlidingSupport.NODE_FIND_END, param, 4))

        // 精确点击目标 + next 接回 JumpBackNode
        assertTrue(host.joined().contains("\"BetterSlidingPreciseClick\""))
        assertTrue(
            host.joined().contains("""{"BetterSlidingPreciseClick":{"next":["BetterSlidingJumpBackNode"]}}"""),
        )
        // 5/10 未超过 80%
        assertEquals(false, host.joined().contains("BetterSlidingFindSwipeForReset"))
    }

    @Test
    fun `handleFindEnd 目标超过八成时先复位再精确点击`() {
        val host = hostWith(mapOf(1L to boxDetail(600, 500)))
        val s = session(host)
        val param = """{"TargetQuantity":9,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""
        s.run(BetterSlidingSupport.NODE_MAIN, param, 0)
        host.details[2L] = boxDetail(100, 500)
        s.run(BetterSlidingSupport.NODE_FIND_START, param, 2)
        host.details[3L] = textDetail("10")
        s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 3)

        host.overrides.clear()
        host.details[4L] = boxDetail(600, 500)
        assertTrue(s.run(BetterSlidingSupport.NODE_FIND_END, param, 4))

        // 9/10 > 80% -> 开启复位闸门、复位后回到精确点击、FindEnd 先去复位
        assertTrue(host.joined().contains("""{"BetterSlidingFindSwipeForReset":{"enabled":true}}"""))
        assertTrue(host.joined().contains("""{"BetterSlidingReset":{"next":["BetterSlidingPreciseClick"]}}"""))
        assertTrue(
            host.joined().contains("""{"BetterSlidingFindEnd":{"next":["BetterSlidingFindSwipeForReset"]}}"""),
        )
    }

    // ───────────── handleCheckQuantity ─────────────

    @Test
    fun `复查低于目标时走增加并带重复次数`() {
        val host = hostWith(mapOf(1L to boxDetail(600, 500)))
        val s = session(host)
        val param = """{"TargetQuantity":8,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""
        s.run(BetterSlidingSupport.NODE_MAIN, param, 0)
        host.details[2L] = boxDetail(100, 500)
        s.run(BetterSlidingSupport.NODE_FIND_START, param, 2)
        host.details[3L] = textDetail("10")
        s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 3)

        host.overrides.clear()
        host.details[9L] = textDetail("5")
        assertTrue(s.run(BetterSlidingSupport.NODE_CHECK_QUANTITY, param, 9))

        // 5 < 8 -> Increase，repeat = 3
        assertTrue(
            "期望路由到增加数量，实际：\n${host.joined()}",
            host.joined().contains("""{"BetterSlidingCheckQuantity":{"next":["BetterSlidingIncreaseQuantity"]}}"""),
        )
        assertTrue(host.joined().contains("\"repeat\":3"))
    }

    @Test
    fun `复查相等时收尾`() {
        val host = hostWith(mapOf(1L to boxDetail(600, 500)))
        val s = session(host)
        val param = """{"TargetQuantity":8,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""
        s.run(BetterSlidingSupport.NODE_MAIN, param, 0)
        host.details[2L] = boxDetail(100, 500)
        s.run(BetterSlidingSupport.NODE_FIND_START, param, 2)
        host.details[3L] = textDetail("10")
        s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 3)

        host.overrides.clear()
        host.details[10L] = textDetail("8")
        assertTrue(s.run(BetterSlidingSupport.NODE_CHECK_QUANTITY, param, 10))

        assertTrue(
            host.joined().contains("""{"BetterSlidingCheckQuantity":{"next":["BetterSlidingDone"]}}"""),
        )
    }

    // ───────────── 越界与结果节点 ─────────────

    @Test
    fun `目标越界且配了结果节点时跳过调整并交给调用方`() {
        val host = hostWith(mapOf(1L to boxDetail(100, 500)))
        val s = session(host)
        val param = """
            {"TargetQuantity":50,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]},
             "OutOfRangeOverrideEnable":"OutpostTradingCanNotBuyNode"}
        """.trimIndent()
        s.run(BetterSlidingSupport.NODE_MAIN, param, 0)

        host.overrides.clear()
        host.details[5L] = textDetail("10")
        assertTrue(s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 5))

        // 50 > 10 且未开钳制 -> 越界：路由到 Done 收尾
        assertTrue(
            host.joined().contains("""{"BetterSlidingGetSliderMaxQuantity":{"next":["BetterSlidingDone"]}}"""),
        )
    }

    @Test
    fun `越界但没配结果节点时失败`() {
        val host = hostWith(mapOf(1L to boxDetail(100, 500)))
        val s = session(host)
        val param = """{"TargetQuantity":50,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""
        s.run(BetterSlidingSupport.NODE_MAIN, param, 0)
        host.details[6L] = textDetail("10")
        assertEquals(false, s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 6))
    }

    @Test
    fun `只滑动模式没有结果节点时不产生任何 enable 覆盖`() {
        val host = FakeHost()
        val ok = session(host).run("SomeCaller", """{"Direction":"right"}""", 0)

        assertTrue(ok)
        assertEquals(1, host.subTasks.size)
        // 只滑动模式没配 OutOfRange/TargetReachable -> NONE，不发 enabled
        assertEquals(false, host.joined().contains("\"enabled\""))
    }

    /**
     * 真机回归：框架在 `MaaContextOverridePipeline` 上对结果节点做 enable 覆盖会偶发 SIGSEGV。
     * out-of-range 改为「手工 satisfy + 把调用方 next 指到 SellLoop」，且**不得**再引用
     * 那两个结果节点的 enabled。
     */
    @Test
    fun `外部调用越界时手工 satisfy 并路由到 SellLoop 且不下发 enabled`() {
        val host = FakeHost()
        host.details[5L] = textDetail("10")
        val s = session(host)
        val caller = "OutpostTradingInfraStationBetterSliding"
        val param =
            """{"TargetQuantity":50,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]},""" +
                """"OutOfRangeOverrideEnable":"OutpostTradingReserveAlreadySatisfied",""" +
                """"TargetReachableOverrideEnable":"OutpostTradingReserveQuantityReached"}"""
        host.onRunSubTask = { _, _ -> s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 5) }

        assertTrue(s.run(caller, param, 0))

        // ① 等价于结果节点的 action：手工 satisfy
        assertEquals(listOf("BetterSliding outcome override"), host.satisfiedLabels)
        // ② 等价于结果节点的 next：调用方直接路由到 SellLoop
        assertTrue(
            "调用方应被路由到 SellLoop，实际：\n${host.joined()}",
            host.joined().contains("""{"$caller":{"next":["OutpostTradingSellLoop"]}}"""),
        )
        // ③ 两个结果节点上不得出现任何 enabled 覆盖（节点名仅可能作为参数值出现）
        assertEquals(false, host.joined().contains("\"OutpostTradingReserveAlreadySatisfied\":{\"enabled\""))
        assertEquals(false, host.joined().contains("\"OutpostTradingReserveQuantityReached\":{\"enabled\""))
    }

    /**
     * targetReachable：放弃交易后记账，**不动调用方 next**（改了会跳过卖出），仅打取舍日志。
     */
    @Test
    fun `外部调用目标可达时跳过结果节点且不改调用方 next`() {
        val host = FakeHost()
        host.details[6L] = textDetail("10")
        val s = session(host)
        val caller = "OutpostTradingInfraStationBetterSliding"
        val param =
            """{"TargetQuantity":5,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]},""" +
                """"OutOfRangeOverrideEnable":"OutpostTradingReserveAlreadySatisfied",""" +
                """"TargetReachableOverrideEnable":"OutpostTradingReserveQuantityReached"}"""
        host.onRunSubTask = { _, _ -> s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 6) }

        assertTrue(s.run(caller, param, 0))

        // 不 satisfy、不动调用方 next、不发 enabled
        assertEquals(emptyList<String>(), host.satisfiedLabels)
        assertEquals(false, host.joined().contains("""{"$caller":{"next":"""))
        assertEquals(false, host.joined().contains("\"OutpostTradingReserveAlreadySatisfied\":{\"enabled\""))
        assertEquals(false, host.joined().contains("\"OutpostTradingReserveQuantityReached\":{\"enabled\""))
        // 必须打一条说明取舍与后果的日志
        assertTrue(
            "缺少 targetReachable 取舍日志，实际日志：\n${host.logs.joinToString("\n")}",
            host.logs.any {
                it.startsWith("W:") &&
                    it.contains("OutpostTradingReserveQuantityReached") &&
                    it.contains("跳过")
            },
        )
    }

    @Test
    fun `外部调用越界但 satisfy 前置状态不齐备时判失败`() {
        val host = FakeHost()
        host.details[5L] = textDetail("10")
        host.satisfyOk = false
        val s = session(host)
        val param =
            """{"TargetQuantity":50,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]},""" +
                """"OutOfRangeOverrideEnable":"OutpostTradingReserveAlreadySatisfied"}"""
        host.onRunSubTask = { _, _ -> s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 5) }

        assertEquals(false, s.run("SomeCaller", param, 0))
    }

    @Test
    fun `host 应用覆盖失败时整体判失败`() {
        val host = FakeHost()
        host.pipelineOk = false
        assertEquals(false, session(host).run(BetterSlidingSupport.NODE_MAIN, """{"Direction":"right"}""", 0))
    }

    // ───────────── 回调 box（真机 bug 回归） ─────────────
    // 真机：BetterSlidingFindStart 是 And 节点，detail_json 没有顶层 box，
    // 旧路径永远读不到 → “读不到滑条起点框”。起点/终点必须用回调参数。

    private val targetParam =
        """{"TargetQuantity":5,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""

    /**
     * 起点(100,500) 终点(600,500)，偏移默认 (-10,0)，target=5/max=10：
     * startCenterX=100, endCenterX=600, dx=500, clickX=100+round(500*4/9)=322, clickY=510。
     */
    @Test
    fun `起点终点改用回调 box，detail_json 为空也能算精确点击`() {
        val host = FakeHost() // details 全空：旧路径在这里必然失败
        val s = session(host)
        assertTrue(s.run(BetterSlidingSupport.NODE_MAIN, targetParam, 0))

        assertTrue(
            "回调 box 必须能记录起点",
            s.run(BetterSlidingSupport.NODE_FIND_START, targetParam, 0, listOf(100, 500, 20, 20)),
        )
        host.details[3L] = textDetail("10")
        assertTrue(s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, targetParam, 3))

        host.overrides.clear()
        assertTrue(s.run(BetterSlidingSupport.NODE_FIND_END, targetParam, 0, listOf(600, 500, 20, 20)))
        assertTrue(
            "期望用回调 box 算出 (322,510)，实际：\n${host.joined()}",
            host.joined().contains("\"target\":[322,510]"),
        )
    }

    @Test
    fun `回调 box 优先于 detail_json`() {
        val host = FakeHost()
        val s = session(host)
        assertTrue(s.run(BetterSlidingSupport.NODE_MAIN, targetParam, 0))

        // detail_json 里的起点框是 (200,500)，若被误用会算出 clickX=378；
        // 回调 box 是 (100,500)，应算出 322。
        host.details[2L] = boxDetail(200, 500)
        assertTrue(s.run(BetterSlidingSupport.NODE_FIND_START, targetParam, 2, listOf(100, 500, 20, 20)))
        host.details[3L] = textDetail("10")
        assertTrue(s.run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, targetParam, 3))

        host.overrides.clear()
        // 终点 detail 框是 (999,500)（若被误用会算出 clickX=500），回调框才是 (600,500)。
        host.details[4L] = boxDetail(999, 500)
        assertTrue(s.run(BetterSlidingSupport.NODE_FIND_END, targetParam, 4, listOf(600, 500, 20, 20)))

        val joined = host.joined()
        assertTrue("应使用回调起点框，实际：\n$joined", joined.contains("\"target\":[322,510]"))
        assertEquals(false, joined.contains("\"target\":[378,510]"))
        assertEquals(false, joined.contains("\"target\":[500,510]"))
    }

    // ───────────── 回调 recoId 的 And 组合 detail（真机 bug 回归） ─────────────
    // 真机：BetterSlidingGetSliderMaxQuantity 是 And，回调 recoId 查到的 detail 根是数组，
    // 旧解析器直接返回 null → 「读不到滑条上限」，而框架日志里 OCR 已识别出 7299。

    private fun andQuantityDetailJson(text: String): String {
        val ocr = """{"box":[1065,499,78,36],"score":0.99,"text":"$text"}"""
        return """[{"algorithm":"OCR","box":[1065,499,78,36],""" +
            """"detail":{"all":[$ocr],"best":$ocr,"filtered":[]},""" +
            """"name":"BetterSlidingGetSliderQuantity","reco_id":42}]"""
    }

    @Test
    fun `And 组合结果数组作 detail 时能读到滑条上限并收尾`() {
        val host = FakeHost()
        host.details[7L] = andQuantityDetailJson("7299")
        val param = """{"TargetQuantity":7299,"Direction":"right","SliderQuantity":{"Box":[300,500,100,40]}}"""

        assertTrue(session(host).run(BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY, param, 7))
        // max=7299 == target=7299 -> 直接收尾；读不到的话会走 warn 并返回 false
        assertTrue(
            "期望读到上限 7299 并短路到 Done，实际：\n${host.joined()}",
            host.joined().contains("""{"BetterSlidingGetSliderMaxQuantity":{"next":["BetterSlidingDone"]}}"""),
        )
    }
}
