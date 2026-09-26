package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * 售卖弹性物资（AutoSell）与表达式识别（ExpressionRecognition）的纯逻辑支持。
 * 行为对齐上游 go-service：
 * - `autosell/autosell.go`（关键词清单、售价决策）
 * - `pkg/ocrnum`（OCR 数字解析，k/m/b、万/萬/만、亿/億/억）
 * - `pkg/recogtarget`（按节点定义的 And/box_index 选取子结果）
 * - `pkg/boolexpr`（`{节点}` 占位布尔表达式求值）
 * - `common/expressionrecognition`（表达式识别流程）
 *
 * 注意：`ScreenshotStableRecognition` 上游只在 Win32-Front 跑，移动端恒 false，
 * 不要把它和这里搞混。
 */

/** 与上游 `moderate/large/massivePriceKeywordGroups` 展开后等价的扁平关键词表 */
object AutoSellKeywords {
    val moderate: List<String> = listOf(
        "锚点", "錨點", "锚點", "Ankhorilling", "アンカー", "앵커",
        "悬空", "懸空", "Musbeast", "浮かぶ", "공중",
        "巫术", "巫術", "Witchcraft", "주술",
        "天使", "Aggeloi", "アンゲロス", "아겔로스",
        "岳研", "Eureka", "악연",
        "冬虫", "冬蟲", "Nymphsprout", "동충하순",
        "武陵", "Wuling", "무릉",
        "武侠", "武俠", "Wuxia", "무협",
    )
    val large: List<String> = listOf(
        "谷地水", "Hydroculture", "협곡 수경",
        "团结", "團結", "Unity", "ユナイト", "단결",
        "塞什", "Seš'qamam", "セシュカ", "세쉬카",
        "星体", "星體", "Astarron", "アスタロン", "별체",
        "天师龙", "天師龍", "Chubby Lung Tianshi", "천사",
        "天师桩", "天師樁",
        "息壤净", "息壤淨", "Xiranite Filter", "息壌浄", "식양 정수",
        "息壤色", "Xiran-Hue", "息壌色", "식양색을",
        "息壤桥", "息壤橋", "息壌橋", "Xiranite Bridge", "식양 다리",
        "清波", "Qingbo", "청파",
        "飞天", "飛天", "Aerial", "空飛ぶ", "비행",
        "选剑", "選劍", "選剣", "Swordmancer", "선검",
        "界石",
        "浮空艇",
    )
    val massive: List<String> = listOf(
        "源石", "Originium", "작은 오리지늄",
        "警戒", "Vigilant", "경계자",
        "硬脑", "硬頭殼", "Hard Noggin", "石頭", "단단한",
        "边角", "碎料", "Scrap", "端材", "재활용",
    )

    fun firstContainedKeyword(s: String, subs: List<String>): String {
        for (sub in subs) {
            if (sub.isNotEmpty() && s.contains(sub)) return sub
        }
        return ""
    }
}

/** 对齐上游 `pkg/ocrnum`：解析 "1.8k"、"12m"、"1.38万" 等 OCR 数字文本 */
object OcrNum {
    private val numericPattern = Regex("""(?i)[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)\s*(?:[a-z]+|万|萬|亿|億|만|억)?""")
    private val asciiLetterSuffix = Regex("""[A-Za-z]+$""")
    private val multipliers = listOf(
        "億" to 1e8, "억" to 1e8, "亿" to 1e8,
        "萬" to 1e4, "만" to 1e4, "万" to 1e4,
        "K" to 1e3, "k" to 1e3,
        "M" to 1e6, "m" to 1e6,
        "B" to 1e9, "b" to 1e9,
    )

