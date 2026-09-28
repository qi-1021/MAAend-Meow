package com.aliothmoon.maafw.remote

/**
 * OutpostTrading 运行时生成数据（`data/OutpostTrading/selection_data.json`）的加载、校验与派生。
 *
 * 对齐上游两处：
 *  - `outposttrading/internal/selectiondata/data.go`（加载/校验/本地化名）
 *  - `outposttrading/operator/data.go`（展开出干员候选集）
 *
 * 数据来源：本仓库的打包链路把上游 assets 目录打成 `assets/pi.zip`，App 解包到
 * `<externalFilesDir>/pi/`，所以运行时路径是 `projectRoot/data/OutpostTrading/selection_data.json`。
 * **不要**照抄 `MaaRunner.loadDeliveryCatalogFromApk()` 的 `assets/data/...`——
 * 当前打包方式下 APK 根 assets 里没有散装 data 目录。
 */
object OperatorDataset {

    /** 语言顺序固定为 zh_cn → zh_tw → en_us → ja_jp → ko_kr（上游 i18n.go:20-26）。 */
    val LANG_ORDER = listOf("zh_cn", "zh_tw", "en_us", "ja_jp", "ko_kr")

    const val DEFAULT_LANG = "zh_cn"

    /** 上游 selectiondata.File。 */
    data class SelectionFile(
        val items: Map<String, NamedEntry>,
        val operators: Map<String, NamedEntry>,
        val locationOrder: List<String>,
        val locations: Map<String, LocationEntry>,
    )

    /** 上游 selectiondata.Item / Operator：都只有一组本地化名。 */
    data class NamedEntry(val names: Map<String, String>)

    /** 上游 selectiondata.TargetOperator。 */
    data class TargetOperatorEntry(
        val name: String,
        val bonusTier: Int,
        val outpostProsperityMaxBonusTier: Int,
    )

    /** 上游 selectiondata.Location（货品的 items 也保留，便于后续与货品子系统统一数据源）。 */
    data class LocationEntry(
        val names: Map<String, String>,
        val targetOperators: List<TargetOperatorEntry>,
        val restoreOperators: List<String>,
    )

    /** 上游 operator/data.go:30 `operatorCandidate`。Priority 越小越优先。 */
    data class OperatorCandidate(
        val name: String,
        val expected: List<String>,
        val priority: Int,
        val bonusTier: Int,
        val outpostProsperityMaxBonusTier: Int,
    )

    /** 上游 operator/data.go:39 `operatorCandidateGroup`。 */
    data class OperatorCandidateGroup(
        val location: String,
        val candidates: List<OperatorCandidate>,
    )

    /** 上游 operator/data.go:21 `operatorSelectionData`。 */
    data class OperatorSelectionData(
        val targetCandidates: Map<String, List<OperatorCandidate>>,
        val restoreGroups: List<OperatorCandidateGroup>,
        val knownOperators: List<OperatorCandidate>,
        val locationOrder: List<String>,
    )

    /** 当前语言；由宿主设置（上游读进程级 i18n.Lang()）。 */
    @Volatile
    var currentLanguage: String = DEFAULT_LANG

    // ───────────────────────── 解析与校验 ─────────────────────────

    /** 从已解析的通用 JSON 树构造 [SelectionFile]；结构不对时返回 null。 */
    fun fromTree(tree: Any?): SelectionFile? {
        val root = tree as? Map<*, *> ?: return null

        val items = namedMap(root["items"]) ?: return null
        val operators = namedMap(root["operators"]) ?: return null
        val locationOrder = stringList(root["location_order"]) ?: return null
        val locations = locationMap(root["locations"]) ?: return null

        return SelectionFile(items, operators, locationOrder, locations)
    }

    /** 便捷入口：从 JSON 文本构造。 */
    fun parse(jsonText: String?): SelectionFile? = fromTree(MaaJsonTree.parse(jsonText))

