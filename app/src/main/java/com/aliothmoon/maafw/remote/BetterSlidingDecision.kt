package com.aliothmoon.maafw.remote

/**
 * BetterSliding 状态机的**判定逻辑**层（对齐上游 bettersliding/handlers.go 里不碰 I/O 的那部分）。
 *
 * 上游 handlers.go 的 8 个 handler 大多在做两件事：读界面（模板匹配/OCR）和改流水线（OverridePipeline）。
 * 剩下的判定——偏移轴、复位方向、1px 累加目标、微调是否触发、目标与滑条上限的关系——
 * 是纯函数，也是这套状态机最容易写错的地方，所以单独拆出来在本机测死。
 *
 * 典型场景：囤货拖到最大后要精确落到某个数量，滑条手柄会被点歪，
 * 于是每轮用 `nudgedClickTarget` 做 1px 级微推、并用 `resolveReset2Side` 决定先把滑块复位到哪一侧。
 */
object BetterSlidingDecision {

    /** 上游 types.go:182 nudgeAxis：偏移作用在哪个轴上。 */
    enum class NudgeAxis(val label: String) {
        X("x"),
        Y("y"),
    }

    /** 上游 types.go:202 reset2Side：复位方向。 */
    enum class Reset2Side(val label: String) {
        TOWARD_START("start"),
        TOWARD_END("end"),
    }

    /** 上游 handlers.go:1038 sliderQuantityOutcome。 */
    enum class SliderQuantityOutcome {
        /** 目标 < 1、滑条上限为 0，或未开钳制却超出上限。 */
        OUT_OF_RANGE,

        /** 目标落在 [1, sliderMaxQuantity] 内，无需钳制即可达成。 */
        TARGET_REACHABLE,

        /** 目标超出上限，已钳制到上限（属部分达成，不算 TARGET_REACHABLE）。 */
        CLAMPED,
    }

    /** 上游 handlers.go:1013 isBetterSlidingActionNode 用的节点集合（上游 nodes.go:30）。 */
    val ACTION_NODES = listOf(
        BetterSlidingSupport.NODE_MAIN,
        BetterSlidingSupport.NODE_FIND_START,
        BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY,
        BetterSlidingSupport.NODE_GET_AVAILABLE_QUANTITY,
        BetterSlidingSupport.NODE_FIND_END,
        BetterSlidingSupport.NODE_CHECK_QUANTITY,
        BetterSlidingSupport.NODE_DONE,
    )

    /** 上游 handlers.go:1013 */
    fun isActionNode(taskName: String): Boolean = taskName in ACTION_NODES

    /** 上游 handlers.go:877 */
    fun absInt(value: Int): Int = if (value < 0) -value else value

    /** 上游 handlers.go:885 */
    fun signInt(value: Int): Int = when {
        value > 0 -> 1
        value < 0 -> -1
        else -> 0
    }

    /**
     * 上游 handlers.go:782 `shouldFineTuneQuantity`。
     *
     * 阈值模式下只有「当前读数离目标不超过阈值」才微调；布尔模式下直接看 enabled。
     */
    fun shouldFineTuneQuantity(
        fineTune: BetterSlidingSupport.FineTuneQuantity,
        current: Int,
        target: Int,
    ): Boolean = if (fineTune.thresholdMode) {
        absInt(current - target) <= fineTune.threshold
    } else {
        fineTune.enabled
    }

    /**
     * 上游 handlers.go:651-663：不微调时按 FineTuneFallback 决定 1px 偏移的方向。
     *
     * more 只在「读数还没到目标」时朝 End 推，less 只在「读数超过目标」时朝 Start 推；
     * 方向条件不成立时返回 0（即不做偏移，直接复检收尾）。
     */
    fun resolveStepSign(fallback: String, current: Int, target: Int): Int = when (fallback) {
        BetterSlidingSupport.FINE_TUNE_FALLBACK_MORE -> if (current < target) 1 else 0
        BetterSlidingSupport.FINE_TUNE_FALLBACK_LESS -> if (current > target) -1 else 0
        else -> 0
    }

