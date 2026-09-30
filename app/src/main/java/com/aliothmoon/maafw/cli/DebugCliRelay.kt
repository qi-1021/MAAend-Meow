package com.aliothmoon.maafw.cli

/**
 * 远程调试**中继模式**的纯逻辑层：地址规格化、桥响应解析、状态码映射、重连退避。
 *
 * 与 [DebugCliSupport] / [DebugCliRemote] 同样的理由：这些东西不依赖 socket / Android，
 * 能在 [scripts/verify_pure_logic.sh] 里本机跑断言。真正的 HTTP 轮询与线程在
 * [DebugCliRelayClient]，binder 面在特权进程的 RemoteServiceImpl。
 *
 * 架构（为什么必须"手机出站"）：
 *  - 手机在蜂窝 / CGNAT / 异网下**无法被入站连接**，同网段直连与 `adb forward` 都不成立；
 *  - 所以由手机主动 POST `/attach` 握手建立 sid，再长轮询 `GET /pull` 取命令，
 *    远端调试者经公网桥 `POST /` 投递命令；
 *  - 这条链路只依赖"出站"，因此开着 VPN / 蜂窝也能用。
 *
 * 协议（见 `scripts/debug_cli_bridge.py`，写死在这里供服务端与测试共用）：
 *  - `POST /attach`            `X-Token` → 200，纯文本一行 sid
 *  - `GET  /pull?sid=&timeout=` `X-Token` → 200：第一行 rid、其余是命令；204：暂无命令
 *  - `POST /result?sid=&rid=`   `X-Token`，body=输出 → 200
 */

/** 默认的公网中继入口；这是"默认值但可修改"，用户在设置页改。 */
const val DEBUG_RELAY_DEFAULT_URL = "https://maaendset.qiisme1021.space"

/** 手机侧取一次命令的长轮询超时（秒）；桥会把 1..60 之外的值夹回来。 */
const val DEBUG_RELAY_PULL_TIMEOUT_SECONDS = 25

/** 重连退避的起始间隔（毫秒）。 */
const val DEBUG_RELAY_BASE_BACKOFF_MILLIS = 1_000L

/** 重连退避的上限（毫秒）；避免断网时把电池耗光。 */
const val DEBUG_RELAY_MAX_BACKOFF_MILLIS = 30_000L

/**
 * 中继客户端对外暴露的状态机。
 *
 * 刻意用显式 wire code 走 binder（旧特权进程与新 App 混跑时 ordinal 不稳），
 * [fromWire] 对未知值 fail-safe 地当成 [FAILED] 而不是 [IDLE]——报成"没在跑"会骗用户。
 */
enum class DebugCliRelayStatus(val wire: Int) {
    IDLE(0),
    CONNECTING(1),
    CONNECTED(2),
    FAILED(3),
    ;

    companion object {
        fun fromWire(value: Int): DebugCliRelayStatus =
            entries.firstOrNull { it.wire == value } ?: FAILED
    }
}

/** 一次 `/pull` 取回的命令：第一行 rid，其余是命令正文。 */
data class DebugCliRelayCommand(val rid: String, val command: String)

/** sid / rid 的形态：桥用 `uuid4().hex`，这里放宽到 URL 安全的字母数字与 `-` `_`。 */
private val RELAY_ID_RE = Regex("[A-Za-z0-9_-]{8,}")

/**
 * 规格化用户填的中继地址。
 *
 * - 去首尾空白、去尾部 `/`；
 * - 没写 scheme 时补 `https://`（用户多半只填域名）；
 * - 只接受 `http` / `https`，其余（含 ftp、file）一律返回 null——这是会被 App 主动连接的地址，
 *   不能把任意 scheme 交给 `URL.openConnection()`；
 * - host 为空或含空白返回 null。
 */
fun normalizeRelayUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val scheme = withScheme.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest = withScheme.substringAfter("://").trimEnd('/')
    if (rest.isEmpty()) return null
    if (rest.any { it.isWhitespace() }) return null
    return "$scheme://$rest"
}

/** 解析 `/attach` 的响应体：取第一行非空文本作为 sid；不合形态返回 null。 */
fun parseRelaySid(body: String?): String? {
    val line = body?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    return line.takeIf { RELAY_ID_RE.matches(it) }
}

/**
 * 解析 `/pull` 的响应体：第一行 rid、其余是命令。
 *
 * 响应不是 JSON，所以这里按行切。rid 不合形态、或命令正文为空时返回 null（当成"没拿到命令"，
 * 由调用方继续长轮询，而不是把半条命令当命令执行）。
 */
fun parsePullResponse(body: String?): DebugCliRelayCommand? {
    if (body.isNullOrEmpty()) return null
    val newline = body.indexOf('\n')
    val ridLine = if (newline < 0) body else body.substring(0, newline)
    val rid = ridLine.trim().trimEnd('\r')
    if (!RELAY_ID_RE.matches(rid)) return null
    val command = if (newline < 0) "" else body.substring(newline + 1).trimEnd('\r', '\n')
    if (command.isBlank()) return null
    return DebugCliRelayCommand(rid, command)
}

/**
 * 第 [attempt] 次重连的退避时长（毫秒）：指数增长、封顶 [DEBUG_RELAY_MAX_BACKOFF_MILLIS]。
 *
 * 纯函数（无随机抖动），便于确定性测试；调用方按需自行加抖动。
 */
fun relayBackoffMillis(attempt: Int): Long {
    val shift = attempt.coerceIn(0, 5)
    return (DEBUG_RELAY_BASE_BACKOFF_MILLIS shl shift).coerceAtMost(DEBUG_RELAY_MAX_BACKOFF_MILLIS)
}
