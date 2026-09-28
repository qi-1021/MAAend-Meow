package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BetterSliding 纯逻辑层的测试（对齐上游 bettersliding/normalize.go 的语义）。
 *
 * 为什么先测这一层：`BetterSliding` 在移动端被注册成 noop 成功，而据点交易的 6 个据点、
 * 囤货、稳定物资购买都 `enabled=true` 地依赖它，滑条会停在默认值——这不是「少个功能」，
 * 是**买错/卖错数量**。状态机（handlers.go）必须建立在这一层之上，先把它钉死。
 *
 * 这里刻意锁住几个「看起来像 bug、其实是上游行为」的点，避免后人好心改坏：
 *  - Percentage 的夹取顺序是先抬到 1 再压到 availableQuantity，所以 availableQuantity=0 时结果是 0；
 *  - FineTuneQuantity 阈值超过 Int.MAX_VALUE 时饱和为「始终微调」，不许回绕成「从不微调」；
 *  - isSwipeOnlyMode 不把 SwipeButton 算进判定（上游确实没算）。
 */
class BetterSlidingSupportTest {

    private fun presence(
        direction: Boolean = false,
        targetQuantity: Boolean = false,
        sliderQuantity: Boolean = false,
        clampTargetToSliderMax: Boolean = false,
        fineTuneQuantity: Boolean = false,
        swipeButton: Boolean = false,
    ) = BetterSlidingSupport.Presence(
        direction = direction,
        targetQuantity = targetQuantity,
        sliderQuantity = sliderQuantity,
        clampTargetToSliderMax = clampTargetToSliderMax,
        fineTuneQuantity = fineTuneQuantity,
        swipeButton = swipeButton,
    )

    private fun rejects(block: () -> Any?) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `clampClickRepeat 夹在 0 到 30`() {
        assertEquals(0, BetterSlidingSupport.clampClickRepeat(-5))
        assertEquals(30, BetterSlidingSupport.clampClickRepeat(30))
        assertEquals(30, BetterSlidingSupport.clampClickRepeat(31))
    }

    @Test
    fun `normalizeButton 只接受 x,y 或 x,y,w,h`() {
        assertEquals(listOf(3, 4, 1, 1), BetterSlidingSupport.normalizeButton(listOf(3, 4)))
        assertEquals(listOf(3, 4, 5, 6), BetterSlidingSupport.normalizeButton(listOf(3, 4, 5, 6)))
        rejects { BetterSlidingSupport.normalizeButton(listOf(1, 2, 3)) }
    }

    @Test
    fun `normalizeCenterPointOffset 缺省是 -10,0`() {
        assertEquals(listOf(-10, 0), BetterSlidingSupport.normalizeCenterPointOffset(null))
        assertEquals(listOf(2, -3), BetterSlidingSupport.normalizeCenterPointOffset(listOf(2, -3)))
        rejects { BetterSlidingSupport.normalizeCenterPointOffset(listOf(1)) }
    }

    @Test
    fun `颜色阈值通道数按 method 决定`() {
        assertEquals(3, BetterSlidingSupport.quantityFilterChannelCount(4))
        assertEquals(3, BetterSlidingSupport.quantityFilterChannelCount(40))
        assertEquals(1, BetterSlidingSupport.quantityFilterChannelCount(6))
        rejects { BetterSlidingSupport.quantityFilterChannelCount(9) }
    }

    @Test
    fun `normalizeQuantityFilter 校验通道数与上下界`() {
        assertEquals(
            BetterSlidingSupport.QuantityFilter(listOf(75, 75, 75), listOf(255, 255, 255), 4),
            BetterSlidingSupport.normalizeQuantityFilter(
                "SliderQuantity", listOf(75, 75, 75), listOf(255, 255, 255), 4,
            ),
        )
        // 真机上 resource_adb 覆盖用的 HSV 阈值
        assertEquals(
            BetterSlidingSupport.QuantityFilter(listOf(20, 150, 150), listOf(35, 255, 255), 40),
            BetterSlidingSupport.normalizeQuantityFilter(
                "SliderQuantity", listOf(20, 150, 150), listOf(35, 255, 255), 40,
            ),
        )
        rejects { BetterSlidingSupport.normalizeQuantityFilter("X", listOf(1, 2, 3), listOf(4, 5, 6), 6) }
        rejects { BetterSlidingSupport.normalizeQuantityFilter("X", listOf(1, 2, 3), null, 4) }
        assertNull(BetterSlidingSupport.normalizeQuantityFilter("X", null, null, 4))
    }

    @Test
    fun `centerPoint 是矩形中心加偏移`() {
        assertEquals(110 to 210, BetterSlidingSupport.centerPoint(listOf(100, 200, 40, 20), listOf(-10, 0)))
        assertEquals(0 to 0, BetterSlidingSupport.centerPoint(listOf(1, 2, 3), listOf(-10, 0)))
    }

    @Test
    fun `normalizeTargetQuantityType 空串默认 Value`() {
        assertEquals("Value", BetterSlidingSupport.normalizeTargetQuantityType("  "))
        assertEquals("Value", BetterSlidingSupport.normalizeTargetQuantityType("value"))
        assertEquals("Percentage", BetterSlidingSupport.normalizeTargetQuantityType("PERCENTAGE"))
        rejects { BetterSlidingSupport.normalizeTargetQuantityType("ratio") }
    }

    @Test
    fun `resolveTargetQuantity Value 模式`() {
        assertEquals(7, BetterSlidingSupport.resolveTargetQuantity(7, "Value", false, 500))
        assertEquals(493, BetterSlidingSupport.resolveTargetQuantity(7, "Value", true, 500))
    }

