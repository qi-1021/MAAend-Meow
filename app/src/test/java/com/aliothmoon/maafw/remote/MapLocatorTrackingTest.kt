package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapLocatorTracking] 状态机全路径测试（对齐 `MapLocator.cpp` 的 locate 尾段与 tryTracking）。
 *
 * 覆盖：冷启动共识不足→REJECT、连续一致→ACCEPT、远跳→REJECT、高置信→立即 ACCEPT、
 * 丢失到上限→RELOCATE（普通区 3 / 路径区 10）、换区重新冷启动、`None` 遮挡 HOLD、
 * 追踪帧的 accept/hold/reject/离群，以及 reset。
 */
class MapLocatorTrackingTest {

    private fun pos(
        zoneId: String = "Z",
        x: Double,
        y: Double,
        score: Double = 0.70,
    ) = MapPosition(zoneId = zoneId, x = x, y = y, score = score)

    /** 用三帧一致观测把状态机推进到「已追踪」。 */
    private fun established(zoneId: String = "Z", x: Double = 100.0, y: Double = 100.0, score: Double = 0.70): MapLocatorTracking {
        val t = MapLocatorTracking()
        t.feed(pos(zoneId, x, y, score), 0.0)
        t.feed(pos(zoneId, x, y, score), 0.1)
        val d = t.feed(pos(zoneId, x, y, score), 0.2)
        assertEquals(TrackingAction.ACCEPT, d.action)
        return t
    }

    // ───────────────────── 冷启动共识 ─────────────────────

    @Test
    fun `冷启动不足三帧不接受`() {
        val t = MapLocatorTracking()
        val d1 = t.feed(pos(x = 100.0, y = 100.0), 0.0)
        assertEquals(TrackingAction.REJECT, d1.action)
        assertEquals(1, d1.coldStartFrames)
        assertNull(d1.position)

        val d2 = t.feed(pos(x = 101.0, y = 100.0), 0.1)
        assertEquals(TrackingAction.REJECT, d2.action)
        assertEquals(2, d2.coldStartFrames)
        assertFalse(t.status().isTracking)
    }

    @Test
    fun `冷启动连续三帧一致接受`() {
        val t = MapLocatorTracking()
        t.feed(pos(x = 100.0, y = 100.0), 0.0)
        t.feed(pos(x = 100.0, y = 100.0), 0.1)
        val d = t.feed(pos(x = 100.0, y = 100.0), 0.2)
        assertEquals(TrackingAction.ACCEPT, d.action)
        assertNotNull(d.position)
        assertEquals(0, d.coldStartFrames)
        assertTrue(t.status().isTracking)
        assertEquals(0, t.lostCount)
    }

    @Test
    fun `冷启动末尾离群帧不构成共识`() {
        val t = MapLocatorTracking()
        t.feed(pos(x = 0.0, y = 0.0), 0.0)
        t.feed(pos(x = 0.0, y = 0.0), 0.1)
        // 第三帧跳到 30px 外，末尾三帧不是紧簇
        val d = t.feed(pos(x = 30.0, y = 0.0), 0.2)
        assertEquals(TrackingAction.REJECT, d.action)
        assertEquals(3, d.coldStartFrames)
    }

    @Test
    fun `高置信首帧立即接受绕过冷启动`() {
        val t = MapLocatorTracking()
        val d = t.feed(pos(x = 500.0, y = 500.0, score = HIGH_CONFIDENCE_OVERRIDE), 0.0)
        assertEquals(TrackingAction.ACCEPT, d.action)
        assertNotNull(d.position)
        assertEquals(500.0, d.position!!.x, 1e-9)
    }

    // ───────────────────── 远跳 / 高置信重锚点 ─────────────────────

    @Test
    fun `远跳低分拒绝并累计一次丢失`() {
        val t = established()
        val d = t.feed(pos(x = 1000.0, y = 100.0, score = 0.50), 0.3)
        assertEquals(TrackingAction.REJECT, d.action)
        assertEquals("far-jump rejected", d.reason)
        assertEquals(1, d.lostCount)
        // 上一帧位置未被改写
        assertEquals(100.0, t.status().lastKnownPosition!!.x, 1e-9)
    }

    @Test
    fun `远跳高置信立即接受并重锚点`() {
        val t = established()
        val d = t.feed(pos(x = 1000.0, y = 100.0, score = 0.90), 0.3)
        assertEquals(TrackingAction.ACCEPT, d.action)
        assertEquals(1000.0, d.position!!.x, 1e-9)
        assertEquals(1000.0, t.stablePosition!!.x, 1e-9)
    }

    // ───────────────────── 丢失上限与 relocate ─────────────────────

    @Test
    fun `普通区丢失到上限触发 relocate`() {
        val t = established()
        var last: TrackingDecision? = null
        repeat(3) { i ->
            last = t.feed(pos(x = 1000.0, y = 100.0, score = 0.50), 0.3 + i * 0.1)
            assertEquals(TrackingAction.REJECT, last!!.action)
        }
        assertEquals(MAX_LOST_TRACKING_COUNT, last!!.lostCount)
        // 第 4 次：markLost 后 lost=4 > 3 -> RELOCATE 并清空
        val d4 = t.feed(pos(x = 1000.0, y = 100.0, score = 0.50), 0.7)
        assertEquals(TrackingAction.RELOCATE, d4.action)
        assertFalse(t.status().isTracking)
        assertNull(t.stablePosition)
        assertNull(t.status().lastKnownPosition)
    }

