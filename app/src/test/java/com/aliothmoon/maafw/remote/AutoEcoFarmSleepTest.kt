package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.AutoEcoFarmSleep.Param
import com.aliothmoon.maafw.remote.AutoEcoFarmSleep.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * autoEcoFarmInterruptibleSleep 测试（对齐上游 interruptiblesleep.go）。
 *
 * 重点是把「分片粒度」与「倒计时报告阈值」钉住：报告条件是 `remaining <= nextReport`，
 * 且 nextReport 初始为 `duration - interval`，所以 interval 不小于 duration 时不会报告。
 */
class AutoEcoFarmSleepTest {

    @Test
    fun `空或非法参数判失败`() {
        assertNull(AutoEcoFarmSleep.parseParam(null))
        assertNull(AutoEcoFarmSleep.parseParam(""))
        assertNull(AutoEcoFarmSleep.parseParam("   "))
        assertNull(AutoEcoFarmSleep.parseParam("不是 json"))
        assertNull(AutoEcoFarmSleep.parseParam("""{"durationMs":"60000"}"""))
    }

    @Test
    fun `字段缺失用零`() {
        assertEquals(Param(0, 0), AutoEcoFarmSleep.parseParam("{}"))
        assertEquals(Param(60_000, 0), AutoEcoFarmSleep.parseParam("""{"durationMs":60000}"""))
        assertEquals(
            Param(60_000, 5_000),
            AutoEcoFarmSleep.parseParam("""{"durationMs":60000,"reportIntervalMs":5000}"""),
        )
    }

    @Test
    fun `非正时长返回空计划`() {
        assertTrue(AutoEcoFarmSleep.plan(0, 5_000).isEmpty())
        assertTrue(AutoEcoFarmSleep.plan(-100, 5_000).isEmpty())
    }

    @Test
    fun `按 250ms 切片且总时长守恒`() {
        val segments = AutoEcoFarmSleep.plan(1_000, 5_000)
        assertEquals(4, segments.size)
        assertTrue(segments.all { it.chunkMs == 250L && it.reportRemainingMs == null })
        assertEquals(1_000L, segments.sumOf { it.chunkMs })
    }

    @Test
    fun `最后一片不足 250ms`() {
        val segments = AutoEcoFarmSleep.plan(300, 5_000)
        assertEquals(listOf(Segment(250, null), Segment(50, null)), segments)
    }

    @Test
    fun `跨过报告阈值时在睡眠前报告剩余 time`() {
        // duration 6000，interval 5000 -> 首次报告点在 remaining=1000
        val segments = AutoEcoFarmSleep.plan(6_000, 5_000)
        assertEquals(24, segments.size)
        val reports = segments.filter { it.reportRemainingMs != null }
        assertEquals(1, reports.size)
        assertEquals(1_000L, reports[0].reportRemainingMs)
        // 报告发生在 remaining=1000 对应的那一片
        assertEquals(20, segments.indexOfFirst { it.reportRemainingMs == 1_000L })
    }

    @Test
    fun `报告间隔非正时用默认 5000`() {
        assertEquals(AutoEcoFarmSleep.plan(6_000, 5_000), AutoEcoFarmSleep.plan(6_000, 0))
    }

    @Test
    fun `间隔不小于时长时不报告`() {
        val segments = AutoEcoFarmSleep.plan(5_000, 5_000)
        assertEquals(20, segments.size)
        assertTrue(segments.all { it.reportRemainingMs == null })
    }

    @Test
    fun `倒计时格式 mm_ss`() {
        assertEquals("00:00", AutoEcoFarmSleep.formatRemaining(0))
        assertEquals("00:01", AutoEcoFarmSleep.formatRemaining(1_000))
        assertEquals("01:00", AutoEcoFarmSleep.formatRemaining(60_000))
        assertEquals("01:05", AutoEcoFarmSleep.formatRemaining(65_000))
        assertEquals("02:05", AutoEcoFarmSleep.formatRemaining(125_000))
    }
}
