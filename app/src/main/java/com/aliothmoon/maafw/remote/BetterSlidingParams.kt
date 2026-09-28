package com.aliothmoon.maafw.remote

/**
 * BetterSliding 的参数解析/归一化层（对齐上游 bettersliding/params.go）。
 *
 * 这一层刻意不碰 JSON：上游用 `json.RawMessage` 判「键在不在」，而 Kotlin 侧不同调用点
 * 会用不同 JSON 库，所以这里把「已解析出来的键集合」作为输入，
 * 由调用方按同一套规则算出 [presence]（见 [detectPresence]）。
 */
object BetterSlidingParams {

    /**
     * 上游 params.go:64 `hasNonNullRawKey` 的键集合：这些键必须「存在且不是 JSON null」才算提供了。
     */
    private val NON_NULL_KEYS = listOf(
        "TargetQuantity",
        "AvailableQuantity",
        "Direction",
        "IncreaseButton",
        "DecreaseButton",
        "SwipeButton",
        "OutOfRangeOverrideEnable",
        "TargetReachableOverrideEnable",
        "TargetQuantityType",
        "ReverseTarget",
        "CenterPointOffset",
        "ClampTargetToSliderMax",
        "FineTuneQuantity",
        "FineTuneFallback",
        "ResetBeforeFindStart",
    )

    /** 上游 params.go:42 单独用「键是否存在」判定，不看值是不是 null。 */
    const val SLIDER_QUANTITY_KEY = "SliderQuantity"

    /** 上游 params.go:421-503 `mergeAttachParams` 会从调用节点 attach 里读的字段。 */
    val ATTACH_MERGED_KEYS = listOf(
        "TargetQuantity",
        "TargetQuantityType",
        "ReverseTarget",
        "FineTuneQuantity",
        "FineTuneFallback",
        "ResetBeforeFindStart",
    )

    /** 上游 quantityFilterParam 的未归一化形态。 */
    data class RawFilter(val lower: List<Int>? = null, val upper: List<Int>? = null, val method: Int = 0)

    /** 上游 quantityParam。 */
    data class RawQuantity(
        val box: List<Int> = emptyList(),
        val filter: RawFilter? = null,
        val onlyRec: Boolean? = null,
    )

    /** 按钮既可以是模板路径，也可以是坐标；上游用 `any` 承载。 */
    sealed interface ButtonTarget {
        data class Template(val path: String) : ButtonTarget
        data class Coordinates(val values: List<Int>) : ButtonTarget
    }

    /** 上游 types.go:10 betterSlidingParam 的字符形态（由调用方从 JSON 填好）。 */
    data class RawParam(
        val targetQuantity: Int = 0,
        val sliderQuantity: RawQuantity = RawQuantity(),
        val availableQuantity: RawQuantity = RawQuantity(),
        val direction: String = "",
        val increaseButton: ButtonTarget? = null,
        val decreaseButton: ButtonTarget? = null,
        val swipeButton: String = "",
        val outOfRangeOverrideEnable: String = "",
        val targetReachableOverrideEnable: String = "",
        val targetQuantityType: String = "",
        val reverseTarget: Boolean = false,
        val centerPointOffset: List<Int>? = null,
        val clampTargetToSliderMax: Boolean = false,
        val fineTuneQuantity: Any? = null,
        val fineTuneFallback: String = "",
        val resetBeforeFindStart: Boolean = false,
    )

    /** 上游 params.go:11 parsedBetterSlidingParams。 */
    data class ParsedParams(
        val targetQuantity: Int,
        val sliderQuantityBox: List<Int>,
        val availableQuantityBox: List<Int>,
        val availableQuantityExplicit: Boolean,
        val sliderQuantityFilter: BetterSlidingSupport.QuantityFilter?,
        val availableQuantityFilter: BetterSlidingSupport.QuantityFilter?,
        val sliderQuantityOnlyRec: Boolean,
        val availableQuantityOnlyRec: Boolean,
        val direction: String,
        val increaseButton: ButtonTarget?,
        val decreaseButton: ButtonTarget?,
        val centerPointOffset: List<Int>,
        val clampTargetToSliderMax: Boolean,
        val swipeButton: String,
        val outOfRangeOverrideEnable: String,
        val targetReachableOverrideEnable: String,
        val targetQuantityType: String,
        val reverseTarget: Boolean,
        val swipeOnlyMode: Boolean,
        val fineTuneQuantity: BetterSlidingSupport.FineTuneQuantity,
        val fineTuneFallback: String,
        val resetBeforeFindStart: Boolean,
    )

