package com.aliothmoon.maafw.remote

/**
 * 据点交易的**干员识别决策层**
 * （对齐上游 `outposttrading/operator/recognition.go` + `conflict.go`）。
 *
 * 六个 recognition + 一个冲突识别都在这里做决策；对 MaaFramework 的依赖收在 [Host] 后面，
 * 所以「什么时候命中、命中返回什么框与 detail、命中后写哪些会话状态」可以本地单测。
 *
 * 三个容易写反的顺序：
 *  1. `CurrentOperatorUncached` 的 **claim 在 Refreshed 检查之前**：即使随后被
 *     「本任务已全量扫过」拒绝，重扫配额也已经被消耗掉了。
 *  2. `CacheReady` 的 **claim 在 ready 判断之前**：缓存状态提示每任务只打一次，
 *     不 ready 时也会打这一次。
 *  3. `OperatorListBottom` 的 **retry 分支**：claim 失败会把 hasCandidate 置回 false
 *     并写 error，于是同一帧的 ScanOutcome 会走到「失败」而不是「没找到」。
 */
class OperatorRecognitions(private val host: Host) {

    private typealias Candidate = OperatorDataset.OperatorCandidate
    private typealias OcrItem = OperatorOcrMatch.Item

    /** 宿主能力：数据、会话、扫描状态、OCR、缓存读写。提示走 [log]。 */
    interface Host {
        fun selectionData(): OperatorDataset.OperatorSelectionData?

        fun selectionFile(): OperatorDataset.SelectionFile?

        /** 拼装选择参数（含校验：location 是否在数据与启用集合里）。失败返回 null。 */
        fun resolveSelection(p: Param): OperatorSelection.SelectionParam?

        /** 账号拥有的干员名；读不到快照时返回空集（不是 null）。 */
        fun loadOwnedNames(): Set<String>?

        /** OCR 一帧干员列表。失败返回 null。 */
        fun listOcr(roi: List<Int>): List<OcrItem>?

        /** 读当前干员；reuse=true 时优先复用 uncached 留下的交接缓存。 */
        fun currentOcr(roi: List<Int>, reuse: Boolean): List<OcrItem>?

        val scanStates: OperatorScan.ScanStates

        val session: OperatorSession

        fun cacheHasSnapshot(uid: String): Boolean

        /** 清掉磁盘快照；返回是否成功。 */
        fun invalidateSnapshot(uid: String): Boolean

        /** 是否满足写快照的条件（全量扫描 + refresh 或无快照）。 */
        fun shouldWriteSnapshot(p: Param): Boolean

        /** 写快照；返回是否成功。 */
        fun writeSnapshot(p: Param, scanCandidates: List<Candidate>, observed: List<String>): Boolean

        /** 最近一次失败原因（供写入扫描状态的 error）。 */
        fun lastError(): String

        fun currentUid(): String

        fun log(message: String)
    }

    /** 上游 `operatorRecognitionParam`。 */
    data class Param(
        val mode: String,
        val usage: String,
        val location: String,
        val result: String,
        val roi: List<Int>,
    )

    /** 上游 `operatorConflictParam`。 */
    data class ConflictParam(val result: String, val usage: String, val location: String)

    /** 统一的返回：命中时带框与 detail。 */
    sealed interface Outcome {
        object Miss : Outcome

        /** [box] 为 null 表示宿主不回写 outBox。 */
        data class Hit(val box: OcrBox?, val detail: String) : Outcome
    }

    /** 缓存状态（CacheReady 用）。 */
    data class CacheStatus(val ready: Boolean, val updatedAt: String)

    // ───────────────────────── 参数解析 ─────────────────────────

