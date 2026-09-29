package com.aliothmoon.maafw.remote

import kotlin.math.abs

/**
 * `CameraScanAction` 的纯逻辑：参数解析、扫描路径（步数/方向）、命中后的对准几何、
 * 以及「转几圈 / 何时放弃 / 超时怎么算」的终止判定。
 *
 * 上游对应 `MaaEnd/agent/go-service/common/camerascan/`：
 *  - `action.go`：参数结构 `cameraScanParam`、`parseParam`（默认值与 4..72 范围校验）、
 *    `aimDelta`（命中框中心相对屏幕中心的偏移 + clamp）、成功/失败语义；
 *  - `path.go`：九宫格 → 复位 → 三个俯仰环（中/上/下）的步进路径；
 *  - `register.go`：注册名 `CameraScanAction`。
 *
 * 这里不碰 MaaFramework/JNA/Android：截图、识别与触摸转动都在 [MaaRunner] 侧完成，
 * 本对象只做可本机单测的决策。移动方向与像素量对齐上游默认移动节点
 * `Common/Private/CameraScan/Action.json`（上下 240px、左右 160px）。
 *
 * 两个最容易写错、写错只表现为「扫不到」或「扫过头」的点：
 *  1. 除复位步外，**每一步移动前后各识别一次**（步内先识别、再移动、再识别）；
 *  2. 复位步 `needsRecognition=false`：只把镜头拉回原点，不做识别。
 */
object CameraScanSupport {

    /** 注册名，对齐上游 `componentName`。 */
    const val COMPONENT = "CameraScanAction"

    /** 上游默认移动节点名（schema 的缺省值）。 */
    const val DEFAULT_MOVE_UP = "__CameraScanMoveUp"
    const val DEFAULT_MOVE_DOWN = "__CameraScanMoveDown"
    const val DEFAULT_MOVE_LEFT = "__CameraScanMoveLeft"
    const val DEFAULT_MOVE_RIGHT = "__CameraScanMoveRight"

    /** 上游默认 `fallback_yaw_steps`。 */
    const val DEFAULT_FALLBACK_YAW_STEPS = 8

    /** 上游范围校验 `fallback_yaw_steps < 4 || > 72`。 */
    const val MIN_FALLBACK_YAW_STEPS = 4
    const val MAX_FALLBACK_YAW_STEPS = 72

    /** 目标框中心的参考屏幕尺寸：上游固定 640×360。 */
    const val SCREEN_CENTER_X = 640
    const val SCREEN_CENTER_Y = 360

    /** 默认移动节点单次滑动的像素量（对齐 Action.json 的 begin→end）。 */
    const val MOVE_VERTICAL_PIXELS = 240
    const val MOVE_HORIZONTAL_PIXELS = 160

    /**
     * 单步超时预算（防御性上限，非上游语义）。
     *
     * 上游没有总超时，靠 MaaFramework 的单次识别/动作超时兜底。移动端一次识别
     * （截图 + 模板匹配）比 PC 慢，这里给每步一个宽裕的上限，用于任务被挂死时
     * 能返回失败而不是永远卡住。默认 36 步 × 4s ≈ 144s。
     */
    const val DEFAULT_PER_STEP_MS = 4_000L

    /** 扫描阶段，对齐上游 `path.go` 的 phase 常量。 */
    enum class Phase {
        NINE_GRID,
        RESET,
        FALLBACK_MID,
        FALLBACK_UP,
        FALLBACK_DOWN,
    }

    /** 单步：先沿 pitch 方向移动 [pitchDelta] 个单位，再沿 yaw 方向移动 [yawDelta] 个单位。 */
    data class Step(val yawDelta: Int, val pitchDelta: Int, val phase: Phase) {
        /** 复位步不做识别（上游 `needsRecognition`）。 */
        val needsRecognition: Boolean get() = phase != Phase.RESET
    }

    /** 移动方向（与像素增量解耦，便于单测步进序列）。 */
    enum class Move { UP, DOWN, LEFT, RIGHT }

    /** 上游 `maa.Rect`（x, y, w, h）。 */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    /** 上游 `cameraScanParam`。 */
    data class Param(
        val waitNodes: List<String>,
        val aimTarget: Boolean,
        val fallbackYawSteps: Int,
        val moveUp: String,
        val moveDown: String,
        val moveLeft: String,
        val moveRight: String,
    ) {
        companion object {
            fun defaults(waitNodes: List<String>): Param = Param(
                waitNodes = waitNodes,
                aimTarget = false,
                fallbackYawSteps = DEFAULT_FALLBACK_YAW_STEPS,
                moveUp = DEFAULT_MOVE_UP,
                moveDown = DEFAULT_MOVE_DOWN,
                moveLeft = DEFAULT_MOVE_LEFT,
                moveRight = DEFAULT_MOVE_RIGHT,
            )
        }
    }

    // ───────────────────────── 参数解析 ─────────────────────────

