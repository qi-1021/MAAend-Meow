package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapLocatorPathHeatmap] 的纯逻辑测试。
 *
 * 分三块：
 *  1. 热图构建 `ExtractPathHeatmapFeature`（`MatchStrategy.cpp:19-66`）——目标色 / 容差梯度 /
 *     越界归零 / alpha 跳过 / 高斯模糊。
 *  2. 掩膜全层 `GenerateMinimapMask`（`MapAlgorithm.cpp:10-90`）——圆盘 / 中心 / 暗部 / 白 /
 *     彩色图标 / HSV 开关 / 膨胀 / 两个开关。
 *  3. **合成数据打分区分**：造一张稀疏「路网」地图，从已知真值位置取模板，证明
 *     `scoreAt` 在真值位置与错误位置给出**显著分数差**，且 `matchGlobal` 找到真值位置。
 *     真实分数差在测试里打印，见 `println`。
 */
class MapLocatorPathHeatmapTest {

    private val H = MapLocatorPathHeatmap

    // ───────────────────── 工具 ─────────────────────

    private fun solidBgr(w: Int, h: Int, b: Int, g: Int, r: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        var i = 0
        while (i < out.size) {
            out[i] = b.toByte()
            out[i + 1] = g.toByte()
            out[i + 2] = r.toByte()
            i += 3
        }
        return out
    }

    private fun setPx(bgr: ByteArray, w: Int, x: Int, y: Int, b: Int, g: Int, r: Int) {
        val i = (y * w + x) * 3
        bgr[i] = b.toByte()
        bgr[i + 1] = g.toByte()
        bgr[i + 2] = r.toByte()
    }

    private fun countTrue(mask: BooleanArray): Int {
        var c = 0
        for (v in mask) if (v) c++
        return c
    }

    /** 确定性散列，给稀疏路网用。 */
    private fun hash(x: Int, y: Int, seed: Long): Long {
        var s = x * 374761393L + y * 668265263L + seed
        s = (s xor (s shr 13)) * 1274126177L
        s = s xor (s shr 16)
        return s
    }

