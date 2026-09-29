package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `DeliveryJobsResolveOngoingDepotAction` 的纯逻辑层（对齐上游
 * `agent/go-service/deliveryjobs/ongoing_delivery.go` 与 `autodelivery/`）。
 *
 * 用途：送货前发现**残留的未完成送货任务**时，从任务详情的区域 OCR 文本解析它属于哪个仓储，
 * 再把当前节点的 `next` 覆盖到该仓储自己的分派节点 `DeliveryJobsOngoingDeliveryFor<AreaID>`。
 *
 * 关键语义（逐条对齐上游）：
 *  - 区域匹配复用 [AutoDeliverySupport.resolveArea]（归一化 + 编辑距离 + 0.70 阈值 + 0.05 边际），
 *    不在这里重造匹配算法。
 *  - `<AreaID>` = [AutoDeliverySupport.localizedAreaID]（`en_us` 区域名去非字母数字、**保留大小写**）。
 *  - **解析失败即失败**：上游解析不出来时打印原因并返回 false，不走任何默认分支。
 *    残留送货若静默走默认分支会让整条流程走偏到结束，所以这里同样抛异常交给调用方失败。
 */
object OngoingDeliverySupport {
    /** 上游注册名，MaaRunner 必须用这个名字注册真实动作并从 noop 名单删除。 */
    const val ACTION_NAME = "DeliveryJobsResolveOngoingDepotAction"

    /** 被覆盖 `next` 的节点（上游 resolveOngoingDepotNode）。 */
    const val RESOLVE_NODE = "DeliveryJobsResolveOngoingDepot"

    /** 任务详情里承载区域文本的 OCR 子节点（上游 areaTextNode）。 */
    const val AREA_TEXT_NODE = "AutoDeliveryCheckAreaText"

    /** 各仓储分派节点的公共前缀（上游 ongoingDeliveryNodePrefix）。 */
    const val ONGOING_NODE_PREFIX = "DeliveryJobsOngoingDeliveryFor"

    data class Resolution(
        /** MaaEnd 侧仓储节点 ID，可直接拼业务节点名。 */
        val areaId: String,
        /** 游戏侧仓储 ID（用于日志/校验）。 */
        val depotId: String,
        /** 覆盖到 next 的目标节点。 */
        val nextNode: String,
        val similarity: Double,
        val runnerUp: Double,
    )

    /**
     * 区域 OCR 文本 → 分派节点覆盖信息。
     *
     * @throws AutoDeliverySupport.ResolveException 文本为空、未达阈值/歧义、或区域无关联仓储。
     */
    fun resolve(areaText: String): Resolution {
        if (areaText.isBlank()) {
            throw AutoDeliverySupport.ResolveException("区域 OCR 文本缺失")
        }
        val (area, match) = AutoDeliverySupport.resolveArea(areaText)
        if (area.depotId.isBlank()) {
            throw AutoDeliverySupport.ResolveException("送货区域「${area.id}」没有关联仓储，无法分派残留送货")
        }
        return Resolution(
            areaId = area.id,
            depotId = area.depotId,
            nextNode = ONGOING_NODE_PREFIX + area.id,
            similarity = match.similarity,
            runnerUp = match.runnerUp,
        )
    }

    /**
     * 从识别结果树里取区域节点（[AREA_TEXT_NODE]）的 OCR 文本。
     *
     * 对齐上游 `AreaTextFromRecognition`：按节点名深度优先查找子节点；找不到节点或文本为空都算失败。
     */
    fun extractAreaText(
        detail: BetterSlidingOcr.Detail?,
        areaNodeName: String = AREA_TEXT_NODE,
    ): String {
        val node = BetterSlidingOcr.findByName(detail, areaNodeName)
            ?: throw AutoDeliverySupport.ResolveException("识别结果里缺少区域节点「$areaNodeName」")
        val text = node.text?.trim()
        if (text.isNullOrEmpty()) {
            throw AutoDeliverySupport.ResolveException("区域节点「$areaNodeName」没有 OCR 文本")
        }
        return text
    }

    /**
     * 生成覆盖 [RESOLVE_NODE] 的 `next` 字段的 pipeline override。
     *
     * MaaRunner 没有绑定 `MaaContextOverrideNext`（见 DEVLOG），本项目统一用「覆盖 next 字段」的等价做法。
     */
    fun nextOverrideJson(nextNode: String): String = buildJsonObject {
        put(RESOLVE_NODE, buildJsonObject {
            put("next", JsonArray(listOf(JsonPrimitive(nextNode))))
        })
    }.toString()
}
