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
 * 对齐上游 `pkg/boolexpr` 的**完整移植**。
 *
 * 上游用 Go 的 `go/parser.ParseExpr` 解析、再对 AST 求值；这里用等价的递归下降，
 * 覆盖上游实际支持的语法，并对「上游会解析但求值阶段拒绝」或「本移植未实现」的
 * 语法**明确抛错**，绝不静默返回 false。
 *
 *  ✅ 已支持：十进制整数字面量；括号；一元 `+ - !`；二元 `+ - * / %`、
 *     `== != < <= > >=`、`&& ||`；占位符 `{name}` 替换（值域 Long，与 64 位设备上
 *     Go 的平台 int 对齐）。运算符优先级与 Go 一致（见下方各 parse* 层级）。
 *  ❌ 明确不支持（抛 [BoolExpr.BoolExprException]）：位运算 `& | ^ << >> &^`；
 *     非十进制字面量（`0x`/`0o`/`0b`/下划线/小数）与字符串字面量；标识符、函数调用、
 *     索引、选择器、切片等一切非上述结构。上游对位运算同样是解析成功、求值报
 *     `unsupported binary operator`；十六进制等在 `strconv.Atoi` 阶段失败。
 *
 * 类型规则与上游一致：算术与大小比较要求两侧都是 int；`== !=` 要求两侧同型
 * （int 或 bool）；`&& ||` 与一元 `!` 要求 bool。
 * `ExpressionRecognition` / `ItemQuantitySatisfied` 都要求最终结果必须是 bool。
 */
object BoolExpr {
    /** 上游 `PlaceholderPattern`：`\{([^{}]+)\}`。 */
    val PLACEHOLDER_PATTERN = Regex("""\{([^{}]+)\}""")

    /** 表达式非法时抛出；调用方按「不命中」处理，而不是当成 false 静默成功。 */
    class BoolExprException(message: String) : IllegalArgumentException(message)

    /** [resolvePlaceholders] 的结果：替换后的数字表达式 + 占位符→值。 */
    data class Resolved(val expression: String, val values: Map<String, Long>)

