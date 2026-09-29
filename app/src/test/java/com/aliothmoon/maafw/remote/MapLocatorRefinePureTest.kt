package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * [MapLocatorRefinePure] 的纯逻辑测试：BGR2GRAY、圆掩膜、带掩膜 ZNCC、抛物线/连续两种
 * 亚像素精修、边界与退化输入。
 *
 * 精度验收用**合成数据**离线证明：造一张确定性纹理图，从**非整数位置** `(137.30, 88.70)`
 * 用与实现同式的双线性插值裁出模板，再让精修层把它恢复出来，断言误差 `< 0.2 px`。
 */
class MapLocatorRefinePureTest {

    // ───────────────────── 合成纹理与采样工具 ─────────────────────

    private val mapW = 400
    private val mapH = 300
    private val templW = 48
    private val templH = 40
    private val trueX = 137.30
    private val trueY = 88.70

    /** 确定性伪随机，返回 `[-0.5, 0.5]`，给纹理叠一层高频细节让相关峰更尖。 */
    private fun hashNoise(x: Int, y: Int): Double {
        var s = (x * 374761393 + y * 668265263 + 0x9E3779B9).toLong()
        s = (s xor (s shr 13)) * 1274126177L
        s = s xor (s shr 16)
        return ((s and 0xFFFFL).toDouble() / 65535.0) - 0.5
    }

