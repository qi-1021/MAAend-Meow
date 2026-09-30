package com.aliothmoon.maafw.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [DebugCliRemote] 的纯逻辑测试：回环判定必须 fail-closed，令牌必须够长且字符集受限，
 * 限速要真的锁得住。
 */
class DebugCliRemoteTest {

    // ───────────────────── 回环判定 ─────────────────────

    @Test
    fun `IPv4 回环被认出`() {
        assertTrue(debugCliIsLoopback("127.0.0.1"))
        assertTrue(debugCliIsLoopback("127.0.0.2"))
        assertTrue(debugCliIsLoopback("127.255.255.255"))
    }

    @Test
    fun `IPv4 回环前后空白被忽略`() {
        assertTrue(debugCliIsLoopback("  127.0.0.1  "))
    }

    @Test
    fun `带 zone id 的回环被认出`() {
        assertTrue(debugCliIsLoopback("127.0.0.1%wlan0"))
    }

    @Test
    fun `IPv6 回环被认出`() {
        assertTrue(debugCliIsLoopback("::1"))
        assertTrue(debugCliIsLoopback("0:0:0:0:0:0:0:1"))
        assertTrue(debugCliIsLoopback("::1%lo"))
    }

    @Test
    fun `v4-mapped 回环被认出`() {
        assertTrue(debugCliIsLoopback("::ffff:127.0.0.1"))
        assertTrue(debugCliIsLoopback("::FFFF:127.0.0.1"))
    }

    @Test
    fun `v4-mapped 非回环仍判远程`() {
        assertFalse(debugCliIsLoopback("::ffff:192.168.1.5"))
    }

    @Test
    fun `局域网与公网地址判远程`() {
        assertFalse(debugCliIsLoopback("192.168.1.2"))
        assertFalse(debugCliIsLoopback("10.0.0.1"))
        assertFalse(debugCliIsLoopback("8.8.8.8"))
        assertFalse(debugCliIsLoopback("2001:db8::1"))
    }

    @Test
    fun `null 与空白 fail-closed 判远程`() {
        assertFalse(debugCliIsLoopback(null))
        assertFalse(debugCliIsLoopback(""))
        assertFalse(debugCliIsLoopback("   "))
    }

    @Test
    fun `主机名与非法段 fail-closed 判远程`() {
        assertFalse(debugCliIsLoopback("localhost"))
        assertFalse(debugCliIsLoopback("127.0"))
        assertFalse(debugCliIsLoopback("127.0.0.1.5"))
        assertFalse(debugCliIsLoopback("127.0.0.999"))
        assertFalse(debugCliIsLoopback("127.a.b.c"))
    }

    // ───────────────────── 令牌 ─────────────────────

    @Test
    fun `默认令牌长度是 32`() {
        assertEquals(DEBUG_CLI_TOKEN_LENGTH, debugCliGenerateToken(Random(1)).length)
    }

    @Test
    fun `指定长度生效`() {
        assertEquals(8, debugCliGenerateToken(Random(1), 8).length)
    }

    @Test
    fun `令牌字符集受限`() {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val token = debugCliGenerateToken(Random(42), 256)
        assertTrue(token.all { it in allowed })
    }

    @Test
    fun `同 seed 生成确定`() {
        assertEquals(debugCliGenerateToken(Random(7)), debugCliGenerateToken(Random(7)))
    }

    @Test
    fun `不同 seed 通常不同`() {
        assertFalse(debugCliGenerateToken(Random(1)) == debugCliGenerateToken(Random(2)))
    }

    @Test
    fun `长度为 0 报错`() {
        assertThrows(IllegalArgumentException::class.java) { debugCliGenerateToken(Random(1), 0) }
    }

    @Test
    fun `生产令牌够长`() {
        assertTrue(debugCliNewToken().length >= DEBUG_CLI_TOKEN_LENGTH)
    }

    // ───────────────────── 令牌比较 ─────────────────────

    @Test
    fun `相同令牌匹配`() {
        assertTrue(debugCliTokensMatch("abcDEF123", "abcDEF123"))
    }

    @Test
    fun `不同令牌不匹配`() {
        assertFalse(debugCliTokensMatch("abcDEF123", "abcDEF124"))
        assertFalse(debugCliTokensMatch("abc", "abcd"))
    }

    @Test
    fun `空期望或空候选都不匹配`() {
        assertFalse(debugCliTokensMatch("", ""))
        assertFalse(debugCliTokensMatch(null, "x"))
        assertFalse(debugCliTokensMatch("x", null))
        assertFalse(debugCliTokensMatch("x", ""))
    }

    // ───────────────────── 鉴权限速 ─────────────────────

    @Test
    fun `未达上限不锁`() {
        val throttle = DebugCliAuthThrottle(maxAttempts = 3, lockoutMillis = 1000)
        assertFalse(throttle.onFailure(0))
        assertFalse(throttle.onFailure(0))
        assertFalse(throttle.isLocked(0))
    }

    @Test
    fun `达到上限锁定`() {
        val throttle = DebugCliAuthThrottle(maxAttempts = 3, lockoutMillis = 1000)
        throttle.onFailure(0)
        throttle.onFailure(0)
        assertTrue(throttle.onFailure(0))
        assertTrue(throttle.isLocked(0))
        assertEquals(1000, throttle.lockRemainingMillis(0))
    }

    @Test
    fun `锁定期内继续失败仍锁`() {
        val throttle = DebugCliAuthThrottle(maxAttempts = 1, lockoutMillis = 1000)
        assertTrue(throttle.onFailure(0))
        assertTrue(throttle.onFailure(500))
        assertTrue(throttle.isLocked(999))
    }

    @Test
    fun `锁定到期自动解除`() {
        val throttle = DebugCliAuthThrottle(maxAttempts = 2, lockoutMillis = 1000)
        throttle.onFailure(0)
        assertTrue(throttle.onFailure(0))
        assertFalse(throttle.isLocked(1000))
        assertEquals(0, throttle.lockRemainingMillis(1000))
        // 到期后计数已清零，再失败一次不该立刻又锁（上限是 2）
        assertFalse(throttle.onFailure(1000))
    }

    @Test
    fun `成功后清零`() {
        val throttle = DebugCliAuthThrottle(maxAttempts = 3, lockoutMillis = 1000)
        throttle.onFailure(0)
        throttle.onFailure(0)
        throttle.onSuccess()
        assertFalse(throttle.onFailure(0))
        assertFalse(throttle.onFailure(0))
        assertFalse(throttle.isLocked(0))
    }

    @Test
    fun `非法上限报错`() {
        assertThrows(IllegalArgumentException::class.java) { DebugCliAuthThrottle(maxAttempts = 0) }
    }
}
