package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.BetterSlidingDecision.NudgeAxis
import com.aliothmoon.maafw.remote.BetterSlidingDecision.Reset2Side
import com.aliothmoon.maafw.remote.BetterSlidingDecision.SliderQuantityOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BetterSliding 状态机判定逻辑测试（对齐上游 bettersliding/handlers.go）。
 *
 * 这一层是整套滑条状态机里最容易悄悄写错的部分：偏移轴怎么选、复位往哪一侧、
 * 1px 累加怎么叠、目标与滑条上限谁被钳制。真机上错了只会表现为「数量不准」，
 * 很难从日志看出来，所以在这里按上游语义逐条钉死。
 */
class BetterSlidingDecisionTest {

    private val offset = listOf(-10, 0)

    /** 水平滑条：Start 中心 (110,510)，End 中心 (610,510) */
    private val hStart = listOf(100, 500, 20, 20)
    private val hEnd = listOf(600, 500, 20, 20)

    /** offset=0 时中心就是 rect 中心，方便心算 */
    private val offset0 = listOf(0, 0)

    private fun rejects(block: () -> Any?) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `absInt 与 signInt`() {
        assertEquals(7, BetterSlidingDecision.absInt(-7))
        assertEquals(7, BetterSlidingDecision.absInt(7))
        assertEquals(-1, BetterSlidingDecision.signInt(-3))
        assertEquals(0, BetterSlidingDecision.signInt(0))
        assertEquals(1, BetterSlidingDecision.signInt(9))
    }

    @Test
    fun `shouldFineTuneQuantity 布尔模式看开关`() {
        val on = BetterSlidingSupport.FineTuneQuantity(enabled = true)
        val off = BetterSlidingSupport.FineTuneQuantity(enabled = false)
        assertEquals(true, BetterSlidingDecision.shouldFineTuneQuantity(on, 1, 999))
        assertEquals(false, BetterSlidingDecision.shouldFineTuneQuantity(off, 1, 999))
    }

    @Test
    fun `shouldFineTuneQuantity 阈值模式看差值绝对值`() {
        val q = BetterSlidingSupport.FineTuneQuantity(thresholdMode = true, threshold = 5)
        assertEquals(true, BetterSlidingDecision.shouldFineTuneQuantity(q, 95, 100))
        assertEquals(true, BetterSlidingDecision.shouldFineTuneQuantity(q, 105, 100))
        assertEquals(false, BetterSlidingDecision.shouldFineTuneQuantity(q, 94, 100))
    }

    @Test
    fun `resolveStepSign 只在方向条件成立时才偏移`() {
        assertEquals(1, BetterSlidingDecision.resolveStepSign("more", 10, 20))
        assertEquals(0, BetterSlidingDecision.resolveStepSign("more", 20, 20))
        assertEquals(0, BetterSlidingDecision.resolveStepSign("more", 30, 20))
        assertEquals(-1, BetterSlidingDecision.resolveStepSign("less", 30, 20))
        assertEquals(0, BetterSlidingDecision.resolveStepSign("less", 20, 20))
        assertEquals(0, BetterSlidingDecision.resolveStepSign("less", 10, 20))
        assertEquals(0, BetterSlidingDecision.resolveStepSign("none", 10, 20))
    }

    @Test
    fun `resolveNudgeAxis 按主位移方向定轴`() {
        assertEquals(NudgeAxis.X to 1, BetterSlidingDecision.resolveNudgeAxis(hStart, hEnd, offset))
        assertEquals(NudgeAxis.X to -1, BetterSlidingDecision.resolveNudgeAxis(hEnd, hStart, offset))
        assertEquals(
            NudgeAxis.Y to 1,
            BetterSlidingDecision.resolveNudgeAxis(listOf(500, 100, 20, 20), listOf(500, 600, 20, 20), offset),
        )
        assertEquals(
            NudgeAxis.Y to -1,
            BetterSlidingDecision.resolveNudgeAxis(listOf(500, 600, 20, 20), listOf(500, 100, 20, 20), offset),
        )
    }

    @Test
    fun `resolveNudgeAxis 平局与重合都退到 y 轴正方向`() {
        assertEquals(NudgeAxis.Y to 1, BetterSlidingDecision.resolveNudgeAxis(listOf(0, 0, 20, 20), listOf(50, 50, 20, 20), offset))
        assertEquals(NudgeAxis.Y to 1, BetterSlidingDecision.resolveNudgeAxis(hStart, hStart, offset))
        assertEquals(NudgeAxis.Y to 1, BetterSlidingDecision.resolveNudgeAxis(null, null, offset))
    }

