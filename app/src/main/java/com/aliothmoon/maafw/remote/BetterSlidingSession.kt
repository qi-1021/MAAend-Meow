package com.aliothmoon.maafw.remote

/**
 * BetterSliding 内部子流水线的宿主。
 *
 * 把 MaaFramework 的调用面收窄成这几个方法，是为了让 [BetterSlidingSession] 的**编排逻辑**
 * 能在本机跑测试——编排错（漏一次 override、路由到错的节点）在真机上只表现为数量不对，
 * 极难从日志定位。
 */
interface BetterSlidingHost {
    /** 应用流水线覆盖。返回是否成功。 */
    fun overridePipeline(overrideJson: String): Boolean

    /** 以给定覆盖运行内部子流水线（上游 `ctx.RunTask(nodeBetterSlidingMain, override)`）。 */
    fun runSubTask(nodeName: String, overrideJson: String): Boolean

    /** 读调用节点的定义 JSON（取 attach）。 */
    fun callerNodeJson(nodeName: String): String?

    /** 按 recoId 取识别结果 detail JSON。 */
    fun recognitionDetailJson(recoId: Long): String?

    /** 解析 JSON 文本成通用树（Map/List/String/Number/Boolean/null）。 */
    fun parseJson(text: String): Any?

    /**
     * 手工执行一次 `OutpostTradingReserveSession` 的 `satisfy`（当前选中物品标记为已满足）。
     *
     * 结果节点 `OutpostTradingReserveAlreadySatisfied` / `OutpostTradingReserveQuantityReached`
     * 的 action 就是这个 satisfy；框架在点亮它们时用的 `enabled` 覆盖会偶发 SIGSEGV，
     * 所以 out-of-range 分支改为直接调这里，等价于「跑一次结果节点」。
     *
     * 返回是否成功；失败时必须已打日志（由 host 侧复用与真实节点同一份 satisfy 逻辑保证），
     * 调用方不再静默跳过。
     */
    fun satisfyReserveOutcome(): Boolean

    fun info(message: String)

    fun warn(message: String)
}

/**
 * BetterSliding 的状态机编排层（对齐上游 bettersliding/handlers.go 的 handler 骨架）。
 *
 * 判定全部委托给 [BetterSlidingDecision] / [BetterSlidingSupport] / [BetterSlidingOverrides]，
 * 这里只负责「什么时候调哪一步、把结果写到哪里、路由到哪个节点」。
 *
 * 一份实例对应一次内部子流水线（上游是单例 action，`handleMain` 开头的 `resetState`
 * 承担跨轮清场，所以语义一致）。
 */
class BetterSlidingSession(private val host: BetterSlidingHost) {

    private val state = BetterSlidingDecision.ActionState()
    private var params: BetterSlidingParams.ParsedParams? = null

    /**
     * 上游 handlers.go:12 `Run`。
     *
     * 被**内部驱动节点**调用时走分发；被**外部调用方**调用时启动内部子流水线。
     *
     * [actionBox] 是 MaaCustomActionCallback 第 7 个参数解出的「本节点命中框」
     * （见 [BetterSlidingOcr.boxOfRect]）。起点/终点识别都必须是它——
     * `detail_json` 里没有顶层 box，真机上永远读不到（详见 [BetterSlidingOcr.readHitBox]）。
     * 只有 [MaaRunner] 那侧能拿到指针，所以由它解好四元组后传进来，本层不碰 JNA。
     */
    fun run(
        nodeName: String,
        customActionParam: String?,
        recoId: Long,
        actionBox: List<Int>? = null,
    ): Boolean {
        return try {
            if (!BetterSlidingDecision.isActionNode(nodeName)) {
                runInternalPipeline(nodeName, customActionParam)
            } else {
                val tree = customActionParam?.let { host.parseJson(it) }
                if (!loadActionParams(tree)) {
                    false
                } else {
                    dispatch(nodeName, recoId, actionBox)
                }
            }
        } catch (t: Throwable) {
            host.warn("BetterSliding [$nodeName] 执行异常：${t.message}")
            false
        }
    }

