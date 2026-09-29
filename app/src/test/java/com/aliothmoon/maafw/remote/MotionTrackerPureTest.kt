package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MotionTracker 纯逻辑测试（对齐 `MotionTracker.cpp` 全文）。
 *
 * 时间用单调秒数；重点覆盖零速度死区、丢失计数边界、速度 EMA 的 dt/score 门槛、
 * 预测超时与搜索窗截断。
 */
class MotionTrackerPureTest {

    private fun pos(x: Double, y: Double, score: Double = 1.0) = MapPosition(zoneId = "Z", x = x, y = y, score = score)

    @Test
    fun `构造后未追踪且丢失计数为上限加一`() {
        val t = MotionTracker()
        assertFalse(t.isTracking(0))
        assertFalse(t.isTracking(3))
        assertEquals(MAX_LOST_TRACKING_COUNT + 1, t.getLostCount())
        assertNull(t.getLastPos())
        assertEquals(0.0, t.getPredictedX(1.0), 0.0)
        assertEquals(0.0, t.getPredictedY(1.0), 0.0)
    }

    @Test
    fun `update 记录位置并清零丢失计数`() {
        val t = MotionTracker()
        t.update(pos(10.0, 20.0), now = 0.0)
        assertEquals(pos(10.0, 20.0), t.getLastPos())
        assertEquals(0, t.getLostCount())
        assertTrue(t.isTracking(0))
        assertEquals(10.0, t.getPredictedX(0.0), 0.0)
        assertEquals(20.0, t.getPredictedY(0.0), 0.0)
    }

    @Test
    fun `速度 EMA 按 alpha 平滑并可用于外推`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 0.1) // rawVx=100, alpha=0.5 -> 50
        assertEquals(50.0, t.getVelocityX(), 1e-9)
        assertEquals(0.0, t.getVelocityY(), 1e-9)
        // 上一帧在 t=0.1、x=10，预测 t=0.2：10 + 50*0.1
        assertEquals(15.0, t.getPredictedX(0.2), 1e-9)
    }

    @Test
    fun `位移小于死区时速度清零`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 0.1)
        assertEquals(50.0, t.getVelocityX(), 1e-9)
        // 从 x=10 只挪 0.1（< kVelocityDeadband=0.25）
        t.update(pos(10.1, 0.0), now = 0.2)
        assertEquals(0.0, t.getVelocityX(), 1e-9)
        assertEquals(0.0, t.getVelocityY(), 1e-9)
    }

    @Test
    fun `dt 不大于 0_016 或达到预测上限时都不更新速度`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 0.016) // 不 > 0.016
        assertEquals(0.0, t.getVelocityX(), 1e-9)

        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 5.0) // 不 < maxDtForPrediction
        assertEquals(0.0, t.getVelocityX(), 1e-9)

        // 4.9 合法
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(4.9, 0.0), now = 4.9)
        assertEquals(0.5, t.getVelocityX(), 1e-9) // rawV=1, alpha 0.5
    }

    @Test
    fun `匹配分低于门槛时不更新速度`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0, score = 0.69), now = 0.1)
        assertEquals(0.0, t.getVelocityX(), 1e-9)
        // 恰好 0.70 达标（>=）
        t.update(pos(10.0, 0.0), now = 0.1)
        t.update(pos(20.0, 0.0, score = 0.70), now = 0.2)
        assertEquals(50.0, t.getVelocityX(), 1e-9)
    }

    @Test
    fun `上一帧处于丢失态时 update 不估速但仍清零`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 0.1)
        assertEquals(50.0, t.getVelocityX(), 1e-9)
        t.markLost()
        assertEquals(1, t.getLostCount())
        t.update(pos(20.0, 0.0), now = 0.2)
        // 丢失态下跳过，速度保持 50 而不是 75
        assertEquals(50.0, t.getVelocityX(), 1e-9)
        assertEquals(0, t.getLostCount())
        assertEquals(pos(20.0, 0.0), t.getLastPos())
    }

    @Test
    fun `markLost 累加且 isTracking 是上界包含`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.markLost(3)
        assertEquals(3, t.getLostCount())
        assertTrue(t.isTracking(3))
        t.markLost() // +1 -> 4
        assertEquals(4, t.getLostCount())
        assertFalse(t.isTracking(3))
        assertTrue(t.isTracking(4))
    }

    @Test
    fun `forceLost 置大丢失计数并清位置`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.forceLost()
        assertEquals(MAX_LOST_TRACKING_COUNT + 100, t.getLostCount())
        assertNull(t.getLastPos())
        assertFalse(t.isTracking(1000))
        assertEquals(0.0, t.getPredictedX(1.0), 0.0)
    }

    @Test
    fun `hold 保留丢失计数与速度只改位置时间`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 0.1)
        t.markLost(2)
        t.hold(pos(3.0, 4.0), now = 0.5)
        assertEquals(2, t.getLostCount())
        assertEquals(pos(3.0, 4.0), t.getLastPos())
        assertEquals(50.0, t.getVelocityX(), 1e-9)
        assertEquals(0.5, t.getLastTime(), 0.0)
    }

    @Test
    fun `预测超时直接返回上一帧坐标`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 0.1)
        // dt=6.0-0.1=5.9 > 5.0 -> 不做速度外推
        assertEquals(10.0, t.getPredictedX(6.0), 1e-9)
        // dt=5.1-0.1=5.0，不 > 5.0 -> 外推
        assertEquals(10.0 + 50.0 * 5.0, t.getPredictedX(5.1), 1e-6)
    }

    @Test
    fun `搜索窗 pad 按 MobileSearchRadius 与模板尺寸计算`() {
        val t = MotionTracker()
        // 无位置：预测中心 0,0；templ 0 -> pad = int(50 + 0) = 50
        assertEquals(MapRect(-50, -50, 100, 100), t.predictNextSearchRect(1.0, 0, 0, 0.0))
    }

    @Test
    fun `搜索窗对预测中心与 pad 都向零截断`() {
        val t = MotionTracker()
        t.update(pos(3.9, 4.9), now = 0.0)
        // pad = int(50 + max(21,10)*0.5/2=5.25) = int(55.25) = 55；中心 (3,4)
        assertEquals(MapRect(3 - 55, 4 - 55, 110, 110), t.predictNextSearchRect(0.5, 21, 10, 0.0))
        // 负坐标同样向零截断：-3.9 -> -3
        t.update(pos(-3.9, -4.9), now = 1.0)
        assertEquals(MapRect(-3 - 55, -4 - 55, 110, 110), t.predictNextSearchRect(0.5, 21, 10, 1.0))
    }

    @Test
    fun `clearVelocity 清零速度`() {
        val t = MotionTracker()
        t.update(pos(0.0, 0.0), now = 0.0)
        t.update(pos(10.0, 0.0), now = 0.1)
        t.clearVelocity()
        assertEquals(0.0, t.getVelocityX(), 0.0)
        assertEquals(0.0, t.getVelocityY(), 0.0)
    }
}
