package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MatchStrategy 验证与峰值精修的纯逻辑测试（对齐 `MatchStrategy.cpp`）。
 *
 * 重点覆盖：抛物线偏移的符号/clamp、Standard 与 PathHeatmap 各自的分档阈值、
 * 边界分（0.60/0.80/0.85 等）、传送与边缘吸附、以及工厂的分区/模式选择。
 */
class MatchValidationTest {

    private val searchRect = MapRect(0, 0, 100, 80)
    private val tracking = TrackingConfig()
    private val match = MatchConfig()

    private fun raw(
        score: Double,
        locX: Double = 40.0,
        locY: Double = 40.0,
        delta: Double = 0.5,
        psr: Double = 10.0,
    ) = MatchResultRaw(score = score, locX = locX, locY = locY, delta = delta, psr = psr)

    // ───────────────────── RefinePeakOffset ─────────────────────

    @Test
    fun `RefinePeakOffset 对称或平坦返回零`() {
        assertEquals(0.0, refinePeakOffset(1f, 1f, 1f), 1e-12)
        assertEquals(0.0, refinePeakOffset(0.5f, 1f, 0.5f), 1e-12)
    }

    @Test
    fun `RefinePeakOffset 峰值偏向一侧`() {
        assertEquals(-0.5, refinePeakOffset(0f, 0f, 1f), 1e-12)
        assertEquals(0.5, refinePeakOffset(1f, 0f, 0f), 1e-12)
    }

    @Test
    fun `RefinePeakOffset 斜率过陡时 clamp 到正负 0_5`() {
        assertEquals(-0.5, refinePeakOffset(0f, 0.1f, 100f), 1e-9)
        assertEquals(0.5, refinePeakOffset(100f, 0.1f, 0f), 1e-9)
    }

    // ───────────────────── Standard validateTracking ─────────────────────

    @Test
    fun `Standard 中心高分有效且绝对坐标是模板中心`() {
        val v = StandardMatchStrategy(false, tracking, match)
            .validateTracking(raw(1.0), 1.0, null, searchRect, 20, 10)
        assertTrue(v.isValid)
        assertFalse(v.isEdgeSnapped)
        assertFalse(v.isTeleported)
        assertFalse(v.isScreenBlocked)
        assertEquals(50.0, v.absX, 1e-12)
        assertEquals(45.0, v.absY, 1e-12)
    }

    @Test
    fun `Standard 边缘吸附按 margin 判定`() {
        val s = StandardMatchStrategy(false, tracking, match)
        // maxX=80, maxY=70，margin=1
        assertTrue(s.validateTracking(raw(1.0, locX = 0.0), 1.0, null, searchRect, 20, 10).isEdgeSnapped)
        assertTrue(s.validateTracking(raw(1.0, locX = 1.0), 1.0, null, searchRect, 20, 10).isEdgeSnapped)
        assertTrue(s.validateTracking(raw(1.0, locX = 79.0), 1.0, null, searchRect, 20, 10).isEdgeSnapped)
        assertTrue(s.validateTracking(raw(1.0, locY = 69.0), 1.0, null, searchRect, 20, 10).isEdgeSnapped)
        val inside = s.validateTracking(raw(1.0, locX = 2.0, locY = 2.0), 1.0, null, searchRect, 20, 10)
        assertFalse(inside.isEdgeSnapped)
        assertTrue(inside.isValid)
    }

    @Test
    fun `Standard 帧间速度超限判传送`() {
        val s = StandardMatchStrategy(false, tracking, match)
        val last = MapPosition(x = 0.0, y = 0.0)
        val v = s.validateTracking(raw(1.0), 1.0, last, searchRect, 20, 10)
        assertTrue(v.isTeleported)
        assertFalse(v.isValid)
    }

    @Test
    fun `Standard 正常速度不判传送`() {
        val s = StandardMatchStrategy(false, tracking, match)
        val last = MapPosition(x = 60.0, y = 45.0) // 距 abs(50,45) 为 10，dt=1 -> 10px/s
        val v = s.validateTracking(raw(1.0), 1.0, last, searchRect, 20, 10)
        assertFalse(v.isTeleported)
        assertTrue(v.isValid)
    }

