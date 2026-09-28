package com.aliothmoon.maafw.remote

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 据点交易的**任务级会话状态**（对齐上游 `outposttrading/operator/session.go`）。
 *
 * 这个对象管的是「一次任务内跨据点共享的那些决策记录」：
 * 哪些据点启用、哪些恢复已完成、哪些干员已被锁定/拉黑、重试配额用掉没有。
 * 据点之间靠 location 键隔离，但下面几项是**有意全局共享**的——
 * 恢复分配的全局互斥、禁用集合、发展值满级集合，都是全局规划的必要条件。
 *
 * 分发 operation 时**不打印**，而是返回 [SessionOutcome] 让调用方去提示：
 * 这样整个状态机可以本地单测。
 */
class OperatorSession(private val host: Host) {

    /** 宿主需要提供的能力：UID、发展值状态的磁盘读写。 */
    interface Host {
        fun currentUid(): String

        /** 从磁盘读该账号「发展值已满」的据点集合；失败返回空集（上游只告警不阻断）。 */
        fun loadProsperityMaxLocations(uid: String): Set<String>

        /** 写盘；返回是否真的发生了修改。失败返回 null。 */
        fun persistProsperityStatus(uid: String, location: String, reached: Boolean): Boolean?
    }

    /** 上游 session.go:31 `operatorSessionState`。字段全部是任务级，reset 时整体重建。 */
    data class State(
        var uid: String,
        var mode: String,
        val activeLocations: MutableSet<String> = mutableSetOf(),
        val completedRestoreLocations: MutableSet<String> = mutableSetOf(),
        val enteredLocations: MutableSet<String> = mutableSetOf(),
        val targetAssignments: MutableMap<String, OperatorDataset.OperatorCandidate> = mutableMapOf(),
        val plannedRestoreAssignments: MutableMap<String, OperatorDataset.OperatorCandidate> = mutableMapOf(),
        val lockedRestoreAssignments: MutableMap<String, OperatorDataset.OperatorCandidate> = mutableMapOf(),
        val excludedOperators: MutableSet<String> = mutableSetOf(),
        val outpostProsperityMaxLocations: MutableSet<String> = mutableSetOf(),
        val retriedSelections: MutableSet<String> = mutableSetOf(),
        var cacheNoticePrinted: Boolean = false,
        var refreshed: Boolean = false,
    )

    /** 快照（深拷贝）：纯算法层拿到后可以随便改，不影响全局。 */
    data class Snapshot(
        val uid: String,
        val mode: String,
        val activeLocations: Set<String>,
        val completedRestoreLocations: Set<String>,
        val targetAssignments: Map<String, OperatorDataset.OperatorCandidate>,
        val lockedRestoreAssignments: Map<String, OperatorDataset.OperatorCandidate>,
        val excludedOperators: Set<String>,
        val outpostProsperityMaxLocations: Set<String>,
    )

    /** 上游 `operatorSessionActionParam`。 */
    data class ActionParam(
        val operation: String,
        val mode: String = "",
        val location: String = "",
        val usage: String = "",
        val active: Boolean = false,
        val changed: Boolean = false,
        val outpostProsperityMax: Boolean = false,
    )

    /** 分发结果：调用方据此打印提示。 */
    sealed interface SessionOutcome {
        /** 正常完成，无需提示。 */
        object Ok : SessionOutcome

        /** 需要打印「沿用 / 已切换」。 */
        data class Assignment(
            val location: String,
            val usage: String,
            val candidate: OperatorDataset.OperatorCandidate,
            val changed: Boolean,
        ) : SessionOutcome

        data class RestoreSkipped(val location: String) : SessionOutcome

        data class ConflictExcluded(
            val location: String,
            val usage: String,
            val candidate: OperatorDataset.OperatorCandidate,
        ) : SessionOutcome

        /** 参数缺失/未知 operation/前置状态缺失 → 动作判失败。 */
        data class Failed(val reason: String) : SessionOutcome
    }

    /** reset 是否发生了（调用方需要据此清空扫描状态表）。 */
    var lastResetClearedScanStates: Boolean = false
        private set

    private val lock = ReentrantLock()
    private var state: State? = null

    // ───────────────────────── 参数解析 ─────────────────────────