    /** 上游 selectiondata.go:117 `Validate`：据点目录非空。 */
    fun validate(data: SelectionFile?): Boolean {
        if (data == null) return false
        return data.locationOrder.isNotEmpty() && data.locations.isNotEmpty()
    }

    /** 上游 selectiondata.go:139 `ValidateOperators`。 */
    fun validateOperators(data: SelectionFile?): Boolean {
        if (!validate(data)) return false
        return data!!.operators.isNotEmpty()
    }

    // ───────────────────────── 本地化名 ─────────────────────────

    /** 上游 selectiondata.go:150 `LocalizedName`：当前语言 → 默认语言 → 回退值。 */
    fun localizedName(names: Map<String, String>, fallback: String): String {
        val lang = normalizeLang(currentLanguage)
        names[lang]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        names[DEFAULT_LANG]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return fallback.trim()
    }

    /** 上游 selectiondata.go:195 `ExpectedNames`：按仓库语言顺序返回去重后的 OCR 候选名。 */
    fun expectedNames(names: Map<String, String>): List<String> =
        uniqueNonEmptyStrings(LANG_ORDER.map { names[it].orEmpty() })

    /** 上游 i18n.go:105 `NormalizeLang`：不认识的都回退默认语言。 */
    fun normalizeLang(raw: String): String =
        if (raw in LANG_ORDER) raw else DEFAULT_LANG

    // ───────────────────────── 派生候选集 ─────────────────────────

    /** 上游 operator/data.go:71 `buildOperatorSelectionData`。 */
    fun buildOperatorSelectionData(data: SelectionFile?): OperatorSelectionData? {
        if (!validateOperators(data)) return null
        val file = data!!

        val targetCandidates = mutableMapOf<String, List<OperatorCandidate>>()
        val restoreGroups = mutableListOf<OperatorCandidateGroup>()

        // 已知干员：按名字排序后，索引即 priority
        val operatorNames = file.operators.keys.sorted()
        val known = mutableListOf<OperatorCandidate>()
        for ((priority, name) in operatorNames.withIndex()) {
            val candidate = candidateFromData(file, name, priority, 0, 0) ?: return null
            known += candidate
        }

        for (locationName in file.locationOrder) {
            val location = file.locations[locationName] ?: return null

            val targets = location.targetOperators.mapIndexed { priority, entry ->
                candidateFromData(file, entry.name, priority, entry.bonusTier, entry.outpostProsperityMaxBonusTier)
                    ?: return null
            }
            targetCandidates[locationName] = normalizeOperatorCandidates(targets)

            val restores = location.restoreOperators.mapIndexed { priority, name ->
                candidateFromData(file, name, priority, 0, 0) ?: return null
            }
            val normalizedRestores = normalizeOperatorCandidates(restores)
            if (normalizedRestores.isNotEmpty()) {
                restoreGroups += OperatorCandidateGroup(locationName, normalizedRestores)
            }
        }

        return OperatorSelectionData(
            targetCandidates = targetCandidates,
            restoreGroups = normalizeOperatorCandidateGroups(restoreGroups),
            knownOperators = normalizeOperatorCandidates(known),
            locationOrder = file.locationOrder.toList(),
        )
    }

    /** 上游 operator/data.go:156 `operatorCandidateFromData`。 */
    fun candidateFromData(
        data: SelectionFile,
        name: String,
        priority: Int,
        bonusTier: Int,
        outpostProsperityMaxBonusTier: Int,
    ): OperatorCandidate? {
        val trimmed = name.trim()
        val entry = data.operators[trimmed] ?: return null
        val candidate = OperatorCandidate(
            name = trimmed,
            expected = expectedNames(entry.names),
            priority = priority,
            bonusTier = bonusTier,
            outpostProsperityMaxBonusTier = outpostProsperityMaxBonusTier,
        )
        // 上游会把单个候选再走一遍 normalize 校验：名字或候选名全空即视为数据无效
        return normalizeOperatorCandidates(listOf(candidate)).firstOrNull()
    }

