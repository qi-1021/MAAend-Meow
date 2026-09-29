package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 自动送货目录解析（对齐上游 agent/go-service/autodelivery/）。
 *
 * 从 catalog.json（depots + destinations）构建区域/终点文本表，
 * 供 ResolveDepot/Destination 动作把任务页 OCR 的区域/终点文本匹配成
 * 具体路线节点，再以 pipeline override 交给 MapNavigateAction 执行。
 *
 * 匹配算法与上游一致：全角转半角+去符号归一化后，取「OCR 文本中与目标串
 * 最佳子串」的编辑距离算相似度；阈值 0.70，与次优差距须 ≥ 0.05。
 */
object AutoDeliverySupport {
    private const val MIN_SIMILARITY = 0.70
    private const val MIN_MARGIN = 0.05

    data class Depot(
        val id: String,
        val nameZh: String,
        val routeNode: String,
        val zipRouteNode: String,
        val retryRouteNode: String,
        val ziplineOnly: Boolean,
    )

    data class Destination(
        val id: String,
        val kind: String,
        val depotId: String,
        val nameTexts: List<String>,
        val missionTexts: List<String>,
        val areaTexts: List<String>,
        val routeNode: String,
        val zipRouteNode: String,
        val retryRouteNode: String,
        val ziplineOnly: Boolean,
    )

    data class Area(val id: String, val depotId: String, val texts: List<String>)

    data class Match(
        val similarity: Double,
        val runnerUp: Double,
        val matchedText: String,
    )

    class ResolveException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var loaded = false
    private val depots = mutableListOf<Depot>()
    private val destinations = mutableListOf<Destination>()
    private val areas = mutableListOf<Area>()