    @Test
    fun `Standard 分数硬下限与遮挡`() {
        val s = StandardMatchStrategy(false, tracking, match)
        val v = s.validateTracking(raw(0.59, delta = 1.0, psr = 10.0), 1.0, null, searchRect, 20, 10)
        assertFalse(v.isValid)
        assertFalse(v.isScreenBlocked) // 0.59 >= 0.4
        val blocked = s.validateTracking(raw(0.39, delta = 1.0, psr = 10.0), 1.0, null, searchRect, 20, 10)
        assertTrue(blocked.isScreenBlocked)
        assertFalse(blocked.isValid)
    }

    @Test
    fun `Standard 低分时 PSR 或 delta 不足判歧义`() {
        val s = StandardMatchStrategy(false, tracking, match)
        assertFalse(s.validateTracking(raw(0.79, delta = 0.5, psr = 5.99), 1.0, null, searchRect, 20, 10).isValid)
        assertFalse(s.validateTracking(raw(0.79, delta = 0.019, psr = 10.0), 1.0, null, searchRect, 20, 10).isValid)
        // 边界：psr=6.0 且 delta=0.02 -> 不歧义
        assertTrue(s.validateTracking(raw(0.79, delta = 0.02, psr = 6.0), 1.0, null, searchRect, 20, 10).isValid)
        // 0.80 起 lowScore 为假，不再看 psr/delta
        assertTrue(s.validateTracking(raw(0.80, delta = 0.0, psr = 0.0), 1.0, null, searchRect, 20, 10).isValid)
        // 0.60 恰好在硬下限上
        assertTrue(s.validateTracking(raw(0.60, delta = 1.0, psr = 10.0), 1.0, null, searchRect, 20, 10).isValid)
    }

    @Test
    fun `Standard validateGlobalSearch 以 passThreshold 为界`() {
        val s = StandardMatchStrategy(false, tracking, MatchConfig(passThreshold = 0.55))
        assertNull(s.validateGlobalSearch(raw(0.54)))
        assertEquals(0.55, s.validateGlobalSearch(raw(0.55))!!, 0.0)
    }

    // ───────────────────── PathHeatmap validateTracking ─────────────────────

    @Test
    fun `PathHeatmap 四条 accept 分档`() {
        val p = PathHeatmapMatchStrategy(false, tracking, match)
        assertTrue(p.validateTracking(raw(0.85, delta = 0.0, psr = 0.0), 1.0, null, searchRect, 20, 10).isValid)
        assertTrue(p.validateTracking(raw(0.70, delta = 0.25, psr = 2.0), 1.0, null, searchRect, 20, 10).isValid)
        assertTrue(p.validateTracking(raw(0.42, delta = 0.04, psr = 3.8), 1.0, null, searchRect, 20, 10).isValid)
        assertTrue(p.validateTracking(raw(0.40, delta = 0.05, psr = 3.8), 1.0, null, searchRect, 20, 10).isValid)
    }

    @Test
    fun `PathHeatmap 不满足 accept 即歧义`() {
        val p = PathHeatmapMatchStrategy(false, tracking, match)
        // 0.41/0.04/3.8：第三条要求 >=0.42，第四条要求 delta>=0.05 -> 都不满足
        val v = p.validateTracking(raw(0.41, delta = 0.04, psr = 3.8), 1.0, null, searchRect, 20, 10)
        assertFalse(v.isValid)
        assertTrue(v.isScreenBlocked)
        // 0.84 无 delta/psr
        assertFalse(p.validateTracking(raw(0.84, delta = 0.0, psr = 0.0), 1.0, null, searchRect, 20, 10).isValid)
    }

    @Test
    fun `PathHeatmap hold 只影响遮挡不影响有效性`() {
        val p = PathHeatmapMatchStrategy(false, tracking, match)
        // score>=0.35 且 psr>=4.0 -> hold，但 accept 为假 -> ambiguous，仍无效
        val v = p.validateTracking(raw(0.36, delta = 0.0, psr = 4.0), 1.0, null, searchRect, 20, 10)
        assertFalse(v.isValid)
        assertFalse(v.isScreenBlocked)
    }

