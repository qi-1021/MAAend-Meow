package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.third.Ln
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 自动采集「闭环走路」的**执行器**：把纯状态机 [MapNavWalkSession] 的每拍决策落到触摸上。
 *
 * 一拍（对齐上游 `navigation_state_machine.cpp:1164-1165` 主线）：
 *   1. [FrameLocator.observe] 截一帧 → 小地图定位 → 喂 [MapLocatorTracking] → 估朝向；
 *   2. 状态机按「到当前路点距离 / 朝向误差 / 丢失拍数 / 耗时」出裁决
 *      （[MapNavAction]：走 / 转 / 重定位 / 等到点 / 超时）；
 *   3. 裁决落到 [MotionSupport]：`steerSwipeDelta` → 转视角，`joystickVector` → 推摇杆；
 *   4. [onTick] 回调给 [RunDiagnostics] 记关键数字，便于真机调参。
 *
 * 方位/转向/到达的纯计算由 [MapNavControlPure]（并行 lane）提供，经 [ControlAdapter] 适配到
 * [NavWalkControl]。navmesh 规划在 P1 不启用：采集路线点密、按作者顺序逐点展开。
 */
object MapNavRuntime {

    /**
     * 单帧观测：MaaRunner 负责「截一帧 → 定位 → 喂追踪 → 估朝向」，把结果塞进本结构。
     * [accepted] 为真表示本拍定位裁决 ACCEPT（有稳定化位置）。
     */
    data class FrameFix(
        val accepted: Boolean,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val yawDeg: Double? = null,
        val reason: String = "",
    )

    /** MaaRunner 注入的单帧定位器；取不到帧 / 定位失败时 [FrameFix.accepted] 为 false。 */
    fun interface FrameLocator {
        fun observe(): FrameFix
    }

    /** 一次走路的结局。 */
    data class WalkOutcome(
        val success: Boolean,
        val reason: String,
        val ticks: Int,
        val elapsedMs: Long,
        val finalDistance: Double,
    )

    /**
     * 纯控制端口 → [MapNavControlPure] 的适配器。持有一个 [MapNavControlPure.NavControlConfig]
     * 以承载掉头 latch 的跨拍状态（每条闭环一个实例，与上游 `int& turn_latch_sign` 同义）。
     */
    class ControlAdapter(
        private val cfg: MapNavControlPure.NavControlConfig = MapNavControlPure.NavControlConfig(),
    ) : NavWalkControl {
        override fun bearing(fromX: Double, fromY: Double, toX: Double, toY: Double): Double =
            MapNavControlPure.bearing(fromX, fromY, toX, toY)

        override fun normalizeAngle(deg: Double): Double = MapNavControlPure.normalizeAngle(deg)

        override fun steerSwipeDelta(yawErrorDeg: Double): Double =
            MapNavControlPure.steerSwipeDelta(yawErrorDeg, cfg)

        override fun arrived(px: Double, py: Double, tx: Double, ty: Double, radius: Double): Boolean =
            MapNavControlPure.arrived(px, py, tx, ty, radius)
    }

    /** 8 向摇杆（[MotionSupport.setMovement] 的 WASD 语义）。 */
    private data class MoveDir(
        val forward: Boolean,
        val left: Boolean,
        val backward: Boolean,
        val right: Boolean,
    )

    /**
     * 目标方位相对相机朝向的屏幕方向 → 8 向。
     *
     * @param relDeg `normalizeAngle(bearing - yaw)`：0=正前，正=右。
     */
    private fun dir8(relDeg: Double): MoveDir = when {
        relDeg >= -22.5 && relDeg < 22.5 -> MoveDir(true, false, false, false)
        relDeg >= 22.5 && relDeg < 67.5 -> MoveDir(true, false, false, true)
        relDeg >= 67.5 && relDeg < 112.5 -> MoveDir(false, false, false, true)
        relDeg >= 112.5 && relDeg < 157.5 -> MoveDir(false, false, true, true)
        relDeg >= 157.5 || relDeg < -157.5 -> MoveDir(false, false, true, false)
        relDeg >= -157.5 && relDeg < -112.5 -> MoveDir(false, true, true, false)
        relDeg >= -112.5 && relDeg < -67.5 -> MoveDir(false, true, false, false)
        else -> MoveDir(true, true, false, false) // (-67.5, -22.5)
    }