    /**
     * 上游 `ResolvePlaceholders`：把表达式里每个 `{name}` 用 [resolve] 的值替换，
     * 返回替换后的表达式与 name→value 映射。任一占位符解析失败即整体失败。
     */
    fun resolvePlaceholders(expression: String, resolve: (String) -> Long): Resolved {
        val values = LinkedHashMap<String, Long>()
        val out = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER_PATTERN.findAll(expression)) {
            out.append(expression, last, m.range.first)
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) throw BoolExprException("placeholder must not be empty")
            val value = try {
                resolve(name)
            } catch (e: BoolExprException) {
                throw e
            } catch (e: Exception) {
                throw BoolExprException("$name: ${e.message ?: e.toString()}")
            }
            values[name] = value
            out.append(value)
            last = m.range.last + 1
        }
        out.append(expression, last, expression.length)
        return Resolved(out.toString(), values)
    }

    /**
     * 上游 `Evaluate`：解析并求值，结果是任意值（Long / Boolean）。调用方若需要
     * 识别命中，必须自行断言结果是 Boolean。非法表达式抛 [BoolExprException]。
     */
    fun evaluateRaw(expression: String): Any {
        val parser = Parser(tokenize(expression), expression)
        return parser.parse()
    }

    /**
     * `ExpressionRecognition` / `ItemQuantitySatisfied` 的用法：先替换占位符，
     * 再把结果断言成 bool。
     */
    fun evaluate(expression: String, resolve: (String) -> Long): Boolean {
        val resolved = resolvePlaceholders(expression, resolve)
        val result = evaluateRaw(resolved.expression)
        return result as? Boolean
            ?: throw BoolExprException("expression result must be boolean, got ${boolExprTypeName(result)}")
    }

    /**
     * 上游 `ParseIntLiteral`：十进制整数字面量；超出 Long 范围时钳到 Long.MAX_VALUE
     * （上游是钳到平台 int 的 IntMax）。非十进制/非法字面量明确失败。
     */
    fun parseIntLiteral(raw: String): Long {
        if (raw.isEmpty() || raw.any { it !in '0'..'9' }) {
            throw BoolExprException("unsupported integer literal \"$raw\"")
        }
        return raw.toLongOrNull() ?: Long.MAX_VALUE
    }

    private enum class Tok {
        INT, PLUS, MINUS, STAR, SLASH, PERCENT,
        LT, LE, GT, GE, EQ, NEQ,
        AND, OR, NOT, LPAREN, RPAREN,
        SHL, SHR, AMP, PIPE, CARET, ANDNOT, EOF,
    }

    private class Token(val type: Tok, val text: String, val intValue: Long = 0L)

    /**
     * 先词法分析再递归下降，避免 `|` 吃掉 `||`、`&` 吃掉 `&&` 这类前缀歧义。
     * 未知字符（标识符、字符串、非法运算符）一律明确报错。
     */
    private fun tokenize(src: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() -> {
                    val start = i
                    i++
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.')) i++
                    val raw = src.substring(start, i)
                    out += Token(Tok.INT, raw, BoolExpr.parseIntLiteral(raw))
                }
                c == '+' -> { out += Token(Tok.PLUS, "+"); i++ }
                c == '-' -> { out += Token(Tok.MINUS, "-"); i++ }
                c == '*' -> { out += Token(Tok.STAR, "*"); i++ }
                c == '/' -> { out += Token(Tok.SLASH, "/"); i++ }
                c == '%' -> { out += Token(Tok.PERCENT, "%"); i++ }
                c == '(' -> { out += Token(Tok.LPAREN, "("); i++ }
                c == ')' -> { out += Token(Tok.RPAREN, ")"); i++ }
                c == '^' -> { out += Token(Tok.CARET, "^"); i++ }
                c == '&' -> when {
                    src.startsWith("&&", i) -> { out += Token(Tok.AND, "&&"); i += 2 }
                    src.startsWith("&^", i) -> { out += Token(Tok.ANDNOT, "&^"); i += 2 }
                    else -> { out += Token(Tok.AMP, "&"); i++ }
                }
                c == '|' -> when {
                    src.startsWith("||", i) -> { out += Token(Tok.OR, "||"); i += 2 }
                    else -> { out += Token(Tok.PIPE, "|"); i++ }
                }
                c == '<' -> when {
                    src.startsWith("<<", i) -> { out += Token(Tok.SHL, "<<"); i += 2 }
                    src.startsWith("<=", i) -> { out += Token(Tok.LE, "<="); i += 2 }
                    else -> { out += Token(Tok.LT, "<"); i++ }
                }
                c == '>' -> when {
                    src.startsWith(">>", i) -> { out += Token(Tok.SHR, ">>"); i += 2 }
                    src.startsWith(">=", i) -> { out += Token(Tok.GE, ">="); i += 2 }
                    else -> { out += Token(Tok.GT, ">"); i++ }
                }
                c == '=' -> when {
                    src.startsWith("==", i) -> { out += Token(Tok.EQ, "=="); i += 2 }
                    else -> throw BoolExprException("unexpected '=' in \"$src\"")
                }
                c == '!' -> when {
                    src.startsWith("!=", i) -> { out += Token(Tok.NEQ, "!="); i += 2 }
                    else -> { out += Token(Tok.NOT, "!"); i++ }
                }
                else -> throw BoolExprException("unexpected '$c' in \"$src\"")
            }
        }
        out += Token(Tok.EOF, "")
        return out
    }

    /**
     * 递归下降求值。优先级自低到高（与 Go 的二元运算符优先级一致）：
     *   `||` < `&&` < `== != < <= > >=` < `+ - | ^` < `* / % << >> & &^` < 一元。
     * 位运算层级能解析出来，但在求值阶段抛「unsupported binary operator」，与上游一致。
     */
    private class Parser(private val tokens: List<Token>, private val src: String) {
        private var i = 0

        fun parse(): Any {
            val value = parseOr()
            if (peek().type != Tok.EOF) {
                throw BoolExprException("unexpected trailing input '${peek().text}' in \"$src\"")
            }
            return value
        }

        private fun peek(): Token = tokens[i]
        private fun advance(): Token = tokens[i++]
        private fun match(type: Tok): Boolean {
            if (peek().type == type) { i++; return true }
            return false
        }

        private fun parseOr(): Any {
            var left = parseAnd()
            while (match(Tok.OR)) left = evalBinary(left, parseAnd(), "||")
            return left
        }

        private fun parseAnd(): Any {
            var left = parseComparison()
            while (match(Tok.AND)) left = evalBinary(left, parseComparison(), "&&")
            return left
        }

        private fun parseComparison(): Any {
            var left = parseAdditive()
            while (true) {
                left = when {
                    match(Tok.EQ) -> evalBinary(left, parseAdditive(), "==")
                    match(Tok.NEQ) -> evalBinary(left, parseAdditive(), "!=")
                    match(Tok.LE) -> evalBinary(left, parseAdditive(), "<=")
                    match(Tok.GE) -> evalBinary(left, parseAdditive(), ">=")
                    match(Tok.LT) -> evalBinary(left, parseAdditive(), "<")
                    match(Tok.GT) -> evalBinary(left, parseAdditive(), ">")
                    else -> return left
                }
            }
        }

        private fun parseAdditive(): Any {
            var left = parseMultiplicative()
            while (true) {
                left = when {
                    match(Tok.PLUS) -> evalBinary(left, parseMultiplicative(), "+")
                    match(Tok.MINUS) -> evalBinary(left, parseMultiplicative(), "-")
                    match(Tok.PIPE) -> evalBinary(left, parseMultiplicative(), "|")
                    match(Tok.CARET) -> evalBinary(left, parseMultiplicative(), "^")
                    else -> return left
                }
            }
        }

        private fun parseMultiplicative(): Any {
            var left = parseUnary()
            while (true) {
                left = when {
                    match(Tok.STAR) -> evalBinary(left, parseUnary(), "*")
                    match(Tok.SLASH) -> evalBinary(left, parseUnary(), "/")
                    match(Tok.PERCENT) -> evalBinary(left, parseUnary(), "%")
                    match(Tok.SHL) -> evalBinary(left, parseUnary(), "<<")
                    match(Tok.SHR) -> evalBinary(left, parseUnary(), ">>")
                    match(Tok.AMP) -> evalBinary(left, parseUnary(), "&")
                    match(Tok.ANDNOT) -> evalBinary(left, parseUnary(), "&^")
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Any {
            if (match(Tok.NOT)) return !requireBool(parseUnary(), "!")
            if (match(Tok.PLUS)) return requireInt(parseUnary(), "+")
            if (match(Tok.MINUS)) return -requireInt(parseUnary(), "-")
            return parsePrimary()
        }

        private fun parsePrimary(): Any {
            val token = peek()
            return when (token.type) {
                Tok.INT -> { advance(); token.intValue }
                Tok.LPAREN -> {
                    advance()
                    val value = parseOr()
                    if (!match(Tok.RPAREN)) throw BoolExprException("missing ')' in \"$src\"")
                    value
                }
                Tok.EOF -> throw BoolExprException("unexpected end of expression \"$src\"")
                else -> throw BoolExprException("unexpected '${token.text}' in \"$src\"")
            }
        }

        private fun requireInt(value: Any, op: String): Long =
            value as? Long
                ?: throw BoolExprException("operator $op expects int operands, got ${boolExprTypeName(value)}")

        private fun requireBool(value: Any, op: String): Boolean =
            value as? Boolean
                ?: throw BoolExprException("operator $op expects bool operands, got ${boolExprTypeName(value)}")

        private fun evalBinary(left: Any, right: Any, op: String): Any = when (op) {
            "+" -> requireInt(left, op) + requireInt(right, op)
            "-" -> requireInt(left, op) - requireInt(right, op)
            "*" -> requireInt(left, op) * requireInt(right, op)
            "/" -> {
                val divisor = requireInt(right, op)
                if (divisor == 0L) throw BoolExprException("division by zero")
                requireInt(left, op) / divisor
            }
            "%" -> {
                val divisor = requireInt(right, op)
                if (divisor == 0L) throw BoolExprException("division by zero")
                requireInt(left, op) % divisor
            }
            "<" -> requireInt(left, op) < requireInt(right, op)
            "<=" -> requireInt(left, op) <= requireInt(right, op)
            ">" -> requireInt(left, op) > requireInt(right, op)
            ">=" -> requireInt(left, op) >= requireInt(right, op)
            "==" -> valuesEqual(left, right, op)
            "!=" -> !valuesEqual(left, right, op)
            "&&" -> requireBool(left, op) && requireBool(right, op)
            "||" -> requireBool(left, op) || requireBool(right, op)
            else -> throw BoolExprException("unsupported binary operator $op")
        }

        private fun valuesEqual(left: Any, right: Any, op: String): Boolean = when (left) {
            is Long -> {
                if (right !is Long) {
                    throw BoolExprException(
                        "operator $op expects same-type operands, got int and ${boolExprTypeName(right)}",
                    )
                }
                left == right
            }
            is Boolean -> {
                if (right !is Boolean) {
                    throw BoolExprException(
                        "operator $op expects same-type operands, got bool and ${boolExprTypeName(right)}",
                    )
                }
                left == right
            }
            else -> throw BoolExprException("unsupported equality operand type ${boolExprTypeName(left)}")
        }
    }
}

private fun boolExprTypeName(value: Any): String = when (value) {
    is Long -> "int"
    is Boolean -> "bool"
    else -> value::class.simpleName ?: "unknown"
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
