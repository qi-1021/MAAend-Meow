package com.aliothmoon.maafw.remote

private typealias Candidate = OperatorDataset.OperatorCandidate
private typealias CandidateGroup = OperatorDataset.OperatorCandidateGroup

/**
 * 据点交易的**干员选择纯算法**（对齐上游 `outposttrading/operator/selection.go`）。
 *
 * 这个文件不识别、不点击、不碰流水线，只做「给定数据与快照，算出该用谁」。
 * 最容易写错的是三处，实现时逐条对照：
 *
 *  1. **全局恢复分配**（[buildRestoreAssignmentPlan]）是一个可跳过据点的 DFS：
 *     每个据点的分支 = {分配任一可用候选} ∪ {跳过}，跳过分支排在候选循环**之后**，
 *     以保证「新地区先锁定」——平局时先到的方案胜出。
 *  2. **字典序比较**（[isBetterRestorePlan]）：覆盖数 → 沿用数 → 可复用数（都是大者优），
 *     然后**只有据点集合相同**才比总成本（小者优）。集合不同直接判否，这是「平局保留先到者」
 *     的实现方式，不是入口前置校验。
 *  3. **`OutpostProsperityMaxBonusTier` 的口径**：它不是「比较时取 max」，而是
 *     「据点发展值已满、发展值词条失效后」的档位。用哪个由据点是否在
 *     [SelectionParam.outpostProsperityMaxLocations] 里决定，方向始终是越小越好。
 */
object OperatorSelection {

    const val USAGE_TARGET = "target"
    const val USAGE_RESTORE = "restore"
    const val USAGE_ALL = "all"

    /** 账号拥有的干员。 */
    data class Ownership(val operators: Set<String>)

    private typealias Candidate = OperatorDataset.OperatorCandidate
    private typealias CandidateGroup = OperatorDataset.OperatorCandidateGroup

    /** 上游 `operatorSelectionParam`：由识别器把数据 + 会话快照拼成，纯函数只读它。 */
    data class SelectionParam(
        val usage: String,
        val location: String,
        /** 仅 usage=target 时填充（该据点的 target 候选）。 */
        val candidates: List<Candidate> = emptyList(),
        val targetCandidatesByLocation: Map<String, List<Candidate>> = emptyMap(),
        val restoreGroups: List<CandidateGroup> = emptyList(),
        val scanCandidates: List<Candidate> = emptyList(),
        val knownOperators: List<Candidate> = emptyList(),
        val activeLocations: Set<String> = emptySet(),
        val completedRestoreLocations: Set<String> = emptySet(),
        val targetAssignments: Map<String, Candidate> = emptyMap(),
        val lockedRestoreAssignments: Map<String, Candidate> = emptyMap(),
        val excludedOperators: Set<String> = emptySet(),
        val outpostProsperityMaxLocations: Set<String> = emptySet(),
    )

    /** 上游 `restoreAssignmentPlan`。 */
    data class RestorePlan(
        val assignments: Map<String, Candidate> = emptyMap(),
        val assigned: Int = 0,
        val keptTargets: Int = 0,
        val reusableTargets: Int = 0,
        val totalCost: Int = 0,
    )

    // ───────────────────────── 入口 ─────────────────────────

    /** 上游 selection.go:21 */
    fun candidatesForOwnership(param: SelectionParam, ownership: Ownership): List<Candidate> =
        candidatesForCurrentSelection(param, ownership.operators)

    /**
     * 上游 selection.go:29：与 [candidatesForOwnership] 不同，这里**保留最高档全部候选**
     * （不模拟恢复计划、不单选），用于「当前派驻是否已经足够好」。
     */
    fun equivalentTargetCandidatesForOwnership(
        param: SelectionParam,
        ownership: Ownership,
    ): List<Candidate> {
        val available = availableOperatorsForTarget(param, ownership.operators)
        return preferredTargetCandidates(
            param.candidates,
            available,
            param.location,
            param.restoreGroups,
            param.outpostProsperityMaxLocations,
        )
    }

