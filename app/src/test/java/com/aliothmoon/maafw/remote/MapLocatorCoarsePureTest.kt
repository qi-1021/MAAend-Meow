package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapLocatorCoarsePure] 的纯逻辑测试：zone key 规则、小地图 ROI 选择/裁剪几何、
 * 搜索 ROI 约束、命中框判定。对齐上游 `loadAvailableZones` / `TryExtractMinimap` /
 * `startGlobalSearch`。
 */
class MapLocatorCoarsePureTest {

    // ───────────────────── mapZoneKey：base 分支 ─────────────────────

    @Test
    fun `base 判定大小写不敏感`() {
        assertEquals("ValleyIV_Base", MapLocatorCoarsePure.mapZoneKey("ValleyIV", "Base.png"))
        assertEquals("Wuling_Base", MapLocatorCoarsePure.mapZoneKey("Wuling", "base.png"))
        assertEquals("Wuling_Base", MapLocatorCoarsePure.mapZoneKey("Wuling", "BASE.PNG"))
        assertEquals("Wuling_Base", MapLocatorCoarsePure.mapZoneKey("Wuling", "Base.Png"))
    }

    @Test
    fun `base jpeg 不算 base 走 stem`() {
        assertEquals("base", MapLocatorCoarsePure.mapZoneKey("Wuling", "base.jpeg"))
    }

    // ───────────────────── mapZoneKey：层级文件分支 ─────────────────────

    @Test
    fun `层级文件去掉前导零`() {
        assertEquals("ValleyIV_L1_114", MapLocatorCoarsePure.mapZoneKey("ValleyIV", "Lv001Tier114.png"))
        assertEquals("ValleyIV_L12_10", MapLocatorCoarsePure.mapZoneKey("ValleyIV", "Lv12Tier10.png"))
        assertEquals("ValleyIV_L4_3", MapLocatorCoarsePure.mapZoneKey("ValleyIV", "Lv004Tier003.png"))
    }

    @Test
    fun `全零层级档位保留单个零`() {
        assertEquals("Z_L0_0", MapLocatorCoarsePure.mapZoneKey("Z", "Lv000Tier000.png"))
    }

    @Test
    fun `扩展名大小写与 jpg webp 都认`() {
        assertEquals("Wuling_L3_17", MapLocatorCoarsePure.mapZoneKey("Wuling", "Lv003Tier17.PNG"))
        assertEquals("Wuling_L3_17", MapLocatorCoarsePure.mapZoneKey("Wuling", "Lv003Tier17.jpg"))
        assertEquals("Wuling_L3_17", MapLocatorCoarsePure.mapZoneKey("Wuling", "Lv003Tier17.WEBP"))
    }

    @Test
    fun `正则从任意位置搜索且只锚定结尾`() {
        assertEquals("Z_L3_17", MapLocatorCoarsePure.mapZoneKey("Z", "FooLv003Tier17.png"))
        // 结尾必须是受支持扩展名
        assertEquals("Lv003Tier17", MapLocatorCoarsePure.mapZoneKey("Z", "Lv003Tier17.bmp"))
    }

    // ───────────────────── mapZoneKey：非标准文件走 stem ─────────────────────

    @Test
    fun `非标准文件名取 stem`() {
        assertEquals("Dung01Base", MapLocatorCoarsePure.mapZoneKey("Dung", "Dung01Base.png"))
        assertEquals("Dung01Tier186", MapLocatorCoarsePure.mapZoneKey("Dung", "Dung01Tier186.png"))
        assertEquals("IndieDg005Base", MapLocatorCoarsePure.mapZoneKey("IndieDg005", "IndieDg005Base.png"))
        assertEquals("OMVBase01", MapLocatorCoarsePure.mapZoneKey("OMVBase", "OMVBase01.png"))
    }

    @Test
    fun `fileStem 覆盖隐藏文件无扩展名多点名`() {
        assertEquals("a", MapLocatorCoarsePure.fileStem("a.b"))
        assertEquals("a.tar", MapLocatorCoarsePure.fileStem("a.tar.gz"))
        assertEquals(".hidden", MapLocatorCoarsePure.fileStem(".hidden"))
        assertEquals("a", MapLocatorCoarsePure.fileStem("a."))
        assertEquals("bare", MapLocatorCoarsePure.fileStem("bare"))
    }

    @Test
    fun `zoneIndex 后写覆盖同名 key`() {
        val index = MapLocatorCoarsePure.zoneIndex(
            listOf(
                "ValleyIV" to "Base.png",
                "ValleyIV" to "Lv003Tier17.png",
                "Wuling" to "Base.png",
            ),
        )
        assertEquals(3, index.size)
        assertEquals("ValleyIV" to "Base.png", index["ValleyIV_Base"])
        assertEquals("ValleyIV" to "Lv003Tier17.png", index["ValleyIV_L3_17"])
        assertEquals("Wuling" to "Base.png", index["Wuling_Base"])
    }

