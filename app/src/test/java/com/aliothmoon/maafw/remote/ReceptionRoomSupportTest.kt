package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.ReceptionRoomSupport.ParamsOutcome
import com.aliothmoon.maafw.remote.ReceptionRoomSupport.SecondsOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会客室·交流倒计时纯逻辑测试（对齐上游 dijjiangrewards/reception_room.go）。
 *
 * 重点钉住两类「错了只会静默判错分支」的规则：
 *  1. `previousNonSpaceRune` 的冒号前缀保护：`1:0:30` 不能被解析成 `0:30`；
 *  2. 两个节流状态机的边界（首次、间隔、会话中断重置）。
 */
class ReceptionRoomSupportTest {

    private fun seconds(text: String?): Int {
        val outcome = ReceptionRoomSupport.parseCountdownSeconds(text)
        assertTrue("期望解析成功，实际 $outcome", outcome is SecondsOutcome.Ok)
        return (outcome as SecondsOutcome.Ok).seconds
    }

    private fun secondsInvalid(text: String?) {
        val outcome = ReceptionRoomSupport.parseCountdownSeconds(text)
        assertTrue("期望解析失败，实际 $outcome", outcome is SecondsOutcome.Invalid)
    }

    private fun paramsOk(raw: String?): ReceptionRoomSupport.CountdownParams {
        val outcome = ReceptionRoomSupport.parseCountdownParams(raw)
        assertTrue("期望解析成功，实际 $outcome", outcome is ParamsOutcome.Ok)
        return (outcome as ParamsOutcome.Ok).params
    }

    private fun paramsInvalid(raw: String?) {
        val outcome = ReceptionRoomSupport.parseCountdownParams(raw)
        assertTrue("期望解析失败，实际 $outcome", outcome is ParamsOutcome.Invalid)
    }

    // ───────────────────── 参数解析 ─────────────────────

    @Test
    fun `空参数走默认阈值且不提示`() {
        assertEquals(
            ReceptionRoomSupport.CountdownParams(5, false),
            paramsOk(null),
        )
        assertEquals(
            ReceptionRoomSupport.CountdownParams(5, false),
            paramsOk(""),
        )
        assertEquals(
            ReceptionRoomSupport.CountdownParams(5, false),
            paramsOk("   "),
        )
        assertEquals(
            ReceptionRoomSupport.CountdownParams(5, false),
            paramsOk("{}"),
        )
    }

    @Test
    fun `显式阈值与提示开关`() {
        assertEquals(
            ReceptionRoomSupport.CountdownParams(3, false),
            paramsOk("""{"threshold_minutes":3}"""),
        )
        assertEquals(
            ReceptionRoomSupport.CountdownParams(5, true),
            paramsOk("""{"report_waiting":true}"""),
        )
        assertEquals(
            ReceptionRoomSupport.CountdownParams(7, true),
            paramsOk("""{"threshold_minutes":7,"report_waiting":true}"""),
        )
    }

    @Test
    fun `参数非法时整节点失败`() {
        paramsInvalid("不是 json")
        paramsInvalid("[1,2,3]")
        paramsInvalid("""{"threshold_minutes":0}""")
        paramsInvalid("""{"threshold_minutes":-1}""")
        paramsInvalid("""{"threshold_minutes":"5"}""")
        paramsInvalid("""{"report_waiting":"true"}""")
    }

    // ───────────────────── 倒计时解析 ─────────────────────

    @Test
    fun `两段按分秒三段按时分秒`() {
        assertEquals(754, seconds("12:34"))
        assertEquals(0, seconds("0:00"))
        assertEquals(59, seconds("0:59"))
        assertEquals(5025, seconds("1:23:45"))
        assertEquals(24 * 60 * 60, seconds("24:00:00"))
    }

    @Test
    fun `前后有文字与全角冒号也能解析`() {
        assertEquals(754, seconds("剩余 12:34"))
        assertEquals(754, seconds("12:34 结束"))
        assertEquals(754, seconds("12：34"))
        assertEquals(754, seconds("12 : 34"))
        assertEquals(5025, seconds("倒计时 1 : 23 : 45"))
    }

