package com.aliothmoon.maafw.remote

import java.util.Locale

/**
 * 会客室·交流倒计时相关的纯逻辑（对齐上游
 * `agent/go-service/dijiangrewards/reception_room.go`，整包 328 行）。
 *
 * 上游有两个 custom recognition：
 *  - `ReceptionRoomExchangeCountdownWithinThresholdRecognition`：OCR 当前交流剩余时间，
 *    判断是否已进入阈值（默认 5 分钟）内；可顺带刷新「等待中」提示。
 *  - `ReceptionRoomWaitExchangeKeepAliveDueRecognition`：长时间等待期间每 20 分钟判一次
 *    「该退出重进会客室保活」；若两次判定间距超过 2 分钟（会话被打断），保活计时重新开始。
 *
 * 两者都先 `RunRecognition(ReceptionRoomExchangeCountdownText)` 取一段 OCR 文本，
 * 再用 [COUNTDOWN_PATTERN] 解析成秒数。取文本/跑识别的编排在 MaaRunner，这里只放可单测的
 * 解析与判定。风格照 [MapNaviParam]：吃通用 JSON 树，返回结果或失败原因。
 *
 * 别小看 [previousNonSpaceRune]：文本 `1:0:30`（时:分:秒，分只有一位）匹配不上三段式，
 * 正则会退回匹配 `0:30`——即把「0 分 30 秒」当成「30 秒」。上游用「匹配前面紧邻的
 * 非空白字符是不是冒号」来丢掉这种尾巴，移植时不能当冗余删掉。
 */
object ReceptionRoomSupport {

    /** 取倒计时文本的 OCR 节点（上游 `countdownTextNode`）。 */
    const val COUNTDOWN_TEXT_NODE = "ReceptionRoomExchangeCountdownText"

    /** 上游 `defaultThresholdMinutes`。 */
    const val DEFAULT_THRESHOLD_MINUTES = 5

    /** 上游 `waitingReportInterval`：两次「等待中」提示的最小间隔。 */
    const val WAITING_REPORT_INTERVAL_MILLIS = 10_000L

    /** 上游 `keepAliveInterval`：两次保活之间的间隔。 */
    const val KEEP_ALIVE_INTERVAL_MILLIS = 20 * 60_000L

    /** 上游 `keepAliveSessionGap`：判定间隔超过它视为会话中断，保活计时重来。 */
    const val KEEP_ALIVE_SESSION_GAP_MILLIS = 2 * 60_000L

    /**
     * 上游 `countdownPattern`：`\b(\d{1,2})\s*[:：]\s*(\d{2})(?:\s*[:：]\s*(\d{2}))?\b`。
     * 三个捕获组分别是 时/分（1~2 位）、分/秒（2 位）、可选的秒（2 位）。
     */
    private val COUNTDOWN_PATTERN =
        Regex("""\b(\d{1,2})\s*[:：]\s*(\d{2})(?:\s*[:：]\s*(\d{2}))?\b""")

    /** 上游 `countdownParams`。 */
    data class CountdownParams(
        val thresholdMinutes: Int = DEFAULT_THRESHOLD_MINUTES,
        val reportWaiting: Boolean = false,
    )

    sealed interface ParamsOutcome {
        data class Ok(val params: CountdownParams) : ParamsOutcome
        data class Invalid(val reason: String) : ParamsOutcome
    }

    sealed interface SecondsOutcome {
        data class Ok(val seconds: Int) : SecondsOutcome
        data class Invalid(val reason: String) : SecondsOutcome
    }

    // ───────────────────────── 参数解析 ─────────────────────────

    /**
     * 上游 `parseCountdownParams`：空串走默认值；JSON 非法、类型不对、
     * `threshold_minutes` 非正数都算整节点失败。
     */
    fun parseCountdownParams(raw: String?): ParamsOutcome {
        if (raw.isNullOrBlank()) return ParamsOutcome.Ok(CountdownParams())
        val tree = MaaJsonTree.parse(raw)
            ?: return ParamsOutcome.Invalid("custom_recognition_param 不是合法 JSON")
        return parseCountdownParams(tree)
    }