    @Test
    fun `resolveTargetQuantity Percentage 模式`() {
        assertEquals(100, BetterSlidingSupport.resolveTargetQuantity(25, "Percentage", false, 400))
        assertEquals(201, BetterSlidingSupport.resolveTargetQuantity(50, "Percentage", true, 401))
        // 四舍五入是 half-up
        assertEquals(2, BetterSlidingSupport.resolveTargetQuantity(50, "Percentage", false, 3))
        // 先抬到 1 再压到 availableQuantity，所以结果是 9
        assertEquals(9, BetterSlidingSupport.resolveTargetQuantity(100, "Percentage", false, 9))
        // 上游夹取顺序的产物：availableQuantity=0 时结果是 0，不是 1。这是上游行为，不要"顺手修"。
        assertEquals(0, BetterSlidingSupport.resolveTargetQuantity(100, "Percentage", false, 0))
        rejects { BetterSlidingSupport.resolveTargetQuantity(0, "Percentage", false, 10) }
        rejects { BetterSlidingSupport.resolveTargetQuantity(101, "Percentage", false, 10) }
    }

    @Test
    fun `minimumTargetShortCircuit 只在 目标为1 且 Value 且非反向 时成立`() {
        assertEquals(true, BetterSlidingSupport.isMinimumTargetShortCircuit(1, "Value", false))
        assertEquals(false, BetterSlidingSupport.isMinimumTargetShortCircuit(1, "Value", true))
        assertEquals(false, BetterSlidingSupport.isMinimumTargetShortCircuit(2, "Value", false))
        assertEquals(false, BetterSlidingSupport.isMinimumTargetShortCircuit(1, "Percentage", false))
        assertEquals("BetterSlidingReset", BetterSlidingSupport.minimumTargetShortCircuitNext(true))
        assertEquals("BetterSlidingClearMaxHit", BetterSlidingSupport.minimumTargetShortCircuitNext(false))
    }

    @Test
    fun `normalizeFineTuneQuantity 缺省是始终微调`() {
        assertEquals(
            BetterSlidingSupport.FineTuneQuantity(enabled = true),
            BetterSlidingSupport.normalizeFineTuneQuantity(null, present = false),
        )
        // present=true 但值是 null：上游走 default 分支报错
        rejects { BetterSlidingSupport.normalizeFineTuneQuantity(null, present = true) }
        assertEquals(
            BetterSlidingSupport.FineTuneQuantity(enabled = true),
            BetterSlidingSupport.normalizeFineTuneQuantity(true, present = true),
        )
        assertEquals(
            BetterSlidingSupport.FineTuneQuantity(enabled = false),
            BetterSlidingSupport.normalizeFineTuneQuantity(false, present = true),
        )
    }

    @Test
    fun `normalizeFineTuneQuantity 整数是阈值语义`() {
        assertEquals(
            BetterSlidingSupport.FineTuneQuantity(thresholdMode = true, threshold = 5),
            BetterSlidingSupport.normalizeFineTuneQuantity(5, present = true),
        )
        assertEquals(
            BetterSlidingSupport.FineTuneQuantity(thresholdMode = true, threshold = 5),
            BetterSlidingSupport.normalizeFineTuneQuantity(5.0, present = true),
        )
        // 超过上界饱和为"始终微调"，不能回绕成"从不微调"
        assertEquals(
            BetterSlidingSupport.FineTuneQuantity(enabled = true),
            BetterSlidingSupport.normalizeFineTuneQuantity(3.0e9, present = true),
        )
        rejects { BetterSlidingSupport.normalizeFineTuneQuantity(0, present = true) }
        rejects { BetterSlidingSupport.normalizeFineTuneQuantity(1.5, present = true) }
    }

    @Test
    fun `normalizeFineTuneFallback 大小写不敏感`() {
        assertEquals("none", BetterSlidingSupport.normalizeFineTuneFallback(null))
        assertEquals("none", BetterSlidingSupport.normalizeFineTuneFallback("  "))
        assertEquals("more", BetterSlidingSupport.normalizeFineTuneFallback("MORE"))
        assertEquals("less", BetterSlidingSupport.normalizeFineTuneFallback("less"))
        rejects { BetterSlidingSupport.normalizeFineTuneFallback("sideways") }
    }

    @Test
    fun `isSwipeOnlyMode 只给 Direction 的时代入滑动模式`() {
        // AutoStockpileSwipeMax / AutoStockSwipeToMax 就是这种（只要拖到最大）
        assertEquals(true, BetterSlidingSupport.isSwipeOnlyMode(presence(direction = true)))
        assertEquals(true, BetterSlidingSupport.isSwipeOnlyMode(presence()))
        assertEquals(false, BetterSlidingSupport.isSwipeOnlyMode(presence(direction = true, targetQuantity = true)))
        assertEquals(
            false,
            BetterSlidingSupport.isSwipeOnlyMode(presence(direction = true, clampTargetToSliderMax = true)),
        )
        assertEquals(false, BetterSlidingSupport.isSwipeOnlyMode(presence(direction = true, sliderQuantity = true)))
        assertEquals(false, BetterSlidingSupport.isSwipeOnlyMode(presence(direction = true, fineTuneQuantity = true)))
        // 上游没把 SwipeButton 算进判定，这里保持一致
        assertEquals(true, BetterSlidingSupport.isSwipeOnlyMode(presence(swipeButton = true)))
    }
}