    /** 从 APK assets 里的 data/AutoDelivery/catalog.json 加载（打包白名单 data/ 目录已带入） */
    fun ensureLoaded(catalogJsonText: String) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            parseCatalog(catalogJsonText)
            loaded = true
        }
    }

    private fun parseCatalog(text: String) {
        val root = json.parseToJsonElement(text).jsonObject
        val zhOf = { o: JsonObject? -> o?.get("zh_cn")?.jsonPrimitive?.contentOrNull.orEmpty() }
        for (d in root["depots"]!!.jsonArray.map { it.jsonObject }) {
            depots += Depot(
                id = d["id"]!!.jsonPrimitive.content,
                nameZh = zhOf(d["name"]?.jsonObject),
                routeNode = d["route_node"]!!.jsonPrimitive.content,
                zipRouteNode = d["zip_route_node"]!!.jsonPrimitive.content,
                retryRouteNode = d["retry_route_node"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ziplineOnly = d["zipline_only"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }
        val areaTextsById = mutableMapOf<String, MutableSet<String>>()
        val areaDepotById = mutableMapOf<String, String>()
        for (e in root["destinations"]!!.jsonArray.map { it.jsonObject }) {
            val areaMap = localizedMap(e["area"]?.jsonObject)
            val dest = Destination(
                id = e["id"]!!.jsonPrimitive.content,
                kind = e["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                depotId = e["depot_id"]!!.jsonPrimitive.content,
                nameTexts = localizedTexts(e["name"]?.jsonObject),
                missionTexts = localizedTexts(e["mission"]?.jsonObject),
                areaTexts = localizedTexts(e["area"]?.jsonObject),
                routeNode = e["route_node"]!!.jsonPrimitive.content,
                zipRouteNode = e["zip_route_node"]!!.jsonPrimitive.content,
                retryRouteNode = e["retry_route_node"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ziplineOnly = e["zipline_only"]?.jsonPrimitive?.contentOrNull == "true",
            )
            destinations += dest
            // 区域分组键必须与上游 localizedAreaID 一致：en_us 名去掉非 ASCII 字母数字、保留大小写。
            // 它会被 DeliveryJobsResolveOngoingDepotAction 直接拼成
            // `DeliveryJobsOngoingDeliveryFor<ID>`；若用中文/小写归一化文本当键，节点名会对不上。
            val key = localizedAreaID(areaMap)
            if (key != null && dest.areaTexts.isNotEmpty()) {
                areaTextsById.getOrPut(key) { mutableSetOf() }.addAll(dest.areaTexts)
                areaDepotById[key] = dest.depotId
            }
        }
        for ((key, texts) in areaTextsById) {
            areas += Area(id = key, depotId = areaDepotById[key].orEmpty(), texts = texts.toList())
        }
    }

    private fun localizedTexts(o: JsonObject?): List<String> =
        o?.mapNotNull { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            .orEmpty().filter { it.isNotBlank() }

    private fun localizedMap(o: JsonObject?): Map<String, String> =
        o?.mapNotNull { (k, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let { k to it }
        }?.toMap().orEmpty()

    /**
     * 对齐上游 `localizedAreaID`：区域节点 ID = `en_us` 名去掉所有非 ASCII 字母/数字字符，
     * **保留大小写**（`Originium Science Park` → `OriginiumSciencePark`）。
     *
     * 这是「仓储名 → next 目标」最容易写错的一步：它直接拼成
     * `DeliveryJobsOngoingDeliveryFor<ID>`，任何小写化、空格替换或全角折叠都会让
     * next 指向不存在的节点。`en_us` 缺失或过滤后为空则返回 null。
     */
    fun localizedAreaID(localized: Map<String, String>): String? {
        val english = localized["en_us"]?.trim().orEmpty()
        if (english.isEmpty()) return null
        val id = buildString {
            for (r in english) if (r in 'A'..'Z' || r in 'a'..'z' || r in '0'..'9') append(r)
        }
        return id.ifEmpty { null }
    }

    // ── 归一化 + 匹配（对齐 matcher.go）──

    private fun normalize(text: String): String = buildString {
        for (r in text) {
            var c = r
            if (c in '！'..'～') c -= ('！' - '!')
            val lower = c.lowercaseChar()
            if (lower.isLetterOrDigit()) append(lower)
        }
    }

    /** 目标串在 OCR 文本中的最佳子串编辑距离（对齐 objectiveEditDistance） */
    private fun substringEditDistance(ocr: String, objective: String): Int {
        val ocrR = ocr.toList()
        val objR = objective.toList()
        if (ocrR.size <= objR.size) return levenshtein(ocr, objective)
        var previous = IntArray(ocrR.size + 1) { it }
        var current = IntArray(ocrR.size + 1)
        for ((objIdx, objCh) in objR.withIndex()) {
            current[0] = objIdx + 1
            for (ocrIdx in ocrR.indices) {
                val cost = if (objCh != ocrR[ocrIdx]) 1 else 0
                current[ocrIdx + 1] = minOf(
                    previous[ocrIdx + 1] + 1,
                    current[ocrIdx] + 1,
                    previous[ocrIdx] + cost,
                )
            }
            val tmp = previous; previous = current; current = tmp
        }
        return previous.drop(1).min()
    }

    private fun levenshtein(a: String, b: String): Int {
        val aR = a.toList(); val bR = b.toList()
        var prev = IntArray(aR.size + 1) { it }
        var cur = IntArray(aR.size + 1)
        for ((bi, bCh) in bR.withIndex()) {
            cur[0] = bi + 1
            for (ai in aR.indices) {
                val cost = if (bCh != aR[ai]) 1 else 0
                cur[ai + 1] = minOf(prev[ai + 1] + 1, cur[ai] + 1, prev[ai] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[aR.size]
    }

    private fun similarityOf(objective: String, distance: Int): Double {
        val len = objective.length
        return if (len == 0) 1.0 else 1.0 - distance.toDouble() / len
    }

    private fun bestMatch(ocr: String, texts: List<String>): Match {
        var best = Match(-1.0, 0.0, "")
        var bestDistance = -1
        for (t in texts) {
            val norm = normalize(t)
            if (norm.isEmpty()) continue
            val d = substringEditDistance(normalize(ocr), norm)
            val s = similarityOf(norm, d)
            if (s > best.similarity || (s == best.similarity && (bestDistance < 0 || d < bestDistance))) {
                best = Match(s, 0.0, t)
                bestDistance = d
            }
        }
        return best
    }

    private fun checkThreshold(label: String, best: Match): Match {
        if (best.similarity < MIN_SIMILARITY) {
            throw ResolveException(
                "$label 未达相似度阈值: best='${best.matchedText}' sim=%.3f thr=%.3f".format(best.similarity, MIN_SIMILARITY),
            )
        }
        if (best.similarity - best.runnerUp < MIN_MARGIN) {
            throw ResolveException(
                "$label 匹配存在歧义: best='${best.matchedText}' sim=%.3f runner_up=%.3f".format(best.similarity, best.runnerUp),
            )
        }
        return best
    }

    // ── 解析入口 ──

    /** OCR 区域文本 → 仓储点（顺带校验相似度阈值与边际） */
    fun resolveArea(ocrText: String): Pair<Area, Match> {
        val normOcr = normalize(ocrText)
        if (normOcr.isEmpty()) throw ResolveException("区域 OCR 归一化后为空")
        val scored = areas.map { it to bestMatch(ocrText, it.texts) }
            .sortedWith(compareByDescending<Pair<Area, Match>> { it.second.similarity }.thenBy { it.first.id })
        if (scored.isEmpty()) throw ResolveException("区域候选表为空")
        val best = scored[0]
        val runnerUp = scored.getOrNull(1)?.second?.similarity ?: 0.0
        val m = checkThreshold("送货区域", best.second.copy(runnerUp = runnerUp))
        return best.first to m
    }

    /** OCR 目标文本 → 终点（普通任务匹配 buyer 名，回收站匹配 mission 全文） */
    fun resolveDestination(ocrText: String): Pair<Destination, Match> {
        val normOcr = normalize(ocrText)
        if (normOcr.isEmpty()) throw ResolveException("送货目标 OCR 归一化后为空")
        val scored = destinations.map { d ->
            val texts = if (d.kind == "recycle_bin") d.missionTexts else d.nameTexts + d.missionTexts
            d to bestMatch(ocrText, texts)
        }.sortedWith(compareByDescending<Pair<Destination, Match>> { it.second.similarity }.thenBy { it.first.id })
        if (scored.isEmpty()) throw ResolveException("终点候选表为空")
        val best = scored[0]
        val runnerUp = scored.getOrNull(1)?.second?.similarity ?: 0.0
        val m = checkThreshold("送货终点", best.second.copy(runnerUp = runnerUp))
        return best.first to m
    }

    fun depotOf(id: String): Depot = depots.first { it.id == id }

    // ── Pipeline override 生成（对齐 buildDepotNavigationOverride）──

    fun depotNavigationOverride(route: Depot, zip: Boolean): JsonObject = buildJsonObject {
        put("AutoDeliveryNavigateDepot", buildJsonObject {
            put("custom_action", "SubTask")
            putJsonObject("custom_action_param") {
                put("sub", kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonPrimitive(if (zip) route.zipRouteNode else route.routeNode)),
                ))
            }
        })
        put("AutoDeliveryRetryNavigateDepot", buildJsonObject {
            if (route.retryRouteNode.isNotBlank()) {
                put("enabled", true)
                put("custom_action", "SubTask")
                putJsonObject("custom_action_param") {
                    put("sub", kotlinx.serialization.json.JsonArray(
                        listOf(kotlinx.serialization.json.JsonPrimitive(route.retryRouteNode)),
                    ))
                }
            } else {
                put("enabled", false)
            }
        })
    }

    fun destinationNavigationOverride(dest: Destination, zip: Boolean): JsonObject = buildJsonObject {
        put("AutoDeliveryNavigateDestination", buildJsonObject {
            put("custom_action", "SubTask")
            putJsonObject("custom_action_param") {
                put("sub", kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonPrimitive(if (zip) dest.zipRouteNode else dest.routeNode)),
                ))
            }
        })
        put("AutoDeliveryRetryNavigateDestination", buildJsonObject {
            if (dest.retryRouteNode.isNotBlank()) {
                put("enabled", true)
                put("custom_action", "SubTask")
                putJsonObject("custom_action_param") {
                    put("sub", kotlinx.serialization.json.JsonArray(
                        listOf(kotlinx.serialization.json.JsonPrimitive(dest.retryRouteNode)),
                    ))
                }
            } else {
                put("enabled", false)
            }
        })
    }
}
