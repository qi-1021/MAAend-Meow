package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.BetterSlidingDecision.Reset2Side
import com.aliothmoon.maafw.remote.BetterSlidingOverrides as OV
import com.aliothmoon.maafw.remote.BetterSlidingParams.ButtonTarget
import com.aliothmoon.maafw.remote.BetterSlidingSupport as BS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BetterSliding 覆盖构造层测试（对齐上游 bettersliding/overrides.go）。
 *
 * 这些函数把「算好的目标」翻译成 MaaFramework 的流水线覆盖 JSON。
 * 用真实节点名做断言，保证和 resource/pipeline/BetterSliding 目录里的节点对得上——
 * 节点名写错一个字符，运行时就是覆盖到一个不存在的节点然后静默失效。
 */
class BetterSlidingOverridesTest {

    private fun rejects(block: () -> Any?) {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `buildSwipeEnd 按方向给最大侧或最小侧终点`() {
        assertEquals(listOf(1260, 10, 10, 10), OV.buildSwipeEnd("right"))
        assertEquals(listOf(1260, 10, 10, 10), OV.buildSwipeEnd("up"))
        assertEquals(listOf(10, 700, 10, 10), OV.buildSwipeEnd("left"))
        assertEquals(listOf(10, 700, 10, 10), OV.buildSwipeEnd("down"))
        rejects { OV.buildSwipeEnd("diagonal") }
    }

    @Test
    fun `buildResetSwipeEnd 与 buildSwipeEnd 相反`() {
        assertEquals(listOf(10, 700, 10, 10), OV.buildResetSwipeEnd("right"))
        assertEquals(listOf(1260, 10, 10, 10), OV.buildResetSwipeEnd("left"))
    }

    @Test
    fun `buildReset2SwipeEnd 按点击位置决定复位方向`() {
        assertEquals(
            listOf(1260, 10, 10, 10),
            OV.buildReset2SwipeEnd("right", Reset2Side.TOWARD_END),
        )
        assertEquals(
            listOf(10, 700, 10, 10),
            OV.buildReset2SwipeEnd("right", Reset2Side.TOWARD_START),
        )
    }

    @Test
    fun `resolveButtonHelperNode 映射到增减按钮节点`() {
        assertEquals("BetterSlidingIncreaseButton", OV.resolveButtonHelperNode("BetterSlidingIncreaseQuantity"))
        assertEquals("BetterSlidingDecreaseButton", OV.resolveButtonHelperNode("BetterSlidingDecreaseQuantity"))
        assertEquals("", OV.resolveButtonHelperNode("BetterSlidingDone"))
    }

    @Test
    fun `buildCheckQuantityBranchOverride 非加减出口返回空`() {
        assertEquals(
            emptyMap<String, Any?>(),
            OV.buildCheckQuantityBranchOverride("BetterSlidingDone", null, 0),
        )
    }

    @Test
    fun `buildCheckQuantityBranchOverride 模板按钮要挂 helper 节点`() {
        val override = OV.buildCheckQuantityBranchOverride(
            "BetterSlidingIncreaseQuantity",
            ButtonTarget.Template("AutoStockpile/IncreaseButton.png"),
            5,
        )
        val helper = override["BetterSlidingIncreaseButton"] as Map<*, *>
        val param = ((helper["recognition"] as Map<*, *>)["param"] as Map<*, *>)
        assertEquals(listOf("AutoStockpile/IncreaseButton.png"), param["template"])
        assertEquals(true, param["green_mask"])

        val node = override["BetterSlidingIncreaseQuantity"] as Map<*, *>
        assertEquals(5, node["repeat"])
        val action = (node["action"] as Map<*, *>)
        assertEquals("Click", action["type"])
    }

    @Test
    fun `buildCheckQuantityBranchOverride 坐标按钮写 target 且 repeat 夹取`() {
        val override = OV.buildCheckQuantityBranchOverride(
            "BetterSlidingDecreaseQuantity",
            ButtonTarget.Coordinates(listOf(10, 20, 30, 40)),
            99,
        )
        val node = override["BetterSlidingDecreaseQuantity"] as Map<*, *>
        assertEquals(30, node["repeat"])
        val param = (((node["action"] as Map<*, *>)["param"]) as Map<*, *>)
        assertEquals(listOf(10, 20, 30, 40), param["target"])
    }

    @Test
    fun `buildMainInitializationOverride 滑条框为空时提前返回`() {
        val override = OV.buildMainInitializationOverride(
            end = listOf(1260, 10, 10, 10),
            sliderQuantityBox = emptyList(),
            availableQuantityBox = emptyList(),
            availableQuantityExplicit = false,
            sliderQuantityFilter = null,
            availableQuantityFilter = null,
            sliderQuantityOnlyRec = false,
            availableQuantityOnlyRec = false,
            swipeButton = "",
        )
        // 上游在 len(sliderQuantityBox)==0 时**直接 return**（overrides.go:113），
        // 所以 GetAvailableQuantity 的启用/禁用根本不会执行——
        // 这正是"只滑动"模式（AutoStockpileSwipeMax）走的分支。
        assertEquals(1, override.size)
        assertTrue(override.containsKey("BetterSlidingSwipeToMax"))
        assertEquals(false, override.containsKey("BetterSlidingGetAvailableQuantity"))
        assertEquals(false, override.containsKey("BetterSlidingGetSliderQuantity"))
    }

    @Test
    fun `buildMainInitializationOverride 完整形态`() {
        val override = OV.buildMainInitializationOverride(
            end = listOf(1260, 10, 10, 10),
            sliderQuantityBox = listOf(303, 516, 111, 47),
            availableQuantityBox = listOf(500, 100, 50, 20),
            availableQuantityExplicit = true,
            sliderQuantityFilter = BS.QuantityFilter(listOf(20, 150, 150), listOf(35, 255, 255), 40),
            availableQuantityFilter = BS.QuantityFilter(listOf(1), listOf(2), 6),
            sliderQuantityOnlyRec = true,
            availableQuantityOnlyRec = true,
            swipeButton = "x/y.png",
        )
        assertTrue(override.containsKey("BetterSlidingSwipeButton"))
        assertTrue(override.containsKey("BetterSlidingSliderQuantityFilter"))
        assertTrue(override.containsKey("BetterSlidingAvailableQuantityFilter"))

        val sliderParam =
            (((override["BetterSlidingGetSliderQuantity"] as Map<*, *>)["recognition"] as Map<*, *>)["param"] as Map<*, *>)
        assertEquals(listOf(303, 516, 111, 47), sliderParam["roi"])
        assertEquals(true, sliderParam["only_rec"])
        // 有颜色阈值时才挂 color_filter，并指向对应的阈值节点
        assertEquals("BetterSlidingSliderQuantityFilter", sliderParam["color_filter"])

        val sliderFilter =
            (((override["BetterSlidingSliderQuantityFilter"] as Map<*, *>)["recognition"] as Map<*, *>)["param"] as Map<*, *>)
        assertEquals(40, sliderFilter["method"])
        // 上游把 lower/upper 包成二维数组
        assertEquals(listOf(listOf(20, 150, 150)), sliderFilter["lower"])
        assertEquals(listOf(listOf(35, 255, 255)), sliderFilter["upper"])

        val available = override["BetterSlidingGetAvailableQuantity"] as Map<*, *>
        assertEquals(true, available["enabled"])
    }

    @Test
    fun `buildInternalPipelineOverride 给每个驱动节点塞同一份参数`() {
        val override = OV.buildInternalPipelineOverride(mapOf("k" to 1))
        assertEquals(7, override.size)
        for (node in BetterSlidingDecision.ACTION_NODES) {
            val param =
                ((((override[node] as Map<*, *>)["action"] as Map<*, *>)["param"]) as Map<*, *>)
            assertEquals(mapOf("k" to 1), param["custom_action_param"])
        }
    }

    @Test
    fun `parseInternalPipelineCustomActionParam 能解一层字符串`() {
        // 模拟：外层已经是字符串，parseJson 能解开 -> 用解开的结果
        assertEquals(
            "hello",
            OV.parseInternalPipelineCustomActionParam("\"hello\"") { it.trim('"') },
        )
        // 解不开就原样保留
        assertEquals(
            "not-json",
            OV.parseInternalPipelineCustomActionParam("not-json") { null },
        )
        // 非字符串（已是 map）原样返回
        assertEquals(
            mapOf("a" to 1),
            OV.parseInternalPipelineCustomActionParam(mapOf("a" to 1)) { null },
        )
    }

    @Test
    fun `buildResetSwipeOverride 改写复位节点`() {
        val override = OV.buildResetSwipeOverride("right", true)
        assertEquals(mapOf("enabled" to true), override["BetterSlidingFindSwipeForReset"])
        val param =
            ((((override["BetterSlidingReset"] as Map<*, *>)["action"] as Map<*, *>)["param"]) as Map<*, *>)
        val end = param["end"] as List<*>
        assertEquals("BetterSlidingFindSwipeForReset", end[0])
        assertEquals(listOf(10, 700, 10, 10), end[1])
    }
}
