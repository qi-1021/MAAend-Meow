package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapNavControlPure] 的纯逻辑回归（方位 / 归一化 / 转向 P / 摇杆 / 到达）。
 *
 * 对齐上游 `navi_math.cpp`、`steering_controller.cpp`、`navigation_state_machine.cpp`。
 */
class MapNavControlPureTest {

    private val eps = 1e-9

    // ───────────────── 方位 bearing（navi_math.cpp:9-18） ─────────────────

    @Test
    fun `方位 8 个方向`() {
        // 从原点 (0,0) 出发，屏幕坐标系 y 向下，故「北=0」的点在 (0,-1)。
        assertEquals(0.0, MapNavControlPure.bearing(0.0, 0.0, 0.0, -1.0), eps) // N
        assertEquals(45.0, MapNavControlPure.bearing(0.0, 0.0, 1.0, -1.0), eps) // NE
        assertEquals(90.0, MapNavControlPure.bearing(0.0, 0.0, 1.0, 0.0), eps) // E
        assertEquals(135.0, MapNavControlPure.bearing(0.0, 0.0, 1.0, 1.0), eps) // SE
        assertEquals(180.0, MapNavControlPure.bearing(0.0, 0.0, 0.0, 1.0), eps) // S
        assertEquals(225.0, MapNavControlPure.bearing(0.0, 0.0, -1.0, 1.0), eps) // SW
        assertEquals(270.0, MapNavControlPure.bearing(0.0, 0.0, -1.0, 0.0), eps) // W
        assertEquals(315.0, MapNavControlPure.bearing(0.0, 0.0, -1.0, -1.0), eps) // NW
    }

    @Test
    fun `方位 相对偏移`() {
        // 平移不改变方位
        assertEquals(90.0, MapNavControlPure.bearing(100.0, 100.0, 110.0, 100.0), eps)
        assertEquals(0.0, MapNavControlPure.bearing(100.0, 100.0, 100.0, 90.0), eps)
        // 与 MapNavHeading.estimateFromBgr 同式：实数不取整，atan2(3,-4) ≈ 143.1301°
        assertEquals(143.13010235415598, MapNavControlPure.bearing(0.0, 0.0, 3.0, 4.0), 1e-6)
    }

    @Test
    fun `方位 跨 0 与 360 边界`() {
        // 目标略偏北的东侧 → 约 0.57°，不出现负数
        assertEquals(0.57293869768355, MapNavControlPure.bearing(0.0, 0.0, 0.01, -1.0), 1e-6)
        // 目标略偏北的西侧 → 约 359.43°，不跳成负数
        assertEquals(359.42706130231645, MapNavControlPure.bearing(0.0, 0.0, -0.01, -1.0), 1e-6)
        // 正东/正西附近（y 有微小偏移）
        assertEquals(89.99427042, MapNavControlPure.bearing(0.0, 0.0, 1.0, -0.0001), 1e-4)
        assertEquals(269.99427042, MapNavControlPure.bearing(0.0, 0.0, -1.0, 0.0001), 1e-4)
    }

    // ───────────────── 归一化 normalizeAngle（navi_math.cpp:25-34） ─────────────────

    @Test
    fun `归一化 常规值`() {
        assertEquals(0.0, MapNavControlPure.normalizeAngle(0.0), eps)
        assertEquals(90.0, MapNavControlPure.normalizeAngle(90.0), eps)
        assertEquals(-90.0, MapNavControlPure.normalizeAngle(-90.0), eps)
        assertEquals(-170.0, MapNavControlPure.normalizeAngle(190.0), eps)
        assertEquals(170.0, MapNavControlPure.normalizeAngle(-190.0), eps)
        assertEquals(-90.0, MapNavControlPure.normalizeAngle(270.0), eps)
    }

