package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FailureCollector*` 纯逻辑测试（对齐上游 failurecollector/action.go + collector.go）。
 *
 * 重点锁住此前 noop 静默丢掉的语义：失败会被记录、Finish 有失败要判失败、失败表按 key
 * 隔离且 Finish 后删除、以及「目标节点显式禁用则跳过」的放行条件。
 */
class FailureCollectorSupportTest {

    // ───────────────────────── 参数解析 ─────────────────────────

    @Test
    fun `parseParam 解析并 trim 四个字段`() {
        assertEquals(
            FailureCollectorSupport.ActionParam(
                key = "AutoCollect",
                task = "AutoCollectRoute1Start",
                recoveryTask = "EnvRecover",
                failureTask = "AutoCollectRoute1Failed",
            ),
            FailureCollectorSupport.parseParam(
                mapOf(
                    "key" to " AutoCollect ",
                    "task" to " AutoCollectRoute1Start ",
                    "recovery_task" to " EnvRecover ",
                    "failure_task" to " AutoCollectRoute1Failed ",
                ),
            ),
        )
    }

    @Test
    fun `parseParam 可选字段缺省为空串`() {
        val p = FailureCollectorSupport.parseParam(mapOf("key" to "K"))
        assertEquals(FailureCollectorSupport.ActionParam(key = "K"), p)
        assertEquals("", p?.recoveryTask)
        assertEquals("", p?.failureTask)
        assertEquals("", p?.task)
    }

    @Test
    fun `parseParam 缺 key 或非 map 返回 null`() {
        assertNull(FailureCollectorSupport.parseParam(null))
        assertNull(FailureCollectorSupport.parseParam("not-a-map"))
        assertNull(FailureCollectorSupport.parseParam(emptyMap<String, Any?>()))
        assertNull(FailureCollectorSupport.parseParam(mapOf("key" to "   ")))
        assertNull(FailureCollectorSupport.parseParam(mapOf("task" to "X")))
    }

    // ───────────────────────── 失败表 ─────────────────────────

    @Test
    fun `record 累积且按 key 隔离`() {
        val s = FailureCollectorSupport.Session()
        s.record("A", "TaskA1")
        s.record("A", "TaskA2")
        s.record("A", "TaskA1")
        s.record("B", "TaskB1")
        assertEquals(3, s.failureCount("A"))
        assertEquals(1, s.failureCount("B"))
        assertEquals(listOf("TaskA1", "TaskA2", "TaskA1"), s.finish("A"))
    }

    @Test
    fun `reset 清空指定 key 不影响其他 key`() {
        val s = FailureCollectorSupport.Session()
        s.record("A", "TaskA")
        s.record("B", "TaskB")
        s.reset("A")
        assertEquals(0, s.failureCount("A"))
        assertEquals(1, s.failureCount("B"))
    }

    @Test
    fun `finish 返回记录并删除 key 再次 finish 为空`() {
        val s = FailureCollectorSupport.Session()
        s.record("A", "TaskA")
        assertEquals(listOf("TaskA"), s.finish("A"))
        assertEquals(0, s.failureCount("A"))
        assertTrue(s.finish("A").isEmpty())
    }

    @Test
    fun `finish 后重新 record 从空开始`() {
        val s = FailureCollectorSupport.Session()
        s.record("A", "TaskA1")
        s.finish("A")
        s.record("A", "TaskA2")
        assertEquals(listOf("TaskA2"), s.finish("A"))
    }

    @Test
    fun `从未记录的 key finish 返回空`() {
        val s = FailureCollectorSupport.Session()
        assertTrue(s.finish("never").isEmpty())
    }

    @Test
    fun `resetAll 清空所有 key`() {
        val s = FailureCollectorSupport.Session()
        s.record("A", "TaskA")
        s.record("B", "TaskB")
        s.resetAll()
        assertEquals(0, s.failureCount("A"))
        assertEquals(0, s.failureCount("B"))
        assertTrue(s.finish("A").isEmpty())
    }

    // ───────────────────────── Finish 返回判定 ─────────────────────────

    @Test
    fun `finishSucceeds 仅当没有失败时为真`() {
        assertTrue(FailureCollectorSupport.finishSucceeds(emptyList()))
        assertFalse(FailureCollectorSupport.finishSucceeds(listOf("TaskA")))
        assertFalse(FailureCollectorSupport.finishSucceeds(listOf("TaskA", "TaskA")))
    }

    // ───────────────────────── 目标节点禁用判定 ─────────────────────────

    @Test
    fun `isNodeDisabled 只认显式 enabled=false`() {
        assertTrue(FailureCollectorSupport.isNodeDisabled("""{"enabled":false}"""))
        assertFalse(FailureCollectorSupport.isNodeDisabled("""{"enabled":true}"""))
        assertFalse(FailureCollectorSupport.isNodeDisabled("""{"action":"DoNothing"}"""))
        assertFalse(FailureCollectorSupport.isNodeDisabled("""{"enabled":"false"}"""))
        assertFalse(FailureCollectorSupport.isNodeDisabled("not json"))
        assertFalse(FailureCollectorSupport.isNodeDisabled(null))
        assertFalse(FailureCollectorSupport.isNodeDisabled(""))
    }
}
