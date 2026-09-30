package com.aliothmoon.maafw.remote

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 自动采集「行走闭环」里**可离线测试**的那部分纯逻辑：
 * 方位计算、朝向 P 控制（转向 swipe 位移）、摇杆向量、到达判定。
 *
 * 一拍闭环 = 定位 → 算到下一个路点的方位 → 转向 → 前进。本文件只做几何/控制量的计算，
 * 不碰截图、识别、触摸；真正的下发（转视角 swipe、摇杆 TouchDown/Move/Up）留在 MaaRunner，
 * 复用 [MotionSupport] 的常量（radians per degree = 5、摇杆原点等）。
 *
 * 对齐的上游（`upstream/maaend/agent/cpp-algo/source/MapNavigator/`）：
 *  - 方位/归一化：`navi_math.cpp:9-18`（`CalcTargetRotation`）、`:25-34`（`NormalizeAngle`）
 *  - 转向 P 控制：`steering_controller.cpp:14-23,42-65`
 *  - 转视角下发：`motion_controller.cpp:94-167`（分批发/静默期）、`:247-273`（度→像素）
 *  - 到达判定：`navigation_state_machine.cpp:1549`（`<= arrival_distance`）、
 *    `navi_domain_types.h:199-212`（判定圈构造）、`navi_config.h:152,430`
 *  - 摇杆几何（本文件不直接用，仅供 [MotionSupport] 参考）：`adb_virtual_joystick_driver.h:19-31`
 *
 * 本文件不碰 Android / JNA / 文件系统，可在 `scripts/verify_pure_logic.sh` 本机回归。
 */
object MapNavControlPure {

    /**
     * 「先用放宽值」的到达判定圈半径（底图像素 / 世界单位）。
     *
     * 上游采集点是收紧到 `kCollectArrivalBandWu = 1.5`（`navi_config.h:430`），下限
     * `kMinArrivalBand = 1.0`（`navi_config.h:152`）；常规点约 `lookahead 2.5 + slack 0.5 +
     * quantum` ≈ 3.25。真机起步阶段先放宽到 5~8（本值取 6.0），避免定位量化误差让路点吃不掉、
     * 角色在原地反复微调。**真机可调项**：待走路稳定后逐步收紧回上游值。
     *
     * 与上游差异：上游判定圈由 [lookahead/slack/corridor] 动态算出，本处先用固定放宽常量，
     * 由调用方传给 [arrived]。
     */
    const val DEFAULT_ARRIVAL_RADIUS_WU = 6.0

    /**
     * 上游 `SteeringController::Update` 的 `issued` 门限（`steering_controller.cpp:65`）：
     * `|cmd| >= 2.0°` 才认为这条转向指令值得下发。本对象返回的是位移像素，对应门限为
     * `2.0 * pixelsPerDegree`（默认 10px）。调用方若发现 `|steerSwipeDelta| < 该值`，应跳过下发。
     */
    const val MIN_EMIT_CMD_DEG = 2.0

    /**
     * 转向控制的参数集合。默认值逐条对齐上游 `steering_controller.cpp`。
     *
     * [turnLatchSign] 是**跨拍状态**（上游用 `int&` 传出/传入，见
     * `steering_controller.cpp:31,42-50`）。因为 [steerSwipeDelta] 的签名固定只有
     * `(yawErrorDeg, cfg)` 两个入参，掉头 latch 的状态只能挂在 [NavControlConfig] 上：
     * 调用方**每条行走闭环复用一个 cfg 实例**，[steerSwipeDelta] 会就地更新它。
     */
    data class NavControlConfig(
        /** 死区：`|误差| < 该值` 时 P 项归零（`steering_controller.cpp:14,61`）。 */
        val headingDeadbandDeg: Double = 6.6,

        /** 移动中的转向限幅（度）：`steering_controller.cpp:15,62`。 */
        val movingMaxCmdDeg: Double = 90.0,

        /** 原地转向（未前进）时的转向限幅（度）：`steering_controller.cpp:16,62`。 */
        val turningMaxCmdDeg: Double = 70.0,

        /** 比例增益（`steering_controller.cpp:17`）。默认 1.0，即 P 项就是误差本身。 */
        val kp: Double = 1.0,

        /** 掉头 latch 进入门限（度）：`|误差| >= 该值` 且当前无 latch 时锁定转向方向（`steering_controller.cpp:22,45-47`）。 */
        val turnLatchEnterDeg: Double = 150.0,

        /** 掉头 latch 解开门限（度）：`|误差| < 该值` 时清 latch（`steering_controller.cpp:23,42-44`）。 */
        val turnLatchExitDeg: Double = 120.0,

        /** 度→转视角 swipe 像素的比例。上游 ADB `default_units_per_degree = 5.0`（`navi_config.h:20`），与 [MotionSupport.TURN_UNITS_PER_DEGREE] 一致。 */
        val pixelsPerDegree: Double = 5.0,

        /** 本拍是否在前进：决定限幅取 [movingMaxCmdDeg] 还是 [turningMaxCmdDeg]（`steering_controller.cpp:62`）。 */
        val movingForward: Boolean = true,

        /** 掉头 latch 的方向（+1/-1/0），跨拍保留。**可变**，由 [steerSwipeDelta] 就地维护。 */
        var turnLatchSign: Int = 0,
    )

