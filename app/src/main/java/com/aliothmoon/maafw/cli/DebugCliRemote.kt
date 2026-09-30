package com.aliothmoon.maafw.cli

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/**
 * 远程调试的**纯逻辑层**：令牌生成与校验、连接来源判定（是否回环）、鉴权限速。
 *
 * 与 [DebugCliSupport] 同样的理由：这些东西不依赖 socket / Android，能在
 * [scripts/verify_pure_logic.sh] 里本机跑断言。真正的绑定、读写与线程在 [DebugCliServer]。
 *
 * 安全约定（写死在这里，服务端与测试共用）：
 *  - 回环连接免令牌，现有 `adb forward` + `nc` 工作流不坏；
 *  - 非回环连接在远程调试开启时必须先 `auth <token>`，否则除 `auth` / `help` 外一律拒绝；
 *  - 来源地址取不到一律按**远程**处理（fail-closed），绝不因为解析失败就放行。
 */

/** 连接来源。回环免令牌；远程必须 `auth`。 */
enum class DebugCliConnection { LOOPBACK, REMOTE }

/** 令牌长度：32 位大小写字母 + 数字，约 190 bit 熵，足够抗暴力。 */
const val DEBUG_CLI_TOKEN_LENGTH = 32

/** 令牌字符表：URL 安全、无歧义字符（省掉 `+` `/` `=`）。 */
private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

/**
 * 判断一个来源地址是否回环。
 *
 * 认 IPv4 `127.0.0.0/8`、IPv6 `::1`，以及 v4-mapped 形态 `::ffff:127.x.x.x`。
 * null / 空白 / 解析不出的一律返回 false（当成远程），这是刻意的 fail-closed。
 */
fun debugCliIsLoopback(address: String?): Boolean {
    val host = address?.trim().orEmpty()
    if (host.isEmpty()) return false
    // 去掉 IPv6 zone id（fe80::1%wlan0）后统一小写
    val normalized = host.substringBefore('%').lowercase()
    if (normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
    if (normalized.startsWith("::ffff:")) {
        return debugCliIsLoopback(normalized.removePrefix("::ffff:"))
    }
    if (normalized.startsWith("127.")) {
        val parts = normalized.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
    }
    return false
}

/** 由给定随机源生成一个令牌；测试传固定 seed，生产传 [SecureRandom]。 */
fun debugCliGenerateToken(random: Random, length: Int = DEBUG_CLI_TOKEN_LENGTH): String {
    require(length > 0) { "token length must be positive" }
    val builder = StringBuilder(length)
    repeat(length) { builder.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
    return builder.toString()
}

/** 生产用：密码学安全随机令牌。 */
fun debugCliNewToken(): String = debugCliGenerateToken(SecureRandom())

/**
 * 常量时间比较令牌，避免逐字符短路泄露前缀。
 *
 * 长度不同时 [MessageDigest.isEqual] 也会走完整比较（其实现按 max 长度补齐），但空候选一律直接否掉，
 * 免得「期望非空、候选空」被误判。
 */
fun debugCliTokensMatch(expected: String?, candidate: String?): Boolean {
    if (expected.isNullOrEmpty() || candidate == null) return false
    return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
}

/**
 * 鉴权限速：连续失败 [maxAttempts] 次后锁定 [lockoutMillis]，防暴力尝试。
 *
 * 纯状态机，时间由调用方传入（[now] 毫秒），本机可确定性地测。
 * 限速是**全局**的而不是逐连接：攻击者换连接绕过逐连接计数就失去意义。
 */
class DebugCliAuthThrottle(
    private val maxAttempts: Int = 5,
    private val lockoutMillis: Long = 30_000L,
) {
    private var failures = 0
    private var lockedUntil = 0L

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(lockoutMillis > 0) { "lockoutMillis must be positive" }
    }

    fun isLocked(now: Long): Boolean = now < lockedUntil

    fun lockRemainingMillis(now: Long): Long = (lockedUntil - now).coerceAtLeast(0L)

    /** 记一次失败；返回「记完之后是否处于锁定」。锁定期内继续失败不重置倒计时。 */
    fun onFailure(now: Long): Boolean {
        if (isLocked(now)) return true
        failures++
        if (failures >= maxAttempts) {
            failures = 0
            lockedUntil = now + lockoutMillis
            return true
        }
        return false
    }

    /** 成功即清零失败计数与锁定。 */
    fun onSuccess() {
        failures = 0
        lockedUntil = 0L
    }
}
