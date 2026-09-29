package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pkg/boolexpr` 完整移植的纯逻辑测试。
 *
 * 重点：
 *  1. 运算优先级与 Go 一致（`*` 高于 `+`，`&&` 高于 `||`）；
 *  2. 括号、比较、一元、布尔逻辑；
 *  3. 占位符替换返回替换后表达式与解析值；
 *  4. **非法表达式 / 不支持语法必须报错，而不是静默 false**。
 */
class BoolExprTest {

    private fun eval(expression: String, values: Map<String, Long> = emptyMap()): Boolean =
        BoolExpr.evaluate(expression) { values[it] ?: 0L }

    private fun raw(expression: String): Any = BoolExpr.evaluateRaw(expression)

    @Test
    fun `算术优先级先乘除后加减`() {
        assertEquals(7L, raw("1+2*3"))
        assertEquals(9L, raw("(1+2)*3"))
        assertEquals(1L, raw("7%3"))
        assertEquals(2L, raw("7/3"))
        assertTrue(eval("1+2*3==7"))
    }

    @Test
    fun `逻辑优先级 and 高于 or`() {
        // true || (false && true) = true
        assertTrue(eval("1==1 || 1==2 && 1==1"))
        // (true || false) && false = false
        assertFalse(eval("(1==1 || 1==2) && 1==2"))
    }

    @Test
    fun `比较与括号`() {
        assertTrue(eval("(5>3)==(2<4)"))
        assertTrue(eval("1<=1 && 2>=2 && 1!=2 && 1==1"))
        assertFalse(eval("3<3"))
        assertFalse(eval("3>3"))
        assertTrue(eval("3<=3"))
    }

    @Test
    fun `一元运算符`() {
        assertTrue(eval("-5 < 0"))
        assertTrue(eval("+-5 == -5"))
        assertFalse(eval("!(1<2)"))
        assertTrue(eval("!!(1<2)"))
        assertTrue(eval("!(1==2)"))
    }

    @Test
    fun `布尔相等与逻辑`() {
        assertTrue(eval("(1<2) == (3<4)"))
        assertTrue(eval("(1<2) != (3>4)"))
        assertTrue(eval("(1<2) && !(3>4)"))
        assertFalse(eval("(1<2) && (3>4)"))
        assertTrue(eval("(1>2) || (3<4)"))
    }

    @Test
    fun `占位符替换并返回解析值与替换后表达式`() {
        val resolved = BoolExpr.resolvePlaceholders("{a}-{b}>=300") { name ->
            when (name) {
                "a" -> 1000L
                "b" -> 600L
                else -> 0L
            }
        }
        assertEquals("1000-600>=300", resolved.expression)
        assertEquals(mapOf("a" to 1000L, "b" to 600L), resolved.values)
        assertTrue(eval("{a}-{b}>=300", mapOf("a" to 1000L, "b" to 600L)))
    }

    @Test
    fun `占位符替换后仍保持运算符优先级`() {
        // (1*10000 + 6*1000) >= 15000 -> true
        assertTrue(eval("({h}*10000+{m}*1000)>={t}", mapOf("h" to 1L, "m" to 6L, "t" to 15000L)))
    }

    @Test
    fun `负数占位符值参与一元与算术`() {
        assertTrue(eval("{d} <= -50", mapOf("d" to -60L)))
        assertEquals(-110L, raw("-50-60"))
    }

    @Test
    fun `空白与尾随空格可接受`() {
        assertTrue(eval("  1   ==   1  "))
    }

    @Test
    fun `evaluateRaw 保留类型`() {
        assertEquals(3L, raw("1+2"))
        assertEquals(true, raw("1<2"))
        assertEquals(false, raw("1>2"))
    }

    @Test
    fun `整数溢出字面量钳到 Long 最大值`() {
        assertEquals(Long.MAX_VALUE, BoolExpr.parseIntLiteral("999999999999999999999999999"))
    }

    @Test
    fun `非法表达式必须报错而不是 false`() {
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1+") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("(1+2") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 2") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("foo") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1.5") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("0x10") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1_000") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("\"s\"") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 = 1") }
    }

    @Test
    fun `不支持但可解析的位运算求值时报错`() {
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 & 1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 | 1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 ^ 1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 << 1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 >> 1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 &^ 1") }
    }

    @Test
    fun `类型不匹配必须报错`() {
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("(1<2) && 1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 || (1<2)") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("(1<2) + 1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 < (2<3)") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 == (1<2)") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("!(1)") }
    }

    @Test
    fun `除零与取模零报错`() {
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1/0") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1%0") }
    }

    @Test
    fun `evaluate 要求最终结果是 bool`() {
        assertThrows(BoolExpr.BoolExprException::class.java) { eval("1+1") }
        assertThrows(BoolExpr.BoolExprException::class.java) { eval("5") }
    }

    @Test
    fun `占位符非法时解析失败`() {
        // 上游 PlaceholderPattern 要求 `{` 与 `}` 之间至少一个字符：`{}` 不是占位符，
        // 替换阶段原样保留，随后在求值阶段报错。
        val untouched = BoolExpr.resolvePlaceholders("{}") { 1L }
        assertEquals("{}", untouched.expression)
        assertTrue(untouched.values.isEmpty())
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("{}") }
        assertThrows(BoolExpr.BoolExprException::class.java) {
            BoolExpr.resolvePlaceholders("{bad}") { name -> throw IllegalStateException("no $name") }
        }
    }

    @Test
    fun `布尔字面量不被支持必须报错`() {
        // 上游用 Go AST：`true`/`false` 是标识符，求值阶段报 unsupported expression type。
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("true") }
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("false") }
    }

    @Test
    fun `双竖线不会被单竖线吃掉`() {
        // 词法化后 `|` 与 `||` 必须区分；`||` 正常求值，`|` 求值阶段报不支持
        assertTrue(eval("(1>2) || (3<4)"))
        assertThrows(BoolExpr.BoolExprException::class.java) { raw("1 | 1") }
    }
}