    /** 上游 handlers.go:33 `dispatchActionNode`。 */
    private fun dispatch(nodeName: String, recoId: Long, actionBox: List<Int>?): Boolean = when (nodeName) {
        BetterSlidingSupport.NODE_MAIN -> handleMain(nodeName)
        BetterSlidingSupport.NODE_FIND_START -> handleFindStart(recoId, actionBox)
        BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY -> handleGetSliderMaxQuantity(nodeName, recoId)
        BetterSlidingSupport.NODE_GET_AVAILABLE_QUANTITY -> handleGetAvailableQuantity(recoId)
        BetterSlidingSupport.NODE_FIND_END -> handleFindEnd(nodeName, recoId, actionBox)
        BetterSlidingSupport.NODE_CHECK_QUANTITY -> handleCheckQuantity(nodeName, recoId)
        BetterSlidingSupport.NODE_DONE -> true
        else -> {
            host.warn("BetterSliding 未知驱动节点：$nodeName")
            false
        }
    }

    // ────────────────────────── 参数与子流水线 ──────────────────────────

    /** 上游 params.go:101 `loadActionParams`。 */
    private fun loadActionParams(tree: Any?): Boolean {
        val extracted = BetterSlidingParams.fromTree(tree)
        if (extracted == null) {
            host.warn("BetterSliding 参数解析失败（不是对象）")
            return false
        }
        val (raw, presence) = extracted
        val parsed = try {
            BetterSlidingParams.normalizeActionParams(raw, presence)
        } catch (t: IllegalArgumentException) {
            host.warn("BetterSliding 参数不合法：${t.message}")
            return false
        }
        // 上游 applyActionParams 的前两行：原始目标always更新，实际目标受运行时守卫保护
        state.applyParsedTarget(parsed.targetQuantity)
        params = parsed
        return true
    }

    /** 上游 handlers.go:896 `runInternalPipeline`。 */
    private fun runInternalPipeline(nodeName: String, customActionParam: String?): Boolean {
        val merged = mergeAttach(nodeName, customActionParam)
        if (!loadActionParams(merged)) return false

        // 上游 parseInternalPipelineCustomActionParam：外层可能被包成 JSON 字符串，能解就解一层
        val paramValue = BetterSlidingOverrides.parseInternalPipelineCustomActionParam(merged) { host.parseJson(it) }
        val overrideJson = JsonTree.toJson(BetterSlidingOverrides.buildInternalPipelineOverride(paramValue))
        if (!host.overridePipeline(overrideJson)) {
            host.warn("BetterSliding 内部子流水线覆盖失败（caller=$nodeName）")
            return false
        }
        if (!host.runSubTask(BetterSlidingSupport.NODE_MAIN, overrideJson)) {
            host.warn("BetterSliding 内部子流水线执行失败（caller=$nodeName）")
            return false
        }
        return applyOutcomeOverrides(nodeName)
    }

    /**
     * 上游 params.go:369 `mergeAttachParams`：把调用节点 attach 里认得的那几个字段
     * 并进 custom_action_param。
     *
     * 上游对每个字段都会先按类型解析、失败就 warn 跳过；这里直接取用，
     * 因为 attach 内容都来自本仓库自己的流水线定义。
     */
    private fun mergeAttach(nodeName: String, customActionParam: String?): Any? {
        val paramTree = customActionParam?.let { host.parseJson(it) }
        val nodeJson = host.callerNodeJson(nodeName) ?: return paramTree
        val nodeTree = host.parseJson(nodeJson) as? Map<*, *> ?: return paramTree
        val attach = nodeTree["attach"] as? Map<*, *> ?: return paramTree
        val paramMap = (paramTree as? Map<*, *>)?.toMutableMap() ?: return paramTree

        for (key in BetterSlidingParams.ATTACH_MERGED_KEYS) {
            val value = attach[key] ?: continue
            paramMap[key] = value
        }
        return paramMap
    }

