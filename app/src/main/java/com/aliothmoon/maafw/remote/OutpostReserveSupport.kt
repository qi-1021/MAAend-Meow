package com.aliothmoon.maafw.remote

/**
 * `OutpostTradingReserveSession` 的纯逻辑层（对齐上游 `outposttrading/goods/reserve.go`）。
 *
 * 这个会话维护「本次任务每个物品保留多少件、哪些已经满足、当前正在处理哪件」，
 * 并在执行售卖数量滑块前**改写 pipeline**：配置了保留 N 件的物品只卖超出部分，
 * 未配置的全部卖出；活动物品按当前据点调度券额度整批卖出。
 *
 * 为什么必须抽出来：整个模块原本在移动端是 noop-success，等于把用户显式配置的
 * 「保留 / 永不出售」语义静默丢掉，还会把活动物品卖到超额度触发整任务 Stop。
 *
 * 纯逻辑（参数解析、状态机、滑块覆盖构造、额度÷单价换算）放这里测；
 * 取截图、跑 OCR、调 `OverridePipeline` 留给 MaaRunner。
 *
 * 物品身份统一用 **zh_cn 商品名**：识别侧只产出商品名，[OutpostData] 负责
 * `item_id <-> 商品名` 的映射，避免同一件货在两套 id 之间来回翻译。
 */
object OutpostReserveSupport {

    const val OPERATION_RESET = "reset"
    const val OPERATION_REGISTER = "register"
    const val OPERATION_SELECT = "select"
    const val OPERATION_APPLY = "apply"
    const val OPERATION_SATISFY = "satisfy"

    /** 任务配置中「永不售卖」的唯一哨兵值（上游 reserve.go:22）。 */
    const val BLACKLIST_QUANTITY = -1

    /** 未配置保留规则时的目标数量：尽量全卖（上游 reserve.go:392）。 */
    const val SELL_ALL_QUANTITY = 999999

    const val NODE_SELL = "OutpostTradingSell"
    const val NODE_SELL_THEN_LOOP = "OutpostTradingSellThenLoop"
    const val NODE_SELL_LOOP = "OutpostTradingSellLoop"
    const val NODE_RESERVE_ALREADY_SATISFIED = "OutpostTradingReserveAlreadySatisfied"
    const val NODE_SELL_LOOP_END = "OutpostTradingSellLoopEnd"

    /** 上游 reserve.go:39 `reserveSessionActionParam`。 */
    data class ActionParam(
        val operation: String,
        val itemId: String = "",
        val quantity: Int = 0,
        val slidingNode: String = "",
        val location: String = "",
    )

    /**
     * 上游 reserve.go:213 `parseReserveSessionActionParam`。
     *
     * 返回 null 表示参数不合法（未知 operation、register 数量小于 -1、select 缺 item_id、
     * apply 缺 location/sliding_node），调用方应判动作失败而不是静默跳过。
     */
    fun parseParam(tree: Any?): ActionParam? {
        val map = tree as? Map<*, *> ?: return null
        val operation = (map["operation"] as? String)?.trim().orEmpty()
        val param = ActionParam(
            operation = operation,
            itemId = (map["item_id"] as? String)?.trim().orEmpty(),
            quantity = (map["quantity"] as? Number)?.toInt() ?: 0,
            slidingNode = (map["sliding_node"] as? String)?.trim().orEmpty(),
            location = (map["location"] as? String)?.trim().orEmpty(),
        )
        when (operation) {
            OPERATION_RESET, OPERATION_SATISFY -> {}
            OPERATION_REGISTER -> if (param.quantity < BLACKLIST_QUANTITY) return null
            OPERATION_SELECT -> if (param.itemId.isEmpty()) return null
            OPERATION_APPLY -> {
                if (param.location.isEmpty()) return null
                if (param.slidingNode.isEmpty()) return null
            }

            else -> return null
        }
        return param
    }

    /**
     * 上游 reserve.go:260 `parseReserveItemIDAttach`。
     *
     * `register` 槽位未显式给 `item_id` 时，从调用节点定义里读 `attach.item_id`。
     * 节点 JSON 解析失败返回 null；没有 attach / item_id 返回空串。
     */
    fun parseAttachItemId(nodeJson: String?): String? {
        if (nodeJson.isNullOrBlank()) return null
        val map = MaaJsonTree.parse(nodeJson) as? Map<*, *> ?: return null
        val attach = map["attach"] as? Map<*, *> ?: return ""
        return (attach["item_id"] as? String)?.trim().orEmpty()
    }

    /** 当前选中物品的保留规则；[configured] 为真表示「数量 > 0 的有效保留」。 */
    data class SelectedRule(val name: String, val quantity: Int, val configured: Boolean)

    /** `satisfy` 的结果；[ok] 为假表示当前物品没有有效保留规则（上游判失败）。 */
    data class SatisfyOutcome(val name: String, val quantity: Int, val marked: Boolean, val ok: Boolean)

    /**
     * 上游 reserve.go:32-37 的三个任务级 Map，整体可重置。
     *
     * - `rules`: 商品名 -> 保留数量（-1 表示永不售卖）
     * - `satisfied`: 本次任务已达到保留数量的商品名
     * - `selected`: 当前正在处理的商品名
     */
    class Session {
        private val rules = LinkedHashMap<String, Int>()
        private val satisfied = LinkedHashSet<String>()
        private var selected: String = ""

        /** 上游 reserve.go:273 `resetReserveSession`（不含调用方对优先会话的连带重置）。 */
        fun reset() {
            rules.clear()
            satisfied.clear()
            selected = ""
        }

        /** 上游 reserve.go:310 `registerReserveRule`；返回是否覆盖了已有规则。 */
        fun registerRule(name: String, quantity: Int): Boolean {
            val replaced = rules.containsKey(name)
            rules[name] = quantity
            return replaced
        }

