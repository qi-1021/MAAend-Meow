package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.MapLocatorPure.globalSearchRoiPad
import com.aliothmoon.maafw.remote.MapLocatorPure.isTightCluster
import com.aliothmoon.maafw.remote.MapLocatorPure.matchesExpectedZoneSelector
import com.aliothmoon.maafw.remote.MapLocatorPure.normalizeExpectedZoneId
import com.aliothmoon.maafw.remote.MapLocatorPure.quantizePosition
import com.aliothmoon.maafw.remote.MapLocatorPure.quantizeToHundredth
import com.aliothmoon.maafw.remote.MapLocatorPure.searchConstraintsEqual
import com.aliothmoon.maafw.remote.MapLocatorPure.trimLeadingZeros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MapLocator.cpp 几何/映射/裁决纯逻辑测试。
 *
 * 重点钉住 QuantizeToHundredth 的取整方向（std::round 远离零）、stabilizePosition
 * 的死区/重锚点、以及 buildSearchConstraint 的 ROI 裁剪到边界。
 */
class MapLocatorPureTest {

    // ───────────────────── TrimLeadingZeros ─────────────────────

    @Test
    fun `trimLeadingZeros 覆盖空串全零与普通串`() {
        assertEquals("", trimLeadingZeros(""))
        assertEquals("0", trimLeadingZeros("0"))
        assertEquals("0", trimLeadingZeros("00"))
        assertEquals("0", trimLeadingZeros("000"))
        assertEquals("12", trimLeadingZeros("0012"))
        assertEquals("100", trimLeadingZeros("100"))
        assertEquals("123000", trimLeadingZeros("000123000"))
    }

    // ───────────────────── selector 匹配 ─────────────────────

    @Test
    fun `MatchesExpectedZoneSelector 三条全等与前缀`() {
        val coarse = YoloCoarseResult(zoneId = "ZoneA_L1_1", baseClass = "ZoneA_Base", rawClass = "ZoneA_L1_1X")
        assertTrue(matchesExpectedZoneSelector("", coarse))
        assertTrue(matchesExpectedZoneSelector("ZoneA_L1_1", coarse))
        assertTrue(matchesExpectedZoneSelector("ZoneA_Base", coarse))
        assertTrue(matchesExpectedZoneSelector("ZoneA_L1_1X", coarse))
        assertTrue(matchesExpectedZoneSelector("ZoneA_L1", coarse)) // rawClass 前缀
        assertFalse(matchesExpectedZoneSelector("ZoneB", coarse))
        // zone_id 不做前缀匹配：zoneId 前缀但 rawClass 不匹配 -> false
        assertFalse(matchesExpectedZoneSelector("ZoneA_L1_1_", coarse))
    }

    @Test
    fun `NormalizeExpectedZoneId 空串与空转换器原样返回`() {
        assertEquals("", normalizeExpectedZoneId("", { it + "!" }))
        assertEquals("ZoneA", normalizeExpectedZoneId("ZoneA", null))
        assertEquals("ZoneA_L1_1", normalizeExpectedZoneId("Map01Lv01Tier01") { "ZoneA_L1_1" })
    }

    // ───────────────────── GlobalSearchRoiPad ─────────────────────

    @Test
    fun `GlobalSearchRoiPad 覆盖正常与夹紧到 1`() {
        // min=118 -> 118-16+1=103，clamp(1,120)=103 -> ceil(51.5)+1=53
        assertEquals(53, globalSearchRoiPad(118, 120))
        // 小模板 disc 为负，夹到 1 -> ceil(0.5)+1=2
        assertEquals(2, globalSearchRoiPad(10, 10))
        assertEquals(2, globalSearchRoiPad(16, 16))
        // 40x24: min=24 -> disc=9, max=40 -> ceil(4.5)+1=6
        assertEquals(6, globalSearchRoiPad(40, 24))
        // 120x40: min=40 -> disc=25, max=120 -> ceil(12.5)+1=14
        assertEquals(14, globalSearchRoiPad(120, 40))
    }

