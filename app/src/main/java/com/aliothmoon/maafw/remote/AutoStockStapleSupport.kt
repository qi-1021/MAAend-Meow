package com.aliothmoon.maafw.remote

/**
 * `AutoStockStapleQuantityControlAction` 的纯逻辑层
 * （对齐上游 autostockstaple/action.go）。
 *
 * 这个动作负责「这一格商品该买多少个」：从校验节点的表达式里读出阈值与计数节点名，
 * 截图 OCR 出当前持有量，算出还差多少，再把 `TargetQuantity` 覆盖到 BetterSliding 上。
 * 真正的拖动交给 [BetterSlidingSession]（两者是兄弟分支，本动作只负责给目标值）。
 *
 * 纯逻辑（参数解析、节点名拼装、表达式解析、覆盖构造、OCR 文本提取）放这里测；
 * 截图与跑识别留给 MaaRunner。
 */
object AutoStockStapleSupport {

    const val DEFAULT_SLIDING_NODE = "AutoStockStapleBetterSliding"

    /** 上游 action.go:27 quantityControlActionParam。 */
    data class Param(
        val itemName: String,
        val validatorNode: String,
        val slidingNode: String,
    )

    /** 上游 action.go:249 `parseQuantityControlActionParam`。 */
    fun parseParam(tree: Any?): Param? {
        val map = tree as? Map<*, *> ?: return null
        val itemName = (map["item_name"] as? String)?.trim().orEmpty()
        val validatorNode = (map["validator_node"] as? String)?.trim().orEmpty()
        val slidingNode = (map["sliding_node"] as? String)?.trim().orEmpty()
        // 上游要求二者至少给一个：给不出节点名就没法查阈值
        if (itemName.isEmpty() && validatorNode.isEmpty()) return null
        return Param(itemName, validatorNode, slidingNode)
    }

    /**
     * 上游 action.go:266 `buildValidatorNodeName`。
     *
     * `锚点厨具-2` 这类 item_name 按 `_`/`-`/空格 分词后各段首字母大写，
     * 前后拼上 `AutoStockStapleGoods` / `Validate`。
     */
    fun buildValidatorNodeName(itemName: String): String {
        val parts = itemName.trim()
            .split('_', '-', ' ')
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""

        val sb = StringBuilder("AutoStockStapleGoods")
        for (part in parts) {
            sb.append(part[0].uppercaseChar())
            if (part.length > 1) sb.append(part.substring(1))
        }
        sb.append("Validate")
        return sb.toString()
    }

    /**
     * 上游 action.go:301 `parseValidatorExpression`。
     *
     * 表达式形如 `{Limit} > {AutoStockStapleGoodsCountValidate}`：
     *  - 阈值取**第一个**整数（`-?\d+`）
     *  - 计数节点取**第一个** `{...}` 占位符
     *
     * 注意上游只判「至少有一个占位符」（`len(matches) != 2` 仅在无匹配时成立），
     * 多个占位符时取第一个，不报错。
     */
    fun parseValidatorExpression(expression: String): Pair<Int, String> {
        val thresholdToken = FIRST_INTEGER.find(expression)?.value
            ?: throw IllegalArgumentException("expression \"$expression\" does not contain integer threshold")
        val threshold = thresholdToken.toIntOrNull()
            ?: throw IllegalArgumentException("parse threshold failed: $thresholdToken")

        val placeholder = NODE_PLACEHOLDER.find(expression)?.groupValues?.get(1)?.trim()
            ?: throw IllegalArgumentException("expression \"$expression\" does not contain exactly one node placeholder")
        if (placeholder.isEmpty()) {
            throw IllegalArgumentException("expression \"$expression\" contains empty node placeholder")
        }
        return threshold to placeholder
    }

    /** 解析结果：阈值 + 计数节点名 + 原始表达式（日志用）。 */
    data class ValidatorSpec(val threshold: Int, val countNode: String, val expression: String)

    /**
     * 上游 action.go:284 `resolveValidatorSpec` 的纯部分：
     * 从校验节点的定义 JSON 树里取 `recognition.param.custom_recognition_param.expression` 再解析。
     */
    fun resolveValidatorSpec(nodeTree: Any?): ValidatorSpec? {
        val node = nodeTree as? Map<*, *> ?: return null
        val expression = Seq.of(node, "recognition", "param", "custom_recognition_param", "expression")
            ?.let { it as? String }
            ?.trim()
        if (expression.isNullOrEmpty()) return null

        val (threshold, countNode) = parseValidatorExpression(expression)
        return ValidatorSpec(threshold, countNode, expression)
    }

    /** 上游 action.go:145 `buildQuantityControlOverride`。 */
    fun buildQuantityControlOverride(slidingNode: String, target: Int): Map<String, Any?> = mapOf(
        slidingNode to mapOf(
            "enabled" to (target > 0),
            "attach" to mapOf("TargetQuantity" to target),
        ),
    )

    /** 上游 action.go:326 `runCountRecognition` 末尾步：从 OCR 文本里取第一个整数。 */
    fun firstIntegerOfText(text: String): Int? =
        FIRST_INTEGER.find(text)?.value?.toIntOrNull()

    /**
     * 上游 action.go:346 `findFirstOCRText`。
     *
     * 优先节点自身（含子树）的 OCR 文本；取不到时退回「包围盒与该节点相同的子节点」——
     * 组合识别（And/Or）的 `Results` 为空、`box_index` 已经把最终框定在某个子节点上，
     * 所以用 box 相等来选中它。
     */
    fun findFirstOcrText(detail: BetterSlidingOcr.Detail?): String? {
        if (detail == null) return null
        detail.text?.takeIf { it.isNotEmpty() }?.let { return it }

        val box = detail.box ?: return null
        for (child in detail.children) {
            if (child.box == box) {
                child.text?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private val FIRST_INTEGER = Regex("""-?\d+""")
    private val NODE_PLACEHOLDER = Regex("""\{([^{}]+)\}""")

    /** 按 key 序列下钻，任一层不是对象就返回 null。 */
    private object Seq {
        fun of(root: Any?, vararg keys: String): Any? {
            var cur: Any? = root
            for (key in keys) {
                cur = (cur as? Map<*, *>)?.get(key) ?: return null
            }
            return cur
        }
    }
}