    /**
     * 上游 handlers.go:977 `applyOutcomeOverrides`。
     *
     * **本实现不再下发任何 `enabled` 覆盖**：真机上框架在 `MaaContextOverridePipeline`
     * 上点亮结果节点时会间歇性 SIGSEGV（tombstone pc = `MaaContextOverridePipeline+592`；
     * 同一条调用前三次成功、第四次崩，与内容无关；连 `MaaContextGetNodeData` 纯查询在
     * 同一节点名上都会崩）。于是把结果节点要做的事搬到编排层手工完成：
     *
     *  - `outOfRange`：结果节点 `OutpostTradingReserveAlreadySatisfied` 的 action 是
     *    `satisfy`、next 是 `OutpostTradingSellLoop`。这里改为直接调 [BetterSlidingHost.satisfyReserveOutcome]
     *    并把调用方 next 覆盖成 `OutpostTradingSellLoop`——与「点亮结果节点后走它的 next」
     *    完全等价（fix-1 已确认），且不再引用结果节点。
     *  - `targetReachable`：结果节点 `OutpostTradingReserveQuantityReached` 静态挂在
     *    `OutpostTradingSellCheckThenLoop.next`，在交易之后做记账。这里**不动调用方 next**
     *    （改了会跳过卖出），只是不再点亮它，并打日志说明取舍。
     */
    private fun applyOutcomeOverrides(caller: String): Boolean {
        val p = params ?: return true
        return when (
            BetterSlidingDecision.resolveOutcomeAction(
                // 只有调用方**显式配置**了对应结果节点才处理：参数为空表示上游本来就不会点亮，
                // 此时保持"什么都不做"，绝不能误 satisfy 掉一个未声明结果处理的物品。
                outOfRange = state.outOfRange && p.outOfRangeOverrideEnable.isNotEmpty(),
                targetReachable = state.targetReachable && p.targetReachableOverrideEnable.isNotEmpty(),
            )
        ) {
            BetterSlidingDecision.OutcomeAction.NONE -> true

            BetterSlidingDecision.OutcomeAction.SATISFY_AND_ROUTE -> {
                if (!host.satisfyReserveOutcome()) {
                    host.warn("BetterSliding 目标越界但 satisfy 失败，无法等价点亮结果节点（caller=$caller）")
                    return false
                }
                if (!overrideCheckQuantityBranch(caller, OutpostReserveSupport.NODE_SELL_LOOP, null, 0)) {
                    return false
                }
                host.info(
                    "BetterSliding 目标越界：已 satisfy 并把调用方路由到 " +
                        "${OutpostReserveSupport.NODE_SELL_LOOP}（caller=$caller，未下发 enabled 覆盖）",
                )
                true
            }

            BetterSlidingDecision.OutcomeAction.SKIP_TARGET_REACHABLE -> {
                // 能到这里说明 p.targetReachableOverrideEnable 非空（已在上面 gate 过）
                host.warn(
                    "为确保据点交易不因框架崩溃中断，已跳过 ${p.targetReachableOverrideEnable} 的点亮" +
                        "（该节点只在交易后做记账）；该物品不会被记入 satisfiedItems，" +
                        "但选品侧已有 attempted 集合兜底。待上游修复后恢复。",
                )
                true
            }
        }
    }

    // ────────────────────────── 各驱动节点 ──────────────────────────

