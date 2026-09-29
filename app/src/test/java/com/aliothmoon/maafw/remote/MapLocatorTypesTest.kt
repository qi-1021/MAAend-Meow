package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MapTypes.h 纯逻辑常量/映射测试（对齐 `MapTypes.h`）。
 */
class MapLocatorTypesTest {

    @Test
    fun `默认与 adb 小地图 ROI 对齐上游常量`() {
        assertEquals(49, DEFAULT_MINIMAP_ROI.x)
        assertEquals(51, DEFAULT_MINIMAP_ROI.y)
        assertEquals(118, DEFAULT_MINIMAP_ROI.width)
        assertEquals(120, DEFAULT_MINIMAP_ROI.height)

        // kAdbMinimapRoi = 默认 ROI + (0, -7)
        assertEquals(49, ADB_MINIMAP_ROI.x)
        assertEquals(44, ADB_MINIMAP_ROI.y)
        assertEquals(118, ADB_MINIMAP_ROI.width)
        assertEquals(120, ADB_MINIMAP_ROI.height)
        assertEquals(0.8, ADB_MINIMAP_FULL_IMAGE_SCALE, 0.0)
    }

    @Test
    fun `GetMinimapRoiConfig 按开关返回两份 ROI`() {
        assertEquals(DEFAULT_MINIMAP_ROI, getMinimapRoiConfig(false))
        assertEquals(ADB_MINIMAP_ROI, getMinimapRoiConfig(true))
    }

    @Test
    fun `ZoneTemplateScale 只有 ValleyIV_Base 是 15 比 16`() {
        assertEquals(15.0 / 16.0, zoneTemplateScale("ValleyIV_Base"), 0.0)
        assertEquals(1.0, zoneTemplateScale("ValleyIV_L1_1"), 0.0)
        assertEquals(1.0, zoneTemplateScale("SomeBase"), 0.0)
        assertEquals(1.0, zoneTemplateScale(""), 0.0)
    }

    @Test
    fun `IsPathHeatmapZone 是子串匹配且区分大小写`() {
        assertTrue(isPathHeatmapZone("OMVBase"))
        assertTrue(isPathHeatmapZone("XOMVBaseY"))
        assertTrue(isPathHeatmapZone("OMVBase_L1_1"))
        assertFalse(isPathHeatmapZone("OMVTier"))
        assertFalse(isPathHeatmapZone("omvbase"))
        assertFalse(isPathHeatmapZone(""))
    }

    @Test
    fun `MapRect intersect 对齐 OpenCV 的 Rect 交集`() {
        assertEquals(MapRect(5, 5, 5, 5), MapRect(0, 0, 10, 10).intersect(MapRect(5, 5, 10, 10)))
        // 完全包含：结果是被包含者
        assertEquals(MapRect(3, 3, 2, 2), MapRect(0, 0, 10, 10).intersect(MapRect(3, 3, 2, 2)))
    }

    @Test
    fun `MapRect intersect 不相交时宽高非正且为空`() {
        val r = MapRect(0, 0, 10, 10).intersect(MapRect(20, 20, 5, 5))
        assertTrue(r.isEmpty)
        assertTrue(r.width <= 0 || r.height <= 0)
    }

    @Test
    fun `MapRect isEmpty 判 width 或 height 非正`() {
        assertTrue(MapRect(0, 0, 0, 10).isEmpty)
        assertTrue(MapRect(0, 0, 10, 0).isEmpty)
        assertTrue(MapRect(0, 0, -1, 10).isEmpty)
        assertTrue(MapRect(0, 0, 10, -1).isEmpty)
        assertTrue(MapRect().isEmpty)
        assertFalse(MapRect(0, 0, 1, 1).isEmpty)
    }

    @Test
    fun `LocateStatus ordinal 与上游 static_cast int 一致`() {
        assertEquals(0, LocateStatus.SUCCESS.ordinal)
        assertEquals(1, LocateStatus.TRACKING_LOST.ordinal)
        assertEquals(2, LocateStatus.SCREEN_BLOCKED.ordinal)
        assertEquals(3, LocateStatus.TELEPORTED.ordinal)
        assertEquals(4, LocateStatus.YOLO_FAILED.ordinal)
        assertEquals(5, LocateStatus.NOT_INITIALIZED.ordinal)
    }
}
