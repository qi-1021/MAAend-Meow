package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.AutoEcoFarmNearest.Param
import com.aliothmoon.maafw.remote.AutoEcoFarmNearest.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * autoEcoFarmFindNearestRecognitionResult 测试（对齐上游 findNearestMaaResult.go）。
 *
 * 重点钉住两件错了只表现为「点错田块」的事：
 *  1. 边界插值用的是各框的**左上角**（不是中心）；
 *  2. 只在 `filtered` 结果里挑，最近判定才用框中心。
 */
class AutoEcoFarmNearestTest {

    private fun box(x: Int, y: Int, w: Int, h: Int) = mapOf("box" to listOf(x, y, w, h))

    private fun filtered(vararg results: Any?) = mapOf("filtered" to results.toList())

    // ───────────────────── 参数解析与校验 ─────────────────────

    @Test
    fun `空或缺失参数用默认`() {
        assertEquals(Param.DEFAULT, AutoEcoFarmNearest.parseParam(null))
        assertEquals(Param.DEFAULT, AutoEcoFarmNearest.parseParam(""))
        assertEquals(Param.DEFAULT, AutoEcoFarmNearest.parseParam("{}"))
    }

    @Test
    fun `正常参数解析`() {
        val p = AutoEcoFarmNearest.parseParam(
            """{"recognitionNodeName":"_AutoEcoFarmFindFarmland","xRatio":0.5,"yRatio":0.25}""",
        )
        assertEquals(Param("_AutoEcoFarmFindFarmland", 0.5, 0.25), p)
    }

    @Test
    fun `类型不对或非法 JSON 判失败`() {
        assertNull(AutoEcoFarmNearest.parseParam("""{"recognitionNodeName":1}"""))
        assertNull(AutoEcoFarmNearest.parseParam("""{"xRatio":"0.5"}"""))
        assertNull(AutoEcoFarmNearest.parseParam("不是 json"))
    }

    @Test
    fun `校验要求名字非空且比例在 0 到 1`() {
        assertFalse(AutoEcoFarmNearest.validate(Param("", 0.5, 0.5)))
        assertFalse(AutoEcoFarmNearest.validate(Param("N", -0.01, 0.5)))
        assertFalse(AutoEcoFarmNearest.validate(Param("N", 0.5, 1.01)))
        assertTrue(AutoEcoFarmNearest.validate(Param("N", 0.0, 1.0)))
    }

    // ───────────────────── 最近结果选取 ─────────────────────

    @Test
    fun `取中心离插值点最近的框`() {
        // 两个框左上角 (0,0) 与 (100,0)，包络 minX=0 maxX=100
        // ratio 0.5 -> 目标 (50,0)；中心 5 比 105 更近 -> 选前者
        val picked = AutoEcoFarmNearest.pickNearest(
            filtered(box(0, 0, 10, 10), box(100, 0, 10, 10)),
            0.5, 0.5,
        )
        assertEquals(Rect(0, 0, 10, 10), picked)
    }

    @Test
    fun `比例为 1 时取包络右端的框`() {
        val picked = AutoEcoFarmNearest.pickNearest(
            filtered(box(0, 0, 10, 10), box(100, 0, 10, 10)),
            1.0, 0.5,
        )
        assertEquals(Rect(100, 0, 10, 10), picked)
    }

    @Test
    fun `边界插值用左上角而不是中心`() {
        // A 左上 (0,0) 尺寸 100 -> 中心 50；B 左上 (200,0) 尺寸 10 -> 中心 205。
        // 用左上角插值：min=0 max=200 -> 目标 (100,0)，A 更近。
        // 若误用中心插值：min=50 max=205 -> 目标 127.5，B 更近 —— 结果会相反。
        val picked = AutoEcoFarmNearest.pickNearest(
            filtered(box(0, 0, 100, 100), box(200, 0, 10, 10)),
            0.5, 0.5,
        )
        assertEquals(Rect(0, 0, 100, 100), picked)
    }

    @Test
    fun `filtered 优先于 all 与 best`() {
        val tree = mapOf(
            "filtered" to listOf(box(0, 0, 10, 10)),
            "all" to listOf(box(500, 0, 10, 10)),
            "best" to box(400, 0, 10, 10),
        )
        assertEquals(Rect(0, 0, 10, 10), AutoEcoFarmNearest.pickNearest(tree, 0.5, 0.5))
    }

    @Test
    fun `结构不符的项被跳过`() {
        val picked = AutoEcoFarmNearest.pickNearest(
            filtered(mapOf("box" to listOf(1, 2)), box(10, 10, 4, 4)),
            0.5, 0.5,
        )
        assertEquals(Rect(10, 10, 4, 4), picked)
    }

    @Test
    fun `空结果或空树返回 null`() {
        assertNull(AutoEcoFarmNearest.pickNearest(filtered(), 0.5, 0.5))
        assertNull(AutoEcoFarmNearest.pickNearest(null, 0.5, 0.5))
        assertNull(AutoEcoFarmNearest.pickNearest("不是树", 0.5, 0.5))
    }

    @Test
    fun `没有 filtered 键时退回递归收集`() {
        // 防御分支：形态意外不同时用 BetterSlidingOcr 的通用遍历兜底
        val tree = mapOf("all" to listOf(box(0, 0, 10, 10), box(100, 0, 10, 10)))
        assertEquals(Rect(0, 0, 10, 10), AutoEcoFarmNearest.pickNearest(tree, 0.5, 0.5))
    }
}
