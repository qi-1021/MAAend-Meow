package com.aliothmoon.maafw.remote

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 自动采集「走路」闭环的**纯状态机**：吃「本拍定位结果」，吐「这一拍该转多少 / 往哪走 / 是否到点 /
 * 是否该重定位 / 是否超时」。
 *
 * 一条闭环（对齐上游 `MapNavigator/` 的 `navigation_state_machine.cpp` + `steering_controller.cpp` +
 * `motion_controller.cpp` 的主线，见 `navi_config.h:93-192`）：
 *
 *   截图 → 小地图定位（[MapLocatorTracking]）→ 估朝向（[MapNavHeading]）→
 *   算到当前路点的方位（[MapNavControlPure.bearing]）→ P 控制转向
 *   （[MapNavControlPure.steerSwipeDelta]）→ 推摇杆前进（[MapNavControlPure.joystickVector]）→
 *   到达判定（[MapNavControlPure.arrived]）→ 到点整下一路点。
 *
 * 本文件只做**状态推进**：方位换算/转向 P 控制/到达圈分别由注入的 [NavWalkControl] 提供
 * （生产实现是 `MapNavRuntime.ControlAdapter` → [MapNavControlPure]），这样状态机可以完全脱离
 * Android / JNA / 截图，用「一串定位结果」在 [scripts/verify_pure_logic.sh] 本机回归。
 *
 * 与上游的差异（P1 起步阶段，真机可调，见 [NavWalkConfig]）：
 *  - **不做 navmesh 规划**：采集路线点距密、作者顺序即路点顺序，逐点展开（文档明确「未启用滑索时
 *    按作者顺序」）。故本状态机只处理「当前路点 → 下一个路点」的推进，不含 A*。
 *  - **定位丢失**先原地转圈重定位（上游是 jump/detour 恢复阶梯，需要 navmesh/物理量）；重定位
 *    若干次仍无有效定位即失败，交给上层重试整条路线。
 */

/**
 * 走路闭环的每拍配置。默认值都对齐上游并把「起步阶段放宽、便于真机调参」的项标出来。
 *
 * @param arrivalRadius 到达判定圈半径（底图像素 / 世界单位）。上游常规点约 3.25、采集点
 *   收紧到 1.5（`navi_config.h:430`）。P1 起步先放宽到 [DEFAULT_ARRIVAL_RADIUS]（6.0），
 *   避免定位量化误差让路点吃不掉、角色原地微调；走稳后再收紧。**真机主要调参项**。
 * @param maxTurnSwipePxPerTick 单拍转视角水平位移上限（像素）。上游一拍最多 3 批 ×20°=60°
 *   （`navi_config.h:126`、`adb_input_backend.cpp:118`），按 [MotionSupport] 的 5px/度即 300px。
 * @param turnEmitThresholdPx 转向指令下发门限（像素），低于此值视为噪声不转。上游 `|cmd| >= 2°`
 *   （`steering_controller.cpp:65`），对应 10px。
 * @param walkYawErrorMaxDeg 朝向误差超过该值时只转不走（背对目标先转正）。上游摇杆力度
 *   `cos(误差)`，误差 ≥90° 力度归零（`motion_controller.cpp` 对应用户侧），这里取 90°。
 * @param lostTicksBeforeRelocate 连续多少拍拿不到「可用定位（位置+朝向）」就触发重定位。
 *   定位一帧约 0.9s，故按拍计数而非毫秒。
 * @param relocateTicks 每次重定位持续几拍（原地转圈重新找箭头）。
 * @param relocateTurnSwipePx 重定位每拍转的视角位移（像素，默认 300 ≈ 60°）。
 * @param maxRelocateAttempts 连续（未恢复定位）最多重定位几次，用尽仍无定位即失败；一旦恢复
 *   定位则预算重置。
 * @param waypointTimeoutMs 单路点总超时，到点未达即失败。上游主线无单点硬超时，靠恢复阶梯；
 *   这里给 90s 兜底并如实上报。**真机可调**。
 * @param tickIntervalMs 每拍之间的睡眠（截图/定位本身约 0.9s，这是额外节流）。**真机可调**。
 */
data class NavWalkConfig(
    val arrivalRadius: Double = DEFAULT_ARRIVAL_RADIUS,
    val maxTurnSwipePxPerTick: Double = 300.0,
    val turnEmitThresholdPx: Double = 10.0,
    val walkYawErrorMaxDeg: Double = 90.0,
    val lostTicksBeforeRelocate: Int = 8,
    val relocateTicks: Int = 3,
    val relocateTurnSwipePx: Int = 300,
    val maxRelocateAttempts: Int = 2,
    val waypointTimeoutMs: Long = 90_000L,
    val tickIntervalMs: Long = 250L,
    val maxPlausibleStepDistance: Double = 50.0,
) {
    companion object {
        /** 起步到达圈，对齐 [MapNavControlPure.DEFAULT_ARRIVAL_RADIUS_WU]。 */
        const val DEFAULT_ARRIVAL_RADIUS = 6.0
    }
}

