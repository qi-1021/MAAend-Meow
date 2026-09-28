package com.aliothmoon.maafw.remote

/**
 * BetterSliding 的纯逻辑层。
 *
 * 上游 `upstream/maaend/agent/go-service/bettersliding/` 是一个约 2658 行的 Go 包，
 * 它把「滑条选数量」拆成两个部分：
 *  1. 一组固定的流水线节点（`resource/pipeline/BetterSliding/Main.json`）负责滑动/点击；
 *  2. Go 侧 8 个 handler 按「当前是哪个节点在调用我」来驱动那张状态机。
 *
 * 移动端目前把 `BetterSliding` 注册成 noop 成功，后果不是「少个功能」而是**买错数量**：
 * 据点交易 6 个据点、囤货、稳定物资购买全都 `enabled=true` 地依赖它，滑条会停在默认值。
 *
 * 这里只落盘**不依赖设备就能验证**的那一层：参数归一化与目标数量计算
 * （对应上游 normalize.go 全部 341 行 + types.go 的常量/语义）。
 * 它被 handlers.go 大量调用，先把它测死，后面接状态机才不会一边猜一边错。
 */
object BetterSlidingSupport {

    /** 上游 types.go:151 */
    const val MAX_CLICK_REPEAT = 30

    /**
     * 上游 types.go:159。
     *
     * 取 Int.MAX_VALUE：远大于任何真实数量差值，且能被 Double 精确表示。
     * 阈值超过它时不再转 Int，而是饱和为「始终微调」——旧实现钳到 float64(math.MaxInt)
     * 再转换会回绕成 math.MinInt，把「超大阈值」反转成「从不微调」。
     */
    const val MAX_FINE_TUNE_THRESHOLD = Int.MAX_VALUE

    /** 上游 types.go:230-233 */
    const val TARGET_QUANTITY_TYPE_VALUE = "Value"
    const val TARGET_QUANTITY_TYPE_PERCENTAGE = "Percentage"

    /** 上游 types.go:172-178 */
    const val FINE_TUNE_FALLBACK_NONE = "none"
    const val FINE_TUNE_FALLBACK_MORE = "more"
    const val FINE_TUNE_FALLBACK_LESS = "less"

    /** 上游 types.go:235 */
    val DEFAULT_CENTER_POINT_OFFSET = listOf(-10, 0)

    // ---- 上游 nodes.go: 状态机节点名。Kotlin 侧驱动的是同一张既有流水线。 ----

    const val NODE_MAIN = "BetterSlidingMain"
    const val NODE_FIND_START = "BetterSlidingFindStart"
    const val NODE_FIND_SWIPE_FOR_RESET = "BetterSlidingFindSwipeForReset"
    const val NODE_GET_SLIDER_MAX_QUANTITY = "BetterSlidingGetSliderMaxQuantity"
    const val NODE_GET_AVAILABLE_QUANTITY = "BetterSlidingGetAvailableQuantity"
    const val NODE_FIND_END = "BetterSlidingFindEnd"
    const val NODE_CHECK_QUANTITY = "BetterSlidingCheckQuantity"
    const val NODE_DONE = "BetterSlidingDone"
    const val NODE_CLEAR_MAX_HIT = "BetterSlidingClearMaxHit"
    const val NODE_RESET = "BetterSlidingReset"
    const val NODE_RESET2 = "BetterSlidingReset2"
    const val NODE_PRECISE_CLICK = "BetterSlidingPreciseClick"
    const val NODE_SWIPE_TO_MAX = "BetterSlidingSwipeToMax"
    const val NODE_SWIPE_BUTTON = "BetterSlidingSwipeButton"
    const val NODE_SLIDER_QUANTITY_FILTER = "BetterSlidingSliderQuantityFilter"
    const val NODE_GET_SLIDER_QUANTITY = "BetterSlidingGetSliderQuantity"
    const val NODE_AVAILABLE_QUANTITY_FILTER = "BetterSlidingAvailableQuantityFilter"
    const val NODE_INCREASE_QUANTITY = "BetterSlidingIncreaseQuantity"
    const val NODE_DECREASE_QUANTITY = "BetterSlidingDecreaseQuantity"
    const val NODE_INCREASE_BUTTON = "BetterSlidingIncreaseButton"
    const val NODE_DECREASE_BUTTON = "BetterSlidingDecreaseButton"
    const val NODE_JUMP_BACK_NODE = "BetterSlidingJumpBackNode"
    const val NODE_MOVE_MOUSE = "BetterSlidingMoveMouse"

