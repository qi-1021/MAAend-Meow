package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * YOLO 分区分类（`cls.onnx`）走框架 `NeuralNetworkClassify` 的**纯逻辑接线**：
 *  - 构造识别节点覆盖 JSON（`model` 用 `model/classify/` 下的相对路径）；
 *  - 解析框架 `MaaTaskerGetRecognitionDetail` 返回的 detail JSON，取 `cls_index` / `label` / `box`；
 *  - 把 `cls_index` 按 `cls.json` 的 `classes` 还原成类名（上游 `YoloPredictor.cpp:204-244`），
 *    再经已移植的 [YoloMapping.convertYoloNameToZoneId] 得 zone_id、查 `tile_mapping.json` 得 ROI。
 *
 * ## 已核实：框架能给索引，也能给类名
 *
 * MaaFramework v5.14.0 `Vision/NeuralNetworkClassifier.cpp` 的 `classify()`：
 *  - `res.cls_index = argmax(softmax(raw))`（softmax 单调，argmax 与上游直接 argmax 等价）；
 *  - `res.label = cls_index < labels.size() ? labels[cls_index] : "Unknown_{idx}"`；
 *  - 结果按 `MEO_JSONIZATION(cls_index, label, box, score)` 序列化，外层是 `{all, filtered, best}`。
 *
 * 因此 detail JSON 里 `best.cls_index` 一定拿得到；类名既可由节点 `labels`（我们传 `classes`）
 * 给出，也可由我们自己用 [YoloConfig.classes] 映射。这里**以索引查 [YoloConfig.classes] 为准**
 * （与上游完全同源，顺序由 `cls.json` 决定），框架 `label` 仅作诊断回显。
 *
 * ## 为什么 Kotlin 先做预处理
 *
 * 框架 `classify()` 会把 ROI `cv::resize` 到模型输入（128×128）。我们把
 * [YoloPreprocess] 处理好的 128×128 图连同全图 ROI 传进去，resize 是 1:1，预处理不被破坏。
 * 颜色顺序见 [YoloPreprocess] 的说明（框架 `image_to_tensor` 自己做 BGR→RGB + /255 + CHW）。
 *
 * 本文件只吃字符串 / 数据类，不碰 Android / JNA，可在本机回归。
 */
object YoloClassifySupport {

    /** 上游模型在 `model/classify/` 下的相对路径（sidecar `map/cls.json` 的 `map/cls.onnx`）。 */
    const val DEFAULT_MODEL = "cls.onnx"

    /**
     * 临时 `NeuralNetworkClassify` 节点名（只在 pipeline_override 里定义，不落资源）。
     */
    const val DEFAULT_PROBE_NODE = "__YoloClassifyProbe__"

    /** 上游 `cls.json` 里代表「本帧没有可定位区域」的类名（`YoloPredictor.cpp:215`）。 */
    const val NONE_CLASS = "None"

    /** 框架 detail JSON 里单个分类结果的最小视图。 */
    data class YoloClassifyResult(
        val clsIndex: Int,
        val label: String,
        val score: Double,
        /** `[x, y, w, h]`；缺失为 null。对全图 ROI 的 classify 通常就是整图框。 */
        val box: IntArray? = null,
    )

    /**
     * 构造 `MaaContextRunRecognition` / `MaaTaskerPostTask` 用的
     * `NeuralNetworkClassify` 节点覆盖 JSON。
     *
     * @param model `model/classify/` 下的相对路径（如 `cls.onnx`）。
     * @param labels 可选类名表；仅影响框架调试图/日志（传 `cls.json` 的 `classes` 可让 detail
     *   里的 `label` 可读）。**不传也不影响**索引解析与 [resolveClassify]。
     */
    fun buildClassifyOverride(
        nodeName: String = DEFAULT_PROBE_NODE,
        model: String = DEFAULT_MODEL,
        labels: List<String> = emptyList(),
    ): String {
        val param = buildJsonObject {
            put("model", model)
            if (labels.isNotEmpty()) {
                put("labels", JsonArray(labels.map { JsonPrimitive(it) }))
            }
        }
        val node = buildJsonObject {
            put(
                "recognition",
                buildJsonObject {
                    put("type", "NeuralNetworkClassify")
                    put("param", param)
                },
            )
            // 只认不按：临时节点不触发任何动作
            put("action", buildJsonObject { put("type", "DoNothing") })
        }
        return buildJsonObject { put(nodeName, node) }.toString()
    }