    /** 连续底色函数，在整数栅格上取值并量化成 0..255 的灰度图。 */
    private fun textureGray(w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = 128.0 +
                    60.0 * sin(x * 0.37 + y * 0.13) +
                    40.0 * cos(x * 0.11 - y * 0.29) +
                    30.0 * sin((x + y) * 0.053) +
                    50.0 * hashNoise(x, y)
                out[y * w + x] = v.roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** 与实现同式的双线性采样（测试侧独立实现，作为裁模板的真值来源）。 */
    private fun sampleBilinear(gray: IntArray, w: Int, h: Int, x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val ax = x - x0
        val ay = y - y0
        return (1.0 - ax) * (1.0 - ay) * gray[y0 * w + x0] +
            ax * (1.0 - ay) * gray[y0 * w + x1] +
            (1.0 - ax) * ay * gray[y1 * w + x0] +
            ax * ay * gray[y1 * w + x1]
    }

    /** 在 `(x0, y0)`（可非整数）用双线性插值裁出 `tw×th` 灰度模板。 */
    private fun cutTemplate(gray: IntArray, w: Int, h: Int, x0: Double, y0: Double, tw: Int, th: Int): IntArray {
        val out = IntArray(tw * th)
        for (dy in 0 until th) {
            for (dx in 0 until tw) {
                out[dy * tw + dx] = sampleBilinear(gray, w, h, x0 + dx, y0 + dy).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** R=G=B 复制成 BGR（BGR2GRAY 会精确还原灰度，见下）。 */
    private fun toBgr(gray: IntArray): ByteArray {
        val out = ByteArray(gray.size * 3)
        for (i in gray.indices) {
            val v = gray[i].toByte()
            out[i * 3] = v
            out[i * 3 + 1] = v
            out[i * 3 + 2] = v
        }
        return out
    }

    private fun synth(): Triple<ByteArray, ByteArray, IntArray> {
        val mapGray = textureGray(mapW, mapH)
        val templGray = cutTemplate(mapGray, mapW, mapH, trueX, trueY, templW, templH)
        return Triple(toBgr(mapGray), toBgr(templGray), mapGray)
    }

    // ───────────────────── BGR2GRAY ─────────────────────

    @Test
    fun `bgrToGray 复刻 OpenCV 14 位定点系数`() {
        val bgr = byteArrayOf(10, 20, 30) // B,G,R
        val gray = MapLocatorRefinePure.bgrToGray(bgr, 1, 1)!!
        // (10*1868 + 20*9617 + 30*4899 + 8192) >> 14 = 366182 >> 14 = 22
        assertEquals(22, gray[0].toInt() and 0xFF)
    }

    @Test
    fun `bgrToGray 灰色输入精确还原`() {
        val values = intArrayOf(0, 1, 127, 128, 254, 255)
        val bgr = ByteArray(values.size * 3)
        for (i in values.indices) {
            val v = values[i].toByte()
            bgr[i * 3] = v
            bgr[i * 3 + 1] = v
            bgr[i * 3 + 2] = v
        }
        val gray = MapLocatorRefinePure.bgrToGray(bgr, values.size, 1)!!
        for (i in values.indices) assertEquals(values[i], gray[i].toInt() and 0xFF)
    }

    @Test
    fun `bgrToGray 尺寸不符返回 null`() {
        assertNull(MapLocatorRefinePure.bgrToGray(ByteArray(5), 2, 1))
        assertNull(MapLocatorRefinePure.bgrToGray(ByteArray(0), 0, 1))
    }

    // ───────────────────── 圆掩膜 ─────────────────────

    @Test
    fun `circleValidMask 圆心在内四角在外`() {
        val mask = MapLocatorRefinePure.circleValidMask(20, 20, 10, 10, 5)
        assertTrue(mask[10 * 20 + 10]) // 圆心
        assertFalse(mask[0]) // 左上角
        assertFalse(mask[19]) // 右上角
        assertFalse(mask[19 * 20]) // 左下角
        assertFalse(mask[19 * 20 + 19]) // 右下角
        assertTrue(mask[10 * 20 + 15]) // 正右 radius 处
    }

    @Test
    fun `circleValidMask 非正方形按各自尺寸栅格化`() {
        // 48x40，中心 (24,20)，radius 12（borderMargin=8 的上游 tier 参数）
        val mask = MapLocatorRefinePure.circleValidMask(48, 40, 24, 20, 12)
        assertEquals(48 * 40, mask.size)
        assertTrue(mask[20 * 48 + 24])
        assertFalse(mask[0])
        assertFalse(mask[39 * 48 + 47])
    }

    @Test
    fun `circleValidMask 负半径按 0 只留圆心`() {
        val mask = MapLocatorRefinePure.circleValidMask(10, 10, 5, 5, -3)
        var count = 0
        for (v in mask) if (v) count++
        assertEquals(1, count)
        assertTrue(mask[5 * 10 + 5])
    }

    // ───────────────────── 合成精度（硬要求） ─────────────────────

    @Test
    fun `连续模式全有效掩膜恢复亚像素位置误差小于 0_2px`() {
        val (mapBgr, templBgr, _) = synth()
        val r = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 4,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
            refineMode = MapLocatorRefinePure.PeakRefineMode.CONTINUOUS,
        )
        assertNotNull(r)
        val ex = abs(r!!.x - trueX)
        val ey = abs(r.y - trueY)
        println("[continuous/all] errX=$ex errY=$ey score=${r.score} atBoundary=${r.atBoundary}")
        assertTrue("errX=$ex", ex < 0.2)
        assertTrue("errY=$ey", ey < 0.2)
        assertTrue("score=${r.score}", r.score > 0.99)
        assertTrue(r.valid)
    }

    @Test
    fun `连续模式圆形掩膜也恢复亚像素位置误差小于 0_2px`() {
        val (mapBgr, templBgr, _) = synth()
        val r = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 4,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.CIRCLE,
            borderMargin = 8,
            refineMode = MapLocatorRefinePure.PeakRefineMode.CONTINUOUS,
        )
        assertNotNull(r)
        val ex = abs(r!!.x - trueX)
        val ey = abs(r.y - trueY)
        println("[continuous/circle] errX=$ex errY=$ey score=${r.score}")
        assertTrue("errX=$ex", ex < 0.2)
        assertTrue("errY=$ey", ey < 0.2)
        assertTrue(r.valid)
    }

    @Test
    fun `抛物线模式亚像素位置在可保证界内`() {
        val (mapBgr, templBgr, _) = synth()
        val r = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 4,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
            refineMode = MapLocatorRefinePure.PeakRefineMode.PARABOLA,
        )
        assertNotNull(r)
        val ex = abs(r!!.x - trueX)
        val ey = abs(r.y - trueY)
        println("[parabola/all] errX=$ex errY=$ey score=${r.score} atBoundary=${r.atBoundary}")
        // 抛物线受相关面格点限制，比连续模式粗；这里给出可保证界 0.5px。
        assertTrue("errX=$ex", ex < 0.5)
        assertTrue("errY=$ey", ey < 0.5)
    }

    @Test
    fun `整数精确裁剪时峰值在整数点且分数接近 1`() {
        val plan = MapLocatorProbeSupport.syntheticPlan()!!
        val r = MapLocatorRefinePure.refineSubpixel(
            plan.fullBgr, plan.fullWidth, plan.fullHeight,
            plan.patchBgr, plan.patchW, plan.patchH,
            coarseX = plan.patchX, coarseY = plan.patchY, searchRadius = 3,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
            refineMode = MapLocatorRefinePure.PeakRefineMode.CONTINUOUS,
        )
        assertNotNull(r)
        assertEquals(plan.patchX.toDouble(), r!!.x, 1e-6)
        assertEquals(plan.patchY.toDouble(), r.y, 1e-6)
        assertTrue("score=${r.score}", r.score > 0.999)
    }

    // ───────────────────── 边界与退化 ─────────────────────

    @Test
    fun `模板贴地图左上角不越界且定位正确`() {
        val gray = textureGray(64, 64)
        val templ = cutTemplate(gray, 64, 64, 0.0, 0.0, 32, 32)
        val r = MapLocatorRefinePure.refineSubpixel(
            toBgr(gray), 64, 64, toBgr(templ), 32, 32,
            coarseX = 0, coarseY = 0, searchRadius = 2,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
            refineMode = MapLocatorRefinePure.PeakRefineMode.CONTINUOUS,
        )
        assertNotNull(r)
        // 粗搜在角上会外扩到负坐标（模板部分越界），实现须跳过越界像素而非崩溃。
        assertTrue("x=${r!!.x}", abs(r.x - 0.0) < 0.2)
        assertTrue("y=${r.y}", abs(r.y - 0.0) < 0.2)
    }

    @Test
    fun `模板比地图大返回 null`() {
        val map = MapLocatorProbeSupport.synthesizeBgr(32, 32)
        val templ = MapLocatorProbeSupport.synthesizeBgr(33, 33)
        val r = MapLocatorRefinePure.refineSubpixel(
            map, 32, 32, templ, 33, 33,
            coarseX = 0, coarseY = 0, searchRadius = 2,
        )
        assertNull(r)
    }

    @Test
    fun `全黑相关面无定义时 valid 为 false 分数 -1`() {
        val map = ByteArray(64 * 64 * 3)
        val templ = ByteArray(16 * 16 * 3)
        val r = MapLocatorRefinePure.refineSubpixel(
            map, 64, 64, templ, 16, 16,
            coarseX = 20, coarseY = 20, searchRadius = 3,
        )
        assertNotNull(r)
        assertFalse(r!!.valid)
        assertEquals(-1.0, r.score, 1e-12)
        assertEquals("x=${r.x}", 20.0, r.x, 1e-12)
        assertEquals("y=${r.y}", 20.0, r.y, 1e-12)
    }

    @Test
    fun `全白相关面无定义时 valid 为 false`() {
        val map = ByteArray(64 * 64 * 3) { 255.toByte() }
        val templ = ByteArray(16 * 16 * 3) { 255.toByte() }
        val r = MapLocatorRefinePure.refineSubpixel(
            map, 64, 64, templ, 16, 16,
            coarseX = 20, coarseY = 20, searchRadius = 3,
        )
        assertNotNull(r)
        assertFalse(r!!.valid)
        assertEquals(-1.0, r.score, 1e-12)
    }

    @Test
    fun `掩膜有效像素不足时无定义`() {
        // borderMargin 过大 -> radius<0 -> 只留圆心 1 个有效像素 < MIN_VALID_PIXELS
        val (mapBgr, templBgr, _) = synth()
        val r = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 2,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.CIRCLE,
            borderMargin = 100,
        )
        assertNotNull(r)
        assertFalse(r!!.valid)
        assertEquals(-1.0, r.score, 1e-12)
    }

