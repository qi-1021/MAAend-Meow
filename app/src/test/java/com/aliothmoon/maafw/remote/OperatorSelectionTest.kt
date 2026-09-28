package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.OperatorDataset.OperatorCandidate
import com.aliothmoon.maafw.remote.OperatorDataset.OperatorCandidateGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 干员选择纯算法测试（对齐上游 outposttrading/operator/selection.go）。
 *
 * 重点不在「能选出人」，而在三处极易写错、且错了只表现为「选人变了」的行为：
 *  1. DFS 的**平局方向**（新地区优先 = 先探索分配、后探索跳过）
 *  2. `isBetterRestorePlan` 的字典序与「集合不同不比 cost」
 *  3. `OutpostProsperityMaxBonusTier` 的口径切换
 */
class OperatorSelectionTest {

    private fun candidate(
        name: String,
        bonusTier: Int = 0,
        prosperityTier: Int = 0,
        priority: Int = 0,
    ) = OperatorCandidate(
        name = name,
        expected = listOf(name),
        priority = priority,
        bonusTier = bonusTier,
        outpostProsperityMaxBonusTier = prosperityTier,
    )

    private fun group(location: String, vararg candidates: OperatorCandidate) =
        OperatorCandidateGroup(location, candidates.toList())

    private fun baseParam(
        usage: String = OperatorSelection.USAGE_RESTORE,
        location: String = "L1",
        candidates: List<OperatorCandidate> = emptyList(),
        targetCandidatesByLocation: Map<String, List<OperatorCandidate>> = emptyMap(),
        restoreGroups: List<OperatorCandidateGroup> = emptyList(),
        activeLocations: Set<String> = setOf("L1"),
        completedRestoreLocations: Set<String> = emptySet(),
        targetAssignments: Map<String, OperatorCandidate> = emptyMap(),
        lockedRestoreAssignments: Map<String, OperatorCandidate> = emptyMap(),
        excludedOperators: Set<String> = emptySet(),
        outpostProsperityMaxLocations: Set<String> = emptySet(),
    ) = OperatorSelection.SelectionParam(
        usage = usage,
        location = location,
        candidates = candidates,
        targetCandidatesByLocation = targetCandidatesByLocation,
        restoreGroups = restoreGroups,
        activeLocations = activeLocations,
        completedRestoreLocations = completedRestoreLocations,
        targetAssignments = targetAssignments,
        lockedRestoreAssignments = lockedRestoreAssignments,
        excludedOperators = excludedOperators,
        outpostProsperityMaxLocations = outpostProsperityMaxLocations,
    )

    // ───────────────────── 档位 ─────────────────────

    @Test
    fun `bestBonusTierCandidates 取最小档并保持遭遇顺序`() {
        val a = candidate("A", bonusTier = 1)
        val b = candidate("B", bonusTier = 0)
        val c = candidate("C", bonusTier = 0)
        val d = candidate("D", bonusTier = 2)
        val best = OperatorSelection.bestBonusTierCandidates(listOf(a, b, c, d), outpostProsperityMax = false)
        // 最小档是 0；同档保持输入顺序（B 在 C 前）
        assertEquals(listOf("B", "C"), best.map { it.name })
    }

    @Test
    fun `bestBonusTierCandidates 按发展值满级口径切换字段`() {
        val normal = candidate("A", bonusTier = 0, prosperityTier = 9)
        val maxScenario = candidate("B", bonusTier = 9, prosperityTier = 0)

        // 未满级：用 bonusTier -> A(0) 更优
        assertEquals(
            listOf("A"),
            OperatorSelection.bestBonusTierCandidates(listOf(normal, maxScenario), outpostProsperityMax = false).map { it.name },
        )
        // 已满级：用 outpostProsperityMaxBonusTier -> B(0) 更优
        assertEquals(
            listOf("B"),
            OperatorSelection.bestBonusTierCandidates(listOf(normal, maxScenario), outpostProsperityMax = true).map { it.name },
        )
    }

    @Test
    fun `bestBonusTierCandidates 空输入返回空`() {
        assertEquals(emptyList<OperatorCandidate>(), OperatorSelection.bestBonusTierCandidates(emptyList(), false))
    }

    // ───────────────────── 完美候选 ─────────────────────

    @Test
    fun `preferredTargetCandidates 完美候选优先于同档非完美`() {
        val perfect = candidate("A", bonusTier = 0)
        val plain = candidate("B", bonusTier = 0)
        // A 同时是该据点的恢复候选（完美），B 不是
        val result = OperatorSelection.preferredTargetCandidates(
            listOf(perfect, plain),
            owned = setOf("A", "B"),
            location = "L1",
            restoreGroups = listOf(group("L1", candidate("A"))),
            outpostProsperityMaxLocations = emptySet(),
        )
        assertEquals(listOf("A"), result.map { it.name })
    }