    /** 上游 types.go:239：滑条/按钮模板匹配固定开启绿色掩码。 */
    const val DEFAULT_GREEN_MASK = true

    /** 上游 overrides.go:19：朝最大侧拖动的终点矩形。 */
    val SWIPE_END_MAX = listOf(1260, 10, 10, 10)

    /** 上游 overrides.go:31：复位滑动用的最小侧终点矩形。 */
    val SWIPE_END_MIN = listOf(10, 700, 10, 10)

    /** 上游支持的全部滑动方向。 */
    val SWIPE_DIRECTIONS = setOf("left", "right", "up", "down")

    /** 数量 OCR 的颜色阈值；method 与 MaaFramework 的 color_filter 一致。 */
    data class QuantityFilter(val lower: List<Int>, val upper: List<Int>, val method: Int)

    /**
     * 上游 types.go:162-169 的 fineTuneQuantity 归一化载体。
     *
     * `thresholdMode=false` 时是布尔语义：`enabled` 即「是否总是微调」。
     * `thresholdMode=true` 时只有差值 <= `threshold` 才微调。
     */
    data class FineTuneQuantity(
        val thresholdMode: Boolean = false,
        val enabled: Boolean = false,
        val threshold: Int = 0,
    ) {
        /** 仅用于日志，对应上游 modeLabel() */
        fun modeLabel(): String = if (thresholdMode) "threshold" else "bool"

        companion object {
            /** 上游 types.go:169：未提供 FineTuneQuantity 时的默认行为是「始终微调」 */
            val DEFAULT = FineTuneQuantity(enabled = true)
        }
    }

    /** 对应上游 betterSlidingParamPresence：只记「调用方有没有显式给这个键」。 */
    data class Presence(
        val targetQuantity: Boolean = false,
        val sliderQuantity: Boolean = false,
        val availableQuantity: Boolean = false,
        val direction: Boolean = false,
        val increaseButton: Boolean = false,
        val decreaseButton: Boolean = false,
        val swipeButton: Boolean = false,
        val outOfRangeOverrideEnable: Boolean = false,
        val targetReachableOverrideEnable: Boolean = false,
        val targetQuantityType: Boolean = false,
        val reverseTarget: Boolean = false,
        val centerPointOffset: Boolean = false,
        val clampTargetToSliderMax: Boolean = false,
        val fineTuneQuantity: Boolean = false,
        val fineTuneFallback: Boolean = false,
        val resetBeforeFindStart: Boolean = false,
    )

    /** 上游 normalize.go:11 */
    fun clampClickRepeat(repeat: Int): Int = repeat.coerceIn(0, MAX_CLICK_REPEAT)

    /** 上游 normalize.go:22：按钮只能是 [x,y] 或 [x,y,w,h]。 */
    fun normalizeButton(button: List<Int>): List<Int> = when (button.size) {
        2 -> listOf(button[0], button[1], 1, 1)
        4 -> listOf(button[0], button[1], button[2], button[3])
        else -> throw IllegalArgumentException("button must be [x,y] or [x,y,w,h], got len=${button.size}")
    }

    /** 上游 normalize.go:56 */
    fun normalizeCenterPointOffset(raw: List<Int>?): List<Int> {
        if (raw == null) return DEFAULT_CENTER_POINT_OFFSET
        if (raw.size != 2) {
            throw IllegalArgumentException("centerPointOffset must be [x,y], got len=${raw.size}")
        }
        return listOf(raw[0], raw[1])
    }