    companion object {
        const val MODE_CACHE = "cache"
        const val MODE_REFRESH = "refresh"

        /** 上游 session.go:138 `parseOperatorSessionActionParam`。 */
        fun parseParam(tree: Any?): ActionParam? {
            val map = tree as? Map<*, *> ?: return null
            val operation = (map["operation"] as? String)?.trim().orEmpty()
            if (operation.isEmpty()) return null

            val param = ActionParam(
                operation = operation,
                mode = (map["mode"] as? String)?.trim().orEmpty(),
                location = (map["location"] as? String)?.trim().orEmpty(),
                usage = (map["usage"] as? String)?.trim().orEmpty(),
                active = map["active"] as? Boolean ?: false,
                changed = map["changed"] as? Boolean ?: false,
                outpostProsperityMax = map["outpost_prosperity_max"] as? Boolean ?: false,
            )

            if (operation == "reset" && param.mode !in setOf(MODE_CACHE, MODE_REFRESH)) return null
            if (operation in LOCATION_REQUIRED && param.location.isEmpty()) return null
            if (operation == "exclude_selected" && param.usage !in setOf("target", "restore")) return null
            return param
        }

        /** 除 reset 外都需要 location（与上游一致）。 */
        private val LOCATION_REQUIRED = setOf(
            "register", "enter_location", "complete_target",
            "complete_restore", "skip_restore", "exclude_selected",
        )
    }

    // ───────────────────────── 分发 ─────────────────────────

    /** 上游 session.go:68 `Run`。 */
    fun run(param: ActionParam): SessionOutcome = when (param.operation) {
        "reset" -> {
            reset(param.mode)
            SessionOutcome.Ok
        }

        "register" -> {
            // active=false 时什么都不做（与上游一致）
            if (param.active) registerLocation(param.location)
            SessionOutcome.Ok
        }

        "enter_location" -> {
            val uid = setProsperityMax(param.location, param.outpostProsperityMax)
            // 写盘失败只告警，不影响动作成功
            host.persistProsperityStatus(uid, param.location, param.outpostProsperityMax)
            enterLocation(param.location)
            SessionOutcome.Ok
        }

        "complete_target" -> {
            val candidate = targetAssignment(param.location)
                ?: return SessionOutcome.Failed("complete_target: no target assignment for ${param.location}")
            // changed 只影响提示文案，对状态零影响
            SessionOutcome.Assignment(param.location, "target", candidate, param.changed)
        }

        "complete_restore" -> {
            val candidate = completeRestore(param.location)
                ?: return SessionOutcome.Failed("complete_restore: no planned restore for ${param.location}")
            SessionOutcome.Assignment(param.location, "restore", candidate, param.changed)
        }

        "skip_restore" -> {
            skipRestore(param.location)
            SessionOutcome.RestoreSkipped(param.location)
        }

        "exclude_selected" -> {
            val candidate = excludeSelected(param.usage, param.location)
                ?: return SessionOutcome.Failed("exclude_selected: nothing selected for ${param.location}")
            SessionOutcome.ConflictExcluded(param.location, param.usage, candidate)
        }

        else -> SessionOutcome.Failed("unknown operation: ${param.operation}")
    }

    // ───────────────────────── 懒建与重置 ─────────────────────────

    /**
     * 上游 session.go:365 `ensureOperatorSessionLocked`。
     *
     * 触发重建的条件只有一个：**UID 变了**或从未初始化。懒建强制 `mode=cache`
     * （refresh 只可能来自显式 reset）。在锁内读盘播种发展值满级集合。
     */
    private fun ensureLocked(): State {
        val uid = host.currentUid()
        val existing = state
        if (existing != null && existing.uid == uid) return existing

        val fresh = State(
            uid = uid,
            mode = MODE_CACHE,
            outpostProsperityMaxLocations = host.loadProsperityMaxLocations(uid).toMutableSet(),
        )
        state = fresh
        return fresh
    }

    /**
     * 上游 session.go:171 `operatorSessionReset`。
     *
     * 磁盘读在加锁前（上游如此），整体重建。
     * 注意：上游还会顺带清空扫描状态表，那属于 [OperatorScan]，由调用方在收到
     * 本方法返回后清（见 [lastResetClearedScanStates]）。
     */
    fun reset(mode: String) {
        val uid = host.currentUid()
        val maxLocations = host.loadProsperityMaxLocations(uid)
        lock.withLock {
            state = State(
                uid = uid,
                mode = mode,
                outpostProsperityMaxLocations = maxLocations.toMutableSet(),
            )
        }
        lastResetClearedScanStates = true
    }

    // ───────────────────────── 状态读写 ─────────────────────────

    fun registerLocation(location: String) = lock.withLock {
        ensureLocked().activeLocations += location
    }

