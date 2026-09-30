package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldMapSolverPure] 的纯逻辑测试：粗解尺度阶梯与尺寸闸、粗解→细解窗口、
 * 视口组装与置信裁决。对齐上游 `ScanViewport`（`WorldMapSolver.cpp:367-470`）。
 */
class WorldMapSolverPureTest {

    // ───────────────────── 粗解阶梯 ─────────────────────

    @Test
    fun `coarseScales 无 hint 走整条带`() {
        val ladder = WorldMapSolverPure.coarseScales(WorldMapViewportConfig())
        assertEquals(28, ladder.size)
        assertEquals(0.30, ladder.first(), 1e-12)
    }

    @Test
    fun `coarseScales 有 hint 只留相邻三档`() {
        val ladder = WorldMapSolverPure.coarseScales(WorldMapViewportConfig(scaleHint = 2.0))
        assertEquals(3, ladder.size)
        assertEquals(2.0 / 1.1, ladder[0], 1e-12)
        assertEquals(2.0, ladder[1], 1e-12)
        assertEquals(2.0 * 1.1, ladder[2], 1e-12)
    }

    @Test
    fun `coarseScalesFiltered 按模板尺寸闸剪枝`() {
        val cfg = WorldMapViewportConfig()
        // 小底图：高倍率模板放不下，应被剪掉
        val pruned = WorldMapSolverPure.coarseScalesFiltered(cfg, 121, 126, 200, 200)
        assertTrue(pruned.size < 28)
        assertTrue(pruned.isNotEmpty())
        // 大底图上全部通过
        val all = WorldMapSolverPure.coarseScalesFiltered(cfg, 121, 126, 504, 744)
        assertEquals(28, all.size)
    }

    @Test
    fun `bestCoarse 取最高分并跳过无解档`() {
        val probes = listOf(
            WorldMapSolverPure.CoarseProbe(0.5, null),
            WorldMapSolverPure.CoarseProbe(0.6, WorldMapPeak(0.4, 10.0, 20.0, 50, 50)),
            WorldMapSolverPure.CoarseProbe(0.7, WorldMapPeak(0.6, 30.0, 40.0, 60, 60)),
        )
        val best = WorldMapSolverPure.bestCoarse(probes)
        assertNotNull(best)
        assertEquals(0.7, best!!.scale, 1e-12)
        assertEquals(0.6, best.peak.score, 1e-12)
        assertEquals(2, WorldMapSolverPure.coarseRungs(probes).size)
    }

    @Test
    fun `bestCoarse 全空返回 null`() {
        assertNull(
            WorldMapSolverPure.bestCoarse(
                listOf(WorldMapSolverPure.CoarseProbe(0.5, null)),
            ),
        )
    }

    // ───────────────────── 细解窗口 ─────────────────────

    @Test
    fun `finePlan 窗口几何`() {
        val cfg = WorldMapViewportConfig()
        val coarse = WorldMapSolverPure.CoarseBest(
            scale = 0.4,
            peak = WorldMapPeak(score = 0.8, locX = 100.0, locY = 200.0, sizeWidth = 48, sizeHeight = 50),
        )
        val plan = WorldMapSolverPure.finePlan(cfg, 2000, 3000, 486, 504, coarse)
        assertNotNull(plan)
        assertEquals(388, plan!!.window.x)
        assertEquals(788, plan.window.y)
        assertEquals(242, plan.window.width)
        assertEquals(250, plan.window.height)
        assertEquals(15, plan.scales.size)
        assertEquals(0.352, plan.scales.first(), 1e-9)
        assertEquals(0.448, plan.scales.last(), 1e-9)
    }

    @Test
    fun `finePlan 退化返回 null`() {
        val cfg = WorldMapViewportConfig()
        val coarse = WorldMapSolverPure.CoarseBest(
            scale = 0.4,
            peak = WorldMapPeak(score = 0.8, locX = 100.0, locY = 200.0, sizeWidth = 48, sizeHeight = 50),
        )
        // 底图太小，窗口宽被 baseWidth - windowX 压成负数
        assertNull(WorldMapSolverPure.finePlan(cfg, 200, 200, 486, 504, coarse))
    }

