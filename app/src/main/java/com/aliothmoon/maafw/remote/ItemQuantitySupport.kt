package com.aliothmoon.maafw.remote

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `ItemQuantitySatisfied`（IMS R1）与 IMS 库存容器的纯逻辑层。
 *
 * 对齐上游：
 *  - `agent/go-service/ims/item_quantity_satisfied.go`（参数解析 / 表达式求值 / report_only）
 *  - `agent/go-service/ims/cache.go`（内存库存容器）
 *  - `pkg/boolexpr`（表达式求值，见 [BoolExpr]）
 *
 * ## 本轮审计的根因（为什么必须实现）
 * `ItemQuantitySatisfied` 此前被注册成恒真 `noopTrueRecognitionCallback`。采集
 * （AutoCollect）「目标库存模式」下，`AutoCollectRouteNSubSkipped`（语义是「库存已达标，
 * 跳过采集」）排在 next 第一位；恒真导致**所有路线都被判已达标直接跳过**——任务成功
 * 结束但什么都没采。这是静默空转里最隐蔽的一处。
 *
 * ## 空库存的正确语义
 * 本项目的 IMS 记账动作 `AddItemData` / `SyncItemData` / `UpdateItemQuantity` 目前仍是
 * noop（未移植），所以库存容器**一直是空的**。空库存的正确语义是「**不知道 / 未达标**」，
 * 而不是「已达标」：
 *  - 缺失物品按数量 0 参与求值（对齐上游 `cache.quantity` 对缺失项返回 0）；
 *  - 于是 `{item} < {limit}`、`{limit} == 0` 等门控会命中 → **放行去采集**；
 *  - `SubSkipped` 的 `!({limit}==0 || {item}<{limit})` 不命中 → **不会跳过**。
 * MaaRunner 在检测到库存为空/未初始化时会打一条日志（只打一次），提醒不要误以为门控
 * 已经生效。**绝不能把「未知」当成「已达标」。**
 */
object ItemQuantitySupport {

    /** 上游 `itemQuantitySatisfiedParam`。 */
    data class Param(
        val expression: String,
        val notifyUi: Boolean = false,
        val reportOnly: Boolean = false,
    )

    /** 参数 / 表达式 / 库存层面的错误；调用方一律按「不命中」处理，不静默成功。 */
    class ItemQuantityException(message: String) : IllegalArgumentException(message)

    /**
     * 上游 `parseItemQuantitySatisfiedParam`：`custom_recognition_param` 必需且为 JSON 对象，
     * `expression` trim 后必需；`report_only` 时表达式必须恰好含一个 `{ITEM_ID}`。
     */
    fun parseParams(raw: String?): Param {
        if (raw.isNullOrBlank()) throw ItemQuantityException("custom_recognition_param is required")
        val tree = MaaJsonTree.parse(raw) as? Map<*, *>
            ?: throw ItemQuantityException("custom_recognition_param must be a JSON object")
        val expression = (tree["expression"] as? String)?.trim().orEmpty()
        if (expression.isEmpty()) throw ItemQuantityException("expression is required")
        val notifyUi = tree["notify_ui"] as? Boolean ?: false
        val reportOnly = tree["report_only"] as? Boolean ?: false
        if (reportOnly) singleItemId(expression)
        return Param(expression, notifyUi, reportOnly)
    }

    /** 上游 `singleItemIDFromExpression`：要求表达式恰好一个 `{ITEM_ID}`，返回其名字。 */
    fun singleItemId(expression: String): String {
        val matches = BoolExpr.PLACEHOLDER_PATTERN.findAll(expression).toList()
        if (matches.isEmpty()) {
            throw ItemQuantityException("report_only expression must contain one {ITEM_ID}")
        }
        if (matches.size > 1) {
            throw ItemQuantityException(
                "report_only expression must contain exactly one {ITEM_ID}, got ${matches.size}",
            )
        }
        val itemId = matches[0].groupValues[1].trim()
        if (itemId.isEmpty()) throw ItemQuantityException("report_only item id must not be empty")
        return itemId
    }

    /** 一次求值的结果；[reportOnly] 恒 [matched]（上游 report_only 始终命中）。 */
    data class Evaluation(
        val matched: Boolean,
        val expression: String,
        val resolvedExpression: String,
        val values: Map<String, Long>,
        val reportItemId: String? = null,
        val reportQuantity: Int? = null,
    )

