package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapLocatorHeatmapPipeline] 的纯逻辑测试：掩膜外接框裁剪、掩膜外均值填充（选项 a）、
 * 局部窗口真掩膜 ZNCC 精排（选项 b）、搜索热图两槽缓存。
 *
 * 合成数据造法与 `MapLocatorPathHeatmapTest` 相同：稀疏「路网」热图有唯一强峰，
 * 便于证明精排能回收真值位置。
 */
class MapLocatorHeatmapPipelineTest {

    private val H = MapLocatorPathHeatmap
    private val P = MapLocatorHeatmapPipeline

    // ───────────────────── 工具 ─────────────────────

    private fun hash(x: Int, y: Int, seed: Long): Long {
        var s = x * 374761393L + y * 668265263L + seed
        s = (s xor (s shr 13)) * 1274126177L
        s = s xor (s shr 16)
        return s
    }

    private fun sparseRoadMap(w: Int, h: Int, seed: Long = 0x4D41504CL): ByteArray {
        val out = ByteArray(w * h * 3)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val road = (hash(x, y, seed) % 100L).let { if (it < 0) it + 100 else it } < 12L
                if (road) {
                    out[i] = H.PATH_TARGET_B.toByte()
                    out[i + 1] = H.PATH_TARGET_G.toByte()
                    out[i + 2] = H.PATH_TARGET_R.toByte()
                } else {
                    out[i] = 30
                    out[i + 1] = 40
                    out[i + 2] = 50
                }
                i += 3
            }
        }
        return out
    }

    private fun cropGray(full: ByteArray, fw: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(full, (y + row) * fw + x, out, row * w, w)
        }
        return out
    }

    // ───────────────────── 1. 掩膜外接框 / 裁剪 ─────────────────────

    @Test
    fun `外接框含端点`() {
        val w = 10
        val h = 8
        val mask = BooleanArray(w * h)
        mask[2 * w + 3] = true
        mask[4 * w + 7] = true
        val box = P.maskBoundingBox(mask, w, h)
        assertEquals(3, box.x)
        assertEquals(2, box.y)
        assertEquals(7 - 3 + 1, box.width)
        assertEquals(4 - 2 + 1, box.height)
    }

    @Test
    fun `外接框全假返回空`() {
        assertEquals(true, P.maskBoundingBox(BooleanArray(12), 4, 3).isEmpty)
        assertEquals(true, P.maskBoundingBox(BooleanArray(0), 0, 0).isEmpty)
    }

    @Test
    fun `裁剪热图按矩形`() {
        val src = ByteArray(20) { it.toByte() }
        val rect = MapRect(1, 1, 3, 2) // 行1: 6,7,8；行2: 11,12,13
        val out = P.cropGray(src, 5, 4, rect)!!
        assertEquals(6, out.size)
        assertEquals(6, out[0].toInt())
        assertEquals(8, out[2].toInt())
        assertEquals(11, out[3].toInt())
    }

    @Test
    fun `裁剪越界或尺寸不符返回 null`() {
        val src = ByteArray(20)
        assertNull(P.cropGray(src, 5, 4, MapRect(4, 0, 3, 2)))
        assertNull(P.cropGray(ByteArray(5), 5, 4, MapRect(0, 0, 1, 1)))
        assertNull(P.cropGray(src, 5, 4, MapRect(0, 0, 0, 0)))
        val mask = BooleanArray(20)
        assertNull(P.cropMask(mask, 5, 4, MapRect(4, 0, 3, 2)))
    }

    // ───────────────────── 2. 选项 (a)：掩膜外填充 ─────────────────────

    @Test
    fun `均值填充掩膜外且掩膜内不变`() {
        val templ = byteArrayOf(10, 20, 30, 40)
        val mask = booleanArrayOf(true, true, false, false)
        val out = P.fillOutsideMask(templ, mask)
        assertEquals(10, out[0].toInt())
        assertEquals(20, out[1].toInt())
        assertEquals(15, out[2].toInt()) // mean(10,20)=15
        assertEquals(15, out[3].toInt())
    }

    @Test
    fun `掩膜全假时均值填充为 0`() {
        val templ = byteArrayOf(10, 20, 30)
        val out = P.fillOutsideMask(templ, BooleanArray(3))
        for (v in out) assertEquals(0, v.toInt())
    }

    @Test
    fun `replicateToBgr 三通道同值`() {
        val heat = byteArrayOf(0.toByte(), 128.toByte(), 255.toByte())
        val bgr = P.replicateToBgr(heat)
        assertEquals(9, bgr.size)
        for (i in heat.indices) {
            assertEquals(heat[i], bgr[i * 3])
            assertEquals(heat[i], bgr[i * 3 + 1])
            assertEquals(heat[i], bgr[i * 3 + 2])
        }
    }

    // ───────────────────── 3. 选项 (b)：窗口精排 ─────────────────────

    @Test
    fun `精排窗口居中并裁到边界`() {
        val win = P.refineWindow(50, 50, 20, 20, 200, 200, radius = 40)!!
        assertEquals(10, win.x)
        assertEquals(10, win.y)
        assertEquals(100, win.width)
        assertEquals(100, win.height)

        // 贴近左上角：窗口不越界
        val win2 = P.refineWindow(2, 1, 20, 20, 200, 200, radius = 40)!!
        assertEquals(0, win2.x)
        assertEquals(0, win2.y)

        // 贴近右下角：窗口右/下裁到搜索图边界
        val win3 = P.refineWindow(198, 198, 20, 20, 200, 200, radius = 40)!!
        assertTrue(win3.x + win3.width <= 200)
        assertTrue(win3.y + win3.height <= 200)
    }

    @Test
    fun `精排窗口放不下模板返回 null`() {
        assertNull(P.refineWindow(0, 0, 300, 20, 200, 200, radius = 40))
        assertNull(P.refineWindow(0, 0, 0, 20, 200, 200, radius = 40))
        assertNull(P.refineWindow(0, 0, 20, 20, 0, 200, radius = 40))
    }

    @Test
    fun `窗口内真掩膜 ZNCC 定位到真值并换算回搜索坐标`() {
        val sw = 200
        val sh = 160
        val tw = 48
        val th = 48
        val trueX = 70
        val trueY = 50

        val map = sparseRoadMap(sw, sh, seed = 0x5EEDL)
        val heat = H.extractPathHeatmap(map, sw, sh)!!
        val templ = cropGray(heat, sw, trueX, trueY, tw, th)
        val mask = BooleanArray(tw * th) { true }

        // 粗排框偏离真值 6/4 px，精排应拉回真值
        val refined = P.refineInWindow(heat, sw, sh, templ, tw, th, mask, trueX + 6, trueY - 4)
        assertNotNull(refined)
        println("[pipeline-refine] best=(${refined!!.x},${refined.y}) score=${refined.score} psr=${refined.psr}")
        assertEquals(trueX, refined.x)
        assertEquals(trueY, refined.y)
        assertTrue("score=${refined.score}", refined.score > 0.99)
    }

    @Test
    fun `精排窗口无解时返回 null`() {
        val heat = H.extractPathHeatmap(sparseRoadMap(40, 40), 40, 40)!!
        val templ = cropGray(heat, 40, 8, 8, 16, 16)
        val mask = BooleanArray(16 * 16) { false } // 全无效掩膜
        assertNull(P.refineInWindow(heat, 40, 40, templ, 16, 16, mask, 8, 8))
    }

    // ───────────────────── 4. 搜索热图两槽缓存 ─────────────────────

    @Test
    fun `缓存同键命中不同键重算`() {
        val cache = MapLocatorHeatmapPipeline.SearchFeatureCache()
        var calls = 0
        val key = MapLocatorHeatmapPipeline.SearchFeatureKey(
            "ValleyIV_OMVBase", TemplateFeatureKind.PATH_HEATMAP_BASE, MapRect(0, 0, 10, 10), 7L,
        )
        val a = cache.getOrCompute(key) { calls++; ByteArray(4) { 1 } }!!
        val b = cache.getOrCompute(key) { calls++; ByteArray(4) { 2 } }!!
        assertEquals(1, calls)
        assertTrue("same feature instance expected", a === b)

        // 换 ROI → 同槽不同键 → 重算
        val c = cache.getOrCompute(key.copy(roi = MapRect(1, 1, 10, 10))) { calls++; ByteArray(4) { 3 } }!!
        assertEquals(2, calls)
        assertEquals(3, c[0].toInt())

        // 换代际 → 重算
        cache.getOrCompute(key.copy(generation = 8L)) { calls++; ByteArray(4) { 4 } }
        assertEquals(3, calls)
    }

    @Test
    fun `缓存两槽按策略分离互不覆盖`() {
        val cache = MapLocatorHeatmapPipeline.SearchFeatureCache()
        var calls = 0
        val base = MapLocatorHeatmapPipeline.SearchFeatureKey(
            "z", TemplateFeatureKind.PATH_HEATMAP_BASE, MapRect(0, 0, 10, 10), 1L,
        )
        val std = MapLocatorHeatmapPipeline.SearchFeatureKey(
            "z", TemplateFeatureKind.STANDARD_BASE, MapRect(0, 0, 10, 10), 1L,
        )
        cache.getOrCompute(base) { calls++; ByteArray(4) { 1 } }
        cache.getOrCompute(std) { calls++; ByteArray(4) { 2 } }
        assertEquals(2, calls)
        // 再取两个都命中，不再重算
        cache.getOrCompute(base) { calls++; ByteArray(4) { 9 } }
        cache.getOrCompute(std) { calls++; ByteArray(4) { 9 } }
        assertEquals(2, calls)
        // clear 后都要重算
        cache.clear()
        cache.getOrCompute(base) { calls++; ByteArray(4) { 1 } }
        cache.getOrCompute(std) { calls++; ByteArray(4) { 2 } }
        assertEquals(4, calls)
    }

    @Test
    fun `assetGeneration 稳定且对长度敏感`() {
        assertEquals(P.assetGeneration(1000L, 12345L), P.assetGeneration(1000L, 12345L))
        assertTrue(P.assetGeneration(1001L, 12345L) != P.assetGeneration(1000L, 12345L))
        assertTrue(P.assetGeneration(1000L, 12346L) != P.assetGeneration(1000L, 12345L))
    }

    // ───────────────────── 精排种子框合法性（角落夹窗根因） ─────────────────────

    @Test
    fun `isUsableSeedBox 拒掉框架未命中的 0 0 0 0 框`() {
        // 框架 TemplateMatch 未命中时回的框，绝不能当种子（否则窗口夹到搜索 ROI 左上角）
        assertTrue(!P.isUsableSeedBox(intArrayOf(0, 0, 0, 0)))
        // 宽高为负 / 长度不足 / null 都不可用
        assertTrue(!P.isUsableSeedBox(intArrayOf(0, 0, -1, 5)))
        assertTrue(!P.isUsableSeedBox(intArrayOf(5, 5, 0, 0)))
        assertTrue(!P.isUsableSeedBox(intArrayOf(0, 0, 10)))
        assertTrue(!P.isUsableSeedBox(null))
    }

    @Test
    fun `isUsableSeedBox 接受左上角非原点的正框`() {
        assertTrue(P.isUsableSeedBox(intArrayOf(1, 0, 10, 10)))
        assertTrue(P.isUsableSeedBox(intArrayOf(0, 1, 10, 10)))
        assertTrue(P.isUsableSeedBox(intArrayOf(445, 625, 118, 120)))
    }

    @Test
    fun `粗排求峰阈值必须低于采信阈值以免丢失峰位`() {
        // 求峰位用 0.0：只要非负峰就保留位置，采信与否交后续精排/追踪
        assertEquals(0.0, P.COARSE_SEED_THRESHOLD, 0.0)
        assertTrue(P.COARSE_SEED_THRESHOLD <= 0.0)
    }
}