    /** 上游 handlers.go:56 `handleMain`。 */
    private fun handleMain(nodeName: String): Boolean {
        state.reset()
        val p = params ?: return false

        state.minimumTargetShortCircuit = !p.swipeOnlyMode && BetterSlidingSupport.isMinimumTargetShortCircuit(
            state.originalTargetQuantity,
            p.targetQuantityType,
            p.reverseTarget,
        )
        if (state.minimumTargetShortCircuit) state.targetReachable = true

        BetterSlidingDecision.validateMainInputs(
            swipeOnlyMode = p.swipeOnlyMode,
            sliderQuantityBox = p.sliderQuantityBox,
            availableQuantityBox = p.availableQuantityBox,
            availableQuantityExplicit = p.availableQuantityExplicit,
            direction = p.direction,
        )

        val end = BetterSlidingOverrides.buildSwipeEnd(p.direction)
        val override = BetterSlidingOverrides.buildMainInitializationOverride(
            end = end,
            sliderQuantityBox = p.sliderQuantityBox,
            availableQuantityBox = p.availableQuantityBox,
            availableQuantityExplicit = p.availableQuantityExplicit,
            sliderQuantityFilter = p.sliderQuantityFilter,
            availableQuantityFilter = p.availableQuantityFilter,
            sliderQuantityOnlyRec = p.sliderQuantityOnlyRec,
            availableQuantityOnlyRec = p.availableQuantityOnlyRec,
            swipeButton = p.swipeButton,
        ).toMutableMap()
        override.putAll(BetterSlidingOverrides.buildResetSwipeOverride(p.direction, p.resetBeforeFindStart))

        if (!applyPipeline(override)) {
            host.warn("BetterSliding 主流程初始化覆盖失败（node=$nodeName）")
            return false
        }

        // 只滑动模式：清空 SwipeToMax 的 next，让它一次性跑完
        if (p.swipeOnlyMode) {
            if (!overrideNext(BetterSlidingSupport.NODE_SWIPE_TO_MAX, emptyList())) return false
        }

        // 目标即最小值：直接路由到 Done，跳过滑条端点识别与精确点击
        if (state.minimumTargetShortCircuit) {
            val from = BetterSlidingSupport.minimumTargetShortCircuitNext(p.resetBeforeFindStart)
            if (!overrideNext(from, listOf(BetterSlidingSupport.NODE_DONE))) return false
            host.info(
                "BetterSliding 短路：目标即滑条最小值，跳过滑条识别（from=$from, target=${state.targetQuantity}）",
            )
        }
        return true
    }

    /**
     * 上游 handlers.go:191 `handleFindStart`。
     *
     * 起点框来自回调参数 [actionBox]（`BetterSlidingFindStart` 是 `And` 节点，
     * 其命中框即子节点 `BetterSlidingSwipeButton` 的模板框）。
     *
     * `readHitBox(recoId)` 那条 `detail_json` 路径是兜底：不采用它作主路，不是因为读不到
     * （数组根已于 2026-09 支持，见 [BetterSlidingOcr.fromRecognizedDetail]），而是回调
     * 参数 [actionBox] 就是框架给的「本节点命中框」，更直接、少一层解析假设。
     * 别把它改回主路。
     */
    private fun handleFindStart(recoId: Long, actionBox: List<Int>?): Boolean {
        val box = actionBox ?: readHitBox(recoId) ?: run {
            host.warn("BetterSliding 读不到滑条起点框")
            return false
        }
        state.startBox = box
        return true
    }

