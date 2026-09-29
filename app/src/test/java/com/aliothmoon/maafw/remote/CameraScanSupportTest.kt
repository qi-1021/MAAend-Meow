package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.CameraScanSupport.Budget
import com.aliothmoon.maafw.remote.CameraScanSupport.Move
import com.aliothmoon.maafw.remote.CameraScanSupport.Param
import com.aliothmoon.maafw.remote.CameraScanSupport.Phase
import com.aliothmoon.maafw.remote.CameraScanSupport.Rect
import com.aliothmoon.maafw.remote.CameraScanSupport.Step
import com.aliothmoon.maafw.remote.CameraScanSupport.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CameraScanAction 纯逻辑测试（对齐上游 camerascan/action.go + path.go）。
 *
 * 重点钉住三件错了只表现为「扫不到目标」的事：
 *  1. 参数校验：`wait_nodes` 必填、`fallback_yaw_steps` 只认 4..72（缺省/显式 0 → 8）；
 *  2. 路径形状：九宫格 8 步 + 复位 1 步（不识别）+ 中/上/下三环，每环 1 步俯仰 + N 步右转；
 *  3. 每步移动前、后各识别一次；复位步不识别；找不到必须返回失败（不是 noop-success）。
 */
class CameraScanSupportTest {

    private fun step(yaw: Int, pitch: Int, phase: Phase) = Step(yaw, pitch, phase)

    // ───────────────────── 参数解析 ─────────────────────

    @Test
    fun `空串非法 JSON 或非对象顶层都判失败`() {
        assertNull(CameraScanSupport.parseParam(null))
        assertNull(CameraScanSupport.parseParam(""))
        assertNull(CameraScanSupport.parseParam("   "))
        assertNull(CameraScanSupport.parseParam("不是 json"))
        assertNull(CameraScanSupport.parseParam("[1,2]"))
        assertNull(CameraScanSupport.parseParam("\"CameraScanAction\""))
        assertNull(CameraScanSupport.parseParam("null"))
    }