    @Test
    fun `preferredTargetCandidates 无完美候选时回退整档`() {
        val a = candidate("A", bonusTier = 0)
        val b = candidate("B", bonusTier = 0)
        val result = OperatorSelection.preferredTargetCandidates(
            listOf(a, b),
            owned = setOf("A", "B"),
            location = "L1",
            restoreGroups = listOf(group("L1", candidate("Z"))),
            outpostProsperityMaxLocations = emptySet(),
        )
        assertEquals(listOf("A", "B"), result.map { it.name })
    }

    @Test
    fun `preferredTargetCandidates 只保留拥有的干员`() {
        val a = candidate("A")
        val b = candidate("B")
        val result = OperatorSelection.preferredTargetCandidates(
            listOf(a, b), owned = setOf("B"), location = "L1",
            restoreGroups = emptyList(), outpostProsperityMaxLocations = emptySet(),
        )
        assertEquals(listOf("B"), result.map { it.name })
    }

    @Test
    fun `restoreCandidateNames 找不到据点返回 null`() {
        assertNull(OperatorSelection.restoreCandidateNames(listOf(group("L1", candidate("A"))), "L9"))
        assertEquals(
            setOf("A"),
            OperatorSelection.restoreCandidateNames(listOf(group("L1", candidate("A"))), "L1"),
        )
    }

    // ───────────────────── DFS：互斥、可跳过、平局方向 ─────────────────────

    @Test
    fun `DFS 同一干员不会分配给两个据点`() {
        val a = candidate("A")
        val plan = OperatorSelection.buildRestoreAssignmentPlan(
            listOf(group("L1", a), group("L2", a)),
            owned = setOf("A"),
        )
        assertEquals(1, plan.assigned)
        // 只能给一个；靠前的据点先探索 -> 给 L1
        assertEquals(setOf("L1"), plan.assignments.keys)
        assertEquals("A", plan.assignments["L1"]?.name)
    }

    @Test
    fun `DFS 平局时保留靠前据点 即新地区优先`() {
        val a = candidate("A")
        // L1 与 L2 只有一个干员可用：{L1} 与 {L2} 两个方案的各项指标完全相同
        val plan = OperatorSelection.buildRestoreAssignmentPlanWithPreferencesAndTargets(
            listOf(group("L1", a), group("L2", a)),
            owned = setOf("A"),
            preferred = emptyMap(),
            reusableTargets = emptyMap(),
        )
        // 集合不同 -> isBetterRestorePlan 返回 false -> 保留先到的 {L1}
        assertEquals(setOf("L1"), plan.assignments.keys)
    }

    @Test
    fun `DFS 覆盖率优先于成本`() {
        val a = candidate("A", priority = 9)
        val b = candidate("B", priority = 0)
        // 分配两个据点（assigned=2, cost=9）应胜过只分配一个（assigned=1, cost=0）
        val plan = OperatorSelection.buildRestoreAssignmentPlan(
            listOf(group("L1", a), group("L2", b)),
            owned = setOf("A", "B"),
        )
        assertEquals(2, plan.assigned)
        assertEquals(9, plan.totalCost)
    }

    @Test
    fun `DFS 沿用数优先于总成本`() {
        val cheap = candidate("A", priority = 0)
        val costly = candidate("B", priority = 5)
        val plan = OperatorSelection.buildRestoreAssignmentPlanWithPreferencesAndTargets(
            listOf(group("L1", cheap, costly)),
            owned = setOf("A", "B"),
            preferred = mapOf("L1" to costly),
            reusableTargets = emptyMap(),
        )
        // 沿用 B 得 kept=1（cost 5），沿用 A 得 kept=0（cost 0）；kept 优先 -> 选 B
        assertEquals("B", plan.assignments["L1"]?.name)
        assertEquals(1, plan.keptTargets)
    }

    @Test
    fun `DFS 可复用数优先于总成本`() {
        val cheap = candidate("A", priority = 0)
        val costly = candidate("B", priority = 5)
        val plan = OperatorSelection.buildRestoreAssignmentPlanWithPreferencesAndTargets(
            listOf(group("L1", cheap, costly)),
            owned = setOf("A", "B"),
            preferred = emptyMap(),
            reusableTargets = mapOf("L1" to setOf("B")),
        )
        assertEquals("B", plan.assignments["L1"]?.name)
        assertEquals(1, plan.reusableTargets)
    }