        /** 上游 reserve.go:342 `setSelectedReserveItem`。 */
        fun setSelected(name: String) {
            selected = name.trim()
        }

        fun selectedName(): String = selected

        /** 上游 reserve.go:348 `selectedReserveRule`。 */
        fun selectedRule(): SelectedRule {
            val quantity = rules[selected] ?: 0
            return SelectedRule(selected, quantity, rules.containsKey(selected) && quantity > 0)
        }

        /** 上游 reserve.go:284 `markSelectedReserveSatisfied`。 */
        fun markSatisfied(): SatisfyOutcome {
            val name = selected
            val quantity = rules[name]
            if (name.isEmpty() || quantity == null || quantity <= 0) {
                return SatisfyOutcome(name, quantity ?: 0, false, false)
            }
            val marked = satisfied.add(name)
            return SatisfyOutcome(name, quantity, marked, true)
        }

        /** 上游 reserve.go:330：本次任务明确配置为永不售卖的商品名。 */
        fun blacklistedItems(): Set<String> =
            rules.filterValues { it == BLACKLIST_QUANTITY }.keys.toSet()

        /** 上游 reserve.go:299：本次任务已达到保留数量的商品名。 */
        fun satisfiedItems(): Set<String> = satisfied.toSet()

        /** 诊断/测试用快照。 */
        fun rulesSnapshot(): Map<String, Int> = LinkedHashMap(rules)
    }

    /**
     * 上游 reserve.go:180 `activityQuantityFromStockBills` 的换算部分：
     * 当前调度券额度 ÷ 单价 = 本批可卖数量。单价非正或额度为负返回 null（调用方判失败）。
     */
    fun activityQuantity(stockBills: Int, unitPrice: Int): Int? {
        if (unitPrice <= 0) return null
        if (stockBills < 0) return null
        return stockBills / unitPrice
    }

    /** `apply` 的规划结果：要么一段可直接交给 `OverridePipeline` 的 JSON，要么明确失败。 */
    sealed interface ApplyPlan {
        data class Override(val next: String, val overrideJson: String) : ApplyPlan
        data class Failed(val reason: String) : ApplyPlan
    }

    /**
     * 上游 reserve.go:104-155 `apply` 分支的纯实现。
     *
     * 负责决定：
     *  - 黑名单物品不得进入滑块（应已被选品阶段排除，到达即契约破坏 → 失败）
     *  - 活动物品：额度 ÷ 单价；额度不足一件直接跳 `SellLoopEnd`
     *  - 非活动 + 配置保留 N：`TargetQuantity=N, ReverseTarget=true`，next 走 `SellThenLoop`
     *  - 非活动 + 未配置：`TargetQuantity=999999, ReverseTarget=false`，next 走 `Sell`
     *
     * 并把 `next` 覆盖到当前调用节点 [nodeName] 上（上游 reserve.go:141）。
     */
    fun planApply(
        nodeName: String,
        slidingNode: String,
        quantity: Int,
        configured: Boolean,
        unitPrice: Int,
        activity: Boolean,
        stockBills: Int,
    ): ApplyPlan {
        if (quantity == BLACKLIST_QUANTITY) {
            return ApplyPlan.Failed("blacklisted item reached reserve rule application")
        }
        if (unitPrice <= 0) {
            return ApplyPlan.Failed("invalid unit price: $unitPrice")
        }

        val base: Map<String, Any?>
        val next: String
        if (activity) {
            val activityQuantity = activityQuantity(stockBills, unitPrice)
                ?: return ApplyPlan.Failed("invalid stock bills quantity: $stockBills")
            if (activityQuantity == 0) {
                // BetterSliding 不接受零目标：额度不足一件时由 Pipeline 结束据点售卖。
                base = emptyMap()
                next = NODE_SELL_LOOP_END
            } else {
                base = buildActivitySlidingOverride(slidingNode, activityQuantity)
                next = slidingNode
            }
        } else {
            base = buildReserveSlidingOverride(slidingNode, quantity, configured)
            next = slidingNode
        }

        val override = LinkedHashMap<String, Any?>(base)
        override[nodeName] = mapOf("next" to listOf(next))
        return ApplyPlan.Override(next, JsonTree.toJson(override))
    }

    /**
     * 上游 reserve.go:373 `buildReserveSlidingOverride`：
     * 配置了保留数量只卖超出部分（ReverseTarget=true），未配置则全部卖出。
     */
    fun buildReserveSlidingOverride(
        slidingNode: String,
        quantity: Int,
        configured: Boolean,
    ): Map<String, Any?> = if (configured) {
        mapOf(
            slidingNode to mapOf(
                "next" to listOf(NODE_RESERVE_ALREADY_SATISFIED, NODE_SELL_THEN_LOOP),
                "attach" to mapOf(
                    "TargetQuantity" to quantity,
                    "ReverseTarget" to true,
                ),
            ),
        )
    } else {
        mapOf(
            slidingNode to mapOf(
                "next" to listOf(NODE_SELL),
                "attach" to mapOf(
                    "TargetQuantity" to SELL_ALL_QUANTITY,
                    "ReverseTarget" to false,
                ),
            ),
        )
    }

    /** 上游 reserve.go:359 `buildActivitySlidingOverride`：活动物品按额度整批卖出。 */
    fun buildActivitySlidingOverride(
        slidingNode: String,
        activityQuantity: Int,
    ): Map<String, Any?> = mapOf(
        slidingNode to mapOf(
            "next" to listOf(NODE_SELL),
            "attach" to mapOf(
                "TargetQuantity" to activityQuantity,
                "ReverseTarget" to false,
            ),
        ),
    )
}