    @Test
    fun `wait_nodes 必填且必须是非空字符串数组`() {
        assertNull(CameraScanSupport.parseParam("{}"))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":null}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":[]}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":"Node"}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":[1]}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["A",null]}"""))
    }

    @Test
    fun `最小合法参数使用全部默认值`() {
        val p = CameraScanSupport.parseParam("""{"wait_nodes":["TargetNode"]}""")
        assertEquals(Param.defaults(listOf("TargetNode")), p)
        assertEquals(false, p!!.aimTarget)
        assertEquals(8, p.fallbackYawSteps)
        assertEquals(CameraScanSupport.DEFAULT_MOVE_UP, p.moveUp)
        assertEquals(CameraScanSupport.DEFAULT_MOVE_DOWN, p.moveDown)
        assertEquals(CameraScanSupport.DEFAULT_MOVE_LEFT, p.moveLeft)
        assertEquals(CameraScanSupport.DEFAULT_MOVE_RIGHT, p.moveRight)
    }

    @Test
    fun `wait_nodes 保持声明顺序`() {
        val p = CameraScanSupport.parseParam("""{"wait_nodes":["B","A"]}""")
        assertEquals(listOf("B", "A"), p!!.waitNodes)
    }

    @Test
    fun `aim_target 解析为真且类型不对判失败`() {
        assertTrue(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"aim_target":true}""")!!.aimTarget)
        assertFalse(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"aim_target":false}""")!!.aimTarget)
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"aim_target":"true"}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"aim_target":1}"""))
    }

    @Test
    fun `fallback_yaw_steps 缺省或 null 或显式 0 都用默认 8`() {
        assertEquals(8, CameraScanSupport.parseParam("""{"wait_nodes":["N"]}""")!!.fallbackYawSteps)
        assertEquals(8, CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":null}""")!!.fallbackYawSteps)
        assertEquals(8, CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":0}""")!!.fallbackYawSteps)
    }

    @Test
    fun `fallback_yaw_steps 边界 4 与 72 合法`() {
        assertEquals(4, CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":4}""")!!.fallbackYawSteps)
        assertEquals(72, CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":72}""")!!.fallbackYawSteps)
    }

    @Test
    fun `fallback_yaw_steps 越界或类型不对判失败`() {
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":3}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":73}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":-1}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":8.5}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":"8"}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":true}"""))
        // 超过 Int 范围、又不可能落在 4..72 -> 失败（不能因 toInt 回绕而误收）
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"fallback_yaw_steps":4294967304}"""))
    }

    @Test
    fun `move 节点缺省 null 空串用默认 类型不对判失败`() {
        val defaults = CameraScanSupport.parseParam("""{"wait_nodes":["N"]}""")!!
        assertEquals(CameraScanSupport.DEFAULT_MOVE_UP, CameraScanSupport.parseParam("""{"wait_nodes":["N"],"move_up":null}""")!!.moveUp)
        assertEquals(CameraScanSupport.DEFAULT_MOVE_UP, CameraScanSupport.parseParam("""{"wait_nodes":["N"],"move_up":""}""")!!.moveUp)
        assertEquals(
            defaults.copy(moveRight = "CustomRight"),
            CameraScanSupport.parseParam("""{"wait_nodes":["N"],"move_right":"CustomRight"}"""),
        )
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"move_up":1}"""))
        assertNull(CameraScanSupport.parseParam("""{"wait_nodes":["N"],"move_left":false}"""))
    }

    // ───────────────────── 扫描路径 ─────────────────────

    @Test
    fun `九宫格前 8 步逐条对齐上游`() {
        val path = CameraScanSupport.buildPath(8)
        assertEquals(
            listOf(
                step(0, -1, Phase.NINE_GRID),
                step(1, 0, Phase.NINE_GRID),
                step(0, 1, Phase.NINE_GRID),
                step(0, 1, Phase.NINE_GRID),
                step(-1, 0, Phase.NINE_GRID),
                step(-1, 0, Phase.NINE_GRID),
                step(0, -1, Phase.NINE_GRID),
                step(0, -1, Phase.NINE_GRID),
            ),
            path.take(8),
        )
    }

    @Test
    fun `复位步取九宫格净位移的反向且不识别`() {
        val reset = CameraScanSupport.buildPath(8)[8]
        // 九宫格净位移 yaw=-1 / pitch=-1 -> 复位 = (+1, +1)
        assertEquals(step(1, 1, Phase.RESET), reset)
        assertFalse(reset.needsRecognition)
    }

    @Test
    fun `三个俯仰环形状与顺序正确`() {
        val path = CameraScanSupport.buildPath(4)
        // 跳过 8 步九宫格 + 1 步复位
        val rings = path.drop(9)
        assertEquals(3 * (1 + 4), rings.size)

        assertEquals(step(0, 0, Phase.FALLBACK_MID), rings[0])
        assertEquals(step(0, -1, Phase.FALLBACK_UP), rings[5])
        assertEquals(step(0, 2, Phase.FALLBACK_DOWN), rings[10])

        // 每环首步是俯仰、其后 N 步都是右转
        for (offset in listOf(0, 5, 10)) {
            for (i in 1..4) {
                assertEquals(step(1, 0, rings[offset + i].phase), rings[offset + i])
            }
        }
    }

    @Test
    fun `总步数等于 8 加 1 加 3 环`() {
        assertEquals(36, CameraScanSupport.stepCount(8))
        assertEquals(36, CameraScanSupport.buildPath(8).size)
        // 8 + 1 + 3*(1+4) = 24
        assertEquals(24, CameraScanSupport.stepCount(4))
        assertEquals(24, CameraScanSupport.buildPath(4).size)
        // 8 + 1 + 3*(1+72) = 228
        assertEquals(228, CameraScanSupport.stepCount(72))
    }

    @Test
    fun `只有复位步不识别`() {
        val path = CameraScanSupport.buildPath(8)
        assertEquals(1, path.count { !it.needsRecognition })
        assertEquals(Phase.RESET, path.single { !it.needsRecognition }.phase)
    }

    @Test
    fun `每个环的水平步数等于 fallback_yaw_steps`() {
        assertEquals(8, CameraScanSupport.yawStepsPerRing(8))
        val path = CameraScanSupport.buildPath(6)
        val yawPerRing = path.drop(9).chunked(7).map { ring -> ring.count { it.yawDelta == 1 } }
        assertEquals(listOf(6, 6, 6), yawPerRing)
    }

    // ───────────────────── 移动方向与像素量 ─────────────────────

    @Test
    fun `movesFor 先俯仰后水平并对齐方向`() {
        assertEquals(listOf(Move.UP), CameraScanSupport.movesFor(step(0, -1, Phase.NINE_GRID)))
        assertEquals(listOf(Move.DOWN), CameraScanSupport.movesFor(step(0, 1, Phase.NINE_GRID)))
        assertEquals(listOf(Move.RIGHT), CameraScanSupport.movesFor(step(1, 0, Phase.NINE_GRID)))
        assertEquals(listOf(Move.LEFT), CameraScanSupport.movesFor(step(-1, 0, Phase.NINE_GRID)))
        assertEquals(
            listOf(Move.DOWN, Move.DOWN, Move.LEFT, Move.LEFT),
            CameraScanSupport.movesFor(step(-2, 2, Phase.FALLBACK_DOWN)),
        )
        assertEquals(emptyList<Move>(), CameraScanSupport.movesFor(step(0, 0, Phase.FALLBACK_MID)))
    }

    @Test
    fun `像素增量对齐上游默认移动节点 上下 240 左右 160`() {
        assertEquals(0 to -240, CameraScanSupport.pixelDelta(Move.UP))
        assertEquals(0 to 240, CameraScanSupport.pixelDelta(Move.DOWN))
        assertEquals(-160 to 0, CameraScanSupport.pixelDelta(Move.LEFT))
        assertEquals(160 to 0, CameraScanSupport.pixelDelta(Move.RIGHT))
    }

    // ───────────────────── 命中对准 ─────────────────────

    @Test
    fun `空框判失败`() {
        assertNull(CameraScanSupport.aimDelta(Rect(100, 100, 0, 20)))
        assertNull(CameraScanSupport.aimDelta(Rect(100, 100, 20, 0)))
        assertNull(CameraScanSupport.aimDelta(Rect(100, 100, -5, 10)))
    }

    @Test
    fun `框中心正好在屏幕中心时偏移为零`() {
        // 640-40=600, 360-40=320 -> 中心 (640,360)
        assertEquals(0 to 0, CameraScanSupport.aimDelta(Rect(600, 320, 80, 80)))
    }

    @Test
    fun `偏移等于目标中心减屏幕中心`() {
        // 中心 (1040,360) -> dx=400
        assertEquals(400 to 0, CameraScanSupport.aimDelta(Rect(1000, 320, 80, 80)))
        // 中心 (640,440) -> dy=80
        assertEquals(0 to 80, CameraScanSupport.aimDelta(Rect(600, 400, 80, 80)))
    }

    @Test
    fun `偏移 clamp 到屏幕范围`() {
        // 中心远超右下 -> dx=639, dy=359
        assertEquals(639 to 359, CameraScanSupport.aimDelta(Rect(5000, 5000, 100, 100)))
        // 中心远超左上 -> dx=-640, dy=-360
        assertEquals(-640 to -360, CameraScanSupport.aimDelta(Rect(-5000, -5000, 100, 100)))
    }

    // ───────────────────── 超时与终止 ─────────────────────

    @Test
    fun `预算总步数等于路径长度 超时等于步数乘单步`() {
        assertEquals(Budget(36, 36 * 4_000L), CameraScanSupport.budget(8))
        assertNotNull(CameraScanSupport.budget(4, 1_000L))
        // 8 + 1 + 3*(1+4) = 24 步
        assertEquals(Budget(24, 24_000L), CameraScanSupport.budget(4, 1_000L))
    }

    @Test
    fun `未到步数上限未超时未停止则继续`() {
        val b = CameraScanSupport.budget(8)
        assertEquals(StopReason.NONE, CameraScanSupport.stopReason(0, b, stopping = false, elapsedMs = 0))
        assertEquals(StopReason.NONE, CameraScanSupport.stopReason(35, b, stopping = false, elapsedMs = b.timeoutMs))
    }

    @Test
    fun `停止优先于耗尽与超时`() {
        val b = CameraScanSupport.budget(8)
        assertEquals(StopReason.STOPPING, CameraScanSupport.stopReason(36, b, stopping = true, elapsedMs = Long.MAX_VALUE))
    }

    @Test
    fun `步数耗尽判放弃`() {
        val b = CameraScanSupport.budget(8)
        assertEquals(StopReason.EXHAUSTED, CameraScanSupport.stopReason(36, b, stopping = false, elapsedMs = 0))
    }

    @Test
    fun `超时判放弃`() {
        val b = CameraScanSupport.budget(8)
        assertEquals(StopReason.TIMEOUT, CameraScanSupport.stopReason(0, b, stopping = false, elapsedMs = b.timeoutMs + 1))
    }

    // ───────────────────── 自定义移动节点告警 ─────────────────────

    @Test
    fun `默认移动节点不产生告警`() {
        assertNull(CameraScanSupport.customMoveWarn(Param.defaults(listOf("N"))))
    }

    @Test
    fun `自定义移动节点会明确告警而非静默忽略`() {
        val warn = CameraScanSupport.customMoveWarn(Param.defaults(listOf("N")).copy(moveUp = "MyUp"))
        assertTrue(warn != null && warn.contains("move_up=MyUp"))
    }
}
