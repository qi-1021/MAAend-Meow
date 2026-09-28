package com.aliothmoon.maafw.remote

/**
 * BetterSliding 的识别结果读取层（对齐上游 bettersliding/ocr.go）。
 *
 * 这里刻意不依赖 MaaFramework 的 `RecognitionDetail` 结构体，而是接受**已经转好的通用对象**
 * （JsonElement / Map / List 都行）：
 * 上游 `readHitBox` / `readQuantityValue` 的真正逻辑是「在一棵识别结果树里找某个名字的子节点，
 * 再取它的框或 OCR 文本」——这部分是纯逻辑，值得测；而「怎么从 MaaFramework 的 detail 取出
 * 这棵树」交给调用方。
 *
 * 取法遵循本仓库既有做法（见 [GoodsSupport.collectOcrItems]）：**递归遍历、不认具体路径**。
 * 一开始我按 `results.best.best_template_box` 这类猜的路径写过一版，那是错的——
 * MaaFramework 并没有那样固定的嵌套，`GoodsSupport` 之所以遍历整棵树正是因为这个原因。
 *
 * 剩余唯一假设是节点名字段用 `"name"`（MaaFramework detail JSON 的字段名）。
 * 这一条只能用真机 dump 一次 detailJson 来确认，不能靠推断。
 */
object BetterSlidingOcr {

    /** 识别结果节点：名字、该节点（或子树）的 OCR 文本、包围盒、子节点。 */
    data class Detail(
        val name: String,
        val text: String? = null,
        val box: List<Int>? = null,
        val children: List<Detail> = emptyList(),
    )

    /** 承载节点名的字段名。MaaFramework detail JSON 用 `"name"`。 */
    private const val NAME_KEY = "name"

    /** 上游 ocr.go:87：按名字深度优先找子节点。 */
    fun findByName(detail: Detail?, targetName: String): Detail? {
        if (detail == null) return null
        if (detail.name == targetName) return detail
        for (child in detail.children) {
            findByName(child, targetName)?.let { return it }
        }
        return null
    }

    /**
     * 从 `const MaaRect*` 解出的四元组构造 hit box，并做有效性判定。
     *
     * **这是 BetterSliding 起点/终点框的正路**：MaaCustomActionCallback 第 7 个参数
     * 就是本节点的命中框（框架识别成功后必然给出，见真机 `action_details.box`）。
     *
     * 有效性只要求**宽高为正**：`w == 0 || h == 0` 的退化框会让精确点击算出贴边坐标，
     * 必须当成「没拿到框」。`x` / `y` 允许为 0（识别结果贴屏幕左边/上边）。
     * `MaaRect` 的读取（JNA）交给调用方，这里是可单测的纯函数。
     */
    fun boxOfRect(x: Int, y: Int, w: Int, h: Int): List<Int>? =
        if (w > 0 && h > 0) listOf(x, y, w, h) else null

    /**
     * 上游 ocr.go:11 `readHitBox`。
     *
     * 优先取 [BetterSlidingSupport.NODE_SWIPE_BUTTON] 子节点（滑条手柄）的框；
     * 找不到就退回根节点的框。两者都没有则返回 null。
     *
     * 上游还有一级「换了候选节点时退回原始 detail 的框」——这里的树本来就是从根节点取的，
     * 所以那一级由「退回根节点框」承担。
     *
     * **真机实测：起点/终点框优先用回调第 7 个参数（[boxOfRect]），不要依赖这条 detail_json。**
     * 原因不是「数组根读不到」——2026-09 已修成能读（见 [fromRecognizedDetail]）；
     * 而是回调参数就是框架给的「本节点命中框」，语义最直接、少一层解析假设。
     * 这里的兜底在组合结果（And）下会取 `BetterSlidingSwipeButton` 子项的 box，通常与回调框一致。
     */
    fun readHitBox(detail: Detail?): List<Int>? {
        if (detail == null) return null
        val candidate = findByName(detail, BetterSlidingSupport.NODE_SWIPE_BUTTON) ?: detail
        return candidate.box?.takeIf { it.size >= 4 }
            ?: detail.box?.takeIf { it.size >= 4 }
    }

    /**
     * 上游 ocr.go:36 `readQuantityText`。
     *
     * 优先取 [BetterSlidingSupport.NODE_GET_SLIDER_QUANTITY] 子节点的文本，否则退回根节点。
     */
    fun readQuantityText(detail: Detail?): String? {
        if (detail == null) return null
        val candidate = findByName(detail, BetterSlidingSupport.NODE_GET_SLIDER_QUANTITY) ?: detail
        return (candidate.text ?: detail.text)?.trim()
    }

