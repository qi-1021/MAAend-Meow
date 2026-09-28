package com.aliothmoon.maafw.diagnostics

/**
 * [RunDiagnostics] 的**纯逻辑**策略层：只做「留哪些、删哪些」的计算，不碰文件系统。
 *
 * 抽出来的原因很直接：真机上跑一天，报告目录里就是几十个文件、几百 MiB 的分布，
 * 「到底删了哪几份、留下哪几份」不该靠读代码猜。这里没有 Android、没有 IO，
 * 可以进 [scripts/verify_pure_logic.sh] 本机跑断言。
 *
 * 上限数字的由来（用户要求「别浪费储存空间」）：
 *  - 单文件 8 MiB：一次长任务的事件量通常远不到（几千行 × 每行几百字节 ≈ 几 MiB），
 *    8 MiB 足够装下一次完整的排查；再大就是 MaaFramework 自己刷屏，报告里也读不出东西了。
 *  - 保留 10 份：够覆盖「今天连着调了十来次」的来回比对，再多基本不会回头看。
 *  - 目录 64 MiB：10 × 8 = 80 MiB 是各文件都写满的最坏情况，64 MiB 是最后一道闸；
 *    实际运行里只要目录一超就把最旧的按时间删到线内，磁盘占用有硬顶。
 */
object RunDiagnosticsPolicy {

    /** 单份报告文件上限，写满即停并落截断标记。 */
    const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

    /** report 目录里保留的最新报告份数。 */
    const val MAX_FILES: Int = 10

    /** 整个 report 目录的硬上限，超出按时间从旧到新删。 */
    const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024

    /** 报告子目录名（挂在 `<pkg>/files/log/` 下）。 */
    const val REPORT_DIR: String = "report"

    /** 报告文件名前缀，同时也是「哪些文件算报告」的判据。 */
    const val FILE_PREFIX: String = "run-"

    /** 报告文件名后缀。 */
    const val FILE_SUFFIX: String = ".jsonl"

    /** 目录里一份报告的元信息；[lastModified] 是毫秒时间戳。 */
    data class ReportFile(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /**
     * 当前文件加上即将写入的一行会不会越过单文件上限。
     *
     * 用 `>` 而非 `>=`：正好写满上限不算截断，避免边界上白丢一行。
     */
    fun shouldTruncate(
        currentBytes: Long,
        incomingBytes: Long,
        limitBytes: Long = MAX_FILE_BYTES,
    ): Boolean = currentBytes + incomingBytes > limitBytes

    /** 按「旧到新」排序：时间是主键，时间相同再按文件名定序，保证删的顺序稳定可测。 */
    fun oldestFirst(files: List<ReportFile>): List<ReportFile> =
        files.sortedWith(compareBy({ it.lastModified }, { it.name }))

    /** 超出份数上限时要删的（最旧的先删），未超返回空。 */
    fun selectOverflowByCount(
        files: List<ReportFile>,
        maxFiles: Int = MAX_FILES,
    ): List<ReportFile> {
        if (files.size <= maxFiles) return emptyList()
        return oldestFirst(files).take(files.size - maxFiles)
    }

    /** 目录总量超上限时，从最旧开始删到线内。 */
    fun selectOverflowByTotal(
        files: List<ReportFile>,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): List<ReportFile> {
        var total = files.sumOf { it.sizeBytes }
        if (total <= maxTotalBytes) return emptyList()
        val toDelete = mutableListOf<ReportFile>()
        for (file in oldestFirst(files)) {
            if (total <= maxTotalBytes) break
            toDelete += file
            total -= file.sizeBytes
        }
        return toDelete
    }

    /**
     * 两条上限综合后的待删集合。
     *
     * 先按份数删，再在**剩下的文件**上算总量：反过来的话会把「按份数该删的」
     * 又从总量里扣一遍，可能多删。返回去重后的列表，顺序仍是旧到新。
     */
    fun selectDeletions(
        files: List<ReportFile>,
        maxFiles: Int = MAX_FILES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): List<ReportFile> {
        val byCount = selectOverflowByCount(files, maxFiles)
        val removed = byCount.toHashSet()
        val remaining = files.filterNot { it in removed }
        return byCount + selectOverflowByTotal(remaining, maxTotalBytes)
    }
}
