package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 货物识别（据点交易 / 囤货）支持。
 * 策略：OCR 出文本+框，按商品名表贪心匹配首个未尝试项并返回其包围盒；
 * 流水线自带的 next/重试负责导航与确认，这里只解决“认不出货物”。
 */
object GoodsSupport {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class OcrItem(val text: String, val box: IntArray?)

    /** 从 OCR detail JSON 里收集 (文本, 包围盒) */
    fun collectOcrItems(detailJson: String?): List<OcrItem> {
        if (detailJson.isNullOrBlank()) return emptyList()
        val out = mutableListOf<OcrItem>()
        try {
            collectItems(json.parseToJsonElement(detailJson), out)
        } catch (_: Exception) {
        }
        return out
    }

    private fun collectItems(
        el: kotlinx.serialization.json.JsonElement,
        out: MutableList<OcrItem>,
    ) {
        when (el) {
            is kotlinx.serialization.json.JsonObject -> {
                val text = el["text"]?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrBlank()) {
                    val boxArr = el["box"]?.jsonArray
                    val box = if (boxArr != null && boxArr.size >= 4) {
                        val nums = boxArr.mapNotNull { it.jsonPrimitive.intOrNull }
                        if (nums.size >= 4) intArrayOf(nums[0], nums[1], nums[2], nums[3]) else null
                    } else null
                    out += OcrItem(text, box)
                }
                for ((_, v) in el) collectItems(v, out)
            }
            is kotlinx.serialization.json.JsonArray -> for (v in el) collectItems(v, out)
            else -> {}
        }
    }

    /**
     * 在 OCR 结果里找首个命中任一候选名的条目。
     * [tried] 为已尝试集合（命中即跳过）；返回匹配条目或 null。
     */
    fun findFirstMatch(
        items: List<OcrItem>,
        names: List<String>,
        tried: Set<String> = emptySet(),
    ): OcrItem? {
        for ((text, box) in items) {
            if (box == null) continue
            for (name in names) {
                if (name.isEmpty()) continue
                if (text.contains(name) || name.contains(text)) {
                    if (name in tried) continue
                    return OcrItem(text, box)
                }
            }
        }
        return null
    }

    /**
     * 带优先序的匹配：先按 [preferOrder] 顺序（如套利价升序）找首个未尝试命中，
     * 全部落空再退回 [findFirstMatch] 的阅读顺序贪心。
     */
    fun findBestMatch(
        items: List<OcrItem>,
        names: List<String>,
        tried: Set<String> = emptySet(),
        preferOrder: List<String>? = null,
    ): OcrItem? {
        if (preferOrder != null) {
            for (name in preferOrder) {
                if (name.isEmpty() || name in tried) continue
                val item = items.firstOrNull { o ->
                    o.box != null && (o.text.contains(name) || name.contains(o.text))
                }
                if (item != null) return item
            }
        }
        return findFirstMatch(items, names, tried)
    }

    /** 按名称反查其在候选表中的标准名（用于标记 tried） */
    fun standardName(text: String, names: List<String>): String? {
        for (name in names) {
            if (name.isEmpty()) continue
            if (text.contains(name) || name.contains(text)) return name
        }
        return null
    }
}
