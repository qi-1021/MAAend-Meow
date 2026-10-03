package com.aliothmoon.maafw.remote

/**
 * `OutpostTradingPrioritySession` / `OutpostTradingPriorityItem` /
 * `OutpostTradingCurrentGoods` 的纯逻辑层
 * （对齐上游 `outposttrading/goods/session.go` 与 `selection.go`）。
 *
 * 这个会话维护「用户配置的优先售卖物品、已尝试/待确认/当前物品、任务级缺货集合」，
 * 并提供选品过滤：优先售卖项要按用户顺序排前，[OutpostReserveSupport] 的
 * 永不售卖 / 已满足集合必须从候选中排除，缺货与已尝试也不得再选。
 *
 * 物品身份统一用 zh_cn 商品名（见 [OutpostReserveSupport] 顶部说明）。
 */
object OutpostPrioritySupport {

    const val OPERATION_CONFIGURE = "configure"
    const val OPERATION_CONFIGURE_STRATEGY = "configure_strategy"
    const val OPERATION_RESET_PREFERRED = "reset_preferred"
    const val OPERATION_RESET_GOODS_SELECTION = "reset_goods_selection"
    const val OPERATION_REGISTER = "register"
    const val OPERATION_COMMIT = "commit"
    const val OPERATION_ADOPT = "adopt"
    const val OPERATION_OUT_OF_STOCK = "out_of_stock"

    const val STRATEGY_RARITY = "rarity"
    const val STRATEGY_PRICE = "price"
    const val STRATEGY_STOCK = "stock"

    /** 上游 `sellstrategy.Kind`：只有这三种合法。 */
    val STRATEGIES = setOf(STRATEGY_RARITY, STRATEGY_PRICE, STRATEGY_STOCK)

    /** 上游 session.go:31 `prioritySessionActionParam`。 */
    data class ActionParam(
        val operation: String,
        val enabled: Boolean = false,
        val onlyPreferred: Boolean = false,
        val strategy: String = "",
        val minimumPrice: Int = 0,
        val location: String = "",
        val itemId: String = "",
    )

    /**
     * 上游 session.go:179 `parsePrioritySessionActionParam`。
     *
     * 返回 null 表示参数不合法（未知 operation、未知 strategy、commit/adopt/out_of_stock
     * 缺 location）。注意 configure_strategy 不再接受空串：上游 `sellstrategy.New("")`
     * 会失败，移动端此前把空串当合法静默吞掉。
     */
    fun parseParam(tree: Any?): ActionParam? {
        val map = tree as? Map<*, *> ?: return null
        val operation = (map["operation"] as? String)?.trim().orEmpty()
        val param = ActionParam(
            operation = operation,
            enabled = map["enabled"] as? Boolean ?: false,
            onlyPreferred = map["only_preferred"] as? Boolean ?: false,
            strategy = (map["strategy"] as? String)?.trim().orEmpty(),
            minimumPrice = (map["minimum_unit_price"] as? Number)?.toInt() ?: 0,
            location = (map["location"] as? String)?.trim().orEmpty(),
            itemId = (map["item_id"] as? String)?.trim().orEmpty(),
        )
        when (operation) {
            OPERATION_CONFIGURE, OPERATION_RESET_PREFERRED, OPERATION_RESET_GOODS_SELECTION -> {}
            OPERATION_CONFIGURE_STRATEGY -> if (param.strategy !in STRATEGIES) return null
            OPERATION_REGISTER -> {}
            OPERATION_COMMIT, OPERATION_ADOPT, OPERATION_OUT_OF_STOCK -> {
                if (param.location.isEmpty()) return null
            }

            else -> return null
        }
        return param
    }

    /** 选品策略快照；`enabled=false` 时不套用任何用户优先（上游 session.go:265）。 */
    data class Policy(val preferred: List<String>, val onlyPreferred: Boolean)

    data class OutOfStockOutcome(val name: String, val marked: Boolean, val ok: Boolean)

    private data class ExhaustionObs(val signature: String, val count: Int)