    fun parseCountdownParams(tree: Any?): ParamsOutcome {
        val root = tree as? Map<*, *>
            ?: return ParamsOutcome.Invalid("custom_recognition_param 不是对象")

        var thresholdMinutes = DEFAULT_THRESHOLD_MINUTES
        val thresholdNode = root["threshold_minutes"]
        if (thresholdNode != null) {
            val value = (thresholdNode as? Number)?.toInt()
                ?: return ParamsOutcome.Invalid("threshold_minutes 不是数字")
            if (value <= 0) return ParamsOutcome.Invalid("threshold_minutes must be positive")
            thresholdMinutes = value
        }

        var reportWaiting = false
        val reportNode = root["report_waiting"]
        if (reportNode != null) {
            reportWaiting = reportNode as? Boolean
                ?: return ParamsOutcome.Invalid("report_waiting 不是布尔")
        }

        return ParamsOutcome.Ok(CountdownParams(thresholdMinutes, reportWaiting))
    }

    // ───────────────────────── 倒计时解析 ─────────────────────────

    /**
     * 上游 `parseCountdownSeconds`：把 OCR 文本解析成秒数。
     * 恰好两段按 `分:秒`，三段按 `时:分:秒`；`分`/`秒` 超过 59 判失败。
     */
    fun parseCountdownSeconds(text: String?): SecondsOutcome {
        val cleaned = text?.trim().orEmpty()
        if (cleaned.isEmpty()) return SecondsOutcome.Invalid("countdown text is empty")

        val match = findCountdownMatch(cleaned)
            ?: return SecondsOutcome.Invalid("countdown text \"$cleaned\" contains no time value")

        val first = match[1].toIntOrNull()
            ?: return SecondsOutcome.Invalid("first value is not a number: ${match[1]}")
        val second = match[2].toIntOrNull()
            ?: return SecondsOutcome.Invalid("second value is not a number: ${match[2]}")
        if (second >= 60) return SecondsOutcome.Invalid("invalid minute/second value $second")

        val thirdRaw = match[3]
        if (thirdRaw.isEmpty()) return SecondsOutcome.Ok(first * 60 + second)

        val third = thirdRaw.toIntOrNull()
            ?: return SecondsOutcome.Invalid("third value is not a number: $thirdRaw")
        if (third >= 60) return SecondsOutcome.Invalid("invalid second value $third")

        return SecondsOutcome.Ok(first * 3600 + second * 60 + third)
    }