    // ───────────────────── usesAdbMinimapRoi ─────────────────────

    @Test
    fun `adb 与 playcover 大小写不敏感`() {
        assertTrue(MapLocatorCoarsePure.usesAdbMinimapRoi("adb"))
        assertTrue(MapLocatorCoarsePure.usesAdbMinimapRoi("ADB"))
        assertTrue(MapLocatorCoarsePure.usesAdbMinimapRoi("Adb"))
        assertTrue(MapLocatorCoarsePure.usesAdbMinimapRoi("playcover"))
        assertTrue(MapLocatorCoarsePure.usesAdbMinimapRoi("PlayCover"))
        assertTrue(MapLocatorCoarsePure.usesAdbMinimapRoi("play_cover"))
    }

    @Test
    fun `非 adb 控制器走默认 ROI`() {
        assertFalse(MapLocatorCoarsePure.usesAdbMinimapRoi(null))
        assertFalse(MapLocatorCoarsePure.usesAdbMinimapRoi(""))
        assertFalse(MapLocatorCoarsePure.usesAdbMinimapRoi("android_native"))
        assertFalse(MapLocatorCoarsePure.usesAdbMinimapRoi("win32"))
    }

    // ───────────────────── minimapExtractPlan / extractMinimapArgb ─────────────────────

    @Test
    fun `默认 ROI 在 720p 上有效`() {
        val plan = MapLocatorCoarsePure.minimapExtractPlan(1280, 720, useAdbRoi = false)!!
        assertEquals(1280, plan.scaledWidth)
        assertEquals(720, plan.scaledHeight)
        assertEquals(MapRect(49, 51, 118, 120), plan.roi)
    }

    @Test
    fun `adb 变体先缩放 0-8 再用 y-7 的 ROI`() {
        val plan = MapLocatorCoarsePure.minimapExtractPlan(1280, 720, useAdbRoi = true)!!
        assertEquals(1024, plan.scaledWidth)
        assertEquals(576, plan.scaledHeight)
        assertEquals(MapRect(49, 44, 118, 120), plan.roi)
    }

    @Test
    fun `ROI 越界时返回 null`() {
        assertNull(MapLocatorCoarsePure.minimapExtractPlan(100, 100, useAdbRoi = false))
        assertNull(MapLocatorCoarsePure.minimapExtractPlan(0, 720, useAdbRoi = false))
        assertNull(MapLocatorCoarsePure.minimapExtractPlan(1280, -1, useAdbRoi = false))
    }

    @Test
    fun `extractMinimapArgb 逐行裁出 ROI`() {
        // 用 (y*width + x) 编码像素，便于验证裁剪的行列对应
        val width = 200
        val height = 180
        val argb = IntArray(width * height) { it }
        val out = MapLocatorCoarsePure.extractMinimapArgb(argb, width, height, useAdbRoi = false)!!
        assertEquals(118 * 120, out.size)

        // 第一行第一列应等于 (51*200 + 49)
        assertEquals(51 * width + 49, out[0])
        // 最后一行最后一列：y=51+119, x=49+117
        assertEquals((51 + 119) * width + (49 + 117), out[118 * 120 - 1])
    }

    @Test
    fun `extractMinimapArgb 对需要缩放的 adb 路径返回 null`() {
        val argb = IntArray(1280 * 720)
        assertNull(MapLocatorCoarsePure.extractMinimapArgb(argb, 1280, 720, useAdbRoi = true))
    }

    @Test
    fun `extractMinimapArgb 尺寸不符返回 null`() {
        assertNull(MapLocatorCoarsePure.extractMinimapArgb(IntArray(10), 200, 180, useAdbRoi = false))
    }

    // ───────────────────── constrainedSearchRoi ─────────────────────

    @Test
    fun `FULL_MAP 模式返回整张地图`() {
        val constraint = SearchConstraint(GlobalSearchMode.FULL_MAP_FINE, true, MapRect(0, 0, 0, 0))
        val roi = MapLocatorCoarsePure.constrainedSearchRoi(constraint, 1440, 1350, 118, 120)!!
        assertEquals(MapRect(0, 0, 1440, 1350), roi)
    }

    @Test
    fun `ROI_FINE 额外外扩 GlobalSearchRoiPad`() {
        // 模板 118x120 -> pad=53；ROI (200,300,400,300) -> (147,247,506,406)，全在地图内
        val constraint = SearchConstraint(GlobalSearchMode.ROI_FINE, true, MapRect(200, 300, 400, 300))
        val roi = MapLocatorCoarsePure.constrainedSearchRoi(constraint, 1440, 1350, 118, 120)!!
        assertEquals(MapRect(147, 247, 506, 406), roi)
    }

