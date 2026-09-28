package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `AutoStockStapleQuantityControlAction` 纯逻辑测试（对齐上游 autostockstaple/action.go）。
 *
 * 表达式形态取自真实资产（General/GoodsCountValidate.json）：
 * `"20 > {AutoStockStapleGoodsCountValidate}"` —— 阈值 20 是字面整数，
 * 计数节点是那个共享的 And 识别（Area + Color + Text，box_index=2）。
 */
class AutoStockStapleSupportTest {

    private fun rejects(block: () -> Any?) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `parseParam 要求 item_name 或 validator_node 至少给一个`() {
        assertEquals(
            AutoStockStapleSupport.Param("谷地刻写券", "", ""),
            AutoStockStapleSupport.parseParam(mapOf("item_name" to "谷地刻写券")),
        )
        assertEquals(
            AutoStockStapleSupport.Param("", "SomeValidate", ""),
            AutoStockStapleSupport.parseParam(mapOf("validator_node" to "SomeValidate")),
        )
        assertNull(AutoStockStapleSupport.parseParam(mapOf("item_name" to "   ")))
        assertNull(AutoStockStapleSupport.parseParam(mapOf("item_name" to "", "validator_node" to "")))
        assertNull(AutoStockStapleSupport.parseParam("不是对象"))
        assertNull(AutoStockStapleSupport.parseParam(null))
    }

    @Test
    fun `parseParam 会 trim 三个字段`() {
        val p = AutoStockStapleSupport.parseParam(
            mapOf("item_name" to "  a_b  ", "validator_node" to " V ", "sliding_node" to " S "),
        )
        assertEquals(AutoStockStapleSupport.Param("a_b", "V", "S"), p)
    }

    @Test
    fun `buildValidatorNodeName 按分隔符分词并首字母大写`() {
        assertEquals(
            "AutoStockStapleGoodsValleyEngravingPermitValidate",
            AutoStockStapleSupport.buildValidatorNodeName("valley_engraving_permit"),
        )
        assertEquals(
            "AutoStockStapleGoodsValleyIvOreValidate",
            AutoStockStapleSupport.buildValidatorNodeName("valley-iv ore"),
        )
        // 无分隔符时整段当一个词
        assertEquals(
            "AutoStockStapleGoodsKunstTubeValidate",
            AutoStockStapleSupport.buildValidatorNodeName("kunstTube"),
        )
        assertEquals("", AutoStockStapleSupport.buildValidatorNodeName("   "))
    }

    @Test
    fun `parseValidatorExpression 解析真实表达式`() {
        val (threshold, countNode) =
            AutoStockStapleSupport.parseValidatorExpression("20 > {AutoStockStapleGoodsCountValidate}")
        assertEquals(20, threshold)
        assertEquals("AutoStockStapleGoodsCountValidate", countNode)
    }

    @Test
    fun `parseValidatorExpression 支持负数阈值与空格占位符`() {
        val (threshold, countNode) =
            AutoStockStapleSupport.parseValidatorExpression("  -50 <= {  SomeCountNode  } ")
        assertEquals(-50, threshold)
        assertEquals("SomeCountNode", countNode)
    }

    @Test
    fun `parseValidatorExpression 多占位符时取第一个 与上游一致`() {
        val (threshold, countNode) =
            AutoStockStapleSupport.parseValidatorExpression("{First} > {Second} + 7")
        assertEquals(7, threshold)
        assertEquals("First", countNode)
    }

    @Test
    fun `parseValidatorExpression 占位符在前也能取到后面那个整数`() {
        val (threshold, countNode) = AutoStockStapleSupport.parseValidatorExpression("{OnlyNode} > 3")
        assertEquals(3, threshold)
        assertEquals("OnlyNode", countNode)
    }

    @Test
    fun `parseValidatorExpression 缺少整数或占位符要报错`() {
        // 两个占位符、没有字面整数 -> 找不到阈值
        rejects { AutoStockStapleSupport.parseValidatorExpression("{Limit} > {AutoStockStapleGoodsCountValidate}") }
        // 有整数但没有占位符
        rejects { AutoStockStapleSupport.parseValidatorExpression("20 > 10") }
        rejects { AutoStockStapleSupport.parseValidatorExpression("{}") }
        rejects { AutoStockStapleSupport.parseValidatorExpression("") }
    }