    @Test
    fun `路径区丢失上限放宽到 10`() {
        val t = MapLocatorTracking()
        // 高置信首帧直接接受，进入追踪
        t.feed(pos("OMVBase", 100.0, 100.0, 0.90), 0.0)
        assertEquals(0, t.lostCount)

        var last: TrackingDecision? = null
        repeat(10) { i ->
            last = t.feed(pos("OMVBase", 1000.0, 100.0, 0.50), 0.1 + i * 0.1)
            assertEquals(TrackingAction.REJECT, last!!.action)
        }
        assertEquals(PATH_HEATMAP_MAX_LOST_TRACKING_COUNT, last!!.lostCount)
        val d11 = t.feed(pos("OMVBase", 1000.0, 100.0, 0.50), 1.3)
        assertEquals(TrackingAction.RELOCATE, d11.action)
    }

    // ───────────────────── 换区 / None ─────────────────────

    @Test
    fun `换区不沿用上一帧而重新冷启动`() {
        val t = established(zoneId = "Z")
        val d = t.feed(pos(zoneId = "W", x = 100.0, y = 100.0), 0.3)
        assertEquals(TrackingAction.REJECT, d.action)
        assertEquals(1, d.coldStartFrames)
    }

    @Test
    fun `zone None 保持上一帧`() {
        val t = established()
        val d = t.feed(pos(zoneId = "None", x = 0.0, y = 0.0, score = 1.0), 0.3)
        assertEquals(TrackingAction.HOLD, d.action)
        assertNotNull(d.position)
        assertEquals(100.0, d.position!!.x, 1e-9)
    }

    // ───────────────────── 追踪帧（tryTracking） ─────────────────────

    @Test
    fun `追踪器未就绪时要求 relocate`() {
        val t = MapLocatorTracking()
        val d = t.feedTracking(
            MatchResultRaw(score = 1.0, locX = 10.0, locY = 10.0),
            MapRect(0, 0, 200, 200), 40, 40, 0.0,
        )
        assertEquals(TrackingAction.RELOCATE, d.action)
    }

    @Test
    fun `追踪帧高分有效接受`() {
        val t = established()
        // absX = 0 + 80 + 20 = 100, absY = 100 -> 与上一帧同点
        val d = t.feedTracking(
            MatchResultRaw(score = 0.95, locX = 80.0, locY = 80.0),
            MapRect(0, 0, 200, 200), 40, 40, 0.3,
        )
        assertEquals(TrackingAction.ACCEPT, d.action)
        assertEquals(100.0, d.position!!.x, 1e-9)
        assertEquals(100.0, d.position!!.y, 1e-9)
    }

    @Test
    fun `追踪帧歧义保持上一帧并记丢失`() {
        val t = established()
        // Standard: score 0.5 < 0.60 硬下限，>=0.4 不算遮挡 -> 仅歧义 -> HOLD
        val d = t.feedTracking(
            MatchResultRaw(score = 0.50, locX = 80.0, locY = 80.0),
            MapRect(0, 0, 200, 200), 40, 40, 0.3,
        )
        assertEquals(TrackingAction.HOLD, d.action)
        assertNotNull(d.position)
        assertEquals(100.0, d.position!!.x, 1e-9)
        assertEquals(1, d.lostCount)
    }

    @Test
    fun `追踪帧边缘吸附拒绝`() {
        val t = established()
        // locX=0 命中边缘吸附 -> isValid=false 且 onlyAmbiguous=false -> REJECT
        val d = t.feedTracking(
            MatchResultRaw(score = 1.0, locX = 0.0, locY = 80.0),
            MapRect(0, 0, 200, 200), 40, 40, 0.3,
        )
        assertEquals(TrackingAction.REJECT, d.action)
        assertEquals(0, d.lostCount)
    }

    @Test
    fun `追踪帧坐标离群保持并记丢失`() {
        val t = established()
        // 有效帧但分数 < 0.78 且跳变 hypot(100,100)≈141 > 25 -> HOLD。
        // dt 取 4s：速度 35px/s < maxNormalSpeed(40)，不触发传送，才能走到离群分支。
        val d = t.feedTracking(
            MatchResultRaw(score = 0.75, locX = 180.0, locY = 180.0, delta = 0.5, psr = 10.0),
            MapRect(0, 0, 400, 400), 40, 40, 4.2,
        )
        assertEquals(TrackingAction.HOLD, d.action)
        assertEquals(1, d.lostCount)
    }

    // ───────────────────── reset / status ─────────────────────

    @Test
    fun `reset 清空全部状态`() {
        val t = established()
        t.reset()
        val s = t.status()
        assertEquals("", s.zoneId)
        assertNull(s.stablePosition)
        assertNull(s.lastKnownPosition)
        assertEquals(0, s.coldStartFrames)
        assertFalse(s.isTracking)
    }

    @Test
    fun `describe 输出状态行`() {
        val t = established()
        val lines = t.describe()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("tracking=true"))
        assertTrue(lines[1].contains("(100.00,100.00"))
    }
}