/**
 * 一拍定位观测（执行器从截图/定位/朝向估计产出，测试直接注入）。
 *
 * [accepted] = 本拍 [MapLocatorTracking] 裁决为 ACCEPT（有稳定化位置）；[yawDeg] 为
 * [MapNavHeading] 估出的朝向（0=北、顺时针），读不到为 null。
 * 只有「位置有 + 朝向有」才视为**可用**（闭环两个输入都齐才敢推摇杆）。
 */
data class NavFix(
    val accepted: Boolean,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val yawDeg: Double? = null,
    val reason: String = "",
) {
    /** 位置与朝向都就绪。 */
    val usable: Boolean get() = accepted && yawDeg != null
}

/** 一个待走路点（底图像素坐标）。 */
data class NavWaypoint(
    val x: Double,
    val y: Double,
    val index: Int = 0,
    val label: String = "",
)

/** 本拍状态机裁决。 */
enum class NavWalkAction {
    /** 朝目标走（可能同时带小幅转向）。 */
    WALK,

    /** 背对目标，先原地转正、不推摇杆。 */
    TURN,

    /** 连续拿不到可用定位，本拍原地转圈重新找箭头。 */
    RELOCATE,

    /** 本拍定位不可用但还没到重定位门限，松摇杆等下一拍。 */
    HOLD,

    /** 已进入到达圈。 */
    ARRIVED,

    /** 超时或重定位次数用尽。 */
    FAILED,
}

/**
 * 本拍决策。
 *
 * @param turnSwipePx 建议下发的转视角水平位移（像素，正=右）。0 表示不转。
 * @param moveBearingDeg 建议前进的世界方位（0=北、顺时针）；null 表示本拍不推摇杆（[TURN]/
 *   [HOLD]/[RELOCATE]/[ARRIVED]/[FAILED]）。摇杆方向由执行器按 `bearing - yaw` 换算到屏幕轴。
 * @param distance 到当前路点距离（尚无定位时为 0）。
 * @param yawErrorDeg 目标方位减当前朝向归一化后的误差（无朝向时为 null）。
 */
data class NavWalkTick(
    val action: NavWalkAction,
    val waypointIndex: Int,
    val turnSwipePx: Int,
    val moveBearingDeg: Double?,
    val distance: Double,
    val yawErrorDeg: Double?,
    val lostStreak: Int,
    val relocateAttempts: Int,
    val elapsedMs: Long,
    val reason: String,
)

/**
 * 状态机依赖的「控制量」纯函数端口。生产实现转发到 [MapNavControlPure]（并行 lane 提供），
 * 测试注入自足实现，故本文件不直接依赖 lane 文件、可在 verify 里独立编译。
 */
interface NavWalkControl {
    /** 到目标点的方位角（度，0=北、顺时针）。 */
    fun bearing(fromX: Double, fromY: Double, toX: Double, toY: Double): Double

    /** 归一化到 (-180, 180]。 */
    fun normalizeAngle(deg: Double): Double

    /** 朝向误差 → 本拍转视角水平位移（像素，正=右）。内部维护掉头 latch 等跨拍状态。 */
    fun steerSwipeDelta(yawErrorDeg: Double): Double

    /** 到达判定（判定圈，闭区间）。 */
    fun arrived(px: Double, py: Double, tx: Double, ty: Double, radius: Double): Boolean
}

/**
 * 单路点闭环状态机。可复用状态跨拍调用 [tick]；到达/失败后 [finished] 置位。
 *
 * 状态：[elapsedMs]（本路点累计耗时）、[lostStreak]（连续不可用拍数）、[relocateAttempts]、
 * [relocateTicksRemaining]（重定位剩余拍数）。
 */
