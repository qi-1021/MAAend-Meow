package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务级会话状态测试（对齐上游 outposttrading/operator/session.go）。
 *
 * 重点：懒建的触发条件、7 种 operation 的精确副作用、claim 的一次性语义、
 * snapshot 必须是深拷贝（纯算法层要能随便改而不污染全局）。
 */
class OperatorSessionTest {

    private class FakeHost(var uid: String = "0123456789abcdef") : OperatorSession.Host {
        val persisted = mutableListOf<Triple<String, String, Boolean>>()
        var prosperityMax: Set<String> = emptySet()
        var persistResult: Boolean? = true

        override fun currentUid(): String = uid
        override fun loadProsperityMaxLocations(uid: String): Set<String> = prosperityMax
        override fun persistProsperityStatus(uid: String, location: String, reached: Boolean): Boolean? {
            persisted += Triple(uid, location, reached)
            return persistResult
        }
    }

    private fun candidate(name: String) = OperatorDataset.OperatorCandidate(name, listOf(name), 0, 0, 0)

    private fun session(host: FakeHost = FakeHost()) = OperatorSession(host) to host

    private fun param(
        operation: String,
        mode: String = "",
        location: String = "",
        usage: String = "",
        active: Boolean = false,
        changed: Boolean = false,
        prosperityMax: Boolean = false,
    ) = OperatorSession.ActionParam(operation, mode, location, usage, active, changed, prosperityMax)

    // ───────────────────── 参数解析 ─────────────────────

    @Test
    fun `parseParam 按 operation 校验必填字段`() {
        assertEquals(
            "reset",
            OperatorSession.parseParam(mapOf("operation" to " reset ", "mode" to "refresh"))?.operation,
        )
        // reset 的 mode 必须是 cache/refresh
        assertNull(OperatorSession.parseParam(mapOf("operation" to "reset", "mode" to "bogus")))
        // 其余 operation 必须有 location
        assertNull(OperatorSession.parseParam(mapOf("operation" to "register")))
        assertNull(OperatorSession.parseParam(mapOf("operation" to "enter_location", "location" to "  ")))
        // exclude_selected 还要求 usage
        assertNull(
            OperatorSession.parseParam(mapOf("operation" to "exclude_selected", "location" to "L1", "usage" to "all")),
        )
        // 未知 operation 不在这里拦，交给 run 判失败
        assertEquals(
            "bogus",
            OperatorSession.parseParam(mapOf("operation" to "bogus", "location" to "L1"))?.operation,
        )
    }

    @Test
    fun `parseParam 对非对象返回 null`() {
        assertNull(OperatorSession.parseParam(null))
        assertNull(OperatorSession.parseParam("不是对象"))
    }

    // ───────────────────── 懒建 ─────────────────────

    @Test
    fun `懒建时强制 cache 模式并从磁盘播种满级据点`() {
        val host = FakeHost()
        host.prosperityMax = setOf("L1")
        val (s, _) = session(host)

        // 首次访问触发懒建
        assertEquals(OperatorSession.MODE_CACHE, s.mode())
        assertEquals(setOf("L1"), s.snapshot().outpostProsperityMaxLocations)
    }

    @Test
    fun `UID 变化时重建会话并丢掉进度`() {
        val host = FakeHost()
        val (s, _) = session(host)
        s.registerLocation("L1")
        s.setTargetAssignment("L1", candidate("A"))
        assertEquals(setOf("L1"), s.snapshot().activeLocations)

        // 换号
        host.uid = "fedcba9876543210"
        assertTrue(s.snapshot().activeLocations.isEmpty())
        assertTrue(s.snapshot().targetAssignments.isEmpty())
    }

    // ───────────────────── 七种 operation ─────────────────────

    @Test
    fun `reset 用参数模式重建并清空进度`() {
        val host = FakeHost()
        host.prosperityMax = setOf("L9")
        val (s, _) = session(host)
        s.registerLocation("L1")
        s.run(param("reset", mode = "refresh"))

        assertEquals(OperatorSession.MODE_REFRESH, s.mode())
        assertTrue(s.snapshot().activeLocations.isEmpty())
        assertEquals(setOf("L9"), s.snapshot().outpostProsperityMaxLocations)
        assertTrue(s.lastResetClearedScanStates)
    }

    @Test
    fun `register 在 active 为假时什么都不做`() {
        val (s, _) = session()
        s.run(param("register", location = "L1", active = false))
        assertTrue(s.snapshot().activeLocations.isEmpty())
        s.run(param("register", location = "L1", active = true))
        assertEquals(setOf("L1"), s.snapshot().activeLocations)
    }

    @Test
    fun `enter_location 写内存集合并写盘`() {
        val host = FakeHost()
        host.persisted.clear()
        val (s, _) = session(host)
        s.run(param("enter_location", location = "L1", prosperityMax = true))
        assertEquals(setOf("L1"), s.snapshot().outpostProsperityMaxLocations)
        assertEquals(listOf(Triple(host.uid, "L1", true)), host.persisted)
        // 首次进入返回 true，重复进入返回 false
        assertFalse(s.enterLocation("L1"))
    }

    @Test
    fun `enter_location 写盘失败不影响动作成功`() {
        val host = FakeHost()
        host.persistResult = null
        val (s, _) = session(host)
        assertEquals(OperatorSession.SessionOutcome.Ok, s.run(param("enter_location", location = "L1")))
    }

