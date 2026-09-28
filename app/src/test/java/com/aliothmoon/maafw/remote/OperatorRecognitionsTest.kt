package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.OperatorDataset.OperatorCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 干员识别决策层测试（对齐上游 recognition.go + conflict.go）。
 *
 * 重点压在三条「顺序写反就静默出错」的规则上：
 *  1. `decideUncached` 的 claim 在 Refreshed 检查**之前**（否则配额语义变了）
 *  2. `decideCacheReady` 的 claim 在 ready 判断**之前**（否则不 ready 时不提示）
 *  3. `OperatorListBottom` 的 retry 分支：claim 失败要把 error 写上、hasCandidate 置回 false
 */
class OperatorRecognitionsTest {

    private class FakeHost : OperatorRecognitions.Host {
        var data: OperatorDataset.OperatorSelectionData? = null
        var file: OperatorDataset.SelectionFile? = null
        var resolved: OperatorSelection.SelectionParam? = null
        var owned: Set<String>? = setOf("char_a", "char_b")
        var listItems: List<OperatorOcrMatch.Item>? = emptyList()
        var currentItems: List<OperatorOcrMatch.Item>? = emptyList()
        var hasSnapshot = false
        var writeSnapshotOk = true
        var invalidateOk = true
        var lastErr = "boom"
        var uid = "0123456789abcdef"
        val logs = mutableListOf<String>()
        var shouldWrite = true
        var cacheStatusReady = false
        var cacheStatusUpdatedAt = "2026-09-28T00:00:00Z"

        override val scanStates = OperatorScan.ScanStates()
        override val session = OperatorSession(object : OperatorSession.Host {
            override fun currentUid() = uid
            override fun loadProsperityMaxLocations(uid: String) = emptySet<String>()
            override fun persistProsperityStatus(uid: String, location: String, reached: Boolean) = true
        })

        override fun selectionData() = data
        override fun selectionFile() = file
        override fun resolveSelection(p: OperatorRecognitions.Param) = resolved
        override fun loadOwnedNames() = owned
        override fun listOcr(roi: List<Int>) = listItems
        override fun currentOcr(roi: List<Int>, reuse: Boolean) = currentItems
        override fun cacheHasSnapshot(uid: String) = hasSnapshot
        override fun invalidateSnapshot(uid: String) = invalidateOk
        override fun shouldWriteSnapshot(p: OperatorRecognitions.Param) = shouldWrite
        override fun writeSnapshot(
            p: OperatorRecognitions.Param,
            scanCandidates: List<OperatorCandidate>,
            observed: List<String>,
        ) = writeSnapshotOk

        override fun lastError() = lastErr
        override fun currentUid() = uid
        override fun log(message: String) {
            logs += message
        }
    }

    private fun candidate(name: String, bonusTier: Int = 0) =
        OperatorCandidate(name, listOf(name), 0, bonusTier, 0)

    private fun item(text: String, y: Int = 0) = OperatorOcrMatch.Item(text, OcrBox(0, y, 10, 10))

    private fun param(
        mode: String = "cache",
        usage: String = "target",
        location: String = "L1",
        result: String = "scan_done",
        roi: List<Int> = listOf(0, 0, 100, 100),
    ) = OperatorRecognitions.Param(mode, usage, location, result, roi)

    private fun selectionParam(
        usage: String = "target",
        location: String = "L1",
        candidates: List<OperatorCandidate> = emptyList(),
        knownOperators: List<OperatorCandidate> = emptyList(),
        restoreGroups: List<OperatorDataset.OperatorCandidateGroup> = emptyList(),
        activeLocations: Set<String> = setOf("L1"),
        lockedRestoreAssignments: Map<String, OperatorCandidate> = emptyMap(),
        /** 扫描域候选；ListBottom 用它判定「本帧看得见谁」，不设就永远到不了底。 */
        scanCandidates: List<OperatorCandidate> = candidates,
    ) = OperatorSelection.SelectionParam(
        usage = usage,
        location = location,
        candidates = candidates,
        knownOperators = knownOperators,
        targetCandidatesByLocation = mapOf(location to candidates),
        restoreGroups = restoreGroups,
        scanCandidates = scanCandidates,
        activeLocations = activeLocations,
        lockedRestoreAssignments = lockedRestoreAssignments,
    )

