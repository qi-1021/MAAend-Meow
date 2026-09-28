package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * 任务周期判定测试（对齐上游 common/schedule/recognition.go）。
 *
 * 重点是 04:00 那个游戏日边界：凌晨 0~3 点必须算**前一天**。
 * 这一条错了，用户设成「周一到周五跑」会在周一凌晨两点被跳过（或反过来多跑），
 * 而现象只表现为「某天没跑」，很难联想到时区/边界。
 */
class ScheduleSupportTest {

    private fun flags(
        monday: Boolean = false, tuesday: Boolean = false, wednesday: Boolean = false,
        thursday: Boolean = false, friday: Boolean = false, saturday: Boolean = false,
        sunday: Boolean = false,
    ) = ScheduleSupport.WeekdayFlags(monday, tuesday, wednesday, thursday, friday, saturday, sunday)

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 9, day, hour, minute)

    @Test
    fun `gameWeekday 在 04 点前算前一天`() {
        // 2026-09-28 是周一
        assertEquals(DayOfWeek.MONDAY, ScheduleSupport.gameWeekday(at(28, 4)))
        assertEquals(DayOfWeek.MONDAY, ScheduleSupport.gameWeekday(at(28, 12)))
        assertEquals(DayOfWeek.MONDAY, ScheduleSupport.gameWeekday(at(28, 23, 59)))
        // 03:59 还算前一天（周日）
        assertEquals(DayOfWeek.SUNDAY, ScheduleSupport.gameWeekday(at(28, 3, 59)))
        assertEquals(DayOfWeek.SUNDAY, ScheduleSupport.gameWeekday(at(28, 0)))
    }

    @Test
    fun `gameWeekday 跨周边界正确`() {
        // 2026-09-28 周一 02:00 -> 属于上周日
        assertEquals(DayOfWeek.SUNDAY, ScheduleSupport.gameWeekday(at(28, 2)))
        // 2026-10-01 周四 03:00 -> 属于周三
        assertEquals(DayOfWeek.WEDNESDAY, ScheduleSupport.gameWeekday(LocalDateTime.of(2026, 10, 1, 3, 0)))
    }

    @Test
    fun `isEnabledOn 逐日判定`() {
        val f = flags(monday = true, sunday = true)
        assertEquals(true, ScheduleSupport.isEnabledOn(f, DayOfWeek.MONDAY))
        assertEquals(true, ScheduleSupport.isEnabledOn(f, DayOfWeek.SUNDAY))
        assertEquals(false, ScheduleSupport.isEnabledOn(f, DayOfWeek.TUESDAY))
        assertEquals(false, ScheduleSupport.isEnabledOn(f, DayOfWeek.SATURDAY))
    }

    @Test
    fun `parseFlags 从 attach 读取`() {
        val node = mapOf(
            "attach" to mapOf("monday" to true, "wednesday" to true, "friday" to false),
        )
        val f = ScheduleSupport.parseFlags(node)
        assertEquals(true, f?.monday)
        assertEquals(true, f?.wednesday)
        assertEquals(false, f?.friday)
        assertEquals(false, f?.tuesday)
        assertEquals(true, f?.anyEnabled())
    }

    @Test
    fun `parseFlags 缺 attach 得到全 false 而不是全开`() {
        // 上游在 attach 缺失时是零值（全 false）-> 当天不命中。
        // 这里若"贴心地"默认全开，用户设定的周期就完全失效了。
        val f = ScheduleSupport.parseFlags(mapOf("recognition" to mapOf("type" to "Custom")))
        assertEquals(false, f?.anyEnabled())
        assertEquals(false, ScheduleSupport.isEnabledOn(f!!, DayOfWeek.MONDAY))
    }

    @Test
    fun `parseFlags 节点定义无效时返回 null`() {
        assertNull(ScheduleSupport.parseFlags(null))
        assertNull(ScheduleSupport.parseFlags("不是对象"))
    }

    @Test
    fun `parseFlags 忽略非布尔值`() {
        val node = mapOf("attach" to mapOf("monday" to "true", "tuesday" to 1, "sunday" to true))
        val f = ScheduleSupport.parseFlags(node)
        assertEquals(false, f?.monday)
        assertEquals(false, f?.tuesday)
        assertEquals(true, f?.sunday)
    }

    @Test
    fun `默认七天全开与恒真等价`() {
        val all = flags(true, true, true, true, true, true, true)
        for (d in DayOfWeek.entries) {
            assertEquals("$d 应命中", true, ScheduleSupport.isEnabledOn(all, d))
        }
    }
}