    /**
     * 上游 selection.go:46 `candidatesForCurrentSelection`。
     *
     * 完整判定链见各步骤注释；`usage=all` 一律返回空（它只用于扫描，不参与选择）。
     */
    fun candidatesForCurrentSelection(param: SelectionParam, owned: Set<String>): List<Candidate> {
        // A：先剔本轮冲突拉黑的干员
        val availableOwned = operatorsExcludingConflicts(param, owned)

        if (param.usage == USAGE_TARGET) {
            // B：再剔「其它据点已完成恢复被锁定」的干员（本据点自己的锁定保留）
            removeOtherLockedRestoreOperators(availableOwned, param.lockedRestoreAssignments, param.location)

            // C：最高售卖档 -> 完美候选（同时是该据点恢复候选）优先，否则回退整档
            val candidates = preferredTargetCandidates(
                param.candidates,
                availableOwned,
                param.location,
                param.restoreGroups,
                param.outpostProsperityMaxLocations,
            )
            if (candidates.isEmpty()) return emptyList()

            // D：同档多人时逐个模拟后续全局恢复，取整体最优的 1 人
            return listOf(selectTargetCandidateForRestorePlan(param, availableOwned, candidates))
        }

        if (param.usage != USAGE_RESTORE) return emptyList()

        // E：本据点已完成恢复 -> 直接返回锁定结果，不进全局搜索
        param.lockedRestoreAssignments[param.location]?.let { return listOf(it) }

        // F：可用集合 = A − 全部已锁定恢复干员（不限据点）
        val available = availableOwned.toMutableSet().also { set ->
            param.lockedRestoreAssignments.values.forEach { set.remove(it.name) }
        }

        // G：偏好/可复用集合（注意传的是 availableOwned，不是 available）
        val (preferred, reusable) = restorePlanPreferences(param, availableOwned)

        // H：对所有「启用且未完成恢复」的据点做互斥全局分配
        val plan = buildRestoreAssignmentPlanWithPreferencesAndTargets(
            restoreGroupsForSelection(param),
            available,
            preferred,
            reusable,
        )

        // I：只取本据点的分配结果
        return plan.assignments[param.location]?.let { listOf(it) } ?: emptyList()
    }

    // ───────────────────────── 集合过滤 ─────────────────────────

    /** 上游 selection.go:96：owned − 冲突拉黑。 */
    fun operatorsExcludingConflicts(param: SelectionParam, owned: Set<String>): MutableSet<String> =
        owned.toMutableSet().also { set ->
            param.excludedOperators.forEach { set.remove(it) }
        }

    /** 上游 selection.go:105 */
    fun availableOperatorsForTarget(param: SelectionParam, owned: Set<String>): MutableSet<String> =
        operatorsExcludingConflicts(param, owned).also { set ->
            removeOtherLockedRestoreOperators(set, param.lockedRestoreAssignments, param.location)
        }

    /** 上游 selection.go:113：只删「其它据点」已锁定的干员。 */
    fun removeOtherLockedRestoreOperators(
        available: MutableSet<String>,
        locked: Map<String, Candidate>,
        currentLocation: String,
    ) {
        for ((location, candidate) in locked) {
            if (location == currentLocation) continue
            available.remove(candidate.name)
        }
    }

    /** 上游 selection.go:448 */
    fun filterOwnedCandidates(candidates: List<Candidate>, owned: Set<String>): List<Candidate> {
        if (candidates.isEmpty() || owned.isEmpty()) return emptyList()
        return candidates.filter { it.name in owned }
    }

    // ───────────────────────── 档位与完美候选 ─────────────────────────

    /**
     * 上游 selection.go:127 `bestBonusTierCandidates`。
     *
     * 流式扫描取最小档并收集同档者，**保持输入遭遇顺序**（不重排）。
     * [outpostProsperityMax] 为 true 时用 `outpostProsperityMaxBonusTier`，否则用 `bonusTier`。
     */
    fun bestBonusTierCandidates(candidates: List<Candidate>, outpostProsperityMax: Boolean): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()

        fun tier(c: Candidate): Int =
            if (outpostProsperityMax) c.outpostProsperityMaxBonusTier else c.bonusTier