    /** 上游 handlers.go:208 `handleGetSliderMaxQuantity`。 */
    private fun handleGetSliderMaxQuantity(nodeName: String, recoId: Long): Boolean {
        val p = params ?: return false

        val maxQuantity = readQuantityValue(recoId) ?: run {
            host.warn("BetterSliding 读不到滑条上限")
            return false
        }
        state.sliderMaxQuantity = maxQuantity

        // 可用数量还没解析过时，用滑条上限当基准解析目标（Percentage / Reverse 会重算）
        if (!state.availableQuantityResolved) {
            val resolved = try {
                BetterSlidingSupport.resolveTargetQuantity(
                    state.originalTargetQuantity,
                    p.targetQuantityType,
                    p.reverseTarget,
                    state.sliderMaxQuantity,
                )
            } catch (t: IllegalArgumentException) {
                host.warn("BetterSliding 目标解析失败：${t.message}")
                return false
            }
            state.targetQuantity = resolved
            state.runtimeTargetResolved = true
        }

        val (resolvedTarget, outcome) = BetterSlidingDecision.resolveSliderQuantityOutcome(
            state.targetQuantity,
            state.sliderMaxQuantity,
            p.clampTargetToSliderMax,
        )
        state.targetQuantity = resolvedTarget
        state.outOfRange = outcome == BetterSlidingDecision.SliderQuantityOutcome.OUT_OF_RANGE
        state.targetReachable = outcome == BetterSlidingDecision.SliderQuantityOutcome.TARGET_REACHABLE

        if (state.outOfRange) {
            if (p.outOfRangeOverrideEnable.isEmpty()) {
                host.warn("BetterSliding 目标越界但没有配置越界处理（OutOfRangeOverrideEnable，target=${state.targetQuantity}, max=${state.sliderMaxQuantity}）")
                return false
            }
            if (!overrideCheckQuantityBranch(nodeName, BetterSlidingSupport.NODE_DONE, null, 0)) return false
            host.warn("BetterSliding 目标越界，跳过调整并交给调用方处理（result=${p.outOfRangeOverrideEnable}）")
            return true
        }

        val next = try {
            BetterSlidingDecision.resolveSliderMaxQuantityNext(state.sliderMaxQuantity, state.targetQuantity)
        } catch (t: IllegalArgumentException) {
            host.warn("BetterSliding ${t.message}")
            return false
        }
        if (next != null) {
            if (!overrideCheckQuantityBranch(nodeName, next, null, 0)) return false
            host.info("BetterSliding 滑条上限正好等于目标，直接收尾（max=${state.sliderMaxQuantity}）")
        }
        return true
    }

    /** 上游 handlers.go:362 `handleGetAvailableQuantity`。 */
    private fun handleGetAvailableQuantity(recoId: Long): Boolean {
        val p = params ?: return false

        val available = readQuantityValue(recoId) ?: run {
            host.warn("BetterSliding 读不到可用数量")
            return false
        }
        state.availableQuantity = available

        val resolved = try {
            BetterSlidingSupport.resolveTargetQuantity(
                state.originalTargetQuantity,
                p.targetQuantityType,
                p.reverseTarget,
                state.availableQuantity,
            )
        } catch (t: IllegalArgumentException) {
            host.warn("BetterSliding 按可用数量解析目标失败：${t.message}")
            return false
        }
        state.targetQuantity = resolved
        state.runtimeTargetResolved = true
        state.availableQuantityResolved = true
        return true
    }