    @Test
    fun `DFS 集合相同时才比总成本`() {
        val cheap = candidate("A", priority = 0)
        val costly = candidate("B", priority = 5)
        val plan = OperatorSelection.buildRestoreAssignmentPlan(
            listOf(group("L1", costly, cheap)),
            owned = setOf("A", "B"),
        )
        // 同一据点，集合都是 {L1} -> 比 cost -> 选 priority 小的 A
        assertEquals("A", plan.assignments["L1"]?.name)
    }

    @Test
    fun `DFS 空据点集合或无人可用时返回空计划`() {
        val empty = OperatorSelection.buildRestoreAssignmentPlan(emptyList(), setOf("A"))
        assertTrue(empty.assignments.isEmpty())
        assertEquals(0, empty.assigned)

        val nobody = OperatorSelection.buildRestoreAssignmentPlan(
            listOf(group("L1", candidate("A"))),
            owned = emptySet(),
        )
        assertTrue(nobody.assignments.isEmpty())
    }

    @Test
    fun `isBetterRestorePlan 集合不同时不比成本`() {
        val a = candidate("A")
        val b = candidate("B")
        val cheapDifferentSet = OperatorSelection.RestorePlan(
            assignments = mapOf("L2" to a), assigned = 1, totalCost = 0,
        )
        val costlySameSet = OperatorSelection.RestorePlan(
            assignments = mapOf("L1" to a), assigned = 1, totalCost = 99,
        )
        // 各项指标相同但集合不同 -> false（保留 incumbent）
        assertEquals(false, OperatorSelection.isBetterRestorePlan(cheapDifferentSet, costlySameSet))
        // 反过来也 false
        assertEquals(false, OperatorSelection.isBetterRestorePlan(costlySameSet, cheapDifferentSet))
        // 同一集合内才比成本：L1 的 newcomers 成本更低则更优
        val cheaperSameSet = OperatorSelection.RestorePlan(
            assignments = mapOf("L1" to b), assigned = 1, totalCost = 1,
        )
        assertEquals(true, OperatorSelection.isBetterRestorePlan(cheaperSameSet, costlySameSet))
    }

    // ───────────────────── target 路径 ─────────────────────

    @Test
    fun `target 返回最高档且最多一人`() {
        val param = baseParam(
            usage = OperatorSelection.USAGE_TARGET,
            location = "L1",
            candidates = listOf(candidate("A", bonusTier = 0), candidate("B", bonusTier = 1)),
            activeLocations = setOf("L1"),
        )
        val result = OperatorSelection.candidatesForCurrentSelection(param, setOf("A", "B"))
        assertEquals(listOf("A"), result.map { it.name })
    }

    @Test
    fun `target 剔除冲突拉黑的干员`() {
        val param = baseParam(
            usage = OperatorSelection.USAGE_TARGET,
            location = "L1",
            candidates = listOf(candidate("A", bonusTier = 0), candidate("B", bonusTier = 1)),
            excludedOperators = setOf("A"),
        )
        val result = OperatorSelection.candidatesForCurrentSelection(param, setOf("A", "B"))
        assertEquals(listOf("B"), result.map { it.name })
    }

    @Test
    fun `target 剔除其它据点已锁定的干员但保留本据点的`() {
        val param = baseParam(
            usage = OperatorSelection.USAGE_TARGET,
            location = "L1",
            candidates = listOf(candidate("A", bonusTier = 0), candidate("B", bonusTier = 1)),
            lockedRestoreAssignments = mapOf("L2" to candidate("A")),
        )
        val result = OperatorSelection.candidatesForCurrentSelection(param, setOf("A", "B"))
        assertEquals(listOf("B"), result.map { it.name })

        // 本据点自己的锁定不算冲突
        val sameLocation = param.copy(lockedRestoreAssignments = mapOf("L1" to candidate("A")))
        assertEquals(
            listOf("A"),
            OperatorSelection.candidatesForCurrentSelection(sameLocation, setOf("A", "B")).map { it.name },
        )
    }

    @Test
    fun `target 在最高档多人时模拟全局恢复后只留一人`() {
        val a = candidate("A", bonusTier = 0)
        val b = candidate("B", bonusTier = 0)
        val param = baseParam(
            usage = OperatorSelection.USAGE_TARGET,
            location = "L1",
            candidates = listOf(a, b),
            targetCandidatesByLocation = mapOf("L1" to listOf(a, b)),
            restoreGroups = listOf(group("L1", a, b), group("L2", a)),
            activeLocations = setOf("L1", "L2"),
        )
        val result = OperatorSelection.candidatesForCurrentSelection(param, setOf("A", "B"))
        assertEquals(1, result.size)
        // A 同时是 L2 的恢复候选，留给 L2 更划算 -> L1 选 B（覆盖率优先）
        assertEquals("B", result.first().name)
    }

