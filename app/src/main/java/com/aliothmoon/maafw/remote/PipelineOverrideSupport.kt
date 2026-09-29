package com.aliothmoon.maafw.remote

/**
 * `PipelineOverride` / `PipelineOverrideAction` 的纯逻辑层。
 *
 * 上游对应 `agent/go-service/common/pipelineoverride/action.go`（196 行，
 * 注册名见 `register.go`：`PipelineOverride` 与 `PipelineOverrideAction` 是同一个
 * runner 的两个别名）。这里只负责「参数解析 + 开关判定 + strip next」，
 * 真正写回流水线（[MaaContextOverridePipeline]）留在 MaaRunner。
 *
 * 逐条对齐上游语义：
 *  - `patch` 必填且必须是非空 JSON 对象；缺字段 / 类型不符 / 空对象一律失败
 *    （上游 `params.Patch` 反序列化后 len==0 → return false）。
 *  - 每个 patch 条目的值必须是 JSON 对象，节点名不能为空串。
 *  - `allow_next` 默认 false；为 false 时**移除**每个节点片段顶层的 `next`，
 *    保持预设拓扑；为 true 时原样保留。
 *  - `strict` 默认 false；仅在 `allow_next=false` 时生效：
 *    若某节点片段带 `next` 则**直接失败**而不是静默移除。
 *    `allow_next=true` 时 `strict` 被忽略（上游打 Info 日志后置 false）。
 *  - `resource_override` 默认 false（Context/当前任务级）；true 时上游走
 *    `Resource.OverridePipeline`（资源级）。
 *  - 三个开关若出现但类型不是布尔（JSON null 视同缺省）→ 失败，
 *    对齐 Go 反序列化进 `*bool` 的行为。
 *  - 解析失败**一律返回失败**（[Outcome.Rejected]），不再 warn 后当成功。
 *
 * 关键设计：结果里带 `strippedNextNodes` / `allowNext` / `strictRequested` /
 * `resourceOverride`，让调用方能把「strip 了谁」「strict 被忽略」「resource_override
 * 不受本宿主支持」这些决策**显式打日志**，杜绝「看着像支持了其实忽略」。
 */
object PipelineOverrideSupport {

    /** 解析结果：要么给出可应用的干净 patch，要么给出明确失败原因。 */
    sealed interface Outcome {

        /**
         * 解析成功。
         *
         * @param cleanPatch 已按 allow_next/strict 处理过的 patch（节点名 → 节点片段）。
         * @param allowNext 是否保留 `next`。
         * @param strictRequested 参数里是否显式要求 strict（用于调用方判断是否被忽略）。
         * @param resourceOverride 参数里是否要求资源级覆盖。
         * @param strippedNextNodes 被移除 `next` 的节点名（按 patch 顺序）。
         */
        data class Apply(
            val cleanPatch: Map<String, Any?>,
            val allowNext: Boolean,
            val strictRequested: Boolean,
            val resourceOverride: Boolean,
            val strippedNextNodes: List<String>,
        ) : Outcome

        /** 参数非法，调用方必须按失败处理并打日志。 */
        data class Rejected(val reason: String) : Outcome
    }

    /** 内部用来区分「缺字段 / 合法布尔 / 类型错」——JSON null 等同缺字段（Go `*bool` 语义）。 */
    private sealed interface BoolField {
        data class Value(val value: Boolean) : BoolField
        data object Absent : BoolField
        data object Invalid : BoolField
    }

    fun parse(raw: String?): Outcome {
        if (raw.isNullOrBlank()) return Outcome.Rejected("custom_action_param 为空")

        val root = MaaJsonTree.parse(raw) as? Map<*, *>
            ?: return Outcome.Rejected("custom_action_param 不是 JSON 对象")

        val patch = root["patch"] as? Map<*, *>
            ?: return Outcome.Rejected("缺少必填字段 patch（必须是 JSON 对象）")
        if (patch.isEmpty()) return Outcome.Rejected("patch 不能为空")

        val allowNext = when (val f = boolField(root, "allow_next")) {
            is BoolField.Value -> f.value
            BoolField.Absent -> false
            BoolField.Invalid -> return Outcome.Rejected("allow_next 必须是布尔值")
        }
        val strictRequested = when (val f = boolField(root, "strict")) {
            is BoolField.Value -> f.value
            BoolField.Absent -> false
            BoolField.Invalid -> return Outcome.Rejected("strict 必须是布尔值")
        }
        val resourceOverride = when (val f = boolField(root, "resource_override")) {
            is BoolField.Value -> f.value
            BoolField.Absent -> false
            BoolField.Invalid -> return Outcome.Rejected("resource_override 必须是布尔值")
        }

        val cleanPatch = LinkedHashMap<String, Any?>()
        val stripped = ArrayList<String>()

        for ((rawName, rawNode) in patch) {
            val nodeName = rawName as? String
                ?: return Outcome.Rejected("patch 的键必须是节点名字符串")
            if (nodeName.isBlank()) return Outcome.Rejected("patch 含空节点名")

            val nodeObj = rawNode as? Map<*, *>
                ?: return Outcome.Rejected("节点「$nodeName」的 patch 必须是 JSON 对象")

            val cloned = LinkedHashMap<String, Any?>()
            for ((k, v) in nodeObj) cloned[k?.toString() ?: ""] = v

            if (!allowNext && cloned.containsKey("next")) {
                if (strictRequested) {
                    return Outcome.Rejected("节点「$nodeName」在 allow_next=false 下含 next（strict）")
                }
                cloned.remove("next")
                stripped += nodeName
            }

            cleanPatch[nodeName] = cloned
        }

        return Outcome.Apply(
            cleanPatch = cleanPatch,
            allowNext = allowNext,
            strictRequested = strictRequested,
            resourceOverride = resourceOverride,
            strippedNextNodes = stripped,
        )
    }

    private fun boolField(map: Map<*, *>, key: String): BoolField {
        if (!map.containsKey(key)) return BoolField.Absent
        return when (val v = map[key]) {
            null -> BoolField.Absent
            is Boolean -> BoolField.Value(v)
            else -> BoolField.Invalid
        }
    }
}