    @Test
    fun `SearchConstraintsEqual 比较三项`() {
        val a = SearchConstraint(GlobalSearchMode.ROI_FINE, true, MapRect(1, 2, 3, 4))
        val b = SearchConstraint(GlobalSearchMode.ROI_FINE, true, MapRect(1, 2, 3, 4))
        assertTrue(searchConstraintsEqual(a, b))
        assertFalse(searchConstraintsEqual(a, a.copy(mode = GlobalSearchMode.FULL_MAP_FINE)))
        assertFalse(searchConstraintsEqual(a, a.copy(yoloValidated = false)))
        assertFalse(searchConstraintsEqual(a, a.copy(roi = MapRect(0, 0, 1, 1))))
    }

    // ───────────────────── IsTightCluster ─────────────────────

    @Test
    fun `IsTightCluster 少于三帧直接 false`() {
        assertFalse(isTightCluster(emptyList(), 12.0))
        assertFalse(isTightCluster(listOf(MapPosition(x = 0.0, y = 0.0)), 12.0))
        assertFalse(isTightCluster(listOf(MapPosition(x = 0.0, y = 0.0), MapPosition(x = 1.0, y = 1.0)), 12.0))
    }

    @Test
    fun `IsTightCluster 只看末尾三帧且含参考帧自身`() {
        val pts = listOf(
            MapPosition(x = 100.0, y = 100.0), // 远处历史帧不参与
            MapPosition(x = 0.0, y = 0.0),
            MapPosition(x = 3.0, y = 4.0), // dist 5
            MapPosition(x = 0.0, y = 0.0), // ref
        )
        assertTrue(isTightCluster(pts, 12.0))
        assertFalse(isTightCluster(pts, 4.0)) // 5 > 4
    }

    @Test
    fun `IsTightCluster 末尾有离群帧则 false`() {
        val pts = listOf(
            MapPosition(x = 0.0, y = 0.0),
            MapPosition(x = 1.0, y = 0.0),
            MapPosition(x = 30.0, y = 0.0), // 相对 ref(0,0) 距离 30
        )
        assertFalse(isTightCluster(pts, 12.0))
    }

    // ───────────────────── Quantize ─────────────────────

    @Test
    fun `QuantizeToHundredth 遇 5 远离零而非银行家舍入`() {
        // std::round(12.5)=13，ties-to-even 会得 12
        assertEquals(0.13, quantizeToHundredth(0.125), 1e-12)
        // std::round(-12.5)=-13，Math.round/ties-even 会得 -12
        assertEquals(-0.13, quantizeToHundredth(-0.125), 1e-12)
    }

    @Test
    fun `QuantizeToHundredth 常规取整与符号`() {
        assertEquals(0.0, quantizeToHundredth(0.0), 0.0)
        assertEquals(0.01, quantizeToHundredth(0.014), 1e-12)
        assertEquals(0.02, quantizeToHundredth(0.015), 1e-12)
        assertEquals(123.46, quantizeToHundredth(123.456), 1e-9)
        assertEquals(-1.23, quantizeToHundredth(-1.234), 1e-9)
        assertEquals(-1.24, quantizeToHundredth(-1.235), 1e-9)
    }

    @Test
    fun `QuantizePosition 只改 xy 其余字段保留`() {
        val p = MapPosition(zoneId = "Z", x = 1.2345, y = -1.2345, score = 0.9, sliceIndex = 3, angle = 45.0, latencyMs = 7)
        val q = quantizePosition(p)
        assertEquals(1.23, q.x, 1e-12)
        assertEquals(-1.23, q.y, 1e-12)
        assertEquals("Z", q.zoneId)
        assertEquals(0.9, q.score, 0.0)
        assertEquals(3, q.sliceIndex)
        assertEquals(45.0, q.angle, 0.0)
        assertEquals(7L, q.latencyMs)
    }

    // ───────────────────── PositionStabilizer ─────────────────────

    @Test
    fun `stabilize zone None 原样返回且不写状态`() {
        val s = PositionStabilizer()
        val raw = MapPosition(zoneId = "None", x = 1.23456, y = 2.34567)
        assertEquals(raw, s.stabilize(raw))
        assertNull(s.stablePosition)
    }

    @Test
    fun `stabilize 首次或换区时量化重锚点`() {
        val s = PositionStabilizer()
        val first = s.stabilize(MapPosition(zoneId = "Z", x = 1.2345, y = 4.5612, score = 0.5))
        assertEquals(1.23, first.x, 1e-12)
        assertEquals(4.56, first.y, 1e-12)
        assertEquals(first, s.stablePosition)

        val other = s.stabilize(MapPosition(zoneId = "Z2", x = 9.87, y = 1.11, score = 0.99))
        assertEquals(9.87, other.x, 1e-12)
        assertEquals(1.11, other.y, 1e-12)
        assertEquals(other, s.stablePosition)
    }