    fun parse(text: String): Int {
        val cleaned = text.trim()
        require(cleaned.isNotEmpty()) { "ocr text is empty" }
        val match = numericPattern.find(cleaned)
            ?: throw IllegalArgumentException("ocr text \"$cleaned\" contains no numeric value")
        val (numberText, multiplier) = normalizeToken(match.value)
        val value = numberText.toDoubleOrNull()
            ?: throw IllegalArgumentException("ocr numeric token \"$cleaned\" has no numeric part")
        val scaled = Math.round(value * multiplier).toDouble()
        return scaled.roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun normalizeToken(token: String): Pair<String, Double> {
        var normalized = token.trim()
        require(normalized.isNotEmpty()) { "ocr numeric token is empty" }
        var multiplier = 1.0
        for ((unit, mult) in multipliers) {
            if (normalized.endsWith(unit)) {
                normalized = normalized.dropLast(unit.length).trim()
                multiplier = mult
                break
            }
        }
        require(asciiLetterSuffix.find(normalized) == null) { "unsupported ocr numeric suffix in \"$token\"" }
        require(normalized.isNotEmpty()) { "ocr numeric token \"$token\" has no numeric part" }
        normalized = normalized.replace(" ", "")
        normalized = if (normalized.contains(".")) {
            normalized.replace(",", "")
        } else {
            val commaCount = normalized.count { it == ',' }
            if (commaCount == 1) {
                val parts = normalized.split(",")
                if (parts.size == 2 && parts[1].length != 3) parts[0] + "." + parts[1]
                else normalized.replace(",", "")
            } else {
                normalized.replace(",", "")
            }
        }
        return normalized to multiplier
    }
}

/**
 * 对齐上游 `pkg/boolexpr` 的子集：`{节点}` 占位 + 四则 + 比较 + 逻辑。
 * 上游用 Go AST 解析；这里用等价的递归下降，保证 `{A}-{B}>=300`、`{X} >= 5000`、
 * `30000000 < {Y}` 这类资产里的写法行为一致。
 */
object BoolExpr {
    private val placeholderPattern = Regex("""\{([^{}]+)\}""")

    fun evaluate(expression: String, resolve: (String) -> Int): Boolean {
        val resolved = StringBuilder()
        val values = mutableMapOf<String, Int>()
        var last = 0
        for (m in placeholderPattern.findAll(expression)) {
            resolved.append(expression, last, m.range.first)
            val name = m.groupValues[1].trim()
            require(name.isNotEmpty()) { "placeholder must not be empty" }
            val value = resolve(name)
            values[name] = value
            resolved.append(value)
            last = m.range.last + 1
        }
        resolved.append(expression, last, expression.length)
        val parser = Parser(resolved.toString())
        val result = parser.parseOr()
        parser.expectEnd()
        return result != 0L
    }

    private class Parser(private val s: String) {
        private var pos = 0
        private fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        private fun peek(): Char? { skipWs(); return if (pos < s.length) s[pos] else null }
        private fun eat(c: Char): Boolean { if (peek() == c) { pos++; return true }; return false }
        private fun eatOp(op: String): Boolean { skipWs(); if (s.startsWith(op, pos)) { pos += op.length; return true }; return false }

        fun parseOr(): Long {
            var v = parseAnd()
            while (true) {
                if (eatOp("||")) { val r = parseAnd(); v = if (v != 0L || r != 0L) 1 else 0 }
                else return v
            }
        }
        private fun parseAnd(): Long {
            var v = parseComparison()
            while (true) {
                if (eatOp("&&")) { val r = parseComparison(); v = if (v != 0L && r != 0L) 1 else 0 }
                else return v
            }
        }
        private fun parseComparison(): Long {
            val l = parseAdd()
            val c = peek() ?: return l
            if (c != '>' && c != '<' && c != '=' && c != '!') return l
            val op = when {
                eatOp(">=") -> ">="; eatOp("<=") -> "<="
                eatOp("==") -> "=="; eatOp("!=") -> "!="
                eat('>') -> ">"; eat('<') -> "<"
                eat('=') -> "=="; eat('!') -> throw IllegalArgumentException("unexpected '!' in \"$s\"")
                else -> return l
            }
            val r = parseAdd()
            return if (when (op) {
                    ">" -> l > r; ">=" -> l >= r; "<" -> l < r
                    "<=" -> l <= r; "==" -> l == r; else -> l != r
                }) 1 else 0
        }
        private fun parseAdd(): Long {
            var v = parseMul()
            while (true) {
                if (eat('+')) v += parseMul()
                else if (eat('-')) v -= parseMul()
                else return v
            }
        }
        private fun parseMul(): Long {
            var v = parseUnary()
            while (true) {
                if (eat('*')) v *= parseUnary()
                else if (eat('/')) { val r = parseUnary(); require(r != 0L) { "division by zero in \"$s\"" }; v /= r }
                else if (eat('%')) { val r = parseUnary(); require(r != 0L) { "modulo by zero in \"$s\"" }; v %= r }
                else return v
            }
        }
        private fun parseUnary(): Long {
            if (eat('!')) return if (parseUnary() == 0L) 1 else 0
            if (eat('-')) return -parseUnary()
            if (eat('+')) return parseUnary()
            return parsePrimary()
        }
        private fun parsePrimary(): Long {
            val c = peek() ?: throw IllegalArgumentException("unexpected end of \"$s\"")
            if (c == '(') { pos++; val v = parseOr(); require(eat(')')) { "missing ')' in \"$s\"" }; return v }
            if (c.isDigit()) {
                val start = pos
                while (pos < s.length && s[pos].isDigit()) pos++
                return s.substring(start, pos).toLong()
            }
            throw IllegalArgumentException("unexpected '$c' in \"$s\"")
        }
        fun expectEnd() { require(peek() == null) { "unexpected trailing input in \"$s\"" } }
    }
}

/**
 * 对齐上游 `pkg/recogtarget` 的子集：在 C API 返回的 detail JSON 里，
 * 按节点定义的 And/box_index 选取 OCR 文本与包围盒。
 */
object RecoDetail {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class NodeShape(val type: String, val boxIndex: Int)