    /**
     * 上游 handlers.go:793 `resolveNudgeAxis`：按 Start → End 的方向定轴与正方向。
     *
     * `abs(dx) > abs(dy)` 取 x 轴，否则取 y 轴——**平局取 y**。
     * 两个中心重合时取 y 轴、正方向 +1 兜底（上游此时告警）。
     *
     * 返回 (轴, 正方向符号)。
     */
    fun resolveNudgeAxis(
        startBox: List<Int>?,
        endBox: List<Int>?,
        offset: List<Int>,
    ): Pair<NudgeAxis, Int> {
        val (startX, startY) = center(startBox, offset)
        val (endX, endY) = center(endBox, offset)
        val dx = endX - startX
        val dy = endY - startY

        var axis = NudgeAxis.Y
        if (absInt(dx) > absInt(dy)) axis = NudgeAxis.X

        if (dx == 0 && dy == 0) return axis to 1

        return axis to if (axis == NudgeAxis.X) signInt(dx) else signInt(dy)
    }

    /**
     * 上游 handlers.go:827 `resolveReset2Side`：按点击基准坐标落在 Start → End 轴上的位置选复位方向。
     *
     * 投影比例 < 0.5（靠近 Start）时向 End 侧复位，否则向 Start 侧复位。
     * 轴跨度为 0（或投影正好落在边界）时取 Start 侧。
     *
     * 比例比较用整数运算避开浮点，并随 span 符号翻转不等号。
     */
    fun resolveReset2Side(
        axis: NudgeAxis,
        startBox: List<Int>?,
        endBox: List<Int>?,
        offset: List<Int>,
        base: Pair<Int, Int>,
    ): Reset2Side {
        val (startX, startY) = center(startBox, offset)
        val (endX, endY) = center(endBox, offset)

        val startCoord: Int
        val endCoord: Int
        val baseCoord: Int
        if (axis == NudgeAxis.Y) {
            startCoord = startY
            endCoord = endY
            baseCoord = base.second
        } else {
            startCoord = startX
            endCoord = endX
            baseCoord = base.first
        }

        val span = endCoord - startCoord
        if (span == 0) return Reset2Side.TOWARD_START

        var closerToStart = (baseCoord - startCoord) * 2 < span
        if (span < 0) closerToStart = (baseCoord - startCoord) * 2 > span

        return if (closerToStart) Reset2Side.TOWARD_END else Reset2Side.TOWARD_START
    }

    /**
     * 上游 handlers.go:864 `nudgedClickTarget`：基准坐标沿指定轴做 1px 级累加偏移。
     *
     * `k` 是累计偏移次数，所以偏移量随重试线性增长。
     */
    fun nudgedClickTarget(
        base: Pair<Int, Int>,
        axis: NudgeAxis,
        endSign: Int,
        stepSign: Int,
        k: Int,
    ): Pair<Int, Int> {
        val delta = endSign * stepSign * k
        return if (axis == NudgeAxis.X) {
            (base.first + delta) to base.second
        } else {
            base.first to (base.second + delta)
        }
    }

    /**
     * 上游 handlers.go:1063 `resolveSliderQuantityOutcome`。
     *
     * 三种结果互斥，按优先级短路：
     *  1. 目标 < 1 或滑条上限为 0 → OUT_OF_RANGE；
     *  2. 目标 > 上限 → 开了钳制则下调到上限并返回 CLAMPED，否则 OUT_OF_RANGE；
     *  3. 其余 → TARGET_REACHABLE。
     *
     * 返回 (实际使用的目标数量, 判定结果)。除 CLAMPED 外数量都是入参原值。
     */
    fun resolveSliderQuantityOutcome(
        targetQuantity: Int,
        sliderMaxQuantity: Int,
        clampTargetToSliderMax: Boolean,
    ): Pair<Int, SliderQuantityOutcome> {
        if (targetQuantity < 1 || sliderMaxQuantity == 0) {
            return targetQuantity to SliderQuantityOutcome.OUT_OF_RANGE
        }
        if (targetQuantity > sliderMaxQuantity) {
            return if (clampTargetToSliderMax) {
                sliderMaxQuantity to SliderQuantityOutcome.CLAMPED
            } else {
                targetQuantity to SliderQuantityOutcome.OUT_OF_RANGE
            }
        }
        return targetQuantity to SliderQuantityOutcome.TARGET_REACHABLE
    }