    /**
     * 解析 `custom_action_param`。返回 null 表示整节点失败（对齐上游 `parseParam` 返回 false）：
     *  - 非 JSON / 空串 / 顶层不是对象 → 失败；
     *  - `wait_nodes` 缺失、非数组、含非字符串元素或为空 → 失败（上游要求必填且 minItems=1）；
     *  - `aim_target` / `move_*` / `fallback_yaw_steps` 类型不对 → 失败；
     *  - `move_*` 缺失、null 或空串 → 用默认节点名；
     *  - `fallback_yaw_steps` 缺失、null 或显式 0 → 用默认 8；其余必须落在 4..72，否则失败。
     */
    fun parseParam(raw: String?): Param? {
        val map = MaaJsonTree.parse(raw) as? Map<*, *> ?: return null

        val waitRaw = map["wait_nodes"]
        val waitNodes = when (waitRaw) {
            null -> return null
            is List<*> -> {
                val nodes = waitRaw.map { it as? String ?: return null }
                if (nodes.isEmpty()) return null
                nodes
            }

            else -> return null
        }

        val aimTarget = when (val v = map["aim_target"]) {
            null -> false
            is Boolean -> v
            else -> return null
        }

        val fallback = when (val v = map["fallback_yaw_steps"]) {
            null -> DEFAULT_FALLBACK_YAW_STEPS
            is Long -> when {
                v == 0L -> DEFAULT_FALLBACK_YAW_STEPS
                v < MIN_FALLBACK_YAW_STEPS || v > MAX_FALLBACK_YAW_STEPS -> return null
                else -> v.toInt()
            }

            else -> return null
        }

        val moveUp = stringOrDefault(map, "move_up", DEFAULT_MOVE_UP) ?: return null
        val moveDown = stringOrDefault(map, "move_down", DEFAULT_MOVE_DOWN) ?: return null
        val moveLeft = stringOrDefault(map, "move_left", DEFAULT_MOVE_LEFT) ?: return null
        val moveRight = stringOrDefault(map, "move_right", DEFAULT_MOVE_RIGHT) ?: return null

        return Param(waitNodes, aimTarget, fallback, moveUp, moveDown, moveLeft, moveRight)
    }

    /** 缺失/null/空串 → 默认；字符串 → 原值；其余类型 → null（失败）。 */
    private fun stringOrDefault(map: Map<*, *>, key: String, default: String): String? {
        val v = map[key] ?: return default
        return when (v) {
            is String -> v.ifEmpty { default }
            else -> null
        }
    }

    // ───────────────────────── 扫描路径 ─────────────────────────

    /** 上游 `nineGridSteps`（8 步，净位移 yaw=0 / pitch=-1）。 */
    private val NINE_GRID_STEPS = listOf(
        Step(yawDelta = 0, pitchDelta = -1, phase = Phase.NINE_GRID),
        Step(yawDelta = 1, pitchDelta = 0, phase = Phase.NINE_GRID),
        Step(yawDelta = 0, pitchDelta = 1, phase = Phase.NINE_GRID),
        Step(yawDelta = 0, pitchDelta = 1, phase = Phase.NINE_GRID),
        Step(yawDelta = -1, pitchDelta = 0, phase = Phase.NINE_GRID),
        Step(yawDelta = -1, pitchDelta = 0, phase = Phase.NINE_GRID),
        Step(yawDelta = 0, pitchDelta = -1, phase = Phase.NINE_GRID),
        Step(yawDelta = 0, pitchDelta = -1, phase = Phase.NINE_GRID),
    )

    /**
     * 上游 `buildCameraScanPath`：九宫格 8 步 → 1 步复位（净位移取反，不识别）→
     * 中/上/下三个俯仰环，每环 = 1 步俯仰 + [fallbackYawSteps] 步右转（每步都识别）。
     */
    fun buildPath(fallbackYawSteps: Int): List<Step> {
        val steps = ArrayList<Step>(stepCount(fallbackYawSteps))
        steps += NINE_GRID_STEPS

        var yaw = 0
        var pitch = 0
        for (s in NINE_GRID_STEPS) {
            yaw += s.yawDelta
            pitch += s.pitchDelta
        }
        steps += Step(yawDelta = -yaw, pitchDelta = -pitch, phase = Phase.RESET)

        steps += appendFallbackRing(0, Phase.FALLBACK_MID, fallbackYawSteps)
        steps += appendFallbackRing(-1, Phase.FALLBACK_UP, fallbackYawSteps)
        steps += appendFallbackRing(2, Phase.FALLBACK_DOWN, fallbackYawSteps)
        return steps
    }

    /** 路径总步数：8 步九宫格 + 1 步复位 + 3 个环 × (1 + fallbackYawSteps)。 */
    fun stepCount(fallbackYawSteps: Int): Int = 8 + 1 + 3 * (1 + fallbackYawSteps)

