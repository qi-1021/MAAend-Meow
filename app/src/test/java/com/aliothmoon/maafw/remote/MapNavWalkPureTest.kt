package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * [MapNavWalkPlanner] / [MapNavWalkSession] 状态机测试：用「一串定位结果」驱动，覆盖
 * 直行到点、需要转向、定位丢失后重定位、超时失败、多路点串联。
 *
 * 注入的 [TestControl] 是自足的纯实现（不依赖并行 lane 的 `MapNavControlPure`），
 * 故本测试可在 `scripts/verify_pure_logic.sh` 独立编译运行。
 */
class MapNavWalkPureTest {

    /** 自足的控制端口：方位/归一化/到达用上游口径，转向用简单限幅 P（便于断言）。 */
    private class TestControl : NavWalkControl {
        override fun bearing(fromX: Double, fromY: Double, toX: Double, toY: Double): Double {
            var deg = Math.toDegrees(atan2(toX - fromX, -(toY - fromY)))
            if (deg < 0.0) deg += 360.0
            return (Math.round(deg) % 360L).toDouble()
        }

        override fun normalizeAngle(deg: Double): Double {
            var a = deg % 360.0
            if (a > 180.0) a -= 360.0
            else if (a <= -180.0) a += 360.0
            return a
        }

        override fun steerSwipeDelta(yawErrorDeg: Double): Double =
            yawErrorDeg.coerceIn(-90.0, 90.0) * 5.0

        override fun arrived(px: Double, py: Double, tx: Double, ty: Double, radius: Double): Boolean =
            hypot(px - tx, py - ty) <= radius
    }

    private fun fix(x: Double, y: Double, yaw: Double? = 0.0, accepted: Boolean = true) =
        NavFix(accepted = accepted, x = x, y = y, yawDeg = yaw)

    // ───────────────────── 直行到点 ─────────────────────

    @Test
    fun `直行到点先走再到达`() {
        val planner = MapNavWalkPlanner(NavWaypoint(100.0, 0.0, index = 0), TestControl())
        val t1 = planner.tick(fix(100.0, 100.0), 100)
        assertEquals(NavWalkAction.WALK, t1.action)
        assertEquals(0.0, t1.moveBearingDeg!!, 1e-9)
        assertEquals(100.0, t1.distance, 1e-9)
        assertEquals(0, t1.turnSwipePx)
        assertFalse(planner.finished)

        val t2 = planner.tick(fix(100.0, 50.0), 100)
        assertEquals(NavWalkAction.WALK, t2.action)
        assertEquals(50.0, t2.distance, 1e-9)

        // 进圈（默认 6px）：到点。
        val t3 = planner.tick(fix(100.0, 4.0), 100)
        assertEquals(NavWalkAction.ARRIVED, t3.action)
        assertTrue(planner.arrived)
        assertTrue(planner.finished)
    }

    // ───────────────────── 需要转向 ─────────────────────

    @Test
    fun `背对目标先原地转正不推杆`() {
        // 目标在正西(bearing 270)，相机朝东(yaw 90) → 误差 180° → TURN。
        val planner = MapNavWalkPlanner(NavWaypoint(-100.0, 0.0, index = 3), TestControl())
        val t = planner.tick(fix(0.0, 0.0, yaw = 90.0), 100)
        assertEquals(NavWalkAction.TURN, t.action)
        assertNull(t.moveBearingDeg)
        // 转向被单拍上限 300px 截断，方向为正（顺时针）。
        assertEquals(300, t.turnSwipePx)
        assertEquals(180.0, t.yawErrorDeg!!, 1e-9)
        assertEquals(3, t.waypointIndex)
    }

    @Test
    fun `偏右目标边走边正`() {
        // 目标在正东(bearing 90)，相机朝北(yaw 0) → 误差 +90 → 仍走（<=90），带右转。
        val planner = MapNavWalkPlanner(NavWaypoint(100.0, 0.0), TestControl())
        val t = planner.tick(fix(0.0, 0.0, yaw = 0.0), 100)
        assertEquals(NavWalkAction.WALK, t.action)
        assertEquals(90.0, t.moveBearingDeg!!, 1e-9)
        assertTrue(t.turnSwipePx > 0)
    }