    @Test
    fun `resolveValidatorSpec 从节点定义里取表达式`() {
        val node = mapOf(
            "recognition" to mapOf(
                "type" to "Custom",
                "param" to mapOf(
                    "custom_recognition" to "ExpressionRecognition",
                    "custom_recognition_param" to mapOf(
                        "expression" to "20 > {AutoStockStapleGoodsCountValidate}",
                    ),
                ),
            ),
        )
        val spec = AutoStockStapleSupport.resolveValidatorSpec(node)
        assertEquals(20, spec?.threshold)
        assertEquals("AutoStockStapleGoodsCountValidate", spec?.countNode)
        assertEquals("20 > {AutoStockStapleGoodsCountValidate}", spec?.expression)
    }

    @Test
    fun `resolveValidatorSpec 表达式缺失或路径不对时返回 null`() {
        assertNull(AutoStockStapleSupport.resolveValidatorSpec(null))
        assertNull(AutoStockStapleSupport.resolveValidatorSpec(mapOf("recognition" to emptyMap<String, Any?>())))
        assertNull(
            AutoStockStapleSupport.resolveValidatorSpec(
                mapOf("recognition" to mapOf("param" to mapOf("custom_recognition_param" to mapOf("expression" to "  ")))),
            ),
        )
    }

    @Test
    fun `buildQuantityControlOverride 按目标正负决定是否启用`() {
        assertEquals(
            mapOf(
                "AutoStockStapleBetterSliding" to mapOf(
                    "enabled" to true,
                    "attach" to mapOf("TargetQuantity" to 7),
                ),
            ),
            AutoStockStapleSupport.buildQuantityControlOverride("AutoStockStapleBetterSliding", 7),
        )
        // 目标 <= 0 时禁用，避免又一次无意义的滑动
        assertEquals(
            mapOf(
                "N" to mapOf("enabled" to false, "attach" to mapOf("TargetQuantity" to 0)),
            ),
            AutoStockStapleSupport.buildQuantityControlOverride("N", 0),
        )
    }

    @Test
    fun `firstIntegerOfText 取第一个整数`() {
        assertEquals(42, AutoStockStapleSupport.firstIntegerOfText("持有 42 / 99"))
        assertEquals(-3, AutoStockStapleSupport.firstIntegerOfText("x -3 y 8"))
        assertNull(AutoStockStapleSupport.firstIntegerOfText("没有数字"))
    }

    @Test
    fun `findFirstOcrText 优先自身文本`() {
        val detail = BetterSlidingOcr.Detail(
            name = "root",
            text = "128",
            box = listOf(1, 2, 3, 4),
            children = listOf(BetterSlidingOcr.Detail(name = "child", text = "999")),
        )
        assertEquals("128", AutoStockStapleSupport.findFirstOcrText(detail))
    }

    @Test
    fun `findFirstOcrText 组合识别时用包围盒相同的子节点`() {
        // And{Area, Color, Text} box_index=2 -> 根的 box 等于 Text 子节点的 box
        val detail = BetterSlidingOcr.Detail(
            name = "AutoStockStapleGoodsCountValidate",
            text = null,
            box = listOf(10, 20, 30, 40),
            children = listOf(
                BetterSlidingOcr.Detail(name = "Area", text = null, box = listOf(0, 0, 100, 100)),
                BetterSlidingOcr.Detail(name = "Color", text = null, box = listOf(5, 5, 90, 90)),
                BetterSlidingOcr.Detail(name = "Text", text = "42", box = listOf(10, 20, 30, 40)),
            ),
        )
        assertEquals("42", AutoStockStapleSupport.findFirstOcrText(detail))
    }

    @Test
    fun `findFirstOcrText 取不到时返回 null`() {
        assertNull(AutoStockStapleSupport.findFirstOcrText(null))
        assertNull(AutoStockStapleSupport.findFirstOcrText(BetterSlidingOcr.Detail(name = "root")))
        // 有 box 但没有 box 相同的子节点
        assertNull(
            AutoStockStapleSupport.findFirstOcrText(
                BetterSlidingOcr.Detail(
                    name = "root",
                    box = listOf(1, 2, 3, 4),
                    children = listOf(BetterSlidingOcr.Detail(name = "c", text = "5", box = listOf(9, 9, 9, 9))),
                ),
            ),
        )
    }
}
