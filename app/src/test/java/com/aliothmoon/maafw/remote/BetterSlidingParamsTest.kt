package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.BetterSlidingParams.ButtonTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BetterSliding 参数层测试（对齐上游 bettersliding/params.go）。
 *
 * 用例直接照着仓库里两个真实节点写：
 *  - `AutoStockpileSwipeMax` 的参数只有 `{"Direction":"right"}`，必须落进「只滑动」模式；
 *  - `AutoStockpileSwipeSpecificQuantity` 的 ADB 覆盖版带 TargetQuantity/SliderQuantity(HSV)/增减按钮，
 *    必须落进「定量」模式，且 SliderQuantity.Filter 要按 method=40 收三通道。
 */
class BetterSlidingParamsTest {

    /** 上游 hasNonNullRawKey 的等价物：给出全部键，并指明哪些是 JSON null。 */
    private fun presence(keys: List<String>, nullKeys: List<String> = emptyList()) =
        BetterSlidingParams.detectPresence(
            allKeys = keys.toSet(),
            nonNullKeys = keys.filterNot { it in nullKeys }.toSet(),
        )

    private fun rejects(block: () -> Any?) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `SliderQuantity 只看键存在 其余键要求非 null`() {
        val p = presence(listOf("SliderQuantity", "TargetQuantity"), nullKeys = listOf("SliderQuantity", "TargetQuantity"))
        assertEquals(true, p.sliderQuantity)
        assertEquals(false, p.targetQuantity)
    }

    @Test
    fun `SwipeMax 只有 Direction 时进入只滑动模式`() {
        val p = presence(listOf("Direction"))
        assertEquals(true, BetterSlidingSupport.isSwipeOnlyMode(p))

        val r = BetterSlidingParams.normalizeActionParams(BetterSlidingParams.RawParam(direction = "right"), p)
        assertEquals(true, r.swipeOnlyMode)
        assertEquals("right", r.direction)
        assertEquals(0, r.targetQuantity)
        assertEquals(listOf(-10, 0), r.centerPointOffset)
        assertEquals(BetterSlidingSupport.FineTuneQuantity(enabled = true), r.fineTuneQuantity)
        assertEquals("none", r.fineTuneFallback)
        assertEquals(emptyList<Int>(), r.sliderQuantityBox)
        assertNull(r.increaseButton)
    }

    @Test
    fun `只滑动模式的方向大小写与空格归一`() {
        val p = presence(listOf("Direction"))
        assertEquals("right", BetterSlidingParams.normalizeActionParams(BetterSlidingParams.RawParam(direction = " RIGHT "), p).direction)
    }

    @Test
    fun `只滑动模式方向非法要报错`() {
        val p = presence(listOf("Direction"))
        rejects { BetterSlidingParams.normalizeActionParams(BetterSlidingParams.RawParam(direction = "diagonal"), p) }
        rejects { BetterSlidingParams.normalizeActionParams(BetterSlidingParams.RawParam(direction = ""), p) }
    }

    @Test
    fun `只滑动模式仍可携带 SwipeButton 与 ResetBeforeFindStart`() {
        val p = presence(listOf("Direction", "SwipeButton", "ResetBeforeFindStart"))
        val r = BetterSlidingParams.normalizeActionParams(
            BetterSlidingParams.RawParam(direction = "up", swipeButton = " x.png ", resetBeforeFindStart = true),
            p,
        )
        assertEquals("x.png", r.swipeButton)
        assertEquals(true, r.resetBeforeFindStart)
    }

    @Test
    fun `SwipeSpecificQuantity 的 ADB 覆盖版进入定量模式`() {
        val keys = listOf(
            "ClampTargetToSliderMax", "DecreaseButton", "Direction", "IncreaseButton",
            "SliderQuantity", "TargetQuantity",
        )
        val p = presence(keys)
        assertEquals(false, BetterSlidingSupport.isSwipeOnlyMode(p))

        val r = BetterSlidingParams.normalizeActionParams(
            BetterSlidingParams.RawParam(
                targetQuantity = 1,
                sliderQuantity = BetterSlidingParams.RawQuantity(
                    box = listOf(303, 516, 111, 47),
                    filter = BetterSlidingParams.RawFilter(listOf(20, 150, 150), listOf(35, 255, 255), 40),
                ),
                direction = "right",
                increaseButton = ButtonTarget.Template("AutoStockpile/IncreaseButton.png"),
                decreaseButton = ButtonTarget.Template("AutoStockpile/DecreaseButton.png"),
                clampTargetToSliderMax = true,
            ),
            p,
        )
        assertEquals(false, r.swipeOnlyMode)
        assertEquals(1, r.targetQuantity)
        assertEquals(
            BetterSlidingSupport.QuantityFilter(listOf(20, 150, 150), listOf(35, 255, 255), 40),
            r.sliderQuantityFilter,
        )
        assertEquals(listOf(303, 516, 111, 47), r.sliderQuantityBox)
        assertEquals(true, r.clampTargetToSliderMax)
        assertEquals(ButtonTarget.Template("AutoStockpile/IncreaseButton.png"), r.increaseButton)
        assertEquals(false, r.availableQuantityExplicit)
        assertEquals(false, r.sliderQuantityOnlyRec)
        // 目标 1 + Value + 非反向 -> 走「目标即最小值」短路
        assertEquals(true, BetterSlidingSupport.isMinimumTargetShortCircuit(r.targetQuantity, r.targetQuantityType, r.reverseTarget))
    }

