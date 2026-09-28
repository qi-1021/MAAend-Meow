package com.aliothmoon.maafw.remote

/**
 * `autoEcoFarmFindNearestRecognitionResult` 的纯逻辑。
 *
 * 上游对应 `autoecofarm/findNearestMaaResult.go`（196 行）：
 * 先跑一个由参数指定的模板识别节点，在它的 **filtered** 结果里取所有框，
 * 用这些框的**左上角**插值出一个「目标点」（`min + (max-min)*ratio`），
 * 再返回**中心**离目标点欧几里得距离平方最小的那个框。
 *
 * 注意两个和直觉不太一样、写错只表现为「点错田块」的点：
 *  1. 插值用的是左上角 `X()/Y()`，不是中心；最近判定用的才是中心；
 *  2. ratio 是相对**所有 filtered 结果的包络**（min..max），不是整屏。
 *
 * detail JSON 的形态由 MaaFramework C API 决定，与 maa-framework-go 解析的完全一致：
 * `{"all":[Result...], "best":Result|null, "filtered":[Result...]}`，
 * 模板结果形如 `{"box":[x,y,w,h], "score":...}`。上游读 `detail.Results.Filtered`，
 * 所以这里也以 `filtered` 为准。若宿主形态意外不同，退回 [BetterSlidingOcr] 的递归收集兜底。
 */
object AutoEcoFarmNearest {

    /** 上游参数缺省值。 */
    const val DEFAULT_RATIO = 0.5

    /** 上游 `autoEcoFarmFindNearestRecognitionResultParams`。 */
    data class Param(val recognitionNodeName: String, val xRatio: Double, val yRatio: Double) {
        companion object {
            val DEFAULT = Param("", DEFAULT_RATIO, DEFAULT_RATIO)
        }
    }

    /** 上游 `maa.Rect`（x, y, w, h）。 */
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val centerX: Double get() = x + w / 2.0
        val centerY: Double get() = y + h / 2.0
    }

    /**
     * 解析 `custom_recognition_param`。语义同上游 `json.Unmarshal`：
     * 空串 → 默认（name 为空，随后会被 [validate] 判失败）；字段缺失/null → 默认；
     * 类型不对 → 返回 null（整节点失败）。
     */
    fun parseParam(raw: String?): Param? {
        if (raw.isNullOrEmpty()) return Param.DEFAULT
        val map = MaaJsonTree.parse(raw) as? Map<*, *> ?: return null
        val name = stringOf(map, "recognitionNodeName") ?: return null
        val x = ratioOf(map, "xRatio") ?: return null
        val y = ratioOf(map, "yRatio") ?: return null
        return Param(name, x, y)
    }

    /** 上游 Run 开头的参数校验：name 非空且两个 ratio 都在 [0,1]。 */
    fun validate(param: Param): Boolean =
        param.recognitionNodeName.isNotEmpty() &&
            param.xRatio >= 0.0 && param.xRatio <= 1.0 &&
            param.yRatio >= 0.0 && param.yRatio <= 1.0

    /**
     * 在识别结果树里挑出离 `(xRatio, yRatio)` 位置最近的框。
     * 结果为空或树里没有任何框时返回 null。
     */
    fun pickNearest(detailTree: Any?, xRatio: Double, yRatio: Double): Rect? {
        val boxes = boxesOf(detailTree) ?: return null
        if (boxes.isEmpty()) return null

        // 边界用左上角（上游 minX/maxX/minY/maxY 就是 Box.X()/Y()）
        var minX = boxes[0].x
        var maxX = boxes[0].x
        var minY = boxes[0].y
        var maxY = boxes[0].y
        for (box in boxes) {
            if (box.x < minX) minX = box.x
            if (box.x > maxX) maxX = box.x
            if (box.y < minY) minY = box.y
            if (box.y > maxY) maxY = box.y
        }

        val targetX = minX + (maxX - minX) * xRatio
        val targetY = minY + (maxY - minY) * yRatio

        var best = boxes[0]
        var bestDistance2 = distance2(best, targetX, targetY)
        for (index in 1 until boxes.size) {
            val candidate = boxes[index]
            val distance = distance2(candidate, targetX, targetY)
            if (distance < bestDistance2) {
                bestDistance2 = distance
                best = candidate
            }
        }
        return best
    }

    private fun distance2(box: Rect, targetX: Double, targetY: Double): Double {
        val dx = box.centerX - targetX
        val dy = box.centerY - targetY
        return dx * dx + dy * dy
    }

    // ───────────────────────── 结果提取 ─────────────────────────

    /** 优先读 `filtered`；取不到再退回递归收集（见类注释）。 */
    private fun boxesOf(detailTree: Any?): List<Rect>? {
        val map = detailTree as? Map<*, *> ?: return null
        val filtered = map["filtered"]
        if (filtered is List<*>) return filtered.mapNotNull { boxOf(it) }

        val detail = BetterSlidingOcr.fromRecognizedDetail(detailTree, "") ?: return null
        val out = mutableListOf<Rect>()
        collectBoxes(detail, out)
        return out
    }

    private fun boxOf(node: Any?): Rect? {
        val map = node as? Map<*, *> ?: return null
        val list = map["box"] as? List<*> ?: return null
        val nums = list.mapNotNull { (it as? Number)?.toInt() }
        if (nums.size < 4) return null
        return Rect(nums[0], nums[1], nums[2], nums[3])
    }

    private fun collectBoxes(detail: BetterSlidingOcr.Detail, out: MutableList<Rect>) {
        detail.box?.takeIf { it.size >= 4 }?.let {
            out += Rect(it[0], it[1], it[2], it[3])
        }
        for (child in detail.children) collectBoxes(child, out)
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun stringOf(map: Map<*, *>, key: String): String? {
        if (!map.containsKey(key)) return ""
        val value = map[key] ?: return ""
        return value as? String ?: return null
    }

    private fun ratioOf(map: Map<*, *>, key: String): Double? {
        if (!map.containsKey(key)) return DEFAULT_RATIO
        val value = map[key] ?: return DEFAULT_RATIO
        return (value as? Number)?.toDouble() ?: return null
    }
}
