package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.maa.MaaFrameworkLibrary
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.third.Ln
import java.util.concurrent.TimeUnit

/**
 * 3D 场景运动控制（采集/送货/协议空间共用）。
 *
 * 参数全部对齐上游 MapNavigator Adb 后端（agent/cpp-algo/source/MapNavigator/Backend/Adb/）：
 * - 虚拟摇杆：原点 (198,552)，ROI (89,443,219,219)，拖拽半径 72，斜向按 1/√2 缩放
 * - 视角转向：360° ≈ 2.23006×屏宽（kTurn360UnitsPerWidth），1 度 ≈ 5 像素，swipe 时长 70ms
 * - 动作按钮：冲刺 (1166,620)、跳跃 (1166,475)、攻击 (1030,551)、交互 (1080,390)
 *
 * 坐标系是 MaaFW 截图的 1280×720，控制器会自行归一化到设备分辨率。
 */
object MotionSupport {
    /** 参考帧尺寸（与上游 kWorkWidth/kReferenceFrameHeight 一致） */
    const val FRAME_W = 1280
    const val FRAME_H = 720

    // ── 虚拟摇杆 ──
    private const val JOYSTICK_ORIGIN_X = 198
    private const val JOYSTICK_ORIGIN_Y = 552
    private const val JOYSTICK_ROI_X = 89
    private const val JOYSTICK_ROI_Y = 443
    private const val JOYSTICK_ROI_W = 219
    private const val JOYSTICK_ROI_H = 219
    private const val JOYSTICK_DRAG_RADIUS = 72
    private const val JOYSTICK_EDGE_INSET = 4

    // ── 触摸转向 ──
    private const val TURN_UNITS_PER_DEGREE = 5.0
    private const val TURN_SWIPE_DURATION_MS = 70

    /**
     * 相机 swipe 的起点。**不能取屏幕正中**：上游 `AdbCameraSwipeDriverConfig`
     * （`adb_camera_swipe_driver.h:18-22`）是 `origin_x = 1280/2 = 640`、
     * `origin_y = 720/2 - 96 = 264`；真机实测从 (640,360) 起拖不动视角（下半屏被角色/UI 吞掉），
     * 换成 (640,264) 后视角才响应。
     */
    private const val CAMERA_ORIGIN_X = FRAME_W / 2
    private const val CAMERA_ORIGIN_Y = FRAME_H / 2 - 96

    /** 上游 `AdbCameraSwipeDriverConfig` 的分步拖拽时序。 */
    private const val CAMERA_MOVE_STEPS = 6
    private const val CAMERA_TOUCH_DOWN_HOLD_MS = 8L
    private const val CAMERA_MOVE_STEP_DELAY_MS = 10L
    private const val CAMERA_END_HOLD_MS = 30L

    // ── 动作按钮（contact id 固定，互不干扰）──
    private const val SPRINT_BTN_X = 1166
    private const val SPRINT_BTN_Y = 620
    private const val JUMP_BTN_X = 1166
    private const val JUMP_BTN_Y = 475
    private const val INTERACT_BTN_X = 1080
    private const val INTERACT_BTN_Y = 390

    /** 攻击按钮；坐标取自上游 adb_input_backend 的 action_buttons_（FIGHT 动作用）。 */
    private const val ATTACK_BTN_X = 1030
    private const val ATTACK_BTN_Y = 551

    /** 摇杆拖拽专用 contact；转向/点击各自独立 contact，多指并存（完全对齐上游） */
    private const val CONTACT_JOYSTICK = 8
    private const val CONTACT_CAMERA = 1
    private const val CONTACT_ACTION = 5 // 对齐 interact_button.contact_id = 5
    private const val CONTACT_SPRINT = 2 // 对齐 sprint_button.contact_id = 2
    private const val CONTACT_JUMP = 3   // 对齐 jump_button.contact_id = 3
    private const val CONTACT_ATTACK = 4 // 对齐 attack_button.contact_id = 4

    /** 摇杆方向向量（index = 上游 Direction 枚举序）：8 向单位向量 */
    private val DIRECTION_VECTORS = arrayOf(
        intArrayOf(0, 0),   // None
        intArrayOf(0, -1),  // Forward
        intArrayOf(-1, -1), // ForwardLeft
        intArrayOf(-1, 0),  // Left
        intArrayOf(-1, 1),  // BackwardLeft
        intArrayOf(0, 1),   // Backward
        intArrayOf(1, 1),   // BackwardRight
        intArrayOf(1, 0),   // Right
        intArrayOf(1, -1),  // ForwardRight
    )