    @Test
    fun `定量模式 targetQuantity 必须大于 0`() {
        rejects {
            BetterSlidingParams.normalizeActionParams(
                BetterSlidingParams.RawParam(targetQuantity = 0),
                presence(listOf("TargetQuantity")),
            )
        }
    }

    @Test
    fun `定量模式的 direction 不校验 与上游一致`() {
        val p = presence(listOf("TargetQuantity", "Direction"))
        assertEquals(
            "diagonal",
            BetterSlidingParams.normalizeActionParams(
                BetterSlidingParams.RawParam(targetQuantity = 5, direction = "Diagonal"),
                p,
            ).direction,
        )
    }

    @Test
    fun `按钮可以是坐标或模板`() {
        assertEquals(
            ButtonTarget.Coordinates(listOf(3, 4, 1, 1)),
            BetterSlidingParams.normalizeButtonTarget(ButtonTarget.Coordinates(listOf(3, 4))),
        )
        assertEquals(ButtonTarget.Template("a.png"), BetterSlidingParams.normalizeButtonTarget(ButtonTarget.Template(" a.png ")))
        rejects { BetterSlidingParams.normalizeButtonTarget(ButtonTarget.Template("   ")) }
    }

    @Test
    fun `AvailableQuantity 只有提供了才解析`() {
        val p = presence(listOf("TargetQuantity", "AvailableQuantity"))
        val r = BetterSlidingParams.normalizeActionParams(
            BetterSlidingParams.RawParam(
                targetQuantity = 10,
                availableQuantity = BetterSlidingParams.RawQuantity(
                    box = listOf(1, 2, 3, 4),
                    filter = BetterSlidingParams.RawFilter(listOf(1), listOf(2), 6),
                    onlyRec = true,
                ),
            ),
            p,
        )
        assertEquals(true, r.availableQuantityExplicit)
        assertEquals(listOf(1, 2, 3, 4), r.availableQuantityBox)
        assertEquals(BetterSlidingSupport.QuantityFilter(listOf(1), listOf(2), 6), r.availableQuantityFilter)
        assertEquals(true, r.availableQuantityOnlyRec)
    }

    @Test
    fun `两个结果覆盖节点不能重复 且首尾空格要去掉`() {
        val p = presence(listOf("TargetQuantity", "OutOfRangeOverrideEnable", "TargetReachableOverrideEnable"))
        rejects {
            BetterSlidingParams.normalizeActionParams(
                BetterSlidingParams.RawParam(
                    targetQuantity = 1,
                    outOfRangeOverrideEnable = "N",
                    targetReachableOverrideEnable = "N",
                ),
                p,
            )
        }
        val r = BetterSlidingParams.normalizeActionParams(
            BetterSlidingParams.RawParam(
                targetQuantity = 1,
                outOfRangeOverrideEnable = " A ",
                targetReachableOverrideEnable = "B",
            ),
            p,
        )
        assertEquals("A", r.outOfRangeOverrideEnable)
        assertEquals("B", r.targetReachableOverrideEnable)
    }

    @Test
    fun `FineTuneQuantity 与 Fallback 在定量模式生效`() {
        val p = presence(listOf("TargetQuantity", "FineTuneQuantity", "FineTuneFallback"))
        val r = BetterSlidingParams.normalizeActionParams(
            BetterSlidingParams.RawParam(targetQuantity = 50, fineTuneQuantity = 3, fineTuneFallback = "MORE"),
            p,
        )
        assertEquals(BetterSlidingSupport.FineTuneQuantity(thresholdMode = true, threshold = 3), r.fineTuneQuantity)
        assertEquals("more", r.fineTuneFallback)
    }

    @Test
    fun `SliderQuantity 显式 null 会把 SwipeMax 推入定量模式并因 target 非法报错`() {
        val p = presence(listOf("Direction", "SliderQuantity"), nullKeys = listOf("SliderQuantity"))
        assertEquals(true, p.sliderQuantity)
        assertEquals(false, BetterSlidingSupport.isSwipeOnlyMode(p))
        rejects { BetterSlidingParams.normalizeActionParams(BetterSlidingParams.RawParam(direction = "right"), p) }
    }

    @Test
    fun `attach 合并字段与上游一致`() {
        assertEquals(
            listOf(
                "TargetQuantity", "TargetQuantityType", "ReverseTarget",
                "FineTuneQuantity", "FineTuneFallback", "ResetBeforeFindStart",
            ),
            BetterSlidingParams.ATTACH_MERGED_KEYS,
        )
    }
}
