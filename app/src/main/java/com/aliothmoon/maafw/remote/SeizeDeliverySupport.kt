package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「抢占送货任务 / SeizeDeliveryJobs」链路的纯逻辑层。
 *
 * 对齐上游：
 *  - `upstream/maaend/agent/go-service/seizedeliveryjobs/find_target.go`（链式扫描 + 价格下限过滤 + 取首个目标）
 *  - `upstream/maaend/agent/go-service/seizedeliveryjobs/scan_target.go`（列表缓存 + 逐项点开查看的会话状态）
 *  - `upstream/maaend/assets/resource/pipeline/SeizeDeliveryJobs/SeizeDeliveryJobsCommon.json`
 *    与 `.../SeizeDeliveryJobs.json`、`.../SeizeDeliveryJobsEndpointFilter.json`、`assets/tasks/SeizeDeliveryJobs.json`
 *
 * 这两个识别在移动端原先恒假（MaaRunner 的 `falseRecognitions` 名单），整条链走不通。
 * 本文件只承载**不碰 Android / JNA**的部分：奖励解析、链式 ROI 偏移、候选的过滤/去重/排序、
 * 「找到目标」的取框、以及「扫描目标」的列表缓存会话。真正的框架调用（跑子识别）在接线补丁里。
 *
 * 关键语义（逐条对齐上游）：
 *  - 调度券图标（`__SeizeDeliveryJobsRecoCommissionToken`）是每行锚点，go 以它的 box 按固定偏移
 *    推导奖励数字、出发地、接取、查看位置的 ROI（[OFFSET_*]）。偏移是**相对上一个识别的 box**，
 *    不是全屏绝对坐标，链式写错会整体平移。
 *  - 奖励文本可能是「万/萬」或英文缩写 K/M（部分地区客户端），[parseRewardFloat] 统一到「万」。
 *  - 价格判定是 `>= 下限`（不是 `>`）；低于下限、任一 OCR 未命中的行整体跳过。
 *  - 「找到目标」（FindTarget）取列表**最上**（`items[0]`）的接取按钮框；
 *    「扫描目标」（ScanTarget）只在首次扫描时缓存整列达标委托，后续复用。
 */
object SeizeDeliverySupport {

    // ── 上游节点名（SeizeDeliveryJobsCommon.json）──

    /** 每行锚点：调度券图标（单节点多模板，多 box）。 */
    const val COMMISSION_TOKEN_NODE = "__SeizeDeliveryJobsRecoCommissionToken"

    /** 委托奖励金额 OCR（go per-box 覆写 roi）。 */
    const val REWARD_NODE = "__SeizeDeliveryJobsRecoReward"

    /** 委托出发地 OCR。 */
    const val ORIGIN_NODE = "__SeizeDeliveryJobsRecoOrigin"

    /** 接取运送委托按钮文本 OCR。 */
    const val ACCEPT_NODE = "__SeizeDeliveryJobsRecoAccept"

    /** 查看位置按钮文本 OCR。 */
    const val VIEW_LOCATION_NODE = "__SeizeDeliveryJobsRecoViewLocation"

    /** 价格下限配置仓库（expected 由 tasks 覆写为用户输入）。 */
    const val MIN_REWARD_NODE = "__SeizeDeliveryJobsMinReward"

    // ── 被 go 覆写 target 的流程节点（SeizeDeliveryJobsEndpointFilter.json）──

    /** 扫描路径：点击委托项的「查看位置」（由 action 覆写 target）。 */
    const val VIEW_LOCATION_CLICK_NODE = "SeizeDeliveryJobsFoundTargetViewLocationClick"

    /** 扫描路径：点击「接取运送委托」（由 action 覆写 target）。 */
    const val ACCEPT_CLICK_NODE = "SeizeDeliveryJobsAcceptClick"

    /** FindTarget 的 detail 标记（上游返回的 Detail 字符串；本平台无法回写 out_detail，仅作记录）。 */
    const val FIND_TARGET_DETAIL = """{"custom":"SeizeDeliveryJobsFindTarget"}"""

    // ── 链式 ROI 偏移（find_target.go:26-31，照搬原档位 And 的 sub 间相对 offset）──

    /** 调度券锚点 box → 奖励金额 roi：`box + (0,30,0,-20)`。 */
    val OFFSET_TOKEN_TO_REWARD = intArrayOf(0, 30, 0, -20)