    /** 首次进入返回 true。 */
    fun enterLocation(location: String): Boolean = lock.withLock {
        ensureLocked().enteredLocations.add(location)
    }

    /** 更新发展值满级集合，返回 uid（供写盘）。 */
    fun setProsperityMax(location: String, reached: Boolean): String = lock.withLock {
        val s = ensureLocked()
        if (reached) s.outpostProsperityMaxLocations += location
        else s.outpostProsperityMaxLocations -= location
        s.uid
    }

    fun targetAssignment(location: String): OperatorDataset.OperatorCandidate? = lock.withLock {
        ensureLocked().targetAssignments[location]
    }

    fun setTargetAssignment(location: String, candidate: OperatorDataset.OperatorCandidate) = lock.withLock {
        ensureLocked().targetAssignments[location] = candidate
    }

    /** candidate 为 null 时删除计划。 */
    fun setPlannedRestore(location: String, candidate: OperatorDataset.OperatorCandidate?) = lock.withLock {
        val s = ensureLocked()
        if (candidate == null) s.plannedRestoreAssignments.remove(location)
        else s.plannedRestoreAssignments[location] = candidate
    }

    /**
     * 上游 session.go:295 `operatorSessionCompleteRestore`。
     *
     * 锁定分配 + 标记完成 + 清掉 target 与计划。没有计划则返回 null 且**什么都不改**。
     */
    fun completeRestore(location: String): OperatorDataset.OperatorCandidate? = lock.withLock {
        val s = ensureLocked()
        val planned = s.plannedRestoreAssignments[location] ?: return@withLock null
        s.lockedRestoreAssignments[location] = planned
        s.completedRestoreLocations += location
        s.targetAssignments.remove(location)
        s.plannedRestoreAssignments.remove(location)
        planned
    }

    /** 上游 session.go:312：标记完成并不再为该据点预留，但**不**写 locked。 */
    fun skipRestore(location: String) = lock.withLock {
        val s = ensureLocked()
        s.completedRestoreLocations += location
        s.targetAssignments.remove(location)
        s.plannedRestoreAssignments.remove(location)
    }

    /**
     * 上游 session.go:271 `operatorSessionExcludeSelected`。
     *
     * 把刚刚触发弹窗的候选拉黑并删掉对应计划；取不到候选返回 null。
     */
    fun excludeSelected(usage: String, location: String): OperatorDataset.OperatorCandidate? = lock.withLock {
        val s = ensureLocked()
        val selected = when (usage) {
            "target" -> {
                val c = s.targetAssignments[location]
                s.targetAssignments.remove(location)
                c
            }

            "restore" -> {
                val c = s.plannedRestoreAssignments[location]
                s.plannedRestoreAssignments.remove(location)
                c
            }

            else -> null
        } ?: return@withLock null
        s.excludedOperators += selected.name
        selected
    }

    fun markRefreshed() = lock.withLock {
        ensureLocked().refreshed = true
    }

    fun isRefreshed(): Boolean = lock.withLock { ensureLocked().refreshed }

    /**
     * 上游 session.go:329 `operatorSessionClaimRetry`：同 (usage, location) 每任务只允许一次。
     */
    fun claimRetry(usage: String, location: String): Boolean = lock.withLock {
        ensureLocked().retriedSelections.add("$usage|$location")
    }

    /** 上游 session.go:342：等价于 claimRetry(all, global)。 */
    fun claimCacheRescan(): Boolean = claimRetry("all", "global")

    /** 上游 session.go:347：缓存状态提示每任务只打一次。 */
    fun claimCacheNotice(): Boolean = lock.withLock {
        val s = ensureLocked()
        if (s.cacheNoticePrinted) return@withLock false
        s.cacheNoticePrinted = true
        true
    }

    fun mode(): String = lock.withLock { ensureLocked().mode }

    /** 上游 session.go:199 `operatorSessionSnapshot`：深拷贝，识别器只读它。 */
    fun snapshot(): Snapshot = lock.withLock {
        val s = ensureLocked()
        Snapshot(
            uid = s.uid,
            mode = s.mode,
            activeLocations = s.activeLocations.toSet(),
            completedRestoreLocations = s.completedRestoreLocations.toSet(),
            targetAssignments = s.targetAssignments.toMap(),
            lockedRestoreAssignments = s.lockedRestoreAssignments.toMap(),
            excludedOperators = s.excludedOperators.toSet(),
            outpostProsperityMaxLocations = s.outpostProsperityMaxLocations.toSet(),
        )
    }
}