    @Test
    fun `stabilize 高分小位移钉回稳定坐标`() {
        val s = PositionStabilizer()
        s.stabilize(MapPosition(zoneId = "Z", x = 1.2345, y = 4.5612, score = 0.9))
        // dist = hypot(0.05, 0.03) ≈ 0.058 <= deadband 0.15
        val out = s.stabilize(MapPosition(zoneId = "Z", x = 1.2845, y = 4.5912, score = 0.9, angle = 12.0))
        assertEquals(1.23, out.x, 1e-12)
        assertEquals(4.56, out.y, 1e-12)
        assertEquals(12.0, out.angle, 0.0) // 其余字段用本帧 raw
        // 稳定坐标未变
        assertEquals(1.23, s.stablePosition!!.x, 1e-12)
    }

    @Test
    fun `stabilize 高分位移在死区与释放距离之间也钉回`() {
        val s = PositionStabilizer()
        s.stabilize(MapPosition(zoneId = "Z", x = 1.0, y = 1.0, score = 0.9))
        val out = s.stabilize(MapPosition(zoneId = "Z", x = 1.27, y = 1.0, score = 0.9))
        assertEquals(1.0, out.x, 1e-12) // dist 0.27 < 0.40 -> 钉回
    }

    @Test
    fun `stabilize 位移达到释放距离时重锚点`() {
        val s = PositionStabilizer()
        s.stabilize(MapPosition(zoneId = "Z", x = 1.0, y = 1.0, score = 0.9))
        val out = s.stabilize(MapPosition(zoneId = "Z", x = 1.5, y = 1.0, score = 0.9))
        assertEquals(1.5, out.x, 1e-12) // dist 0.50 >= 0.40 -> 重锚点（量化）
        assertEquals(1.5, s.stablePosition!!.x, 1e-12)
    }

    @Test
    fun `stabilize 低分即使位移很小也重锚点`() {
        val s = PositionStabilizer()
        s.stabilize(MapPosition(zoneId = "Z", x = 1.0, y = 1.0, score = 0.9))
        val out = s.stabilize(MapPosition(zoneId = "Z", x = 1.05, y = 1.0, score = 0.79))
        assertEquals(1.05, out.x, 1e-12)
        assertEquals(1.05, s.stablePosition!!.x, 1e-12)
    }

    @Test
    fun `accept 把稳定结果喂给追踪器`() {
        val s = PositionStabilizer()
        val tracker = MotionTracker()
        val raw = MapPosition(zoneId = "Z", x = 5.6789, y = 2.3456, score = 0.9)
        val out = s.accept(raw, tracker, now = 0.0)
        assertEquals(5.68, out.x, 1e-12)
        assertEquals(2.35, out.y, 1e-12)
        assertEquals(out, tracker.getLastPos())
        assertTrue(tracker.isTracking(0))
    }

    @Test
    fun `reset 清空稳定位置与 forceLost 后的重锚点行为`() {
        val s = PositionStabilizer()
        s.stabilize(MapPosition(zoneId = "Z", x = 1.0, y = 1.0, score = 0.9))
        s.reset()
        assertNull(s.stablePosition)
        val out = s.stabilize(MapPosition(zoneId = "Z", x = 2.3456, y = 3.4567, score = 0.1))
        assertEquals(2.35, out.x, 1e-12)
        assertEquals(3.46, out.y, 1e-12)
    }

    // ───────────────────── buildSearchConstraint ─────────────────────

    private fun coarse(
        valid: Boolean = true,
        zoneId: String = "Z",
        rawClass: String = "Map01Lv01Tier01",
        baseClass: String = "RegionA_Base",
        hasRoi: Boolean = true,
        roiX: Int = 100,
        roiY: Int = 100,
        roiW: Int = 200,
        roiH: Int = 150,
        inferMargin: Int = 64,
    ) = YoloCoarseResult(
        valid = valid,
        zoneId = zoneId,
        rawClass = rawClass,
        baseClass = baseClass,
        hasRoi = hasRoi,
        roiX = roiX,
        roiY = roiY,
        roiW = roiW,
        roiH = roiH,
        inferMargin = inferMargin,
    )

