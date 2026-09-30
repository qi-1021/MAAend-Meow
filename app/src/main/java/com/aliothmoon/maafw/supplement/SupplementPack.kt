package com.aliothmoon.maafw.supplement

import com.aliothmoon.maafw.remote.MaaJsonTree
import java.security.MessageDigest

/**
 * 补充包：体积大、按需下载的资产（地图定位/寻路模型、战斗识别模型）。
 *
 * 为什么要有这一层：把这些打进 APK 会让安装包从 275 MiB 涨到 **479 MiB**，
 * 而绝大多数用户只用得上其中一部分。理念是「想要什么就取什么」——
 * 基础包里是已经能用的功能，高级能力（精确寻路、自动战斗）由用户自己决定要不要下。
 *
 * 本文件是**纯逻辑**（不依赖 Android），因此可脱离设备单测：
 * 清单解析、安装状态判定、依赖检查、校验和计算。
 * 真正的下载/落盘在 SupplementPackInstaller，界面在 SettingsScreen。
 */
object SupplementPack {

    /** 清单里的一个文件。[blob] 是 git blob 的 SHA-1。 */
    data class FileSpec(val path: String, val size: Long, val blob: String)

    /** 一个可选装的包。名称/说明走 i18n key，不写死文案。 */
    data class Pack(
        val id: String,
        val nameKey: String,
        val summaryKey: String,
        val requires: List<String>,
        val totalBytes: Long,
        val files: List<FileSpec>,
    )

    data class Source(val repo: String, val commit: String, val baseUrl: String)

    data class Manifest(val schema: Int, val source: Source, val packs: List<Pack>)

    /** 本地文件的三要素；缺文件用 null 表示。 */
    data class LocalFile(val size: Long, val sha1: String?)

    /** 一个包相对本机的状态。 */
    enum class State {
        /** 一个文件都没有。 */
        NOT_INSTALLED,

        /** 有部分文件（下到一半、或校验失败被剔除）。 */
        PARTIAL,

        /** 全部文件齐、大小与校验和都对。 */
        INSTALLED,
    }

    // ───────────────────────── 清单 ─────────────────────────

    /** 解析清单；结构不对返回 null（而不是抛异常）。 */
    fun parseManifest(tree: Any?): Manifest? {
        val root = tree as? Map<*, *> ?: return null
        val schema = (root["schema"] as? Number)?.toInt() ?: return null
        val sourceMap = root["source"] as? Map<*, *> ?: return null
        val source = Source(
            repo = sourceMap["repo"] as? String ?: return null,
            commit = sourceMap["commit"] as? String ?: return null,
            baseUrl = sourceMap["baseUrl"] as? String ?: return null,
        )
        val packsNode = root["packs"] as? List<*> ?: return null

        val packs = packsNode.mapNotNull { node ->
            val p = node as? Map<*, *> ?: return@mapNotNull null
            val id = p["id"] as? String ?: return@mapNotNull null
            val nameKey = p["nameKey"] as? String ?: return@mapNotNull null
            val summaryKey = p["summaryKey"] as? String ?: return@mapNotNull null
            val requires = (p["requires"] as? List<*>)
                ?.mapNotNull { it as? String }.orEmpty()
            val files = (p["files"] as? List<*>).orEmpty().mapNotNull { f ->
                val fm = f as? Map<*, *> ?: return@mapNotNull null
                val path = fm["path"] as? String ?: return@mapNotNull null
                val size = (fm["size"] as? Number)?.toLong() ?: return@mapNotNull null
                val blob = fm["blob"] as? String ?: return@mapNotNull null
                FileSpec(path, size, blob)
            }
            if (files.isEmpty()) return@mapNotNull null
            Pack(
                id = id,
                nameKey = nameKey,
                summaryKey = summaryKey,
                requires = requires,
                // 以文件之和为准：清单里那个 totalBytes 只是冗余字段，别信它
                totalBytes = files.sumOf { it.size },
                files = files,
            )
        }
        if (packs.isEmpty()) return null
        return Manifest(schema, source, packs)
    }

    fun parseManifest(jsonText: String?): Manifest? {
        if (jsonText.isNullOrBlank()) return null
        return parseManifest(MaaJsonTree.parse(jsonText))
    }

