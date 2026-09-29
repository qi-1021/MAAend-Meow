package com.aliothmoon.maafw.remote

/**
 * 列表「是否已到底」判定的纯逻辑层（[ListCompleteSupport]）。
 *
 * 对应上游 `upstream/maaend/agent/go-service/common/listcomplete/recognition.go:24-184`
 * 的 `ListCompleteRecognition`：**靠画面比对判底**，不是靠调用计数。
 *
 * ## 上游语义（带行号）
 *
 *  - `recognition.go:90-129` 读调用节点 `attach.ready`；为假时把节点原生 roi 从当前帧裁出来、
 *    `OverrideImage` 写进运行时模板、把 `attach.ready` 置真，**并返回未命中**（首次只是建模板）。
 *  - `recognition.go:131-164` `attach.ready` 为真时对 roi 跑 `TemplateMatch`；相似度
 *    `>= custom_recognition_param.threshold`（默认 0.9）视为画面未变、列表到底，返回命中。
 *  - `recognition.go:166-183` 低于阈值则**重截模板**并返回未命中（画面还在动，继续滑）。
 *  - `recognition.go:186-201` `threshold` 缺省 0.9；显式为 0 也回落到 0.9；`<=0` 或 `>1`
 *    视为非法参数。
 *
 * ## 为什么必须做画面判定
 *
 * 之前移动端把两个识别都写成「第 N 次调用恒真」，IntelArchive 每个页签滑约 4 屏就判到底，
 * 后面的条目（实测 paper 238 条）永远扫不到，导出清单缺项。本文件把「建模板 / 比模板 /
 * 重截模板」的状态机与参数解析钉死，可脱离 Android/JNA 本机回归。
 *
 * ## 与上游的差异
 *
 *  - 上游解析非法 `threshold` 时返回 error（识别失败）。这里为了**不让扫描链断掉**，
 *    非法值回落到默认 0.9，并在 [Params.thresholdRejected] 标出来供调用方记日志。
 *  - 新增可选 `max_attempts`（默认 [DEFAULT_MAX_ATTEMPTS]）：`ready` 后连续判「未到底」
 *    达到该次数就强制返回完成，作为**防死循环的硬上限安全阀**，避免 roi 里有动画时永远
 *    到不了底。上游没有这个兜底。
 *
 * 真机像素处理（PNG 解码、ROI 裁剪、`MaaImageBufferSetRawData`、`OverrideImage`）留在
 * MaaRunner；本文件只做「给定分数 + 阈值 + attach 状态 → 该干什么」的判定，因此无依赖。
 */
object ListCompleteSupport {

    /** 上游 `defaultThreshold`（`recognition.go:17`）。 */
    const val DEFAULT_THRESHOLD = 0.9

    /**
     * `ready` 之后连续未到底的安全上限。真机 IntelArchive 的滑动节点 `max_hit` 是 100，
     * 这里留足余量（更长的列表也不该被这个兜底提前截断），只在 roi 持续变化（动画）时兜底。
     */
    const val DEFAULT_MAX_ATTEMPTS = 200

    /** 上游 `attachReady`（`recognition.go:16`）。 */
    const val READY_KEY = "ready"

    /** 上游 `templateNameFmt = "ListCompleteRecognition/%s.png"`（`recognition.go:18`）。 */
    const val TEMPLATE_NAME_PREFIX = "ListCompleteRecognition"

    /** `cv::TemplateMatchMatchingMethod::TM_CCOEFF_NORMED = 5`。 */
    const val TEMPLATE_MATCH_METHOD = 5

    /** 合成/截图模板不带绿幕掩膜。 */
    const val GREEN_MASK = false

    /** 解析后的识别参数。 */
    data class Params(
        /** 相似度 >= 该值判定到底。 */
        val threshold: Double,
        /** 硬上限安全阀：连续判未到底达到该次数后强制完成。 */
        val maxAttempts: Int,
        /** `threshold` 显式传了非法值（<=0 或 >1）并已回落到默认值。 */
        val thresholdRejected: Boolean = false,
        /** `max_attempts` 显式传了非法值（<=0）并已回落到默认值。 */
        val maxAttemptsRejected: Boolean = false,
    )

    /** 一次识别该采取的动作。 */
    enum class Action {
        /** 还没有模板：截当前 roi 存为运行时模板，返回未命中。 */
        CAPTURE_TEMPLATE,

        /** 有模板但画面变了：重截模板，返回未命中（继续滑）。 */
        RECAPTURE_TEMPLATE,

