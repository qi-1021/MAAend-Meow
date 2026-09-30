package com.aliothmoon.maafw.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DebugCliRelay] 纯逻辑测试：地址规格化必须挡掉非 http(s) scheme，桥响应解析要能扛住
 * 空体 / 半条命令，退避要指数增长且封顶。
 */
class DebugCliRelayTest {

    // ───────────────────── 地址规格化 ─────────────────────

    @Test
    fun `默认地址是 https`() {
        assertEquals("https://maaendset.qiisme1021.space", DEBUG_RELAY_DEFAULT_URL)
    }

    @Test
    fun `https 去尾斜杠`() {
        assertEquals("https://a.example.com", normalizeRelayUrl("https://a.example.com/"))
        assertEquals("https://a.example.com/x", normalizeRelayUrl("  https://a.example.com/x///  "))
    }

    @Test
    fun `没写 scheme 补 https`() {
        assertEquals("https://a.example.com", normalizeRelayUrl("a.example.com"))
        assertEquals("https://127.0.0.1:7788", normalizeRelayUrl("127.0.0.1:7788"))
    }

    @Test
    fun `保留 http`() {
        assertEquals("http://127.0.0.1:7788", normalizeRelayUrl("http://127.0.0.1:7788"))
        assertEquals("http://a.b", normalizeRelayUrl("HTTP://a.b/"))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(normalizeRelayUrl(null))
        assertNull(normalizeRelayUrl(""))
        assertNull(normalizeRelayUrl("   "))
        assertNull(normalizeRelayUrl("ftp://a.example.com"))
        assertNull(normalizeRelayUrl("file:///etc/passwd"))
        assertNull(normalizeRelayUrl("https://"))
        assertNull(normalizeRelayUrl("http://a b"))
    }

    // ───────────────────── sid 解析 ─────────────────────

    @Test
    fun `attach 取第一行非空当 sid`() {
        assertEquals("0123456789abcdef0123456789abcdef", parseRelaySid("0123456789abcdef0123456789abcdef\n"))
        assertEquals("abc12345", parseRelaySid("\n  abc12345  \n"))
    }

    @Test
    fun `attach 空体或非法 sid 返回 null`() {
        assertNull(parseRelaySid(null))
        assertNull(parseRelaySid(""))
        assertNull(parseRelaySid("\n\n"))
        assertNull(parseRelaySid("short"))
        assertNull(parseRelaySid("has space in it"))
    }

    // ───────────────────── pull 解析 ─────────────────────

    @Test
    fun `pull 第一行 rid 其余是命令`() {
        val cmd = parsePullResponse("0123456789abcdef0123456789abcdef\nstatus")
        assertEquals("0123456789abcdef0123456789abcdef", cmd?.rid)
        assertEquals("status", cmd?.command)
    }

    @Test
    fun `pull 命令可含空格与多行`() {
        val cmd = parsePullResponse("0123456789abcdef\ncoarselocate /a/b c zone")
        assertEquals("coarselocate /a/b c zone", cmd?.command)
        val multi = parsePullResponse("abcdef12\nline1\nline2\n")
        assertEquals("line1\nline2", multi?.command)
    }

    @Test
    fun `pull 空体或坏 rid 返回 null`() {
        assertNull(parsePullResponse(null))
        assertNull(parsePullResponse(""))
        assertNull(parsePullResponse("short\nstatus"))
        assertNull(parsePullResponse("0123456789abcdef\n"))
        assertNull(parsePullResponse("0123456789abcdef\n   \n"))
    }

    // ───────────────────── 状态码映射 ─────────────────────

    @Test
    fun `wire code 映射`() {
        assertEquals(DebugCliRelayStatus.IDLE, DebugCliRelayStatus.fromWire(0))
        assertEquals(DebugCliRelayStatus.CONNECTING, DebugCliRelayStatus.fromWire(1))
        assertEquals(DebugCliRelayStatus.CONNECTED, DebugCliRelayStatus.fromWire(2))
        assertEquals(DebugCliRelayStatus.FAILED, DebugCliRelayStatus.fromWire(3))
    }

    @Test
    fun `未知 wire code fail-safe 成失败`() {
        assertEquals(DebugCliRelayStatus.FAILED, DebugCliRelayStatus.fromWire(99))
        assertEquals(DebugCliRelayStatus.FAILED, DebugCliRelayStatus.fromWire(-1))
    }

    // ───────────────────── 退避 ─────────────────────

    @Test
    fun `退避指数增长`() {
        assertEquals(1_000L, relayBackoffMillis(0))
        assertEquals(2_000L, relayBackoffMillis(1))
        assertEquals(4_000L, relayBackoffMillis(2))
        assertEquals(8_000L, relayBackoffMillis(3))
    }

    @Test
    fun `退避封顶且负数当首次`() {
        assertEquals(30_000L, relayBackoffMillis(5))
        assertEquals(30_000L, relayBackoffMillis(10))
        assertEquals(1_000L, relayBackoffMillis(-3))
        assertTrue(relayBackoffMillis(4) <= DEBUG_RELAY_MAX_BACKOFF_MILLIS)
    }
}
