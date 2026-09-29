package com.aliothmoon.maafw.remote

/**
 * BetterSliding 的流水线覆盖构造层（对齐上游 bettersliding/overrides.go）。
 *
 * 上游把 `BetterSliding` 拆成「一次内部子流水线」：handler 只负责算出目标，
 * 再把改写好的 JSON 交给 `ctx.OverridePipeline`，真正滑动/点击的是
 * `resource/pipeline/BetterSliding/Main.json` 里那批既有节点。
 *
 * 所以这一层全是**纯数据构造**：不碰 MaaContext，产出可直接断言的 Map，
 * 由 MaaRunner 那侧序列化成 JSON 再覆盖。
 */
object BetterSlidingOverrides {

    private fun BetterSlidingSupport.node(name: String) = name

    /** 把 [node] 名与 [rect] 组成 MaaFramework 的多段 swipe 端点。 */
    private fun multiSegment(node: String, rect: List<Int>): List<Any?> = listOf(node, rect.toList())

    private fun actionParam(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        mapOf("param" to mapOf(*pairs))

    private fun action(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf("action" to actionParam(*pairs))

    private fun recognitionParam(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        mapOf("recognition" to mapOf("param" to mapOf(*pairs)))

    private fun colorFilterNodes(node: String, filter: BetterSlidingSupport.QuantityFilter): Map<String, Any?> =
        mapOf(
            node to recognitionParam(
                "method" to filter.method,
                "lower" to listOf(filter.lower.toList()),
                "upper" to listOf(filter.upper.toList()),
            ),
        )

    /**
     * 上游 overrides.go:16 `buildSwipeEnd`。
     *
     * right/up 往最大侧拖，left/down 往最小侧拖；方向必须是这四个之一。
     * 滑条本身的位置由 `begin` 段动态给定，这里只固定终点。
     */
    fun buildSwipeEnd(direction: String): List<Int> = when (direction) {
        "right", "up" -> BetterSlidingSupport.SWIPE_END_MAX
        "left", "down" -> BetterSlidingSupport.SWIPE_END_MIN
        else -> throw IllegalArgumentException("unsupported direction \"$direction\"")
    }

    /** 上游 overrides.go:28 `buildResetSwipeEnd`：复位往最小侧拖，与 buildSwipeEnd 相反。 */
    fun buildResetSwipeEnd(direction: String): List<Int> = when (direction) {
        "right", "up" -> BetterSlidingSupport.SWIPE_END_MIN
        "left", "down" -> BetterSlidingSupport.SWIPE_END_MAX
        else -> throw IllegalArgumentException("unsupported direction \"$direction\"")
    }

    /**
     * 上游 overrides.go:42 `buildReset2SwipeEnd`。
     *
     * 复位要拖到精确点击点的另一侧：点击靠近 Start 时往最大侧复位，反之往最小侧。
     */
    fun buildReset2SwipeEnd(
        direction: String,
        side: BetterSlidingDecision.Reset2Side,
    ): List<Int> =
        if (side == BetterSlidingDecision.Reset2Side.TOWARD_END) {
            buildSwipeEnd(direction)
        } else {
            buildResetSwipeEnd(direction)
        }

    /** 上游 overrides.go:224 `buildNodeEnableOverride`。 */
    fun buildNodeEnableOverride(nodeName: String, enabled: Boolean): Map<String, Any?> =
        mapOf(nodeName to mapOf("enabled" to enabled))

    /**
     * 从节点定义里读 `enabled`。缺省为 `true`（框架语义）；不是对象则返回 null 表示"未知"。
     *
     * 为什么需要它：真机实测（2026-09-29）`{"<节点>":{"enabled":false}}` 这条 override
     * 在框架 `MaaContextOverridePipeline` 里 **SIGSEGV**（tombstone 的 pc 就落在这个函数），
     * 而且**同一条 override 前三次成功、第四次崩**——与内容无关，是框架侧的状态/竞态问题。
     * 那两个节点（OutpostTradingReserveAlreadySatisfied / ReserveQuantityReached）
     * **默认就是 enabled:false**，所以这些调用大多是**空操作**。
     *
     * 于是：当前状态已等于目标值时**省掉这次调用**——行为完全不变，却绕开了崩溃。
     */
    fun readNodeEnabled(tree: Any?): Boolean? {
        val map = tree as? Map<*, *> ?: return null
        // 缺省即启用（框架语义）；字段存在但类型不对 → null＝未知，**不冒然跳过**覆盖
        if (!map.containsKey("enabled")) return true
        return map["enabled"] as? Boolean
    }

    /** 上游 overrides.go:232 `buildTemplateMatchButtonHelperOverride`。 */
    fun buildTemplateMatchButtonHelperOverride(template: String): Map<String, Any?> =
        recognitionParam("template" to listOf(template), "green_mask" to BetterSlidingSupport.DEFAULT_GREEN_MASK)

    /** 上游 overrides.go:243 `buildTemplateMatchButtonOverride`。 */
    fun buildTemplateMatchButtonOverride(helperNode: String, repeat: Int): Map<String, Any?> = mapOf(
        "recognition" to mapOf(
            "type" to "And",
            "param" to mapOf("all_of" to listOf(helperNode), "box_index" to 0),
        ),
        "action" to mapOf(
            "type" to "Click",
            "param" to mapOf("target" to true, "target_offset" to listOf(5, 5, -10, -10)),
        ),
        "repeat" to BetterSlidingSupport.clampClickRepeat(repeat),
    )

    /** 上游 overrides.go:213 `resolveButtonHelperNode`。 */
    fun resolveButtonHelperNode(nextNode: String): String = when (nextNode) {
        BetterSlidingSupport.NODE_INCREASE_QUANTITY -> BetterSlidingSupport.NODE_INCREASE_BUTTON
        BetterSlidingSupport.NODE_DECREASE_QUANTITY -> BetterSlidingSupport.NODE_DECREASE_BUTTON
        else -> ""
    }

    /**
     * 上游 overrides.go:172 `buildCheckQuantityBranchOverride`。
     *
     * 只有加/减数量两个出口需要改写；其它 next（Done / Reset2 等）返回空 map，
     * 调用方据此判断「不需要 OverridePipeline，只改 next」。
     *
     * 按钮是模板时多一个 helper 识别节点；是坐标时直接写 target + repeat。
     */
    fun buildCheckQuantityBranchOverride(
        nextNode: String,
        target: BetterSlidingParams.ButtonTarget?,
        repeat: Int,
    ): Map<String, Any?> {
        if (nextNode != BetterSlidingSupport.NODE_INCREASE_QUANTITY &&
            nextNode != BetterSlidingSupport.NODE_DECREASE_QUANTITY
        ) {
            return emptyMap()
        }

        val clamped = BetterSlidingSupport.clampClickRepeat(repeat)

        return when (target) {
            is BetterSlidingParams.ButtonTarget.Template -> mapOf(
                resolveButtonHelperNode(nextNode) to buildTemplateMatchButtonHelperOverride(target.path),
                nextNode to buildTemplateMatchButtonOverride(resolveButtonHelperNode(nextNode), clamped),
            )

            is BetterSlidingParams.ButtonTarget.Coordinates -> mapOf(
                nextNode to mapOf(
                    "action" to actionParam("target" to target.values.toList()),
                    "repeat" to clamped,
                ),
            )

            null -> mapOf(
                nextNode to mapOf(
                    "action" to actionParam("target" to emptyList<Int>()),
                    "repeat" to clamped,
                ),
            )
        }
    }

    /**
     * 上游 overrides.go:78 `buildMainInitializationOverride`。
     *
     * 内部子流水线的初始改写：
     *  - 拖到最大时用「FindStart 识别到的位置 → 固定终点」两段式；
     *  - 给了自定义 SwipeButton 才覆盖滑条模板（并强制绿色掩码）；
     *  - SliderQuantity.Box 为空就直接返回，**不**改数量识别节点；
     *  - AvailableQuantity 显式给了才启用，否则直接禁用该节点。
     */
    fun buildMainInitializationOverride(
        end: List<Int>,
        sliderQuantityBox: List<Int>,
        availableQuantityBox: List<Int>,
        availableQuantityExplicit: Boolean,
        sliderQuantityFilter: BetterSlidingSupport.QuantityFilter?,
        availableQuantityFilter: BetterSlidingSupport.QuantityFilter?,
        sliderQuantityOnlyRec: Boolean,
        availableQuantityOnlyRec: Boolean,
        swipeButton: String,
    ): Map<String, Any?> {
        val override = linkedMapOf<String, Any?>(
            BetterSlidingSupport.NODE_SWIPE_TO_MAX to action(
                "end" to multiSegment(BetterSlidingSupport.NODE_FIND_START, end),
            ),
        )

        if (swipeButton.isNotEmpty()) {
            override[BetterSlidingSupport.NODE_SWIPE_BUTTON] = buildTemplateMatchButtonHelperOverride(swipeButton)
        }

        if (sliderQuantityBox.isEmpty()) return override

        val sliderParam = linkedMapOf<String, Any?>(
            "roi" to sliderQuantityBox.toList(),
            "only_rec" to sliderQuantityOnlyRec,
        )
        if (sliderQuantityFilter != null) {
            sliderParam["color_filter"] = BetterSlidingSupport.NODE_SLIDER_QUANTITY_FILTER
            override.putAll(colorFilterNodes(BetterSlidingSupport.NODE_SLIDER_QUANTITY_FILTER, sliderQuantityFilter))
        }
        override[BetterSlidingSupport.NODE_GET_SLIDER_QUANTITY] = mapOf("recognition" to mapOf("param" to sliderParam))

        if (availableQuantityExplicit) {
            val availableParam = linkedMapOf<String, Any?>(
                "roi" to availableQuantityBox.toList(),
                "only_rec" to availableQuantityOnlyRec,
            )
            if (availableQuantityFilter != null) {
                availableParam["color_filter"] = BetterSlidingSupport.NODE_AVAILABLE_QUANTITY_FILTER
                override.putAll(
                    colorFilterNodes(BetterSlidingSupport.NODE_AVAILABLE_QUANTITY_FILTER, availableQuantityFilter),
                )
            }
            override[BetterSlidingSupport.NODE_GET_AVAILABLE_QUANTITY] = mapOf(
                "enabled" to true,
                "recognition" to mapOf("param" to availableParam),
            )
        } else {
            override[BetterSlidingSupport.NODE_GET_AVAILABLE_QUANTITY] = mapOf("enabled" to false)
        }

        return override
    }

    /** 上游 overrides.go:55 `buildResetSwipeOverride`。 */
    fun buildResetSwipeOverride(direction: String, enabled: Boolean): Map<String, Any?> = mapOf(
        BetterSlidingSupport.NODE_FIND_SWIPE_FOR_RESET to mapOf("enabled" to enabled),
        BetterSlidingSupport.NODE_RESET to action(
            "end" to multiSegment(BetterSlidingSupport.NODE_FIND_SWIPE_FOR_RESET, buildResetSwipeEnd(direction)),
        ),
    )

    /**
     * 上游 overrides.go:283 `parseInternalPipelineCustomActionParam`。
     *
     * 调用方把 `custom_action_param` 传进来时，可能已经被包成一层 JSON 字符串；
     * 能解开就解一层，解不开就原样保留。`parseJson` 返回 null 表示解析失败。
     */
    fun parseInternalPipelineCustomActionParam(parsed: Any?, parseJson: (String) -> Any?): Any? =
        if (parsed is String) parseJson(parsed) ?: parsed else parsed

    /**
     * 上游 overrides.go:263 `buildInternalPipelineOverride`。
     *
     * 给全部驱动节点塞同一个 `custom_action_param`，handler 才能在内部子流水线里
     * 认出自己是被谁调用的。
     */
    fun buildInternalPipelineOverride(paramValue: Any?): Map<String, Any?> {
        val override = linkedMapOf<String, Any?>()
        for (node in BetterSlidingDecision.ACTION_NODES) {
            override[node] = action("custom_action_param" to paramValue)
        }
        return override
    }
}
