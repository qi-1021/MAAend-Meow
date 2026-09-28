package com.aliothmoon.maafw.remote

/**
 * `autoEcoFarmOverrideTargetTemplate` 的纯逻辑。
 *
 * 上游对应 `autoecofarm/targettemplateoverride.go`（134 行）。
 *
 * 这个 action 是**两个识别的必要前置**，不是可选优化：
 * `AutoEcoFarmFindFarmland` / `AutoEcoFarmSwipeToTarget` 的入口节点靠它把
 * `_AutoEcoFarmTargeRecognitionFullScreen` 等节点的 `recognition.param.template`
 * 从「追踪标记」切成「农田标记」。它若保持 noop，识别链仍会去找旧模板，
 * 表现为「分支能进了，但一直找不到目标、随机转视角」——正是要避免的静默错误。
 *
 * 构造覆盖片段是纯逻辑（吃通用树、吐 Map），可本机单测；写回 pipeline 在 MaaRunner。
 */
object AutoEcoFarmOverride {

    /** 上游 `autoEcoFarmOverrideTargetTemplateParam`。 */
    data class Param(val template: String, val nodeNames: List<String>)

    /**
     * 解析 `custom_action_param`。语义同上游 `json.Unmarshal`：
     * 空串/非法 JSON/字段类型不对 → null（整节点失败）。
     */
    fun parseParam(raw: String?): Param? {
        if (raw.isNullOrBlank()) return null
        val map = MaaJsonTree.parse(raw) as? Map<*, *> ?: return null
        val template = templateOf(map) ?: return null
        val nodeNames = nodeNamesOf(map) ?: return null
        return Param(template, nodeNames)
    }

    /**
     * 清洗节点名：去首尾空白、丢掉空串、保留原顺序（上游 `normalizeTargetTemplateNodeNames`）。
     */
    fun normalizeNodeNames(nodeNames: List<String>): List<String> =
        nodeNames.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 构造整份 override；参数不合法（template 为空 / 清洗后 nodeNames 为空）返回 null。
     *
     * 只改 `recognition.param.template`，不碰 roi/threshold/next，把影响压到最小。
     */
    fun buildOverride(param: Param): Map<String, Any?>? {
        val template = param.template.trim()
        val nodeNames = normalizeNodeNames(param.nodeNames)
        if (template.isEmpty() || nodeNames.isEmpty()) return null

        val override = LinkedHashMap<String, Any?>()
        for (nodeName in nodeNames) {
            override[nodeName] = mapOf(
                "recognition" to mapOf(
                    "param" to mapOf(
                        "template" to listOf(template),
                    ),
                ),
            )
        }
        return override
    }

    private fun templateOf(map: Map<*, *>): String? {
        if (!map.containsKey("template")) return ""
        val value = map["template"] ?: return ""
        return value as? String ?: return null
    }

    private fun nodeNamesOf(map: Map<*, *>): List<String>? {
        if (!map.containsKey("nodeNames")) return emptyList()
        val value = map["nodeNames"] ?: return emptyList()
        val list = value as? List<*> ?: return null
        val out = mutableListOf<String>()
        for (item in list) {
            out += item as? String ?: return null
        }
        return out
    }
}