    /**
     * 摇杆向量：方向是单位向量，力度是 0..1。
     *
     * 坐标系：屏幕像素，`dx > 0` 向右、`dy < 0` 向上（与 [MotionSupport] 一致）。
     * 方向取**目标方位 [bearingDeg] 的世界朝向**换算到屏幕轴（0°=正上/北，顺时针）：
     * `dx = sin(bearing)`、`dy = -cos(bearing)`。相机朝向 [yawDeg] 只参与力度：
     * 正对目标（yaw == bearing）力度 1，偏得越多越小，|角差| ≥ 90° 力度 0（先转再走）。
     */
    data class JoystickVector(
        /** 屏幕水平分量，>0 向右。单位向量的一部分。 */
        val dx: Double,
        /** 屏幕垂直分量，<0 向上（屏幕 y 向下）。单位向量的一部分。 */
        val dy: Double,
        /** 力度 0..1，正对目标 = 1。 */
        val force: Double,
    )

    /**
     * 到目标点的方位角（度）：0 = 正上/北，顺时针增大。
     *
     * 公式与 [MapNavHeading.estimateFromBgr] **完全同式**（`atan2(dx, -dy)`，负值加 360）：都是
     * 「北为 0、顺时针为正」的实数角度。上游 `NaviMath::CalcTargetRotation`（`navi_math.cpp:9-18`）
     * 在此基础上多一步 `fmod(round(angle), 360)` 的**整数取整**；此处不取整，与实数朝向的
     * [MapNavHeading.estimateFromBgr] 保持一致（取整差至多 0.5°，在 6.6° 死区之下可忽略）。
     */
    fun bearing(fromX: Double, fromY: Double, toX: Double, toY: Double): Double {
        val dx = toX - fromX
        val dy = toY - fromY
        var deg = atan2(dx, -dy) * 180.0 / PI
        if (deg < 0.0) deg += 360.0
        return deg
    }

    /**
     * 朝向误差归一化到 `(-180, 180]`。
     *
     * 上游 `NaviMath::NormalizeAngle`（`navi_math.cpp:25-34`）：`> 180` 减 360、`<= -180` 加 360
     * 的循环。边界口径：`180 → 180`、`-180 → 180`、`0 → 0`、`360 → 0`、`-360 → 0`。
     * 用一次取模等价实现（对有限值逐位等价）。
     */
    fun normalizeAngle(deg: Double): Double {
        var a = deg % 360.0
        if (a > 180.0) a -= 360.0
        else if (a <= -180.0) a += 360.0
        return a
    }