    @Test
    fun `resolveReset2Side 靠近 Start 时向 End 侧复位`() {
        assertEquals(
            Reset2Side.TOWARD_END,
            BetterSlidingDecision.resolveReset2Side(NudgeAxis.X, hStart, hEnd, offset, 210 to 510),
        )
        assertEquals(
            Reset2Side.TOWARD_START,
            BetterSlidingDecision.resolveReset2Side(NudgeAxis.X, hStart, hEnd, offset, 610 to 510),
        )
    }

    @Test
    fun `resolveReset2Side 轴跨度为 0 时退到 Start 侧`() {
        assertEquals(
            Reset2Side.TOWARD_START,
            BetterSlidingDecision.resolveReset2Side(NudgeAxis.X, hStart, hStart, offset, 510 to 510),
        )
    }

    @Test
    fun `resolveReset2Side 反向滑条按 span 符号翻转判断`() {
        // start 在右、end 在左，base 靠近 end(左) -> 向 Start 复位
        assertEquals(
            Reset2Side.TOWARD_START,
            BetterSlidingDecision.resolveReset2Side(NudgeAxis.X, hEnd, hStart, offset, 210 to 510),
        )
        // base 靠近 start(右) -> 向 End 复位
        assertEquals(
            Reset2Side.TOWARD_END,
            BetterSlidingDecision.resolveReset2Side(NudgeAxis.X, hEnd, hStart, offset, 610 to 510),
        )
    }

    @Test
    fun `resolveReset2Side 在 y 轴上改用 y 分量`() {
        assertEquals(
            Reset2Side.TOWARD_END,
            BetterSlidingDecision.resolveReset2Side(
                NudgeAxis.Y,
                listOf(500, 100, 20, 20),
                listOf(500, 600, 20, 20),
                offset,
                500 to 210,
            ),
        )
    }

    @Test
    fun `nudgedClickTarget 沿轴做 1px 级累加偏移`() {
        assertEquals(103 to 200, BetterSlidingDecision.nudgedClickTarget(100 to 200, NudgeAxis.X, 1, 1, 3))
        assertEquals(100 to 200, BetterSlidingDecision.nudgedClickTarget(100 to 200, NudgeAxis.X, 1, 1, 0))
        assertEquals(97 to 200, BetterSlidingDecision.nudgedClickTarget(100 to 200, NudgeAxis.X, 1, -1, 3))
        assertEquals(97 to 200, BetterSlidingDecision.nudgedClickTarget(100 to 200, NudgeAxis.X, -1, 1, 3))
        assertEquals(100 to 203, BetterSlidingDecision.nudgedClickTarget(100 to 200, NudgeAxis.Y, 1, 1, 3))
    }

    @Test
    fun `resolveSliderQuantityOutcome 目标在范围内`() {
        assertEquals(
            5 to SliderQuantityOutcome.TARGET_REACHABLE,
            BetterSlidingDecision.resolveSliderQuantityOutcome(5, 10, false),
        )
        // 相等也算可达（只有严格大于上限才钳制）
        assertEquals(
            10 to SliderQuantityOutcome.TARGET_REACHABLE,
            BetterSlidingDecision.resolveSliderQuantityOutcome(10, 10, false),
        )
    }

    @Test
    fun `resolveSliderQuantityOutcome 超上限时看是否钳制`() {
        assertEquals(
            10 to SliderQuantityOutcome.CLAMPED,
            BetterSlidingDecision.resolveSliderQuantityOutcome(15, 10, true),
        )
        assertEquals(
            15 to SliderQuantityOutcome.OUT_OF_RANGE,
            BetterSlidingDecision.resolveSliderQuantityOutcome(15, 10, false),
        )
    }

    @Test
    fun `resolveSliderQuantityOutcome 目标非法或滑条上限为 0 都越界`() {
        assertEquals(
            0 to SliderQuantityOutcome.OUT_OF_RANGE,
            BetterSlidingDecision.resolveSliderQuantityOutcome(0, 10, true),
        )
        assertEquals(
            5 to SliderQuantityOutcome.OUT_OF_RANGE,
            BetterSlidingDecision.resolveSliderQuantityOutcome(5, 0, true),
        )
    }

    @Test
    fun `resolveSliderMaxQuantityNext 相等收尾 矛盾报错 否则不改写`() {
        assertEquals("BetterSlidingDone", BetterSlidingDecision.resolveSliderMaxQuantityNext(10, 10))
        assertNull(BetterSlidingDecision.resolveSliderMaxQuantityNext(10, 5))
        rejects { BetterSlidingDecision.resolveSliderMaxQuantityNext(5, 10) }
    }