    /** 摇杆当前是否按着；Release 前重复 TouchUp 无害但省一步 */
    @Volatile
    private var joystickActive = false

    private fun lib(): MaaFrameworkLibrary = MaaFrameworkLoader.library
        ?: error("MaaFramework 未加载")
    private fun ctrl() = MaaRunner.currentController
        ?: error("controller 未连接")

    private fun waitCtrl(id: Long) {
        if (id > 0) lib().MaaControllerWait(ctrl(), id)
    }

    private fun sleep(ms: Long) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ── 基础原语 ──

    fun touchDown(contact: Int, x: Int, y: Int, pressure: Int = 0) {
        waitCtrl(lib().MaaControllerPostTouchDown(ctrl(), contact, x.coerceIn(0, FRAME_W - 1), y.coerceIn(0, FRAME_H - 1), pressure))
    }

    fun touchMove(contact: Int, x: Int, y: Int, pressure: Int = 0) {
        waitCtrl(lib().MaaControllerPostTouchMove(ctrl(), contact, x.coerceIn(0, FRAME_W - 1), y.coerceIn(0, FRAME_H - 1), pressure))
    }

    fun touchUp(contact: Int) {
        waitCtrl(lib().MaaControllerPostTouchUp(ctrl(), contact))
    }

    /** 点击动作按钮（交互/跳跃/冲刺/攻击共用）：按下→保持→抬起 */
    fun tapButton(x: Int, y: Int, holdMs: Int = 50, contact: Int = CONTACT_ACTION) {
        touchMove(contact, x, y)
        touchDown(contact, x, y)
        sleep(holdMs.toLong())
        touchUp(contact)
    }

    // ── 视角转向 ──

    /**
     * 触摸拖拽转视角。起点取上游 `AdbCameraSwipeDriver` 的 (640,264)（非屏幕正中）。
     * dy 正值 = 目标在下方（俯视），dx 正值 = 向右转。
     */
    fun rotateView(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val sx = CAMERA_ORIGIN_X
        val sy = CAMERA_ORIGIN_Y
        val ex = (sx + dx).coerceIn(0, FRAME_W - 1)
        val ey = (sy + dy).coerceIn(0, FRAME_H - 1)
        // 分步移动模拟连续拖拽（上游 ExecuteStableDrag：down→hold→6 步 move→end hold→up），
        // 避免一次大位移被游戏判定为 fling。
        touchDown(CONTACT_CAMERA, sx, sy)
        sleep(CAMERA_TOUCH_DOWN_HOLD_MS)
        for (i in 1..CAMERA_MOVE_STEPS) {
            val t = i.toDouble() / CAMERA_MOVE_STEPS
            touchMove(
                CONTACT_CAMERA,
                (sx + (ex - sx) * t).toInt(),
                (sy + (ey - sy) * t).toInt(),
            )
            if (i < CAMERA_MOVE_STEPS) sleep(CAMERA_MOVE_STEP_DELAY_MS)
        }
        sleep(CAMERA_END_HOLD_MS)
        touchUp(CONTACT_CAMERA)
        sleep(60) // action_quiet_period_ms：刚转完视角的移动会被吞
    }

    /** 按度数转偏航（yaw）：正 = 右转 */
    fun yawDelta(deltaDeg: Int) {
        rotateView((deltaDeg * TURN_UNITS_PER_DEGREE).toInt(), 0)
    }

    /** 按度数转俯仰（pitch）：正 = 低头 */
    fun pitchDelta(deltaDeg: Int) {
        rotateView(0, (deltaDeg * TURN_UNITS_PER_DEGREE).toInt())
    }

    // ── 虚拟摇杆 ──

    private fun joystickClamp(targetX: Int, targetY: Int): Pair<Int, Int> {
        // 上游 ClampPoint 到 ROI（edge_inset 内缩），防止拖出摇杆范围
        val minX = JOYSTICK_ROI_X + JOYSTICK_EDGE_INSET
        val minY = JOYSTICK_ROI_Y + JOYSTICK_EDGE_INSET
        val maxX = JOYSTICK_ROI_X + JOYSTICK_ROI_W - JOYSTICK_EDGE_INSET - 1
        val maxY = JOYSTICK_ROI_Y + JOYSTICK_ROI_H - JOYSTICK_EDGE_INSET - 1
        return Pair(targetX.coerceIn(minX, maxX), targetY.coerceIn(minY, maxY))
    }

