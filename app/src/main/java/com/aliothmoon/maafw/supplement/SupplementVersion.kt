package com.aliothmoon.maafw.supplement

/**
 * 补充包版本号：解析、比较、以及 App 侧要求的最低版本。
 *
 * 本文件是**纯逻辑**（不依赖 Android），可脱离设备单测，纳入 scripts/verify_pure_logic.sh。
 *
 * 两个概念不要混：
 *  - 包的**声明版本**（[SupplementPack.Pack.version]）：清单里随内容一起 pin 住的版本，
 *    安装成功时落盘；用于回答「我装的到底是哪一份」。
 *  - App 的**要求最低版本**（[requiredFor]）：当前 App 认为「还能用」的下限，随 App 发布，
 *    与下载渠道/镜像无关。已装版本低于它 → 界面明确提示（不阻止使用，只告诉用户该重下）。
 *
 * 比较口径：版本串按 `.` 分段，能解析成数字的段按**数值**比较（`10 > 9`，不是字典序），
 * 否则按字符串比较；段数不等时缺的段当 0（`2026.9` == `2026.9.0`）。
 * 因此日期串（`2026.9.28`）与语义化串（`1.4.0`）都能用。
 */
object SupplementVersion {

    /**
     * App 要求的最低补充包版本，键为包 id；空串 = 对该包无要求。
     *
     * 写死在这里而不是放清单：清单是**可替换**的（镜像/回退都可能拿到不同 revision），
     * 而「App 需要什么才能正确工作」是 App 自己的契约，不能由被下载的内容来决定。
     */
    val REQUIRED: Map<String, String> = mapOf(
        "map-locate" to "2026.9.28",
        "map-navmesh" to "2026.9.28",
        "detect" to "2026.9.28",
    )

    /** 取某个包要求的最低版本；未收录的包返回空串（视为无要求）。 */
    fun requiredFor(packId: String): String = REQUIRED[packId].orEmpty()

    /**
     * 比较两版本串：`a < b` 返回负，相等返回 0，`a > b` 返回正。
     *
     * 空白串按「未声明」处理，视为小于任何非空版本（但 [isBelow] 会先把空白挡掉）。
     */
    fun compare(a: String, b: String): Int {
        val pa = a.trim().split('.')
        val pb = b.trim().split('.')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val sa = pa.getOrNull(i) ?: "0"
            val sb = pb.getOrNull(i) ?: "0"
            val na = sa.toLongOrNull()
            val nb = sb.toLongOrNull()
            val c = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
            if (c != 0) return c
        }
        return 0
    }

    /** [installed] 是否低于 [required]；任一方未声明（null/空白）都返回 false（无从判断）。 */
    fun isBelow(installed: String?, required: String?): Boolean {
        if (installed.isNullOrBlank() || required.isNullOrBlank()) return false
        return compare(installed, required) < 0
    }

    /** 便捷判定：包 [packId] 已装的 [installed] 是否低于 App 的要求。 */
    fun isOutdated(packId: String, installed: String?): Boolean =
        isBelow(installed, requiredFor(packId))
}
