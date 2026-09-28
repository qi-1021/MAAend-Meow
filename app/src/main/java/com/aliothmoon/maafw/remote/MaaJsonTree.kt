package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * kotlinx.serialization 的 [JsonElement] ↔ 通用树（Map/List/String/Boolean/Number/null）转换。
 *
 * 为什么要有这层：BetterSliding 的纯逻辑层（[BetterSlidingParams] / [BetterSlidingOcr] /
 * [BetterSlidingSession]）刻意不依赖任何 JSON 库，只吃通用树。MaaFramework 那侧给的是字符串，
 * 于是解析与转换收在这里一处，纯逻辑层保持可本地测试。
 */
object MaaJsonTree {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        return try {
            fromElement(json.parseToJsonElement(text))
        } catch (_: Exception) {
            null
        }
    }

    fun fromElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> primitive(element)
        is JsonObject -> element.mapValues { fromElement(it.value) }
        is JsonArray -> element.map { fromElement(it) }
    }

    /**
     * 字面量优先级：字符串 → 布尔 → 整数 → 小数。
     *
     * 必须先判 [JsonPrimitive.isString]：否则 `"3"`（带引号）会被当成数字 3，
     * attach 里把 TargetQuantity 写成字符串时就会被静默接受。
     */
    private fun primitive(element: JsonPrimitive): Any? {
        if (element.isString) return element.content
        element.booleanOrNull?.let { return it }
        element.longOrNull?.let { return it }
        element.doubleOrNull?.let { return it }
        return element.content
    }
}