    @Test
    fun `finePlan 钉尺度时只留给定档`() {
        val cfg = WorldMapViewportConfig(scaleHint = 0.4)
        val coarse = WorldMapSolverPure.CoarseBest(
            scale = 0.4,
            peak = WorldMapPeak(score = 0.8, locX = 100.0, locY = 200.0, sizeWidth = 48, sizeHeight = 50),
        )
        val plan = WorldMapSolverPure.finePlan(cfg, 2000, 3000, 486, 504, coarse)
        assertNotNull(plan)
        assertEquals(listOf(0.4), plan!!.scales)
    }

    // ───────────────────── 视口组装/裁决 ─────────────────────

    @Test
    fun `buildViewport 组装与 baseOrigin 平移`() {
        val cfg = WorldMapViewportConfig()
        val roiRect = MapRect(333, 86, 486, 504)
        val window = MapRect(100, 200, 242, 250)
        val finePeak = WorldMapPeak(score = 0.8, locX = 10.0, locY = 20.0, sizeWidth = 194, sizeHeight = 202, psr = 5.0)
        val vp = WorldMapSolverPure.buildViewport(
            cfg, roiRect, window, fineScale = 0.4, finePeak = finePeak,
            rungs = listOf(WorldMapRung(0.5, 0.4)), voteGrid = 1,
        )
        assertNotNull(vp)
        assertEquals(0.4, vp!!.scale, 1e-12)
        assertEquals(110.0, vp.baseOriginX, 1e-12)
        assertEquals(220.0, vp.baseOriginY, 1e-12)
        assertEquals(333.0, vp.roiOriginX, 1e-12)
        assertEquals(0.4, vp.delta, 1e-12)
        assertEquals(1, vp.voteGrid)
    }

    @Test
    fun `buildViewport 分数过低拒绝`() {
        val cfg = WorldMapViewportConfig()
        val vp = WorldMapSolverPure.buildViewport(
            cfg, MapRect(0, 0, 10, 10), MapRect(0, 0, 100, 100), 0.4,
            WorldMapPeak(0.4, 0.0, 0.0, 10, 10), emptyList(), 1,
        )
        assertNull(vp)
    }

    @Test
    fun `buildViewport delta 不足拒绝`() {
        val cfg = WorldMapViewportConfig()
        val vp = WorldMapSolverPure.buildViewport(
            cfg, MapRect(0, 0, 10, 10), MapRect(0, 0, 100, 100), 0.4,
            WorldMapPeak(0.80, 0.0, 0.0, 10, 10),
            rungs = listOf(WorldMapRung(0.5, 0.79)), voteGrid = 1,
        )
        assertNull(vp)
    }

    @Test
    fun `pinnedConfig 与 expectedScreen`() {
        val pinned = WorldMapSolverPure.pinnedConfig(WorldMapViewportConfig(), 0.42)
        assertEquals(0.42, pinned.scaleHint!!, 1e-12)

        val vp = WorldMapViewport(
            scale = 2.0, roiOriginX = 100.0, roiOriginY = 100.0,
            baseOriginX = 50.0, baseOriginY = 50.0,
            roiWidth = 486, roiHeight = 504, score = 0.8, delta = 0.3, psr = 1.0, voteGrid = 1,
        )
        val screen = WorldMapSolverPure.expectedScreen(vp, 250.0, 450.0)
        assertEquals(200.0, screen[0], 1e-9)
        assertEquals(300.0, screen[1], 1e-9)
    }

    @Test
    fun `viewportAccepted 与 gateReject 组合护栏`() {
        // 分数达标但 delta 太小时拒绝
        assertFalse(WorldMapTypes.viewportAccepted(0.79, 0.01, WorldMapViewportConfig()))
        // 定点命中偏出判定圈拒绝；浮动不拒
        assertTrue(WorldMapTypes.gateReject(100.0, 10.0, floating = false))
        assertFalse(WorldMapTypes.gateReject(100.0, 10.0, floating = true))
    }
}