    /**
     * 上游 session.go:58 `prioritySelectionSessionState`。
     *
     * 全部按 zh_cn 商品名索引；状态整体可重置（任务开始时或保留会话 reset 时）。
     */
    class Session {
        var enabled: Boolean = false
            private set
        private var onlyPreferredFlag: Boolean = false
        private var regionEnabled: Boolean = false
        var strategy: String = "rarity"
            private set
        var minimumPrice: Int = 0
            private set

        private val preferred = LinkedHashSet<String>()
        private val attempted = LinkedHashMap<String, LinkedHashSet<String>>()
        private val pending = LinkedHashMap<String, String>()
        private val current = LinkedHashMap<String, String>()
        private val outOfStock = LinkedHashSet<String>()
        private val goodsSelection = LinkedHashMap<String, List<String>>()
        private val exhaustion = LinkedHashMap<String, ExhaustionObs>()

        /** 上游 session.go:301 `resetPrioritySelectionSession`：整表重建。 */
        fun resetAll() {
            enabled = false
            onlyPreferredFlag = false
            regionEnabled = false
            strategy = "rarity"
            minimumPrice = 0
            preferred.clear()
            attempted.clear()
            pending.clear()
            current.clear()
            outOfStock.clear()
            goodsSelection.clear()
            exhaustion.clear()
        }

        /** 上游 session.go:307 `configurePrioritySession`。 */
        fun configure(enabled: Boolean, onlyPreferred: Boolean) {
            this.enabled = enabled
            onlyPreferredFlag = enabled && onlyPreferred
        }

        /** 上游 session.go:314 `configureSelectionStrategy`。 */
        fun configureStrategy(kind: String, minimumPrice: Int) {
            this.strategy = kind
            this.minimumPrice = minimumPrice
        }

        /** 上游 session.go:323 `resetPreferredPriorityItems`。 */
        fun resetPreferred(enabled: Boolean) {
            regionEnabled = enabled
            preferred.clear()
        }

        /** 上游 session.go:223 `resetGoodsSelection`。 */
        fun resetGoodsSelection() {
            goodsSelection.clear()
        }

        /** 上游 session.go:253 `registerPriorityItem`；重复返回 false（保留首次槽位顺序）。 */
        fun registerPreferred(name: String): Boolean {
            if (name.isEmpty() || preferred.contains(name)) return false
            preferred.add(name)
            return true
        }

        /** 上游 session.go:265 `priorityPolicySnapshot`。 */
        fun policy(): Policy =
            if (!enabled || !regionEnabled) {
                Policy(emptyList(), false)
            } else {
                Policy(preferred.toList(), onlyPreferredFlag)
            }

        fun attemptedNames(location: String): Set<String> = attempted[location]?.toSet() ?: emptySet()

        fun outOfStockNames(): Set<String> = outOfStock.toSet()

        fun currentName(location: String): String = current[location].orEmpty()

        fun pendingName(location: String): String = pending[location].orEmpty()

        fun preferredNames(): List<String> = preferred.toList()

        /** 上游 session.go:344 `prioritySelectionSetPending`。 */
        fun setPending(location: String, name: String) {
            pending[location] = name
            exhaustion.remove(location)
            goodsSelection.remove(location)
        }

        /**
         * 上游 session.go:351 `prioritySelectionCommit`：
         * 待确认项转正（记录已尝试 + 当前），清空 pending / 耗尽观察。
         */
        fun commit(location: String): String? {
            val name = pending[location] ?: return null
            attempted.getOrPut(location) { LinkedHashSet() }.add(name)
            current[location] = name
            pending.remove(location)
            exhaustion.remove(location)
            goodsSelection.remove(location)
            return name
        }

        /** 上游 session.go:370 `prioritySelectionAdopt`：采用界面已识别的当前货品。 */
        fun adopt(location: String, name: String) {
            attempted.getOrPut(location) { LinkedHashSet() }.add(name)
            current[location] = name
            pending.remove(location)
            exhaustion.remove(location)
            goodsSelection.remove(location)
        }

