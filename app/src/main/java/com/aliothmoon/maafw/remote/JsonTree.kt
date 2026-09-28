package com.aliothmoon.maafw.remote

/**
 * 极小的 JSON 树序列化工具。
 *
 * BetterSliding 的覆盖构造层（[BetterSlidingOverrides]）产出的是 `Map<String, Any?>`，
 * 交给 MaaFramework 前必须变成 JSON 字符串。这里刻意不引入 kotlinx.serialization：
 * 纯逻辑层保持无依赖，就能在本机直接跑测试。
 *
 * 支持的叶子类型：null / Boolean / Number / String；
 * 容器：Map（键按插入顺序）/ Iterable。
 * 其它类型一律按字符串处理（上游 `json.Marshal` 对不支持的类型会报错，
 * 但这里所有产出都来自我们自己的构造器，只有上面这几种）。
 */
object JsonTree {

    fun toJson(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(number(value))
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k?.toString() ?: "null")
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }

            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }

            is Array<*> -> write(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    /** 整数不带小数点；NaN/Infinity 在 JSON 里不存在，退化成 null。 */
    private fun number(value: Number): String = when (value) {
        is Double -> if (value.isFinite() && value == Math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else if (value.isFinite()) {
            value.toString()
        } else {
            "null"
        }

        is Float -> number(value.toDouble())
        is Long, is Int, is Short, is Byte -> value.toString()
        else -> value.toString()
    }

    /** 按 JSON 规范转义，控制字符走 \\u00XX。 */
    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }
}