    // ───────────────────── restore 路径 ─────────────────────

    @Test
    fun `restore 已完成恢复时直接返回锁定的人`() {
        val param = baseParam(
            usage = OperatorSelection.USAGE_RESTORE,
            location = "L1",
            lockedRestoreAssignments = mapOf("L1" to candidate("A")),
        )
        // 即使账号不拥有任何人也要返回锁定结果
        assertEquals(listOf("A"), OperatorSelection.candidatesForCurrentSelection(param, emptySet()).map { it.name })
    }

    @Test
    fun `restore 从全局分配里取本据点的人`() {
        val a = candidate("A")
        val param = baseParam(
            usage = OperatorSelection.USAGE_RESTORE,
            location = "L1",
            restoreGroups = listOf(group("L1", a)),
            activeLocations = setOf("L1"),
        )
        assertEquals(listOf("A"), OperatorSelection.candidatesForCurrentSelection(param, setOf("A")).map { it.name })
    }

    @Test
    fun `restore 忽略已完成恢复的据点`() {
        val a = candidate("A")
        val param = baseParam(
            usage = OperatorSelection.USAGE_RESTORE,
            location = "L1",
            restoreGroups = listOf(group("L1", a), group("L2", a)),
            activeLocations = setOf("L1", "L2"),
            completedRestoreLocations = setOf("L2"),
        )
        // L2 已完成，只剩 L1 参与分配 -> A 给 L1
        assertEquals("A", OperatorSelection.candidatesForCurrentSelection(param, setOf("A")).first().name)
    }

    @Test
    fun `usage 为 all 时永远返回空`() {
        val param = baseParam(
            usage = OperatorSelection.USAGE_ALL,
            location = "global",
            restoreGroups = listOf(group("L1", candidate("A"))),
            activeLocations = setOf("L1"),
        )
        assertEquals(emptyList<OperatorCandidate>(), OperatorSelection.candidatesForCurrentSelection(param, setOf("A")))
    }

    // ───────────────────── 偏好与模拟不写回 ─────────────────────

    @Test
    fun `restorePlanPreferences 本轮售卖结果覆盖默认偏好`() {
        val defaultBest = candidate("A")
        val actuallySold = candidate("B")
        val param = baseParam(
            location = "L1",
            targetCandidatesByLocation = mapOf("L1" to listOf(defaultBest, actuallySold)),
            activeLocations = setOf("L1"),
            targetAssignments = mapOf("L1" to actuallySold),
        )
        val (preferred, reusable) = OperatorSelection.restorePlanPreferences(param, setOf("A", "B"))
        assertEquals("B", preferred["L1"]?.name)
        // reusable 是整档名字集合，不被 targetAssignments 影响
        assertEquals(setOf("A", "B"), reusable["L1"])
    }

    @Test
    fun `selectTargetCandidateForRestorePlan 不修改入参快照`() {
        val a = candidate("A", bonusTier = 0)
        val b = candidate("B", bonusTier = 0)
        val param = baseParam(
            usage = OperatorSelection.USAGE_TARGET,
            location = "L1",
            candidates = listOf(a, b),
            targetCandidatesByLocation = mapOf("L1" to listOf(a, b)),
            restoreGroups = listOf(group("L2", a)),
            activeLocations = setOf("L1", "L2"),
        )
        val before = param.targetAssignments
        OperatorSelection.selectTargetCandidateForRestorePlan(param, setOf("A", "B"), listOf(a, b))
        // 必须是副本上模拟，入参的 map 引用与内容都不能变
        assertEquals(before, param.targetAssignments)
        assertTrue(param.targetAssignments.isEmpty())
    }

    @Test
    fun `equivalentTargetCandidates 保留整档而不单选`() {
        val a = candidate("A", bonusTier = 0)
        val b = candidate("B", bonusTier = 0)
        val param = baseParam(
            location = "L1",
            candidates = listOf(a, b),
            targetCandidatesByLocation = mapOf("L1" to listOf(a, b)),
            activeLocations = setOf("L1"),
        )
        val result = OperatorSelection.equivalentTargetCandidatesForOwnership(
            param, OperatorSelection.Ownership(setOf("A", "B")),
        )
        assertEquals(listOf("A", "B"), result.map { it.name })
    }

    @Test
    fun `filterOwnedCandidates 空集合短路`() {
        assertEquals(emptyList<OperatorCandidate>(), OperatorSelection.filterOwnedCandidates(listOf(candidate("A")), emptySet()))
        assertEquals(emptyList<OperatorCandidate>(), OperatorSelection.filterOwnedCandidates(emptyList(), setOf("A")))
    }
}