class MapNavWalkPlanner(
    private val waypoint: NavWaypoint,
    private val control: NavWalkControl,
    private val config: NavWalkConfig = NavWalkConfig(),
) {
    private var elapsedMs = 0L
    private var lostStreak = 0
    private var relocateAttempts = 0
    private var relocateTicksRemaining = 0
    private var lastDistance = 0.0

    /** 已到点或已判失败。 */
    var finished = false
        private set

    /** 已到点（[finished] 且成功）。 */
    var arrived = false
        private set

    /** 供诊断：本路点已重定位次数。 */
    val attempts: Int get() = relocateAttempts

    /**
     * 推进一拍。
     *
     * @param fix 本拍定位观测。
     * @param dtMs 距上一拍的毫秒数（首拍从本路点开始计）。
     */
    fun tick(fix: NavFix, dtMs: Long): NavWalkTick {
        elapsedMs += dtMs.coerceAtLeast(0L)
        if (finished) {
            return result(NavWalkAction.FAILED, 0, null, null, "finished")
        }

        val usable = fix.usable
        val isJumpOutlier = if (usable && lastDistance > 0.0) {
            val dist = hypot(fix.x - waypoint.x, fix.y - waypoint.y)
            abs(dist - lastDistance) > config.maxPlausibleStepDistance
        } else {
            false
        }

        if (usable && !isJumpOutlier) {
            // 有可用定位即恢复：清丢失计数、重定位窗口与已用预算（已重新推进即算恢复）。
            lostStreak = 0
            relocateTicksRemaining = 0
            relocateAttempts = 0
            lastDistance = hypot(fix.x - waypoint.x, fix.y - waypoint.y)
            if (control.arrived(fix.x, fix.y, waypoint.x, waypoint.y, config.arrivalRadius)) {
                arrived = true
                finished = true
                return result(NavWalkAction.ARRIVED, 0, null, null, "arrived")
            }
        }

        if (elapsedMs >= config.waypointTimeoutMs) {
            finished = true
            return result(NavWalkAction.FAILED, 0, null, null, "timeout ${elapsedMs}ms")
        }

        val acceptedUsable = usable && !isJumpOutlier
        if (acceptedUsable) {
            val yaw = fix.yawDeg!!
            val bearing = control.bearing(fix.x, fix.y, waypoint.x, waypoint.y)
            val yawError = control.normalizeAngle(bearing - yaw)
            val swipe = control.steerSwipeDelta(yawError)
                .coerceIn(-config.maxTurnSwipePxPerTick, config.maxTurnSwipePxPerTick)
            val turnPx = if (abs(swipe) < config.turnEmitThresholdPx) 0 else Math.round(swipe).toInt()
            val walk = abs(yawError) <= config.walkYawErrorMaxDeg
            return if (walk) {
                result(NavWalkAction.WALK, turnPx, bearing, yawError, "walk")
            } else {
                result(NavWalkAction.TURN, turnPx, null, yawError, "turn")
            }
        }

        // 不可用：重定位进行中则继续转。
        if (relocateTicksRemaining > 0) {
            relocateTicksRemaining--
            return result(
                NavWalkAction.RELOCATE, config.relocateTurnSwipePx, null, null,
                "relocate tick",
            )
        }

        lostStreak++
        if (lostStreak >= config.lostTicksBeforeRelocate) {
            if (relocateAttempts < config.maxRelocateAttempts) {
                relocateAttempts++
                relocateTicksRemaining = (config.relocateTicks - 1).coerceAtLeast(0)
                lostStreak = 0
                return result(
                    NavWalkAction.RELOCATE, config.relocateTurnSwipePx, null, null,
                    "relocate attempt $relocateAttempts",
                )
            }
            finished = true
            return result(NavWalkAction.FAILED, 0, null, null, "localization lost")
        }
        return result(NavWalkAction.HOLD, 0, null, null, "lost streak $lostStreak")
    }

    private fun result(
        action: NavWalkAction,
        turnPx: Int,
        moveBearing: Double?,
        yawError: Double?,
        reason: String,
    ) = NavWalkTick(
        action = action,
        waypointIndex = waypoint.index,
        turnSwipePx = turnPx,
        moveBearingDeg = moveBearing,
        distance = lastDistance,
        yawErrorDeg = yawError,
        lostStreak = lostStreak,
        relocateAttempts = relocateAttempts,
        elapsedMs = elapsedMs,
        reason = reason,
    )
}

/**
 * 多路点串联：包住一串 [NavWaypoint]，到点自动整下一个（每整一次重置该点的超时/丢失计数）。
 *
 * 执行器每条 RUN/NAVMESH 路点包一个单元素 session；测试用多元素 session 覆盖串联推进。
 */
class MapNavWalkSession(
    private val waypoints: List<NavWaypoint>,
    private val control: NavWalkControl,
    private val config: NavWalkConfig = NavWalkConfig(),
) {
    private var index = 0
    private var planner: MapNavWalkPlanner =
        MapNavWalkPlanner(waypoints.firstOrNull() ?: NavWaypoint(0.0, 0.0), control, config)

    /** 当前正在走的路点下标。 */
    val currentIndex: Int get() = index

    /** 当前路点；全部走完为 null。 */
    val currentWaypoint: NavWaypoint? get() = waypoints.getOrNull(index)

    /** 空列表视为已完成，否则走完最后一个到点为 true。 */
    val finished: Boolean get() = waypoints.isEmpty() || index >= waypoints.size

    /** 到点路点数（诊断用）。 */
    val arrivedCount: Int get() = index

    /** 推进一拍；到点时自动整下一个路点。 */
    fun tick(fix: NavFix, dtMs: Long): NavWalkTick {
        if (finished) {
            return NavWalkTick(
                action = NavWalkAction.FAILED,
                waypointIndex = index,
                turnSwipePx = 0,
                moveBearingDeg = null,
                distance = 0.0,
                yawErrorDeg = null,
                lostStreak = 0,
                relocateAttempts = 0,
                elapsedMs = 0L,
                reason = "session finished",
            )
        }
        val out = planner.tick(fix, dtMs)
        if (out.action == NavWalkAction.ARRIVED) {
            index++
            if (index < waypoints.size) {
                planner = MapNavWalkPlanner(waypoints[index], control, config)
            }
        }
        return out
    }
}
