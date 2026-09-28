package com.aliothmoon.maafw.remote

/**
 * 干员候选与 OCR 结果的匹配（对齐上游 `outposttrading/operator/matching.go`）。
 *
 * 底层的严格分层匹配在 [OperatorOcrMatch]；这里只处理「用哪一组候选去比」以及
 * 上游为「当前干员」额外加的一层**前缀噪声回退**。
 *
 * 那层回退要小心：OCR 会把「当前干员名」和右侧界面文字并成一条，
 * 所以允许「目标名是 OCR 文本前缀」，但**必须**排除「还存在更长的已知干员名也匹配该前缀」的情况，
 * 否则短名会把长名吃掉（比如同时存在「测试甲」与「测试甲乙」时，不能把后者认成前者）。
 */
object OperatorMatching {

    private typealias Candidate = OperatorDataset.OperatorCandidate
    private typealias OcrItem = OperatorOcrMatch.Item

    /**
     * 上游 matching.go:11 `findBestVisibleOperator`。
     *
     * 只匹配计划指定的**全局最优候选**（`candidates[0]`）。即使次优候选当前可见，
     * 也必须继续滚动找第一名，不能提前降级选择。
     */
    fun findBestVisibleOperator(
        candidates: List<Candidate>,
        items: List<OcrItem>,
    ): Pair<Candidate, OperatorOcrMatch.Result>? {
        val candidate = candidates.firstOrNull() ?: return null
        val match = OperatorOcrMatch.findBest(items, candidate.expected) ?: return null
        return candidate to match
    }

    /**
     * 上游 matching.go:27 `findCurrentBestOperator`。
     *
     * 按稳定顺序遍历候选，逐个先走精确分层匹配，失败再走前缀噪声回退。
     */
    fun findCurrentBestOperator(
        candidates: List<Candidate>,
        knownOperators: List<Candidate>,
        items: List<OcrItem>,
    ): Pair<Candidate, OperatorOcrMatch.Result>? {
        if (candidates.isEmpty()) return null
        for (candidate in candidates) {
            val match = OperatorOcrMatch.findBest(items, candidate.expected)
                ?: findCurrentOperatorPrefixMatch(items, candidate, knownOperators)
            if (match != null) return candidate to match
        }
        return null
    }

    /**
     * 上游 matching.go:49 `findUncachedCurrentOperator`。
     *
     * 判断当前派驻干员是否为「已知但未进入缓存快照」的人。未识别出已知干员时**不作结论**，
     * 避免 OCR 噪声误判成「缓存过期」而触发无谓的全量重扫。
     */
    fun findUncachedCurrentOperator(
        knownOperators: List<Candidate>,
        ownedNames: Set<String>,
        items: List<OcrItem>,
    ): Pair<Candidate, OperatorOcrMatch.Result>? {
        val found = findCurrentBestOperator(knownOperators, knownOperators, items) ?: return null
        if (found.first.name in ownedNames) return null
        return found
    }

    /**
     * 上游 matching.go:66 `findCurrentOperatorPrefixMatch`。
     *
     * 仅当「目标名是 OCR 文本前缀」且「不存在更长的已知干员名同样匹配该前缀」时命中。
     */
    fun findCurrentOperatorPrefixMatch(
        items: List<OcrItem>,
        target: Candidate,
        knownOperators: List<Candidate>,
    ): OperatorOcrMatch.Result? {
        for (item in OperatorOcrMatch.sortItemsByPosition(items)) {
            val ocrCore = OperatorOcrMatch.stripSeparators(item.text)
            if (ocrCore.isEmpty()) continue
            for (candidate in target.expected) {
                val candidateCore = OperatorOcrMatch.stripSeparators(candidate)
                if (candidateCore.isEmpty() || ocrCore == candidateCore) continue
                if (!ocrCore.startsWith(candidateCore)) continue
                if (hasLongerKnownOperatorPrefix(ocrCore, candidateCore, target, knownOperators)) continue
                return OperatorOcrMatch.Result(
                    ocrText = item.text,
                    candidate = candidate,
                    tier = OperatorOcrMatch.TIER_PREFIX_NOISE,
                    box = item.box,
                )
            }
        }
        return null
    }

    /**
     * 上游 matching.go:97 `hasLongerKnownOperatorPrefix`。
     *
     * OCR 更可能是另一个名字更长的已知干员吗？长度按**码点**比较（CJK 与 emoji 都算一个）。
     */
    fun hasLongerKnownOperatorPrefix(
        ocrCore: String,
        targetCore: String,
        target: Candidate,
        knownOperators: List<Candidate>,
    ): Boolean {
        val targetLength = targetCore.codePointCount()
        for (operator in knownOperators) {
            if (operator.name == target.name) continue
            for (expected in operator.expected) {
                val knownCore = OperatorOcrMatch.stripSeparators(expected)
                if (knownCore.codePointCount() <= targetLength) continue
                if (ocrCore.startsWith(knownCore)) return true
            }
        }
        return false
    }

    private fun String.codePointCount(): Int {
        var count = 0
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            i += Character.charCount(cp)
            count++
        }
        return count
    }
}