    @Test
    fun `shouldResetBeforePreciseClick 是严格大于 80 percent 且严格小于上限`() {
        assertEquals(true, BetterSlidingDecision.shouldResetBeforePreciseClick(81, 100))
        assertEquals(false, BetterSlidingDecision.shouldResetBeforePreciseClick(80, 100))
        assertEquals(true, BetterSlidingDecision.shouldResetBeforePreciseClick(9, 10))
        assertEquals(false, BetterSlidingDecision.shouldResetBeforePreciseClick(100, 100))
        assertEquals(false, BetterSlidingDecision.shouldResetBeforePreciseClick(0, 100))
        assertEquals(false, BetterSlidingDecision.shouldResetBeforePreciseClick(1, 1))
        assertEquals(false, BetterSlidingDecision.shouldResetBeforePreciseClick(2, 100))
    }

    @Test
    fun `isActionNode 只认那七个驱动节点`() {
        for (node in listOf(
            "BetterSlidingMain",
            "BetterSlidingFindStart",
            "BetterSlidingGetSliderMaxQuantity",
            "BetterSlidingGetAvailableQuantity",
            "BetterSlidingFindEnd",
            "BetterSlidingCheckQuantity",
            "BetterSlidingDone",
        )) {
            assertEquals(true, BetterSlidingDecision.isActionNode(node))
        }
        // 这两个是流水线里的普通动作节点，不由 handler 分发
        assertEquals(false, BetterSlidingDecision.isActionNode("BetterSlidingPreciseClick"))
        assertEquals(false, BetterSlidingDecision.isActionNode("BetterSlidingSwipeToMax"))
    }

    @Test
    fun `goRound 是半数远离零 不是 Math round`() {
        assertEquals(3, BetterSlidingDecision.goRound(2.5))
        assertEquals(2, BetterSlidingDecision.goRound(2.4))
        // 与 Math.round 的分歧点：负半数
        assertEquals(-3, BetterSlidingDecision.goRound(-2.5))
        assertEquals(-2, BetterSlidingDecision.goRound(-2.4))
        assertEquals(0, BetterSlidingDecision.goRound(0.0))
    }

    @Test
    fun `preciseClick 数量 1 落在 Start 最高档落在 End`() {
        // Start 中心 (100,510)、End 中心 (600,510)：注意 offset=-10 会把中心左移
        assertEquals(100 to 510, BetterSlidingDecision.preciseClick(hStart, hEnd, offset, 1, 10))
        assertEquals(600 to 510, BetterSlidingDecision.preciseClick(hStart, hEnd, offset, 10, 10))
    }

    @Test
    fun `preciseClick 按比例插值`() {
        // 目标 4 / 上限 10 -> 分子 3 分母 9 -> 500*3/9 = 166.67 -> 167
        assertEquals(267 to 510, BetterSlidingDecision.preciseClick(hStart, hEnd, offset, 4, 10))
        // 目标 5 / 上限 10 -> 4/9 -> 222.22 -> 222
        assertEquals(322 to 510, BetterSlidingDecision.preciseClick(hStart, hEnd, offset, 5, 10))
    }

    @Test
    fun `preciseClick 负半数必须远离零 而不是向零靠拢`() {
        // offset 取 0 方便心算：Start 中心 x=10，End 中心 x=5，位移 -5
        // 目标 2 / 上限 3 -> 分子 1 分母 2 -> -5*1/2 = -2.5
        // Go math.Round 是 -3；Math.round(-2.5) 是 -2 —— 这里必须得 -3
        assertEquals(
            7 to 510,
            BetterSlidingDecision.preciseClick(listOf(0, 500, 20, 20), listOf(0, 500, 10, 20), offset0, 2, 3),
        )
    }

    @Test
    fun `preciseClick 反向滑条同样按端点插值`() {
        // Start 在右 (600,510)、End 在左 (100,510)，位移为负
        assertEquals(600 to 510, BetterSlidingDecision.preciseClick(hEnd, hStart, offset, 1, 10))
        assertEquals(100 to 510, BetterSlidingDecision.preciseClick(hEnd, hStart, offset, 10, 10))
    }

    @Test
    fun `preciseClick 非法输入要报错`() {
        // 上限为 1 时分母为 0
        rejects { BetterSlidingDecision.preciseClick(hStart, hEnd, offset, 1, 1) }
        rejects { BetterSlidingDecision.preciseClick(hStart, hEnd, offset, 1, 0) }
        rejects { BetterSlidingDecision.preciseClick(listOf(1, 2, 3), hEnd, offset, 1, 10) }
        rejects { BetterSlidingDecision.preciseClick(hStart, null, offset, 1, 10) }
    }