        /** 画面与模板一致（或触发安全阀）：列表已到底，返回命中。 */
        COMPLETE,
    }

    /**
     * 解析 `custom_recognition_param`。任何缺省/非法输入都回落到默认值，**绝不抛**：
     * 参数读不出来时让识别走默认阈值继续扫描，比直接把链断掉好。
     */
    fun parseParams(raw: String?): Params {
        val root = MaaJsonTree.parse(raw) as? Map<*, *>
            ?: return Params(DEFAULT_THRESHOLD, DEFAULT_MAX_ATTEMPTS)

        var thresholdRejected = false
        val rawThreshold = numberOf(root["threshold"])
        val threshold = when {
            rawThreshold == null -> {
                // 键存在但值非 null 且不是数字（如字符串）视为非法；缺失/显式 null 视为缺省。
                if (root.containsKey("threshold") && root["threshold"] != null) thresholdRejected = true
                DEFAULT_THRESHOLD
            }

            rawThreshold == 0.0 -> DEFAULT_THRESHOLD // 上游：显式 0 也回落到默认
            rawThreshold > 0.0 && rawThreshold <= 1.0 -> rawThreshold
            else -> {
                thresholdRejected = true
                DEFAULT_THRESHOLD
            }
        }

        var maxAttemptsRejected = false
        val rawMax = intOf(root["max_attempts"])
        val maxAttempts = when {
            rawMax == null -> {
                if (root.containsKey("max_attempts") && root["max_attempts"] != null) maxAttemptsRejected = true
                DEFAULT_MAX_ATTEMPTS
            }

            rawMax > 0 -> rawMax
            else -> {
                maxAttemptsRejected = true
                DEFAULT_MAX_ATTEMPTS
            }
        }

        return Params(threshold, maxAttempts, thresholdRejected, maxAttemptsRejected)
    }

    /** 运行时模板名：`ListCompleteRecognition/<node>.png`。 */
    fun templateName(nodeName: String): String = "$TEMPLATE_NAME_PREFIX/$nodeName.png"

    /**
     * 把节点原生 roi 规范化为图像范围内的 `[x, y, w, h]`。
     *
     *  - [roi] 为空 / 长度不足 4 / 宽高非正 → 视为全屏 `[0, 0, imgWidth, imgHeight]`；
     *  - 越界时按图像边界裁剪（等价上游 `resolveROI` 的 `Intersect`）；
     *  - 裁剪后为空、或图像尺寸非正 → 返回 null（调用方据此放弃本次判定）。
     */
    fun normalizeRoi(roi: IntArray?, imgWidth: Int, imgHeight: Int): IntArray? {
        if (imgWidth <= 0 || imgHeight <= 0) return null
        val full = intArrayOf(0, 0, imgWidth, imgHeight)
        if (roi == null || roi.size < 4 || roi[2] <= 0 || roi[3] <= 0) return full

        val left = roi[0].coerceIn(0, imgWidth)
        val top = roi[1].coerceIn(0, imgHeight)
        val right = (roi[0] + roi[2]).coerceIn(0, imgWidth)
        val bottom = (roi[1] + roi[3]).coerceIn(0, imgHeight)
        if (right <= left || bottom <= top) return null
        return intArrayOf(left, top, right - left, bottom - top)
    }

    /** 从调用节点定义 JSON 里读 `attach`（键保持插入顺序）。解析失败/缺失返回空表，不抛。 */
    fun attachMap(nodeJson: String?): Map<String, Any?> {
        val root = MaaJsonTree.parse(nodeJson) as? Map<*, *> ?: return emptyMap()
        val attach = root["attach"] as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>(attach.size)
        for ((key, value) in attach) {
            if (key is String) out[key] = value
        }
        return out
    }

    /** `attach.ready` 是否为真；缺失、类型不符、节点 JSON 取不到都算未就绪。 */
    fun isReady(nodeJson: String?): Boolean = attachMap(nodeJson)[READY_KEY] == true

    /**
     * 从框架识别 detail JSON 里取 `best.score`。TemplateMatch / NeuralNetworkClassify 都是
     * `{all, filtered, best}` 形状；取不到、类型不符返回 null，不抛。
     */
    fun bestTemplateScore(detailJson: String?): Double? {
        val root = MaaJsonTree.parse(detailJson) as? Map<*, *> ?: return null
        val best = root["best"] as? Map<*, *> ?: return null
        return numberOf(best["score"])
    }