    /**
     * 解析框架 classify 的 detail JSON。外层为 `{all, filtered, best}`；优先取 `best`，
     * 其次 `filtered[0]`，再退 `all[0]`（`best` 为 null 时框架会原样写 `null`）。
     *
     * 任何字段类型不符按缺失处理，不抛；取不到 `cls_index` 时返回 null。
     */
    fun parseClassifyDetail(detailJson: String?): YoloClassifyResult? {
        if (detailJson.isNullOrBlank()) return null
        val root = MaaJsonTree.parse(detailJson) as? Map<*, *> ?: return null
        val item = (root["best"] as? Map<*, *>)
            ?: firstMap(root["filtered"])
            ?: firstMap(root["all"])
            ?: return null
        val index = toIntOrNull(item["cls_index"]) ?: return null
        return YoloClassifyResult(
            clsIndex = index,
            label = item["label"] as? String ?: "",
            score = toDoubleOrNull(item["score"]) ?: 0.0,
            box = toBox(item["box"]),
        )
    }

    /**
     * 上游 `YoloPredictor.cpp:204-244` 的判定：
     *  1. `classes[cls_index]` 越界 → 无效（`valid=false`）；`rawClass` 回填框架 label 便于诊断；
     *  2. 类名 `"None"` → `valid=true, isNone=true, zoneId="None"`（跳过后续定位）；
     *  3. 其余 → `valid=true`，`zoneId=convertYoloNameToZoneId(name)`，
     *     原始类名再查 `tile_mapping` 得 ROI（`hasRoi`）。
     */
    fun resolveClassify(
        coarse: YoloClassifyResult,
        config: YoloConfig,
        mapping: YoloMapping,
    ): YoloCoarseResult {
        val name = config.classes.getOrNull(coarse.clsIndex)
        if (name == null) {
            // 上游这里 valid 保持初值 false（maxIdx 越界），但保留可读类名帮助诊断。
            return YoloCoarseResult(
                valid = false,
                isNone = false,
                rawClass = coarse.label,
                confidence = coarse.score.toFloat(),
            )
        }
        if (name == NONE_CLASS) {
            return YoloCoarseResult(
                valid = true,
                isNone = true,
                rawClass = NONE_CLASS,
                zoneId = NONE_CLASS,
                confidence = coarse.score.toFloat(),
            )
        }
        val tile = mapping.tileRegions[name]
        return YoloCoarseResult(
            valid = true,
            isNone = false,
            rawClass = name,
            baseClass = tile?.baseClass ?: "",
            zoneId = mapping.convertYoloNameToZoneId(name),
            confidence = coarse.score.toFloat(),
            hasRoi = tile != null,
            roiX = tile?.x ?: 0,
            roiY = tile?.y ?: 0,
            roiW = tile?.w ?: 0,
            roiH = tile?.h ?: 0,
            inferMargin = tile?.inferMargin ?: 0,
        )
    }

    /**
     * 人类可读的结果行，供 debug CLI 直接回显（纯逻辑，可单测）。
     * [frameworkLabel] 是框架 detail 里的 `label`（未传 labels 时是 `Unknown_{idx}`）。
     */
    fun describeOutcome(index: Int, frameworkLabel: String, outcome: YoloCoarseResult): List<String> = listOf(
        "cls_index : $index",
        "class     : ${outcome.rawClass.ifBlank { "-" }} (framework label: ${frameworkLabel.ifBlank { "-" }})",
        "valid     : ${outcome.valid}",
        "is_none   : ${outcome.isNone}",
        "zone_id   : ${outcome.zoneId.ifBlank { "-" }}",
        "base_class: ${outcome.baseClass.ifBlank { "-" }}",
        "roi       : " + if (outcome.hasRoi) {
            "[${outcome.roiX},${outcome.roiY},${outcome.roiW},${outcome.roiH}] infer_margin=${outcome.inferMargin}"
        } else {
            "-"
        },
        "confidence: ${outcome.confidence}",
    )

    private fun firstMap(value: Any?): Map<*, *>? =
        (value as? List<*>)?.firstOrNull() as? Map<*, *>

    private fun toIntOrNull(value: Any?): Int? = when (value) {
        is Long -> value.toInt()
        is Int -> value
        is Double -> value.toInt()
        else -> null
    }

    private fun toDoubleOrNull(value: Any?): Double? = when (value) {
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is Double -> value
        else -> null
    }

    private fun toBox(value: Any?): IntArray? {
        val list = value as? List<*> ?: return null
        if (list.size < 4) return null
        val nums = list.map { toIntOrNull(it) ?: return null }
        return intArrayOf(nums[0], nums[1], nums[2], nums[3])
    }
}