    @Test
    fun `归一化 边界 180 与 -180`() {
        // 区间是 (-180, 180]：正 180 保留，负 180 折成 +180
        assertEquals(180.0, MapNavControlPure.normalizeAngle(180.0), eps)
        assertEquals(180.0, MapNavControlPure.normalizeAngle(-180.0), eps)
        assertEquals(180.0, MapNavControlPure.normalizeAngle(540.0), eps)
        assertEquals(180.0, MapNavControlPure.normalizeAngle(-540.0), eps)
    }

    @Test
    fun `归一化 整数圈归零`() {
        assertEquals(0.0, MapNavControlPure.normalizeAngle(360.0), eps)
        assertEquals(0.0, MapNavControlPure.normalizeAngle(-360.0), eps)
        assertEquals(0.0, MapNavControlPure.normalizeAngle(720.0), eps)
    }

    // ───────────────── 转向 P 控制 steerSwipeDelta（steering_controller.cpp:14-23,42-65） ─────────────────

    @Test
    fun `常量默认值对齐上游`() {
        val cfg = MapNavControlPure.NavControlConfig()
        assertEquals(6.6, cfg.headingDeadbandDeg, eps)
        assertEquals(90.0, cfg.movingMaxCmdDeg, eps)
        assertEquals(70.0, cfg.turningMaxCmdDeg, eps)
        assertEquals(1.0, cfg.kp, eps)
        assertEquals(150.0, cfg.turnLatchEnterDeg, eps)
        assertEquals(120.0, cfg.turnLatchExitDeg, eps)
        assertEquals(5.0, cfg.pixelsPerDegree, eps)
        assertTrue(cfg.movingForward)
        assertEquals(0, cfg.turnLatchSign)
    }

    @Test
    fun `死区内不转`() {
        val cfg = MapNavControlPure.NavControlConfig()
        assertEquals(0.0, MapNavControlPure.steerSwipeDelta(0.0, cfg), eps)
        assertEquals(0.0, MapNavControlPure.steerSwipeDelta(6.0, cfg), eps)
        assertEquals(0.0, MapNavControlPure.steerSwipeDelta(-6.5, cfg), eps)
        // 恰好等于死区不再是 `< 6.6`，P 项生效
        assertEquals(6.6 * 5.0, MapNavControlPure.steerSwipeDelta(6.6, cfg), eps)
    }

    @Test
    fun `比例项与像素换算`() {
        val cfg = MapNavControlPure.NavControlConfig()
        // 45° 未触限幅：45 * 1.0 * 5 = 225 px
        assertEquals(225.0, MapNavControlPure.steerSwipeDelta(45.0, cfg), eps)
        assertEquals(-225.0, MapNavControlPure.steerSwipeDelta(-45.0, cfg), eps)
    }

    @Test
    fun `移动中限幅 90 度`() {
        val cfg = MapNavControlPure.NavControlConfig(movingForward = true)
        assertEquals(90.0 * 5.0, MapNavControlPure.steerSwipeDelta(100.0, cfg), eps)
        // 未触发 latch 的大误差同样被限幅（100 < 120 不 latch，但 100 > 90 被夹）
        assertEquals(90.0 * 5.0, MapNavControlPure.steerSwipeDelta(100.0, cfg), eps)
    }

    @Test
    fun `原地转向限幅 70 度`() {
        val cfg = MapNavControlPure.NavControlConfig(movingForward = false)
        assertEquals(70.0 * 5.0, MapNavControlPure.steerSwipeDelta(100.0, cfg), eps)
        assertEquals(-70.0 * 5.0, MapNavControlPure.steerSwipeDelta(-100.0, cfg), eps)
    }

    @Test
    fun `掉头 latch 触发并保持方向`() {
        val cfg = MapNavControlPure.NavControlConfig()
        // |160| >= 150 → 锁定 +1
        assertEquals(90.0 * 5.0, MapNavControlPure.steerSwipeDelta(160.0, cfg), eps)
        assertEquals(1, cfg.turnLatchSign)
        // 误差翻到 -130（|130| >= 120，不解除）：latch 把方向钉在 +，仍向右转
        assertEquals(90.0 * 5.0, MapNavControlPure.steerSwipeDelta(-130.0, cfg), eps)
        assertEquals(1, cfg.turnLatchSign)
        // -160 也一样：latch 生效，符号被强制为 +
        assertEquals(90.0 * 5.0, MapNavControlPure.steerSwipeDelta(-160.0, cfg), eps)
    }