    /**
     * 合成「稀疏路网」BGR 地图：约 12% 像素是路面标准色 [H.PATH_TARGET_*]，其余是远离目标色的底色。
     * 用意是逼近真机资产的稀疏线稿（11% 不透明），并让相关性有唯一强峰——纯随机稀疏场的
     * 错误位置期望相关 ~0。
     */
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
                    // 深蓝灰底：与目标色曼哈顿距离远大于 180，热图为 0
                    out[i] = 30
                    out[i + 1] = 40
                    out[i + 2] = 50
                }
                i += 3
            }
        }
        return out
    }

    private fun cropBgr(full: ByteArray, fw: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        for (row in 0 until h) {
            System.arraycopy(full, ((y + row) * fw + x) * 3, out, row * w * 3, w * 3)
        }
        return out
    }

    /** 从单通道图裁出 `w×h`。 */
    private fun cropGray(full: ByteArray, fw: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(full, (y + row) * fw + x, out, row * w, w)
        }
        return out
    }

    // ───────────────────── 1. 热图构建 ─────────────────────

    @Test
    fun `目标路面色热图全为 255`() {
        val img = solidBgr(8, 8, H.PATH_TARGET_B, H.PATH_TARGET_G, H.PATH_TARGET_R)
        val heat = H.extractPathHeatmap(img, 8, 8)!!
        for (v in heat) assertEquals(255, v.toInt() and 0xFF)
    }

    @Test
    fun `远离目标色热图为 0`() {
        val img = solidBgr(8, 8, 0, 0, 0)
        val heat = H.extractPathHeatmap(img, 8, 8)!!
        for (v in heat) assertEquals(0, v.toInt() and 0xFF)
    }

    @Test
    fun `曼哈顿距离 90 的热图值精确为 128`() {
        // dist = |207-237|+|203-233|+|198-228| = 90；255 - 90*255/180 = 255-127 = 128
        val img = solidBgr(9, 9, 207, 203, 198)
        val heat = H.extractPathHeatmap(img, 9, 9)!!
        for (v in heat) assertEquals(128, v.toInt() and 0xFF)
    }

    @Test
    fun `容差边界 dist 179 为 2 而 180 归零`() {
        val near = solidBgr(9, 9, 178, 173, 168) // 59+60+60 = 179
        val eq = solidBgr(9, 9, 177, 173, 168) // 60+60+60 = 180
        val hn = H.extractPathHeatmap(near, 9, 9)!!
        val he = H.extractPathHeatmap(eq, 9, 9)!!
        assertEquals(2, hn[0].toInt() and 0xFF)
        assertEquals(0, he[0].toInt() and 0xFF)
    }

    @Test
    fun `alpha 小于 128 的像素被跳过`() {
        val img = solidBgr(3, 3, H.PATH_TARGET_B, H.PATH_TARGET_G, H.PATH_TARGET_R)
        val alpha = ByteArray(9) { 0 } // 全透明
        val heat = H.extractPathHeatmap(img, 3, 3, alpha)!!
        for (v in heat) assertEquals(0, v.toInt() and 0xFF)
    }

    @Test
    fun `高斯模糊让孤立亮点向邻域扩散且中心小于 255`() {
        val img = solidBgr(7, 7, 0, 0, 0)
        setPx(img, 7, 3, 3, H.PATH_TARGET_B, H.PATH_TARGET_G, H.PATH_TARGET_R)
        val heat = H.extractPathHeatmap(img, 7, 7)!!
        val center = heat[3 * 7 + 3].toInt() and 0xFF
        val ring = heat[3 * 7 + 2].toInt() and 0xFF
        val far = heat[0].toInt() and 0xFF
        println("[blur] center=$center ring=$ring far=$far")
        assertTrue("center=$center", center in 1..254)
        assertTrue("ring=$ring", ring in 1..254)
        assertEquals(0, far)
    }

    @Test
    fun `尺寸或长度不符返回 null`() {
        assertNull(H.extractPathHeatmap(ByteArray(5), 2, 2))
        assertNull(H.extractPathHeatmap(ByteArray(0), 0, 0))
        assertNull(H.extractPathHeatmap(ByteArray(4 * 4 * 3), 4, 4, ByteArray(5)))
    }

    // ───────────────────── 2. 掩膜全层 ─────────────────────

    private fun baseCfg() = MapLocatorPathHeatmap.ImageProcessingConfig(
        minimapDarkMaskThreshold = -1, // 禁用暗部
        useHsvWhiteMask = false,
        borderMargin = 0,
        centerMaskRadius = 0,
        whiteDilate = 3,
        colorDilate = 3,
    )

    @Test
    fun `外接圆盘边界`() {
        val img = solidBgr(20, 20, 40, 40, 40)
        val cfg = baseCfg().copy(borderMargin = 4)
        val mask = H.generateMinimapMask(img, 20, 20, cfg, withUiMask = false, withCenterMask = false)
        assertEquals(400, mask.size)
        assertTrue(mask[10 * 20 + 10]) // 圆心
        assertTrue(mask[10 * 20 + 15]) // 距圆心 5 < 6
        assertFalse(mask[10 * 20 + 17]) // 距圆心 7 > 6
        assertFalse(mask[0]) // 角
        assertFalse(mask[19 * 20 + 19])
    }

    @Test
    fun `中心遮蔽清空中心保留外环`() {
        val img = solidBgr(20, 20, 40, 40, 40)
        val cfg = baseCfg().copy(borderMargin = 0, centerMaskRadius = 3)
        val mask = H.generateMinimapMask(img, 20, 20, cfg, withUiMask = false, withCenterMask = true)
        assertFalse(mask[10 * 20 + 10]) // 中心
        assertFalse(mask[10 * 20 + 13]) // 距圆心 3，仍在遮蔽圈内
        assertTrue(mask[10 * 20 + 15]) // 距圆心 5 > 3
    }

    @Test
    fun `withCenterMask=false 保留中心`() {
        val img = solidBgr(20, 20, 40, 40, 40)
        val cfg = baseCfg().copy(centerMaskRadius = 3)
        val mask = H.generateMinimapMask(img, 20, 20, cfg, withUiMask = false, withCenterMask = false)
        assertTrue(mask[10 * 20 + 10])
    }

    @Test
    fun `暗部剔除清掉暗像素保留亮像素`() {
        val img = solidBgr(20, 20, 0, 0, 0)
        setPx(img, 20, 5, 5, 200, 200, 200)
        val cfg = baseCfg().copy(minimapDarkMaskThreshold = 20)
        val mask = H.generateMinimapMask(img, 20, 20, cfg, withUiMask = false, withCenterMask = false)
        assertTrue(mask[5 * 20 + 5]) // 亮
        assertFalse(mask[10 * 20 + 10]) // 暗（0 <= 20）
    }

    @Test
    fun `阈值小于 0 等价于禁用暗部剔除`() {
        val img = solidBgr(20, 20, 0, 0, 0)
        val enabled = H.generateMinimapMask(
            img, 20, 20, baseCfg().copy(minimapDarkMaskThreshold = 20),
            withUiMask = false, withCenterMask = false,
        )
        val disabled = H.generateMinimapMask(
            img, 20, 20, baseCfg().copy(minimapDarkMaskThreshold = -1),
            withUiMask = false, withCenterMask = false,
        )
        assertFalse(enabled[10 * 20 + 10])
        assertTrue(disabled[10 * 20 + 10])
        assertTrue(countTrue(disabled) > countTrue(enabled))
    }

    @Test
    fun `白掩膜及其膨胀清掉周围像素`() {
        val img = solidBgr(20, 20, 40, 40, 40)
        setPx(img, 20, 5, 5, 255, 255, 255)
        val mask = H.generateMinimapMask(
            img, 20, 20, baseCfg().copy(whiteDilate = 3),
            withUiMask = true, withCenterMask = false,
        )
        assertFalse(mask[5 * 20 + 5]) // 纯白
        assertFalse(mask[5 * 20 + 6]) // 膨胀 1 圈
        assertTrue(mask[5 * 20 + 9]) // 距 4 > 1
    }

    @Test
    fun `彩色图标掩膜及其膨胀清掉像素`() {
        val img = solidBgr(20, 20, 40, 40, 40)
        // r>100,g>100,min(r,g)-b = 150-50 = 100 > iconDiffThreshold
        setPx(img, 20, 5, 5, 50, 150, 150)
        val mask = H.generateMinimapMask(
            img, 20, 20, baseCfg().copy(colorDilate = 3),
            withUiMask = true, withCenterMask = false,
        )
        assertFalse(mask[5 * 20 + 5])
        assertFalse(mask[5 * 20 + 6])
        assertTrue(mask[5 * 20 + 9])
    }

    @Test
    fun `HSV 白掩膜开关决定浅灰路面是否被剔除`() {
        val img = solidBgr(20, 20, H.PATH_TARGET_B, H.PATH_TARGET_G, H.PATH_TARGET_R)
        val on = H.generateMinimapMask(
            img, 20, 20, baseCfg().copy(useHsvWhiteMask = true),
            withUiMask = true, withCenterMask = false,
        )
        val off = H.generateMinimapMask(
            img, 20, 20, baseCfg().copy(useHsvWhiteMask = false),
            withUiMask = true, withCenterMask = false,
        )
        // 路面浅灰 V≈237、S≈10 → HSV 白命中；关闭后保留
        assertTrue("on=${countTrue(on)} off=${countTrue(off)}", countTrue(on) < countTrue(off))
        assertTrue(off[10 * 20 + 10])
        assertFalse(on[10 * 20 + 10])
    }

    @Test
    fun `withUiMask=false 保留 UI 像素`() {
        val img = solidBgr(20, 20, 40, 40, 40)
        setPx(img, 20, 5, 5, 255, 255, 255)
        val mask = H.generateMinimapMask(
            img, 20, 20, baseCfg(),
            withUiMask = false, withCenterMask = false,
        )
        assertTrue(mask[5 * 20 + 5])
    }

    @Test
    fun `掩膜尺寸非法返回空数组`() {
        assertEquals(0, H.generateMinimapMask(ByteArray(5), 2, 2, baseCfg()).size)
        assertEquals(0, H.generateMinimapMask(ByteArray(0), 0, 0, baseCfg()).size)
    }

    // ───────────────────── 3. 模板特征提取 ─────────────────────

    @Test
    fun `模板特征提取产出热图与掩膜且中心被遮蔽`() {
        val sw = 120
        val sh = 120
        val map = sparseRoadMap(sw, sh)
        val tw = 64
        val th = 64
        val tx = 24
        val ty = 24
        val minimap = cropBgr(map, sw, tx, ty, tw, th)
        val feat = H.extractTemplatePathFeature(minimap, tw, th, MapLocatorPathHeatmap.ImageProcessingConfig.Tier)
        assertNotNull(feat)
        assertEquals(tw * th, feat!!.feature.size)
        assertEquals(tw * th, feat.mask.size)
        assertTrue(countTrue(feat.mask) > 0)
        assertFalse(feat.mask[(th / 2) * tw + tw / 2]) // 中心被中心遮蔽
        assertFalse(feat.mask[0]) // 角被圆盘排除
        // 至少有一个有效像素的路面热图 > 0
        var anyPositive = false
        for (i in feat.mask.indices) {
            if (feat.mask[i] && (feat.feature[i].toInt() and 0xFF) > 0) anyPositive = true
        }
        assertTrue(anyPositive)
    }

    @Test
    fun `模板特征提取尺寸不符返回 null`() {
        assertNull(H.extractTemplatePathFeature(ByteArray(5), 2, 2, MapLocatorPathHeatmap.ImageProcessingConfig.Tier))
    }

    // ───────────────────── 4. 合成打分区分（核心） ─────────────────────

    @Test
    fun `精确裁剪模板在真值位置得满分错误位置显著更低`() {
        val sw = 220
        val sh = 170
        val tw = 64
        val th = 64
        val trueX = 80
        val trueY = 50
        val wrongX = 140
        val wrongY = 90

        val mapBgr = sparseRoadMap(sw, sh)
        val heat = H.extractPathHeatmap(mapBgr, sw, sh)!!
        // 模板直接取自搜索热图，保证真值位置逐位相同
        val templ = cropGray(heat, sw, trueX, trueY, tw, th)
        val mask = BooleanArray(tw * th) { true }

        val sTrue = H.scoreAt(heat, sw, sh, templ, tw, th, mask, trueX, trueY)
        val sWrong = H.scoreAt(heat, sw, sh, templ, tw, th, mask, wrongX, wrongY)
        println("[exact] true=$sTrue wrong=$sWrong gap=${sTrue - sWrong}")
        assertTrue("sTrue=$sTrue", sTrue > 0.999)
        assertTrue("sWrong=$sWrong", sWrong < 0.3)
        assertTrue("gap=${sTrue - sWrong}", sTrue - sWrong > 0.6)
    }

    @Test
    fun `全局搜索找到真值位置且主峰显著`() {
        val sw = 200
        val sh = 160
        val tw = 56
        val th = 56
        val trueX = 72
        val trueY = 48

        val mapBgr = sparseRoadMap(sw, sh, seed = 0x1234ABCDL)
        val heat = H.extractPathHeatmap(mapBgr, sw, sh)!!
        val templ = cropGray(heat, sw, trueX, trueY, tw, th)
        val mask = BooleanArray(tw * th) { true }

        val m = H.matchGlobal(heat, sw, sh, templ, tw, th, mask)
        assertNotNull(m)
        println("[global] best=(${m!!.x},${m.y}) score=${m.score} second=${m.secondScore} delta=${m.delta} psr=${m.psr}")
        assertEquals(trueX, m.x)
        assertEquals(trueY, m.y)
        assertTrue("score=${m.score}", m.score > 0.99)
        assertTrue("psr=${m.psr}", m.psr > 5.0)
    }

    @Test
    fun `圆形掩膜下仍能在真值位置得高分`() {
        val sw = 220
        val sh = 170
        val tw = 64
        val th = 64
        val trueX = 80
        val trueY = 50

        val mapBgr = sparseRoadMap(sw, sh)
        val heat = H.extractPathHeatmap(mapBgr, sw, sh)!!
        val templ = cropGray(heat, sw, trueX, trueY, tw, th)

        val valid = MapLocatorRefinePure.circleValidMask(tw, th, tw / 2, th / 2, minOf(tw, th) / 2 - 8)
        val sTrue = H.scoreAt(heat, sw, sh, templ, tw, th, valid, trueX, trueY)
        val sWrong = H.scoreAt(heat, sw, sh, templ, tw, th, valid, trueX + 60, trueY + 40)
        println("[circle] true=$sTrue wrong=$sWrong gap=${sTrue - sWrong}")
        assertTrue("sTrue=$sTrue", sTrue > 0.999)
        assertTrue("gap=${sTrue - sWrong}", sTrue - sWrong > 0.5)
    }

    @Test
    fun `端到端小地图裁剪经特征提取后仍定位到真值`() {
        val sw = 220
        val sh = 170
        val tw = 64
        val th = 64
        val trueX = 80
        val trueY = 50

        val mapBgr = sparseRoadMap(sw, sh)
        val searchHeat = H.extractPathHeatmap(mapBgr, sw, sh)!!
        // 独立地从 BGR 裁小地图再走特征提取（与真机路径一致）
        val minimap = cropBgr(mapBgr, sw, trueX, trueY, tw, th)
        val tmpl = H.extractTemplatePathFeature(minimap, tw, th, MapLocatorPathHeatmap.ImageProcessingConfig.Tier)!!
        // 空白/太稀的掩膜应被拒绝
        assertTrue(countTrue(tmpl.mask) >= H.MIN_VALID_PIXELS)

        val m = H.matchGlobal(searchHeat, sw, sh, tmpl.feature, tw, th, tmpl.mask)
        assertNotNull(m)
        val sTrue = H.scoreAt(searchHeat, sw, sh, tmpl.feature, tw, th, tmpl.mask, trueX, trueY)
        val sWrong = H.scoreAt(searchHeat, sw, sh, tmpl.feature, tw, th, tmpl.mask, trueX + 60, trueY + 40)
        println("[pipeline] best=(${m!!.x},${m.y}) mScore=${m.score} sTrue=$sTrue sWrong=$sWrong gap=${sTrue - sWrong}")
        assertEquals(trueX, m.x)
        assertEquals(trueY, m.y)
        assertTrue("sTrue=$sTrue", sTrue > 0.9)
        assertTrue("gap=${sTrue - sWrong}", sTrue - sWrong > 0.5)
    }

    // ───────────────────── 5. 边界 / 缓存分槽 ─────────────────────

    @Test
    fun `掩膜有效像素不足返回无定义`() {
        val sw = 64
        val sh = 64
        val tw = 16
        val th = 16
        val heat = H.extractPathHeatmap(sparseRoadMap(sw, sh), sw, sh)!!
        val templ = cropGray(heat, sw, 20, 20, tw, th)
        val mask = BooleanArray(tw * th) { false } // 全无效
        val m = H.matchGlobal(heat, sw, sh, templ, tw, th, mask)
        assertNull(m)
        assertEquals(H.NO_SCORE, H.scoreAt(heat, sw, sh, templ, tw, th, mask, 20, 20), 1e-12)
    }

    @Test
    fun `越界或尺寸不符返回哨兵`() {
        val heat = H.extractPathHeatmap(sparseRoadMap(32, 32), 32, 32)!!
        val templ = cropGray(heat, 32, 8, 8, 16, 16)
        val mask = BooleanArray(16 * 16) { true }
        assertEquals(H.NO_SCORE, H.scoreAt(heat, 32, 32, templ, 16, 16, mask, -1, 0), 1e-12)
        assertEquals(H.NO_SCORE, H.scoreAt(heat, 32, 32, templ, 16, 16, mask, 20, 20), 1e-12)
        assertNull(H.matchGlobal(heat, 32, 32, templ, 17, 17, mask))
        assertNull(H.matchGlobal(heat, 32, 32, ByteArray(5), 16, 16, mask))
    }

    @Test
    fun `缓存分槽按策略分离`() {
        assertEquals(1, H.cacheSlotIndex(TemplateFeatureKind.PATH_HEATMAP_BASE))
        assertEquals(1, H.cacheSlotIndex(TemplateFeatureKind.PATH_HEATMAP_TIER))
        assertEquals(0, H.cacheSlotIndex(TemplateFeatureKind.STANDARD_BASE))
        assertEquals(0, H.cacheSlotIndex(TemplateFeatureKind.STANDARD_TIER))
    }
}
