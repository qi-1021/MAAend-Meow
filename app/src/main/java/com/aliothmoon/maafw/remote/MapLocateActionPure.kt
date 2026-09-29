package com.aliothmoon.maafw.remote

import kotlin.math.ceil
import kotlin.math.floor

/**
 * `MapLocateAction.cpp` 的参数解析与输出构造（纯逻辑部分）。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MapLocateAction.cpp`：
 *  - `ParseCustomRecognitionParam`（113-128）——解析失败整体退回默认值；
 *  - `BuildLocateOutput`（141-162）、`BuildAssertLocationOutput`（164-188）；
 *  - `MakePointBox`（190-198）、`TryBuildAssertRect`（200-217）、`IsPositionInsideRect`（219-226）。
 *
 * 这些结构体在上游用 `MEO_JSONIZATION` 声明，`to_json` **输出全部字段**（`MEO_OPT`
 * 只影响反序列化时的可选性），因此这里的数据类保留全部字段并给出完整
 * [LocateOutput.toJsonObject] / [AssertLocationOutput.toJsonObject]。
 *
 * 不移植：`WriteJsonDetail`（写 MaaStringBuffer）、`getExeDir`/`getOrInitLocator`
 * （文件系统 + 单例）、`DetectControllerType`/`UsesAdbMinimapRoi`（框架 + 图像）。
 */
object MapLocateActionPure {

    /** 解析期间的字段类型错误，触发整体退回默认值（对应上游 `from_json` 返回 false）。 */
    private class ParseError : RuntimeException()

    // ───────────────────────── 参数解析 ─────────────────────────

    /**
     * 上游 `ParseCustomRecognitionParam<LocateOptions>`（`MapLocateAction.cpp:113-128`）。
     *
     * `LocateOptions` 的所有字段都是 `MEO_OPT`：缺失用默认；**一旦存在但类型不对**，
     * `from_json` 失败 → 整个对象退回默认。空串/非法 JSON/顶层非对象同样默认。
     */
    fun parseLocateOptions(raw: String?): LocateOptions {
        if (raw.isNullOrEmpty()) return LocateOptions()
        val map = MaaJsonTree.parse(raw) as? Map<*, *> ?: return LocateOptions()
        return try {
            LocateOptions(
                locThreshold = readDouble(map, "loc_threshold", LocateOptions.DEFAULT_LOC_THRESHOLD),
                yoloThreshold = readDouble(map, "yolo_threshold", LocateOptions.DEFAULT_YOLO_THRESHOLD),
                forceGlobalSearch = readBool(map, "force_global_search", false),
                maxLostFrames = readInt(map, "max_lost_frames", LocateOptions.DEFAULT_MAX_LOST_FRAMES),
                expectedZoneId = readString(map, "expected_zone_id", ""),
                searchHints = readSearchHints(map["search_hints"]),
            )
        } catch (_: ParseError) {
            LocateOptions()
        }
    }

    /**
     * 上游 `ParseCustomRecognitionParam<MapLocateAssertLocationParam>`
     * （`MapLocateAction.cpp:113-128` + 结构体 65-71）。
     * `zone_id` / `target` 都是 `MEO_OPT`：缺失默认；类型错整体默认。
     */
    fun parseAssertLocationParam(raw: String?): AssertLocationParam {
        if (raw.isNullOrEmpty()) return AssertLocationParam()
        val map = MaaJsonTree.parse(raw) as? Map<*, *> ?: return AssertLocationParam()
        return try {
            AssertLocationParam(
                zoneId = readString(map, "zone_id", ""),
                target = readDoubleList(map["target"]),
            )
        } catch (_: ParseError) {
            AssertLocationParam()
        }
    }