    /**
     * 上游 params.go:36 `detectBetterSlidingParamPresence`。
     *
     * @param allKeys     JSON 里出现过的全部键
     * @param nonNullKeys 其中值不是 JSON null 的键
     *
     * 注意 `SliderQuantity` 只认「键存在」：显式给 `null` 也算提供了。
     * 这个怪癖会影响 [BetterSlidingSupport.isSwipeOnlyMode]，进而决定走「只滑动」还是「定量」，
     * 所以不要「顺手」统一成 non-null 判定。
     */
    fun detectPresence(allKeys: Set<String>, nonNullKeys: Set<String>): BetterSlidingSupport.Presence {
        fun has(key: String) = key in nonNullKeys
        return BetterSlidingSupport.Presence(
            targetQuantity = has("TargetQuantity"),
            sliderQuantity = SLIDER_QUANTITY_KEY in allKeys,
            availableQuantity = has("AvailableQuantity"),
            direction = has("Direction"),
            increaseButton = has("IncreaseButton"),
            decreaseButton = has("DecreaseButton"),
            swipeButton = has("SwipeButton"),
            outOfRangeOverrideEnable = has("OutOfRangeOverrideEnable"),
            targetReachableOverrideEnable = has("TargetReachableOverrideEnable"),
            targetQuantityType = has("TargetQuantityType"),
            reverseTarget = has("ReverseTarget"),
            centerPointOffset = has("CenterPointOffset"),
            clampTargetToSliderMax = has("ClampTargetToSliderMax"),
            fineTuneQuantity = has("FineTuneQuantity"),
            fineTuneFallback = has("FineTuneFallback"),
            resetBeforeFindStart = has("ResetBeforeFindStart"),
        )
    }

    /**
     * 从已解析的通用 JSON 树（Map/List/String/Number/Boolean）里抽出 [RawParam] 与 presence。
     *
     * 「presence」的两条规则见 [detectPresence]：`SliderQuantity` 只看键在不在，
     * 其余键要求「在且不是 null」。所以这里要把**全部键**和**非 null 键**分开交给它。
     */
    fun fromTree(tree: Any?): Pair<RawParam, BetterSlidingSupport.Presence>? {
        val map = tree as? Map<*, *> ?: return null
        val allKeys = map.keys.mapNotNull { it as? String }.toSet()
        val nonNullKeys = map.entries
            .filter { it.value != null }
            .mapNotNull { it.key as? String }
            .toSet()

        val presence = detectPresence(allKeys, nonNullKeys)
        val raw = RawParam(
            targetQuantity = intOf(map["TargetQuantity"]) ?: 0,
            sliderQuantity = quantityOf(map["SliderQuantity"]),
            availableQuantity = quantityOf(map["AvailableQuantity"]),
            direction = stringOf(map["Direction"]).orEmpty(),
            increaseButton = buttonOf(map["IncreaseButton"]),
            decreaseButton = buttonOf(map["DecreaseButton"]),
            swipeButton = stringOf(map["SwipeButton"]).orEmpty(),
            outOfRangeOverrideEnable = stringOf(map["OutOfRangeOverrideEnable"]).orEmpty(),
            targetReachableOverrideEnable = stringOf(map["TargetReachableOverrideEnable"]).orEmpty(),
            targetQuantityType = stringOf(map["TargetQuantityType"]).orEmpty(),
            reverseTarget = boolOf(map["ReverseTarget"]) ?: false,
            centerPointOffset = intListOf(map["CenterPointOffset"]),
            clampTargetToSliderMax = boolOf(map["ClampTargetToSliderMax"]) ?: false,
            fineTuneQuantity = map["FineTuneQuantity"],
            fineTuneFallback = stringOf(map["FineTuneFallback"]).orEmpty(),
            resetBeforeFindStart = boolOf(map["ResetBeforeFindStart"]) ?: false,
        )
        return raw to presence
    }