    /**
     * 上游 handlers.go:416 `handleFindEnd`。
     *
     * 终点框同样优先用回调参数 [actionBox]，`detail_json` 只作兜底（原因见
     * [handleFindStart]）。
     */
    private fun handleFindEnd(nodeName: String, recoId: Long, actionBox: List<Int>?): Boolean {
        val p = params ?: return false

        if (state.sliderMaxQuantity < 1) {
            host.warn("BetterSliding 滑条上限非法，无法计算精确点击（max=${state.sliderMaxQuantity}）")
            return false
        }
        val endBox = actionBox ?: readHitBox(recoId) ?: run {
            host.warn("BetterSliding 读不到滑条终点框")
            return false
        }
        state.endBox = endBox

        val click = try {
            BetterSlidingDecision.preciseClick(
                state.startBox,
                state.endBox,
                p.centerPointOffset,
                state.targetQuantity,
                state.sliderMaxQuantity,
            )
        } catch (t: IllegalArgumentException) {
            host.warn("BetterSliding 精确点击计算失败：${t.message}")
            return false
        }

        // 重算基准坐标即重置偏移索引
        state.preciseClickBase = click
        state.preciseClickNudges = 0

        if (!applyPipeline(preciseClickOverride(click.first, click.second))) return false
        if (!overrideNext(BetterSlidingSupport.NODE_PRECISE_CLICK, listOf(BetterSlidingSupport.NODE_JUMP_BACK_NODE))) {
            return false
        }

        if (BetterSlidingDecision.shouldResetBeforePreciseClick(state.targetQuantity, state.sliderMaxQuantity)) {
            if (!applyPipeline(
                    mapOf(BetterSlidingSupport.NODE_FIND_SWIPE_FOR_RESET to mapOf("enabled" to true)),
                )
            ) {
                return false
            }
            if (!overrideNext(BetterSlidingSupport.NODE_RESET, listOf(BetterSlidingSupport.NODE_PRECISE_CLICK))) {
                return false
            }
            if (!overrideNext(nodeName, listOf(BetterSlidingSupport.NODE_FIND_SWIPE_FOR_RESET))) return false
            host.info("BetterSliding 目标超过滑条上限 80%，精确点击前先复位（target=${state.targetQuantity}）")
        }
        return true
    }

    /** 上游 handlers.go:533 `handleCheckQuantity`。 */
    private fun handleCheckQuantity(nodeName: String, recoId: Long): Boolean {
        val p = params ?: return false

        val current = readQuantityValue(recoId) ?: run {
            host.warn("BetterSliding 读不到当前数量")
            return false
        }

        if (!BetterSlidingDecision.shouldFineTuneQuantity(p.fineTuneQuantity, current, state.targetQuantity)) {
            return handleNoFineTune(nodeName, current)
        }

        val plan = BetterSlidingDecision.resolveQuantityBranch(current, state.targetQuantity)
        val button = when (plan.branch) {
            BetterSlidingDecision.QuantityBranch.INCREASE -> p.increaseButton
            BetterSlidingDecision.QuantityBranch.DECREASE -> p.decreaseButton
            BetterSlidingDecision.QuantityBranch.DONE -> null
        }
        return overrideCheckQuantityBranch(nodeName, plan.nextNode, button, plan.repeat)
    }

    /**
     * 上游 handlers.go:644 `handleNoFineTune`：本次判定不微调时的出口。
     *
     * 按 FineTuneFallback 解析步进方向；步进为 0（none，或方向条件不成立）就复检后收尾。
     */
    private fun handleNoFineTune(nodeName: String, currentQuantity: Int): Boolean {
        val p = params ?: return false

        val (axis, endSign) = BetterSlidingDecision.resolveNudgeAxis(
            state.startBox,
            state.endBox,
            p.centerPointOffset,
        )
        val stepSign = BetterSlidingDecision.resolveStepSign(p.fineTuneFallback, currentQuantity, state.targetQuantity)

        if (stepSign != 0) {
            return nudgePreciseClick(nodeName, axis, endSign, stepSign, currentQuantity)
        }

        if (!overrideCheckQuantityBranch(nodeName, BetterSlidingSupport.NODE_DONE, null, 0)) return false
        host.info("BetterSliding 跳过微调，复检后收尾（fallback=${p.fineTuneFallback}）")
        return true
    }