        /** 上游 session.go:382 `prioritySelectionMarkOutOfStock`。 */
        fun markOutOfStock(location: String): OutOfStockOutcome {
            val name = current[location] ?: return OutOfStockOutcome("", false, false)
            val marked = outOfStock.add(name)
            exhaustion.remove(location)
            return OutOfStockOutcome(name, marked, true)
        }

        /**
         * 上游 session.go:229 `beginGoodsSelection`：每轮选货重新扫描，只重置本据点耗尽快照。
         * 注意**不清** exhaustion：耗尽确认依赖跨轮连续两次观察（select → exhausted 循环）。
         */
        fun beginGoodsSelection(location: String) {
            goodsSelection[location] = emptyList()
        }

        /** 上游 session.go:235 `setGoodsSelectionExhaustedItems`。 */
        fun setGoodsSelectionExhausted(location: String, recognized: List<String>) {
            goodsSelection[location] = recognized.toList()
        }

        fun goodsSelectionExhausted(location: String): List<String> = goodsSelection[location].orEmpty()

        /**
         * 上游 session.go:401 `prioritySelectionObserveExhaustion`：
         * 连续两次观察到同一「仅剩已尝试/不可选集合」才确认耗尽，避免单帧 OCR 波动误判。
         */
        fun observeExhaustion(location: String, recognized: List<String>): Boolean {
            val signature = recognized.sorted().joinToString("|")
            val previous = exhaustion[location]
            val count = if (previous?.signature == signature) previous.count + 1 else 1
            exhaustion[location] = ExhaustionObs(signature, count)
            return count >= 2
        }
    }

    /**
     * 上游 selection.go:74-99 `selectGoodsTarget` 的过滤/排序部分（去掉实时库存与策略打分）：
     *
     *  - 先按用户配置的 [preferred] 顺序取可见优先项；[onlyPreferred]=true 时只保留它们
     *  - 其余保持 [baseOrder]（据点原始顺序 / 套利或 bias 顺序）
     *  - [excluded] 汇总已尝试 / 缺货 / 永不售卖 / 已满足，一律不得入选
     */
    fun selectableNames(
        baseOrder: List<String>,
        preferred: List<String>,
        onlyPreferred: Boolean,
        excluded: Set<String>,
    ): List<String> {
        val base = baseOrder.toSet()
        val result = LinkedHashSet<String>()
        if (onlyPreferred) {
            for (name in preferred) {
                if (name in base && name !in excluded) result += name
            }
            return result.toList()
        }
        for (name in preferred) {
            if (name in base && name !in excluded) result += name
        }
        for (name in baseOrder) {
            if (name !in excluded) result += name
        }
        return result.toList()
    }

    /** 单个商品名是否可选（CurrentGoods 判断当前货品能否沿用）。 */
    fun isSelectable(name: String, preferred: List<String>, onlyPreferred: Boolean, excluded: Set<String>): Boolean {
        if (name in excluded) return false
        if (onlyPreferred && name !in preferred) return false
        return true
    }

    /**
     * 按 [order] 顺序在 OCR 结果里找首个命中项；命中返回 (标准名, OCR 条目)。
     * 与 [GoodsSupport.findBestMatch] 不同：**不做阅读顺序兜底**，避免选中未被过滤的货物。
     */
    fun firstVisible(
        items: List<GoodsSupport.OcrItem>,
        order: List<String>,
    ): Pair<String, GoodsSupport.OcrItem>? {
        for (name in order) {
            if (name.isEmpty()) continue
            val item = items.firstOrNull { o ->
                o.box != null && (o.text.contains(name) || name.contains(o.text))
            }
            if (item != null) return name to item
        }
        return null
    }

    /** 当前帧 OCR 里能对应到据点货物表的所有标准名（去重，用于耗尽确认）。 */
    fun visibleStandardNames(items: List<GoodsSupport.OcrItem>, names: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (item in items) {
            GoodsSupport.standardName(item.text, names)?.let { out += it }
        }
        return out.toList()
    }
}