    private fun stringOf(value: Any?): String? = value as? String

    private fun intOf(value: Any?): Int? = (value as? Number)?.toInt()

    private fun boolOf(value: Any?): Boolean? = value as? Boolean

    private fun intListOf(value: Any?): List<Int>? {
        val list = value as? List<*> ?: return null
        return list.mapNotNull { (it as? Number)?.toInt() }
    }

    private fun quantityOf(value: Any?): RawQuantity {
        val map = value as? Map<*, *> ?: return RawQuantity()
        val filterMap = map["Filter"] as? Map<*, *>
        val filter = filterMap?.let {
            RawFilter(
                lower = intListOf(it["lower"]),
                upper = intListOf(it["upper"]),
                method = intOf(it["method"]) ?: 0,
            )
        }
        return RawQuantity(
            box = intListOf(map["Box"]).orEmpty(),
            filter = filter,
            onlyRec = boolOf(map["OnlyRec"]),
        )
    }

    private fun buttonOf(value: Any?): ButtonTarget? = when (value) {
        null -> null
        is String -> ButtonTarget.Template(value)
        is List<*> -> ButtonTarget.Coordinates(value.mapNotNull { (it as? Number)?.toInt() })
        else -> null
    }

    /** 上游 params.go:22 `normalizeButton`；`ButtonTarget.Coordinates` 版本。 */
    fun normalizeButtonTarget(target: ButtonTarget): ButtonTarget = when (target) {
        is ButtonTarget.Template -> {
            val trimmed = target.path.trim()
            require(trimmed.isNotEmpty()) { "button template must not be empty" }
            ButtonTarget.Template(trimmed)
        }

        is ButtonTarget.Coordinates ->
            ButtonTarget.Coordinates(BetterSlidingSupport.normalizeButton(target.values))
    }

