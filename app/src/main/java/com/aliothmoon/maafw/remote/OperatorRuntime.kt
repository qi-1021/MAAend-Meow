package com.aliothmoon.maafw.remote

import java.io.File
import java.time.Instant

/**
 * 据点交易干员子系统的**运行时聚合**。
 *
 * 把「不依赖 JNA 的部分」收在这里：数据加载与缓存、会话、扫描状态、OCR 交接槽、
 * 产权快照读取、UID 来源。MaaRunner 只负责把 lib/context/image 接上去，
 * 于是这一层仍然可以本地单测。
 *
 * 数据路径：打包链路把上游 assets 打成 `assets/pi.zip`，App 解包到 `<externalFilesDir>/pi/`，
 * 所以 `selection_data.json` 在 `projectRoot/data/OutpostTrading/selection_data.json`。
 * **不要**照抄 `loadDeliveryCatalogFromApk` 的 `assets/data/...`——当前打包方式下没有散装 data 目录。
 */
object OperatorRuntime {

    const val SELECTION_DATA_RELATIVE = "data/OutpostTrading/selection_data.json"
    const val CACHE_RELATIVE = "debug/record/OutpostTradingCache.json"

    /** 未接上 CaptureUid 前所有账号共用这个分区（与上游 noop 行为一致）。 */
    const val UNKNOWN_UID = OperatorCache.UNKNOWN_UID

    /** 日志出口；由宿主注入（默认丢弃），这样本层不依赖 android.util.Log，可本地单测。 */
    @Volatile
    var logger: (String) -> Unit = {}

    /** 解包后的 PI 根；由 MaaRunner 注入。 */
    @Volatile
    var projectRoot: String? = null

    /** 干员快照缓存的宿主目录；默认用 [projectRoot] 下的相对路径。 */
    @Volatile
    var cacheRoot: String? = null

    /** 进程内不可变的数据目录缓存（上游 selectiondata / operator 各有一层 sync.Once）。 */
    @Volatile
    private var cachedFile: OperatorDataset.SelectionFile? = null

    @Volatile
    private var cachedSelection: OperatorDataset.OperatorSelectionData? = null

    @Volatile
    private var loadError: String? = null

    val scanStates = OperatorScan.ScanStates()
    val ocrHandoff = OperatorScan.OcrHandoff()

    /** UID 来源；CaptureUid 尚未移植，固定返回 unknown。 */
    @Volatile
    var uidProvider: () -> String = { UNKNOWN_UID }

    val session = OperatorSession(object : OperatorSession.Host {
        override fun currentUid(): String = uidProvider()

        override fun loadProsperityMaxLocations(uid: String): Set<String> =
            OperatorCache.prosperityMaxLocations(readCache(), uid)

        override fun persistProsperityStatus(uid: String, location: String, reached: Boolean): Boolean? {
            return try {
                val (changed, updated) = OperatorCache.updateProsperity(readCache(), uid, location, reached)
                if (!changed) return false
                val data = selectionFile() ?: return null
                OperatorCache.write(cacheFile(), updated, data)
                true
            } catch (t: Throwable) {
                log("OperatorRuntime: 写发展值状态失败：${t.message}")
                null
            }
        }
    })

    private fun log(message: String) = logger(message)

    // ───────────────────────── 数据 ─────────────────────────

    /** 加载并缓存 selection_data.json；失败返回 null 并记下原因。 */
    fun loadData(): OperatorDataset.OperatorSelectionData? {
        cachedSelection?.let { return it }
        synchronized(this) {
            cachedSelection?.let { return it }
            val file = loadFile() ?: return null
            val built = OperatorDataset.buildOperatorSelectionData(file)
            if (built == null) {
                loadError = "buildOperatorSelectionData 失败"
                return null
            }
            cachedSelection = built
            return built
        }
    }

    fun selectionData(): OperatorDataset.OperatorSelectionData? = loadData()

    fun selectionFile(): OperatorDataset.SelectionFile? = loadFile()

    private fun loadFile(): OperatorDataset.SelectionFile? {
        cachedFile?.let { return it }
        val root = projectRoot
        if (root.isNullOrBlank()) {
            loadError = "projectRoot 未注入"
            return null
        }
        val path = File(root, SELECTION_DATA_RELATIVE)
        if (!path.isFile) {
            loadError = "缺少 ${path.path}"
            log("OperatorRuntime: $loadError")
            return null
        }
        val parsed = OperatorDataset.parse(path.readText())
        if (parsed == null || !OperatorDataset.validateOperators(parsed)) {
            loadError = "selection_data.json 结构不合法"
            log("OperatorRuntime: $loadError")
            return null
        }
        cachedFile = parsed
        return parsed
    }

    fun lastError(): String = loadError ?: "operator data unavailable"

    /** 清掉进程级数据缓存（测试/切资源用）。 */
    fun resetForTest() {
        synchronized(this) {
            cachedFile = null
            cachedSelection = null
            loadError = null
        }
    }

    // ───────────────────────── 缓存 ─────────────────────────

    fun cacheFile(): File {
        val root = cacheRoot ?: projectRoot ?: "."
        return File(root, CACHE_RELATIVE)
    }

    /** 读干员快照缓存；坏数据按上游分级容错（整份当不存在）。 */
    fun readCache(): OperatorCache.Cache = try {
        OperatorCache.read(cacheFile())
    } catch (t: Throwable) {
        log("OperatorRuntime: 读缓存失败：${t.message}")
        OperatorCache.Cache(null)
    }

    /** 账号拥有的干员名；无快照时为空集（不是 null，上游区分「没扫过」与「扫过但没有」）。 */
    fun loadOwnedNames(): Set<String> = OperatorCache.cachedOperatorIds(readCache(), uidProvider()).toSet()

    /** 是否已有快照（CacheReady 用）。 */
    fun hasSnapshot(): Boolean = OperatorCache.hasOperatorSnapshot(readCache(), uidProvider())

    /** 缓存更新时间，供状态提示。 */
    fun snapshotUpdatedAt(): String? = OperatorCache.cachedOperatorUpdatedAt(readCache(), uidProvider())

    /** 清掉当前账号的快照（触发重扫）。 */
    fun invalidateSnapshot(): Boolean {
        val data = selectionFile() ?: return false
        return try {
            val updated = OperatorCache.invalidateOperatorSnapshot(readCache(), uidProvider())
            OperatorCache.write(cacheFile(), updated, data)
            true
        } catch (t: Throwable) {
            log("OperatorRuntime: 清快照失败：${t.message}")
            false
        }
    }

    /**
     * 写快照。只保留「扫描域 ∩ 已识别」的 ID。
     * 交集为空会写成空数组（= 扫过但没有相关干员），而不是当成没扫过。
     */
    fun writeSnapshot(
        scanCandidates: List<OperatorDataset.OperatorCandidate>,
        observed: List<String>,
    ): Boolean {
        val data = selectionFile() ?: return false
        return try {
            val merged = OperatorCache.mergeOperatorSnapshot(
                readCache(), uidProvider(), scanCandidates, observed, Instant.now(),
            )
            OperatorCache.write(cacheFile(), merged, data)
            true
        } catch (t: Throwable) {
            loadError = "写快照失败：${t.message}"
            log("OperatorRuntime: $loadError")
            false
        }
    }
}