    /**
     * 上游 `findCountdownMatch`：返回第一个「前面不是冒号」的匹配。
     * 返回 `[完整匹配, 组1, 组2, 组3]`，未参与的可选组为空串；无匹配返回 null。
     */
    fun findCountdownMatch(text: String): List<String>? {
        for (match in COUNTDOWN_PATTERN.findAll(text)) {
            val prev = previousNonSpaceRune(text.substring(0, match.range.first))
            if (prev == ':'.code || prev == '：'.code) continue

            return listOf(
                match.value,
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3],
            )
        }
        return null
    }

    /**
     * 上游 `previousNonSpaceRune`：取文本里最后一个非空白字符，返回其码点；
     * 全空白或空串返回 null（对齐 Go 的 `rune`/零值语义）。
     */
    fun previousNonSpaceRune(text: String): Int? {
        var end = text.length
        while (end > 0) {
            val codePoint = text.codePointBefore(end)
            end -= Character.charCount(codePoint)
            if (!isGoSpace(codePoint)) return codePoint
        }
        return null
    }

    private fun isGoSpace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    /**
     * 上游 `formatCountdown`：秒数格式化成 `mm:ss` 或 `hh:mm:ss`，负数按 0。
     */
    fun formatCountdown(seconds: Int): String {
        val value = if (seconds < 0) 0 else seconds
        val hours = value / 3600
        val minutes = (value % 3600) / 60
        val remainingSeconds = value % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds)
        }
    }

    /**
     * 上游 `bestOCRText`：优先 `best`，其次 `filtered`、`all`。
     *
     * MaaFramework 的识别详情 JSON 形态为 `{all,best,filtered}`（见 [AutoEcoFarmNearest]），
     * 有些形态会再包一层 `results`。这里吃已解析的通用树：带 `text` 键的对象视为一处 OCR 结果。
     * 若三个键都不在（形状意外），退回递归取第一处 `text`——宁可多一层兜底，也不要因为
     * 猜错路径让识别永远不命中（本项目吃过「stub 静默成功」的亏）。
     */
    fun bestOcrText(detailTree: Any?): String? {
        val root = detailTree as? Map<*, *> ?: return null
        val container = (root["results"] as? Map<*, *>) ?: root
        if (container.containsKey("best")) {
            textOf(container["best"])?.let { return it }
        }
        (container["filtered"] as? List<*>)?.forEach { node ->
            textOf(node)?.let { return it }
        }
        (container["all"] as? List<*>)?.forEach { node ->
            textOf(node)?.let { return it }
        }
        return firstTextDeep(root)
    }

    private fun textOf(node: Any?): String? {
        val map = node as? Map<*, *> ?: return null
        if (!map.containsKey("text")) return null
        return (map["text"] as? String)?.trim()
    }

    /** DFS 取第一处非空 `text`（仅作形状意外时的兜底）。 */
    private fun firstTextDeep(node: Any?): String? {
        when (node) {
            is Map<*, *> -> {
                (node["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                for ((key, value) in node) {
                    if (key == "text") continue
                    firstTextDeep(value)?.let { return it }
                }
            }

            is List<*> -> for (item in node) {
                firstTextDeep(item)?.let { return it }
            }

            else -> {}
        }
        return null
    }

    // ───────────────────────── 节流状态机 ─────────────────────────

    /**
     * 上游 `ExchangeCountdownWithinThresholdRecognition.shouldReportWaiting`：
     * 首次或距上次提示 ≥ [WAITING_REPORT_INTERVAL_MILLIS] 才放行。
     */
    class WaitingReportGate {
        private var lastReportAtMillis: Long? = null

        @Synchronized
        fun shouldReportWaiting(nowMillis: Long): Boolean {
            val last = lastReportAtMillis
            if (last == null || nowMillis - last >= WAITING_REPORT_INTERVAL_MILLIS) {
                lastReportAtMillis = nowMillis
                return true
            }
            return false
        }
    }

    /**
     * 上游 `ExchangeKeepAliveDueRecognition.shouldKeepAlive`：
     *  - 首次判定或距上次判定 > [KEEP_ALIVE_SESSION_GAP_MILLIS]（会话中断）时，
     *    重置保活计时点；
     *  - 距上次保活 < [KEEP_ALIVE_INTERVAL_MILLIS] 不放行；
     *  - 放行时同时把保活计时点推到当前。
     */
    class KeepAliveGate {
        private var lastCheckAtMillis: Long? = null
        private var lastKeepAliveAtMillis: Long? = null

        @Synchronized
        fun shouldKeepAlive(nowMillis: Long): Boolean {
            val lastCheck = lastCheckAtMillis
            if (lastCheck == null || nowMillis - lastCheck > KEEP_ALIVE_SESSION_GAP_MILLIS) {
                lastKeepAliveAtMillis = nowMillis
            }
            lastCheckAtMillis = nowMillis

            val lastKeepAlive = lastKeepAliveAtMillis
            if (lastKeepAlive == null || nowMillis - lastKeepAlive < KEEP_ALIVE_INTERVAL_MILLIS) {
                return false
            }

            lastKeepAliveAtMillis = nowMillis
            return true
        }
    }
}
