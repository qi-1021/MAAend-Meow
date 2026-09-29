package com.aliothmoon.maafw.remote

/**
 * YOLO 类别名 → 内部 zoneId 的映射，以及 `cls.json` / `tile_mapping.json` 的结构解析。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/YoloPredictor.cpp:81-99`
 * （`convertYoloNameToZoneId`）与 `YoloPredictor.h:33-43`（`TileRegion` 字段与可选性）。
 * 这里不碰 ONNX/`cv::Mat`，只保留名字映射与 sidecar 结构。
 */

/**
 * `tile_mapping.json` 里的一个 tile 区域。
 *
 * 上游 `YoloPredictor::TileRegion`（`YoloPredictor.h:33-43`）：六个字段**全部**是
 * `MEO_OPT`，缺失时保留 C++ 默认值（`base_class=""`、数值 0）。
 */
data class TileRegion(
    val baseClass: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val inferMargin: Int = 0,
)

/** `cls.json` 的纯逻辑内容（上游 `YoloPredictor` 构造里读取，`YoloPredictor.cpp:36-56`）。 */
data class YoloConfig(
    val inputName: String? = null,
    val outputName: String? = null,
    val classes: List<String> = emptyList(),
    val regionMapping: Map<String, String> = emptyMap(),
)

/**
 * `YoloPredictor::convertYoloNameToZoneId` 的纯逻辑替身。
 *
 * @param regionMapping 上游 `regionMapping`：键是类别名的**前 5 个字符**。
 * @param tileRegions 上游 `tileRegions`：类别名 → [TileRegion]，供粗搜取 ROI 用。
 */
class YoloMapping(
    private val regionMapping: Map<String, String> = emptyMap(),
    val tileRegions: Map<String, TileRegion> = emptyMap(),
) {

    /**
     * 上游 `convertYoloNameToZoneId`（`YoloPredictor.cpp:81-99`）。
     *
     * 分支优先级（**逐条照搬**）：
     *  1. 取名字前 5 字符（不足 5 则整串）作前缀；前缀不在 `region_mapping` 里 → 原样返回。
     *  2. 命中前缀后，若名字**同时**含 `"Base"` 与 `"Map"` → `regionName + "_Base"`。
     *  3. 否则用正则 `(Map\d+)Lv0*(\d+)Tier0*(\d+)` 搜索（任意位置），命中 → `regionName + "_L{2}_{3}"`。
     *  4. 前缀命中但上面都不成立 → **原样返回 yoloName**（不是 regionName）。
     *
     * 正则注意点：C++ 用 `boost::regex_search`（子串搜索），Kotlin 用 [Regex.find]
     * 对齐；`Lv0*` / `Tier0*` 会吃掉前导零，捕获组 2/3 是去零后的层级与档位。
     */
    fun convertYoloNameToZoneId(yoloName: String): String {
        val prefix = if (yoloName.length >= 5) yoloName.substring(0, 5) else yoloName
        val regionName = regionMapping[prefix] ?: return yoloName

        if (yoloName.contains("Base") && yoloName.contains("Map")) {
            return regionName + "_Base"
        }
        val match = TIER_REGEX.find(yoloName)
        if (match != null) {
            return regionName + "_L" + match.groupValues[2] + "_" + match.groupValues[3]
        }

        return yoloName
    }

    companion object {
        /**
         * 对应的 boost 正则 `R"((Map\d+)Lv0*(\d+)Tier0*(\d+))"`。
         * 用于 [Regex.find]，与 `regex_search` 一样从任意位置开始匹配。
         */
        val TIER_REGEX = Regex("""(Map\d+)Lv0*(\d+)Tier0*(\d+)""")

        fun fromConfig(config: YoloConfig): YoloMapping =
            YoloMapping(config.regionMapping, emptyMap())
    }
}

/**
 * `cls.json` / `tile_mapping.json` 的结构解析。
 *
 * 上游用 meojson 反序列化（`YoloPredictor.cpp:36-75`）。字段可选性与上游对齐：
 *  - `cls.json` 的 `input_name`/`output_name`/`classes`/`region_mapping` 都是
 *    `j.contains(...)` 保护的可选字段，缺失即保留默认；
 *  - `tile_mapping.json` 顶层是 `类别名 → TileRegion` 的对象。
 *
 * **防御性差异**：上游 `val.as<T>()` 遇到类型不符会抛异常；这里对类型不符的字段
 * 一律按「缺失/默认」处理（不抛），保证纯逻辑层不因脏 sidecar 崩溃。字段名与可选性
 * 仍与上游一致。
 */
object YoloConfigParser {

    /** 解析 `cls.json`。非法 JSON / 顶层非对象 → 全默认。 */
    fun parseConfig(json: String?): YoloConfig {
        val map = MaaJsonTree.parse(json) as? Map<*, *> ?: return YoloConfig()
        return YoloConfig(
            inputName = map["input_name"] as? String,
            outputName = map["output_name"] as? String,
            classes = (map["classes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            regionMapping = stringMap(map["region_mapping"]),
        )
    }

    /**
     * 解析 `tile_mapping.json`。非法 JSON / 顶层非对象 → 空表。
     * 每个值的字段缺失走 [TileRegion] 默认值。
     */
    fun parseTileMapping(json: String?): Map<String, TileRegion> {
        val map = MaaJsonTree.parse(json) as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, TileRegion>(map.size)
        for ((key, value) in map) {
            val name = key as? String ?: continue
            val obj = value as? Map<*, *> ?: continue
            out[name] = TileRegion(
                baseClass = obj["base_class"] as? String ?: "",
                x = toIntOr(obj["x"], 0),
                y = toIntOr(obj["y"], 0),
                w = toIntOr(obj["w"], 0),
                h = toIntOr(obj["h"], 0),
                inferMargin = toIntOr(obj["infer_margin"], 0),
            )
        }
        return out
    }

    private fun stringMap(value: Any?): Map<String, String> {
        val obj = value as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>(obj.size)
        for ((k, v) in obj) {
            val key = k as? String ?: continue
            val str = v as? String ?: continue
            out[key] = str
        }
        return out
    }

    private fun toIntOr(value: Any?, default: Int): Int = when (value) {
        is Long -> value.toInt()
        is Int -> value
        is Double -> value.toInt()
        else -> default
    }
}
