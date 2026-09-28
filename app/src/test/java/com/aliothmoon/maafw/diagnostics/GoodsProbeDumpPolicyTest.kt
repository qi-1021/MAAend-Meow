package com.aliothmoon.maafw.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GoodsProbeDumpPolicy] 的纯逻辑测试。
 *
 * 这里钉死两件真机上错了就无法补救的事：
 *  - **release 绝不导出**：`shouldDump` 必须在诊断关闭时恒为 false；
 *  - **路径不会越界**：region 再脏也拼不出绝对路径或 `..`，且文件名一定带时间戳可区分。
 */
class GoodsProbeDumpPolicyTest {

    // ───────────────────── 是否导出 ─────────────────────

    @Test
    fun `诊断开启且全 0 候选才导出`() {
        assertTrue(GoodsProbeDumpPolicy.shouldDump(diagnosticsEnabled = true, allAttemptsZeroCandidates = true))
    }

    @Test
    fun `诊断关闭绝不导出`() {
        assertFalse(GoodsProbeDumpPolicy.shouldDump(diagnosticsEnabled = false, allAttemptsZeroCandidates = true))
        assertFalse(GoodsProbeDumpPolicy.shouldDump(diagnosticsEnabled = false, allAttemptsZeroCandidates = false))
    }

    @Test
    fun `还有候选时不导出`() {
        // 只要某次尝试扫到过候选，就不该落失败帧，避免正常路径写盘
        assertFalse(GoodsProbeDumpPolicy.shouldDump(diagnosticsEnabled = true, allAttemptsZeroCandidates = false))
    }

    // ───────────────────── region 收敛 ─────────────────────

    @Test
    fun `正常 region 原样保留`() {
        assertEquals("Wuling", GoodsProbeDumpPolicy.sanitizeRegion("Wuling"))
        assertEquals("ValleyIV", GoodsProbeDumpPolicy.sanitizeRegion("ValleyIV"))
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals("Wuling", GoodsProbeDumpPolicy.sanitizeRegion("  Wuling  "))
    }

    @Test
    fun `空 region 退回 unknown`() {
        assertEquals("unknown", GoodsProbeDumpPolicy.sanitizeRegion(""))
        assertEquals("unknown", GoodsProbeDumpPolicy.sanitizeRegion("   "))
    }

    @Test
    fun `路径分隔符与点号被收敛`() {
        val cleaned = GoodsProbeDumpPolicy.sanitizeRegion("../../etc/passwd")
        assertFalse(cleaned.contains("/"))
        assertFalse(cleaned.contains(".."))
        assertEquals("______etc_passwd", cleaned)
    }

    @Test
    fun `中文与制表符等非法字符被换成下划线`() {
        assertEquals("__", GoodsProbeDumpPolicy.sanitizeRegion("武陵"))
        assertEquals("a_b", GoodsProbeDumpPolicy.sanitizeRegion("a\tb"))
    }

    // ───────────────────── 路径生成 ─────────────────────

    @Test
    fun `导出路径带目录前缀 region 与时间戳`() {
        assertEquals(
            "probe_dump/goods-Wuling-1700000000000.png",
            GoodsProbeDumpPolicy.dumpRelativePath("Wuling", 1700000000000L),
        )
    }

    @Test
    fun `导出路径不含绝对根且恒有 png 后缀`() {
        val path = GoodsProbeDumpPolicy.dumpRelativePath("../../oops", 1L)
        assertFalse(path.startsWith("/"))
        assertFalse(path.contains(".."))
        assertTrue(path.startsWith(GoodsProbeDumpPolicy.DUMP_DIR + "/"))
        assertTrue(path.endsWith(GoodsProbeDumpPolicy.FILE_SUFFIX))
    }

    @Test
    fun `同一 region 不同时间戳文件名不同`() {
        assertFalse(
            GoodsProbeDumpPolicy.dumpRelativePath("Wuling", 1L) ==
                GoodsProbeDumpPolicy.dumpRelativePath("Wuling", 2L),
        )
    }
}