    @Test
    fun `掉头 latch 进入与解开门限`() {
        // 恰好 150 触发
        val enter = MapNavControlPure.NavControlConfig()
        MapNavControlPure.steerSwipeDelta(150.0, enter)
        assertEquals(1, enter.turnLatchSign)
        // 149.9 不触发
        val below = MapNavControlPure.NavControlConfig()
        MapNavControlPure.steerSwipeDelta(149.9, below)
        assertEquals(0, below.turnLatchSign)

        // 恰好 120 不解除（条件是 `< 120`）
        val atExit = MapNavControlPure.NavControlConfig(turnLatchSign = 1)
        MapNavControlPure.steerSwipeDelta(120.0, atExit)
        assertEquals(1, atExit.turnLatchSign)
        // 119.9 解除
        val belowExit = MapNavControlPure.NavControlConfig(turnLatchSign = 1)
        MapNavControlPure.steerSwipeDelta(119.9, belowExit)
        assertEquals(0, belowExit.turnLatchSign)
    }

    @Test
    fun `latch 解除后误差可反向`() {
        val cfg = MapNavControlPure.NavControlConfig()
        // 先掉头锁定 +1
        MapNavControlPure.steerSwipeDelta(160.0, cfg)
        assertEquals(1, cfg.turnLatchSign)
        // |100| < 120 → 解除 latch
        assertEquals(90.0 * 5.0, MapNavControlPure.steerSwipeDelta(100.0, cfg), eps)
        assertEquals(0, cfg.turnLatchSign)
        // 解除后负误差不再被钉住，正常左转
        assertEquals(-90.0 * 5.0, MapNavControlPure.steerSwipeDelta(-100.0, cfg), eps)
        assertEquals(0, cfg.turnLatchSign)
    }

    @Test
    fun `issued 门限常量`() {
        assertEquals(2.0, MapNavControlPure.MIN_EMIT_CMD_DEG, eps)
    }

    // ───────────────── 摇杆向量 joystickVector ─────────────────

    /**
     * 相机空间自证：`yaw == bearing`（正对目标方向）时，摇杆方向就是该世界方位换算到屏幕的
     * 方向。四组分别对应 上 / 右 / 下 / 左；同时力度应为 1。
     */
    @Test
    fun `摇杆四方向 上右下左`() {
        val up = MapNavControlPure.joystickVector(0.0, 0.0)
        assertEquals(0.0, up.dx, eps)
        assertEquals(-1.0, up.dy, eps)
        assertEquals(1.0, up.force, eps)

        val right = MapNavControlPure.joystickVector(90.0, 90.0)
        assertEquals(1.0, right.dx, eps)
        assertEquals(0.0, right.dy, eps)
        assertEquals(1.0, right.force, eps)

        val down = MapNavControlPure.joystickVector(180.0, 180.0)
        assertEquals(0.0, down.dx, eps)
        assertEquals(1.0, down.dy, eps)
        assertEquals(1.0, down.force, eps)

        val left = MapNavControlPure.joystickVector(270.0, 270.0)
        assertEquals(-1.0, left.dx, eps)
        assertEquals(0.0, left.dy, eps)
        assertEquals(1.0, left.force, eps)
    }

