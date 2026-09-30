package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldMapTypes] 的纯逻辑测试：安全区、尺度阶梯、置信裁决、平移/拖动几何、
 * 图标窗口与热点折算。对齐上游 `WorldMapSolver::SafeArea` / `ScanViewport` 与
 * `WorldMapFind.cpp` 的 `PanDelta` / `NudgeDelta` / `DragMap` / `ConfirmSpot`。
 */
class WorldMapTypesTest {

    // ───────────────────── safeArea ─────────────────────

    @Test
    fun `safeArea 默认 ROI 与 margin`() {
        val r = WorldMapTypes.safeArea(1280, 720, WorldMapViewportConfig.DEFAULT_ROI, 0)
        assertEquals(333, r.x)
        assertEquals(86, r.y)
        assertEquals(486, r.width)
        assertEquals(504, r.height)
    }

    @Test
    fun `safeArea 内缩 margin`() {
        val r = WorldMapTypes.safeArea(1280, 720, WorldMapViewportConfig.DEFAULT_ROI, WorldMapTypes.ICON_MARGIN)
        assertEquals(363, r.x)
        assertEquals(116, r.y)
        assertEquals(426, r.width)
        assertEquals(444, r.height)
    }

    @Test
    fun `safeArea ROI 退化返回空`() {
        val tiny = WorldMapScreenRoi(0.5, 0.5, 0.5, 0.6)
        assertTrue(WorldMapTypes.safeArea(1280, 720, tiny, 0).isEmpty)
    }

    // ───────────────────── 尺度阶梯 ─────────────────────

    @Test
    fun `geometricLadder 等比到上界`() {
        assertEquals(listOf(1.0, 2.0, 4.0), WorldMapTypes.geometricLadder(1.0, 4.0, 2.0))
        assertTrue(WorldMapTypes.geometricLadder(0.0, 4.0, 2.0).isEmpty())
        assertTrue(WorldMapTypes.geometricLadder(1.0, 4.0, 1.0).isEmpty())
    }

    @Test
    fun `geometricLadder 0_30 到 4_00 共 28 档`() {
        val ladder = WorldMapTypes.geometricLadder(0.30, 4.00, 1.10)
        assertEquals(28, ladder.size)
        assertEquals(0.30, ladder.first(), 1e-12)
        assertTrue(ladder.last() <= 4.0 * (1.0 + 1e-6))
    }

    @Test
    fun `linearLadder 步数语义`() {
        assertTrue(WorldMapTypes.linearLadder(0.0, 1.0, 0).isEmpty())
        assertEquals(listOf(2.0), WorldMapTypes.linearLadder(2.0, 5.0, 1))
        assertEquals(listOf(0.0, 0.5, 1.0), WorldMapTypes.linearLadder(0.0, 1.0, 3))
    }

    @Test
    fun `spotScaleLadder 对齐 ConfirmSpot 的档数`() {
        val cfg = WorldMapSpotConfig(templates = listOf("x"), scaleMin = 0.9, scaleMax = 1.35, scaleStep = 0.025)
        val ladder = WorldMapTypes.spotScaleLadder(cfg)
        assertEquals(19, ladder.size)
        assertEquals(0.9, ladder.first(), 1e-12)
        assertEquals(1.35, ladder.last(), 1e-12)
    }

    // ───────────────────── 尺寸闸与置信 ─────────────────────

    @Test
    fun `scaleFits 三条闸`() {
        assertTrue(WorldMapTypes.scaleFits(100, 100, 500, 500, 24, 6))
        assertFalse(WorldMapTypes.scaleFits(20, 100, 500, 500, 24, 6))
        assertFalse(WorldMapTypes.scaleFits(500, 100, 500, 500, 24, 6))
        assertFalse(WorldMapTypes.scaleFits(100, 100, 105, 500, 24, 6))
    }

    @Test
    fun `viewportAccepted 阈值`() {
        val cfg = WorldMapViewportConfig()
        assertTrue(WorldMapTypes.viewportAccepted(0.5, 0.02, cfg))
        assertFalse(WorldMapTypes.viewportAccepted(0.499, 0.5, cfg))
        assertFalse(WorldMapTypes.viewportAccepted(0.9, 0.019, cfg))
    }

    @Test
    fun `rivalBest 排除同尺度相邻档`() {
        val rungs = listOf(
            WorldMapRung(1.0, 0.90),
            WorldMapRung(1.03, 0.95),
            WorldMapRung(2.0, 0.50),
        )
        assertEquals(0.50, WorldMapTypes.rivalBest(rungs, 1.0), 1e-12)
    }

    // ───────────────────── 平移/微推/拖动 ─────────────────────

