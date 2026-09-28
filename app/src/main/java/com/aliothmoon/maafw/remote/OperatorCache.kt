package com.aliothmoon.maafw.remote

import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * 据点交易的**干员快照持久缓存**（对齐上游 `outposttrading/operator/cache.go`）。
 *
 * 磁盘文件形如：
 * ```
 * { "accounts": { "<uid>": { "operators": { "updated_at": "...", "ids": [...] },
 *                           "locations": { "<location>": true } } } }
 * ```
 *
 * 三条容易写错、且错了会静默退化的地方：
 *
 *  1. **读取是「分级容错」**：顶层结构坏 → 整份当不存在；单个账号坏 → 只丢该账号。
 *     不是一坏就全丢，也不是一坏就报错。
 *  2. **`operators == null` 与 `ids == []` 语义完全不同**：前者是「从未扫描 / 已被判失效」，
 *     后者是「扫过且该账号确实一个相关干员都没有」。把空数组当成没缓存，
 *     会让每轮任务都全量滚动列表、且永远选不出人。
 *  3. **写入是「先规范化再整份校验」，任一账号非法则整份不写**（旧文件保持原样）。
 *
 * 用普通 data class + 通用 JSON 树实现，不依赖 kotlinx 序列化，所以可以本地单测；
 * 文件 IO 也只用到 `java.io`。
 */
object OperatorCache {

    const val FILE_NAME = "OutpostTradingCache.json"
    const val UNKNOWN_UID = "unknown"

    /** 疑似 Go 零值时间的特征（`time.Time{}` 序列化结果）。 */
    private const val ZERO_TIME_PREFIX = "0001-01-01T00:00:00"

    /** 上游 `outpostTradingOperatorSnapshot`。`ids == null` 表示字段缺失/显式 null。 */
    data class Snapshot(val updatedAt: String, val ids: List<String>?)

    /** 上游 `outpostTradingCacheAccount`。 */
    data class Account(
        val operators: Snapshot? = null,
        val locations: Map<String, Boolean>? = null,
    )

    /** 上游 `outpostTradingCache`。`accounts == null` 等价于「没有任何账号」。 */
    data class Cache(val accounts: Map<String, Account>? = null)

    // ───────────────────────── 解析 ─────────────────────────

    /**
     * 严格解析：顶层只允许 `accounts` 一个键，未知键直接判为「整份不可用」。
     *
     * 返回 null 表示整份当不存在（上游顶层损坏时的行为）。
     */
    fun parse(tree: Any?): Cache? {
        val root = tree as? Map<*, *> ?: return null
        // DisallowUnknownFields 等价物：顶层出现别的键就当整份坏掉
        if (root.keys.any { it != "accounts" }) return null

        val accountsNode = root["accounts"] ?: return Cache(null)
        val accountsMap = accountsNode as? Map<*, *> ?: return null

        val accounts = mutableMapOf<String, Account>()
        for ((key, value) in accountsMap) {
            val uid = key as? String ?: continue
            if (!isValidUid(uid)) continue
            val account = parseAccount(value) ?: continue
            accounts[uid] = account
        }
        return normalize(Cache(accounts))
    }

    private fun parseAccount(node: Any?): Account? {
        val map = node as? Map<*, *> ?: return null
        // 账号级同样禁止未知字段（上游单账号坏 -> 只丢该账号）
        if (map.keys.any { it != "operators" && it != "locations" }) return null

        var snapshot: Snapshot? = null
        map["operators"]?.let { raw ->
            val snap = raw as? Map<*, *> ?: return null
            if (snap.keys.any { it != "updated_at" && it != "ids" }) return null
            snapshot = Snapshot(
                updatedAt = (snap["updated_at"] as? String).orEmpty(),
                ids = (snap["ids"] as? List<*>)?.mapNotNull { it as? String },
            )
        }

        var locations: Map<String, Boolean>? = null
        map["locations"]?.let { raw ->
            val locs = raw as? Map<*, *> ?: return null
            locations = locs.entries
                .mapNotNull { entry ->
                    val k = entry.key as? String ?: return@mapNotNull null
                    val v = entry.value as? Boolean ?: return@mapNotNull null
                    k to v
                }
                .toMap()
        }

        return Account(snapshot, locations)
    }

    /** 便捷入口：从 JSON 文本解析。 */
    fun parseText(jsonText: String?): Cache? = parse(MaaJsonTree.parse(jsonText))