    /** 下载地址：baseUrl 已带尾斜杠，这里只做拼接。等价于 [candidateUrlsFor] 的第一条。 */
    fun urlFor(manifest: Manifest, file: FileSpec): String =
        candidateUrlsFor(manifest, file).first()

    /**
     * 候选下载地址，**按顺序尝试**；[urlFor] 是第一条。
     *
     * 为什么需要多条：清单的 `baseUrl` 指向 `raw.githubusercontent.com`，它在国内网络下经常被重置
     * （实测 `Connection reset by peer`，连本机都拿不到），而补充包是用户按需下载的，必须能下到。
     * 这里给出镜像回退；三条都指向**同一个 commit 的同一份文件**，下载后一律按清单里的
     * git blob SHA-1 校验（见安装器），因此镜像只是传输通道，**不构成信任边界**——
     * 镜像服务给了坏字节只会校验失败然后换下一家。
     */
    fun candidateUrlsFor(manifest: Manifest, file: FileSpec): List<String> {
        val path = file.path.trimStart('/')
        val repo = manifest.source.repo
        val commit = manifest.source.commit
        return listOf(
            manifest.source.baseUrl.trimEnd('/') + "/" + path,
            "https://cdn.jsdelivr.net/gh/$repo@$commit/$path",
            "https://ghproxy.net/https://raw.githubusercontent.com/$repo/$commit/$path",
        ).distinct()
    }

    // ───────────────────────── 状态与依赖 ─────────────────────────

    /**
     * 判定一个包的安装状态。
     *
     * [local] 以**包内相对路径**为键（与 [FileSpec.path] 一致），例如
     * `map/cls.onnx`。文件齐、大小对、校验和对才算 [State.INSTALLED]；
     * 这个严格是有意的——半个 navmesh 拿去寻路只会得到莫名其妙的结果。
     */
    fun stateOf(pack: Pack, local: Map<String, LocalFile>): State {
        var present = 0
        var verified = 0
        for (file in pack.files) {
            val entry = local[file.path] ?: continue
            present++
            if (entry.size == file.size && entry.sha1 != null &&
                entry.sha1.equals(file.blob, ignoreCase = true)
            ) {
                verified++
            }
        }
        return when {
            present == 0 -> State.NOT_INSTALLED
            verified == pack.files.size -> State.INSTALLED
            else -> State.PARTIAL
        }
    }

    /** 还缺哪些依赖（已安装集合里没有的前置包）。 */
    fun unmetRequirements(pack: Pack, installed: Set<String>): List<String> =
        pack.requires.filterNot { it in installed }

    /**
     * 安装 [pack] 之后是否需要顺手装别的：返回它自己 + 尚未安装的前置。
     * 调用方据此提示「还要先装 X」。
     */
    fun requiredWithDependencies(pack: Pack, allPacks: List<Pack>, installed: Set<String>): List<Pack> {
        val byId = allPacks.associateBy { it.id }
        val out = mutableListOf<Pack>()
        val seen = mutableSetOf<String>()

        fun visit(p: Pack) {
            if (!seen.add(p.id)) return
            for (depId in p.requires) {
                byId[depId]?.let { visit(it) }
            }
            if (p.id !in installed) out += p
        }

        visit(pack)
        return out
    }

    // ───────────────────────── 校验和 ─────────────────────────

    /**
     * git blob 的 SHA-1：`SHA1("blob <size>\0" + content)`。
     *
     * 为什么用 SHA-1 而不是 SHA-256：GitHub 的 contents API 免费给这个值，
     * 而 SHA-256 要先把 204 MiB 全下下来才算得出（生成清单时就得下）。
     * 这里定位是**完整性校验**（防下载截断/串包），不是安全边界——
     * 传输本身走 HTTPS，且资源来自固定 commit 的固定仓库。
     */
    fun gitBlobSha1(size: Long, content: ByteArray): String {
        val header = "blob $size\u0000".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-1")
        md.update(header)
        md.update(content)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 流式版本：下载时边写边算，避免把 98 MiB 全读进内存。 */
    fun gitBlobSha1OfStream(size: Long, stream: java.io.InputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob $size\u0000".toByteArray(Charsets.UTF_8))
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
            total += n
        }
        return md.digest().joinToString("") { "%02x".format(it) } to total
    }
}
