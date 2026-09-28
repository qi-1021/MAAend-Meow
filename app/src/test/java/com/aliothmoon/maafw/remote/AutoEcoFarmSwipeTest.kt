package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.AutoEcoFarmSwipe.Param
import com.aliothmoon.maafw.remote.AutoEcoFarmSwipe.Rect
import com.aliothmoon.maafw.remote.AutoEcoFarmSwipe.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * autoEcoFarmCalculateSwipeTarget / ResetSwipeState 测试（对齐上游 scaletarget.go + state.go）。
 *
 * 重点钉住三件错了只表现为「视角转歪/来回抖」的事：
 *  1. 目标点用「屏幕中心 + 目标中心偏移 * 比例」，比例默认 0.5；
 *  2. 有历史状态时，两个轴的比例都改用历史值（忽略本次参数），越界才乘 0.9 衰减；
 *  3. 越界判定是「相对屏幕中心的符号翻转」，且任一为 0 时不判。
 */
class AutoEcoFarmSwipeTest {

    // 屏幕 1280x720 -> 中心 (640, 360)
    private val screenW = 1280
    private val screenH = 720

    private fun rect(x: Int, y: Int, w: Int, h: Int) = Rect(x, y, w, h)

    private fun run(screenW: Int, screenH: Int, roi: Rect, param: Param, last: State? = null) =
        AutoEcoFarmSwipe.computeTarget(screenW, screenH, roi, param, last)

    // ───────────────────── 参数解析 ─────────────────────

    @Test
    fun `空或缺失参数用默认 0_5`() {
        assertEquals(Param.DEFAULT, AutoEcoFarmSwipe.parseParam(null))
        assertEquals(Param.DEFAULT, AutoEcoFarmSwipe.parseParam(""))
        assertEquals(Param.DEFAULT, AutoEcoFarmSwipe.parseParam("{}"))
    }

    @Test
    fun `单个字段存在时另一个用默认`() {
        val p = AutoEcoFarmSwipe.parseParam("""{"xStepRatio":0.2}""")
        assertEquals(Param(0.2, 0.5), p)
    }

    @Test
    fun `JSON null 对非指针类型无效果`() {
        val p = AutoEcoFarmSwipe.parseParam("""{"xStepRatio":null}""")
        assertEquals(Param.DEFAULT, p)
    }

    @Test
    fun `类型不对或非法 JSON 判失败`() {
        assertNull(AutoEcoFarmSwipe.parseParam("""{"xStepRatio":"0.2"}"""))
        assertNull(AutoEcoFarmSwipe.parseParam("不是 json"))
        assertNull(AutoEcoFarmSwipe.parseParam("[1,2]"))
    }

    // ───────────────────── 目标计算 ─────────────────────

    @Test
    fun `默认比例取中心到目标中心的一半`() {
        // 目标中心 (1000,360) 相对屏幕中心 (640,360) 偏 +360 -> 半程 = 640+180=820
        val result = run(screenW, screenH, rect(960, 320, 80, 80), Param.DEFAULT)
        assertEquals(Rect(820, 360, 1, 1), result.box)
    }

    @Test
    fun `有历史状态时忽略本次参数并沿用历史比例`() {
        // 上一次比例 0.3/0.4，本次参数 0.9 -> 必须仍用 0.3/0.4
        val last = State(rect(960, 320, 80, 80), 0.3, 0.4)
        // 当前目标中心 (900,400)：dx=260 dy=40 -> x=640+260*0.3=718, y=360+40*0.4=376
        val result = run(screenW, screenH, rect(860, 360, 80, 80), Param(0.9, 0.9), last)
        assertEquals(Rect(718, 376, 1, 1), result.box)
    }

    @Test
    fun `跨越屏幕中心时该轴比例乘 0_9 衰减`() {
        // 上次在中心左侧 (dx<0)，本次在右侧 (dx>0) -> X 衰减：0.5*0.9=0.45
        val last = State(rect(360, 320, 80, 80), 0.5, 0.5) // 中心 400, dx=-240
        val result = run(screenW, screenH, rect(960, 320, 80, 80), Param.DEFAULT, last) // 中心 1000, dx=360
        assertEquals(0.45, result.state.xStepRatio, 1e-9)
        assertEquals(0.5, result.state.yStepRatio, 1e-9) // Y 没跨，不变
        // targetX = 640 + 360*0.45 = 802
        assertEquals(Rect(802, 360, 1, 1), result.box)
    }

    @Test
    fun `同一轴任一位移为零时不判越界`() {
        // lastDx == 0（目标正好在中心）-> 不触发衰减，沿用历史比例
        val last = State(rect(600, 320, 80, 80), 0.5, 0.5) // 中心 640 == screenCenterX
        val result = run(screenW, screenH, rect(960, 320, 80, 80), Param.DEFAULT, last)
        assertEquals(0.5, result.state.xStepRatio, 1e-9)
    }

    @Test
    fun `比例被夹取到 0_1 到 1_0`() {
        val hi = run(screenW, screenH, rect(960, 320, 80, 80), Param(2.0, 3.0))
        assertEquals(1.0, hi.state.xStepRatio, 1e-9)
        assertEquals(1.0, hi.state.yStepRatio, 1e-9)

        val lo = run(screenW, screenH, rect(960, 320, 80, 80), Param(-4.0, 0.0))
        assertEquals(0.1, lo.state.xStepRatio, 1e-9)
        assertEquals(0.1, lo.state.yStepRatio, 1e-9)
    }

    @Test
    fun `位移为负时 toInt 向零截断`() {
        // 屏幕 100x100 -> 中心 50；目标 ROI 中心 0.5 -> dx=-49.5，比例 1.0 -> 0.5 -> 截断为 0
        val result = run(100, 100, rect(0, 0, 1, 1), Param(1.0, 1.0))
        assertEquals(0, result.box.x)
    }

    // ───────────────────── 全局状态副作用 ─────────────────────

    @Test
    fun `run 会保存夹取后的状态并在下次生效`() {
        AutoEcoFarmSwipe.resetState()
        // 第一次：目标在右侧，参数 2.0 -> 夹取成 1.0 后保存
        AutoEcoFarmSwipe.run(rect(960, 320, 80, 80), Param(2.0, 2.0), screenW, screenH)
        assertEquals(1.0, AutoEcoFarmSwipe.lastState()!!.xStepRatio, 1e-9)

        // 第二次：即使传 0.1，也应沿用历史 1.0（未跨中心不衰减）
        val box = AutoEcoFarmSwipe.run(rect(800, 320, 80, 80), Param(0.1, 0.1), screenW, screenH)
        assertEquals(1.0, AutoEcoFarmSwipe.lastState()!!.xStepRatio, 1e-9)
        // 目标中心 840 -> 640 + (840-640)*1.0 = 840
        assertEquals(840, box.x)
    }

    @Test
    fun `resetState 清空历史`() {
        AutoEcoFarmSwipe.resetState()
        AutoEcoFarmSwipe.run(rect(960, 320, 80, 80), Param.DEFAULT, screenW, screenH)
        assertTrue(AutoEcoFarmSwipe.lastState() != null)
        AutoEcoFarmSwipe.resetState()
        assertNull(AutoEcoFarmSwipe.lastState())
    }
}
