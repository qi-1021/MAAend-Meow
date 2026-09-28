package com.aliothmoon.maafw.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 干员子系统运行时聚合测试（数据加载 + 缓存读写 + UID 分区）。
 *
 * 用临时 projectRoot 搭出与真实打包一致的目录结构：
 * `<root>/data/OutpostTrading/selection_data.json`。
 * 这条路径很重要——它不是 `assets/data/...`（当前打包方式下没有散装 data 目录）。
 */
class OperatorRuntimeTest {

    private val selectionJson = """
        {
          "items": { "i1": { "names": { "zh_cn": "货品" } } },
          "operators": {
            "char_a": { "names": { "zh_cn": "干员A" } },
            "char_b": { "names": { "zh_cn": "干员B" } }
          },
          "location_order": ["L1"],
          "locations": {
            "L1": {
              "names": { "zh_cn": "据点一" },
              "target_operators": [{ "name": "char_a", "bonus_tier": 0, "outpost_prosperity_max_bonus_tier": 0 }],
              "restore_operators": ["char_b"]
            }
          }
        }
    """.trimIndent()

    private lateinit var root: File

    private fun setupRoot(withData: Boolean = true): File {
        root = Files.createTempDirectory("operator-runtime-test").toFile()
        if (withData) {
            val dir = File(root, "data/OutpostTrading")
            dir.mkdirs()
            File(dir, "selection_data.json").writeText(selectionJson)
        }
        OperatorRuntime.resetForTest()
        OperatorRuntime.projectRoot = root.path
        OperatorRuntime.cacheRoot = root.path
        OperatorRuntime.uidProvider = { OperatorRuntime.UNKNOWN_UID }
        return root
    }

    @After
    fun tearDown() {
        OperatorRuntime.resetForTest()
        OperatorRuntime.projectRoot = null
        OperatorRuntime.cacheRoot = null
        OperatorRuntime.uidProvider = { OperatorRuntime.UNKNOWN_UID }
    }

    @Test
    fun `从 projectRoot 的相对路径加载数据`() {
        setupRoot()
        val data = OperatorRuntime.loadData()
        assertNotNull(data)
        assertEquals(listOf("L1"), data?.locationOrder)
        assertEquals(listOf("char_a", "char_b"), data?.knownOperators?.map { it.name })
    }

    @Test
    fun `缺少 projectRoot 或文件时返回 null 并给出原因`() {
        setupRoot(withData = false)
        assertNull(OperatorRuntime.loadData())
        assertTrue(OperatorRuntime.lastError().isNotEmpty())

        OperatorRuntime.resetForTest()
        OperatorRuntime.projectRoot = null
        assertNull(OperatorRuntime.loadData())
        assertTrue(OperatorRuntime.lastError().contains("projectRoot"))
    }

    @Test
    fun `无缓存时拥有干员为空集且没有快照`() {
        setupRoot()
        assertEquals(emptySet<String>(), OperatorRuntime.loadOwnedNames())
        assertFalse(OperatorRuntime.hasSnapshot())
        assertNull(OperatorRuntime.snapshotUpdatedAt())
    }

    @Test
    fun `写快照后能读回拥有干员`() {
        setupRoot()
        val candidates = listOf(
            OperatorDataset.OperatorCandidate("char_a", listOf("干员A"), 0, 0, 0),
            OperatorDataset.OperatorCandidate("char_b", listOf("干员B"), 0, 0, 0),
        )
        assertTrue(OperatorRuntime.writeSnapshot(candidates, listOf("char_a", "char_zzz")))
        assertTrue(OperatorRuntime.hasSnapshot())
        // 只保留扫描域内的
        assertEquals(setOf("char_a"), OperatorRuntime.loadOwnedNames())
        assertNotNull(OperatorRuntime.snapshotUpdatedAt())
    }

    @Test
    fun `交集为空时写成空数组而不是没有快照`() {
        setupRoot()
        val candidates = listOf(OperatorDataset.OperatorCandidate("char_a", listOf("干员A"), 0, 0, 0))
        assertTrue(OperatorRuntime.writeSnapshot(candidates, emptyList()))
        // 扫过但没有相关干员：有快照、拥有集合为空
        assertTrue(OperatorRuntime.hasSnapshot())
        assertEquals(emptySet<String>(), OperatorRuntime.loadOwnedNames())
    }

    @Test
    fun `清快照后回到没有快照的状态`() {
        setupRoot()
        val candidates = listOf(OperatorDataset.OperatorCandidate("char_a", listOf("干员A"), 0, 0, 0))
        OperatorRuntime.writeSnapshot(candidates, listOf("char_a"))
        assertTrue(OperatorRuntime.hasSnapshot())

        assertTrue(OperatorRuntime.invalidateSnapshot())
        assertFalse(OperatorRuntime.hasSnapshot())
    }

    @Test
    fun `缓存写在 cacheRoot 的相对路径下`() {
        val r = setupRoot()
        assertEquals(File(r, OperatorRuntime.CACHE_RELATIVE).path, OperatorRuntime.cacheFile().path)
        assertTrue(OperatorRuntime.cacheFile().path.contains("OutpostTradingCache.json"))
    }

    @Test
    fun `UID 分区隔离`() {
        setupRoot()
        val candidates = listOf(OperatorDataset.OperatorCandidate("char_a", listOf("干员A"), 0, 0, 0))

        OperatorRuntime.uidProvider = { "unknown" }
        OperatorRuntime.writeSnapshot(candidates, listOf("char_a"))

        // 换一个合法 UID：读不到别人的快照
        OperatorRuntime.uidProvider = { "0123456789abcdef" }
        assertFalse(OperatorRuntime.hasSnapshot())
        assertEquals(emptySet<String>(), OperatorRuntime.loadOwnedNames())
    }

    @Test
    fun `发展值状态经会话写盘后可读回`() {
        setupRoot()
        // 通过会话的 enter_location 写盘（内部走 OperatorRuntime 的 cache）
        val outcome = OperatorRuntime.session.run(
            OperatorSession.ActionParam(operation = "enter_location", location = "L1", outpostProsperityMax = true),
        )
        assertEquals(OperatorSession.SessionOutcome.Ok, outcome)

        // 直接读缓存确认落盘
        val cache = OperatorRuntime.readCache()
        assertEquals(setOf("L1"), OperatorCache.prosperityMaxLocations(cache, OperatorRuntime.uidProvider()))
    }
}
