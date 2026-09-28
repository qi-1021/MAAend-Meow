package com.aliothmoon.maafw.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunDiagnosticsPolicy] 的纯逻辑测试。
 *
 * 这里校验的都是「删哪些文件」这种跑错一次就丢证据、又很难在真机上复现的决定，
 * 所以每个边界都钉死：正好到上限不删、超一个就删最旧、时间相同按名字定序。
 */
class RunDiagnosticsPolicyTest {

    private fun file(name: String, size: Long, modified: Long) =
        RunDiagnosticsPolicy.ReportFile(name, size, modified)

    // ───────────────────── 排序 ─────────────────────

    @Test
    fun `按时间旧到新排序`() {
        val sorted = RunDiagnosticsPolicy.oldestFirst(
            listOf(file("c", 1, 300), file("a", 1, 100), file("b", 1, 200)),
        )
        assertEquals(listOf("a", "b", "c"), sorted.map { it.name })
    }

    @Test
    fun `时间相同按文件名定序`() {
        // 同一秒内建的多个报告：排序必须稳定，否则每次算出的待删集合不同
        val sorted = RunDiagnosticsPolicy.oldestFirst(
            listOf(file("b", 1, 100), file("a", 1, 100)),
        )
        assertEquals(listOf("a", "b"), sorted.map { it.name })
    }

    // ───────────────────── 单文件截断 ─────────────────────

    @Test
    fun `正好写到上限不算截断`() {
        assertFalse(RunDiagnosticsPolicy.shouldTruncate(currentBytes = 100, incomingBytes = 8, limitBytes = 108))
    }

    @Test
    fun `超过上限一个字节就截断`() {
        assertTrue(RunDiagnosticsPolicy.shouldTruncate(currentBytes = 100, incomingBytes = 9, limitBytes = 108))
    }

    @Test
    fun `默认上限是 8MiB`() {
        assertEquals(8L * 1024 * 1024, RunDiagnosticsPolicy.MAX_FILE_BYTES)
        assertFalse(RunDiagnosticsPolicy.shouldTruncate(RunDiagnosticsPolicy.MAX_FILE_BYTES, 0))
        assertTrue(RunDiagnosticsPolicy.shouldTruncate(RunDiagnosticsPolicy.MAX_FILE_BYTES, 1))
    }

    // ───────────────────── 份数上限 ─────────────────────

    @Test
    fun `份数未超不删`() {
        val files = (1..3).map { file("run-$it", 1, it.toLong()) }
        assertTrue(RunDiagnosticsPolicy.selectOverflowByCount(files, maxFiles = 3).isEmpty())
    }

    @Test
    fun `份数超出删最旧的若干个`() {
        // 10 份上限、12 份在，删最旧的 2 份（run-1、run-2）
        val files = (1..12).map { file("run-$it", 1, it.toLong()) }
        val deleted = RunDiagnosticsPolicy.selectOverflowByCount(files, maxFiles = 10)
        assertEquals(listOf("run-1", "run-2"), deleted.map { it.name })
    }

    // ───────────────────── 目录总量上限 ─────────────────────

    @Test
    fun `总量未超不删`() {
        val files = listOf(file("a", 40, 1), file("b", 40, 2))
        assertTrue(RunDiagnosticsPolicy.selectOverflowByTotal(files, maxTotalBytes = 100).isEmpty())
    }

    @Test
    fun `总量超出从最旧开始删到线内`() {
        // 总量 300，上限 150：删掉最旧的 a(100) 后剩 200 仍超，再删 b(100) 正好 100 <= 150
        val files = listOf(file("a", 100, 1), file("b", 100, 2), file("c", 100, 3))
        val deleted = RunDiagnosticsPolicy.selectOverflowByTotal(files, maxTotalBytes = 150)
        assertEquals(listOf("a", "b"), deleted.map { it.name })
    }

    // ───────────────────── 两条上限综合 ─────────────────────

    @Test
    fun `先按份数删再按剩下的总量删`() {
        // 5 份 × 100 = 500；份数上限 3 → 删 a、b，剩 c,d,e(300)。
        // 总量上限 250 → 在剩下的里再删最旧的 c(100)，剩 d,e(200)
        val files = (1..5).map { file("run-$it", 100, it.toLong()) }
        val deleted = RunDiagnosticsPolicy.selectDeletions(files, maxFiles = 3, maxTotalBytes = 250)
        assertEquals(listOf("run-1", "run-2", "run-3"), deleted.map { it.name })
    }

    @Test
    fun `综合删集合不重复`() {
        val files = (1..5).map { file("run-$it", 100, it.toLong()) }
        val deleted = RunDiagnosticsPolicy.selectDeletions(files, maxFiles = 2, maxTotalBytes = 50)
        assertEquals(deleted.size, deleted.map { it.name }.toSet().size)
    }

    @Test
    fun `默认上限与常量一致`() {
        assertEquals(10, RunDiagnosticsPolicy.MAX_FILES)
        assertEquals(64L * 1024 * 1024, RunDiagnosticsPolicy.MAX_TOTAL_BYTES)
    }
}