    @Test
    fun `摇杆四象限 对角方向`() {
        val half = 0.7071067811865476
        val ne = MapNavControlPure.joystickVector(0.0, 45.0)
        assertEquals(half, ne.dx, 1e-6)
        assertEquals(-half, ne.dy, 1e-6)

        val se = MapNavControlPure.joystickVector(0.0, 135.0)
        assertEquals(half, se.dx, 1e-6)
        assertEquals(half, se.dy, 1e-6)

        val sw = MapNavControlPure.joystickVector(0.0, 225.0)
        assertEquals(-half, sw.dx, 1e-6)
        assertEquals(half, sw.dy, 1e-6)

        val nw = MapNavControlPure.joystickVector(0.0, 315.0)
        assertEquals(-half, nw.dx, 1e-6)
        assertEquals(-half, nw.dy, 1e-6)
    }

    @Test
    fun `方向是单位向量`() {
        for (bearing in 0..350 step 10) {
            val v = MapNavControlPure.joystickVector(0.0, bearing.toDouble())
            assertEquals(1.0, Math.hypot(v.dx, v.dy), 1e-9)
        }
    }

    @Test
    fun `正对目标力度等于 1`() {
        assertEquals(1.0, MapNavControlPure.joystickVector(123.0, 123.0).force, eps)
        // 跨 0/360 的等价朝向
        assertEquals(1.0, MapNavControlPure.joystickVector(0.0, 0.0).force, eps)
        assertEquals(1.0, MapNavControlPure.joystickVector(359.0, -1.0).force, eps)
    }

    @Test
    fun `背对目标力度归零`() {
        val back = MapNavControlPure.joystickVector(0.0, 180.0)
        assertEquals(0.0, back.dx, eps)
        assertEquals(1.0, back.dy, eps) // 方向仍指向目标（正后方的下）
        assertEquals(0.0, back.force, eps) // 但力度为 0：先转再走

        // 反向的另一组
        assertEquals(0.0, MapNavControlPure.joystickVector(180.0, 0.0).force, eps)
        // 垂直（90°）：力度也归零
        assertEquals(0.0, MapNavControlPure.joystickVector(0.0, 90.0).force, eps)
        // 45°：cos45 ≈ 0.707
        assertEquals(0.7071067811865476, MapNavControlPure.joystickVector(0.0, 45.0).force, 1e-6)
    }

    // ───────────────── 到达判定 arrived（navigation_state_machine.cpp:1549） ─────────────────

    @Test
    fun `到达判定 圈内与圈外`() {
        // 与目标重合
        assertTrue(MapNavControlPure.arrived(0.0, 0.0, 0.0, 0.0, 1.0))
        // 圈内
        assertTrue(MapNavControlPure.arrived(0.5, 0.0, 0.0, 0.0, 1.0))
        // 圈外
        assertFalse(MapNavControlPure.arrived(1.5, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun `到达判定 正好在圈上算到达`() {
        // 距离 == 半径 → 上游 `<=` 闭区间，算到达
        assertTrue(MapNavControlPure.arrived(1.0, 0.0, 0.0, 0.0, 1.0))
        assertTrue(MapNavControlPure.arrived(0.0, -1.0, 0.0, 0.0, 1.0))
        // 3-4-5 直角三角形的斜边正好 = 5
        assertTrue(MapNavControlPure.arrived(3.0, 4.0, 0.0, 0.0, 5.0))
        // 略超一线 → 不到达
        assertFalse(MapNavControlPure.arrived(3.0, 4.001, 0.0, 0.0, 5.0))
        assertFalse(MapNavControlPure.arrived(1.0001, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun `到达判定 半径边界`() {
        // 半径 0：只有与目标完全重合才算
        assertTrue(MapNavControlPure.arrived(0.0, 0.0, 0.0, 0.0, 0.0))
        assertFalse(MapNavControlPure.arrived(0.0001, 0.0, 0.0, 0.0, 0.0))
        // 负半径视为永不到达
        assertFalse(MapNavControlPure.arrived(0.0, 0.0, 0.0, 0.0, -1.0))
    }

    @Test
    fun `默认放宽判定圈常量`() {
        assertEquals(6.0, MapNavControlPure.DEFAULT_ARRIVAL_RADIUS_WU, eps)
    }
}
