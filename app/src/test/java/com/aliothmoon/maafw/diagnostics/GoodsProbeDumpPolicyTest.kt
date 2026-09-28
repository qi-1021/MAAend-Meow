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

    // ───────────────────── 文件名判据（清理的唯一「可删」依据） ─────────────────────

    private fun dump(name: String, size: Long, modified: Long) =
        RunDiagnosticsPolicy.ReportFile(name, size, modified)

    @Test
    fun `文件判据只认 goods 前缀且 png 后缀`() {
        assertTrue(GoodsProbeDumpPolicy.isDumpName("goods-Wuling-1700000000000.png"))
        assertFalse(GoodsProbeDumpPolicy.isDumpName("goods-Wuling-1.txt"))
        assertFalse(GoodsProbeDumpPolicy.isDumpName("run-20260928-120000.jsonl"))
        assertFalse(GoodsProbeDumpPolicy.isDumpName("on_error-ish.png"))
        assertFalse(GoodsProbeDumpPolicy.isDumpName("goods-1.png.bak"))
    }

    // ───────────────────── 份数上限 ─────────────────────

    @Test
    fun `空目录不删`() {
        assertTrue(GoodsProbeDumpPolicy.selectDeletions(emptyList()).isEmpty())
    }

    @Test
    fun `份数正好等于上限不删`() {
        val files = (1..GoodsProbeDumpPolicy.MAX_FILES).map { dump("goods-$it.png", 1, it.toLong()) }
        assertTrue(GoodsProbeDumpPolicy.selectDeletions(files).isEmpty())
    }

    @Test
    fun `份数超一份删最旧`() {
        val files = (1..GoodsProbeDumpPolicy.MAX_FILES + 1).map {
            dump("goods-$it.png", 1, it.toLong())
        }
        val deleted = GoodsProbeDumpPolicy.selectDeletions(files)
        assertEquals(listOf("goods-1.png"), deleted.map { it.name })
    }

    // ───────────────────── 总量上限 ─────────────────────

    @Test
    fun `总量正好等于上限不删`() {
        val half = GoodsProbeDumpPolicy.MAX_TOTAL_BYTES / 2
        val files = listOf(dump("goods-a.png", half, 1), dump("goods-b.png", half, 2))
        assertTrue(GoodsProbeDumpPolicy.selectDeletions(files).isEmpty())
    }

    @Test
    fun `总量超出从最旧删到线内`() {
        val half = GoodsProbeDumpPolicy.MAX_TOTAL_BYTES / 2
        // a+b = 上限整，c 多出的 1 字节把总量顶过线：删最旧的 a 后正好落回线内
        val files = listOf(
            dump("goods-a.png", half, 1),
            dump("goods-b.png", half, 2),
            dump("goods-c.png", 1, 3),
        )
        val deleted = GoodsProbeDumpPolicy.selectDeletions(files)
        assertEquals(listOf("goods-a.png"), deleted.map { it.name })
    }

    // ───────────────────── 只清自己、不误删别的目录 ─────────────────────

    @Test
    fun `混合目录只清探针帧不碰报告与 on_error`() {
        // 报告 JSONL 数量远多于探针帧：若判据没过滤掉它们，待删集合会被它们占满。
        val reports = (1..30).map { dump("run-20260928-$it.jsonl", 1, it.toLong()) }
        val goods = (1..GoodsProbeDumpPolicy.MAX_FILES + 3).map {
            dump("goods-$it.png", 1, 1000L + it)
        }
        val others = listOf(
            dump("on_error-something.png", 1, 1),
            dump("readme.txt", 1, 1),
            dump("goods-notpng.txt", 1, 1),
        )
        val deleted = GoodsProbeDumpPolicy.selectDeletions(reports + goods + others)

        // 只删最旧的 3 张探针帧
        assertEquals(
            listOf("goods-1.png", "goods-2.png", "goods-3.png"),
            deleted.map { it.name },
        )
        // 其余任何文件都不得出现在待删集合
        assertTrue(deleted.none { it.name.startsWith("run-") })
        assertTrue(deleted.none { it.name.startsWith("on_error") })
        assertTrue(deleted.none { it.name == "readme.txt" })
        assertTrue(deleted.none { it.name == "goods-notpng.txt" })
    }

    @Test
    fun `非 png 的同名文件不参与清理`() {
        // goods- 前缀但后缀不对：既不算数、也就不会因此触发总量/份数删除
        val files = (1..GoodsProbeDumpPolicy.MAX_FILES + 5).map {
            dump("goods-$it.txt", 1, it.toLong())
        }
        assertTrue(GoodsProbeDumpPolicy.selectDeletions(files).isEmpty())
    }

    @Test
    fun `默认上限与常量一致`() {
        assertEquals(20, GoodsProbeDumpPolicy.MAX_FILES)
        assertEquals(32L * 1024 * 1024, GoodsProbeDumpPolicy.MAX_TOTAL_BYTES)
    }
}