        var bestTier = tier(candidates[0])
        val best = mutableListOf<Candidate>()
        for (c in candidates) {
            val t = tier(c)
            if (t < bestTier) {
                bestTier = t
                best.clear()
            }
            if (t == bestTier) best += c
        }
        return best
    }

    /**
     * 上游 selection.go:200 `preferredTargetCandidates`。
     *
     * 最高售卖档 -> 与该据点的恢复候选求交；有完美候选时**只**返回完美候选，否则回退整档。
     */
    fun preferredTargetCandidates(
        candidates: List<Candidate>,
        owned: Set<String>,
        location: String,
        restoreGroups: List<CandidateGroup>,
        outpostProsperityMaxLocations: Set<String>,
    ): List<Candidate> {
        val prosperityMax = location in outpostProsperityMaxLocations
        val bestSelling = bestBonusTierCandidates(filterOwnedCandidates(candidates, owned), prosperityMax)
        if (bestSelling.isEmpty()) return emptyList()

        val restoreNames = restoreCandidateNames(restoreGroups, location) ?: emptySet()
        val perfect = bestSelling.filter { it.name in restoreNames }
        return perfect.ifEmpty { bestSelling }
    }

    /** 上游 selection.go:227：找不到该据点返回 null（调用方按空集处理）。 */
    fun restoreCandidateNames(groups: List<CandidateGroup>, location: String): Set<String>? =
        groups.firstOrNull { it.location == location }?.candidates?.map { it.name }?.toSet()

    // ───────────────────────── 偏好与可复用 ─────────────────────────

    /**
     * 上游 selection.go:243 `restorePlanPreferences`。
     *
     * - 沿用（kept）：DFS 给某据点选的人 == preferred[该据点]
     * - 可复用（reusable）：DFS 给某据点选的人在该据点的 reusable 名字集合里
     *
     * 本轮实际售卖结果（targetAssignments）会**覆盖** preferred 的默认值，更能体现「保持不切换」；
     * reusable 不被覆盖。
     */
    fun restorePlanPreferences(
        param: SelectionParam,
        owned: Set<String>,
    ): Pair<Map<String, Candidate>, Map<String, Set<String>>> {
        val preferred = mutableMapOf<String, Candidate>()
        val reusable = mutableMapOf<String, Set<String>>()

        for (location in param.activeLocations) {
            val available = preferredTargetCandidates(
                param.targetCandidatesByLocation[location] ?: emptyList(),
                owned,
                location,
                param.restoreGroups,
                param.outpostProsperityMaxLocations,
            )
            if (available.isEmpty()) continue
            preferred[location] = available[0]
            reusable[location] = available.map { it.name }.toSet()
        }

        for ((location, candidate) in param.targetAssignments) {
            if (location in param.activeLocations) preferred[location] = candidate
        }
        return preferred to reusable
    }

    /** 上游 selection.go:280：只按内部 Name 比较。 */
    fun sameOperator(a: Candidate, b: Candidate): Boolean = a.name == b.name

    /** 上游 selection.go:285：只留启用且未完成恢复的据点，顺序即 data.location_order 过滤后的顺序。 */
    fun restoreGroupsForSelection(param: SelectionParam): List<CandidateGroup> =
        param.restoreGroups.filter {
            it.location in param.activeLocations && it.location !in param.completedRestoreLocations
        }

    // ───────────────────────── 全局恢复分配 ─────────────────────────

    /** 上游 selection.go:311 */
    fun buildRestoreAssignmentPlan(groups: List<CandidateGroup>, owned: Set<String>): RestorePlan =
        buildRestoreAssignmentPlanWithPreferences(groups, owned, null)

    /** 上游 selection.go:316 */
    fun buildRestoreAssignmentPlanWithPreferences(
        groups: List<CandidateGroup>,
        owned: Set<String>,
        preferred: Map<String, Candidate>?,
    ): RestorePlan = buildRestoreAssignmentPlanWithPreferencesAndTargets(groups, owned, preferred, null)

    /**
     * 上游 selection.go:327 `buildRestoreAssignmentPlanWithPreferencesAndTargets`。
     *
     * 可跳过据点的 DFS。分支顺序很关键：**先探索「分配某人」，再探索「跳过」**，
     * 于是完全等价的方案保留「靠前的据点先分配」的那个，也就是新地区优先。
     *
     * 只在叶子比较；`best` 初始为空计划（全空跳过叶子与它全等，不会替换）。
     */
    fun buildRestoreAssignmentPlanWithPreferencesAndTargets(
        groups: List<CandidateGroup>,
        owned: Set<String>,
        preferred: Map<String, Candidate>?,
        reusableTargets: Map<String, Set<String>>?,
    ): RestorePlan {
        var best = RestorePlan()
        val current = mutableMapOf<String, Candidate>()
        val used = mutableSetOf<String>()

        fun walk(index: Int, assigned: Int, kept: Int, reusable: Int, totalCost: Int) {
            if (index >= groups.size) {
                val candidate = RestorePlan(
                    assignments = current, // 仅用于比较的临时视图，更优时才快照
                    assigned = assigned,
                    keptTargets = kept,
                    reusableTargets = reusable,
                    totalCost = totalCost,
                )
                if (isBetterRestorePlan(candidate, best)) {
                    best = candidate.copy(assignments = current.toMap())
                }
                return
            }

            val group = groups[index]
            for (c in filterOwnedCandidates(group.candidates, owned)) {
                if (c.name in used) continue
                used += c.name
                current[group.location] = c

                val keptNext = if (preferred?.get(group.location)?.let { sameOperator(c, it) } == true) kept + 1 else kept
                val reusableNext =
                    if (reusableTargets?.get(group.location)?.contains(c.name) == true) reusable + 1 else reusable

                walk(index + 1, assigned + 1, keptNext, reusableNext, totalCost + c.priority)

                current.remove(group.location)
                used -= c.name
            }

            // 跳过该据点：把干员留给后面的据点
            walk(index + 1, assigned, kept, reusable, totalCost)
        }

        walk(0, 0, 0, 0, 0)
        return best
    }

    /**
     * 上游 selection.go:392 `isBetterRestorePlan`。
     *
     * 字典序：覆盖数 → 沿用数 → 可复用数（**大者优**），然后**仅在据点集合相同时**
     * 比总成本（**小者优**）。全部相等返回 false，因此先到者胜。
     */
    fun isBetterRestorePlan(candidate: RestorePlan, best: RestorePlan): Boolean {
        if (candidate.assigned != best.assigned) return candidate.assigned > best.assigned
        if (candidate.keptTargets != best.keptTargets) return candidate.keptTargets > best.keptTargets
        if (candidate.reusableTargets != best.reusableTargets) {
            return candidate.reusableTargets > best.reusableTargets
        }
        if (!sameAssignedLocations(candidate.assignments, best.assignments)) return false
        return candidate.totalCost < best.totalCost
    }

    /** 上游 selection.go:409：只比据点集合（key），不比分配的人。 */
    fun sameAssignedLocations(
        a: Map<String, Candidate>,
        b: Map<String, Candidate>,
    ): Boolean = a.keys == b.keys

    /** 上游 selection.go:422 */
    fun cloneRestoreAssignments(src: Map<String, Candidate>): MutableMap<String, Candidate> =
        src.toMutableMap()

    /** 上游 selection.go:521 `allOperatorScanCandidates`。 */
    fun allOperatorScanCandidates(data: OperatorDataset.OperatorSelectionData?): List<Candidate> =
        if (data == null) emptyList() else OperatorDataset.normalizeOperatorCandidates(data.knownOperators)

    /** 上游 selection.go:462 `collectScanCandidates`。 */
    fun collectScanCandidates(param: SelectionParam?): List<Candidate> =
        if (param == null) emptyList() else OperatorDataset.normalizeOperatorCandidates(param.scanCandidates)

    // ───────────────────────── target 的恢复模拟 ─────────────────────────

    /**
     * 上游 selection.go:154 `selectTargetCandidateForRestorePlan`。
     *
     * 逐个把候选塞进 TargetAssignments 的**副本**里模拟全局恢复，取更优者。
     * 严格更优才替换，所以平局保留更靠前的候选。不写任何会话状态。
     */
    fun selectTargetCandidateForRestorePlan(
        param: SelectionParam,
        owned: Set<String>,
        candidates: List<Candidate>,
    ): Candidate {
        var bestCandidate = candidates[0]
        if (candidates.size == 1) return bestCandidate

        var bestPlan = restorePlanForTargetCandidate(param, owned, bestCandidate)
        for (c in candidates.drop(1)) {
            val plan = restorePlanForTargetCandidate(param, owned, c)
            if (isBetterRestorePlan(plan, bestPlan)) {
                bestCandidate = c
                bestPlan = plan
            }
        }
        return bestCandidate
    }

    /** 上游 selection.go:175 `restorePlanForTargetCandidate`：临时模拟，不写回会话。 */
    fun restorePlanForTargetCandidate(
        param: SelectionParam,
        owned: Set<String>,
        candidate: Candidate,
    ): RestorePlan {
        val simulated = param.copy(
            targetAssignments = param.targetAssignments.toMutableMap().also {
                it[param.location] = candidate
            },
        )
        val available = owned.toMutableSet().also { set ->
            param.lockedRestoreAssignments.values.forEach { set.remove(it.name) }
        }
        val (preferred, reusable) = restorePlanPreferences(simulated, owned)
        return buildRestoreAssignmentPlanWithPreferencesAndTargets(
            restoreGroupsForSelection(simulated),
            available,
            preferred,
            reusable,
        )
    }
}