    @Test
    fun `complete_target 只读不改状态`() {
        val (s, _) = session()
        s.setTargetAssignment("L1", candidate("A"))
        val outcome = s.run(param("complete_target", location = "L1", changed = true))
        assertTrue(outcome is OperatorSession.SessionOutcome.Assignment)
        assertEquals(true, (outcome as OperatorSession.SessionOutcome.Assignment).changed)
        // 状态不变
        assertEquals("A", s.targetAssignment("L1")?.name)
    }

    @Test
    fun `complete_target 无分配时失败`() {
        val (s, _) = session()
        assertTrue(s.run(param("complete_target", location = "L9")) is OperatorSession.SessionOutcome.Failed)
    }

    @Test
    fun `complete_restore 锁定分配并清掉 target 与计划`() {
        val (s, _) = session()
        s.setPlannedRestore("L1", candidate("A"))
        s.setTargetAssignment("L1", candidate("B"))
        val outcome = s.run(param("complete_restore", location = "L1"))
        assertTrue(outcome is OperatorSession.SessionOutcome.Assignment)

        val snap = s.snapshot()
        assertEquals("A", snap.lockedRestoreAssignments["L1"]?.name)
        assertEquals(setOf("L1"), snap.completedRestoreLocations)
        assertNull(snap.targetAssignments["L1"])
    }

    @Test
    fun `complete_restore 无计划时失败且不改状态`() {
        val (s, _) = session()
        s.setTargetAssignment("L1", candidate("B"))
        assertTrue(s.run(param("complete_restore", location = "L1")) is OperatorSession.SessionOutcome.Failed)
        // target 仍在
        assertEquals("B", s.targetAssignment("L1")?.name)
        assertTrue(s.snapshot().lockedRestoreAssignments.isEmpty())
    }

    @Test
    fun `skip_restore 不写 locked`() {
        val (s, _) = session()
        s.setPlannedRestore("L1", candidate("A"))
        s.run(param("skip_restore", location = "L1"))
        val snap = s.snapshot()
        assertEquals(setOf("L1"), snap.completedRestoreLocations)
        assertTrue(snap.lockedRestoreAssignments.isEmpty())
        assertNull(snap.targetAssignments["L1"])
    }

    @Test
    fun `exclude_selected 两条路径各自取对应计划并拉黑`() {
        val (s, _) = session()
        s.setTargetAssignment("L1", candidate("A"))
        s.setPlannedRestore("L2", candidate("B"))

        val targetOutcome = s.run(param("exclude_selected", location = "L1", usage = "target"))
        assertEquals("A", (targetOutcome as OperatorSession.SessionOutcome.ConflictExcluded).candidate.name)

        val restoreOutcome = s.run(param("exclude_selected", location = "L2", usage = "restore"))
        assertEquals("B", (restoreOutcome as OperatorSession.SessionOutcome.ConflictExcluded).candidate.name)

        val snap = s.snapshot()
        assertEquals(setOf("A", "B"), snap.excludedOperators)
        assertTrue(snap.targetAssignments.isEmpty())
    }

    @Test
    fun `exclude_selected 没有选中项时失败`() {
        val (s, _) = session()
        assertTrue(
            s.run(param("exclude_selected", location = "L1", usage = "target")) is OperatorSession.SessionOutcome.Failed,
        )
    }

    @Test
    fun `未知 operation 判失败`() {
        val (s, _) = session()
        assertTrue(s.run(param("nonsense", location = "L1")) is OperatorSession.SessionOutcome.Failed)
    }

    // ───────────────────── claim 与快照 ─────────────────────

    @Test
    fun `claimRetry 同用途同据点只成功一次`() {
        val (s, _) = session()
        assertTrue(s.claimRetry("target", "L1"))
        assertFalse(s.claimRetry("target", "L1"))
        // 换个 location 或 usage 都是新的配额
        assertTrue(s.claimRetry("target", "L2"))
        assertTrue(s.claimRetry("restore", "L1"))
    }

    @Test
    fun `claimCacheRescan 等价于 claimRetry all global`() {
        val (s, _) = session()
        assertTrue(s.claimCacheRescan())
        assertFalse(s.claimCacheRescan())
        assertFalse(s.claimRetry("all", "global"))
    }

    @Test
    fun `claimCacheNotice 每任务只成功一次`() {
        val (s, _) = session()
        assertTrue(s.claimCacheNotice())
        assertFalse(s.claimCacheNotice())
        // reset 后重新可提示
        s.run(param("reset", mode = "cache"))
        assertTrue(s.claimCacheNotice())
    }

    @Test
    fun `refreshed 标记与查询`() {
        val (s, _) = session()
        assertFalse(s.isRefreshed())
        s.markRefreshed()
        assertTrue(s.isRefreshed())
        s.run(param("reset", mode = "refresh"))
        assertFalse(s.isRefreshed())
    }

    @Test
    fun `snapshot 是时点副本 后续全局变化不影响已取的快照`() {
        val (s, _) = session()
        s.registerLocation("L1")
        s.setTargetAssignment("L1", candidate("A"))

        val snap = s.snapshot()

        // 之后继续改全局
        s.registerLocation("L2")
        s.setTargetAssignment("L1", candidate("B"))

        // 已取的快照必须保持原样（纯算法层拿到快照后不会被后续变更穿透）
        assertEquals(setOf("L1"), snap.activeLocations)
        assertEquals("A", snap.targetAssignments["L1"]?.name)

        // 新快照能看到变化
        val fresh = s.snapshot()
        assertEquals(setOf("L1", "L2"), fresh.activeLocations)
        assertEquals("B", fresh.targetAssignments["L1"]?.name)
    }
}