    @Test
    fun `尺寸或长度不符返回 null`() {
        val map = MapLocatorProbeSupport.synthesizeBgr(32, 32)
        val templ = MapLocatorProbeSupport.synthesizeBgr(8, 8)
        // 地图长度不对
        assertNull(
            MapLocatorRefinePure.refineSubpixel(
                ByteArray(10), 32, 32, templ, 8, 8, 0, 0, 2,
            ),
        )
        // 尺寸非正
        assertNull(
            MapLocatorRefinePure.refineSubpixel(
                map, 0, 32, templ, 8, 8, 0, 0, 2,
            ),
        )
        // 模板长度不对
        assertNull(
            MapLocatorRefinePure.refineSubpixel(
                map, 32, 32, ByteArray(5), 8, 8, 0, 0, 2,
            ),
        )
    }

    // ───────────────────── atBoundary 与阈值 ─────────────────────

    @Test
    fun `搜索半径为 0 时无邻点返回整数位置并标记`() {
        val (mapBgr, templBgr, _) = synth()
        val r = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 0,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
            refineMode = MapLocatorRefinePure.PeakRefineMode.PARABOLA,
        )
        assertNotNull(r)
        assertTrue(r!!.atBoundary)
        assertEquals(137.0, r.x, 1e-12)
        assertEquals(89.0, r.y, 1e-12)
    }

    @Test
    fun `真值在搜索窗边缘时标记 atBoundary 且不越窗`() {
        val gray = textureGray(80, 80)
        val templ = cutTemplate(gray, 80, 80, 20.4, 20.0, 24, 24)
        val r = MapLocatorRefinePure.refineSubpixel(
            toBgr(gray), 80, 80, toBgr(templ), 24, 24,
            coarseX = 16, coarseY = 20, searchRadius = 4,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
            refineMode = MapLocatorRefinePure.PeakRefineMode.PARABOLA,
        )
        assertNotNull(r)
        assertTrue("atBoundary=${r!!.atBoundary}", r.atBoundary)
        // 窗是 [12,20]，峰值贴 20 这一侧；无右邻点 -> x 保持整数峰。
        assertEquals(20.0, r.x, 1e-9)
    }

    @Test
    fun `阈值门限接既有 validateGlobalSearch`() {
        val (mapBgr, templBgr, _) = synth()
        val base = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 3,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
        )!!
        assertTrue(base.valid)
        assertTrue(base.score > 0.99)

        // 阈值抬到不可能的高度 -> valid=false（证明 valid 确实由阈值判定，而非恒真）。
        val strict = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 3,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
            passThreshold = 1.5,
        )!!
        assertFalse(strict.valid)
    }

    @Test
    fun `toMatchResult 字段对应`() {
        val (mapBgr, templBgr, _) = synth()
        val r = MapLocatorRefinePure.refineSubpixel(
            mapBgr, mapW, mapH, templBgr, templW, templH,
            coarseX = 137, coarseY = 89, searchRadius = 3,
            maskKind = MapLocatorRefinePure.TemplateMaskKind.ALL_VALID,
        )!!
        val raw = r.toMatchResult()
        assertEquals(r.score, raw.score, 1e-12)
        assertEquals(r.x, raw.locX, 1e-12)
        assertEquals(r.y, raw.locY, 1e-12)
        assertEquals(r.psr, raw.psr, 1e-12)
        assertEquals(r.delta, raw.delta, 1e-12)
    }
}
