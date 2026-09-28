package com.aliothmoon.maafw.diagnostics

/**
 * 货卡 OCR 探针**失败帧导出**的纯逻辑策略层：只算「该不该导、导到哪」，不碰文件系统、不碰 native。
 *
 * 背景（真机怪问题）：同一轮运行里谷地市场能正常 OCR 出 6 个货组，武陵市场连续三次尝试
 * （跨 3 秒、每次 `MaaControllerPostScreencap` 重新取帧）都只回三条一模一样的乱码 `2そ22`，
 * scan 出 0 个候选；而失败后框架自己抓的 on_error 截图里武陵市场页完全正常。
 * 需要把「探针实际送去 OCR 的那张原图」导出来，才能判定我们的帧和框架的帧是不是同一张。
 *
 * 抽出来的原因和 [RunDiagnosticsPolicy] 一样：导出的路径与触发判据一旦算错，
 * 要么在 release 里凭空写文件、要么真机复现时证据没落下来，都是事后无法补救的。
 * 这里没有 Android、没有 IO、没有 JNA，可以进 [scripts/verify_pure_logic.sh] 本机跑断言。
 */
object GoodsProbeDumpPolicy {

    /** 导出子目录名（挂在 `RunDiagnostics` 的 logDir，即 `<pkg>/files/log/` 下）。 */
    const val DUMP_DIR: String = "probe_dump"

    /** 文件名前缀：一眼能看出是货卡探针的导出。 */
    const val FILE_PREFIX: String = "goods-"

    /** 文件名后缀：原图按 PNG 编码落盘，便于直接叠 ROI 比对。 */
    const val FILE_SUFFIX: String = ".png"

    /**
     * `probe_dump/` 保留的最新帧数。
     *
     * 依据：这个目录只在「三次尝试全 0 候选」时才写一张，触发本身就少见；一次调试
     * 往往要横跨几个 region / 几轮复现来回比对，10 份容易不够看，20 份能覆盖
     * 「一天连着调十来次」的历史，再多基本不会回头翻。
     */
    const val MAX_FILES: Int = 20

    /**
     * `probe_dump/` 目录硬上限，超出按时间从旧到新删。
     *
     * 依据：一帧全分辨率 PNG 截图按 0.5~2 MiB 估（游戏界面渐变多、压缩比有限），
     * 20 份最坏也就 ~40 MiB；这里取 32 MiB 作为最后一道闸，即便单帧异常大
     * 也不会让这个目录无限膨胀。报告目录的硬顶是 64 MiB，探针图只留一半额度，
     * 两者相加仍是有界的小体量，符合「不要浪费储存空间」。
     */
    const val MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024

    /**
     * 要不要导这一帧。
     *
     * 两个条件缺一不可：诊断必须真的开着（release 下绝不写文件），且本次是
     * 「所有尝试都拿到 0 候选」——只有这种情况才需要留证据去判断是取帧来源不对，
     * 还是识别本身读空。
     */
    fun shouldDump(diagnosticsEnabled: Boolean, allAttemptsZeroCandidates: Boolean): Boolean =
        diagnosticsEnabled && allAttemptsZeroCandidates

    /**
     * 把 region 收敛成安全的文件名片段：只留字母/数字/`_`/`-`，其余一律换成 `_`。
     *
     * region 来自节点名（如 Wuling / ValleyIV），正常都是安全字符；这里仍做收敛，
     * 是为了防住「节点名被配置改坏、拼出 `../` 或分隔符」时把文件写到目录之外。
     * 全部被换掉时退回 `unknown`，避免出现空片段。
     */
    fun sanitizeRegion(region: String): String {
        val cleaned = region.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        return cleaned.ifEmpty { "unknown" }
    }

    /**
     * 导出文件的**相对路径**（相对 logDir），形如
     * `probe_dump/goods-<region>-<时间戳>.png`。
     *
     * 只给相对路径：绝对根目录由 [RunDiagnostics] 持有，本策略层不接触文件系统。
     */
    fun dumpRelativePath(region: String, timestampMs: Long): String =
        "$DUMP_DIR/$FILE_PREFIX${sanitizeRegion(region)}-$timestampMs$FILE_SUFFIX"

    /**
     * 这个文件名是不是**本策略产出的探针帧**：`goods-` 前缀 + `.png` 后缀。
     *
     * 这是清理时唯一的「可删」判据，且只对 `probe_dump/` 目录里的文件生效。
     * 非 PNG（半截临时文件、用户手放的说明等）一律不算，既不计数也不删。
     */
    fun isDumpName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    /**
     * 超出份数/总量上限时要删的探针帧（旧到新）。
     *
     * 先按 [isDumpName] 过滤，再复用 [RunDiagnosticsPolicy.selectDeletions] 的同一套
     * 形制（先份数后剩余总量、时间为主键），保证「怎么删」与报告目录完全一致。
     * 过滤这一步很关键：即便调用方误把混合目录的文件喂进来，非探针帧也绝不会被选中。
     */
    fun selectDeletions(
        files: List<RunDiagnosticsPolicy.ReportFile>,
    ): List<RunDiagnosticsPolicy.ReportFile> =
        RunDiagnosticsPolicy.selectDeletions(
            files = files.filter { isDumpName(it.name) },
            maxFiles = MAX_FILES,
            maxTotalBytes = MAX_TOTAL_BYTES,
        )
}
