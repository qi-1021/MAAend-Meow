package com.aliothmoon.maafw.remote

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 据点交易的**干员列表扫描**（对齐上游 `outposttrading/operator/scan.go`）。
 *
 * 「滚列表找干员」是跨多帧完成的，所以这里有两类东西：
 *  1. **纯判定**：[signature] / [reachedBottom] / [shouldHit] / [outcomeDetailJson]
 *     ——「连续两帧签名相同即到底」这类规则，可本地单测。
 *  2. **跨帧状态**：[ScanStates]（key = `UID|mode|usage|location`）与
 *     [OcrHandoff]（当前干员 OCR 的单槽交接）。
 *
 * 两个容易写错的点：
 *  - [reachedBottom] 要求**前一帧非空**才算到底；空签名帧会重置比较链，
 *    否则一次 OCR 瞬时失败就会被误判成「到底」。
 *  - [OcrHandoff.take] **无论 key 是否匹配都清空**槽位；否则旧帧数据会跨任务泄漏。
 */
object OperatorScan {

    /** 上游 scan.go:53 `operatorListScanState`。 */
    data class ScanState(
        val key: String,
        var previousSignature: String = "",
        var observed: MutableList<String> = mutableListOf(),
        var expectedCandidates: List<String> = emptyList(),
        var observedCandidates: List<String> = emptyList(),
        var completed: Boolean = false,
        var hasCandidate: Boolean = false,
        var error: String = "",
    )

    // ───────────────────────── 扫描状态表 ─────────────────────────

    /** 进程内扫描状态；由独立锁保护。 */
    class ScanStates {
        private val lock = ReentrantLock()
        private val states = mutableMapOf<String, ScanState>()

        fun getOrCreate(key: String): ScanState = lock.withLock {
            states.getOrPut(key) { ScanState(key) }
        }

        fun copyOf(key: String): ScanState? = lock.withLock {
            states[key]?.let { it.copy(observed = it.observed.toMutableList()) }
        }

        /** 写回（Go 侧是 copy-on-write：改副本再 set）。 */
        fun put(state: ScanState) = lock.withLock {
            states[state.key] = state
        }

        fun delete(key: String) = lock.withLock {
            states.remove(key)
        }

        /** session reset 时整体清空。 */
        fun clear() = lock.withLock {
            states.clear()
        }

        fun size(): Int = lock.withLock { states.size }
    }

    /** 上游 scan.go:386 `operatorListScanStateKey`：`UID|mode|usage|location`（**不含 taskID**）。 */
    fun scanStateKey(uid: String, mode: String, usage: String, location: String): String =
        "$uid|$mode|$usage|$location"

    // ───────────────────────── OCR 交接缓存 ─────────────────────────

    /** 交接键：taskID | location | roi。 */
    data class HandoffKey(val taskId: Long, val location: String, val roi: List<Int>)

    /**
     * 上游 scan.go:45 `currentOperatorOCRCache`：单槽交接。
     *
     * 用途：`CurrentOperatorUncached` 先 OCR 一次当前干员，紧接着 `CurrentBestOperator`
     * 需要同一帧同一 ROI 的结果，直接复用避免重复 OCR（也避免两帧不一致）。
     */
    class OcrHandoff {
        private val lock = ReentrantLock()
        private var entry: Pair<HandoffKey, List<OperatorOcrMatch.Item>>? = null

        fun store(key: HandoffKey, items: List<OperatorOcrMatch.Item>) = lock.withLock {
            entry = key to items.toList()
        }

        /**
         * 取出并**无条件清空**槽位（上游 scan.go:266 的行为）。
         *
         * key 不匹配时返回 null 但同样清空——这条很关键：跨任务残留必须被丢掉，
         * 而不是等 key 碰巧再匹配一次。
         */
        fun take(key: HandoffKey): List<OperatorOcrMatch.Item>? = lock.withLock {
            val current = entry
            entry = null
            when {
                current == null -> null
                current.first == key -> current.second
                else -> null
            }
        }

        fun clear() = lock.withLock { entry = null }
    }

    // ───────────────────────── 纯判定 ─────────────────────────

    /**
     * 上游 scan.go:183 `observedOperatorIDs`：本帧能看到的扫描域候选（**字典序**）。
     *
     * 只判 `findBest` 是否命中，不关心 Tier 与框；同一 name 命中多次只算一次。
     */
    fun observedOperatorIds(
        items: List<OperatorOcrMatch.Item>,
        candidates: List<OperatorDataset.OperatorCandidate>,
    ): List<String> {
        val observed = mutableSetOf<String>()
        for (candidate in candidates) {
            if (candidate.expected.isEmpty()) continue
            if (OperatorOcrMatch.findBest(items, candidate.expected) != null) observed += candidate.name
        }
        return observed.filter { it.isNotEmpty() }.sorted()
    }

    /**
     * 上游 scan.go:195 `operatorListSignature`：去空 → 去重 → 字典序 → `\n` 连接。
     *
     * 签名只用**当前帧**的可见集合，不是累积集合。
     */
    fun signature(names: List<String>): String {
        if (names.isEmpty()) return ""
        return names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("\n")
    }

    /** 上游 scan.go:206 `operatorListReachedBottom`：前一帧必须非空。 */
    fun reachedBottom(previous: String, current: String): Boolean =
        previous.isNotEmpty() && previous == current

    /** 上游 scan.go:289 `shouldHitOperatorListBottomResult`。 */
    fun shouldHit(result: String, hasCandidate: Boolean): Boolean = when (result) {
        "scan_done" -> true
        "retry" -> hasCandidate
        "not_found" -> !hasCandidate
        else -> true
    }

    /** 上游 scan.go:336 `operatorCandidateIDs`：**候选配置顺序**、去重去空。 */
    fun candidateIds(candidates: List<OperatorDataset.OperatorCandidate>): List<String> =
        candidates.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 上游 scan.go:345 `observedConfiguredOperatorNames`：**候选配置顺序**。 */
    fun observedConfiguredNames(
        candidates: List<OperatorDataset.OperatorCandidate>,
        observed: List<String>,
    ): List<String> {
        val observedSet = observed.filter { it.isNotEmpty() }.toSet()
        return candidates.filter { it.name in observedSet }
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 上游 scan.go:169 `shouldWriteOperatorCacheSnapshot`。 */
    fun shouldWriteSnapshot(
        usage: String,
        location: String,
        mode: String,
        hasSnapshot: Boolean,
    ): Boolean =
        usage == "all" && location == "global" &&
            (mode == OperatorSession.MODE_REFRESH || (mode == OperatorSession.MODE_CACHE && !hasSnapshot))

    /**
     * 上游 scan.go:358 `operatorScanOutcomeDetailJSON`。
     *
     * 4 种 reason，`scan_error` 优先级最高；空字段按上游 omitempty 省略。
     */
    fun outcomeDetailJson(
        result: String,
        usage: String,
        location: String,
        state: ScanState,
    ): String {
        val reason = when {
            state.error.isNotEmpty() -> "scan_error"
            usage == "target" -> "no_owned_candidate"
            usage == "restore" -> "no_available_candidate"
            else -> "no_candidate"
        }
        val payload = linkedMapOf<String, Any?>(
            "result" to result,
            "reason" to reason,
            "usage" to usage,
            "location" to location,
        )
        if (state.expectedCandidates.isNotEmpty()) payload["expected_candidates"] = state.expectedCandidates
        if (state.observedCandidates.isNotEmpty()) payload["observed_candidates"] = state.observedCandidates
        if (state.error.isNotEmpty()) payload["error"] = state.error
        return JsonTree.toJson(payload)
    }
}