    /**
     * 上游 operator/data.go:184 `normalizeOperatorCandidates`。
     *
     * 清洗 + 按内部 Name 去重（同名只保留第一次出现）+ 按 BonusTier↑ / Priority↑ 稳定排序。
     */
    fun normalizeOperatorCandidates(candidates: List<OperatorCandidate>): List<OperatorCandidate> {
        val normalized = mutableListOf<OperatorCandidate>()
        val seen = mutableSetOf<String>()
        for (candidate in candidates) {
            val name = candidate.name.trim()
            val expected = uniqueNonEmptyStrings(candidate.expected)
            if (name.isEmpty() || expected.isEmpty()) continue
            if (!seen.add(name)) continue
            normalized += candidate.copy(name = name, expected = expected)
        }
        // 上游 sort.SliceStable：只比 BonusTier 与 Priority，同值保持原顺序
        return normalized.sortedWith(compareBy({ it.bonusTier }, { it.priority }))
    }

    /** 上游 operator/data.go:229 `normalizeOperatorCandidateGroups`。 */
    fun normalizeOperatorCandidateGroups(
        groups: List<OperatorCandidateGroup>,
    ): List<OperatorCandidateGroup> {
        val normalized = mutableListOf<OperatorCandidateGroup>()
        val seen = mutableSetOf<String>()
        for (group in groups) {
            val location = group.location.trim()
            if (location.isEmpty()) continue
            if (!seen.add(location)) continue
            val candidates = normalizeOperatorCandidates(group.candidates)
            if (candidates.isEmpty()) continue
            normalized += OperatorCandidateGroup(location, candidates)
        }
        return normalized
    }

    /** 上游 selectiondata.go:205 `uniqueNonEmptyStrings`：trim + 去重保序。 */
    fun uniqueNonEmptyStrings(values: List<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (value in values) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) continue
            if (!seen.add(trimmed)) continue
            result += trimmed
        }
        return result
    }

    // ───────────────────────── JSON 树辅助 ─────────────────────────

    private fun namedMap(node: Any?): Map<String, NamedEntry>? {
        val map = node as? Map<*, *> ?: return null
        val out = mutableMapOf<String, NamedEntry>()
        for ((key, value) in map) {
            val k = key as? String ?: return null
            val entry = value as? Map<*, *> ?: return null
            out[k] = NamedEntry(stringMap(entry["names"]))
        }
        return out
    }

    private fun locationMap(node: Any?): Map<String, LocationEntry>? {
        val map = node as? Map<*, *> ?: return null
        val out = mutableMapOf<String, LocationEntry>()
        for ((key, value) in map) {
            val k = key as? String ?: return null
            val entry = value as? Map<*, *> ?: return null

            val targets = mutableListOf<TargetOperatorEntry>()
            val rawTargets = entry["target_operators"]
            if (rawTargets is List<*>) {
                for (raw in rawTargets) {
                    val t = raw as? Map<*, *> ?: continue
                    targets += TargetOperatorEntry(
                        name = (t["name"] as? String).orEmpty(),
                        bonusTier = intOf(t["bonus_tier"]),
                        outpostProsperityMaxBonusTier = intOf(t["outpost_prosperity_max_bonus_tier"]),
                    )
                }
            }

            out[k] = LocationEntry(
                names = stringMap(entry["names"]),
                targetOperators = targets,
                restoreOperators = stringList(entry["restore_operators"]).orEmpty(),
            )
        }
        return out
    }

    private fun stringMap(node: Any?): Map<String, String> {
        val map = node as? Map<*, *> ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for ((key, value) in map) {
            val k = key as? String ?: continue
            out[k] = value as? String ?: continue
        }
        return out
    }

    private fun stringList(node: Any?): List<String>? {
        val list = node as? List<*> ?: return null
        return list.mapNotNull { it as? String }
    }

    private fun intOf(node: Any?): Int = (node as? Number)?.toInt() ?: 0
}