    /**
     * 上游 handlers.go:1081 `resolveSliderMaxQuantityNext`。
     *
     * 上限与目标相等时可以收尾；上限低于目标是配置矛盾，直接失败；
     * 其余情况返回 null，表示不需要改写 next（继续走精确点击流程）。
     */
    fun resolveSliderMaxQuantityNext(sliderMaxQuantity: Int, targetQuantity: Int): String? {
        if (sliderMaxQuantity == targetQuantity) return BetterSlidingSupport.NODE_DONE
        require(sliderMaxQuantity >= targetQuantity) {
            "slider max quantity $sliderMaxQuantity lower than target quantity $targetQuantity"
        }
        return null
    }

    /**
     * 上游 handlers.go:1100 `shouldResetBeforePreciseClick`：
     * 目标是否严格大于滑条上限的 80%（用整数比较避开浮点误差）。
     */
    fun shouldResetBeforePreciseClick(targetQuantity: Int, sliderMaxQuantity: Int): Boolean {
        if (targetQuantity <= 0 || sliderMaxQuantity <= 1 || targetQuantity >= sliderMaxQuantity) return false
        return targetQuantity * 5 > sliderMaxQuantity * 4
    }

    /** 上游 handlers.go:554-631 的三个出口。 */
    enum class QuantityBranch { DONE, INCREASE, DECREASE }

    /** 一次复查的处置方案：走哪个节点、点几次。 */
    data class QuantityBranchPlan(
        val branch: QuantityBranch,
        val nextNode: String,
        val repeat: Int,
    )

    /**
     * 上游 handlers.go:554-631：把「当前读数 vs 目标」翻成出口。
     *
     * 差值直接当 repeat（连点加减按钮的次数），并按 [MAX_CLICK_REPEAT] 夹取——
     * 差得太多时宁可点 30 次，也不要让一次动作点上百下。
     */
    fun resolveQuantityBranch(currentQuantity: Int, targetQuantity: Int): QuantityBranchPlan = when {
        currentQuantity == targetQuantity ->
            QuantityBranchPlan(QuantityBranch.DONE, BetterSlidingSupport.NODE_DONE, 0)

        currentQuantity < targetQuantity -> QuantityBranchPlan(
            QuantityBranch.INCREASE,
            BetterSlidingSupport.NODE_INCREASE_QUANTITY,
            BetterSlidingSupport.clampClickRepeat(targetQuantity - currentQuantity),
        )

        else -> QuantityBranchPlan(
            QuantityBranch.DECREASE,
            BetterSlidingSupport.NODE_DECREASE_QUANTITY,
            BetterSlidingSupport.clampClickRepeat(currentQuantity - targetQuantity),
        )
    }

    /**
     * Go 的 `math.Round`：**半数远离零**。
     *
     * 别直接用 `Math.round`——它是 `floor(x + 0.5)`，负数会向零靠拢：
     * `math.Round(-2.5)` 是 -3，而 `Math.round(-2.5)` 是 -2。
     * 精确点击的位移可能为负（滑条从右往左），这里差一像素就会点歪。
     */
    fun goRound(value: Double): Int =
        (if (value < 0) -Math.round(-value) else Math.round(value)).toInt()

    /**
     * 上游 handlers.go:452-465：算出滑条上精确落在目标数量那一格的点击坐标。
     *
     * 关键是这个 `-1`：数量 1 对应 Start，数量 sliderMax 对应 End，
     * 所以分子分母都要先减 1，否则最高档会算出滑条之外的位置。
     */
    fun preciseClick(
        startBox: List<Int>?,
        endBox: List<Int>?,
        offset: List<Int>,
        targetQuantity: Int,
        sliderMaxQuantity: Int,
    ): Pair<Int, Int> {
        require(sliderMaxQuantity >= 1) {
            "invalid slider max quantity for precise click calculation: $sliderMaxQuantity"
        }
        require((startBox?.size ?: 0) >= 4) { "start box is invalid" }
        require((endBox?.size ?: 0) >= 4) { "end box is invalid" }

        val denominator = sliderMaxQuantity - 1
        require(denominator != 0) {
            "denominator is zero in precise click calculation (slider_max_quantity=$sliderMaxQuantity)"
        }

        val (startX, startY) = center(startBox, offset)
        val (endX, endY) = center(endBox, offset)
        val numerator = targetQuantity - 1

        val clickX = startX + goRound((endX - startX).toDouble() * numerator / denominator)
        val clickY = startY + goRound((endY - startY).toDouble() * numerator / denominator)
        return clickX to clickY
    }

