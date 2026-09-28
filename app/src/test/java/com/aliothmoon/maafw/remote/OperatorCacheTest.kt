package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * 干员快照持久缓存测试（对齐上游 outposttrading/operator/cache.go）。
 *
 * 三类必须钉住的行为：
 *  1. **分级容错**：顶层坏 → 整份当不存在；单账号坏 → 只丢该账号；写入非法 → 整份不写
 *  2. **nil 快照 vs 空数组**：前者「没扫过」，后者「扫过但没有相关干员」，不能混
 *  3. **原子写**：失败不留半截文件，原文件保持旧内容
 */
class OperatorCacheTest {

    private val data = OperatorDataset.parse(
        """
        {
          "items": { "i1": { "names": { "zh_cn": "货品" } } },
          "operators": {
            "char_a": { "names": { "zh_cn": "干员A" } },
            "char_b": { "names": { "zh_cn": "干员B" } }
          },
          "location_order": ["L1"],
          "locations": { "L1": { "names": { "zh_cn": "据点一" }, "target_operators": [], "restore_operators": [] } }
        }
        """.trimIndent(),
    )!!

    private val uid = "0123456789abcdef"
    private val now: Instant = Instant.parse("2026-09-28T04:05:06.123456789Z")

    private fun candidate(name: String) = OperatorDataset.OperatorCandidate(name, listOf(name), 0, 0, 0)

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("operator-cache-test").toFile()
        return File(dir, OperatorCache.FILE_NAME)
    }

    // ───────────────────── UID 校验 ─────────────────────

    @Test
    fun `UID 只接受 unknown 或 16 位小写十六进制`() {
        assertTrue(OperatorCache.isValidUid("unknown"))
        assertTrue(OperatorCache.isValidUid("0123456789abcdef"))
        assertFalse(OperatorCache.isValidUid("0123456789ABCDEF")) // 大写拒绝
        assertFalse(OperatorCache.isValidUid("0123456789abcde")) // 15 位
        assertFalse(OperatorCache.isValidUid("0123456789abcdefg")) // 17 位
        assertFalse(OperatorCache.isValidUid(""))
        assertFalse(OperatorCache.isValidUid("0123456789abcdeg")) // 非 hex
        assertFalse(OperatorCache.isValidUid(" unknown"))
    }

    // ───────────────────── 解析的分级容错 ─────────────────────

    @Test
    fun `顶层出现未知键时整份当不存在`() {
        assertNull(OperatorCache.parseText("""{"accounts":{},"extra":1}"""))
        assertNull(OperatorCache.parseText("不是 json"))
        assertNull(OperatorCache.parseText(null))
    }

    @Test
    fun `账号出现未知字段时只丢该账号`() {
        val cache = OperatorCache.parseText(
            """
            {
              "accounts": {
                "0123456789abcdef": { "operators": { "updated_at": "2026-09-28T00:00:00Z", "ids": [] }, "version": 1 },
                "fedcba9876543210": { "locations": { "L1": true } }
              }
            }
            """.trimIndent(),
        )!!
        assertNull(cache.accounts?.get("0123456789abcdef"))
        assertTrue(cache.accounts?.containsKey("fedcba9876543210") == true)
    }

    @Test
    fun `非法 UID 的账号被丢弃`() {
        val cache = OperatorCache.parseText(
            """{"accounts":{"BAD_UID":{"locations":{"L1":true}}}}""",
        )!!
        assertTrue(cache.accounts.isNullOrEmpty())
    }

    @Test
    fun `accounts 缺失或为 null 时得到空缓存`() {
        assertTrue(OperatorCache.parseText("""{}""")?.accounts.isNullOrEmpty())
        assertTrue(OperatorCache.parseText("""{"accounts":null}""")?.accounts.isNullOrEmpty())
    }

    // ───────────────────── 账号校验 ─────────────────────

    @Test
    fun `有快照但 ids 为 null 时账号非法`() {
        val account = OperatorCache.Account(OperatorCache.Snapshot("2026-09-28T00:00:00Z", null))
        assertFalse(OperatorCache.accountIsValid(account, data))
    }

    @Test
    fun `快照时间为零值或不可解析时账号非法`() {
        assertFalse(
            OperatorCache.accountIsValid(
                OperatorCache.Account(OperatorCache.Snapshot("0001-01-01T00:00:00Z", emptyList())), data,
            ),
        )
        assertFalse(
            OperatorCache.accountIsValid(
                OperatorCache.Account(OperatorCache.Snapshot("不是时间", emptyList())), data,
            ),
        )
    }

    @Test
    fun `快照含未知干员 ID 或未知据点时账号非法`() {
        assertFalse(
            OperatorCache.accountIsValid(
                OperatorCache.Account(OperatorCache.Snapshot("2026-09-28T00:00:00Z", listOf("char_zzz"))), data,
            ),
        )
        assertFalse(
            OperatorCache.accountIsValid(
                OperatorCache.Account(locations = mapOf("L9" to true)), data,
            ),
        )
    }

    @Test
    fun `无快照的账号合法 空 ids 也合法`() {
        assertTrue(OperatorCache.accountIsValid(OperatorCache.Account(locations = mapOf("L1" to true)), data))
        assertTrue(
            OperatorCache.accountIsValid(
                OperatorCache.Account(OperatorCache.Snapshot("2026-09-28T00:00:00Z", emptyList())), data,
            ),
        )
    }

    // ───────────────────── nil 快照 vs 空数组 ─────────────────────

    @Test
    fun `空 ids 表示扫过但没有相关干员 仍然算有快照`() {
        val cache = OperatorCache.Cache(
            mapOf(uid to OperatorCache.Account(OperatorCache.Snapshot("2026-09-28T00:00:00Z", emptyList()))),
        )
        // 这一条错了会让每轮任务都重扫、且永远选不出人
        assertTrue(OperatorCache.hasOperatorSnapshot(cache, uid))
        assertEquals(emptyList<String>(), OperatorCache.cachedOperatorIds(cache, uid))
    }

    @Test
    fun `无快照时 hasOperatorSnapshot 为假`() {
        val cache = OperatorCache.Cache(mapOf(uid to OperatorCache.Account(locations = mapOf("L1" to true))))
        assertFalse(OperatorCache.hasOperatorSnapshot(cache, uid))
        assertNull(OperatorCache.cachedOperatorUpdatedAt(cache, uid))
    }

    // ───────────────────── normalize ─────────────────────

    @Test
    fun `normalize 对 ids 去空去重排序且 uid 原样`() {
        val cache = OperatorCache.Cache(
            mapOf(
                uid to OperatorCache.Account(
                    OperatorCache.Snapshot("2026-09-28T00:00:00Z", listOf("b", "", "a", "b")),
                ),
            ),
        )
        val normalized = OperatorCache.normalize(cache)
        assertEquals(listOf("a", "b"), normalized.accounts?.get(uid)?.operators?.ids)
        assertTrue(normalized.accounts?.containsKey(uid) == true)
    }

    @Test
    fun `normalize 把空账号表变成 null`() {
        assertNull(OperatorCache.normalize(OperatorCache.Cache(emptyMap())).accounts)
        assertNull(OperatorCache.normalize(OperatorCache.Cache(null)).accounts)
    }

    @Test
    fun `normalize 保留非 nil 的空 ids 而不是变成 null`() {
        val cache = OperatorCache.Cache(
            mapOf(uid to OperatorCache.Account(OperatorCache.Snapshot("2026-09-28T00:00:00Z", emptyList()))),
        )
        val ids = OperatorCache.normalize(cache).accounts?.get(uid)?.operators?.ids
        assertEquals(emptyList<String>(), ids)
        assertTrue(ids != null)
    }

    // ───────────────────── 快照写入式变更 ─────────────────────

    @Test
    fun `mergeOperatorSnapshot 只保留扫描域内被识别到的`() {
        val merged = OperatorCache.mergeOperatorSnapshot(
            OperatorCache.Cache(null),
            uid,
            scanCandidates = listOf(candidate("char_a"), candidate("char_b")),
            observed = listOf("char_a", "char_zzz"),
            now = now,
        )
        assertEquals(listOf("char_a"), OperatorCache.cachedOperatorIds(merged, uid))
        assertEquals(now.toString(), OperatorCache.cachedOperatorUpdatedAt(merged, uid))
    }

    @Test
    fun `mergeOperatorSnapshot 交集为空产出空数组而非无快照`() {
        val merged = OperatorCache.mergeOperatorSnapshot(
            OperatorCache.Cache(null), uid,
            scanCandidates = listOf(candidate("char_a")),
            observed = emptyList(),
            now = now,
        )
        assertTrue(OperatorCache.hasOperatorSnapshot(merged, uid))
        assertEquals(emptyList<String>(), OperatorCache.cachedOperatorIds(merged, uid))
    }

    @Test
    fun `invalidateOperatorSnapshot 清快照但保留据点状态`() {
        val cache = OperatorCache.Cache(
            mapOf(
                uid to OperatorCache.Account(
                    OperatorCache.Snapshot("2026-09-28T00:00:00Z", listOf("char_a")),
                    mapOf("L1" to true),
                ),
            ),
        )
        val invalidated = OperatorCache.invalidateOperatorSnapshot(cache, uid)
        assertFalse(OperatorCache.hasOperatorSnapshot(invalidated, uid))
        assertEquals(setOf("L1"), OperatorCache.prosperityMaxLocations(invalidated, uid))
    }

    // ───────────────────── 发展值状态 ─────────────────────

    @Test
    fun `updateProsperity 值未变时不改动`() {
        val cache = OperatorCache.Cache(mapOf(uid to OperatorCache.Account(locations = mapOf("L1" to true))))
        val (changed, updated) = OperatorCache.updateProsperity(cache, uid, "L1", true)
        assertFalse(changed)
        assertEquals(cache, updated)
    }

    @Test
    fun `updateProsperity 值变化时写入`() {
        val cache = OperatorCache.Cache(null)
        val (changed, updated) = OperatorCache.updateProsperity(cache, uid, " L1 ", false)
        assertTrue(changed)
        assertEquals(mapOf("L1" to false), OperatorCache.prosperityStatuses(updated, uid))
        // 新增的 false 不会在下次重复写入
        val (again, _) = OperatorCache.updateProsperity(updated, uid, "L1", false)
        assertFalse(again)
    }

    @Test
    fun `updateProsperity 空据点名报错`() {
        val cache = OperatorCache.Cache(null)
        assertThrows(IllegalArgumentException::class.java) {
            OperatorCache.updateProsperity(cache, uid, "   ", true)
        }
    }

    @Test
    fun `updateProsperity 保留已有快照`() {
        val cache = OperatorCache.Cache(
            mapOf(uid to OperatorCache.Account(OperatorCache.Snapshot("2026-09-28T00:00:00Z", listOf("char_a")))),
        )
        val (_, updated) = OperatorCache.updateProsperity(cache, uid, "L1", true)
        assertEquals(listOf("char_a"), OperatorCache.cachedOperatorIds(updated, uid))
    }

    // ───────────────────── 文件 IO ─────────────────────

    @Test
    fun `read 对不存在的文件返回空缓存`() {
        val file = File(tempFile().parentFile, "not-there.json")
        assertTrue(OperatorCache.read(file).accounts.isNullOrEmpty())
    }

    @Test
    fun `write 后 read 能往返`() {
        val file = tempFile()
        val cache = OperatorCache.Cache(
            mapOf(
                uid to OperatorCache.Account(
                    OperatorCache.Snapshot(now.toString(), listOf("char_a", "char_b")),
                    mapOf("L1" to true),
                ),
            ),
        )
        OperatorCache.write(file, cache, data)

        val loaded = OperatorCache.read(file)
        assertEquals(listOf("char_a", "char_b"), OperatorCache.cachedOperatorIds(loaded, uid))
        assertEquals(setOf("L1"), OperatorCache.prosperityMaxLocations(loaded, uid))
    }

    @Test
    fun `write 校验不过时不动原文件`() {
        val file = tempFile()
        OperatorCache.write(
            file,
            OperatorCache.Cache(mapOf(uid to OperatorCache.Account(locations = mapOf("L1" to true)))),
            data,
        )
        val before = file.readText()

        // 含未知干员 ID -> 整份校验失败
        val bad = OperatorCache.Cache(
            mapOf(uid to OperatorCache.Account(OperatorCache.Snapshot(now.toString(), listOf("char_zzz")))),
        )
        assertThrows(IllegalArgumentException::class.java) { OperatorCache.write(file, bad, data) }
        assertEquals(before, file.readText())
    }

    @Test
    fun `writeAtomic 失败时不留临时文件`() {
        val dir = Files.createTempDirectory("operator-cache-atomic").toFile()
        val target = File(dir, OperatorCache.FILE_NAME)
        // 目标位置是一个目录 -> rename 必然失败
        target.mkdirs()

        assertThrows(Exception::class.java) {
            OperatorCache.writeAtomic(target, "{}".toByteArray())
        }
        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertEquals(emptyList<File>(), leftovers)
    }

    @Test
    fun `序列化保留 null 与空数组的区别`() {
        val withNil = OperatorCache.Cache(mapOf(uid to OperatorCache.Account(locations = mapOf("L1" to true))))
        val json = OperatorCache.toJson(withNil)
        // 无快照时序列化里不应出现 operators 键
        assertFalse(json.contains("operators"))

        val withEmpty = OperatorCache.Cache(
            mapOf(uid to OperatorCache.Account(OperatorCache.Snapshot(now.toString(), emptyList()))),
        )
        val json2 = OperatorCache.toJson(withEmpty)
        assertTrue(json2.contains("\"ids\":[]"))
    }
}