    /**
     * 转向 P 控制：输入朝向误差（度，正 = 目标在右），输出本次**转视角 swipe 的水平位移**
     * （像素，正 = 向右滑）。
     *
     * 对齐上游 `SteeringController::Update`（`steering_controller.cpp:27-69`）：
     *  1. 掉头 latch（`:42-50`）：`|err| < turnLatchExitDeg(120)` 清 latch；
     *     无 latch 且 `|err| >= turnLatchEnterDeg(150)` 时按误差符号锁定方向；
     *     latch 生效时把误差改成 `|err| * latch`，避免在正对侧「左右横跳」。
     *  2. 死区（`:61`）：`|err| < headingDeadbandDeg(6.6)` 时 P 项 = 0。
     *  3. 比例项：`p = err * kp`（`:61`）。
     *  4. 限幅（`:62-63`）：移动中 `±movingMaxCmdDeg(90)`，否则 `±turningMaxCmdDeg(70)`。
     *  5. 位移：`cmd * pixelsPerDegree(5.0)`（对应上游 `SendViewDelta` 的
     *     `units = lround(delta * units_per_degree)`，`motion_controller.cpp:255-258`）。
     *
     * **与上游的差异**（都因为本函数签名固定、无这些入参，交由 MaaRunner 处理）：
     *  - 未实现 `heading_rate` 的毛刺抑制（`:18,37-40`：|角速率| > 60°/s 直接返回 0）；
     *  - 未实现 pending-turn 折扣（`:52-57`：扣掉「已下发但朝向还没走完」的角度），
     *    需要跨拍累计已发角度，调用方按 `kSteeringPendingLifetimeMs` 维护；
     *  - 返回的是**一拍的总位移**，上游还会把它拆成 ≤ `max_batch_delta_deg`（ADB 20°，
     *    `adb_input_backend.cpp:118`）的小批、每拍至多 `kSteeringMaxBatchesPerTick(3)`
     *    （`navi_config.h:126`）、受 `min_send_interval_ms`/`action_quiet_period_ms` 约束
     *    （`motion_controller.cpp:102-151`）；这些分批发/静默期由调用方完成；
     *  - 未实现 `issued` 门限：调用方应跳过 `|返回值| < MIN_EMIT_CMD_DEG * pixelsPerDegree`。
     */
    fun steerSwipeDelta(yawErrorDeg: Double, cfg: NavControlConfig): Double {
        var err = yawErrorDeg

        // 1. 掉头 latch：先把方向钉死，再算 P。
        if (abs(err) < cfg.turnLatchExitDeg) {
            cfg.turnLatchSign = 0
        } else if (cfg.turnLatchSign == 0 && abs(err) >= cfg.turnLatchEnterDeg) {
            cfg.turnLatchSign = if (err >= 0.0) 1 else -1
        }
        if (cfg.turnLatchSign != 0) {
            err = abs(err) * cfg.turnLatchSign
        }

        // 2+3. 死区只闸 P 项；真实大转角不会被它挡住。
        val p = if (abs(err) < cfg.headingDeadbandDeg) 0.0 else err * cfg.kp

        // 4. 限幅：移动中更宽（转向更快会甩出弧线，故原地更保守）。
        val maxCmd = if (cfg.movingForward) cfg.movingMaxCmdDeg else cfg.turningMaxCmdDeg
        val cmd = p.coerceIn(-maxCmd, maxCmd)

        // 5. 度 → 像素。
        return cmd * cfg.pixelsPerDegree
    }

    /**
     * 摇杆向量：已知相机/角色朝向 [yawDeg] 与目标方位 [bearingDeg]，返回应推的
     * [JoystickVector]（单位方向 + 力度）。
     *
     * 方向 = 目标方位换算到屏幕轴：`dx = sin(bearing)`、`dy = -cos(bearing)`，故
     * `bearing=0°→上、90°→右、180°→下、270°→左`。
     *
     * 力度 = `clamp(cos(|normalize(bearing - yaw)|), 0, 1)`：正对目标 = 1，|角差| ≥ 90° = 0
     * （先用 [steerSwipeDelta] 把视角转过去，再前进）。**真机可调项**：若希望背对时也带一点
     * 速度，可把力度换成线性衰减 `1 - |角差|/180`。
     */
    fun joystickVector(yawDeg: Double, bearingDeg: Double): JoystickVector {
        val rad = Math.toRadians(bearingDeg)
        val dx = sin(rad)
        val dy = -cos(rad)
        val diff = abs(normalizeAngle(bearingDeg - yawDeg))
        val force = cos(Math.toRadians(diff)).coerceIn(0.0, 1.0)
        return JoystickVector(dx = dx, dy = dy, force = force)
    }

    /**
     * 到达判定（判定圈，单位 = 底图像素 / 世界单位）。
     *
     * 上游 `navigation_state_machine.cpp:1549` 用 `waypoint_distance <= arrival_distance`：
     * **圈上算到达（闭区间）**。这里同样用 `距离² <= 半径²`（免开方），半径 < 0 视为永不到达。
     * 半径由调用方传入，建议起步用 [DEFAULT_ARRIVAL_RADIUS_WU]。
     */
    fun arrived(px: Double, py: Double, tx: Double, ty: Double, radius: Double): Boolean {
        if (radius < 0.0) return false
        val dx = px - tx
        val dy = py - ty
        return dx * dx + dy * dy <= radius * radius
    }
}