    @Test
    fun `朝向缺失按丢失处理`() {
        val planner = MapNavWalkPlanner(NavWaypoint(100.0, 0.0), TestControl())
        val t = planner.tick(fix(10.0, 10.0, yaw = null), 100)
        assertEquals(NavWalkAction.HOLD, t.action)
        assertEquals(1, t.lostStreak)
    }

    // ───────────────────── 定位丢失后重定位 ─────────────────────

    @Test
    fun `连续丢失触发重定位再用尽失败`() {
        val cfg = NavWalkConfig(
            lostTicksBeforeRelocate = 3,
            relocateTicks = 2,
            maxRelocateAttempts = 1,
        )
        val planner = MapNavWalkPlanner(NavWaypoint(100.0, 0.0), TestControl(), cfg)
        val lost = fix(0.0, 0.0, accepted = false)

        assertEquals(NavWalkAction.HOLD, planner.tick(lost, 100).action)
        assertEquals(NavWalkAction.HOLD, planner.tick(lost, 100).action)
        val r1 = planner.tick(lost, 100)
        assertEquals(NavWalkAction.RELOCATE, r1.action)
        assertEquals(1, r1.relocateAttempts)
        assertEquals(NavWalkAction.RELOCATE, planner.tick(lost, 100).action)
        assertEquals(NavWalkAction.HOLD, planner.tick(lost, 100).action)
        assertEquals(NavWalkAction.HOLD, planner.tick(lost, 100).action)
        val failed = planner.tick(lost, 100)
        assertEquals(NavWalkAction.FAILED, failed.action)
        assertTrue(planner.finished)
        assertFalse(planner.arrived)
    }

    @Test
    fun `重定位期间恢复定位即清状态继续走`() {
        val cfg = NavWalkConfig(lostTicksBeforeRelocate = 2, relocateTicks = 3, maxRelocateAttempts = 1)
        val planner = MapNavWalkPlanner(NavWaypoint(100.0, 0.0), TestControl(), cfg)
        assertEquals(NavWalkAction.HOLD, planner.tick(fix(0.0, 0.0, accepted = false), 100).action)
        assertEquals(NavWalkAction.RELOCATE, planner.tick(fix(0.0, 0.0, accepted = false), 100).action)
        // 中途恢复：丢失计数与重定位窗口清零。
        val recovered = planner.tick(fix(100.0, 80.0, yaw = 0.0), 100)
        assertEquals(NavWalkAction.WALK, recovered.action)
        assertEquals(0, recovered.lostStreak)
        assertEquals(0, recovered.relocateAttempts)
    }

    // ───────────────────── 超时失败 ─────────────────────

    @Test
    fun `单路点超时失败`() {
        val cfg = NavWalkConfig(waypointTimeoutMs = 1000)
        val planner = MapNavWalkPlanner(NavWaypoint(100.0, 0.0), TestControl(), cfg)
        assertEquals(NavWalkAction.WALK, planner.tick(fix(0.0, 0.0, yaw = 0.0), 600).action)
        val t = planner.tick(fix(0.0, 0.0, yaw = 0.0), 600)
        assertEquals(NavWalkAction.FAILED, t.action)
        assertTrue(planner.finished)
    }

    // ───────────────────── 多路点串联 ─────────────────────

    @Test
    fun `多路点串联到点自动整下一个`() {
        val session = MapNavWalkSession(
            listOf(
                NavWaypoint(100.0, 0.0, index = 0, label = "a"),
                NavWaypoint(0.0, 100.0, index = 1, label = "b"),
            ),
            TestControl(),
        )
        assertFalse(session.finished)
        assertEquals(0, session.currentIndex)

        val first = session.tick(fix(100.0, 0.0), 100)
        assertEquals(NavWalkAction.ARRIVED, first.action)
        assertEquals(0, first.waypointIndex)
        assertEquals(1, session.currentIndex)
        assertFalse(session.finished)

        val second = session.tick(fix(0.0, 100.0), 100)
        assertEquals(NavWalkAction.ARRIVED, second.action)
        assertEquals(1, second.waypointIndex)
        assertTrue(session.finished)
        assertEquals(2, session.arrivedCount)
    }

    @Test
    fun `空会话视为已完成`() {
        val session = MapNavWalkSession(emptyList(), TestControl())
        assertTrue(session.finished)
        assertEquals(NavWalkAction.FAILED, session.tick(fix(0.0, 0.0), 100).action)
    }
}