    /** 奖励 box → 出发地 roi：`box + (-229,-38,50,14)`。 */
    val OFFSET_REWARD_TO_ORIGIN = intArrayOf(-229, -38, 50, 14)

    /** 奖励 box → 接取按钮 roi：`box + (226,-4,70,12)`。 */
    val OFFSET_REWARD_TO_ACCEPT = intArrayOf(226, -4, 70, 12)

    /** 奖励 box → 查看位置 roi：`box + (-215,-8,34,10)`。 */
    val OFFSET_REWARD_TO_VIEW = intArrayOf(-215, -8, 34, 10)

    /** 去重时判定「同一行」的锚点框 IoU 阈值（模板重复命中同一张卡时用）。 */
    const val DEDUPE_IOU = 0.5

    // ── 数据模型 ──

    /** 四元组框 `[x, y, w, h]`（上游 `maa.Rect` / `deliveryJobItem` 的 box）。 */
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        /** 可点击/可用的框：宽高必须为正。零宽高在精确点击时会算出贴边坐标，一律视为坏框。 */
        val valid: Boolean get() = w > 0 && h > 0

        val centerY: Double get() = y + h / 2.0
        val centerX: Double get() = x + w / 2.0

        fun toList(): List<Int> = listOf(x, y, w, h)
    }

    /**
     * 一行委托的「已解析观测」。wiring 里每跑完一次 OCR 就填一行，
     * 未命中的项留 null（对齐上游 `ocrFirst` 返回 false）。
     */
    data class RowObservation(
        val rewardText: String? = null,
        val rewardBox: Box? = null,
        val originText: String? = null,
        val originBox: Box? = null,
        val acceptBox: Box? = null,
        val viewLocationBox: Box? = null,
    )

    /** 达标委托项（上游 `deliveryJobItem`）。 */
    data class JobItem(
        val rewardBox: Box,
        val originText: String,
        val acceptBox: Box,
        val viewLocationBox: Box,
    )

    /** 扫描路径一次 action 要覆写的两个点击目标（上游 `ScanTargetAction`）。 */
    data class ScanTargets(val viewLocation: Box, val accept: Box)

    /** OCR 首个 filtered 命中（上游 `ocrFirst`：text + box）。 */
    data class FilteredHit(val text: String, val box: Box?)

    // ── 奖励/下限解析 ──

    /**
     * 上游 `parseRewardFloat`（find_target.go:70-93）：价格文本 → 「万」单位的 double。
     *
     *  - `"16.3万"` / `"16.3萬"` → 16.3；
     *  - `"119K"` / `"119k"` → 11.9（1K = 1000 = 0.1 万）；
     *  - `"1.2M"` / `"1.2m"` → 120.0（1M = 1000000 = 100 万）；
     *  - 无单位按已是「万」处理；
     *  - 空串 / 解析失败 / 只有单位没有数字 → null（调用方跳过该行）。
     */
    fun parseRewardFloat(raw: String?): Double? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.endsWith("万")) return s.dropLast(1).trim().toDoubleOrNull()
        if (s.endsWith("萬")) return s.dropLast(1).trim().toDoubleOrNull()
        if (s.length > 1 && (s.last() == 'K' || s.last() == 'k')) {
            return s.dropLast(1).trim().toDoubleOrNull()?.div(10.0)
        }
        if (s.length > 1 && (s.last() == 'M' || s.last() == 'm')) {
            return s.dropLast(1).trim().toDoubleOrNull()?.times(100.0)
        }
        return s.toDoubleOrNull()
    }

    /**
     * 上游 `readMinReward`（find_target.go:33-65）：从 `__SeizeDeliveryJobsMinReward`
     * 节点定义 JSON 里取价格下限。
     *
     * expected 可能出现在 V1 顶层（`expected`）或 V2（`recognition.param.expected`）；
     * 取 `[0]`。缺失 / 空 / 解析失败返回 null（调用方按未命中处理）。
     */
    fun parseMinReward(nodeJson: String?): Double? {
        if (nodeJson.isNullOrBlank()) return null
        val root = MaaJsonTree.parse(nodeJson) as? Map<*, *> ?: return null
        val topExpected = firstString(root["expected"])
        val v2Expected = firstString(
            ((root["recognition"] as? Map<*, *>)?.get("param") as? Map<*, *>)?.get("expected"),
        )
        val raw = topExpected ?: v2Expected ?: return null
        return parseRewardFloat(raw)
    }

    private fun firstString(value: Any?): String? {
        val list = value as? List<*> ?: return null
        return list.firstOrNull() as? String
    }

    // ── 链式 ROI 偏移 ──

    /**
     * 上游 `offsetBox`（find_target.go:106-112）：`box + offset` 得到下一个识别的 roi。
     * box 或 offset 不合法（不足 4）返回 null。
     */
    fun offsetBox(box: Box?, offset: IntArray): Box? {
        if (box == null || offset.size < 4) return null
        return Box(box.x + offset[0], box.y + offset[1], box.w + offset[2], box.h + offset[3])
    }

    /** 上游 `roiOverride(node, rect)`：`{"node":{"roi":[x,y,w,h]}}`，用于子识别的 per-box ROI 覆写。 */
    fun roiOverrideJson(node: String, box: Box): String = buildJsonObject {
        put(node, buildJsonObject {
            put("roi", toJsonArray(box))
        })
    }.toString()

    // ── 识别 detail 解析 ──

    /**
     * 从识别 detail 树里取 `filtered` 的所有框（调度券锚点用）。
     *
     * MaaFramework 的模板识别 detail 形态为 `{all,best,filtered}`，结果形如 `{box:[x,y,w,h],score:...}`。
     * 取不到 / 类型不符返回空列表，不抛。
     */
    fun parseFilteredBoxes(detailTree: Any?): List<Box> {
        val root = detailTree as? Map<*, *> ?: return emptyList()
        val filtered = root["filtered"] as? List<*> ?: return emptyList()
        return filtered.mapNotNull { boxOf(it) }
    }

    /**
     * 上游 `ocrFirst`：取 `filtered[0]` 的文本与框（找不到返回 null）。
     * text 缺失时以空串表示「命中了但没读出文字」。
     */
    fun parseFilteredFirst(detailTree: Any?): FilteredHit? {
        val root = detailTree as? Map<*, *> ?: return null
        val filtered = root["filtered"] as? List<*> ?: return null
        val first = filtered.firstOrNull() as? Map<*, *> ?: return null
        val text = (first["text"] as? String).orEmpty()
        return FilteredHit(text, boxOf(first))
    }

    private fun boxOf(node: Any?): Box? {
        val map = node as? Map<*, *> ?: return null
        val list = map["box"] as? List<*> ?: return null
        val nums = list.mapNotNull { (it as? Number)?.toInt() }
        if (nums.size < 4) return null
        return Box(nums[0], nums[1], nums[2], nums[3])
    }

    // ── 候选装配：过滤 / 去重 / 排序 ──

    /**
     * 上游 `scanJobs` 的纯逻辑等价：
     *  1. 奖励框有效且文本可解析、`price >= minReward`；
     *  2. 出发地 / 接取 / 查看位置均命中（框有效）——任一缺失整行跳过；
     *  3. 按锚点框去重、按纵向（先 centerY 再 centerX）排序。
     *
     * 输出顺序即「列表自上而下」，[findTargetBox] 取首个。
     */
    fun assembleJobs(rows: List<RowObservation>, minReward: Double): List<JobItem> {
        val out = ArrayList<JobItem>()
        for (row in rows) {
            val rewardBox = row.rewardBox?.takeIf { it.valid } ?: continue
            val price = parseRewardFloat(row.rewardText) ?: continue
            if (price < minReward) continue
            val acceptBox = row.acceptBox?.takeIf { it.valid } ?: continue
            val viewBox = row.viewLocationBox?.takeIf { it.valid } ?: continue
            if (row.originBox?.valid != true) continue
            out += JobItem(
                rewardBox = rewardBox,
                originText = row.originText.orEmpty(),
                acceptBox = acceptBox,
                viewLocationBox = viewBox,
            )
        }
        return sortVertical(dedupeByAnchor(out))
    }

    /** 锚点框 IoU >= [DEDUPE_IOU] 视为同一行重复命中，保留先出现者。 */
    fun dedupeByAnchor(items: List<JobItem>): List<JobItem> {
        val out = ArrayList<JobItem>(items.size)
        for (item in items) {
            if (out.none { iou(it.rewardBox, item.rewardBox) >= DEDUPE_IOU }) out += item
        }
        return out
    }

    /** 上游列表 `order_by: "Vertical"`：按中心 y 再按中心 x 排序。 */
    fun sortVertical(items: List<JobItem>): List<JobItem> =
        items.sortedWith(compareBy({ it.rewardBox.centerY }, { it.rewardBox.centerX }))

    /** 两个框的交并比；任一面积为 0 返回 0。 */
    fun iou(a: Box, b: Box): Double {
        val areaA = a.w.toLong() * a.h
        val areaB = b.w.toLong() * b.h
        if (areaA <= 0 || areaB <= 0) return 0.0
        val ix = maxOf(a.x, b.x)
        val iy = maxOf(a.y, b.y)
        val ix2 = minOf(a.x + a.w, b.x + b.w)
        val iy2 = minOf(a.y + a.h, b.y + b.h)
        val iw = ix2 - ix
        val ih = iy2 - iy
        if (iw <= 0 || ih <= 0) return 0.0
        val inter = iw.toLong() * ih
        val union = areaA + areaB - inter
        return inter.toDouble() / union.toDouble()
    }

    /**
     * 上游 `SeizeDeliveryJobsFindTargetRecognition`：返回首个（列表最上）达标委托的接取按钮框。
     * 空列表或无有效接取框返回 null（识别未命中）。
     */
    fun findTargetBox(items: List<JobItem>): Box? =
        items.firstOrNull()?.acceptBox?.takeIf { it.valid }

    // ── 扫描会话（ScanTarget 识别 + action 的状态机）──

    /**
     * 上游 `scan_target.go` 的包级全局状态 `scannedJobItems` / `currentIndex` 的纯逻辑载体。
     *
     * 语义：
     *  - 首次扫描成功后 [store]（已缓存时再 [store] 被忽略，对齐 `if scannedJobItems != nil` 复用）；
     *  - [current] 取当前项；[advance] 前进，越界返回 false；
     *  - 全部用尽（[current] == null）时 action 返回 false → on_error `ScanExhausted` → 刷新；
     *  - [reset] 清空（`SeizeDeliveryJobsResetScanStateAction`）。
     */
    class ScanSession {
        private var stored: List<JobItem>? = null
        var index: Int = 0
            private set

        /** 已经扫描过（后续调用直接复用，识别恒命中）。 */
        val isScanned: Boolean get() = stored != null

        val size: Int get() = stored?.size ?: 0

        /** 剩余未处理项数（`len(items) - index`，可能为负时按 0 处理仅用于日志）。 */
        val remaining: Int get() = size - index

        /** 首次扫描结果入队；已缓存则忽略（对齐上游复用语义）。 */
        fun store(items: List<JobItem>) {
            if (stored == null) {
                stored = items
                index = 0
            }
        }

        /** 当前待处理项；无 / 已用尽返回 null。 */
        fun current(): JobItem? = stored?.getOrNull(index)

        /** 前进一项；无当前项返回 false（调用方据此走 on_error）。 */
        fun advance(): Boolean {
            if (current() == null) return false
            index++
            return true
        }

        /** 清空扫描状态。 */
        fun reset() {
            stored = null
            index = 0
        }
    }

    /**
     * 上游 `ScanTargetAction`：当前项有效则给出两个点击目标（查看位置 + 接取）。
     * 任一框无效返回 null（action 应返回失败）。
     */
    fun scanTargets(item: JobItem): ScanTargets? {
        if (!item.viewLocationBox.valid || !item.acceptBox.valid) return null
        return ScanTargets(viewLocation = item.viewLocationBox, accept = item.acceptBox)
    }

    /**
     * 上游 `ScanTargetAction` 的 `OverridePipeline`：
     * `{"...ViewLocationClick":{"target":[...]},"...AcceptClick":{"target":[...]}}`。
     */
    fun buildScanOverrideJson(targets: ScanTargets): String = buildJsonObject {
        put(VIEW_LOCATION_CLICK_NODE, buildJsonObject { put("target", toJsonArray(targets.viewLocation)) })
        put(ACCEPT_CLICK_NODE, buildJsonObject { put("target", toJsonArray(targets.accept)) })
    }.toString()

    private fun toJsonArray(box: Box): JsonArray = JsonArray(
        listOf(
            JsonPrimitive(box.x),
            JsonPrimitive(box.y),
            JsonPrimitive(box.w),
            JsonPrimitive(box.h),
        ),
    )
}