    @Test
    fun `resolveQuantityBranch 三个出口`() {
        val done = BetterSlidingDecision.resolveQuantityBranch(7, 7)
        assertEquals(BetterSlidingDecision.QuantityBranch.DONE, done.branch)
        assertEquals("BetterSlidingDone", done.nextNode)
        assertEquals(0, done.repeat)

        val inc = BetterSlidingDecision.resolveQuantityBranch(4, 9)
        assertEquals(BetterSlidingDecision.QuantityBranch.INCREASE, inc.branch)
        assertEquals("BetterSlidingIncreaseQuantity", inc.nextNode)
        assertEquals(5, inc.repeat)

        val dec = BetterSlidingDecision.resolveQuantityBranch(9, 4)
        assertEquals(BetterSlidingDecision.QuantityBranch.DECREASE, dec.branch)
        assertEquals("BetterSlidingDecreaseQuantity", dec.nextNode)
        assertEquals(5, dec.repeat)
    }

    @Test
    fun `resolveQuantityBranch 差值夹在 30 次以内`() {
        assertEquals(30, BetterSlidingDecision.resolveQuantityBranch(1, 999).repeat)
        assertEquals(30, BetterSlidingDecision.resolveQuantityBranch(999, 1).repeat)
    }

    @Test
    fun `validateMainInputs 定量模式必须有合法滑条框`() {
        BetterSlidingDecision.validateMainInputs(
            swipeOnlyMode = true,
            sliderQuantityBox = emptyList(),
            availableQuantityBox = emptyList(),
            availableQuantityExplicit = false,
            direction = "right",
        )
        rejects {
            BetterSlidingDecision.validateMainInputs(false, emptyList(), emptyList(), false, "right")
        }
        rejects {
            BetterSlidingDecision.validateMainInputs(false, listOf(1, 2, 3), emptyList(), false, "right")
        }
        // 显式给了 available 就必须给它的框
        rejects {
            BetterSlidingDecision.validateMainInputs(false, hStart, emptyList(), true, "right")
        }
        BetterSlidingDecision.validateMainInputs(false, hStart, hEnd, true, "left")
        rejects {
            BetterSlidingDecision.validateMainInputs(true, emptyList(), emptyList(), false, "diagonal")
        }
    }

    @Test
    fun `ActionState reset 清空跨节点状态`() {
        val state = BetterSlidingDecision.ActionState()
        state.startBox = hStart
        state.endBox = hEnd
        state.preciseClickBase = 123 to 456
        state.preciseClickNudges = 7
        state.sliderMaxQuantity = 99
        state.availableQuantity = 88
        state.availableQuantityResolved = true
        state.outOfRange = true
        state.targetReachable = true
        state.minimumTargetShortCircuit = true
        state.runtimeTargetResolved = true
        state.applyParsedTarget(42)

        state.reset()

        assertNull(state.startBox)
        assertNull(state.endBox)
        assertEquals(0 to 0, state.preciseClickBase)
        assertEquals(0, state.preciseClickNudges)
        assertEquals(0, state.sliderMaxQuantity)
        assertEquals(0, state.availableQuantity)
        assertEquals(false, state.availableQuantityResolved)
        assertEquals(false, state.outOfRange)
        assertEquals(false, state.targetReachable)
        assertEquals(false, state.minimumTargetShortCircuit)
        assertEquals(false, state.runtimeTargetResolved)
    }

    @Test
    fun `reset 故意不清目标数量`() {
        val state = BetterSlidingDecision.ActionState()
        state.applyParsedTarget(42)
        state.reset()
        // 上游 resetState 也不清这两个：handleMain 之后的节点重新加载参数时
        // 依赖它们被设回原始值，顺手清掉反而丢基准
        assertEquals(42, state.targetQuantity)
        assertEquals(42, state.originalTargetQuantity)
    }

    @Test
    fun `applyParsedTarget 在运行时已解析后不覆盖实际目标`() {
        val state = BetterSlidingDecision.ActionState()
        state.applyParsedTarget(10)
        assertEquals(10, state.targetQuantity)
        assertEquals(10, state.originalTargetQuantity)

        // 模拟 GetSliderMaxQuantity 用滑条上限把目标解析成了 7
        state.targetQuantity = 7
        state.runtimeTargetResolved = true

        // 后续节点（FindEnd / CheckQuantity）再次加载参数：原始目标要更新，实际目标不能被动
        state.applyParsedTarget(10)
        assertEquals(10, state.originalTargetQuantity)
        assertEquals(7, state.targetQuantity)
    }

    @Test
    fun `reset 之后 applyParsedTarget 可以重新覆盖实际目标`() {
        val state = BetterSlidingDecision.ActionState()
        state.targetQuantity = 7
        state.runtimeTargetResolved = true

        // resetState 只清 runtimeTargetResolved
        state.reset()
        state.applyParsedTarget(10)
        assertEquals(10, state.targetQuantity)
    }
}