    @Test
    fun `空文本或无时间值判失败`() {
        secondsInvalid(null)
        secondsInvalid("")
        secondsInvalid("   ")
        secondsInvalid("倒计时")
        secondsInvalid("12-34")
        secondsInvalid("1:2")
        secondsInvalid("12:345")
        secondsInvalid("100:30")
    }

    @Test
    fun `分秒超过59判失败`() {
        secondsInvalid("12:60")
        secondsInvalid("12:99")
        secondsInvalid("1:23:60")
        secondsInvalid("1:23:99")
    }

    /**
     * 冒号保护的核心用例：`1:0:30`（分只有一位）三段式匹配不上，
     * 正则本会退回 `0:30`，若不过滤就会把 1 小时 0 分 30 秒当成 30 秒。
     */
    @Test
    fun `冒号前缀的尾巴匹配被丢弃`() {
        secondsInvalid("1:0:30")
        secondsInvalid("12:0:30")
        secondsInvalid("剩余 1:0:30")
        secondsInvalid("123:45:67:89")
        assertNull(ReceptionRoomSupport.findCountdownMatch(":0:30"))
    }

    @Test
    fun `findCountdownMatch 返回完整匹配与捕获组`() {
        val match = ReceptionRoomSupport.findCountdownMatch("x 12:34 y")
        assertEquals(listOf("12:34", "12", "34", ""), match)

        val three = ReceptionRoomSupport.findCountdownMatch("1:23:45")
        assertEquals(listOf("1:23:45", "1", "23", "45"), three)

        assertNull(ReceptionRoomSupport.findCountdownMatch("没有倒计时"))
    }

    @Test
    fun `previousNonSpaceRune 跳过空白返回码点`() {
        assertEquals('4'.code, ReceptionRoomSupport.previousNonSpaceRune("12:34 "))
        assertEquals('c'.code, ReceptionRoomSupport.previousNonSpaceRune("abc \t\n"))
        assertEquals('余'.code, ReceptionRoomSupport.previousNonSpaceRune("剩 余"))
        assertEquals(':'.code, ReceptionRoomSupport.previousNonSpaceRune("12:"))
        assertNull(ReceptionRoomSupport.previousNonSpaceRune(""))
        assertNull(ReceptionRoomSupport.previousNonSpaceRune("   \t"))
    }

    // ───────────────────── 格式化 ─────────────────────

    @Test
    fun `formatCountdown 分秒与时分秒`() {
        assertEquals("00:00", ReceptionRoomSupport.formatCountdown(0))
        assertEquals("00:59", ReceptionRoomSupport.formatCountdown(59))
        assertEquals("01:00", ReceptionRoomSupport.formatCountdown(60))
        assertEquals("59:59", ReceptionRoomSupport.formatCountdown(3599))
        assertEquals("01:00:00", ReceptionRoomSupport.formatCountdown(3600))
        assertEquals("01:23:45", ReceptionRoomSupport.formatCountdown(5025))
        assertEquals("100:00:00", ReceptionRoomSupport.formatCountdown(360_000))
    }

    @Test
    fun `formatCountdown 负数按零`() {
        assertEquals("00:00", ReceptionRoomSupport.formatCountdown(-1))
        assertEquals("00:00", ReceptionRoomSupport.formatCountdown(-9999))
    }

    // ───────────────────── OCR 文本提取 ─────────────────────

    private fun ocr(text: String) = mapOf("text" to text, "box" to listOf(0, 0, 1, 1))

    @Test
    fun `bestOcrText 优先 best 再 filtered 再 all`() {
        val detail = mapOf(
            "best" to ocr(" 12:34 "),
            "filtered" to listOf(ocr("99:59")),
            "all" to listOf(ocr("88:58")),
        )
        assertEquals("12:34", ReceptionRoomSupport.bestOcrText(detail))
    }

