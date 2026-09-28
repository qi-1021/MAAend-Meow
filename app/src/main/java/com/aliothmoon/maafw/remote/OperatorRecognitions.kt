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
class OperatorRecognitions(
    private val host: Host,
    /**
     * 诊断埋点出口；由宿主注入到 `RunDiagnostics.note("operator", ...)`。
     * 默认丢弃——纯逻辑单测不关心埋点，这一层也不依赖 Android/JNA。
     */
    private val note: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    /** 埋点是否真的会落盘；false 时跳过昂贵的诊断计算（如整帧 OCR×候选匹配摘要）。 */
    private val diagnosticsEnabled: () -> Boolean = { false },
) {

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
        val sp = host.resolveSelection(p)
        if (sp == null) {
            noteDedup(
                "select_best",
                "unresolved|${p.mode}|${p.usage}|${p.location}",
                "target 选择跳过｜selection 参数不可用（usage=${p.usage} location=${p.location} mode=${p.mode}）",
                mapOf("stage" to "select_best", "result" to "unresolved", "usage" to p.usage, "location" to p.location),
            )
            return Outcome.Miss
        }
        val owned = host.loadOwnedNames() ?: return Outcome.Miss
        val candidates = OperatorSelection.candidatesForOwnership(sp, OperatorSelection.Ownership(owned))
        if (candidates.isEmpty()) {
            noteDedup(
                "select_best",
                "no_candidate|${p.usage}|${p.location}",
                "target 无可用候选｜owned=${owned.size} 拉黑=${sp.excludedOperators.size} 据点=${p.location} usage=${p.usage}",
                mapOf(
                    "stage" to "select_best",
                    "result" to "no_candidate",
                    "usage" to p.usage,
                    "location" to p.location,
                    "owned" to owned.size,
                    "excluded" to sp.excludedOperators.toList(),
                ),
            )
            return Outcome.Miss
        }

        setPlannedRestore(sp, candidates)

        val items = host.listOcr(p.roi) ?: return Outcome.Miss
        val found = OperatorMatching.findBestVisibleOperator(candidates, items)
        if (found == null) {
            if (diagnosticsEnabled()) {
                val digest = digestMatches(items, candidates)
                val picked = candidates.first()
                noteDedup(
                    "select_best",
                    "miss|${p.usage}|${p.location}|${items.map { it.text }.sorted().joinToString("|")}",
                    buildString {
                        append("target 选定 ").append(picked.name).append(" 但本帧未命中｜")
                        append(if (digest.names.isEmpty()) "画面无任何候选" else "画面可见其他候选=${digest.names}")
                        append("，期望名=").append(picked.expected)
                        append("，OCR 样本=").append(preview(items.map { it.text }, 3))
                    },
                    mapOf(
                        "stage" to "select_best",
                        "result" to "ocr_miss",
                        "usage" to p.usage,
                        "location" to p.location,
                        "picked" to picked.name,
                        "expected" to picked.expected,
                        "visible" to digest.names,
                        "unmatched" to digest.unmatched,
                        "ocr" to items.map { it.text },
                    ),
                )
            }
            return Outcome.Miss
        }

        recordTargetAssignment(p, found.first)
        host.scanStates.delete(OperatorScan.scanStateKey(host.currentUid(), p.mode, p.usage, p.location))
        if (diagnosticsEnabled()) {
            noteSelection(sp, owned, candidates)
            note(
                "target 命中｜${found.first.name}←'${found.second.ocrText}'(tier=${found.second.tier})",
                mapOf(
                    "stage" to "select_best",
                    "result" to "hit",
                    "usage" to p.usage,
                    "location" to p.location,
                    "picked" to found.first.name,
                    "ocr" to found.second.ocrText,
                    "tier" to found.second.tier,
                ),
            )
        }
        return Outcome.Hit(found.second.box, "${found.second.ocrText}:${found.first.name}")
    }

    /** 上游 recognition.go:109 `CurrentBestOperatorRecognition`。 */
    fun decideCurrentBest(p: Param): Outcome {
        val sp = host.resolveSelection(p)
        if (sp == null) {
            noteDedup(
                "current_best",
                "unresolved|${p.mode}|${p.usage}|${p.location}",
                "当前派驻选择跳过｜selection 参数不可用（usage=${p.usage} location=${p.location} mode=${p.mode}）",
                mapOf("stage" to "current_best", "result" to "unresolved", "usage" to p.usage, "location" to p.location),
            )
            return Outcome.Miss
        }
        val owned = host.loadOwnedNames() ?: return Outcome.Miss
        val ownership = OperatorSelection.Ownership(owned)

        val candidates = if (sp.usage == OperatorSelection.USAGE_TARGET) {
            // target：同档全部候选（用于判断「当前派驻已足够好」）
            OperatorSelection.equivalentTargetCandidatesForOwnership(sp, ownership)
        } else {
            OperatorSelection.candidatesForOwnership(sp, ownership)
        }
        if (candidates.isEmpty()) {
            noteDedup(
                "current_best",
                "no_candidate|${p.usage}|${p.location}",
                "当前派驻无可用候选｜owned=${owned.size} 拉黑=${sp.excludedOperators.size} 据点=${p.location} usage=${p.usage}",
                mapOf(
                    "stage" to "current_best",
                    "result" to "no_candidate",
                    "usage" to p.usage,
                    "location" to p.location,
                    "owned" to owned.size,
                    "excluded" to sp.excludedOperators.toList(),
                ),
            )
            return Outcome.Miss
        }

        setPlannedRestore(sp, candidates)

        val items = host.currentOcr(p.roi, reuse = true) ?: return Outcome.Miss
        val found = OperatorMatching.findCurrentBestOperator(candidates, sp.knownOperators, items)
        if (found == null) {
            if (diagnosticsEnabled()) {
                val digest = digestMatches(items, candidates)
                noteDedup(
                    "current_best",
                    "miss|${p.usage}|${p.location}|${items.map { it.text }.sorted().joinToString("|")}",
                    buildString {
                        append("当前派驻未命中候选=").append(candidates.take(3).map { it.name })
                        append("｜画面").append(if (digest.names.isEmpty()) "无任何候选" else "可见=${digest.names}")
                        append("，OCR 样本=").append(preview(items.map { it.text }, 3))
                    },
                    mapOf(
                        "stage" to "current_best",
                        "result" to "ocr_miss",
                        "usage" to p.usage,
                        "location" to p.location,
                        "candidates" to candidates.map { it.name },
                        "visible" to digest.names,
                        "unmatched" to digest.unmatched,
                        "ocr" to items.map { it.text },
                    ),
                )
            }
            return Outcome.Miss
        }

        recordTargetAssignment(p, found.first)
        host.scanStates.delete(OperatorScan.scanStateKey(host.currentUid(), p.mode, p.usage, p.location))
        if (diagnosticsEnabled()) {
            noteSelection(sp, owned, candidates)
            note(
                "当前派驻命中｜${found.first.name}←'${found.second.ocrText}'(tier=${found.second.tier})",
                mapOf(
                    "stage" to "current_best",
                    "result" to "hit",
                    "usage" to p.usage,
                    "location" to p.location,
                    "picked" to found.first.name,
                    "ocr" to found.second.ocrText,
                    "tier" to found.second.tier,
                ),
            )
        }
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
        if (found == null) return Outcome.Miss

        if (diagnosticsEnabled()) {
            val digest = digestMatches(items, data.knownOperators)
            if (digest.names.size > 1) {
                note(
                    "当前派驻命中多个已知干员=${digest.names}，取首个 ${found.first.name}",
                    mapOf("stage" to "uncached_multi", "location" to p.location, "matched" to digest.names),
                )
            }
        }

        // claim 必须在 Refreshed 检查之前：即使被拒，配额也已消耗
        if (!host.session.claimCacheRescan()) {
            noteDedup(
                "uncached",
                "quota",
                "当前派驻 ${found.first.name} 未缓存，但本任务重扫配额已用尽",
                mapOf("stage" to "uncached", "result" to "quota_used", "picked" to found.first.name),
            )
            return Outcome.Miss
        }
        if (host.session.isRefreshed()) {
            noteDedup(
                "uncached",
                "refreshed",
                "当前派驻 ${found.first.name} 未缓存，但本任务已全量扫过，不再重扫",
                mapOf("stage" to "uncached", "result" to "already_refreshed", "picked" to found.first.name),
            )
            return Outcome.Miss
        }
        if (!host.invalidateSnapshot(host.currentUid())) {
            noteDedup(
                "uncached",
                "invalidate",
                "当前派驻 ${found.first.name} 未缓存，但清快照失败",
                mapOf("stage" to "uncached", "result" to "invalidate_failed", "picked" to found.first.name),
            )
            return Outcome.Miss
        }

        host.log("干员快照已过期，触发全量重扫（当前派驻=${found.first.name}）")
        note(
            "检出当前派驻未缓存：${found.first.name}（ocr='${found.second.ocrText}' tier=${found.second.tier}），已清快照触发全量重扫",
            mapOf(
                "stage" to "uncached",
                "result" to "invalidate_ok",
                "picked" to found.first.name,
                "ocr" to found.second.ocrText,
                "tier" to found.second.tier,
                "owned" to owned.size,
            ),
        )
        return Outcome.Hit(found.second.box, "${found.second.ocrText}:${found.first.name}")
    }

    /** 上游 recognition.go:231 `OperatorCacheReadyRecognition`。 */
    fun decideCacheReady(p: Param, status: CacheStatus): Outcome {
        // 先 claim 再判 ready：不 ready 时这一次提示也已经用掉
        if (host.session.claimCacheNotice()) {
            host.log("干员缓存状态：ready=${status.ready} updated_at=${status.updatedAt}")
            note(
                "缓存就绪检查｜ready=${status.ready} updated_at=${status.updatedAt}",
                mapOf("stage" to "cache_ready", "ready" to status.ready, "updated_at" to status.updatedAt),
            )
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
            clearScanPage(key)
            note(
                "扫描失败｜$error（usage=${p.usage} location=${p.location} mode=${p.mode}）",
                mapOf("stage" to "scan_fail", "usage" to p.usage, "location" to p.location, "error" to error),
            )
            return Outcome.Miss
        }

        val sp = host.resolveSelection(p)
        if (sp == null) return fail(host.lastError())
        if (listItems == null) return fail(host.lastError())

        val scanCandidates = OperatorSelection.collectScanCandidates(sp)
        val frameObserved = OperatorScan.observedOperatorIds(listItems, scanCandidates)
        state = state.copy(observed = (state.observed + frameObserved).toMutableList())

        val signature = OperatorScan.signature(frameObserved)

        // 埋点：只有「新的一屏」（签名相对上一帧变化）才记。同一帧被识别框架重复回调、
        // 或 OCR 短暂失败后重试导致的同签名，都不会重复刷屏。
        if (signature.isNotEmpty() && signature != state.previousSignature && diagnosticsEnabled()) {
            val page = bumpScanPage(key)
            val digest = digestMatches(listItems, scanCandidates)
            val message = buildString {
                append("扫描第 ").append(page).append(" 页｜OCR ").append(listItems.size)
                append(" 条，可见候选 ").append(digest.names.size)
                if (digest.names.isNotEmpty()) append("=").append(digest.names)
                append("，样本=").append(preview(listItems.map { it.text }))
                if (digest.unmatched.isNotEmpty()) append("，未匹配 ").append(digest.unmatched.size).append(" 条")
            }
            note(
                message,
                mapOf(
                    "stage" to "scan",
                    "page" to page,
                    "usage" to p.usage,
                    "mode" to p.mode,
                    "location" to p.location,
                    "count" to listItems.size,
                    "observed" to frameObserved,
                    "matched" to digest.pairs,
                    "unmatched" to digest.unmatched,
                    "sample" to listItems.map { it.text },
                ),
            )
            if (digest.names.size > 1) {
                note(
                    "扫描第 $page 页命中多个候选（${digest.names.size}）：${digest.names}",
                    mapOf(
                        "stage" to "scan_multi",
                        "page" to page,
                        "location" to p.location,
                        "matched" to digest.names,
                    ),
                )
            }
        }

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

        if (diagnosticsEnabled()) {
            val pages = currentScanPage(key)
            val observed = state.observed.distinct()
            note(
                buildString {
                    append("扫描到底｜共 ").append(pages).append(" 页，累计可见候选 ").append(observed.size)
                    append(" 个，owned=").append(owned.size)
                    append("，本轮可用候选=").append(candidates.size)
                    if (candidates.isNotEmpty()) append("（首选 ${candidates.first().name}）")
                },
                mapOf(
                    "stage" to "scan_bottom",
                    "pages" to pages,
                    "usage" to p.usage,
                    "location" to p.location,
                    "observed" to observed,
                    "owned" to owned.size,
                    "candidates" to candidates.map { it.name },
                ),
            )
        }
        clearScanPage(key)

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
                note(
                    "扫描结论=失败｜${state.error}（usage=${p.usage} location=${p.location}）",
                    mapOf(
                        "stage" to "scan_outcome",
                        "result" to "error",
                        "usage" to p.usage,
                        "location" to p.location,
                        "error" to state.error,
                        "observed" to state.observedCandidates,
                    ),
                )
            }

            "not_found" -> {
                if (state.error.isNotEmpty() || state.hasCandidate) return Outcome.Miss
                if (p.usage == OperatorSelection.USAGE_TARGET) host.log("没有可用的售卖干员")
                note(
                    "扫描结论=无可用候选｜usage=${p.usage} location=${p.location} " +
                        "期望=${state.expectedCandidates} 实际可见=${state.observedCandidates}",
                    mapOf(
                        "stage" to "scan_outcome",
                        "result" to "not_found",
                        "usage" to p.usage,
                        "location" to p.location,
                        "expected" to state.expectedCandidates,
                        "observed" to state.observedCandidates,
                    ),
                )
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
        note(
            "冲突识别｜result=${p.result} 来源=${source.ifEmpty { "未识别" }} 启用=$managed 提示='$promptText'",
            mapOf(
                "stage" to "conflict",
                "result" to p.result,
                "usage" to p.usage,
                "location" to p.location,
                "source" to source,
                "managed" to managed,
                "recognized" to recognized,
                "prompt" to promptText,
            ),
        )
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

    // ───────────────────────── 诊断埋点（只读，不参与任何判定） ─────────────────────────
    //
    // 这里的东西全部只服务 RunDiagnostics 报告：读 OCR / 候选 / 会话快照，拼人话与 extra。
    // 刻意不碰判定分支、不写回任何决策状态，也不依赖 Android；关闭埋点时根本不会被调用。

    /** 每个扫描 key 已记录到第几页，仅用于人话里的页码。 */
    private val scanPageCount = mutableMapOf<String, Int>()

    /** 连续重复帧去重：scope -> 上一次记录的帧指纹。 */
    private val lastFingerprint = mutableMapOf<String, String>()

    private fun bumpScanPage(key: String): Int = synchronized(scanPageCount) {
        val next = (scanPageCount[key] ?: 0) + 1
        scanPageCount[key] = next
        next
    }

    private fun currentScanPage(key: String): Int = synchronized(scanPageCount) {
        scanPageCount[key] ?: 0
    }

    private fun clearScanPage(key: String) {
        synchronized(scanPageCount) { scanPageCount.remove(key) }
    }

    /** 同一 scope 连续相同指纹只记一次；指纹变化则覆盖并放行，避免同帧重试刷屏。 */
    private fun noteDedup(scope: String, fingerprint: String, message: String, extra: Map<String, Any?>) {
        val previous = synchronized(lastFingerprint) {
            val prev = lastFingerprint[scope]
            lastFingerprint[scope] = fingerprint
            prev
        }
        if (previous != fingerprint) note(message, extra)
    }

    /** 一帧 OCR 与候选集匹配情况的只读摘要。 */
    private data class MatchDigest(
        /** 形如 `赛希←'赛希'(tier=A)`，按屏幕顺序。 */
        val pairs: List<String>,
        /** 命中的候选名（去重）。 */
        val names: List<String>,
        /** 未与任何候选对上的 OCR 明细：原始文本 + 两层归一化结果。 */
        val unmatched: List<Map<String, Any?>>,
    )

    /**
     * 逐个 OCR 文本去试候选集，得到「认出了谁 / 谁没认出 + 归一化后长什么样」。
     *
     * 只是诊断视角（item → 第一个命中候选），与 [OperatorScan.observedOperatorIds]
     * 的「候选 → 是否可见」互补；顺序按屏幕位置，便于对着真机截图看。
     */
    private fun digestMatches(items: List<OcrItem>, candidates: List<Candidate>): MatchDigest {
        val pairs = mutableListOf<String>()
        val names = mutableListOf<String>()
        val unmatched = mutableListOf<Map<String, Any?>>()
        for (item in OperatorOcrMatch.sortItemsByPosition(items)) {
            var hitName: String? = null
            var hitTier = ""
            for (candidate in candidates) {
                val m = OperatorOcrMatch.findBest(listOf(item), candidate.expected) ?: continue
                hitName = candidate.name
                hitTier = m.tier
                break
            }
            if (hitName != null) {
                pairs += "$hitName←'${item.text}'(tier=$hitTier)"
                names += hitName
            } else {
                val norm = OperatorOcrMatch.stripSeparators(item.text)
                val core = OperatorOcrMatch.stripAsciiAlnum(norm)
                unmatched += mapOf<String, Any?>(
                    "ocr" to item.text,
                    "norm" to norm,
                    "core" to core,
                    // 「为什么没认出来」：是归一化后整个空了，还是归一化没问题但不在任何候选期望名里
                    "reason" to if (norm.isEmpty()) "归一化后为空" else "归一化后不在候选期望名中",
                )
            }
        }
        return MatchDigest(pairs, names.distinct(), unmatched)
    }

    /** 列表样本截断，避免单条 note 过长。 */
    private fun preview(values: List<String>, limit: Int = 3): List<String> =
        if (values.size <= limit) values else values.take(limit) + "…(+${values.size - limit})"

    /** 选人依据：usage、档位池大小、是否完美候选、优先级，以及本轮被拉黑的干员。 */
    private fun noteSelection(
        sp: OperatorSelection.SelectionParam,
        owned: Set<String>,
        candidates: List<Candidate>,
    ) {
        val picked = candidates.first()
        val isTarget = sp.usage == OperatorSelection.USAGE_TARGET
        val pool = if (isTarget) {
            OperatorSelection.equivalentTargetCandidatesForOwnership(sp, OperatorSelection.Ownership(owned))
        } else {
            candidates
        }
        val perfect = OperatorSelection.restoreCandidateNames(sp.restoreGroups, sp.location)?.contains(picked.name) == true
        // 其它据点已锁定的恢复干员：本据点选择时被剔除，属于「被排除」但不进拉黑集合
        val lockedOther = sp.lockedRestoreAssignments
            .filterKeys { it != sp.location }
            .values.map { it.name }
        val message = buildString {
            append("选人｜usage=").append(sp.usage)
            append(" 选定=").append(picked.name)
            append("（bonusTier=").append(picked.bonusTier)
            append(" priority=").append(picked.priority).append("）")
            append(" 档位候选=").append(pool.size)
            if (isTarget) append(" 完美候选=").append(if (perfect) "是" else "否")
            append(" 拉黑=").append(sp.excludedOperators.size)
            if (sp.excludedOperators.isNotEmpty()) append(sp.excludedOperators.toList())
            if (lockedOther.isNotEmpty()) append(" 他据已锁=").append(lockedOther)
        }
        note(
            message,
            mapOf(
                "stage" to "selection",
                "usage" to sp.usage,
                "location" to sp.location,
                "picked" to picked.name,
                "bonus_tier" to picked.bonusTier,
                "priority" to picked.priority,
                "tier_pool" to pool.map { it.name },
                "perfect" to perfect,
                "owned" to owned.size,
                "excluded" to sp.excludedOperators.toList(),
                "locked_other" to lockedOther,
            ),
        )
    }
}