    /**
     * 对当前库存求值。[quantity] 对缺失/未知物品返回 0（空库存 → 倾向于放行采集）。
     * 表达式非法或结果不是 bool 时抛 [ItemQuantityException]。
     */
    fun evaluate(param: Param, quantity: (String) -> Int): Evaluation {
        if (param.reportOnly) {
            val itemId = singleItemId(param.expression)
            val qty = quantity(itemId)
            return Evaluation(
                matched = true,
                expression = param.expression,
                resolvedExpression = param.expression,
                values = linkedMapOf(itemId to qty.toLong()),
                reportItemId = itemId,
                reportQuantity = qty,
            )
        }
        try {
            val resolved = BoolExpr.resolvePlaceholders(param.expression) { name -> quantity(name).toLong() }
            val result = BoolExpr.evaluateRaw(resolved.expression)
            val matched = result as? Boolean
                ?: throw ItemQuantityException("expression result must be boolean, got ${resultTypeName(result)}")
            return Evaluation(
                matched = matched,
                expression = param.expression,
                resolvedExpression = resolved.expression,
                values = resolved.values,
            )
        } catch (e: BoolExpr.BoolExprException) {
            throw ItemQuantityException(e.message ?: "invalid expression")
        }
    }

    private fun resultTypeName(value: Any): String = when (value) {
        is Long -> "int"
        is Boolean -> "bool"
        else -> value::class.simpleName ?: "unknown"
    }
}

/**
 * IMS 库存内存缓存（对齐上游 `ims/cache.go` 的 `cache`）。
 *
 * 语义：
 *  - 只有一次成功的库存同步（上游 A2 `SyncItemData` → `markSynced`）才会把 [hasData]
 *    置真并记录 [snapshot].lastSyncEpochMillis；`AddItemData`（A3）/ `UpdateItemQuantity`（A1）
 *    的增量只改数量、不动就绪状态。
 *  - [quantity] 对缺失物品返回 0（这是空库存「未知」的表现，见 [ItemQuantitySupport] 注释）。
 *  - [applyDelta] 结果钳到 >= 0。
 *
 * 注意：本项目的记账动作仍未移植，所以这个容器在生产里一直是空的；先把它抽成纯逻辑
 * 并单测，等 AddItemData/SyncItemData 落地时直接共用。
 */
class ImsItemCache {

    data class Snapshot(
        val hasData: Boolean,
        val lastSyncEpochMillis: Long,
        val items: Map<String, Int>,
    )

    data class DeltaResult(
        val before: Int,
        val after: Int,
        val clamped: Boolean,
        val items: Map<String, Int>,
        val lastSyncEpochMillis: Long,
        val hasData: Boolean,
    )

    private val lock = ReentrantLock()
    private var hasData = false
    private var lastSyncEpochMillis = 0L
    private var items: Map<String, Int> = emptyMap()

    fun snapshot(): Snapshot = lock.withLock { Snapshot(hasData, lastSyncEpochMillis, items.toMap()) }

    fun hasData(): Boolean = lock.withLock { hasData }

    fun isEmpty(): Boolean = lock.withLock { items.isEmpty() }

    /** 上游 `markSynced`：一次成功同步。items 可以为空。 */
    fun markSynced(atEpochMillis: Long, newItems: Map<String, Int>) = lock.withLock {
        hasData = true
        lastSyncEpochMillis = atEpochMillis
        items = newItems.toMap()
        Unit
    }

    /** 上游 `clear`：清空并标记未就绪。 */
    fun clear() = lock.withLock {
        hasData = false
        lastSyncEpochMillis = 0L
        items = emptyMap()
        Unit
    }

    fun itemsCopy(): Map<String, Int> = lock.withLock { items.toMap() }

    /** 上游 `quantity`：缺失物品返回 0。 */
    fun quantity(item: String): Int = lock.withLock { items[item] ?: 0 }

    /** 上游 `applyDelta`：加增量并钳到 >= 0，不改变就绪状态。 */
    fun applyDelta(item: String, delta: Int): DeltaResult = lock.withLock {
        val before = items[item] ?: 0
        var afterLong = before.toLong() + delta.toLong()
        var clamped = false
        if (afterLong < 0L) {
            afterLong = 0L
            clamped = true
        }
        val after = afterLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        items = items + (item to after)
        DeltaResult(before, after, clamped, items.toMap(), lastSyncEpochMillis, hasData)
    }

    /** 上游 `setItemsOnly`：只替换数量，不动就绪状态/时间戳。 */
    fun setItemsOnly(newItems: Map<String, Int>) = lock.withLock {
        items = newItems.toMap()
        Unit
    }
}

/** 进程级 IMS 库存缓存；识别（已移植）与未来的记账动作共用同一份状态。 */
object Ims {
    val cache = ImsItemCache()
}
