package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 世界地图 `MapFind` 的**参数解析 / 目标构造 / 图标表解析 / detail 序列化**（纯逻辑）。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/WorldMap/WorldMapFind.cpp`：
 *  - `Candidate`（27-33）、`FindParam`（38-55）、`Target`（58-62）；
 *  - `ParseParam`（115-170）：逐条校验，任一不合法整段拒绝；
 *  - `BuildTargets`（325-337）；
 *  - `WriteDetail`（316-323）的序列化形状。
 * 以及 `WorldMapSolver.cpp` 的 `IconEntry`（39-59）与 `LoadIconTable`（517-566）的字段映射。
 *
 * 本文件不依赖 Android / JNA / 文件系统，可在 [scripts/verify_pure_logic.sh] 本机回归。
 * 读文件、开运行时模板、跑识别的那部分留在 [MaaRunner]。
 */
object WorldMapFindPure {

    /** 上游 `Candidate`（`WorldMapFind.cpp:27-33`）。 */
    data class Candidate(val at: List<Double>, val next: String)

    /** 上游 `FindParam`（`WorldMapFind.cpp:38-55`）。 */
    data class FindParam(
        val zone: String,
        val at: List<Double> = emptyList(),
        val candidates: List<Candidate> = emptyList(),
        val icon: String = "",
        val state: String = "",
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val voteGrid: Int = WorldMapViewportConfig.DEFAULT_VOTE_GRID,
    ) {
        companion object {
            /** 上游默认 `max_attempts = 4`（`WorldMapFind.cpp:48`）。 */
            const val DEFAULT_MAX_ATTEMPTS = 4
        }
    }

    /** 上游 `Target`（`WorldMapFind.cpp:58-62`）。 */
    data class Target(val at: List<Double>, val next: String)

    /** 解析结果：失败时 [param] 为 null，[error] 是人话原因。 */
    data class ParseResult(val param: FindParam?, val error: String?)

    /** 图标表里一条的原样（上游 `IconEntry`，`WorldMapSolver.cpp:39-59`）。 */
    data class IconEntry(
        val templates: List<String>,
        val scale: List<Double>? = null,
        val scaleStep: Double? = null,
        val threshold: Double? = null,
        val gate: Double? = null,
        val radius: Double? = null,
        val goldRatio: Double? = null,
        val occludedByPlayer: Boolean = false,
    )

    private class ParseError : RuntimeException()

    // ───────────────────────── 参数解析 ─────────────────────────

    /**
     * 上游 `ParseParam`（`WorldMapFind.cpp:115-170`）。校验顺序与错误语义逐条对齐：
     *  - 空串 / 非法 JSON / 顶层非对象 → 失败；
     *  - `zone` 缺失、空串或非字符串 → 失败；
     *  - `at` 与 `candidates` 必须**恰有一个**非空；
     *  - `at` 非空时必须恰有 2 个数；
     *  - 每个 candidate 的 `at` 恰有 2 个数、`next` 非空；
     *  - `candidates` 非空时 `icon` 必须非空；
     *  - `state` 只能取空 / `locked` / `unlocked`；非空时 `icon` 必须非空；
     *  - `max_attempts < 1` 归一到 1（类型错则整段失败）。
     */
    fun parseFindParam(raw: String?): ParseResult {
        if (raw.isNullOrEmpty()) return ParseResult(null, "custom_recognition_param 为空")
        val map = MaaJsonTree.parse(raw) as? Map<*, *>
            ?: return ParseResult(null, "custom_recognition_param 不是合法 JSON 对象")

        return try {
            val zone = readOptionalString(map, "zone") ?: ""
            if (zone.isEmpty()) return ParseResult(null, "'zone' 必须非空")

            val at = readDoubleList(map, "at")
            val candidates = readCandidates(map["candidates"])
            if (at.isEmpty() == candidates.isEmpty()) {
                return ParseResult(null, "'at' 与 'candidates' 必须恰给一个")
            }
            if (at.isNotEmpty() && at.size != 2) {
                return ParseResult(null, "'at' 必须恰好两个数")
            }
            for (candidate in candidates) {
                if (candidate.at.size != 2 || candidate.next.isEmpty()) {
                    return ParseResult(null, "candidate 的 'at' 要两个数、'next' 要非空")
                }
            }

            val icon = readOptionalString(map, "icon") ?: ""
            val state = readOptionalString(map, "state") ?: ""
            if (candidates.isNotEmpty() && icon.isEmpty()) {
                return ParseResult(null, "'candidates' 需要 'icon' 才能区分")
            }
            if (!state.isEmpty() && state != "locked" && state != "unlocked") {
                return ParseResult(null, "'state' 只能是 'unlocked' 或 'locked'")
            }
            if (!state.isEmpty() && icon.isEmpty()) {
                return ParseResult(null, "'state' 需要 'icon' 才能判")
            }

            var maxAttempts = readOptionalInt(map, "max_attempts") ?: FindParam.DEFAULT_MAX_ATTEMPTS
            if (maxAttempts < 1) maxAttempts = 1
            val voteGrid = readOptionalInt(map, "vote_grid") ?: WorldMapViewportConfig.DEFAULT_VOTE_GRID

            ParseResult(
                FindParam(
                    zone = zone,
                    at = at,
                    candidates = candidates,
                    icon = icon,
                    state = state,
                    maxAttempts = maxAttempts,
                    voteGrid = voteGrid,
                ),
                null,
            )
        } catch (_: ParseError) {
            ParseResult(null, "custom_recognition_param 字段类型不合法")
        }
    }

    /** 上游 `BuildTargets`（`WorldMapFind.cpp:325-337`）。 */
    fun buildTargets(param: FindParam): List<Target> {
        if (param.candidates.isEmpty()) {
            if (param.at.size != 2) return emptyList()
            return listOf(Target(param.at, ""))
        }
        return param.candidates.map { Target(it.at, it.next) }
    }

    /** `wantUnlocked` 的别名，供调用方少写一层。 */
    fun wantsUnlocked(state: String): Boolean = WorldMapTypes.wantsUnlocked(state)

    // ───────────────────────── 底图选取 ─────────────────────────

    /**
     * 上游 `FindZoneBaseFile`（`WorldMapSolver.cpp:150-173`）：目录下**含 `base`、不含 `tier`**
     * 的 `.png`（文件名 stem 小写后做子串判定）。
     *
     * 上游用 `directory_iterator` 的枚举顺序；这里按传入顺序取第一个命中（调用方按稳定顺序给）。
     */
    fun findZoneBaseFile(fileNames: List<String>): String? {
        for (name in fileNames) {
            if (!name.lowercase().endsWith(".png")) continue
            val stem = MapLocatorCoarsePure.fileStem(name).lowercase()
            if (stem.contains("base") && !stem.contains("tier")) return name
        }
        return null
    }

    // ───────────────────────── 图标表 ─────────────────────────

    /**
     * 上游 `LoadIconTable`（`WorldMapSolver.cpp:517-566`）+ `IconEntry` 字段映射（544-562）。
     *
     * 非法条目（`templates` 缺失/空、字段类型错）**跳过**，不阻断其余条目。
     * `scale` 仅在恰有 2 个数且 `0 < scale[0] <= scale[1]` 时生效；其余字段只在 `> 0` 时覆盖默认。
     * 表不是对象、或整个解析失败时返回空表。
     */
    fun parseIconTable(json: String?): Map<String, WorldMapIconSpec> {
        val root = MaaJsonTree.parse(json) as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, WorldMapIconSpec>(root.size)
        for ((key, value) in root) {
            val name = key as? String ?: continue
            val obj = value as? Map<*, *> ?: continue
            val entry = runCatching { readIconEntry(obj) }.getOrNull() ?: continue
            if (entry.templates.isEmpty()) continue
            out[name] = iconEntryToSpec(entry)
        }
        return out
    }

    /** 上游 `IconEntry` → `IconSpec` 的字段映射（`WorldMapSolver.cpp:544-562`）。 */
    fun iconEntryToSpec(entry: IconEntry): WorldMapIconSpec {
        var spot = WorldMapSpotConfig(templates = entry.templates)
        val scale = entry.scale
        if (scale != null && scale.size == 2 && scale[0] > 0.0 && scale[1] >= scale[0]) {
            spot = spot.copy(scaleMin = scale[0], scaleMax = scale[1])
        }
        entry.scaleStep?.let { if (it > 0.0) spot = spot.copy(scaleStep = it) }
        entry.threshold?.let { if (it > 0.0) spot = spot.copy(minScore = it) }
        entry.gate?.let { if (it > 0.0) spot = spot.copy(gateBase = it) }
        entry.radius?.let { spot = spot.copy(radiusBase = it) }
        entry.goldRatio?.let { spot = spot.copy(minGoldRatio = it) }
        return WorldMapIconSpec(spot = spot, occludedByPlayer = entry.occludedByPlayer)
    }

    // ───────────────────────── mapfind 调试探针渲染 ─────────────────────────

    /**
     * `mapfind` 调试探针的单帧结果（纯数据，供 [describeProbe] 渲染，也便于本机单测）。
     *
     * 与 [MaaRunner.mapFindRun] 的差别：探针**只读**——不触发 ZoomOut、不拖动、不交回 next，
     * 只把「解出的 viewport + 目标屏幕框 + 是否命中图标」如实报出来，用于真机单点验证。
     */
    data class ProbeOutcome(
        val zone: String,
        val baseWidth: Int,
        val baseHeight: Int,
        val atX: Double,
        val atY: Double,
        val viewport: WorldMapViewport?,
        val icon: String?,
        val hit: WorldMapSpotHit?,
        val wantUnlocked: Boolean,
    )

    /**
     * 把一次探针结果渲染成给调试者看的行。字段顺序固定：
     * zone / 底图尺寸 / 目标底图坐标 / viewport(scale+origins+置信) / 目标屏幕框 / 命中详情。
     */
    fun describeProbe(outcome: ProbeOutcome): List<String> = buildList {
        add("zone: ${outcome.zone}")
        add("base: ${outcome.baseWidth}x${outcome.baseHeight}")
        add("at: [${fmt(outcome.atX)},${fmt(outcome.atY)}]")

        val vp = outcome.viewport
        if (vp == null) {
            add("viewport: FAILED（解不出：底图缩放带内无可靠峰，或置信不足）")
            add("hit: false")
            return@buildList
        }
        add(
            "viewport: scale=${fmt(vp.scale)} score=${fmt(vp.score)} delta=${fmt(vp.delta)} " +
                "psr=${fmt(vp.psr)} vote=${vp.voteGrid}",
        )
        add(
            "viewport origin: roi=(${fmt(vp.roiOriginX)},${fmt(vp.roiOriginY)}) " +
                "base=(${fmt(vp.baseOriginX)},${fmt(vp.baseOriginY)}) roi_size=${vp.roiWidth}x${vp.roiHeight}",
        )

        val expected = vp.toScreen(outcome.atX, outcome.atY)
        val point = WorldMapTypes.pointBox(expected[0], expected[1])
        add(
            "target screen: (${fmt(expected[0])},${fmt(expected[1])}) " +
                "point_box=[${point.x},${point.y},${point.width},${point.height}]",
        )

        val icon = outcome.icon
        if (icon.isNullOrEmpty()) {
            add("hit: false（未给 icon，仅解坐标，对齐 MapFind 的 at-only 分支）")
            return@buildList
        }
        val hit = outcome.hit
        if (hit == null) {
            add("hit: false（icon=$icon 在期望位置附近认不出）")
            return@buildList
        }
        val box = WorldMapTypes.spotBox(hit)
        add(
            "hit: true template=${hit.templateName} score=${fmt(hit.score)} " +
                "match_scale=${fmt(hit.matchScale)} offset_base=${fmt(hit.offsetBase)} " +
                "unlocked=${hit.unlocked} want_unlocked=${outcome.wantUnlocked}",
        )
        add(
            "spot: center=(${fmt(hit.centerX)},${fmt(hit.centerY)}) " +
                "hotspot=(${fmt(hit.hotspotX)},${fmt(hit.hotspotY)}) " +
                "box=[${box.x},${box.y},${box.width},${box.height}] " +
                "size=${hit.sizeWidth}x${hit.sizeHeight}",
        )
    }

    /** 固定三位小数、`Locale.US`（避免某些区域把小数点渲染成逗号）。 */
    private fun fmt(value: Double): String =
        String.format(java.util.Locale.US, "%.3f", value)

    // ───────────────────────── detail 序列化 ─────────────────────────

    /**
     * 上游 `WriteDetail` 的载荷形状（`WorldMapFind.cpp:523-563`）。
     *
     * 字段顺序与上游 `json::object` 的插入顺序一致；可选项为 null 时不写出。
     */
    @Suppress("LongParameterList")
    fun findDetailJson(
        zone: String,
        index: Int,
        atX: Double,
        atY: Double,
        screenX: Double,
        screenY: Double,
        viewportScale: Double,
        viewportVote: Int,
        next: String? = null,
        icon: String? = null,
        templateName: String? = null,
        score: Double? = null,
        unlocked: Boolean? = null,
        clickX: Double? = null,
        clickY: Double? = null,
        playerMarker: Boolean = false,
    ): String = buildJsonObject {
        put("zone", zone)
        put("index", index)
        put("at", JsonArray(listOf(JsonPrimitive(atX), JsonPrimitive(atY))))
        put("screen", JsonArray(listOf(JsonPrimitive(screenX), JsonPrimitive(screenY))))
        put("viewport_scale", viewportScale)
        put("viewport_vote", viewportVote)
        if (next != null) put("next", next)
        if (icon != null) put("icon", icon)
        if (templateName != null) put("template", templateName)
        if (score != null) put("score", score)
        if (unlocked != null) put("unlocked", unlocked)
        if (clickX != null && clickY != null) {
            put("click", JsonArray(listOf(JsonPrimitive(clickX), JsonPrimitive(clickY))))
        }
        if (playerMarker) put("player_marker", true)
    }.toString()

    // ───────────────────────── 读取助手 ─────────────────────────

    private fun readIconEntry(obj: Map<*, *>): IconEntry {
        val templates = readRequiredStringList(obj["templates"])
        val scale = readOptionalDoubleList(obj["scale"])
        return IconEntry(
            templates = templates,
            scale = scale,
            scaleStep = readOptionalDouble(obj, "scale_step"),
            threshold = readOptionalDouble(obj, "threshold"),
            gate = readOptionalDouble(obj, "gate"),
            radius = readOptionalDouble(obj, "radius"),
            goldRatio = readOptionalDouble(obj, "gold_ratio"),
            occludedByPlayer = readOptionalBool(obj, "occluded_by_player") ?: false,
        )
    }

    private fun readCandidates(value: Any?): List<Candidate> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: throw ParseError()
        return list.map { item ->
            val obj = item as? Map<*, *> ?: throw ParseError()
            Candidate(at = readRequiredDoubleList(obj["at"]), next = readRequiredString(obj, "next"))
        }
    }

    private fun readDoubleList(map: Map<*, *>, key: String): List<Double> {
        val v = map[key] ?: return emptyList()
        return toDoubleList(v)
    }

    private fun readOptionalDoubleList(value: Any?): List<Double>? {
        if (value == null) return null
        return toDoubleList(value)
    }

    private fun toDoubleList(value: Any): List<Double> {
        val list = value as? List<*> ?: throw ParseError()
        return list.map { (it as? Number)?.toDouble() ?: throw ParseError() }
    }

    private fun readRequiredDoubleList(value: Any?): List<Double> {
        val list = value as? List<*> ?: throw ParseError()
        return list.map { (it as? Number)?.toDouble() ?: throw ParseError() }
    }

    private fun readRequiredStringList(value: Any?): List<String> {
        val list = value as? List<*> ?: throw ParseError()
        return list.map { it as? String ?: throw ParseError() }
    }

    private fun readRequiredString(map: Map<*, *>, key: String): String =
        map[key] as? String ?: throw ParseError()

    private fun readOptionalString(map: Map<*, *>, key: String): String? {
        val v = map[key] ?: return null
        return v as? String ?: throw ParseError()
    }

    private fun readOptionalInt(map: Map<*, *>, key: String): Int? {
        val v = map[key] ?: return null
        if (v !is Long) throw ParseError()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw ParseError()
        return v.toInt()
    }

    private fun readOptionalDouble(map: Map<*, *>, key: String): Double? {
        val v = map[key] ?: return null
        return (v as? Number)?.toDouble() ?: throw ParseError()
    }

    private fun readOptionalBool(map: Map<*, *>, key: String): Boolean? {
        val v = map[key] ?: return null
        return v as? Boolean ?: throw ParseError()
    }
}