    @Test
    fun `PathHeatmap 同样做边缘吸附与传送`() {
        val p = PathHeatmapMatchStrategy(false, tracking, match)
        assertTrue(p.validateTracking(raw(0.9, locX = 0.0), 1.0, null, searchRect, 20, 10).isEdgeSnapped)
        val tele = p.validateTracking(raw(0.9), 1.0, MapPosition(x = 0.0, y = 0.0), searchRect, 20, 10)
        assertTrue(tele.isTeleported)
        assertFalse(tele.isValid)
    }

    @Test
    fun `PathHeatmap needsChamferCompensation 为真`() {
        assertTrue(PathHeatmapMatchStrategy(false, tracking, match).needsChamferCompensation)
        assertFalse(StandardMatchStrategy(false, tracking, match).needsChamferCompensation)
    }

    @Test
    fun `PathHeatmap validateGlobalSearch 以 passThreshold 为界`() {
        val p = PathHeatmapMatchStrategy(false, tracking, MatchConfig(passThreshold = 0.55))
        assertNull(p.validateGlobalSearch(raw(0.54)))
        assertEquals(0.55, p.validateGlobalSearch(raw(0.55))!!, 0.0)
    }

    // ───────────────────── Factory ─────────────────────

    @Test
    fun `Factory 按 zoneId 选策略与 base 标志`() {
        val std = MatchStrategyFactory.create("ZoneA_Base")
        assertTrue(std is StandardMatchStrategy)
        assertTrue(std.isBase)
        assertEquals(TemplateFeatureKind.STANDARD_BASE, std.kind)

        val stdTier = MatchStrategyFactory.create("ZoneA_L1_1")
        assertTrue(stdTier is StandardMatchStrategy)
        assertFalse(stdTier.isBase)
        assertEquals(TemplateFeatureKind.STANDARD_TIER, stdTier.kind)

        val path = MatchStrategyFactory.create("OMVBase")
        assertTrue(path is PathHeatmapMatchStrategy)
        assertTrue(path.isBase)
        assertEquals(TemplateFeatureKind.PATH_HEATMAP_BASE, path.kind)

        // IsPathHeatmapZone 只认 "OMVBase" 子串；热力图 tier 只能靠 ForcePathHeatmap 得到
        // （任何含 OMVBase 的字符串必然也含 "Base"，故自动路径下 tier 不可达）。
        val pathTier = MatchStrategyFactory.create("OMVTier", mode = MatchMode.FORCE_PATH_HEATMAP)
        assertTrue(pathTier is PathHeatmapMatchStrategy)
        assertFalse(pathTier.isBase)
        assertEquals(TemplateFeatureKind.PATH_HEATMAP_TIER, pathTier.kind)
    }

    @Test
    fun `Factory MatchMode 强制覆盖热力图选择`() {
        val forcedStd = MatchStrategyFactory.create("OMVBase", mode = MatchMode.FORCE_STANDARD)
        assertTrue(forcedStd is StandardMatchStrategy)
        assertTrue(forcedStd.isBase)

        val forcedPath = MatchStrategyFactory.create("PlainZone", mode = MatchMode.FORCE_PATH_HEATMAP)
        assertTrue(forcedPath is PathHeatmapMatchStrategy)
        assertFalse(forcedPath.isBase)

        val auto = MatchStrategyFactory.create("PlainZone", mode = MatchMode.AUTO)
        assertTrue(auto is StandardMatchStrategy)
    }

    @Test
    fun `Factory 透传 MatchConfig 阈值`() {
        val s = MatchStrategyFactory.create("ZoneA", matchCfg = MatchConfig(passThreshold = 0.9))
        assertNull(s.validateGlobalSearch(raw(0.89)))
        assertEquals(0.9, s.validateGlobalSearch(raw(0.9))!!, 0.0)
    }
}