    // ───────────────────── 参数解析 ─────────────────────

    @Test
    fun `parseParam 校验 mode usage location roi`() {
        val ok = OperatorRecognitions.parseParam(
            mapOf("mode" to " cache ", "usage" to "target", "location" to "L1", "roi" to listOf(1, 2, 3, 4)),
        )
        assertEquals("cache", ok?.mode)
        assertEquals(listOf(1, 2, 3, 4), ok?.roi)

        assertNull(OperatorRecognitions.parseParam(mapOf("mode" to "x", "usage" to "target", "location" to "L", "roi" to listOf(1, 2, 3, 4))))
        assertNull(OperatorRecognitions.parseParam(mapOf("mode" to "cache", "usage" to "x", "location" to "L", "roi" to listOf(1, 2, 3, 4))))
        assertNull(OperatorRecognitions.parseParam(mapOf("mode" to "cache", "usage" to "target", "location" to "  ", "roi" to listOf(1, 2, 3, 4))))
        assertNull(OperatorRecognitions.parseParam(mapOf("mode" to "cache", "usage" to "target", "location" to "L", "roi" to listOf(1, 2, 3))))
        assertNull(OperatorRecognitions.parseParam(null))
    }

    @Test
    fun `parseConflictParam 校验 result usage location`() {
        assertEquals(
            "managed",
            OperatorRecognitions.parseConflictParam(mapOf("result" to "managed", "usage" to "target", "location" to "L1"))?.result,
        )
        assertNull(OperatorRecognitions.parseConflictParam(mapOf("result" to "x", "usage" to "target", "location" to "L1")))
        assertNull(OperatorRecognitions.parseConflictParam(mapOf("result" to "managed", "usage" to "all", "location" to "L1")))
        assertNull(OperatorRecognitions.parseConflictParam(mapOf("result" to "managed", "usage" to "target", "location" to "")))
    }

    // ───────────────────── 冲突来源匹配 ─────────────────────

    @Test
    fun `findConflictSource 方向是 OCR 文本包含候选据点名`() {
        val file = OperatorDataset.parse(
            """
            {
              "items": {}, 
              "operators": { "char_a": { "names": { "zh_cn": "干员A" } } },
              "location_order": ["L1"],
              "locations": { "L1": { "names": { "zh_cn": "据点一" }, "target_operators": [], "restore_operators": [] } }
            }
            """.trimIndent(),
        )!!
        // 弹窗里出现「据点一」的子串即可命中
        val (source, text, recognized) = OperatorRecognitions.findConflictSource(
            listOf(item("该干员当前派驻在【据点一】中")), file,
        )
        assertEquals("L1", source)
        assertTrue(recognized)
        assertEquals("该干员当前派驻在【据点一】中", text)
    }

    @Test
    fun `findConflictSource 未识别时返回首条文本`() {
        val file = OperatorDataset.parse(
            """
            {"items":{},"operators":{"char_a":{"names":{"zh_cn":"A"}}},
             "location_order":["L1"],
             "locations":{"L1":{"names":{"zh_cn":"据点一"},"target_operators":[],"restore_operators":[]}}}
            """.trimIndent(),
        )!!
        val (source, text, recognized) = OperatorRecognitions.findConflictSource(listOf(item("完全无关的弹窗")), file)
        assertEquals("", source)
        assertFalse(recognized)
        assertEquals("完全无关的弹窗", text)
    }

    // ───────────────────── SelectBest ─────────────────────