    /**
     * 上游 handlers.go:75-95：进入主流程前的参数体检。
     *
     * 定量模式必须有合法 SliderQuantity.Box；显式给了 AvailableQuantity 就必须有它的 Box；
     * 方向必须受支持。
     */
    fun validateMainInputs(
        swipeOnlyMode: Boolean,
        sliderQuantityBox: List<Int>,
        availableQuantityBox: List<Int>,
        availableQuantityExplicit: Boolean,
        direction: String,
    ) {
        if (!swipeOnlyMode) {
            require(sliderQuantityBox.size == 4) {
                "invalid slider quantity box, expected [x,y,w,h], got $sliderQuantityBox"
            }
        }
        if (availableQuantityExplicit) {
            require(availableQuantityBox.size == 4) {
                "invalid available quantity box, expected [x,y,w,h], got $availableQuantityBox"
            }
        }
        require(direction in BetterSlidingSupport.SWIPE_DIRECTIONS) { "invalid direction \"$direction\"" }
    }

    /**
     * 上游 handlers.go:1023 `resetState`：一次内部子流水线开始前清空所有跨节点状态。
     *
     * `preciseClickNudges` 尤其要清——它是 1px 累加偏移的**次数**，
     * 跨轮次继承会让下一轮一上来就偏移好几像素。
     */
    class ActionState {
        var startBox: List<Int>? = null
        var endBox: List<Int>? = null
        var preciseClickBase: Pair<Int, Int> = 0 to 0
        var preciseClickNudges: Int = 0
        var sliderMaxQuantity: Int = 0
        var availableQuantity: Int = 0
        var availableQuantityResolved: Boolean = false
        var outOfRange: Boolean = false
        var targetReachable: Boolean = false
        var minimumTargetShortCircuit: Boolean = false
        var runtimeTargetResolved: Boolean = false

        /** 上游 types.go:119：参数里写的原始目标，也是重算的基准。 */
        var originalTargetQuantity: Int = 0

        /** 上游 types.go:97：本次实际使用的目标，可能已被运行时解析改写。 */
        var targetQuantity: Int = 0

        /**
         * 上游 params.go:283-287 `applyActionParams` 的前两行。
         *
         * **注意这个守卫**：原始目标每次都要更新（它是重算基准），
         * 而实际目标只在「运行时还没解析过」时才跟着改。
         *
         * 为什么必须有它：内部子流水线里**每个**节点调用 Run 都会重新加载参数
         * （`Run` → `loadActionParams`）。等到 `BetterSlidingGetSliderMaxQuantity`
         * 用滑条上限把目标解析出来（Percentage / Reverse 都会重算）之后，
         * 后面 `FindEnd`、`CheckQuantity` 再加载参数时，若不加守卫就会把
         * 已经解析好的目标覆盖回原始值，数量当场算错。
         */
        fun applyParsedTarget(parsedTargetQuantity: Int) {
            originalTargetQuantity = parsedTargetQuantity
            if (!runtimeTargetResolved) targetQuantity = parsedTargetQuantity
        }

        /**
         * 上游 handlers.go:1023 `resetState`。
         *
         * **`targetQuantity` / `originalTargetQuantity` 故意不在这里清**——
         * 上游也没清。`resetState` 只清 `runtimeTargetResolved`，于是 `handleMain`
         * 之后的节点重新加载参数时会把 `targetQuantity` 设回原始值，这正是所要的。
         * 顺手把它们也清了会让「重置」变成丢掉基准，反而不对。
         */
        fun reset() {
            startBox = null
            endBox = null
            preciseClickBase = 0 to 0
            preciseClickNudges = 0
            sliderMaxQuantity = 0
            availableQuantity = 0
            availableQuantityResolved = false
            outOfRange = false
            targetReachable = false
            minimumTargetShortCircuit = false
            runtimeTargetResolved = false
        }
    }


    /** `centerPoint` 的空值友好包装：矩形为 null 时按上游等价于 (0,0)。 */
    private fun center(rect: List<Int>?, offset: List<Int>): Pair<Int, Int> =
        BetterSlidingSupport.centerPoint(rect.orEmpty(), offset)
}