    /** 上游 normalize.go:111：method 4/40 是 RGB/HSV 三通道，6 是灰度单通道。 */
    fun quantityFilterChannelCount(method: Int): Int = when (method) {
        4, 40 -> 3
        6 -> 1
        else -> throw IllegalArgumentException(
            "unsupported QuantityFilter method $method, expected 4 (RGB), 40 (HSV), or 6 (GRAY)",
        )
    }

    /** 上游 normalize.go:73 */
    fun normalizeQuantityFilter(
        fieldName: String,
        lower: List<Int>?,
        upper: List<Int>?,
        method: Int,
    ): QuantityFilter? {
        if (lower == null && upper == null) return null
        if (lower.isNullOrEmpty() || upper.isNullOrEmpty()) {
            throw IllegalArgumentException("$fieldName lower and upper must both be provided")
        }
        if (lower.size != upper.size) {
            throw IllegalArgumentException(
                "$fieldName lower and upper must have the same length, got lower=${lower.size} upper=${upper.size}",
            )
        }
        val channelCount = quantityFilterChannelCount(method)
        if (lower.size != channelCount) {
            throw IllegalArgumentException(
                "$fieldName lower and upper must each contain $channelCount values for method $method, got ${lower.size}",
            )
        }
        return QuantityFilter(lower.toList(), upper.toList(), method)
    }

    /** 上游 normalize.go:147：矩形中心 + 偏移；矩形不足 4 个数时回 (0,0)。 */
    fun centerPoint(rect: List<Int>, offset: List<Int>): Pair<Int, Int> {
        if (rect.size < 4) return 0 to 0
        return (rect[0] + rect[2] / 2 + offset[0]) to (rect[1] + rect[3] / 2 + offset[1])
    }

    /** 上游 normalize.go:156：空串默认 Value，大小写不敏感。 */
    fun normalizeTargetQuantityType(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return TARGET_QUANTITY_TYPE_VALUE
        return when (s.lowercase()) {
            "value" -> TARGET_QUANTITY_TYPE_VALUE
            "percentage" -> TARGET_QUANTITY_TYPE_PERCENTAGE
            else -> throw IllegalArgumentException(
                "invalid TargetQuantityType \"$raw\", expected \"$TARGET_QUANTITY_TYPE_VALUE\" or \"$TARGET_QUANTITY_TYPE_PERCENTAGE\"",
            )
        }
    }

    /**
     * 上游 normalize.go:184：用可用数量作百分比/反向计算的基准。
     *
     * ```
     * Value + !Reverse      -> targetQuantity 原样
     * Value + Reverse       -> availableQuantity - targetQuantity（可能 < 1）
     * Percentage + !Reverse -> round(availableQuantity * targetQuantity / 100)，再夹取
     * Percentage + Reverse  -> round(availableQuantity * (100-targetQuantity) / 100)，再夹取
     * ```
     *
     * 夹取顺序必须与上游一致：先抬到 1，再压到 availableQuantity。
     * 所以 availableQuantity=0 时结果是 0（不是 1）——这一步上游没做特殊处理，不要「顺手修」。
     */
    fun resolveTargetQuantity(
        targetQuantity: Int,
        targetQuantityType: String,
        reverseTarget: Boolean,
        availableQuantity: Int,
    ): Int = when (targetQuantityType) {
        TARGET_QUANTITY_TYPE_VALUE -> {
            if (!reverseTarget) targetQuantity else availableQuantity - targetQuantity
        }

        TARGET_QUANTITY_TYPE_PERCENTAGE -> {
            if (targetQuantity == 0) {
                throw IllegalArgumentException("percentage target must be greater than 0")
            }
            if (targetQuantity > 100) {
                throw IllegalArgumentException("percentage target must be at most 100, got $targetQuantity")
            }
            val factor = if (!reverseTarget) {
                targetQuantity / 100.0
            } else {
                (100 - targetQuantity) / 100.0
            }
            var resolved = Math.round(availableQuantity * factor).toInt()
            if (resolved < 1) resolved = 1
            if (resolved > availableQuantity) resolved = availableQuantity
            resolved
        }

        else -> throw IllegalArgumentException("invalid target quantity type \"$targetQuantityType\"")
    }