    /**
     * 走完单个路点的闭环。阻塞直到到点 / 失败。
     *
     * @param waypoint 目标路点。
     * @param config 每拍配置；P1 默认到达圈 6px、单点超时 90s。
     * @param locator 单帧定位器（MaaRunner 实现）。
     * @param onTick 每拍回调（诊断）。
     */
    fun walkTo(
        waypoint: NavWaypoint,
        config: NavWalkConfig = NavWalkConfig(),
        locator: FrameLocator,
        onTick: (NavWalkTick, FrameFix) -> Unit = { _, _ -> },
    ): WalkOutcome {
        val adapter = ControlAdapter()
        val session = MapNavWalkSession(listOf(waypoint), adapter, config)
        val startNs = System.nanoTime()
        var lastNs = startNs
        var ticks = 0
        var lastTick: NavWalkTick? = null

        while (!session.finished && lastTick?.action != NavWalkAction.FAILED) {
            val fix = runCatching { locator.observe() }.getOrElse {
                Ln.w("MaaRunner: MapNav 定位异常：${it.javaClass.simpleName}: ${it.message}")
                FrameFix(false, reason = "observe error: ${it.message}")
            }
            val nowNs = System.nanoTime()
            val dtMs = (nowNs - lastNs) / 1_000_000L
            lastNs = nowNs

            val navFix = NavFix(fix.accepted, fix.x, fix.y, fix.yawDeg, fix.reason)
            val tick = session.tick(navFix, dtMs)
            ticks++
            lastTick = tick

            applyTick(tick, fix.yawDeg)
            onTick(tick, fix)

            when (tick.action) {
                NavWalkAction.ARRIVED, NavWalkAction.FAILED -> {
                    MotionSupport.releaseJoystick()
                }

                else -> sleep(config.tickIntervalMs)
            }
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        val t = lastTick
        val success = t?.action == NavWalkAction.ARRIVED
        val reason = when {
            t == null -> "no tick"
            success -> "arrived"
            else -> t.reason
        }
        return WalkOutcome(
            success = success,
            reason = reason,
            ticks = ticks,
            elapsedMs = elapsedMs,
            finalDistance = t?.distance ?: 0.0,
        )
    }

    /** 把一拍裁决落到触摸。 */
    private fun applyTick(tick: NavWalkTick, yawDeg: Double?) {
        when (tick.action) {
            NavWalkAction.WALK -> {
                if (tick.turnSwipePx != 0) {
                    MotionSupport.rotateView(tick.turnSwipePx, 0)
                }
                pushJoystick(tick, yawDeg)
            }

            NavWalkAction.TURN, NavWalkAction.RELOCATE -> {
                // 背对目标 / 重定位：先松摇杆，只转视角，避免朝反方向走。
                MotionSupport.releaseJoystick()
                if (tick.turnSwipePx != 0) {
                    MotionSupport.rotateView(tick.turnSwipePx, 0)
                }
            }

            NavWalkAction.HOLD, NavWalkAction.ARRIVED, NavWalkAction.FAILED ->
                MotionSupport.releaseJoystick()
        }
    }

    /** 推摇杆：按 `bearing - yaw` 换算屏幕 8 向，力度不足时不推。 */
    private fun pushJoystick(tick: NavWalkTick, yawDeg: Double?) {
        val bearing = tick.moveBearingDeg
        if (yawDeg == null || bearing == null) {
            MotionSupport.releaseJoystick()
            return
        }
        // 方向必须是相机相对（游戏摇杆是相机系）；[MapNavControlPure.joystickVector] 的 dx/dy 是
        // 世界轴绝对方向，故方向这里自己按相对角取 8 向，只用它的 force 做「是否值得推」的门限。
        val force = MapNavControlPure.joystickVector(yawDeg, bearing).force
        if (force <= 0.0) {
            MotionSupport.releaseJoystick()
            return
        }
        val rel = MapNavControlPure.normalizeAngle(bearing - yawDeg)
        val d = dir8(rel)
        MotionSupport.setMovement(d.forward, d.left, d.backward, d.right)
    }

    private fun sleep(ms: Long) {
        if (ms <= 0) return
        try {
            TimeUnit.MILLISECONDS.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 调试用：一拍裁决的可读串。 */
    fun describeTick(tick: NavWalkTick, fix: FrameFix): String = String.format(
        Locale.US,
        "act=%s wp=%d pos=(%.1f,%.1f) yaw=%s dist=%.1f err=%s turn=%d lost=%d reloc=%d t=%dms %s",
        tick.action,
        tick.waypointIndex,
        fix.x,
        fix.y,
        fix.yawDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
        tick.distance,
        tick.yawErrorDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "-",
        tick.turnSwipePx,
        tick.lostStreak,
        tick.relocateAttempts,
        tick.elapsedMs,
        tick.reason,
    )
}
