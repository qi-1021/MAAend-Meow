package com.aliothmoon.maafw.remote

/**
 * `autoEcoFarmCalculateSwipeTarget` / `autoEcoFarmResetSwipeState` 的纯逻辑。
 *
 * 上游对应：
 *  - `autoecofarm/scaletarget.go`（119 行）—— 按目标 ROI 与「拉近比例」算出 swipe 终点坐标
 *  - `autoecofarm/state.go`（53 行）—— 两次调用之间共享的 lastRoi / stepRatio
 *  - `autoecofarm/register.go` 的 `autoEcoFarmResetSwipeState`
 *
 * 这里不碰 MaaFramework/JNA/Android：截图尺寸与 ROI 由 MaaRunner 取好传进来，
 * 结果写回 outBox 也在 MaaRunner。几何计算与状态机可本机单测。
 *
 * 两个容易写错、写错只表现为「视角转歪」的点：
 *  1. 一旦有历史状态，**两个轴**的比例都改用历史值，本次传入的 `xStepRatio/yStepRatio` 被忽略；
 *  2. 越界判定用的是目标中心相对屏幕中心的**符号翻转**（`lastDx*currDx < 0`），
 *     且 `lastDx`/`currDx` 为 0 时不判定（上游 `if lastDx != 0 && currDx != 0`）。
 */
object AutoEcoFarmSwipe {

    /** 上游 `autoEcoFarmStepRatioDecay`。 */
    const val STEP_RATIO_DECAY = 0.9

    /** 上游 `autoEcoFarmStepRatioMin`。 */
    const val STEP_RATIO_MIN = 0.1

    /** 上游参数缺省值。 */
    const val DEFAULT_STEP_RATIO = 0.5

    /** 上游 `autoEcoFarmCalculateSwipeTargetParams`。 */
    data class Param(val xStepRatio: Double, val yStepRatio: Double) {
        companion object {
            val DEFAULT = Param(DEFAULT_STEP_RATIO, DEFAULT_STEP_RATIO)
        }
    }

    /** 上游 `maa.Rect`（x, y, w, h）。 */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val centerX: Double get() = x + w / 2.0
        val centerY: Double get() = y + h / 2.0
    }

    /** 上游 `swipeTargetState` 的快照。 */
    data class State(val lastRoi: Rect, val xStepRatio: Double, val yStepRatio: Double)

    /** 上游 `state.go` 的包级单例。 */
    @Volatile
    private var lastState: State? = null

    /** 最近一次缓存状态的快照（上游 `getLastState`）。 */
    fun lastState(): State? = lastState

    /** 保存最新 ROI 与 StepRatio（上游 `setLastState`）。 */
    fun setState(roi: Rect, xStepRatio: Double, yStepRatio: Double) {
        lastState = State(roi, xStepRatio, yStepRatio)
    }

    /** `autoEcoFarmResetSwipeState`（上游 `ResetSwipeTargetState`）。 */
    fun resetState() {
        lastState = null
    }

    // ───────────────────────── 参数 ─────────────────────────

    /**
     * 解析 `custom_recognition_param`。
     *
     * 上游用 `json.Unmarshal` 到结构体，语义是：
     *  - 空串 → 跳过解析，保留默认 0.5/0.5；
     *  - 字段缺失或为 JSON null → 保留默认（Go 把 null 解到非指针类型是 no-op）；
     *  - 字段存在但类型不对 → 整节点失败。
     *
     * 所以返回 null 表示「失败」，与「空参数用默认」区分开。
     */
    fun parseParam(raw: String?): Param? {
        if (raw.isNullOrEmpty()) return Param.DEFAULT
        val map = MaaJsonTree.parse(raw) as? Map<*, *> ?: return null
        val x = ratioOf(map, "xStepRatio") ?: return null
        val y = ratioOf(map, "yStepRatio") ?: return null
        return Param(x, y)
    }

    private fun ratioOf(map: Map<*, *>, key: String): Double? {
        if (!map.containsKey(key)) return DEFAULT_STEP_RATIO
        val value = map[key] ?: return DEFAULT_STEP_RATIO
        return (value as? Number)?.toDouble() ?: return null
    }

    // ───────────────────────── 计算 ─────────────────────────

    data class Result(val box: Rect, val state: State)

    /**
     * 上游 scaletarget.go 的主体，**不产生副作用**：把历史状态与本次 ROI 一起算进去，
     * 返回要回写的矩形与下一步该保存的状态。
     *
     * @param screenW/screenH 截图尺寸（上游 `arg.Img.Bounds()`）
     * @param roi 识别节点的 ROI（上游 `arg.Roi`）
     * @param last 上一次的状态快照，可为 null
     */
    fun computeTarget(
        screenW: Int,
        screenH: Int,
        roi: Rect,
        param: Param,
        last: State?,
    ): Result {
        val screenCenterX = screenW / 2.0
        val screenCenterY = screenH / 2.0

        var xStepRatio = param.xStepRatio
        var yStepRatio = param.yStepRatio

        if (last != null) {
            // 有历史状态时两个轴都改用历史比例
            xStepRatio = last.xStepRatio
            yStepRatio = last.yStepRatio

            val lastDx = last.lastRoi.centerX - screenCenterX
            val lastDy = last.lastRoi.centerY - screenCenterY
            val currDx = roi.centerX - screenCenterX
            val currDy = roi.centerY - screenCenterY

            if (lastDx != 0.0 && currDx != 0.0 && lastDx * currDx < 0) {
                xStepRatio = maxOf(xStepRatio * STEP_RATIO_DECAY, STEP_RATIO_MIN)
            }
            if (lastDy != 0.0 && currDy != 0.0 && lastDy * currDy < 0) {
                yStepRatio = maxOf(yStepRatio * STEP_RATIO_DECAY, STEP_RATIO_MIN)
            }
        }

        xStepRatio = xStepRatio.coerceIn(STEP_RATIO_MIN, 1.0)
        yStepRatio = yStepRatio.coerceIn(STEP_RATIO_MIN, 1.0)

        val dx = roi.centerX - screenCenterX
        val dy = roi.centerY - screenCenterY
        val targetX = (screenCenterX + dx * xStepRatio).toInt()
        val targetY = (screenCenterY + dy * yStepRatio).toInt()

        return Result(Rect(targetX, targetY, 1, 1), State(roi, xStepRatio, yStepRatio))
    }

    /** 带全局状态副作用的入口（上游 `Run` 末尾会 setLastState）。 */
    fun run(roi: Rect, param: Param, screenW: Int, screenH: Int): Rect {
        val result = computeTarget(screenW, screenH, roi, param, lastState)
        lastState = result.state
        return result.box
    }
}