    @Test
    fun `panDelta 只挪出界的轴`() {
        val safe = MapRect(100, 100, 200, 200)
        val out = WorldMapTypes.panDelta(safe, 50.0, 150.0)
        assertEquals(150.0, out[0], 1e-12)
        assertEquals(0.0, out[1], 1e-12)
        val out2 = WorldMapTypes.panDelta(safe, 350.0, 150.0)
        assertEquals(-150.0, out2[0], 1e-12)
    }

    @Test
    fun `nudgeDelta 首拍回拖与四向轮转`() {
        val safe = MapRect(0, 0, 200, 200)
        val back = WorldMapTypes.nudgeDelta(safe, 10.0, 0.0, 0)
        assertEquals(-5.0, back[0], 1e-12)
        assertEquals(0.0, back[1], 1e-12)

        val first = WorldMapTypes.nudgeDelta(safe, 0.0, 0.0, 0)
        assertEquals(70.0, first[0], 1e-12)
        val second = WorldMapTypes.nudgeDelta(safe, 0.0, 0.0, 1)
        assertEquals(70.0, second[1], 1e-12)
        val third = WorldMapTypes.nudgeDelta(safe, 0.0, 0.0, 2)
        assertEquals(-70.0, third[0], 1e-12)
        val fourth = WorldMapTypes.nudgeDelta(safe, 0.0, 0.0, 3)
        assertEquals(-70.0, fourth[1], 1e-12)
    }

    @Test
    fun `dragClamp 钳到安全区比例与退化`() {
        val safe = MapRect(0, 0, 200, 200)
        val out = WorldMapTypes.dragClamp(safe, 1000.0, 0.0)
        assertEquals(170.0, out[0], 1e-12)
        val degenerate = WorldMapTypes.dragClamp(safe, 0.5, 0.0)
        assertEquals(0.0, degenerate[0], 1e-12)
    }

    @Test
    fun `swipeDuration 按速度并钳位`() {
        assertEquals(100, WorldMapTypes.swipeDuration(200.0, 0.0))
        assertEquals(200, WorldMapTypes.swipeDuration(0.0, 400.0))
        assertEquals(600, WorldMapTypes.swipeDuration(0.0, 2000.0))
    }

    // ───────────────────── 命中框/窗口 ─────────────────────

    @Test
    fun `pointBox 四舍五入`() {
        val box = WorldMapTypes.pointBox(10.4, 10.6)
        assertEquals(10, box.x)
        assertEquals(11, box.y)
        assertEquals(1, box.width)
    }

    @Test
    fun `wantsUnlocked 与 gateReject`() {
        assertTrue(WorldMapTypes.wantsUnlocked(""))
        assertTrue(WorldMapTypes.wantsUnlocked("unlocked"))
        assertFalse(WorldMapTypes.wantsUnlocked("locked"))

        assertFalse(WorldMapTypes.gateReject(5.0, 10.0, floating = false))
        assertTrue(WorldMapTypes.gateReject(25.0, 10.0, floating = false))
        assertFalse(WorldMapTypes.gateReject(1000.0, 10.0, floating = true))
    }

    @Test
    fun `confirmRadius 浮动与定点`() {
        val fixed = WorldMapSpotConfig(templates = listOf("x"), radiusBase = 0.0, radiusScreen = 40)
        assertEquals(40, WorldMapTypes.confirmRadius(fixed, 0.4))
        val floating = WorldMapSpotConfig(templates = listOf("x"), radiusBase = 60.0, radiusScreen = 40)
        assertEquals(150, WorldMapTypes.confirmRadius(floating, 0.4))
    }

    @Test
    fun `offsetBase 与 hotspot 折算`() {
        val offset = WorldMapTypes.offsetBase(10.0, 20.0, 0.0, 0.0, 2.0)
        assertEquals(44.72135955, offset, 1e-6)
        val hs = WorldMapTypes.hotspot(100.0, 100.0, 5.0, 5.0, 2.0)
        assertEquals(110.0, hs[0], 1e-12)
        assertEquals(110.0, hs[1], 1e-12)
    }

    // ───────────────────── 相似变换 ─────────────────────

    @Test
    fun `viewport 正反变换自洽`() {
        val vp = WorldMapViewport(
            scale = 2.0, roiOriginX = 100.0, roiOriginY = 100.0,
            baseOriginX = 50.0, baseOriginY = 50.0,
            roiWidth = 486, roiHeight = 504, score = 0.8, delta = 0.3, psr = 5.0, voteGrid = 1,
        )
        val base = vp.toBase(200.0, 300.0)
        assertEquals(250.0, base[0], 1e-9)
        assertEquals(450.0, base[1], 1e-9)
        val screen = vp.toScreen(base[0], base[1])
        assertEquals(200.0, screen[0], 1e-9)
        assertEquals(300.0, screen[1], 1e-9)
    }
}