    /**
     * 上游 ocr.go:49 `readQuantityValue`。
     *
     * 从 OCR 文本里**只挑数字**再解析——所以 `共 1,024 件` 读出 1024，
     * 也所以「0 折」会被读成 0。滑条那行本来带单位，别为了「更严」改成整串必须是数字。
     */
    fun readQuantityValue(detail: Detail?): Int? {
        val text = readQuantityText(detail) ?: return null
        if (text.isEmpty()) return null
        val digits = text.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()
    }

    /**
     * 把 MaaFramework 的 detail（已解析成 JsonElement / Map / List 的通用对象）
     * 转成 [Detail] 树。
     *
     * 遍历策略与 [GoodsSupport.collectOcrItems] 一致：**不认路径**，递归所有嵌套的
     * object/array。凡带 `text` 的对象就是一处 OCR 命中，其 `box` 是包围盒。
     *
     * 根是数组时按「组合识别（And/Or）」处理：官方 Go 绑定
     * `recognition_result.go:325 parseCombinedResult` 明确 And/Or 的 `detail_json`
     * 根就是数组，每项形如
     * `{"algorithm":"And","box":[...],"detail":{all,best,filtered},"name":"...","reco_id":...}`。
     * [BetterSlidingSupport.NODE_GET_SLIDER_MAX_QUANTITY] /
     * [BetterSlidingSupport.NODE_CHECK_QUANTITY] / FindStart / FindEnd 全是 And 驱动节点，
     * 回调 recoId 查到的 detail 根就是这种数组。
     *
     * **真机 bug 回归（2026-09）**：此前只认对象根，数组根直接返回 null，
     * 于是 `readQuantityValue` 恒为 null，真机报「BetterSliding 读不到滑条上限」——
     * 而框架日志里 OCR 明明识别出 `7299`。这里包一层合成根，让 [findByName]
     * 能下钻到数组里的每一项。recoId 本身是有效的，问题一直在解析，不在取数。
     */
    fun fromRecognizedDetail(node: Any?, fallbackName: String): Detail? {
        val list = node as? List<*> ?: return toDetail(node, fallbackName)
        return Detail(name = fallbackName, children = list.mapNotNull { toDetail(it, "") })
    }

    private fun toDetail(node: Any?, fallbackName: String): Detail? {
        val map = asMap(node) ?: return null

        val name = (map[NAME_KEY] as? String) ?: fallbackName
        val ownBox = toBox(map["box"])
        val ownText = (map["text"] as? String)?.takeIf { it.isNotBlank() }

        // 子节点：递归所有嵌套的 object/array，不认 `combined_result` 这类具体路径
        val children = mutableListOf<Detail>()
        for ((key, value) in map) {
            if (key == "box" || key == "text") continue
            collectChildren(value, children)
        }

        // 上游 `readBestOCRText` 读的是 `RecognitionResults.Best`，OCR detail 里就是 `best`。
        // 不能只取「第一个有 text 的子节点」：JSON 键顺序通常是 all→best→filtered，
        // `only_rec=false` 时会取到 all[0]，与上游不一致。显式优先 `best`。
        val bestText = (asMap(map["best"])?.get("text") as? String)?.takeIf { it.isNotBlank() }

        // 自身没有 text 时，向下取 best / 第一个 OCR 文本（组合识别里 OCR 常在子节点）
        val text = ownText ?: bestText ?: children.firstNotNullOfOrNull { it.text }

        return Detail(name = name, text = text, box = ownBox, children = children)
    }

    private fun collectChildren(value: Any?, out: MutableList<Detail>) {
        when (value) {
            is List<*> -> for (item in value) {
                toDetail(item, "")?.let { out += it } ?: collectChildren(item, out)
            }

            else -> toDetail(value, "")?.let { out += it }
        }
    }

    private fun asMap(node: Any?): Map<*, *>? = when (node) {
        is Map<*, *> -> node
        // kotlinx.serialization 的 JsonObject 也是 Map，但 JsonPrimitive/JsonArray 需要显式展开，
        // 这里不引入序列化依赖，交给调用方转成 Map/List 即可。
        else -> null
    }

    private fun toBox(value: Any?): List<Int>? {
        val list = value as? List<*> ?: return null
        val nums = list.mapNotNull { (it as? Number)?.toInt() }
        return nums.takeIf { it.size >= 4 }?.take(4)
    }
}
