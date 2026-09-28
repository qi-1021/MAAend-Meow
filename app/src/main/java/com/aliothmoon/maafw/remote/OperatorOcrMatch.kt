package com.aliothmoon.maafw.remote

/** OCR 文本的画面区域（对齐上游 `maa.Rect` 的用到的那部分）。 */
data class OcrBox(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * OutpostTrading 各领域共享的**严格规范化 OCR 匹配**
 * （对齐上游 `outposttrading/internal/ocrmatch/match.go`）。
 *
 * 货品和干员名称用分层**严格相等**匹配，**不用**编辑距离或包含匹配。两层：
 *
 *  1. **Tier A**：剥除空白与常见分隔符（方括号/竖线/连字符/点号/顿号…）并统一小写后要求严格相等。
 *     用于 EN 名在 OCR 里多出 `[` `]` `|` 的情况。
 *  2. **Tier B**：在 Tier A 基础上再剔除 ASCII 字母与数字（CJK 名称里的噪声），仍要求严格相等。
 *     用于 `I紫晶质瓶` → `紫晶质瓶`；而「优质柑实罐头」的 CJK 核心与「柑实罐头」**不相等**，
 *     天然不会被误匹配——这正是不能用包含匹配的原因。
 *
 * 别拿 [GoodsSupport.findBestMatch] 那套包含/模糊匹配来替代：那边容忍误配，这边误配代价高
 * （会点错干员、把加成算错）。
 */
object OperatorOcrMatch {

    /** 一项 OCR 文本结果。 */
    data class Item(val text: String, val box: OcrBox)

    /** 命中结果。[tier] 为 `A` / `B`，或 [TIER_PREFIX_NOISE]。 */
    data class Result(val ocrText: String, val candidate: String, val tier: String, val box: OcrBox)

    /** 上游 matching.go:88 使用的前缀噪声标记，不属于 FindBest 的两层。 */
    const val TIER_PREFIX_NOISE = "operator_prefix_noise"

    /**
     * 上游 match.go:70 `FindBest`：按 Tier A → Tier B 顺序匹配，任一层命中即返回。
     *
     * 两层都是「按屏幕顺序的 item × 配置顺序的 candidate」双重循环，
     * 所以同分时优先命中**靠上/靠左**的文本。
     */
    fun findBest(items: List<Item>, candidates: List<String>): Result? {
        val tierA = candidates.map { stripSeparators(it) }
        val tierB = tierA.map { stripAsciiAlnum(it) }
        val sorted = sortItemsByPosition(items)

        for (item in sorted) {
            val ocrA = stripSeparators(item.text)
            if (ocrA.isEmpty()) continue
            for ((i, candA) in tierA.withIndex()) {
                if (candA.isNotEmpty() && ocrA == candA) {
                    return Result(item.text, candidates[i], "A", item.box)
                }
            }
        }

        for (item in sorted) {
            val ocrB = stripAsciiAlnum(stripSeparators(item.text))
            if (ocrB.isEmpty()) continue
            for ((i, candB) in tierB.withIndex()) {
                if (candB.isEmpty()) continue
                if (ocrB == candB) {
                    return Result(item.text, candidates[i], "B", item.box)
                }
            }
        }

        return null
    }

    /** 上游 match.go:121 `SortItemsByPosition`：从上到下、从左到右稳定排序。 */
    fun sortItemsByPosition(items: List<Item>): List<Item> =
        items.sortedWith(compareBy({ it.box.y }, { it.box.x }))

    /**
     * 上游 match.go:134 `StripSeparators`。
     *
     * 剥除分隔符与所有 Unicode 空白，其余统一小写；**保留**字母/数字/CJK。
     */
    fun stripSeparators(s: String): String {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder(trimmed.length)
        for (r in trimmed) {
            if (r in SEPARATORS) continue
            if (r.isWhitespace()) continue
            sb.append(r.lowercaseChar())
        }
        return sb.toString()
    }

    /** 上游 match.go:156 `StripASCIIAlnum`：在已归一化字符串上再剥除 ASCII 字母数字。 */
    fun stripAsciiAlnum(s: String): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (r in s) {
            if (r.code < 0x80) {
                if (r in 'a'..'z' || r in 'A'..'Z' || r in '0'..'9') continue
            }
            sb.append(r)
        }
        return sb.toString()
    }

    /** 上游空集与 Kotlin 空集语义一致，这里显式列出以便对照。 */
    private val SEPARATORS = setOf(
        '[', ']', '|', '(', ')', '-', '_', '.', ',', '、', '·', '/', '\\',
        '：', ':', '；', ';',
    )
}