    @Test
    fun `decideSelectBest 命中第一名并登记 target 分配`() {
        val host = FakeHost()
        host.resolved = selectionParam(candidates = listOf(candidate("char_a")))
        host.listItems = listOf(item("char_a"))
        val outcome = OperatorRecognitions(host).decideSelectBest(param())

        assertTrue(outcome is OperatorRecognitions.Outcome.Hit)
        assertEquals("char_a", (outcome as OperatorRecognitions.Outcome.Hit).detail.substringAfter(":"))
        // 登记了 target 分配
        assertEquals("char_a", host.session.targetAssignment("L1")?.name)
    }

    @Test
    fun `decideSelectBest 只认第一名 次优可见也不降级`() {
        val host = FakeHost()
        host.resolved = selectionParam(candidates = listOf(candidate("char_a"), candidate("char_b")))
        // 界面只有次优
        host.listItems = listOf(item("char_b"))
        val outcome = OperatorRecognitions(host).decideSelectBest(param())
        assertEquals(OperatorRecognitions.Outcome.Miss, outcome)
        assertNull(host.session.targetAssignment("L1"))
    }

    // ───────────────────── CurrentBest ─────────────────────

    @Test
    fun `decideCurrentBest 命中时也登记 target 分配`() {
        val host = FakeHost()
        host.resolved = selectionParam(candidates = listOf(candidate("char_a")))
        host.currentItems = listOf(item("char_a"))
        val outcome = OperatorRecognitions(host).decideCurrentBest(param())

        assertTrue(outcome is OperatorRecognitions.Outcome.Hit)
        assertEquals("char_a", host.session.targetAssignment("L1")?.name)
    }

    // ───────────────────── Uncached 的顺序规则 ─────────────────────

    @Test
    fun `decideUncached 命中时清快照并消耗重扫配额`() {
        val host = FakeHost()
        host.data = OperatorDataset.buildOperatorSelectionData(
            OperatorDataset.parse(
                """
                {"items":{},"operators":{"char_a":{"names":{"zh_cn":"A"}}},
                 "location_order":["L1"],
                 "locations":{"L1":{"names":{"zh_cn":"L1"},"target_operators":[],"restore_operators":[]}}}
                """.trimIndent(),
            ),
        )
        // 当前派驻 char_a 是已知干员，但不在快照里
        host.owned = emptySet()
        // OCR 文本必须与干员的中文名一致才可能匹配（char_a 的 zh_cn 名是 "A"）
        host.currentItems = listOf(item("A"))

        val outcome = OperatorRecognitions(host).decideUncached(param(usage = "all", location = "global"))
        assertTrue(outcome is OperatorRecognitions.Outcome.Hit)
        // 配额已被消耗
        assertFalse(host.session.claimCacheRescan())
    }

    @Test
    fun `decideUncached 已完整扫描过时拒绝但仍消耗配额`() {
        val host = FakeHost()
        host.data = OperatorDataset.buildOperatorSelectionData(
            OperatorDataset.parse(
                """
                {"items":{},"operators":{"char_a":{"names":{"zh_cn":"A"}}},
                 "location_order":["L1"],
                 "locations":{"L1":{"names":{"zh_cn":"L1"},"target_operators":[],"restore_operators":[]}}}
                """.trimIndent(),
            ),
        )
        host.owned = emptySet()
        // OCR 文本必须与干员的中文名一致才可能匹配（char_a 的 zh_cn 名是 "A"）
        host.currentItems = listOf(item("A"))
        host.session.markRefreshed() // 本任务已全量扫过

        val outcome = OperatorRecognitions(host).decideUncached(param(usage = "all", location = "global"))
        assertEquals(OperatorRecognitions.Outcome.Miss, outcome)
        // claim 在 Refreshed 之前 -> 配额已用掉
        assertFalse(host.session.claimCacheRescan())
    }

    // ───────────────────── CacheReady 的顺序规则 ─────────────────────

    @Test
    fun `decideCacheReady 不 ready 时也消耗提示配额`() {
        val host = FakeHost()
        val outcome = OperatorRecognitions(host).decideCacheReady(
            param(usage = "all", location = "global"),
            OperatorRecognitions.CacheStatus(ready = false, updatedAt = "t"),
        )
        assertEquals(OperatorRecognitions.Outcome.Miss, outcome)
        // 提示配额已被消耗（先 claim 再判 ready）
        assertFalse(host.session.claimCacheNotice())
        assertTrue(host.logs.any { it.contains("干员缓存状态") })
    }

