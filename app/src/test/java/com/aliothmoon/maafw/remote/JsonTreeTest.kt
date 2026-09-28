package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JSON 树序列化与其往返稳定性的测试。
 *
 * BetterSliding 的所有流水线覆盖都要经 [JsonTree.toJson] 变成字符串交给 MaaFramework，
 * 转义或数字格式写错就会把覆盖送进一个不存在的节点（静默失效）。
 * 这里同时锁住「键顺序保持」和「序列化→解析→再序列化 幂等」。
 */
class JsonTreeTest {

    @Test
    fun `基本类型`() {
        assertEquals("null", JsonTree.toJson(null))
        assertEquals("true", JsonTree.toJson(true))
        assertEquals("false", JsonTree.toJson(false))
        assertEquals("42", JsonTree.toJson(42))
        assertEquals("42", JsonTree.toJson(42L))
        assertEquals("\"abc\"", JsonTree.toJson("abc"))
    }

    @Test
    fun `浮点整数不带小数点 非整数保留`() {
        assertEquals("3", JsonTree.toJson(3.0))
        assertEquals("3", JsonTree.toJson(3.0f))
        assertEquals("2.5", JsonTree.toJson(2.5))
    }

    @Test
    fun `非有限浮点退化成 null`() {
        assertEquals("null", JsonTree.toJson(Double.NaN))
        assertEquals("null", JsonTree.toJson(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `嵌套 map 与 list 保持插入顺序`() {
        val tree = linkedMapOf<String, Any?>(
            "b" to 1,
            "a" to listOf(1, 2),
            "c" to linkedMapOf("x" to true),
        )
        assertEquals("""{"b":1,"a":[1,2],"c":{"x":true}}""", JsonTree.toJson(tree))
    }

    @Test
    fun `字符串按 JSON 规范转义`() {
        assertEquals("\"a\\\"b\"", JsonTree.toJson("a\"b"))
        assertEquals("\"a\\\\b\"", JsonTree.toJson("a\\b"))
        assertEquals("\"a\\nb\"", JsonTree.toJson("a\nb"))
        assertEquals("\"a\\tb\"", JsonTree.toJson("a\tb"))
        assertEquals("\"a\\u0001b\"", JsonTree.toJson("a\u0001b"))
        // 中文不转义（保证和上游 json.Marshal 的可见字符行为一致）
        assertEquals("\"四号谷地\"", JsonTree.toJson("四号谷地"))
    }

    @Test
    fun `map 的键会转义`() {
        assertEquals("""{"a\"b":1}""", JsonTree.toJson(mapOf("a\"b" to 1)))
    }

    @Test
    fun `序列化解析再序列化是幂等的`() {
        val tree = linkedMapOf<String, Any?>(
            "BetterSlidingSwipeToMax" to linkedMapOf(
                "action" to linkedMapOf(
                    "param" to linkedMapOf(
                        "end" to listOf("BetterSlidingFindStart", listOf(1260, 10, 10, 10)),
                    ),
                ),
            ),
            "BetterSlidingGetSliderQuantity" to linkedMapOf(
                "recognition" to linkedMapOf(
                    "param" to linkedMapOf(
                        "roi" to listOf(303, 516, 111, 47),
                        "only_rec" to true,
                        "color_filter" to "BetterSlidingSliderQuantityFilter",
                    ),
                ),
            ),
            "note" to "含中文与\"引号\"",
        )
        val once = JsonTree.toJson(tree)
        val twice = JsonTree.toJson(MaaJsonTree.parse(once))
        assertEquals(once, twice)
    }

    @Test
    fun `MaaJsonTree 解析失败返回 null 而不是抛异常`() {
        assertEquals(null, MaaJsonTree.parse("这不是 json"))
        assertEquals(null, MaaJsonTree.parse(""))
        assertEquals(null, MaaJsonTree.parse(null))
    }

    @Test
    fun `MaaJsonTree 保留字符串与数字的区别`() {
        // 带引号的 "3" 必须仍是字符串，否则 attach 里写错类型会被静默接受
        assertEquals("3", MaaJsonTree.parse("""{"v":"3"}""").let { (it as Map<*, *>)["v"] })
        assertEquals(3L, MaaJsonTree.parse("""{"v":3}""").let { (it as Map<*, *>)["v"] })
        assertEquals(true, MaaJsonTree.parse("""{"v":true}""").let { (it as Map<*, *>)["v"] })
        assertEquals(null, MaaJsonTree.parse("""{"v":null}""").let { (it as Map<*, *>)["v"] })
    }
}