    /** 上游 `SearchHint`（`MapTypes.h:36-44`）：四个字段**都必填**（非 `MEO_OPT`）。 */
    private fun readSearchHints(value: Any?): List<SearchHint> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: throw ParseError()
        return list.map { item ->
            val obj = item as? Map<*, *> ?: throw ParseError()
            if (!obj.containsKey("zone_id")) throw ParseError()
            SearchHint(
                zoneId = readRequiredString(obj, "zone_id"),
                x = readRequiredDouble(obj, "x"),
                y = readRequiredDouble(obj, "y"),
                radius = readRequiredDouble(obj, "radius"),
            )
        }
    }

    private fun readDoubleList(value: Any?): List<Double> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: throw ParseError()
        return list.map { it as? Number ?: throw ParseError() }.map { it.toDouble() }
    }

    // ───────────────────────── 输出构造 ─────────────────────────

    /** 上游 `BuildLocateOutput`（`MapLocateAction.cpp:141-162`）。 */
    fun buildLocateOutput(result: LocateResult): LocateOutput {
        var output = LocateOutput(
            status = result.status.ordinal,
            message = result.debugMessage,
        )
        val pos = result.position ?: return output
        output = output.copy(
            mapName = pos.zoneId,
            x = pos.x,
            y = pos.y,
            rot = pos.angle,
            locConf = pos.score,
            latencyMs = pos.latencyMs.toInt(),
        )
        val cam = result.camRot
        if (cam != null) {
            output = output.copy(camRot = cam.rot, camRotConf = cam.confidence)
        }
        return output
    }

    /** 上游 `BuildAssertLocationOutput`（`MapLocateAction.cpp:164-188`）。 */
    fun buildAssertLocationOutput(
        result: LocateResult,
        param: AssertLocationParam,
        matched: Boolean,
    ): AssertLocationOutput {
        var output = AssertLocationOutput(
            status = result.status.ordinal,
            matched = matched,
            inTarget = matched,
            message = result.debugMessage,
            zoneId = param.zoneId,
            target = param.target,
        )
        val pos = result.position ?: return output
        output = output.copy(
            x = pos.x,
            y = pos.y,
            rot = pos.angle,
            locConf = pos.score,
            latencyMs = pos.latencyMs.toInt(),
        )
        val cam = result.camRot
        if (cam != null) {
            output = output.copy(camRot = cam.rot, camRotConf = cam.confidence)
        }
        return output
    }

    // ───────────────────────── 几何 ─────────────────────────

    /**
     * 上游 `MakePointBox`（`MapLocateAction.cpp:190-198`）。
     * 位置四舍五入（`std::lround`，遇 .5 远离零）成 1×1 矩形。
     */
    fun makePointBox(position: MapPosition): MapRect = MapRect(
        x = lround(position.x).toInt(),
        y = lround(position.y).toInt(),
        width = 1,
        height = 1,
    )

    /**
     * 上游 `TryBuildAssertRect`（`MapLocateAction.cpp:200-217`）。
     * `target` 必须恰为 4 个元素且宽高 > 0，否则 null；宽高各自 `max(1, lround(...))`。
     */
    fun tryBuildAssertRect(param: AssertLocationParam): MapRect? {
        if (param.target.size != 4) return null
        val width = param.target[2]
        val height = param.target[3]
        if (width <= 0.0 || height <= 0.0) return null

        return MapRect(
            x = lround(param.target[0]).toInt(),
            y = lround(param.target[1]).toInt(),
            width = maxOf(1, lround(width).toInt()),
            height = maxOf(1, lround(height).toInt()),
        )
    }

    /**
     * 上游 `IsPositionInsideRect`（`MapLocateAction.cpp:219-226`）。
     * 左闭右开、上闭下开：`x ∈ [left, right)`、`y ∈ [top, bottom)`。
     */
    fun isPositionInsideRect(position: MapPosition, rect: MapRect): Boolean {
        val left = rect.x.toDouble()
        val top = rect.y.toDouble()
        val right = left + rect.width.toDouble()
        val bottom = top + rect.height.toDouble()
        return position.x >= left && position.x < right && position.y >= top && position.y < bottom
    }

    /** 上游 `std::lround`：四舍五入、遇 .5 远离零，返回 long。 */
    private fun lround(value: Double): Long {
        val r = if (value >= 0.0) floor(value + 0.5) else ceil(value - 0.5)
        return r.toLong()
    }

    // ───────────────────────── 读取助手 ─────────────────────────

    /** 缺失 → default；数字 → 值；其余类型 → [ParseError]。 */
    private fun readDouble(map: Map<*, *>, key: String, default: Double): Double {
        val v = map[key] ?: return default
        return (v as? Number)?.toDouble() ?: throw ParseError()
    }

    /** 缺失 → default；整数 → 值；小数/越界/其余类型 → [ParseError]（对齐 `is<int>`）。 */
    private fun readInt(map: Map<*, *>, key: String, default: Int): Int {
        val v = map[key] ?: return default
        if (v !is Long) throw ParseError()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw ParseError()
        return v.toInt()
    }

    private fun readBool(map: Map<*, *>, key: String, default: Boolean): Boolean {
        val v = map[key] ?: return default
        return v as? Boolean ?: throw ParseError()
    }

    private fun readString(map: Map<*, *>, key: String, default: String): String {
        val v = map[key] ?: return default
        return v as? String ?: throw ParseError()
    }

    private fun readRequiredString(map: Map<*, *>, key: String): String =
        map[key] as? String ?: throw ParseError()

    private fun readRequiredDouble(map: Map<*, *>, key: String): Double =
        (map[key] as? Number)?.toDouble() ?: throw ParseError()
}

/** 上游 `MapLocateAssertLocationParam`（`MapLocateAction.cpp:65-71`）。 */
data class AssertLocationParam(
    val zoneId: String = "",
    val target: List<Double> = emptyList(),
)

/**
 * 上游 `LocateOutput`（`MapLocateAction.cpp:39-63`）。
 * 字段顺序与 `MEO_JSONIZATION` 一致，供 [toJsonObject] 输出。
 */
data class LocateOutput(
    val status: Int = 0,
    val message: String = "",
    val mapName: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rot: Double = 0.0,
    val locConf: Double = 0.0,
    val camRot: Double = 0.0,
    val camRotConf: Double = 0.0,
    val latencyMs: Int = 0,
) {
    fun toJsonObject(): LinkedHashMap<String, Any?> = linkedMapOf(
        "status" to status,
        "message" to message,
        "mapName" to mapName,
        "x" to x,
        "y" to y,
        "rot" to rot,
        "locConf" to locConf,
        "camRot" to camRot,
        "camRotConf" to camRotConf,
        "latencyMs" to latencyMs,
    )
}

/**
 * 上游 `MapLocateAssertLocationOutput`（`MapLocateAction.cpp:73-103`）。
 * 字段顺序与 `MEO_JSONIZATION` 一致。
 */
data class AssertLocationOutput(
    val status: Int = 0,
    val matched: Boolean = false,
    val inTarget: Boolean = false,
    val message: String = "",
    val zoneId: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rot: Double = 0.0,
    val locConf: Double = 0.0,
    val camRot: Double = 0.0,
    val camRotConf: Double = 0.0,
    val latencyMs: Int = 0,
    val target: List<Double> = emptyList(),
) {
    fun toJsonObject(): LinkedHashMap<String, Any?> = linkedMapOf(
        "status" to status,
        "matched" to matched,
        "inTarget" to inTarget,
        "message" to message,
        "zoneId" to zoneId,
        "x" to x,
        "y" to y,
        "rot" to rot,
        "locConf" to locConf,
        "camRot" to camRot,
        "camRotConf" to camRotConf,
        "latencyMs" to latencyMs,
        "target" to target,
    )
}