    fun parseNodeShape(nodeJson: String?): NodeShape? {
        if (nodeJson.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(nodeJson).jsonObject
            val rec = obj["recognition"]
            var type = ""
            var boxIndex = 0
            var allOf: JsonArray? = null
            when (rec) {
                is JsonPrimitive -> type = rec.contentOrNull.orEmpty()
                is JsonObject -> {
                    type = rec["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val param = rec["param"]?.jsonObject
                    if (param != null) {
                        allOf = param["all_of"]?.jsonArray
                        boxIndex = param["box_index"]?.jsonPrimitive?.intOrNull ?: 0
                    }
                }
                else -> {}
            }
            if (type.isEmpty()) {
                allOf = obj["all_of"]?.jsonArray
                if (allOf != null) {
                    type = "And"
                    boxIndex = obj["box_index"]?.jsonPrimitive?.intOrNull ?: 0
                }
            }
            if (type.isEmpty()) return null
            NodeShape(type, boxIndex)
        } catch (_: Exception) {
            null
        }
    }

    /** 从 RunRecognition 的 detail JSON 里收集全部 OCR 文本（上游 CombinedResult 的扁平版） */
    fun collectOcrTexts(detailJson: String?): List<String> {
        if (detailJson.isNullOrBlank()) return emptyList()
        val out = mutableListOf<String>()
        try {
            collectTexts(json.parseToJsonElement(detailJson), out)
        } catch (_: Exception) {
        }
        return out.filter { it.isNotBlank() }.distinct()
    }

    private fun collectTexts(el: kotlinx.serialization.json.JsonElement, out: MutableList<String>) {
        when (el) {
            is JsonObject -> {
                el["text"]?.let {
                    val t = it.jsonPrimitive.contentOrNull
                    if (!t.isNullOrBlank()) out += t
                }
                for ((_, v) in el) collectTexts(v, out)
            }
            is JsonArray -> for (v in el) collectTexts(v, out)
            else -> {}
        }
    }

    /** 按节点形状从 detail 里选取目标子项的包围盒 [x,y,w,h]，取不到返回 null */
    fun selectBox(detailJson: String?, nodeJson: String?): IntArray? {
        if (detailJson.isNullOrBlank()) return null
        return try {
            val root = json.parseToJsonElement(detailJson).jsonObject
            val target = pickChild(root, nodeJson) ?: root
            readBox(target) ?: readBox(root)
        } catch (_: Exception) {
            null
        }
    }

    private fun pickChild(root: JsonObject, nodeJson: String?): JsonObject? {
        val shape = parseNodeShape(nodeJson) ?: return null
        if (shape.type != "And") return null
        val all = root["all"]?.jsonArray ?: root["filtered"]?.jsonArray ?: return null
        if (all.isEmpty()) return null
        val idx = shape.boxIndex.coerceIn(0, all.size - 1)
        return all[idx].jsonObject
    }

    private fun readBox(obj: JsonObject): IntArray? {
        val box = obj["box"]?.jsonArray ?: return null
        if (box.size < 4) return null
        val nums = box.mapNotNull { it.jsonPrimitive.intOrNull }
        if (nums.size < 4) return null
        return intArrayOf(nums[0], nums[1], nums[2], nums[3])
    }
}
