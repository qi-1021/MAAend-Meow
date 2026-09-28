package com.aliothmoon.maafw.supplement

import java.io.File
import java.io.IOException

/**
 * 补充包的本地扫描：纯逻辑，不依赖 Android，可脱离设备单测。
 *
 * 与 [SupplementPackInstaller] 分开的理由：installer 本体依赖 Context 与 OkHttp，
 * 本机验证脚本（scripts/verify_pure_logic.sh）编不了它；而「磁盘上现在是什么状态」
 * 这段决策逻辑跟平台无关，拆出来才进得了那个脚本。
 */
object SupplementPackLocal {

    /**
     * 扫一个包的本地目录，返回以**包内相对路径**为键的
     * [Map]，供 [SupplementPack.stateOf] 判定状态。
     *
     * 只做一件有代价的事：对大小与清单一致的文件算 git blob SHA-1。大小对不上时
     * 直接记 `sha1 = null`——反正 [SupplementPack.stateOf] 先比大小，那 98 MiB
     * 白读一遍没有意义。文件读取失败记 `null` 而不是抛：扫描是只读诊断，
     * 一个不可读文件不该让整页状态挂掉。
     */
    fun scanLocal(root: File, pack: SupplementPack.Pack): Map<String, SupplementPack.LocalFile> {
        val out = LinkedHashMap<String, SupplementPack.LocalFile>(pack.files.size)
        for (spec in pack.files) {
            val local = File(root, spec.path)
            if (!local.isFile) continue
            val size = local.length()
            val sha1 = if (size == spec.size) sha1Of(local, size) else null
            out[spec.path] = SupplementPack.LocalFile(size = size, sha1 = sha1)
        }
        return out
    }

    private fun sha1Of(file: File, size: Long): String? = try {
        file.inputStream().use { SupplementPack.gitBlobSha1OfStream(size, it).first }
    } catch (_: IOException) {
        null
    }

    /**
     * 人类可读的体积，用于「还需要 %1$s」这类文案。
     *
     * 用 1024 进制（KiB/MiB）而不是 1000：清单与 `File.usableSpace` 口径一致，
     * 用户拿设置页的「已安装」数字去对系统里的占用才对得上。
     */
    fun readableSize(bytes: Long): String = when {
        bytes < KIB -> "$bytes B"
        bytes < MIB -> "${bytes / KIB} KiB"
        else -> "${bytes / MIB} MiB"
    }

    private const val KIB = 1024L
    private const val MIB = KIB * 1024
}
