package com.aliothmoon.maafw.remote

/**
 * `autoEcoFarmInterruptibleSleep` 的纯逻辑。
 *
 * 上游对应 `autoecofarm/interruptiblesleep.go`（95 行）：把一段等待切成 250ms 的小片，
 * 期间按 `reportIntervalMs` 报一次 `mm:ss` 倒计时，收到停止信号就提前结束。
 *
 * 这里只抽出「分片计划」与「倒计时格式化」——真正 sleep 与检查停止信号在 MaaRunner。
 * 分片计划而不是循环写进胶水层，是为了把「报告阈值」这种差一判断钉进测试：
 * 上游是 `remaining <= nextReportRemaining` 时报告，再 `nextReport -= interval`。
 */
object AutoEcoFarmSleep {

    /** 上游 `interruptibleSleepChunkMs`。 */
    const val CHUNK_MS = 250L

    /** 上游未指定报告间隔时的默认值。 */
    const val DEFAULT_REPORT_INTERVAL_MS = 5000L

    /** 上游 `interruptibleSleepParams`。 */
    data class Param(val durationMs: Long, val reportIntervalMs: Long)

    /** 一个分片：睡 `chunkMs` 毫秒；`reportRemainingMs` 非空表示睡前先报这个倒计时。 */
    data class Segment(val chunkMs: Long, val reportRemainingMs: Long?)

    /**
     * 解析 `custom_action_param`。语义同上游 `json.Unmarshal`：
     * 空串/非法 JSON/字段类型不对 → null；缺失或 null → 0。
     */
    fun parseParam(raw: String?): Param? {
        if (raw.isNullOrBlank()) return null
        val map = MaaJsonTree.parse(raw) as? Map<*, *> ?: return null
        val duration = longOf(map, "durationMs") ?: return null
        val report = longOf(map, "reportIntervalMs") ?: return null
        return Param(duration, report)
    }

    /** 上游主循环的分片计划；`durationMs <= 0` 返回空。 */
    fun plan(durationMs: Long, reportIntervalMs: Long): List<Segment> {
        if (durationMs <= 0) return emptyList()
        val interval = if (reportIntervalMs <= 0) DEFAULT_REPORT_INTERVAL_MS else reportIntervalMs

        var remaining = durationMs
        var nextReportRemaining = remaining - interval
        val segments = mutableListOf<Segment>()
        while (remaining > 0) {
            val report = if (remaining <= nextReportRemaining) {
                val at = remaining
                nextReportRemaining -= interval
                at
            } else {
                null
            }
            val chunk = minOf(CHUNK_MS, remaining)
            segments += Segment(chunk, report)
            remaining -= chunk
        }
        return segments
    }

    /** 上游 `fmt.Sprintf("%02d:%02d", m, s)`。 */
    fun formatRemaining(remainingMs: Long): String {
        val minutes = remainingMs / 60000
        val seconds = (remainingMs % 60000) / 1000
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun longOf(map: Map<*, *>, key: String): Long? {
        if (!map.containsKey(key)) return 0L
        val value = map[key] ?: return 0L
        return (value as? Number)?.toLong() ?: return null
    }
}
