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
}