    companion object {
        val MODES = setOf("cache", "refresh")
        val USAGES = setOf("target", "restore", "all")

        /** 上游 recognition.go:377 `parseOperatorRecognitionParam`。result 不校验。 */
        fun parseParam(tree: Any?): Param? {
            val map = tree as? Map<*, *> ?: return null
            val mode = (map["mode"] as? String)?.trim().orEmpty()
            if (mode !in MODES) return null
            val usage = (map["usage"] as? String)?.trim().orEmpty()
            if (usage !in USAGES) return null
            val location = (map["location"] as? String)?.trim().orEmpty()
            if (location.isEmpty()) return null
            val roi = (map["roi"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: return null
            if (roi.size != 4) return null
            return Param(
                mode = mode,
                usage = usage,
                location = location,
                result = (map["result"] as? String)?.trim().orEmpty(),
                roi = roi,
            )
        }

        /** 上游 conflict.go:124 `parseOperatorConflictParam`。 */
        fun parseConflictParam(tree: Any?): ConflictParam? {
            val map = tree as? Map<*, *> ?: return null
            val result = (map["result"] as? String)?.trim().orEmpty()
            if (result !in setOf("managed", "protected")) return null
            val usage = (map["usage"] as? String)?.trim().orEmpty()
            if (usage !in setOf("target", "restore")) return null
            val location = (map["location"] as? String)?.trim().orEmpty()
            if (location.isEmpty()) return null
            return ConflictParam(result, usage, location)
        }

        /**
         * 上游 conflict.go:144 `findOperatorConflictSource`。
         *
         * 匹配方向是**OCR 文本包含候选据点名**（不是反过来）；外层按屏幕顺序，
         * 内层按生成数据的 location_order（新地区优先）。
         * 返回 (来源据点, 提示文本, 是否识别出)。
         */
        fun findConflictSource(
            items: List<OcrItem>,
            data: OperatorDataset.SelectionFile,
        ): Triple<String, String, Boolean> {
            for (item in OperatorOcrMatch.sortItemsByPosition(items)) {
                val text = OperatorOcrMatch.stripSeparators(item.text)
                if (text.isEmpty()) continue
                for (locationName in data.locationOrder) {
                    val entry = data.locations[locationName] ?: continue
                    for (expected in OperatorDataset.expectedNames(entry.names)) {
                        val candidate = OperatorOcrMatch.stripSeparators(expected)
                        if (candidate.isNotEmpty() && text.contains(candidate)) {
                            return Triple(locationName, item.text, true)
                        }
                    }
                }
            }
            val sorted = OperatorOcrMatch.sortItemsByPosition(items)
            return if (sorted.isEmpty()) Triple("", "", false) else Triple("", sorted[0].text, false)
        }
    }

    // ───────────────────────── 六个 recognition ─────────────────────────

    /**
     * 上游 recognition.go:62 `SelectBestOperatorRecognition`。
     *
     * 只认规划出来的**第一名**；可见则命中并把框交给流水线点击。
     * 即使次优可见也不降级——继续滚动列表。
     */
    fun decideSelectBest(p: Param): Outcome {
        val sp = host.resolveSelection(p) ?: return Outcome.Miss
        val owned = host.loadOwnedNames() ?: return Outcome.Miss
        val candidates = OperatorSelection.candidatesForOwnership(sp, OperatorSelection.Ownership(owned))
        if (candidates.isEmpty()) return Outcome.Miss

        setPlannedRestore(sp, candidates)

        val items = host.listOcr(p.roi) ?: return Outcome.Miss
        val found = OperatorMatching.findBestVisibleOperator(candidates, items) ?: return Outcome.Miss

        recordTargetAssignment(p, found.first)
        host.scanStates.delete(OperatorScan.scanStateKey(host.currentUid(), p.mode, p.usage, p.location))
        return Outcome.Hit(found.second.box, "${found.second.ocrText}:${found.first.name}")
    }

    /** 上游 recognition.go:109 `CurrentBestOperatorRecognition`。 */
    fun decideCurrentBest(p: Param): Outcome {
        val sp = host.resolveSelection(p) ?: return Outcome.Miss
        val owned = host.loadOwnedNames() ?: return Outcome.Miss
        val ownership = OperatorSelection.Ownership(owned)

        val candidates = if (sp.usage == OperatorSelection.USAGE_TARGET) {
            // target：同档全部候选（用于判断「当前派驻已足够好」）
            OperatorSelection.equivalentTargetCandidatesForOwnership(sp, ownership)
        } else {
            OperatorSelection.candidatesForOwnership(sp, ownership)
        }
        if (candidates.isEmpty()) return Outcome.Miss

        setPlannedRestore(sp, candidates)

        val items = host.currentOcr(p.roi, reuse = true) ?: return Outcome.Miss
        val found = OperatorMatching.findCurrentBestOperator(candidates, sp.knownOperators, items)
            ?: return Outcome.Miss

        recordTargetAssignment(p, found.first)
        host.scanStates.delete(OperatorScan.scanStateKey(host.currentUid(), p.mode, p.usage, p.location))
        return Outcome.Hit(found.second.box, "${found.second.ocrText}:${found.first.name}")
    }

    /**
     * 上游 recognition.go:163 `CurrentOperatorUncachedRecognition`。
     *
     * 当前派驻是「已知但不在快照里」的人 → 说明快照过期，清掉它触发全量重扫。
     */
    fun decideUncached(p: Param): Outcome {
        val data = host.selectionData() ?: return Outcome.Miss
        if (data.knownOperators.isEmpty()) return Outcome.Miss
        val owned = host.loadOwnedNames() ?: return Outcome.Miss

        val items = host.currentOcr(p.roi, reuse = false) ?: return Outcome.Miss
        val found = OperatorMatching.findUncachedCurrentOperator(data.knownOperators, owned, items)
            ?: return Outcome.Miss

        // claim 必须在 Refreshed 检查之前：即使被拒，配额也已消耗
        if (!host.session.claimCacheRescan()) return Outcome.Miss
        if (host.session.isRefreshed()) return Outcome.Miss
        if (!host.invalidateSnapshot(host.currentUid())) return Outcome.Miss

        host.log("干员快照已过期，触发全量重扫（当前派驻=${found.first.name}）")
        return Outcome.Hit(found.second.box, "${found.second.ocrText}:${found.first.name}")
    }

    /** 上游 recognition.go:231 `OperatorCacheReadyRecognition`。 */
    fun decideCacheReady(p: Param, status: CacheStatus): Outcome {
        // 先 claim 再判 ready：不 ready 时这一次提示也已经用掉
        if (host.session.claimCacheNotice()) {
            host.log("干员缓存状态：ready=${status.ready} updated_at=${status.updatedAt}")
        }
        return if (status.ready) Outcome.Hit(null, "cache_ready") else Outcome.Miss
    }

    /**
     * 上游 recognition.go:261 `OperatorListBottomRecognition`：跨帧累积到「连续两帧同签名」。
     *
     * [listItems] 为本帧 OCR 结果；失败传 null（会写 error 并结束状态机）。
     */
    fun decideListBottom(p: Param, listItems: List<OcrItem>?): Outcome {
        val key = OperatorScan.scanStateKey(host.currentUid(), p.mode, p.usage, p.location)
        val existing = host.scanStates.copyOf(key) ?: OperatorScan.ScanState(key)

        // 已完成：只读结论，不再 OCR
        if (existing.completed) return bottomResult(p, existing)

        var state = existing

        fun fail(error: String): Outcome {
            state = state.copy(completed = true, error = error)
            host.scanStates.put(state)
            return Outcome.Miss
        }

        val sp = host.resolveSelection(p)
        if (sp == null) return fail(host.lastError())
        if (listItems == null) return fail(host.lastError())

        val scanCandidates = OperatorSelection.collectScanCandidates(sp)
        val frameObserved = OperatorScan.observedOperatorIds(listItems, scanCandidates)
        state = state.copy(observed = (state.observed + frameObserved).toMutableList())

        val signature = OperatorScan.signature(frameObserved)
        if (!OperatorScan.reachedBottom(state.previousSignature, signature)) {
            state = state.copy(previousSignature = signature)
            host.scanStates.put(state)
            return Outcome.Miss
        }

        // ── 到底 ──
        if (host.shouldWriteSnapshot(p)) {
            if (!host.writeSnapshot(p, scanCandidates, state.observed)) return fail(host.lastError())
        }
        val owned = host.loadOwnedNames() ?: return fail(host.lastError())

        val candidates = OperatorSelection.candidatesForOwnership(sp, OperatorSelection.Ownership(owned))
        setPlannedRestore(sp, candidates)

        val configured = configuredCandidatesForOutcome(sp)
        state = state.copy(
            expectedCandidates = OperatorScan.candidateIds(configured),
            observedCandidates = OperatorScan.observedConfiguredNames(configured, state.observed),
            completed = true,
            hasCandidate = candidates.isNotEmpty(),
        )

        if (p.result == "retry" && state.hasCandidate && !host.session.claimRetry(p.usage, p.location)) {
            // 重试配额用尽：转成失败，让同帧的 ScanOutcome 走 error 分支
            state = state.copy(error = "operator still unavailable after refreshed retry", hasCandidate = false)
        }
        if (p.result == "retry" && state.hasCandidate) {
            host.log("重新规划完成，改用 ${candidates.first().name}")
        }

        host.scanStates.put(state)
        return bottomResult(p, state)
    }

    /** 上游 recognition.go:339 `OperatorScanOutcomeRecognition`：只读已完成的扫描状态。 */
    fun decideScanOutcome(p: Param): Outcome {
        val key = OperatorScan.scanStateKey(host.currentUid(), p.mode, p.usage, p.location)
        val state = host.scanStates.copyOf(key) ?: return Outcome.Miss
        if (!state.completed) return Outcome.Miss

        when (p.result) {
            "error" -> {
                if (state.error.isEmpty()) return Outcome.Miss
                host.log("干员列表扫描失败：${state.error}")
            }

            "not_found" -> {
                if (state.error.isNotEmpty() || state.hasCandidate) return Outcome.Miss
                if (p.usage == OperatorSelection.USAGE_TARGET) host.log("没有可用的售卖干员")
            }

            else -> return Outcome.Miss
        }

        host.scanStates.delete(key)
        return Outcome.Hit(
            null,
            OperatorScan.outcomeDetailJson(p.result, p.usage, p.location, state),
        )
    }

    /**
     * 上游 conflict.go:41 `OperatorConflictRecognition`。
     *
     * 参数里声明的 `result` 必须与实际判定一致才命中：`managed` = 来源据点在启用集合里。
     */
    fun decideConflict(p: ConflictParam, ocrItems: List<OcrItem>): Outcome {
        val data = host.selectionFile() ?: return Outcome.Miss
        val (source, promptText, recognized) = findConflictSource(ocrItems, data)
        val managed = recognized && source in host.session.snapshot().activeLocations
        if ((p.result == "managed") != managed) return Outcome.Miss

        val payload = linkedMapOf<String, Any?>(
            "result" to p.result,
            "usage" to p.usage,
            "location" to p.location,
        )
        if (source.isNotEmpty()) payload["source_location"] = source
        payload["source_managed"] = managed
        if (promptText.isNotEmpty()) payload["prompt_text"] = promptText
        return Outcome.Hit(null, JsonTree.toJson(payload))
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** 上游只在 restore 时登记计划。 */
    private fun setPlannedRestore(sp: OperatorSelection.SelectionParam, candidates: List<Candidate>) {
        if (sp.usage != OperatorSelection.USAGE_RESTORE) return
        host.session.setPlannedRestore(sp.location, candidates.firstOrNull())
    }

    /** 上游只在 target 时登记售卖分配。 */
    private fun recordTargetAssignment(p: Param, candidate: Candidate) {
        if (p.usage == OperatorSelection.USAGE_TARGET) {
            host.session.setTargetAssignment(p.location, candidate)
        }
    }

    /** 上游 scan.go:319 `configuredCandidatesForOutcome`。 */
    private fun configuredCandidatesForOutcome(sp: OperatorSelection.SelectionParam): List<Candidate> =
        if (sp.usage == OperatorSelection.USAGE_TARGET) {
            sp.candidates
        } else {
            sp.restoreGroups.firstOrNull { it.location == sp.location }?.candidates ?: emptyList()
        }

    /** 上游 scan.go:302 `operatorListBottomResult`。 */
    private fun bottomResult(p: Param, state: OperatorScan.ScanState): Outcome {
        if (state.error.isNotEmpty()) return Outcome.Miss
        if (!OperatorScan.shouldHit(p.result, state.hasCandidate)) return Outcome.Miss
        host.scanStates.delete(state.key)
        return if (p.result == "not_found") {
            Outcome.Hit(null, OperatorScan.outcomeDetailJson(p.result, p.usage, p.location, state))
        } else {
            Outcome.Hit(null, p.result)
        }
    }
}