    /** 「转几圈」：三个俯仰环合计的水平步数与每个环的步数（含首步俯仰）由 [fallbackYawSteps] 决定。 */
    fun yawStepsPerRing(fallbackYawSteps: Int): Int = fallbackYawSteps

    private fun appendFallbackRing(pitchDelta: Int, phase: Phase, yawSteps: Int): List<Step> {
        val out = ArrayList<Step>(yawSteps + 1)
        out += Step(yawDelta = 0, pitchDelta = pitchDelta, phase = phase)
        repeat(yawSteps) { out += Step(yawDelta = 1, pitchDelta = 0, phase = phase) }
        return out
    }

    /**
     * 单步的移动序列，顺序对齐上游 `runCameraSwipe`：先 pitch（up/down）后 yaw（left/right）。
     */
    fun movesFor(step: Step): List<Move> {
        val out = ArrayList<Move>(abs(step.pitchDelta) + abs(step.yawDelta))
        repeat(abs(step.pitchDelta)) {
            out += if (step.pitchDelta < 0) Move.UP else Move.DOWN
        }
        repeat(abs(step.yawDelta)) {
            out += if (step.yawDelta < 0) Move.LEFT else Move.RIGHT
        }
        return out
    }

    /**
     * 单个移动方向对应的触摸像素增量（对齐上游默认移动节点，见 Action.json）。
     *
     * 约定与 [MotionSupport.rotateView] 一致：`dx>0` 向右转、`dy>0` 低头。
     */
    fun pixelDelta(move: Move): Pair<Int, Int> = when (move) {
        Move.UP -> 0 to -MOVE_VERTICAL_PIXELS
        Move.DOWN -> 0 to MOVE_VERTICAL_PIXELS
        Move.LEFT -> -MOVE_HORIZONTAL_PIXELS to 0
        Move.RIGHT -> MOVE_HORIZONTAL_PIXELS to 0
    }

    // ───────────────────────── 命中对准 ─────────────────────────

    /**
     * 上游 `aimDelta`：命中框中心相对屏幕中心的偏移，并 clamp 到
     * `[-640, 639] × [-360, 359]`；框宽/高 ≤ 0 时返回 null（上游返回 false）。
     */
    fun aimDelta(box: Rect): Pair<Int, Int>? {
        if (box.w <= 0 || box.h <= 0) return null
        val targetCenterX = box.x + box.w / 2
        val targetCenterY = box.y + box.h / 2
        val dx = (targetCenterX - SCREEN_CENTER_X)
            .coerceIn(-SCREEN_CENTER_X, SCREEN_CENTER_X - 1)
        val dy = (targetCenterY - SCREEN_CENTER_Y)
            .coerceIn(-SCREEN_CENTER_Y, SCREEN_CENTER_Y - 1)
        return dx to dy
    }

    // ───────────────────────── 终止与超时 ─────────────────────────

    /** 扫描预算：总步数与防御性总超时。 */
    data class Budget(val totalSteps: Int, val timeoutMs: Long)

    fun budget(fallbackYawSteps: Int, perStepMs: Long = DEFAULT_PER_STEP_MS): Budget {
        val steps = stepCount(fallbackYawSteps)
        return Budget(steps, steps * perStepMs)
    }

    /** 放弃原因（[NONE] 表示继续）。 */
    enum class StopReason { NONE, STOPPING, TIMEOUT, EXHAUSTED }

    /**
     * 第 [stepIndex] 步开始前是否应放弃。
     *
     * 优先级：用户/任务停止 > 步数耗尽 > 总超时。`stepIndex` 从 0 计。
     */
    fun stopReason(stepIndex: Int, budget: Budget, stopping: Boolean, elapsedMs: Long): StopReason = when {
        stopping -> StopReason.STOPPING
        stepIndex >= budget.totalSteps -> StopReason.EXHAUSTED
        elapsedMs > budget.timeoutMs -> StopReason.TIMEOUT
        else -> StopReason.NONE
    }

    /**
     * 是否存在自定义移动节点覆盖。Android 端移动统一走 [MotionSupport] 的触摸转动，
     * 无法解释任意节点名；有覆盖时返回提示串，由调用方明确告警（不静默）。
     */
    fun customMoveWarn(param: Param): String? {
        val overridden = buildList {
            if (param.moveUp != DEFAULT_MOVE_UP) add("move_up=${param.moveUp}")
            if (param.moveDown != DEFAULT_MOVE_DOWN) add("move_down=${param.moveDown}")
            if (param.moveLeft != DEFAULT_MOVE_LEFT) add("move_left=${param.moveLeft}")
            if (param.moveRight != DEFAULT_MOVE_RIGHT) add("move_right=${param.moveRight}")
        }
        if (overridden.isEmpty()) return null
        return "自定义移动节点 ${overridden.joinToString()} 在移动端被忽略，" +
            "改用内置相机转动（对齐上游默认节点像素量）"
    }
}
