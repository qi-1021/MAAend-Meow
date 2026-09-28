package com.aliothmoon.maafw.supplement

import android.content.Context
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.MiscConstants
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextFromProject
import com.aliothmoon.maafw.i18n.uiTextJoin
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 补充包的下载 / 校验 / 安装 / 删除。
 *
 * 与 [SupplementPack] 的分工：那边是纯逻辑（清单解析、状态判定），这边是带副作用的执行层。
 * 落盘布局：`<externalFilesDir>/supplements/<packId>/<file.path>`，保持清单里的相对路径结构
 * ——消费方（navmesh 寻路等）按相对路径来取，见 [fileFor]。
 *
 * 三条底线：
 * 1. 不整读进内存：单个文件最大 98 MiB，下载走流式，边写边算校验和。
 * 2. 半个文件比没有更糟：先写同目录 `.part`，**校验通过后**才 rename 到位。
 * 3. 文案一律走 [UiText]，不在这里拼中文（UiTextBoundaryTest 会拦）。
 *
 * 线程模型：所有公开方法可从主线程调；真正的磁盘 / 网络活在 [MaaDispatchers.IO] 上。
 * 同一时刻只允许一个下载在跑，[cancel] 取消当前那个。
 */
class SupplementPackInstaller(
    context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val supplementsDir: File =
        File(
            checkNotNull(appContext.getExternalFilesDir(null)) {
                "The external private directory is unavailable; supplements cannot be stored"
            },
            SUPPLEMENTS_DIR,
        )

    data class Progress(val downloadedBytes: Long, val totalBytes: Long, val phase: Phase) {
        enum class Phase { DOWNLOADING, VERIFYING }
    }

    data class PackState(
        val pack: SupplementPack.Pack,
        val state: SupplementPack.State,
        /** null = 空闲 */
        val progress: Progress?,
        /** null = 无错 */
        val error: UiText?,
    )

    data class UiState(
        val manifest: SupplementPack.Manifest? = null,
        /** manifest?.source?.commit.orEmpty()，给界面显示来源版本 */
        val sourceCommit: String = "",
        val packs: List<PackState> = emptyList(),
        /** 已安装包占用之和 */
        val totalInstalledBytes: Long = 0L,
    )

    private val _state = MutableStateFlow(UiState(null, "", emptyList(), 0L))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var manifest: SupplementPack.Manifest? = null

    private var currentJob: Job? = null
    private var currentPackId: String? = null

    /**
     * 每次 [install] 自增，旧 job 的收尾不许覆盖新 job 的进度。
     *
     * cancel 与「立刻重试同一个包」之间没有必然的顺序：旧协程要跑到下一个挂起点才处理取消，
     * 若此时新 job 已把 progress 打开，旧 job 的取消收尾会把它抹掉。
     */
    @Volatile
    private var generation = 0

    /** 正在阻塞读 socket 的那个请求；[cancel] 里要能把它掐掉，否则 read 不感知协程取消 */
    @Volatile
    private var activeCall: Call? = null

    // ───────────────────────── 对外 ─────────────────────────

    /** 重扫本地并更新 state。界面进入补充包页时调一次。 */
    fun refresh() {
        scope.launch { rescan() }
    }

    /** 启动下载。依赖未满足或空间不足时**不启动**，只把 error 写到对应包上。 */
    fun install(packId: String) {
        currentJob?.cancel()
        val token = ++generation
        val job = scope.launch {
            val loaded = ensureManifest() ?: return@launch
            if (_state.value.packs.none { it.pack.id == packId }) rescan()
            val pack = loaded.packs.firstOrNull { it.id == packId } ?: return@launch
            if (!preflight(loaded, pack, token)) return@launch
            runInstall(loaded, pack, token)
        }
        currentJob = job
        currentPackId = packId
        job.invokeOnCompletion {
            if (currentJob === job) {
                currentJob = null
                currentPackId = null
                activeCall = null
            }
        }
    }

    /** 取消当前下载：删临时文件、progress 归 null，不算错误。 */
    fun cancel() {
        currentJob?.cancel()
        // 协程取消要等下一次挂起点才生效，而阻塞的 read 不是挂起点——直接掐断请求
        activeCall?.cancel()
    }

    /** 删除该包目录。若正在下这个包，先取消。 */
    fun remove(packId: String) {
        if (currentPackId == packId) {
            // 连同代际一起推进：已下到一半的 job 即便刚好越过校验，也不许再把 state 改回 INSTALLED
            generation++
            cancel()
        }
        scope.launch {
            withContext(MaaDispatchers.IO) { packDir(packId).deleteRecursively() }
            updatePack(packId) {
                it.copy(state = SupplementPack.State.NOT_INSTALLED, progress = null, error = null)
            }
        }
    }

    /** 包的根目录：`<externalFilesDir>/supplements/<packId>`。 */
    fun packDir(packId: String): File = File(supplementsDir, packId)

    /** 消费方按清单里的相对路径取资产，例如 `map/navmesh/base.nav.gz`。 */
    fun fileFor(packId: String, relativePath: String): File =
        File(packDir(packId), relativePath)

    // ───────────────────────── 清单与扫描 ─────────────────────────

    private suspend fun ensureManifest(): SupplementPack.Manifest? {
        manifest?.let { return it }
        val loaded = withContext(MaaDispatchers.IO) { readManifest() }
        manifest = loaded
        return loaded
    }

    private fun readManifest(): SupplementPack.Manifest? = try {
        appContext.assets.open(MANIFEST_ASSET).bufferedReader().use { reader ->
            SupplementPack.parseManifest(reader.readText())
        }
    } catch (_: IOException) {
        null
    }

    private suspend fun rescan() {
        val loaded = ensureManifest()
        val packs = withContext(MaaDispatchers.IO) { loaded?.packs.orEmpty().map(::scanPack) }
        _state.value = UiState(
            manifest = loaded,
            sourceCommit = loaded?.source?.commit.orEmpty(),
            packs = packs,
            totalInstalledBytes = totalInstalled(packs),
        )
    }

    private fun scanPack(pack: SupplementPack.Pack): PackState = PackState(
        pack = pack,
        state = SupplementPack.stateOf(pack, SupplementPackLocal.scanLocal(packDir(pack.id), pack)),
        progress = null,
        error = null,
    )

    // ───────────────────────── 预检 ─────────────────────────

    /** 依赖与磁盘空间的前置检查；不通过就把 error 写到包上并返回 false。 */
    private fun preflight(loaded: SupplementPack.Manifest, pack: SupplementPack.Pack, token: Int): Boolean {
        val installed = _state.value.packs
            .filter { it.state == SupplementPack.State.INSTALLED }
            .map { it.pack.id }
            .toSet()
        val unmet = SupplementPack.unmetRequirements(pack, installed)
        if (unmet.isNotEmpty()) {
            val names = unmet.map { id ->
                loaded.packs.firstOrNull { it.id == id }?.let(::nameText) ?: uiTextFromProject(id)
            }
            updatePack(pack.id, token) {
                it.copy(error = uiTextOf(R.string.supplement_requires_format, uiTextJoin(*names.toTypedArray(), separator = "、")))
            }
            return false
        }

        val required = pack.totalBytes + DISK_MARGIN_BYTES
        if (supplementsDir.usableSpace < required) {
            updatePack(pack.id, token) {
                it.copy(
                    error = uiTextOf(
                        R.string.supplement_error_disk_full,
                        SupplementPackLocal.readableSize(pack.totalBytes),
                    ),
                )
            }
            return false
        }
        return true
    }

    /**
     * 包名文案。走 [SupplementPackText] 的静态映射，不用 `getIdentifier` 动态查——
     * release 构建的资源压缩会裁掉「只有动态引用」的字符串，编译期没提示，
     * 装到机器上才发现包名显示成了 id。
     */
    private fun nameText(pack: SupplementPack.Pack): UiText {
        val resId = SupplementPackText.nameRes(pack.id)
        return if (resId != null) uiTextOf(resId) else uiTextFromProject(pack.id)
    }

    // ───────────────────────── 下载 ─────────────────────────

    private suspend fun runInstall(loaded: SupplementPack.Manifest, pack: SupplementPack.Pack, token: Int) {
        withContext(MaaDispatchers.IO) {
            val ctx = coroutineContext
            var completed = 0L
            var part: File? = null
            try {
                updatePack(pack.id, token) {
                    it.copy(
                        error = null,
                        progress = Progress(0L, pack.totalBytes, Progress.Phase.DOWNLOADING),
                    )
                }

                for (spec in pack.files) {
                    ctx.ensureActive()
                    val target = fileFor(pack.id, spec.path)
                    val partFile = File(target.path + PART_SUFFIX)
                    part = partFile
                    target.parentFile?.mkdirs()

                    val url = SupplementPack.urlFor(loaded, spec).toHttpUrl()
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", MiscConstants.BROWSER_UA)
                        // 校验和对的是原始字节，绝不能让传输层 gzip 改写
                        .header("Accept-Encoding", "identity")
                        .get()
                        .build()

                    val call = client.newCall(request).also { activeCall = it }
                    try {
                        call.await().use { response ->
                            if (response.code != HTTP_OK) {
                                throw IOException("HTTP ${response.code}")
                            }
                            var inFile = 0L
                            response.body.byteStream().use { raw ->
                                FileOutputStream(partFile).use { out ->
                                    val (sha1, bytes) = SupplementPack.gitBlobSha1OfStream(
                                        spec.size,
                                        TeeInputStream(raw, out) { delta ->
                                            inFile += delta
                                            publishProgress(
                                                pack.id,
                                                token,
                                                completed + inFile,
                                                pack.totalBytes,
                                                Progress.Phase.DOWNLOADING,
                                            )
                                        },
                                    )
                                    // 字节拿全了才谈校验：之前是「下载中」，这一刻起是「校验中」
                                    publishProgress(
                                        pack.id,
                                        token,
                                        completed + bytes,
                                        pack.totalBytes,
                                        Progress.Phase.VERIFYING,
                                    )
                                    if (bytes != spec.size || !sha1.equals(spec.blob, ignoreCase = true)) {
                                        throw VerifyFailedException()
                                    }
                                }
                            }
                        }
                    } finally {
                        if (activeCall === call) activeCall = null
                    }

                    // 校验通过才到位；同目录 rename，掉电也不会留下半个文件冒充成品
                    move(partFile, target)
                    completed += spec.size
                }

                updatePack(pack.id, token) {
                    it.copy(
                        state = SupplementPack.State.INSTALLED,
                        progress = null,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // 取消时 activeCall.cancel() 会让 read 抛 IOException；这不是网络故障
                if (!ctx.isActive) throw CancellationException("supplement download cancelled", e)
                fail(pack.id, token, uiTextOf(R.string.supplement_error_network, e.message.orEmpty()))
            } catch (e: VerifyFailedException) {
                fail(pack.id, token, uiTextOf(R.string.supplement_error_verify_failed))
            } catch (e: Exception) {
                fail(pack.id, token, uiTextOf(R.string.supplement_error_network, e.message.orEmpty()))
            } finally {
                // 成功路径 move 后 part 已不存在；失败 / 取消一律清掉
                part?.delete()
                if (!ctx.isActive) {
                    updatePack(pack.id, token) { it.copy(progress = null) }
                }
            }
        }
    }

    private fun fail(packId: String, token: Int, error: UiText) {
        updatePack(packId, token) { it.copy(progress = null, error = error) }
    }

    private fun publishProgress(
        packId: String,
        token: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        phase: Progress.Phase,
    ) {
        updatePack(packId, token) { it.copy(progress = Progress(downloadedBytes, totalBytes, phase)) }
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(target)) {
                throw IOException("Cannot finalize supplement file: ${target.name}")
            }
        }
    }

    // ───────────────────────── state 维护 ─────────────────────────

    /**
     * [token] 为 -1 表示不做代际检查（[remove] 这类与下载无关的写入）；
     * 下载路径传入本次 install 的 token，被取消的旧 job 不许再改 state。
     */
    private fun updatePack(packId: String, token: Int = -1, transform: (PackState) -> PackState) {
        if (token >= 0 && token != generation) return
        _state.update { ui ->
            val packs = ui.packs.map { if (it.pack.id == packId) transform(it) else it }
            ui.copy(packs = packs, totalInstalledBytes = totalInstalled(packs))
        }
    }

    private fun totalInstalled(packs: List<PackState>): Long = packs
        .filter { it.state == SupplementPack.State.INSTALLED }
        .sumOf { it.pack.totalBytes }

    /**
     * 边读边写：把下载流的每一块同时落盘，再把同一块喂给校验和。
     *
     * 不重写校验循环的原因：`gitBlobSha1OfStream` 是纯逻辑层已测过的实现，
     * 在这里复制一份哈希逻辑只会多一处会漂移的真相。
     */
    private class TeeInputStream(
        private val source: InputStream,
        private val sink: OutputStream,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {

        override fun read(): Int {
            val b = source.read()
            if (b >= 0) {
                sink.write(b)
                onBytes(1L)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = source.read(b, off, len)
            if (n > 0) {
                sink.write(b, off, n)
                onBytes(n.toLong())
            }
            return n
        }

        override fun available(): Int = source.available()

        override fun close() = source.close()
    }

    private class VerifyFailedException : Exception("supplement file verification failed")

    private companion object {
        /** assets 里的清单文件名 */
        const val MANIFEST_ASSET = "supplement-packs.json"

        /** `getExternalFilesDir(null)` 下的子树 */
        const val SUPPLEMENTS_DIR = "supplements"

        const val PART_SUFFIX = ".part"
        const val HTTP_OK = 200

        /** 空间预检余量：下载期间系统其他写入也得有地方落 */
        const val DISK_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
