package com.aliothmoon.maafw.remote

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * 任务执行周期判定（对齐上游 common/schedule/recognition.go）。
 *
 * `ScheduleRecognition` 从调用节点的 `attach` 读 monday..sunday 开关，
 * 命中当前游戏日才放行任务。之前在移动端注册成恒真，等于「用户设了只在周末跑也照跑」。
 *
 * 游戏日的定义是**从本地时间 04:00 起算**：凌晨 0~3 点算前一天。
 * 这不是拍脑袋——每日重置在 4 点，凌晨两点「今天」的任务其实属于昨天。
 */
object ScheduleSupport {

    /** 上游 recognition.go:23 `gameDayBoundaryHour`。 */
    const val GAME_DAY_BOUNDARY_HOUR = 4

    /** 上游 recognition.go:16 `weekdayFlags`；缺字段即 false（上游是值类型，零值为 false）。 */
    data class WeekdayFlags(
        val monday: Boolean = false,
        val tuesday: Boolean = false,
        val wednesday: Boolean = false,
        val thursday: Boolean = false,
        val friday: Boolean = false,
        val saturday: Boolean = false,
        val sunday: Boolean = false,
    ) {
        fun anyEnabled(): Boolean =
            monday || tuesday || wednesday || thursday || friday || saturday || sunday
    }

    /**
     * 上游 recognition.go:88 `loadWeekdayFlagsFromNode`。
     *
     * 读节点定义里的 `attach` 块。返回 null 表示节点定义本身取不到/不是对象
     * （上游此时记 error 并视为未命中）；`attach` 缺失则得到全 false 的 flags，
     * 于是当天不命中——这与上游一致，不要「贴心地」默认成全开。
     */
    fun parseFlags(nodeTree: Any?): WeekdayFlags? {
        val node = nodeTree as? Map<*, *> ?: return null
        val attach = node["attach"] as? Map<*, *> ?: return WeekdayFlags()
        return WeekdayFlags(
            monday = boolOf(attach["monday"]),
            tuesday = boolOf(attach["tuesday"]),
            wednesday = boolOf(attach["wednesday"]),
            thursday = boolOf(attach["thursday"]),
            friday = boolOf(attach["friday"]),
            saturday = boolOf(attach["saturday"]),
            sunday = boolOf(attach["sunday"]),
        )
    }

    /** 上游 recognition.go:26 `gameWeekday`：04:00 前算前一天。 */
    fun gameWeekday(now: LocalDateTime, boundaryHour: Int = GAME_DAY_BOUNDARY_HOUR): DayOfWeek =
        if (now.hour < boundaryHour) now.minusDays(1).dayOfWeek else now.dayOfWeek

    /** 上游 recognition.go:120 `isEnabledOn`。 */
    fun isEnabledOn(flags: WeekdayFlags, day: DayOfWeek): Boolean = when (day) {
        DayOfWeek.MONDAY -> flags.monday
        DayOfWeek.TUESDAY -> flags.tuesday
        DayOfWeek.WEDNESDAY -> flags.wednesday
        DayOfWeek.THURSDAY -> flags.thursday
        DayOfWeek.FRIDAY -> flags.friday
        DayOfWeek.SATURDAY -> flags.saturday
        DayOfWeek.SUNDAY -> flags.sunday
    }

    private fun boolOf(value: Any?): Boolean = value as? Boolean ?: false
}