    @Test
    fun `decideCacheReady ready 时命中并返回 cache_ready`() {
        val host = FakeHost()
        val outcome = OperatorRecognitions(host).decideCacheReady(
            param(usage = "all", location = "global"),
            OperatorRecognitions.CacheStatus(ready = true, updatedAt = "t"),
        )
        assertEquals("cache_ready", (outcome as OperatorRecognitions.Outcome.Hit).detail)
        assertNull((outcome).box)
    }

    // ───────────────────── ListBottom 状态机 ─────────────────────

    @Test
    fun `decideListBottom 首帧不算到底 第二帧同签名才算`() {
        val host = FakeHost()
        host.resolved = selectionParam(candidates = listOf(candidate("char_a")))
        host.owned = setOf("char_a")
        val rec = OperatorRecognitions(host)
        val p = param(result = "scan_done")

        assertEquals(OperatorRecognitions.Outcome.Miss, rec.decideListBottom(p, listOf(item("char_a"))))
        val second = rec.decideListBottom(p, listOf(item("char_a")))
        assertTrue(second is OperatorRecognitions.Outcome.Hit)
        assertEquals("scan_done", (second as OperatorRecognitions.Outcome.Hit).detail)
        // 命中后状态被删除
        assertNull(host.scanStates.copyOf(OperatorScan.scanStateKey(host.uid, "cache", "target", "L1")))
    }

    @Test
    fun `decideListBottom 签名变化时继续滑`() {
        val host = FakeHost()
        host.resolved = selectionParam(candidates = listOf(candidate("char_a"), candidate("char_b")))
        host.owned = setOf("char_a", "char_b")
        val rec = OperatorRecognitions(host)
        val p = param(result = "scan_done")

        assertEquals(OperatorRecognitions.Outcome.Miss, rec.decideListBottom(p, listOf(item("char_a"))))
        // 第二帧多了 char_b -> 签名不同 -> 还没到底
        assertEquals(OperatorRecognitions.Outcome.Miss, rec.decideListBottom(p, listOf(item("char_a"), item("char_b"))))
    }

    @Test
    fun `decideListBottom retry 配额用尽时转成错误`() {
        val host = FakeHost()
        host.resolved = selectionParam(candidates = listOf(candidate("char_a")))
        host.owned = setOf("char_a")
        // 先手动把配额用掉
        host.session.claimRetry("target", "L1")

        val rec = OperatorRecognitions(host)
        val p = param(result = "retry")
        rec.decideListBottom(p, listOf(item("char_a")))
        val second = rec.decideListBottom(p, listOf(item("char_a")))

        // claim 失败 -> hasCandidate=false + error -> bottomResult 判 miss
        assertEquals(OperatorRecognitions.Outcome.Miss, second)
        val state = host.scanStates.copyOf(OperatorScan.scanStateKey(host.uid, "cache", "target", "L1"))
        assertTrue(state?.error?.isNotEmpty() == true)
    }

    @Test
    fun `decideListBottom OCR 失败写入 error 并结束状态机`() {
        val host = FakeHost()
        host.resolved = selectionParam(candidates = listOf(candidate("char_a")))
        host.lastErr = "ocr boom"
        val rec = OperatorRecognitions(host)
        val p = param()

        assertEquals(OperatorRecognitions.Outcome.Miss, rec.decideListBottom(p, null))
        val state = host.scanStates.copyOf(OperatorScan.scanStateKey(host.uid, "cache", "target", "L1"))
        assertTrue(state?.completed == true)
        assertEquals("ocr boom", state?.error)
    }

    // ───────────────────── ScanOutcome ─────────────────────