    /**
     * 上游 params.go:121 `normalizeActionParams`。
     *
     * 校验顺序与上游一致：任何一步失败都 abort，抛 IllegalArgumentException，
     * 由调用方记日志并把节点判失败。
     */
    fun normalizeActionParams(raw: RawParam, presence: BetterSlidingSupport.Presence): ParsedParams {
        val swipeButton = raw.swipeButton.trim()
        val outOfRangeOverrideEnable = raw.outOfRangeOverrideEnable.trim()
        val targetReachableOverrideEnable = raw.targetReachableOverrideEnable.trim()

        validateOutcomeOverrideNodes(outOfRangeOverrideEnable, targetReachableOverrideEnable)

        val targetQuantityType = BetterSlidingSupport.normalizeTargetQuantityType(raw.targetQuantityType)
        val fineTuneQuantity =
            BetterSlidingSupport.normalizeFineTuneQuantity(raw.fineTuneQuantity, presence.fineTuneQuantity)
        val fineTuneFallback = BetterSlidingSupport.normalizeFineTuneFallback(raw.fineTuneFallback)

        if (BetterSlidingSupport.isSwipeOnlyMode(presence)) {
            val direction = raw.direction.trim().lowercase()
            require(direction in BetterSlidingSupport.SWIPE_DIRECTIONS) {
                "invalid direction for swipe-only mode: \"${raw.direction}\""
            }
            return ParsedParams(
                targetQuantity = 0,
                sliderQuantityBox = emptyList(),
                availableQuantityBox = emptyList(),
                availableQuantityExplicit = false,
                sliderQuantityFilter = null,
                availableQuantityFilter = null,
                sliderQuantityOnlyRec = false,
                availableQuantityOnlyRec = false,
                direction = direction,
                increaseButton = null,
                decreaseButton = null,
                centerPointOffset = BetterSlidingSupport.DEFAULT_CENTER_POINT_OFFSET,
                clampTargetToSliderMax = raw.clampTargetToSliderMax,
                swipeButton = swipeButton,
                outOfRangeOverrideEnable = outOfRangeOverrideEnable,
                targetReachableOverrideEnable = targetReachableOverrideEnable,
                targetQuantityType = targetQuantityType,
                reverseTarget = raw.reverseTarget,
                swipeOnlyMode = true,
                fineTuneQuantity = BetterSlidingSupport.FineTuneQuantity.DEFAULT,
                fineTuneFallback = BetterSlidingSupport.FINE_TUNE_FALLBACK_NONE,
                resetBeforeFindStart = raw.resetBeforeFindStart,
            )
        }

        require(raw.targetQuantity > 0) {
            "invalid target quantity, must be greater than 0, got ${raw.targetQuantity}"
        }

        val increaseButton = raw.increaseButton?.let { normalizeButtonTarget(it) }
        val decreaseButton = raw.decreaseButton?.let { normalizeButtonTarget(it) }
        val centerPointOffset = BetterSlidingSupport.normalizeCenterPointOffset(raw.centerPointOffset)
        val sliderQuantityFilter = BetterSlidingSupport.normalizeQuantityFilter(
            "SliderQuantity.Filter",
            raw.sliderQuantity.filter?.lower,
            raw.sliderQuantity.filter?.upper,
            raw.sliderQuantity.filter?.method ?: 0,
        )

        var availableQuantityFilter: BetterSlidingSupport.QuantityFilter? = null
        var availableQuantityBox: List<Int> = emptyList()
        var availableQuantityOnlyRec = false
        if (presence.availableQuantity) {
            availableQuantityFilter = BetterSlidingSupport.normalizeQuantityFilter(
                "AvailableQuantity.Filter",
                raw.availableQuantity.filter?.lower,
                raw.availableQuantity.filter?.upper,
                raw.availableQuantity.filter?.method ?: 0,
            )
            availableQuantityBox = raw.availableQuantity.box.toList()
            availableQuantityOnlyRec = raw.availableQuantity.onlyRec ?: false
        }

        return ParsedParams(
            targetQuantity = raw.targetQuantity,
            sliderQuantityBox = raw.sliderQuantity.box.toList(),
            availableQuantityBox = availableQuantityBox,
            availableQuantityExplicit = presence.availableQuantity,
            sliderQuantityFilter = sliderQuantityFilter,
            availableQuantityFilter = availableQuantityFilter,
            sliderQuantityOnlyRec = raw.sliderQuantity.onlyRec ?: false,
            availableQuantityOnlyRec = availableQuantityOnlyRec,
            direction = raw.direction.trim().lowercase(),
            increaseButton = increaseButton,
            decreaseButton = decreaseButton,
            centerPointOffset = centerPointOffset,
            clampTargetToSliderMax = raw.clampTargetToSliderMax,
            swipeButton = swipeButton,
            outOfRangeOverrideEnable = outOfRangeOverrideEnable,
            targetReachableOverrideEnable = targetReachableOverrideEnable,
            targetQuantityType = targetQuantityType,
            reverseTarget = raw.reverseTarget,
            swipeOnlyMode = false,
            fineTuneQuantity = fineTuneQuantity,
            fineTuneFallback = fineTuneFallback,
            resetBeforeFindStart = raw.resetBeforeFindStart,
        )
    }

    private val SWIPE_DIRECTIONS = BetterSlidingSupport.SWIPE_DIRECTIONS

    /**
     * 上游 params.go:69 `validateOutcomeOverrideNodes`：两个「结果覆盖」节点不能指向同一个，
     * 否则一处启用会连带另一处，语义就乱了。空串表示没提供，跳过。
     */
    private fun validateOutcomeOverrideNodes(vararg nodes: String) {
        val seen = mutableSetOf<String>()
        for (node in nodes) {
            if (node.isEmpty()) continue
            require(seen.add(node)) { "BetterSliding outcome overrides must use different nodes: $node" }
        }
    }
}
