package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 严格规范化 OCR 匹配测试（对齐上游 internal/ocrmatch/match.go）。
 *
 * 这一层的关键不是"能匹配上"，而是**不能误配**：据点交易会按匹配结果点干员、
 * 算加成档位，误配的代价是把错的干员派驻到错的岗位。所以这里重点覆盖
 * 「Tier B 只剥 ASCII 噪声、不做包含匹配」这两条。
 */
class OperatorOcrMatchTest {

    private fun item(text: String, x: Int = 0, y: Int = 0) =
        OperatorOcrMatch.Item(text, OcrBox(x, y, 10, 10))

    @Test
    fun `StripSeparators 剥分隔符与空白并统一小写`() {
        assertEquals("abcd", OperatorOcrMatch.stripSeparators("  A-B_C.D  "))
        assertEquals("紫晶质瓶", OperatorOcrMatch.stripSeparators("【紫晶·质瓶】".replace("【", "[").replace("】", "]")))
        assertEquals("abcd", OperatorOcrMatch.stripSeparators("a|b:c;d"))
        assertEquals("", OperatorOcrMatch.stripSeparators("   "))
        // 保留字母/数字/CJK
        assertEquals("a1紫", OperatorOcrMatch.stripSeparators(" a1紫 "))
    }

    @Test
    fun `StripASCIIAlnum 在归一化基础上再剥 ASCII 字母数字`() {
        assertEquals("紫晶质瓶", OperatorOcrMatch.stripAsciiAlnum("I紫晶质瓶"))
        assertEquals("紫晶质瓶", OperatorOcrMatch.stripAsciiAlnum("紫晶质瓶"))
        // EN 名两侧字母都被剥掉，退化为 Tier A 的等价形式
        assertEquals("", OperatorOcrMatch.stripAsciiAlnum("abc123"))
    }

    @Test
    fun `findBest Tier A 严格相等`() {
        val items = listOf(item("紫晶质瓶", y = 100))
        val hit = OperatorOcrMatch.findBest(items, listOf("紫晶质瓶"))
        assertEquals("A", hit?.tier)
        assertEquals("紫晶质瓶", hit?.candidate)
    }

    @Test
    fun `findBest Tier A 容忍 EN 名多出方括号竖线`() {
        val items = listOf(item("[ Operator ]", y = 10))
        val hit = OperatorOcrMatch.findBest(items, listOf("Operator"))
        assertEquals("A", hit?.tier)
    }

    @Test
    fun `findBest Tier B 吃掉 CJK 名称里的 ASCII 噪声`() {
        // OCR 把 "I紫晶质瓶" 认出来，配置里是 "紫晶质瓶"
        val items = listOf(item("I紫晶质瓶", y = 10))
        val hit = OperatorOcrMatch.findBest(items, listOf("紫晶质瓶"))
        assertEquals("B", hit?.tier)
        assertEquals("紫晶质瓶", hit?.candidate)
        // 命中的是 OCR 原文，便于日志核对
        assertEquals("I紫晶质瓶", hit?.ocrText)
    }

    @Test
    fun `findBest 不做包含匹配 长的不会被短的吃掉`() {
        // 「优质柑实罐头」的 CJK 核心是「优质柑实罐头」，与「柑实罐头」不相等 -> 不该命中
        val items = listOf(item("优质柑实罐头", y = 10))
        assertNull(OperatorOcrMatch.findBest(items, listOf("柑实罐头")))
    }

    @Test
    fun `findBest 同分时按屏幕顺序取靠上的`() {
        val items = listOf(
            item("目标", x = 0, y = 500),
            item("目标", x = 0, y = 100),
        )
        val hit = OperatorOcrMatch.findBest(items, listOf("目标"))
        assertEquals(100, hit?.box?.y)
    }

    @Test
    fun `findBest Tier A 整轮优先于 Tier B`() {
        // 靠上的项只能走 Tier B，靠下的项能走 Tier A；上游是「先跑完整轮 A」，
        // 所以应当命中靠下的那个（Tier A），而不是靠上的 Tier B
        val items = listOf(
            item("I目标", x = 0, y = 10), // Tier B
            item("目标", x = 0, y = 900), // Tier A
        )
        val hit = OperatorOcrMatch.findBest(items, listOf("目标"))
        assertEquals("A", hit?.tier)
        assertEquals(900, hit?.box?.y)
    }

    @Test
    fun `findBest 空候选或空文本时不命中`() {
        assertNull(OperatorOcrMatch.findBest(emptyList(), listOf("目标")))
        assertNull(OperatorOcrMatch.findBest(listOf(item("目标")), emptyList()))
        assertNull(OperatorOcrMatch.findBest(listOf(item("   ")), listOf("目标")))
    }

    @Test
    fun `sortItemsByPosition 先按 Y 再按 X`() {
        val sorted = OperatorOcrMatch.sortItemsByPosition(
            listOf(
                item("c", x = 50, y = 100),
                item("b", x = 10, y = 100),
                item("a", x = 0, y = 10),
            ),
        )
        assertEquals(listOf("a", "b", "c"), sorted.map { it.text })
    }

    @Test
    fun `sortItemsByPosition 不改动入参`() {
        val original = listOf(item("b", y = 20), item("a", y = 10))
        OperatorOcrMatch.sortItemsByPosition(original)
        assertEquals(listOf("b", "a"), original.map { it.text })
    }
}