    /**
     * 构造临时 `TemplateMatch` 节点的 pipeline_override。
     *
     * **所有可变键一律显式写出**（template / method / green_mask / threshold / roi）：
     * 这个临时节点名是跨调用共用的，`MaaContextOverridePipeline` 会保留上次写入的定义，
     * 少写一个键就会继承上一次的值（见 [OcrProbeSupport.buildOverride] 的血泪教训）。
     * roi 无效时显式写 `[0,0,0,0]`（框架按全屏处理），绝不省略。
     */
    fun buildTemplateMatchOverride(
        nodeName: String,
        templateName: String,
        threshold: Double,
        roi: IntArray?,
        method: Int = TEMPLATE_MATCH_METHOD,
        greenMask: Boolean = GREEN_MASK,
    ): String {
        val normalizedRoi = if (roi != null && roi.size >= 4) {
            intArrayOf(roi[0], roi[1], roi[2], roi[3])
        } else {
            intArrayOf(0, 0, 0, 0)
        }
        val param = linkedMapOf<String, Any?>(
            "template" to listOf(templateName),
            "method" to method,
            "green_mask" to greenMask,
            "threshold" to threshold,
            "roi" to normalizedRoi.toList(),
        )
        val node = linkedMapOf<String, Any?>(
            "recognition" to linkedMapOf<String, Any?>(
                "type" to "TemplateMatch",
                "param" to param,
            ),
            // 只认不按：临时节点不触发任何动作
            "action" to linkedMapOf<String, Any?>("type" to "DoNothing"),
        )
        return JsonTree.toJson(mapOf(nodeName to node))
    }

    /**
     * 构造把 `attach.ready` 写回调用节点的 override。
     *
     * 先合并调用节点**现有**的 attach，再覆盖 ready——与上游 `saveReady`
     * （`recognition.go:296-318`）一致，避免把其它 attach 键冲掉。
     */
    fun buildReadyOverride(nodeName: String, existingNodeJson: String?, ready: Boolean): String {
        val merged = LinkedHashMap<String, Any?>(attachMap(existingNodeJson))
        merged[READY_KEY] = ready
        val node = linkedMapOf<String, Any?>("attach" to merged)
        return JsonTree.toJson(mapOf(nodeName to node))
    }

    private fun numberOf(value: Any?): Double? = when (value) {
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is Short -> value.toDouble()
        is Byte -> value.toDouble()
        is Double -> value
        is Float -> value.toDouble()
        else -> null
    }

    private fun intOf(value: Any?): Int? = when (value) {
        is Long -> value.toInt()
        is Int -> value
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Double -> if (value.isFinite() && value == Math.floor(value)) value.toInt() else null
        is Float -> {
            val d = value.toDouble()
            if (d.isFinite() && d == Math.floor(d)) d.toInt() else null
        }

        else -> null
    }

    /**
     * 按节点名保存「连续未到底」计数器，驱动 [Action] 状态机。
     *
     * 调用线程是框架单工作线程，MaaRunner 侧再套一层锁即可，不必内部同步。
     */
    class Session {
        private val attempts = HashMap<String, Int>()

        /** 当前 [node] 已连续判未到底的次数（完成或首轮重建后清零）。 */
        fun attemptsFor(node: String): Int = attempts[node] ?: 0

        /** 清空所有节点的计数（换资源/重建 session 时调）。 */
        fun reset() = attempts.clear()

        /** 只清 [node] 的计数。 */
        fun reset(node: String) {
            attempts.remove(node)
        }

        /**
         * 核心判定。
         *
         * @param ready 调用节点当前 `attach.ready`（来自框架，非本对象记忆）。
         * @param score 本轮 TemplateMatch 的 `best.score`；识别失败/无模板时传 null。
         */
        fun decide(
            node: String,
            ready: Boolean,
            score: Double?,
            threshold: Double,
            maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        ): Action {
            val cap = if (maxAttempts > 0) maxAttempts else DEFAULT_MAX_ATTEMPTS

            // 首轮：只建模板，绝不判定到底；计数从 0 起算（只统计「比过但未到底」的次数）。
            if (!ready) {
                attempts[node] = 0
                return Action.CAPTURE_TEMPLATE
            }

            val count = (attempts[node] ?: 0) + 1
            attempts[node] = count

            // 判定主逻辑：画面（相似度）决定。
            if (score != null && score >= threshold) {
                attempts.remove(node)
                return Action.COMPLETE
            }
            // 安全阀：画面持续变化（动画/噪声）时兜底判完成，避免死循环。
            if (count >= cap) {
                attempts[node] = 0
                return Action.COMPLETE
            }
            return Action.RECAPTURE_TEMPLATE
        }
    }
}