    private val zones = mapOf("Z" to MapDimensions(cols = 1000, rows = 800))

    @Test
    fun `buildSearchConstraint 非法 YOLO 返回默认`() {
        val c = buildSearchConstraint("", "Z", coarse(valid = false), zones)
        assertEquals(SearchConstraint(), c)
        assertFalse(c.yoloValidated)
    }

    @Test
    fun `buildSearchConstraint zone 不匹配或 selector 不匹配则未验证`() {
        assertFalse(buildSearchConstraint("", "Other", coarse(), zones).yoloValidated)
        assertFalse(buildSearchConstraint("Nope", "Z", coarse(), zones).yoloValidated)
    }

    @Test
    fun `buildSearchConstraint path heatmap 走全图`() {
        val c = buildSearchConstraint("", "OMVBase", coarse(zoneId = "OMVBase"), mapOf("OMVBase" to MapDimensions(100, 100)))
        assertTrue(c.yoloValidated)
        assertEquals(GlobalSearchMode.FULL_MAP_FINE, c.mode)
        assertEquals(MapRect(), c.roi)
    }

    @Test
    fun `buildSearchConstraint 无 ROI 映射走全图`() {
        val c = buildSearchConstraint("", "Z", coarse(hasRoi = false), zones)
        assertTrue(c.yoloValidated)
        assertEquals(GlobalSearchMode.FULL_MAP_FINE, c.mode)
    }

    @Test
    fun `buildSearchConstraint 目标 zone 缺失时保持默认模式但已验证`() {
        val c = buildSearchConstraint("", "Missing", coarse(zoneId = "Missing"), zones)
        assertTrue(c.yoloValidated)
        assertEquals(GlobalSearchMode.FULL_MAP_FINE, c.mode)
        assertEquals(MapRect(), c.roi)
    }

    @Test
    fun `buildSearchConstraint 正常 ROI 外扩后裁剪到地图边界`() {
        val c = buildSearchConstraint("", "Z", coarse(), zones)
        assertTrue(c.yoloValidated)
        assertEquals(GlobalSearchMode.ROI_FINE, c.mode)
        // (100-64, 100-64, 200+128, 150+128) = (36,36,328,278)，全在 1000x800 内
        assertEquals(MapRect(36, 36, 328, 278), c.roi)
    }

    @Test
    fun `buildSearchConstraint ROI 超出地图边界时按交集裁剪`() {
        // x=950, margin=64 -> expanded x=886 w=328 -> x2=min(1214,1000)=1000 w=114
        // y=0, margin=64 -> expanded y=-64 h=150+128=278 -> y2=min(214,800)=214 h=214
        val c = buildSearchConstraint("", "Z", coarse(roiX = 950, roiY = 0, roiW = 200, roiH = 150), zones)
        assertEquals(GlobalSearchMode.ROI_FINE, c.mode)
        assertEquals(MapRect(886, 0, 114, 214), c.roi)
    }

    @Test
    fun `buildSearchConstraint ROI 完全在图外时回退全图`() {
        val c = buildSearchConstraint("", "Z", coarse(roiX = 2000, roiY = 0, roiW = 200, roiH = 150), zones)
        assertEquals(GlobalSearchMode.FULL_MAP_FINE, c.mode)
        assertTrue(c.yoloValidated)
        assertEquals(MapRect(), c.roi)
    }

    @Test
    fun `zone 校验失败消息带出实际识别结果且保留前缀`() {
        val coarseResult = YoloCoarseResult(
            valid = true,
            rawClass = "Map01Base__r05_c01",
            baseClass = "Map01Base",
            zoneId = "ValleyIV_Base",
            confidence = 0.98f,
        )
        val msg = MapLocatorPure.describeZoneValidationFailure("Wuling_Base", coarseResult)
        // 前缀必须保持，供 MapLocateAssertPure 的确定性失败判定匹配
        assertTrue(msg.startsWith("YOLO 约束未通过"))
        assertTrue(msg.contains("expected=Wuling_Base"))
        assertTrue(msg.contains("actual=ValleyIV_Base"))
        assertTrue(msg.contains("class=Map01Base__r05_c01"))
        assertTrue(msg.contains("base=Map01Base"))
    }
}