    private fun directionIndex(forward: Boolean, left: Boolean, backward: Boolean, right: Boolean): Int {
        val h = (if (right) 1 else 0) - (if (left) 1 else 0)
        val v = (if (backward) 1 else 0) - (if (forward) 1 else 0)
        return (v + 1) * 3 + (h + 1)
    }

    /**
     * 设置移动状态（WASD 语义）。与上游一致：状态变化时 TouchDown/Move 到新方向点，
     * 全方向松开时 TouchUp。触摸保持期间角色持续移动。
     */
    @Synchronized
    fun setMovement(forward: Boolean, left: Boolean, backward: Boolean, right: Boolean, settleMs: Long = 0) {
        val dir = directionIndex(forward, left, backward, right)
        val vec = DIRECTION_VECTORS[dir]
        if (vec[0] == 0 && vec[1] == 0) {
            if (joystickActive) {
                touchUp(CONTACT_JOYSTICK)
                joystickActive = false
            }
        } else {
            val diagonal = vec[0] != 0 && vec[1] != 0
            val scale = if (diagonal) 0.7071 else 1.0
            val tx = JOYSTICK_ORIGIN_X + (vec[0] * JOYSTICK_DRAG_RADIUS * scale).toInt()
            val ty = JOYSTICK_ORIGIN_Y + (vec[1] * JOYSTICK_DRAG_RADIUS * scale).toInt()
            val (cx, cy) = joystickClamp(tx, ty)
            if (joystickActive) {
                touchMove(CONTACT_JOYSTICK, cx, cy)
            } else {
                touchDown(CONTACT_JOYSTICK, cx, cy)
                joystickActive = true
                sleep(16) // touch_down_hold_ms
            }
        }
        if (settleMs > 0) sleep(settleMs)
    }

    /** 释放摇杆（停止移动） */
    @Synchronized
    fun releaseJoystick(settleMs: Long = 32) {
        if (joystickActive) {
            touchUp(CONTACT_JOYSTICK)
            joystickActive = false
        }
        if (settleMs > 0) sleep(settleMs)
    }

    /** 向前点进一段（PulseForward）：按下→保持→松开 */
    fun pulseForward(holdMs: Long) {
        setMovement(forward = true, left = false, backward = false, right = false)
        sleep(holdMs)
        releaseJoystick()
    }

    // ── 高层动作（对齐上游 action_buttons_）──

    fun interact(holdMs: Int = 50) {
        tapButton(INTERACT_BTN_X, INTERACT_BTN_Y, holdMs, CONTACT_ACTION)
    }

    fun jump(holdMs: Int = 50) {
        tapButton(JUMP_BTN_X, JUMP_BTN_Y, holdMs, CONTACT_JUMP)
    }

    /** 普攻一次（MapNavigateAction 的 FIGHT 动作）。 */
    fun attack(holdMs: Int = 50) {
        tapButton(ATTACK_BTN_X, ATTACK_BTN_Y, holdMs, CONTACT_ATTACK)
    }

    /** 冲刺是 toggle 键：记录状态避免连按解除 */
    @Volatile
    private var sprintDown = false

    @Synchronized
    fun sprint(enable: Boolean) {
        if (enable && !sprintDown) {
            tapButton(SPRINT_BTN_X, SPRINT_BTN_Y, contact = CONTACT_SPRINT)
            sprintDown = true
        } else if (!enable && sprintDown) {
            tapButton(SPRINT_BTN_X, SPRINT_BTN_Y, contact = CONTACT_SPRINT)
            sprintDown = false
        }
    }

    fun resetSprintState() {
        sprintDown = false
    }

    /** 是否处于 3D 大世界（粗判：截图成功即可再由识别链判断） */
    fun screencapFresh(): Boolean {
        val l = lib()
        val capId = l.MaaControllerPostScreencap(ctrl())
        if (capId <= 0) return false
        l.MaaControllerWait(ctrl(), capId)
        return true
    }
}