    @Test
    fun `ROI_FINE 外扩后被地图边界裁剪`() {
        // ROI (0,0,50,50) -> 外扩 53 -> (-53,-53,156,156) -> 裁到 (0,0,103,103)
        val constraint = SearchConstraint(GlobalSearchMode.ROI_FINE, true, MapRect(0, 0, 50, 50))
        val roi = MapLocatorCoarsePure.constrainedSearchRoi(constraint, 1440, 1350, 118, 120)!!
        assertEquals(MapRect(0, 0, 103, 103), roi)
    }

    @Test
    fun `ROI 完全在地图外返回 null`() {
        val constraint = SearchConstraint(GlobalSearchMode.ROI_FINE, true, MapRect(5000, 5000, 100, 100))
        assertNull(MapLocatorCoarsePure.constrainedSearchRoi(constraint, 1440, 1350, 118, 120))
    }

    @Test
    fun `地图尺寸非正返回 null`() {
        val constraint = SearchConstraint(GlobalSearchMode.FULL_MAP_FINE, true, MapRect())
        assertNull(MapLocatorCoarsePure.constrainedSearchRoi(constraint, 0, 1350, 118, 120))
    }

    // ───────────────────── 命中框判定 ─────────────────────

    @Test
    fun `isBoxWithinMap 边界判定`() {
        assertTrue(MapLocatorCoarsePure.isBoxWithinMap(intArrayOf(0, 0, 1440, 1350), 1440, 1350))
        assertTrue(MapLocatorCoarsePure.isBoxWithinMap(intArrayOf(100, 100, 50, 50), 1440, 1350))
        assertFalse(MapLocatorCoarsePure.isBoxWithinMap(intArrayOf(1400, 100, 100, 50), 1440, 1350))
        assertFalse(MapLocatorCoarsePure.isBoxWithinMap(intArrayOf(-1, 0, 50, 50), 1440, 1350))
        assertFalse(MapLocatorCoarsePure.isBoxWithinMap(intArrayOf(0, 0, 0, 50), 1440, 1350))
        assertFalse(MapLocatorCoarsePure.isBoxWithinMap(null, 1440, 1350))
        assertFalse(MapLocatorCoarsePure.isBoxWithinMap(intArrayOf(1, 2, 3), 1440, 1350))
    }

    @Test
    fun `isBoxWithinRoi 边界判定`() {
        val roi = MapRect(100, 100, 200, 200)
        assertTrue(MapLocatorCoarsePure.isBoxWithinRoi(intArrayOf(100, 100, 50, 50), roi))
        assertTrue(MapLocatorCoarsePure.isBoxWithinRoi(intArrayOf(250, 250, 50, 50), roi)) // 右/下贴边
        assertFalse(MapLocatorCoarsePure.isBoxWithinRoi(intArrayOf(99, 100, 50, 50), roi))
        assertFalse(MapLocatorCoarsePure.isBoxWithinRoi(intArrayOf(250, 250, 51, 50), roi))
        assertFalse(MapLocatorCoarsePure.isBoxWithinRoi(null, roi))
    }

    @Test
    fun `evaluateCoarseOutcome 组合三项`() {
        val roi = MapRect(100, 100, 200, 200)
        val ok = MapLocatorCoarsePure.evaluateCoarseOutcome(true, intArrayOf(120, 120, 50, 50), 1440, 1350, roi)
        assertTrue(ok.hit)
        assertTrue(ok.inMap)
        assertTrue(ok.inRoi)

        val outsideRoi = MapLocatorCoarsePure.evaluateCoarseOutcome(true, intArrayOf(10, 10, 50, 50), 1440, 1350, roi)
        assertTrue(outsideRoi.inMap)
        assertFalse(outsideRoi.inRoi)

        val miss = MapLocatorCoarsePure.evaluateCoarseOutcome(false, null, 1440, 1350, roi)
        assertFalse(miss.hit)
        assertFalse(miss.inMap)
        assertFalse(miss.inRoi)
    }

    // ───────────────────── bestMatchScore ─────────────────────

    @Test
    fun `bestMatchScore 取 best 分数`() {
        val json = """{"all":[{"box":[1,2,3,4],"score":0.9}],"filtered":[],"best":{"box":[1,2,3,4],"score":0.9123}}"""
        assertEquals(0.9123, MapLocatorCoarsePure.bestMatchScore(json)!!, 1e-9)
    }

    @Test
    fun `bestMatchScore 无法解析或类型不符返回 null`() {
        assertNull(MapLocatorCoarsePure.bestMatchScore(null))
        assertNull(MapLocatorCoarsePure.bestMatchScore("不是 json"))
        assertNull(MapLocatorCoarsePure.bestMatchScore("""{"best":null}"""))
        assertNull(MapLocatorCoarsePure.bestMatchScore("""{"best":{"score":"高"}}"""))
    }
}