    @Test
    fun `decideScanOutcome 只在已完成且匹配时命中`() {
        val host = FakeHost()
        val rec = OperatorRecognitions(host)
        val p = param(result = "not_found", usage = "target")
        val key = OperatorScan.scanStateKey(host.uid, "cache", "target", "L1")

        // 没有状态 -> miss
        assertEquals(OperatorRecognitions.Outcome.Miss, rec.decideScanOutcome(p))

        // 已完成、无 error、无候选 -> not_found 命中
        host.scanStates.put(OperatorScan.ScanState(key).copy(completed = true, hasCandidate = false))
        val outcome = rec.decideScanOutcome(p)
        assertTrue(outcome is OperatorRecognitions.Outcome.Hit)
        assertTrue((outcome as OperatorRecognitions.Outcome.Hit).detail.contains("no_owned_candidate"))
        assertNull(host.scanStates.copyOf(key))
    }

    @Test
    fun `decideScanOutcome error 分支要求真的有 error`() {
        val host = FakeHost()
        val rec = OperatorRecognitions(host)
        val key = OperatorScan.scanStateKey(host.uid, "cache", "target", "L1")
        val p = param(result = "error", usage = "target")

        host.scanStates.put(OperatorScan.ScanState(key).copy(completed = true))
        assertEquals(OperatorRecognitions.Outcome.Miss, rec.decideScanOutcome(p))

        host.scanStates.put(OperatorScan.ScanState(key).copy(completed = true, error = "boom"))
        assertTrue(rec.decideScanOutcome(p) is OperatorRecognitions.Outcome.Hit)
    }

    // ───────────────────── Conflict ─────────────────────

    @Test
    fun `decideConflict 参数与实际判定不一致时不命中`() {
        val host = FakeHost()
        host.file = OperatorDataset.parse(
            """
            {"items":{},"operators":{"char_a":{"names":{"zh_cn":"A"}}},
             "location_order":["L1"],
             "locations":{"L1":{"names":{"zh_cn":"据点一"},"target_operators":[],"restore_operators":[]}}}
            """.trimIndent(),
        )
        host.session.registerLocation("L1")
        val rec = OperatorRecognitions(host)
        val items = listOf(item("派驻在据点一"))

        // 来源在启用集合 -> managed=true；声明 protected 就应 miss
        assertEquals(
            OperatorRecognitions.Outcome.Miss,
            rec.decideConflict(OperatorRecognitions.ConflictParam("protected", "target", "L1"), items),
        )
        val hit = rec.decideConflict(OperatorRecognitions.ConflictParam("managed", "target", "L1"), items)
        assertTrue(hit is OperatorRecognitions.Outcome.Hit)
        val detail = (hit as OperatorRecognitions.Outcome.Hit).detail
        assertTrue(detail.contains("\"source_location\":\"L1\""))
        assertTrue(detail.contains("\"source_managed\":true"))
    }

    @Test
    fun `decideConflict 来源不在启用集合时 managed 声明应 miss`() {
        val host = FakeHost()
        host.file = OperatorDataset.parse(
            """
            {"items":{},"operators":{"char_a":{"names":{"zh_cn":"A"}}},
             "location_order":["L1"],
             "locations":{"L1":{"names":{"zh_cn":"据点一"},"target_operators":[],"restore_operators":[]}}}
            """.trimIndent(),
        )
        // 没有 register L1
        val rec = OperatorRecognitions(host)
        val items = listOf(item("派驻在据点一"))

        assertEquals(
            OperatorRecognitions.Outcome.Miss,
            rec.decideConflict(OperatorRecognitions.ConflictParam("managed", "target", "L1"), items),
        )
        assertTrue(
            rec.decideConflict(OperatorRecognitions.ConflictParam("protected", "target", "L1"), items)
                is OperatorRecognitions.Outcome.Hit,
        )
    }

    @Test
    fun `decideConflict 数据不可用时 miss`() {
        val host = FakeHost()
        host.file = null
        assertEquals(
            OperatorRecognitions.Outcome.Miss,
            OperatorRecognitions(host).decideConflict(
                OperatorRecognitions.ConflictParam("managed", "target", "L1"),
                listOf(item("x")),
            ),
        )
    }
}
