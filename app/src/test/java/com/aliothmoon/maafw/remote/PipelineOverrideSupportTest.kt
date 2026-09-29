package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PipelineOverride` / `PipelineOverrideAction` 纯逻辑测试
 * （对齐上游 `common/pipelineoverride/action.go`）。
 *
 * 重点钉住最容易犯错的三处：
 *  1. 默认 strip 顶层 `next`，只有 `allow_next=true` 才保留；
 *  2. `strict` 只在 `allow_next=false` 时生效（含 next 即失败），allow_next=true 时被忽略；
 *  3. **解析失败必须显式失败**，绝不能 warn 后当成功。
 */
class PipelineOverrideSupportTest {

    private fun apply(raw: String): PipelineOverrideSupport.Outcome.Apply {
        val outcome = PipelineOverrideSupport.parse(raw)
        assertTrue("期望 Apply，实际 $outcome", outcome is PipelineOverrideSupport.Outcome.Apply)
        return outcome as PipelineOverrideSupport.Outcome.Apply
    }

    private fun rejected(raw: String?): PipelineOverrideSupport.Outcome.Rejected {
        val outcome = PipelineOverrideSupport.parse(raw)
        assertTrue("期望 Rejected，实际 $outcome", outcome is PipelineOverrideSupport.Outcome.Rejected)
        return outcome as PipelineOverrideSupport.Outcome.Rejected
    }

    // ── 参数级失败：必须 Rejected ──

    @Test
    fun `空参数与 null 被拒绝`() {
        rejected("")
        rejected("   ")
        rejected(null)
    }

    @Test
    fun `非法 JSON 被拒绝`() {
        rejected("{ not json")
        rejected("[1,2,3]")
        rejected("\"just a string\"")
    }

    @Test
    fun `缺少 patch 字段被拒绝`() {
        rejected("""{"allow_next":true}""")
        rejected("""{"strict":false}""")
    }

    @Test
    fun `patch 非对象或为空被拒绝`() {
        rejected("""{"patch":[]}""")
        rejected("""{"patch":"x"}""")
        rejected("""{"patch":null}""")
        rejected("""{"patch":{}}""")
    }

    @Test
    fun `节点值不是对象被拒绝`() {
        rejected("""{"patch":{"N":"x"}}""")
        rejected("""{"patch":{"N":[1]}}""")
    }

    @Test
    fun `空节点名被拒绝`() {
        rejected("""{"patch":{"  ":{"enabled":true}}}""")
    }

    @Test
    fun `开关类型错误被拒绝`() {
        rejected("""{"patch":{"N":{"enabled":true}},"allow_next":"true"}""")
        rejected("""{"patch":{"N":{"enabled":true}},"strict":1}""")
        rejected("""{"patch":{"N":{"enabled":true}},"resource_override":"yes"}""")
    }

    // ── strip next ──

    @Test
    fun `默认移除顶层 next 并记录节点`() {
        val r = apply(
            """
            {"patch":{
              "NodeA":{"enabled":true,"next":["B","C"]},
              "NodeB":{"expected":["x"]}
            }}
            """.trimIndent(),
        )
        assertFalse(r.allowNext)
        assertEquals(listOf("NodeA"), r.strippedNextNodes)
        assertEquals(
            mapOf("NodeA" to mapOf("enabled" to true), "NodeB" to mapOf("expected" to listOf("x"))),
            r.cleanPatch,
        )
    }

    @Test
    fun `allow_next=true 保留 next 且不记录 strip`() {
        val r = apply(
            """{"patch":{"NodeA":{"next":["B"]}},"allow_next":true}""",
        )
        assertTrue(r.allowNext)
        assertTrue(r.strippedNextNodes.isEmpty())
        assertEquals(mapOf("NodeA" to mapOf("next" to listOf("B"))), r.cleanPatch)
    }

    @Test
    fun `嵌套的同名 next 不受影响`() {
        val r = apply(
            """{"patch":{"NodeA":{"attach":{"next":["B"]},"next":["C"]}}}""",
        )
        // 只删顶层 next，attach 里的 next 保留
        assertEquals(
            mapOf("NodeA" to mapOf("attach" to mapOf("next" to listOf("B")))),
            r.cleanPatch,
        )
        assertEquals(listOf("NodeA"), r.strippedNextNodes)
    }

    @Test
    fun `next 为 null 也算存在并被移除`() {
        val r = apply("""{"patch":{"NodeA":{"next":null,"enabled":true}}}""")
        assertEquals(listOf("NodeA"), r.strippedNextNodes)
        assertEquals(mapOf("NodeA" to mapOf("enabled" to true)), r.cleanPatch)
    }

    @Test
    fun `多个节点 strip 按 patch 顺序记录`() {
        val r = apply(
            """{"patch":{"A":{"next":["x"]},"B":{"enabled":true},"C":{"next":[]}}}""",
        )
        assertEquals(listOf("A", "C"), r.strippedNextNodes)
    }

    // ── strict ──

    @Test
    fun `strict 且 allow_next=false 时含 next 直接失败`() {
        val r = rejected(
            """{"patch":{"NodeA":{"next":["B"]}},"allow_next":false,"strict":true}""",
        )
        assertTrue(r.reason.contains("next"))
        assertTrue(r.reason.contains("NodeA"))
    }

    @Test
    fun `strict 但不含 next 正常应用`() {
        val r = apply("""{"patch":{"NodeA":{"enabled":false}},"strict":true}""")
        assertTrue(r.strictRequested)
        assertEquals(mapOf("NodeA" to mapOf("enabled" to false)), r.cleanPatch)
        assertTrue(r.strippedNextNodes.isEmpty())
    }

    @Test
    fun `strict 默认 false 时含 next 只移除不失败`() {
        val r = apply("""{"patch":{"NodeA":{"next":["B"]}},"strict":false}""")
        assertFalse(r.strictRequested)
        assertEquals(listOf("NodeA"), r.strippedNextNodes)
    }

    @Test
    fun `allow_next=true 时 strict 被忽略且保留 next`() {
        val r = apply(
            """{"patch":{"NodeA":{"next":["B"]}},"allow_next":true,"strict":true}""",
        )
        assertTrue(r.allowNext)
        assertTrue(r.strictRequested)
        assertEquals(mapOf("NodeA" to mapOf("next" to listOf("B"))), r.cleanPatch)
    }

    // ── resource_override ──

    @Test
    fun `resource_override 默认 false 显式为 true 被解析`() {
        assertFalse(apply("""{"patch":{"N":{"enabled":true}}}""").resourceOverride)
        assertTrue(
            apply("""{"patch":{"N":{"enabled":true}},"resource_override":true}""").resourceOverride,
        )
    }

    // ── 缺省 / null 开关 ──

    @Test
    fun `JSON null 开关视同缺省`() {
        val r = apply(
            """{"patch":{"N":{"enabled":true}},"allow_next":null,"strict":null,"resource_override":null}""",
        )
        assertFalse(r.allowNext)
        assertFalse(r.strictRequested)
        assertFalse(r.resourceOverride)
    }

    // ── 非 next 字段原样保留 ──

    @Test
    fun `非 next 字段原样保留`() {
        val r = apply(
            """
            {"patch":{"NodeA":{
              "enabled":true,
              "expected":["员养成","Operator Progression"],
              "recognition":{"param":{"index":4}}
            }}}
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                "NodeA" to mapOf(
                    "enabled" to true,
                    "expected" to listOf("员养成", "Operator Progression"),
                    "recognition" to mapOf("param" to mapOf("index" to 4L)),
                ),
            ),
            r.cleanPatch,
        )
    }
}