    @Test
    fun `bestOcrText 回退 filtered 与 all`() {
        val fromFiltered = mapOf("filtered" to listOf(ocr("01:02")), "all" to listOf(ocr("03:04")))
        assertEquals("01:02", ReceptionRoomSupport.bestOcrText(fromFiltered))

        val fromAll = mapOf("all" to listOf(ocr("03:04")))
        assertEquals("03:04", ReceptionRoomSupport.bestOcrText(fromAll))

        // best 存在但不是 OCR 结果（无 text）：继续往 filtered 找
        val bestNotOcr = mapOf("best" to mapOf("box" to listOf(0, 0, 1, 1)), "filtered" to listOf(ocr("05:06")))
        assertEquals("05:06", ReceptionRoomSupport.bestOcrText(bestNotOcr))
    }

    @Test
    fun `bestOcrText 兼容 results 外层与意外形状兜底`() {
        val nested = mapOf("results" to mapOf("best" to ocr("07:08")))
        assertEquals("07:08", ReceptionRoomSupport.bestOcrText(nested))

        // 无 all/best/filtered 三键时，递归兜底取第一处 text
        val unexpected = mapOf("something" to mapOf("text" to " 09:10 "))
        assertEquals("09:10", ReceptionRoomSupport.bestOcrText(unexpected))
    }

    @Test
    fun `bestOcrText 无文本返回 null`() {
        assertNull(ReceptionRoomSupport.bestOcrText(null))
        assertNull(ReceptionRoomSupport.bestOcrText("不是对象"))
        assertNull(ReceptionRoomSupport.bestOcrText(mapOf("filtered" to emptyList<Any>())))
    }

    // ───────────────────── 等待提示节流 ─────────────────────

    @Test
    fun `等待提示首次放行且十秒内不再放行`() {
        val gate = ReceptionRoomSupport.WaitingReportGate()
        val t0 = 1_000_000L
        assertTrue(gate.shouldReportWaiting(t0))
        assertFalse(gate.shouldReportWaiting(t0 + 1))
        assertFalse(gate.shouldReportWaiting(t0 + 9_999))
        assertTrue(gate.shouldReportWaiting(t0 + 10_000))
        assertFalse(gate.shouldReportWaiting(t0 + 10_001))
    }

    // ───────────────────── 保活节流 ─────────────────────

    private val minute = 60_000L

    @Test
    fun `保活首次不放行二十分钟后放行`() {
        val gate = ReceptionRoomSupport.KeepAliveGate()
        val t0 = 5_000_000L
        assertFalse(gate.shouldKeepAlive(t0))
        // 每 1 分钟轮询一次，间隔都远小于 2 分钟会话阈值
        var firedAt: Long? = null
        for (m in 1..20) {
            if (gate.shouldKeepAlive(t0 + m * minute)) firedAt = m.toLong()
        }
        assertEquals("应在第 20 分钟才放行", 20L, firedAt)
    }

    @Test
    fun `保活放行后要再等二十分钟`() {
        val gate = ReceptionRoomSupport.KeepAliveGate()
        val t0 = 5_000_000L
        var now = t0
        assertFalse(gate.shouldKeepAlive(now))
        var firedAt: Long? = null
        repeat(20) {
            now += minute
            if (gate.shouldKeepAlive(now)) firedAt = now
        }
        assertEquals(t0 + 20 * minute, firedAt) // 第 20 分钟放行
        now += minute
        assertFalse(gate.shouldKeepAlive(now)) // 才过 1 分钟
    }

    @Test
    fun `保活会话中断后计时重新开始`() {
        val gate = ReceptionRoomSupport.KeepAliveGate()
        val t0 = 5_000_000L
        assertFalse(gate.shouldKeepAlive(t0))
        // 隔了 21 分钟才再判定：虽然距首次已超 20 分钟，但会话中断应重置，
        // 因此这里不放行（若没重置就会误放行）。
        assertFalse(gate.shouldKeepAlive(t0 + 21 * minute))
        // 重置后按 1 分钟轮询，正常满 20 分钟才放行
        var now = t0 + 21 * minute
        var fired = false
        for (m in 1..20) {
            now += minute
            if (gate.shouldKeepAlive(now)) fired = true
        }
        assertTrue(fired)
    }
}