    /**
     * 上游 normalize.go:233：目标即滑条最小值 1 时的短路判定。
     *
     * Percentage 与 ReverseTarget 的有效目标依赖运行时 availableQuantity，进流程前判定不了。
     */
    fun isMinimumTargetShortCircuit(
        targetQuantity: Int,
        targetQuantityType: String,
        reverseTarget: Boolean,
    ): Boolean = targetQuantity == 1 &&
        targetQuantityType == TARGET_QUANTITY_TYPE_VALUE &&
        !reverseTarget

    /** 上游 normalize.go:242：短路时被改写 next 的节点。 */
    fun minimumTargetShortCircuitNext(resetBeforeFindStart: Boolean): String =
        if (resetBeforeFindStart) NODE_RESET else NODE_CLEAR_MAX_HIT

    /**
     * 上游 normalize.go:261。
     *
     * `present=false`（键缺失或显式 null）→ 默认「始终微调」。
     * JSON 数字在 Kotlin 侧会到成 Double/Number，所以这里按 Number 处理并要求整数值。
     */
    fun normalizeFineTuneQuantity(raw: Any?, present: Boolean): FineTuneQuantity {
        if (!present) return FineTuneQuantity.DEFAULT
        return when (raw) {
            is Boolean -> FineTuneQuantity(enabled = raw)

            is Number -> {
                val v = raw.toDouble()
                if (v != Math.floor(v) || v.isInfinite() || v.isNaN()) {
                    throw IllegalArgumentException("FineTuneQuantity must be a bool or an integer, got $raw")
                }
                newThresholdFineTuneQuantity(v)
            }

            else -> throw IllegalArgumentException(
                "FineTuneQuantity must be a bool or an integer >= 1, got ${raw?.let { it::class.simpleName }}",
            )
        }
    }

    /** 上游 normalize.go:283 */
    private fun newThresholdFineTuneQuantity(v: Double): FineTuneQuantity {
        if (v < 1) {
            throw IllegalArgumentException("FineTuneQuantity threshold must be >= 1, got $v")
        }
        // 超过阈值上界：语义上等价于「始终微调」，饱和为 enabled，不截断、不报错。
        if (v > MAX_FINE_TUNE_THRESHOLD) {
            return FineTuneQuantity(enabled = true)
        }
        return FineTuneQuantity(thresholdMode = true, threshold = v.toInt())
    }

    /** 上游 normalize.go:303：空值/空串归一为 none，大小写不敏感。 */
    fun normalizeFineTuneFallback(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return FINE_TUNE_FALLBACK_NONE
        return when (s.lowercase()) {
            FINE_TUNE_FALLBACK_NONE -> FINE_TUNE_FALLBACK_NONE
            FINE_TUNE_FALLBACK_MORE -> FINE_TUNE_FALLBACK_MORE
            FINE_TUNE_FALLBACK_LESS -> FINE_TUNE_FALLBACK_LESS
            else -> throw IllegalArgumentException(
                "invalid FineTuneFallback \"$raw\", expected \"$FINE_TUNE_FALLBACK_NONE\", \"$FINE_TUNE_FALLBACK_MORE\" or \"$FINE_TUNE_FALLBACK_LESS\"",
            )
        }
    }

    /**
     * 上游 normalize.go:327：只给 Direction 之类的调用走「只滑动」模式
     * （`AutoStockpileSwipeMax` / `AutoStockSwipeToMax` 就是这种，它们只需要拖到最大）。
     *
     * 注意 `Direction` 与 `SwipeButton` 不在判定里——上游也确实没把它们算进去。
     */
    fun isSwipeOnlyMode(presence: Presence): Boolean = !(
        presence.targetQuantity ||
            presence.sliderQuantity ||
            presence.availableQuantity ||
            presence.increaseButton ||
            presence.decreaseButton ||
            presence.outOfRangeOverrideEnable ||
            presence.targetReachableOverrideEnable ||
            presence.targetQuantityType ||
            presence.reverseTarget ||
            presence.centerPointOffset ||
            presence.clampTargetToSliderMax ||
            presence.fineTuneQuantity ||
            presence.fineTuneFallback
        )
}