    /** 序列化为紧凑 JSON（写盘时由调用方决定是否美化）。 */
    fun toJson(cache: Cache): String = JsonTree.toJson(
        mapOf(
            "accounts" to cache.accounts?.mapValues { (_, account) ->
                val m = linkedMapOf<String, Any?>()
                account.operators?.let { snap ->
                    m["operators"] = linkedMapOf<String, Any?>(
                        "updated_at" to snap.updatedAt,
                        // nil 与非 nil 空表必须区分：前者是「没扫过」，后者是「扫过但没有」
                        "ids" to snap.ids?.toList(),
                    )
                }
                account.locations?.let { m["locations"] = it.toMap() }
                m
            },
        ),
    )

    // ───────────────────────── 校验 ─────────────────────────

    /** 上游 cache.go:429 `isValidOutpostTradingCacheUID`：`unknown` 或 16 位**小写**十六进制。 */
    fun isValidUid(uid: String): Boolean {
        if (uid == UNKNOWN_UID) return true
        if (uid.length != 16) return false
        return uid.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /**
     * 上游 cache.go:166 `outpostTradingCacheAccountIsValid`。
     *
     * `operators == null` 合法（无快照）；有快照则要求 `ids` 非 null、时间戳是有效 RFC3339 且非零值；
     * 所有干员 ID 必须存在于数据目录；`locations` 的键必须在数据目录里。
     */
    fun accountIsValid(account: Account, data: OperatorDataset.SelectionFile): Boolean {
        account.operators?.let { snap ->
            if (snap.ids == null) return false
            if (isZeroOrUnparsable(snap.updatedAt)) return false
            for (id in snap.ids) {
                if (id !in data.operators) return false
            }
        }
        account.locations?.forEach { (location, _) ->
            if (location !in data.locations) return false
        }
        return true
    }

    /** 上游 cache.go:150：写入前整份校验，任一账号非法即拒绝。 */
    fun isCacheValid(cache: Cache, data: OperatorDataset.SelectionFile): Boolean {
        for ((uid, account) in cache.accounts.orEmpty()) {
            if (!isValidUid(uid)) return false
            if (!accountIsValid(account, data)) return false
        }
        return true
    }

    // ───────────────────────── 规范化 ─────────────────────────

    /**
     * 上游 cache.go:357 `normalizeOutpostTradingCache`。
     *
     * - uid **原样保留**（读取阶段已校验过，禁止在这里做可能碰撞的合并/改写）
     * - 非 nil 快照的 ids：去空串、去重、字典序排序；空集合产出**非 nil 空表**
     * - locations 深拷贝
     * - accounts 为空 → null（写盘时省略顶层键）
     */
    fun normalize(cache: Cache): Cache {
        val normalized = mutableMapOf<String, Account>()
        for ((uid, account) in cache.accounts.orEmpty()) {
            val snapshot = account.operators?.let {
                Snapshot(
                    updatedAt = it.updatedAt,
                    ids = it.ids.orEmpty().filter { id -> id.isNotEmpty() }.distinct().sorted(),
                )
            }
            normalized[uid] = Account(snapshot, account.locations?.toMap())
        }
        return Cache(normalized.ifEmpty { null })
    }

    // ───────────────────────── 快照查询 ─────────────────────────

    /** 上游 cache.go:237：**只看存在性**，空 ids 也算有快照。 */
    fun hasOperatorSnapshot(cache: Cache, uid: String): Boolean =
        normalize(cache).accounts?.get(uid)?.operators != null

    /** 上游 cache.go:243：无快照返回空表。 */
    fun cachedOperatorIds(cache: Cache, uid: String): List<String> =
        normalize(cache).accounts?.get(uid)?.operators?.ids ?: emptyList()

    /** 上游 cache.go:252：无快照返回 null。 */
    fun cachedOperatorUpdatedAt(cache: Cache, uid: String): String? =
        normalize(cache).accounts?.get(uid)?.operators?.updatedAt

    // ───────────────────────── 写入式变更 ─────────────────────────

    /**
     * 上游 cache.go:198 `mergeOperatorSnapshot`：只保留「扫描域 ∩ 已识别」的 ID。
     *
     * 交集为空会产出**非 nil 空表**（扫过但没有相关干员），而不是当成没扫过。
     */
    fun mergeOperatorSnapshot(
        cache: Cache,
        uid: String,
        scanCandidates: List<OperatorDataset.OperatorCandidate>,
        observed: List<String>,
        now: Instant,
    ): Cache {
        val scanSet = scanCandidates.mapNotNull { it.name.takeIf(String::isNotEmpty) }.toSet()
        val operatorSet = observed.filter { it in scanSet }.toSet()
        return withOperatorSnapshot(cache, uid, operatorSet, now)
    }

    /** 上游 cache.go:336 `withOperatorSnapshot`：写快照并保留 locations。 */
    fun withOperatorSnapshot(
        cache: Cache,
        uid: String,
        ids: Set<String>,
        now: Instant,
    ): Cache {
        val accounts = (cache.accounts.orEmpty()).toMutableMap()
        val existing = accounts[uid] ?: Account()
        accounts[uid] = existing.copy(
            operators = Snapshot(updatedAt = now.toString(), ids = ids.sorted()),
        )
        return normalize(Cache(accounts))
    }

    /** 上游 cache.go:219 `invalidateOperatorSnapshotForUID`：清快照但**保留** locations。 */
    fun invalidateOperatorSnapshot(cache: Cache, uid: String): Cache {
        val accounts = cache.accounts.orEmpty()
        val existing = accounts[uid] ?: return normalize(cache)
        if (existing.operators == null) return normalize(cache)
        val updated = accounts.toMutableMap()
        updated[uid] = existing.copy(operators = null)
        return normalize(Cache(updated))
    }

    // ───────────────────────── 发展值状态 ─────────────────────────

    /** 上游 cache.go:279 `outpostProsperityStatusesForUID`。 */
    fun prosperityStatuses(cache: Cache, uid: String): Map<String, Boolean> =
        normalize(cache).accounts?.get(uid)?.locations ?: emptyMap()

    /** 上游 cache.go:287 `MaxLocationsForUID`：只取已经满级的据点。 */
    fun prosperityMaxLocations(cache: Cache, uid: String): Set<String> =
        prosperityStatuses(cache, uid).filterValues { it }.keys

    /**
     * 上游 cache.go:298 `updateCachedOutpostProsperity`：值未变则**不写盘**并返回 false。
     *
     * 返回 (是否真的改了, 新缓存)。location 为空时抛 IllegalArgumentException。
     */
    fun updateProsperity(cache: Cache, uid: String, locationRaw: String, reached: Boolean): Pair<Boolean, Cache> {
        val location = locationRaw.trim()
        require(location.isNotEmpty()) { "outpost prosperity location is empty" }

        val existing = cache.accounts?.get(uid)
        val previous = existing?.locations?.get(location)
        if (previous != null && previous == reached) return false to normalize(cache)

        val newLocations = (existing?.locations ?: emptyMap()).toMutableMap().also {
            it[location] = reached
        }
        val accounts = (cache.accounts.orEmpty()).toMutableMap()
        accounts[uid] = (existing ?: Account()).copy(locations = newLocations)
        return true to normalize(Cache(accounts))
    }

    // ───────────────────────── 文件 IO ─────────────────────────

    /** 读文件；文件不存在或顶层损坏 → 空缓存。IO 其它错误向上抛。 */
    fun read(path: File): Cache {
        if (!path.exists()) return Cache(null)
        val text = try {
            path.readText()
        } catch (e: IOException) {
            throw e
        }
        if (text.isBlank()) return Cache(null)
        return parseText(text) ?: Cache(null)
    }

    /**
     * 上游 cache.go:126 `writeOutpostTradingCache`：先规范化、再整份校验，然后原子写。
     *
     * 校验不过时抛异常且**不动原文件**。
     */
    fun write(path: File, cache: Cache, data: OperatorDataset.SelectionFile) {
        val normalized = normalize(cache)
        require(isCacheValid(normalized, data)) { "validate outpost trading cache: invalid structure" }
        path.parentFile?.mkdirs()
        writeAtomic(path, (toJson(normalized) + "\n").toByteArray())
    }

    /**
     * 上游 cache.go:393 `writeOutpostTradingCacheAtomic`：同目录临时文件 + fsync + rename。
     *
     * 任一步失败都会删掉临时文件，保证目录里不留半截 JSON。
     */
    fun writeAtomic(path: File, content: ByteArray, perm: String? = null) {
        val dir = path.parentFile ?: File(".")
        var cleanup = true
        val tmp = File.createTempFile(".${path.name}.", ".tmp", dir)
        try {
            tmp.outputStream().use { out ->
                out.write(content)
                out.flush()
                out.fd.sync()
            }
            if (perm != null) {
                // 上游 Chmod 0644；JVM 上尽力而为，失败不致命
                runCatching { tmp.setReadable(true, false); tmp.setWritable(true, false) }
            }
            if (!tmp.renameTo(path)) {
                throw IOException("rename failed: ${tmp.name} -> ${path.name}")
            }
            cleanup = false
        } finally {
            if (cleanup) tmp.delete()
        }
    }

    private fun isZeroOrUnparsable(updatedAt: String?): Boolean {
        if (updatedAt.isNullOrBlank()) return true
        if (updatedAt.startsWith(ZERO_TIME_PREFIX)) return true
        return try {
            Instant.parse(updatedAt)
            false
        } catch (_: Exception) {
            true
        }
    }
}