    /** 上游 handlers.go:711 `nudgePreciseClick`。 */
    private fun nudgePreciseClick(
        nodeName: String,
        axis: BetterSlidingDecision.NudgeAxis,
        endSign: Int,
        stepSign: Int,
        currentQuantity: Int,
    ): Boolean {
        val p = params ?: return false

        state.preciseClickNudges += 1
        val nudged = BetterSlidingDecision.nudgedClickTarget(
            state.preciseClickBase,
            axis,
            endSign,
            stepSign,
            state.preciseClickNudges,
        )
        val side = BetterSlidingDecision.resolveReset2Side(
            axis,
            state.startBox,
            state.endBox,
            p.centerPointOffset,
            state.preciseClickBase,
        )
        val resetEnd = try {
            BetterSlidingOverrides.buildReset2SwipeEnd(p.direction, side)
        } catch (t: IllegalArgumentException) {
            host.warn("BetterSliding 复位终点构造失败：${t.message}")
            return false
        }

        val override = mapOf(
            BetterSlidingSupport.NODE_PRECISE_CLICK to mapOf(
                "action" to mapOf("param" to mapOf("target" to listOf(nudged.first, nudged.second))),
            ),
            BetterSlidingSupport.NODE_RESET2 to mapOf(
                "action" to mapOf("param" to mapOf("end" to resetEnd)),
            ),
        )
        if (!applyPipeline(override)) return false
        if (!overrideNext(nodeName, listOf(BetterSlidingSupport.NODE_RESET2))) return false

        host.info(
            "BetterSliding 微调第 ${state.preciseClickNudges} 次：" +
                "axis=${axis.label} nudged=(${nudged.first},${nudged.second}) side=${side.label} " +
                "current=$currentQuantity target=${state.targetQuantity}",
        )
        return true
    }

    // ────────────────────────── 小工具 ──────────────────────────

    /** 上游 handlers.go:200 `overrideCheckQuantityBranch`。 */
    private fun overrideCheckQuantityBranch(
        currentNode: String,
        nextNode: String,
        target: BetterSlidingParams.ButtonTarget?,
        repeat: Int,
    ): Boolean {
        val override = BetterSlidingOverrides.buildCheckQuantityBranchOverride(nextNode, target, repeat)
        if (override.isNotEmpty() && !applyPipeline(override)) {
            host.warn("BetterSliding 复查分支覆盖失败（next=$nextNode）")
            return false
        }
        return overrideNext(currentNode, listOf(nextNode))
    }

    private fun applyPipeline(override: Map<String, Any?>): Boolean =
        host.overridePipeline(JsonTree.toJson(override))

    /**
     * 改某个节点的 next。
     *
     * 上游用 `ctx.OverrideNext`；这里用覆盖 `next` 字段代替——本仓库没有绑定
     * `MaaContextOverrideNext`，而覆盖 next 是等价且更少假设的做法。
     */
    private fun overrideNext(node: String, next: List<String>): Boolean =
        applyPipeline(mapOf(node to mapOf("next" to next)))

    private fun preciseClickOverride(x: Int, y: Int): Map<String, Any?> = mapOf(
        BetterSlidingSupport.NODE_PRECISE_CLICK to mapOf(
            "action" to mapOf("param" to mapOf("target" to listOf(x, y))),
        ),
    )

    /**
     * 仅兜底：从 `detail_json` 找框。框优先用回调参数 [actionBox]（见 [handleFindStart]）。
     */
    private fun readHitBox(recoId: Long): List<Int>? = BetterSlidingOcr.readHitBox(detailOf(recoId))

    /**
     * 数量是 OCR 文本，不是 box，只能走 `detail_json`。
     *
     * 驱动节点 `BetterSlidingGetSliderMaxQuantity` / `BetterSlidingCheckQuantity` 是 `And`，
     * detail 根是「组合结果数组」；[BetterSlidingOcr.fromRecognizedDetail] 会解析数组并
     * 下钻到 `BetterSlidingGetSliderQuantity` 子项取文本——这是 2026-09 真机 bug 的修复点
     * （此前数组根返回 null，导致「读不到滑条上限」）。
     */
    private fun readQuantityValue(recoId: Long): Int? = BetterSlidingOcr.readQuantityValue(detailOf(recoId))

    private fun detailOf(recoId: Long): BetterSlidingOcr.Detail? {
        val json = host.recognitionDetailJson(recoId) ?: return null
        return BetterSlidingOcr.fromRecognizedDetail(host.parseJson(json), "")
    }
}
