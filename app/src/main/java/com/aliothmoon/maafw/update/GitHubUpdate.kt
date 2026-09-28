package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.MiscConstants
import com.aliothmoon.maafw.i18n.uiTextFromFramework
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.util.HttpClientHelper
import com.aliothmoon.maafw.util.boolean
import com.aliothmoon.maafw.util.parseJsonArray
import com.aliothmoon.maafw.util.parseJsonObject
import com.aliothmoon.maafw.util.readBody
import com.aliothmoon.maafw.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * GitHub releases 的 API 取数与解析：分页拉全量 release 列表，检查与解析在其上各取所需
 */
internal class GitHubReleasesApi(
    private val helper: HttpClientHelper,
) {
    internal data class Release(
        val tag: String,
        val htmlUrl: String?,
        val body: String?,
        val assets: List<Asset>,
        val prerelease: Boolean = false,
    )

    internal data class Asset(
        val name: String,
        val downloadUrl: String,
        val sha256: String?,
    ) {
        val isApk: Boolean get() = name.isApkUrl() || downloadUrl.isApkUrl()
    }

    /** 只认 `owner/repo`；URL 形式在 PiParser.parseMetadata 里已截成这两段，这里只兜异常输入 */
    fun parseRepository(rawUrl: String?): String? =
        rawUrl?.trim()?.takeIf(REPOSITORY_PATTERN::matches)

    suspend fun releases(repository: String): UpdateSourceOutcome<List<Release>> {
        val releases = mutableListOf<Release>()
        val endpoints = listOf(
            buildApiUrl(repository),
            "https://ungh.cc/repos/$repository/releases",
            "https://ghfast.top/" + buildApiUrl(repository),
            "https://gh-proxy.com/" + buildApiUrl(repository),
        )

        for (page in 1..MAX_PAGES) {
            var lastOutcome: UpdateSourceOutcome.Failed? = null
            var parsedArray: JsonArray? = null

            for (endpoint in endpoints) {
                try {
                    val response = helper.get(
                        endpoint,
                        buildMap {
                            put("per_page", PAGE_SIZE.toString())
                            put("page", page.toString())
                        },
                        API_HEADERS,
                    )
                    val sc = response.code
                    val body = response.readBody()
                    if (!sc.isSuccess()) {
                        val serverMessage = parseJsonObject(body)?.string("message")
                        val reason = apiFailureReason(sc)
                        val failed = UpdateSourceOutcome.Failed(
                            reason,
                            detail = serverMessage?.let(::uiTextFromFramework)
                                ?: uiTextOf(R.string.update_detail_http_status, sc),
                        )
                        // 限流是账号/IP 级的：换镜像端点照样被限，直接收手。
                        // 早先这里只记 lastOutcome 再 continue，于是后面三个镜像端点
                        // 找不到响应就各自抛异常，把 RATE_LIMITED 覆盖成 NETWORK——
                        // 真正的限流原因被吃掉，还白打三次请求。
                        if (reason == UpdateCheckFailure.RATE_LIMITED) return failed
                        lastOutcome = failed
                        continue
                    }
                    val parsedObj = parseJsonObject(body)
                    val parsed = parseJsonArray(body) ?: (parsedObj?.get("releases") as? JsonArray)
                    if (parsed == null) {
                        lastOutcome = UpdateSourceOutcome.Failed(UpdateCheckFailure.INVALID_RESPONSE)
                        continue
                    }
                    parsedArray = parsed
                    break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag("GitHubUpdate").w(e, "endpoint %s failed, trying fallback", endpoint)
                    lastOutcome = UpdateSourceOutcome.Failed(UpdateCheckFailure.NETWORK)
                }
            }

            if (parsedArray == null) {
                return lastOutcome ?: UpdateSourceOutcome.Failed(UpdateCheckFailure.NETWORK)
            }

            if (parsedArray.isEmpty()) break
            releases += parsedArray.filterIsInstance<JsonObject>().mapNotNull(::release)
            if (parsedArray.size < PAGE_SIZE) break
        }
        return UpdateSourceOutcome.Ok(releases)
    }

    /** 渠道过滤 + 版本解析；无一条合格返回 null */
    fun latestEligible(releases: List<Release>, channel: UpdateChannel): Pair<Release, UpdateVersion>? =
        releases
            .mapNotNull { candidate ->
                val version = UpdateVersion.parse(candidate.tag) ?: return@mapNotNull null
                if (!version.allowedFor(channel)) return@mapNotNull null
                if (channel == UpdateChannel.STABLE && candidate.prerelease) return@mapNotNull null
                candidate to version
            }
            .maxByOrNull { it.second }

    /**
     * 先挑按本机 ABI 拆的变体（标记按优先级排），没拆到再回退 universal
     * （不带任何 ABI 标记的单个 apk）；两者都没有或 universal 歧义返回 null，
     * 交由上层报 NO_MATCHING_ASSET
     */
    fun selectAsset(assets: List<Asset>, abi: AndroidAbi): Asset? {
        val apkAssets = assets.filter(Asset::isApk)
        if (apkAssets.isEmpty()) return null

        val markers = ABI_MARKERS[abi].orEmpty()
        if (markers.isNotEmpty()) {
            val matched = apkAssets
                .mapNotNull { asset ->
                    markers.indexOfFirst { asset.name.matchesAlias(it) }
                        .takeIf { it >= 0 }
                        ?.let { it to asset }
                }
                .minByOrNull { it.first }
                ?.second
            if (matched != null) return matched
        }

        val universal = apkAssets.filterNot { asset -> ALL_ABI_MARKERS.any { asset.name.matchesAlias(it) } }
        if (universal.size == 1) return universal.first()

        // 到这里说明：本机 ABI 没有对应变体，且没有唯一的 universal。
        // 早先这里 `return apkAssets.firstOrNull()` 会兜底返回任意一个 APK——
        // 等于把 ABI 校验架空，会把 x86_64 的包推给 arm64 设备（装上也是坏的）。
        // 与上面文档注释一致：返回 null，交由上层报 NO_MATCHING_ASSET。
        return null
    }

    private fun release(raw: JsonObject): Release? {
        val tag = raw.string("tag_name") ?: raw.string("tag") ?: return null
        return Release(
            tag = tag,
            htmlUrl = raw.string("html_url") ?: raw.string("htmlUrl"),
            body = raw.string("body") ?: raw.string("markdown"),
            assets = (raw["assets"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.mapNotNull(::asset)
                .orEmpty(),
            prerelease = raw.boolean("prerelease") ?: false,
        )
    }

    private fun asset(raw: JsonObject): Asset? {
        val apiUrl = raw.string("url")
        val browserUrl = raw.string("browser_download_url") ?: raw.string("downloadUrl")
        val downloadUrl = browserUrl ?: apiUrl ?: return null
        return Asset(
            name = raw.string("name") ?: downloadUrl.substringAfterLast('/'),
            downloadUrl = downloadUrl,
            sha256 = raw.string("digest")?.trim()?.takeIf(DIGEST_PATTERN::matches),
        )
    }

    private fun String.matchesAlias(alias: String): Boolean = Regex(
        """(^|[ _.\-/])${Regex.escape(alias)}($|[ _.\-/])""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(this)

    private fun buildApiUrl(repository: String): String =
        "https://api.github.com/repos/$repository/releases"

    /** 429 与 403 都按限流归类：不带 token 的匿名额度被这两者覆盖 */
    private fun apiFailureReason(statusCode: Int): UpdateCheckFailure =
        if (statusCode == TOO_MANY_REQUESTS || statusCode == FORBIDDEN) {
            UpdateCheckFailure.RATE_LIMITED
        } else {
            UpdateCheckFailure.HTTP
        }

    private fun Int.isSuccess(): Boolean = this in 200..299

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 3
        const val TOO_MANY_REQUESTS = 429
        const val FORBIDDEN = 403
        val API_HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to MiscConstants.BROWSER_UA,
        )
        val REPOSITORY_PATTERN = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
        val DIGEST_PATTERN = Regex("""^sha256:[0-9a-fA-F]{64}$""")

        /** 本机 ABI 的候选标记，序即优先级（arm64-v8a 优先于裸 arm64） */
        val ABI_MARKERS: Map<AndroidAbi, List<String>> = mapOf(
            AndroidAbi.ARM64 to listOf("arm64-v8a", "arm64", "aarch64"),
            AndroidAbi.X86_64 to listOf("x86_64", "x64", "amd64"),
            AndroidAbi.ARM to listOf("armeabi-v7a", "arm"),
            AndroidAbi.X86 to listOf("x86"),
        )

        /** 任一 ABI 的标记：命中即视为拆分变体，不进 universal 回退 */
        val ALL_ABI_MARKERS = ABI_MARKERS.values.flatten().distinct()
    }
}

internal class GitHubUpdateClient(
    private val api: GitHubReleasesApi,
) : UpdateSourceClient {

    override val source: UpdateSource = UpdateSource.GITHUB

    override suspend fun check(request: UpdateCheckRequest): UpdateCheckResult = try {
        val currentVersion = UpdateVersion.parse(request.currentVersion)
            ?: return UpdateCheckResult.SourceFailed(
                source,
                UpdateCheckFailure.VERSION_INVALID,
                detail = uiTextFromFramework(request.currentVersion),
            )
        val repository = api.parseRepository(request.githubRepository)
            ?: return UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.MISSING_CONFIGURATION)
        val releases = when (val outcome = api.releases(repository)) {
            is UpdateSourceOutcome.Failed -> return UpdateCheckResult.SourceFailed(
                source,
                outcome.reason,
                detail = outcome.detail
            )

            is UpdateSourceOutcome.Ok -> outcome.value
        }
        val effectiveChannel = if (request.channel == UpdateChannel.STABLE && currentVersion.preRelease.isNotEmpty()) {
            UpdateChannel.BETA
        } else {
            request.channel
        }
        val candidate = api.latestEligible(releases, effectiveChannel)
            ?: return UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.NO_MATCHING_ASSET)
        val (release, version) = candidate
        if (version <= currentVersion) {
            UpdateCheckResult.UpToDate(source, release.tag)
        } else {
            UpdateCheckResult.UpdateAvailable(
                source,
                UpdateInfo(version = release.tag, releaseNotesUrl = release.htmlUrl, releaseNotes = release.body),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 非业务异常一律按网络错误给用户，真实原因只进日志
        Timber.tag("UpdateCheck").w(e, "%s check failed", source)
        UpdateCheckResult.SourceFailed(source, UpdateCheckFailure.NETWORK)
    }

    override suspend fun resolve(request: UpdateResolveRequest): UpdateResolveResult = try {
        val currentVersion = UpdateVersion.parse(request.currentVersion)
        val repository = api.parseRepository(request.githubRepository)
            ?: return UpdateResolveResult.Failed(source, UpdateCheckFailure.MISSING_CONFIGURATION)
        val releases = when (val outcome = api.releases(repository)) {
            is UpdateSourceOutcome.Failed -> return UpdateResolveResult.Failed(
                source,
                outcome.reason,
                outcome.detail
            )

            is UpdateSourceOutcome.Ok -> outcome.value
        }
        val effectiveChannel = if (request.channel == UpdateChannel.STABLE && currentVersion?.preRelease?.isNotEmpty() == true) {
            UpdateChannel.BETA
        } else {
            request.channel
        }
        val (release, _) = api.latestEligible(releases, effectiveChannel)
            ?: return UpdateResolveResult.Failed(source, UpdateCheckFailure.NO_MATCHING_ASSET)
        val asset = api.selectAsset(release.assets, request.abi)
            ?: return UpdateResolveResult.Failed(source, UpdateCheckFailure.NO_MATCHING_ASSET)
        // 竞速选出最快镜像站，加速国内下载；失败自动回退原始 URL
        val downloadUrl = GitHubMirrorRacer.race(asset.downloadUrl)
        UpdateResolveResult.Resolved(
            ResolvedUpdate(
                source = source,
                version = release.tag,
                downloadUrl = downloadUrl,
                sha256 = asset.sha256,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 非业务异常一律按网络错误给用户，真实原因只进日志
        Timber.tag("UpdateResolve").w(e, "%s resolve failed", source)
        UpdateResolveResult.Failed(source, UpdateCheckFailure.NETWORK)
    }
}
