package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.MapNavHeading.estimateFromBgr
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapNavHeading] 的合成数据测试（对齐 `MapAlgorithm.cpp:143-281`）。
 *
 * 合成一张 24×24 小地图：深色底 + 中心附近的亮色三角箭头；旋转三角得到 8 个方向。
 * 注意上游的掩膜是"每通道 >=220"的**白色**范围（`:170`），所以这里的"黄"必须足够亮
 * （三通道都 >=220），纯饱和黄 (BGR 0,255,255) 过不了掩膜——见 `纯饱和黄不匹配上游白色掩膜`。
 */
class MapNavHeadingTest {

    private val w = 24
    private val h = 24

    // 高亮暖黄：B=229,G=247,R=255，三通道都 >= 220。
    private val arrowB = 229
    private val arrowG = 247
    private val arrowR = 255

    private fun darkImage(): ByteArray {
        val arr = ByteArray(w * h * 3)
        for (i in arr.indices step 3) {
            arr[i] = 18
            arr[i + 1] = 20
            arr[i + 2] = 22
        }
        return arr
    }

    private fun setPixel(arr: ByteArray, x: Int, y: Int, b: Int, g: Int, r: Int) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        val i = (y * w + x) * 3
        arr[i] = b.toByte()
        arr[i + 1] = g.toByte()
        arr[i + 2] = r.toByte()
    }

    /**
     * 在图上画一个指向 [angleDeg]（0=正上/北，顺时针）的等腰三角。
     * 基准三角尖朝上、面积质心恰在中心（质心离 ROI 中心 0px，远小于上游 5px 上限）。
     */
    private fun drawArrow(
        arr: ByteArray,
        angleDeg: Double,
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        b: Int = arrowB,
        g: Int = arrowG,
        r: Int = arrowR,
    ) {
        val rad = angleDeg * Math.PI / 180.0
        val c = cos(rad)
        val s = sin(rad)
        val cx = w / 2.0 + offsetX
        val cy = h / 2.0 + offsetY
        val rel = arrayOf(
            doubleArrayOf(0.0, -9.0), // 尖
            doubleArrayOf(-6.0, 4.5), // 左底角
            doubleArrayOf(6.0, 4.5), // 右底角
        )
        val abs = Array(3) { i ->
            val dx = rel[i][0]
            val dy = rel[i][1]
            doubleArrayOf(cx + dx * c - dy * s, cy + dx * s + dy * c)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (pointInTriangle(x.toDouble(), y.toDouble(), abs[0], abs[1], abs[2])) {
                    setPixel(arr, x, y, b, g, r)
                }
            }
        }
    }

    /** 把箭头以 [alpha] 不透明度叠在深色底上（模拟半透明/抗锯齿）。 */
    private fun drawArrowBlended(arr: ByteArray, angleDeg: Double, alpha: Double) {
        // 这里用更白的箭头，便于 0.9 alpha 仍过 220 阈值。
        val b = (alpha * 250 + (1 - alpha) * 18).toInt()
        val g = (alpha * 253 + (1 - alpha) * 20).toInt()
        val r = (alpha * 255 + (1 - alpha) * 22).toInt()
        drawArrow(arr, angleDeg, b = b, g = g, r = r)
    }

    private fun sign(px: Double, py: Double, a: DoubleArray, b: DoubleArray): Double =
        (px - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (py - b[1])

    private fun pointInTriangle(px: Double, py: Double, a: DoubleArray, b: DoubleArray, c: DoubleArray): Boolean {
        val d1 = sign(px, py, a, b)
        val d2 = sign(px, py, b, c)
        val d3 = sign(px, py, c, a)
        val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
        val hasPos = d1 > 0 || d2 > 0 || d3 > 0
        return !(hasNeg && hasPos)
    }

    private fun angularError(actual: Double, expected: Double): Double {
        var d = abs(actual - expected) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    // ───────────────────────── 方向精度 ─────────────────────────

    @Test
    fun `八个方向角度误差均小于 10 度`() {
        val expected = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
        val sb = StringBuilder()
        var maxErr = 0.0
        for (e in expected) {
            val img = darkImage()
            drawArrow(img, e)
            val got = estimateFromBgr(img, w, h)
            assertTrue("dir=$e 估出 null", got != null)
            val err = angularError(got!!, e)
            if (err > maxErr) maxErr = err
            sb.append("  expected=" + "%.1f".format(e) + "  got=" + "%.2f".format(got) + "  err=" + "%.2f".format(err) + "\n")
            assertTrue("dir=$e got=$got err=$err", err < 10.0)
        }
        println("MapNavHeading 方向误差表:")
        print(sb)
        println("  maxErr=" + "%.2f".format(maxErr))
    }

    @Test
    fun `任意角度实测误差表`() {
        // 8 个正/斜方向恰好是正方形像素栅格的对称方向，离散误差为 0；这里补几个非对称角，
        // 展示真实的栅格化离散误差量级。
        val angles = listOf(10.0, 30.0, 75.0, 120.0, 200.0, 250.0, 340.0)
        val sb = StringBuilder()
        var maxErr = 0.0
        for (e in angles) {
            val img = darkImage()
            drawArrow(img, e)
            val got = estimateFromBgr(img, w, h)
            assertTrue("dir=$e 估出 null", got != null)
            val err = angularError(got!!, e)
            if (err > maxErr) maxErr = err
            sb.append("  expected=" + "%.1f".format(e) + "  got=" + "%.3f".format(got) + "  err=" + "%.3f".format(err) + "\n")
            assertTrue("dir=$e got=$got err=$err", err < 10.0)
        }
        println("MapNavHeading 非对称角度误差表:")
        print(sb)
        println("  maxErr=" + "%.3f".format(maxErr))
    }

    // ───────────────────────── 退化 ─────────────────────────

    @Test
    fun `全黑返回 null`() {
        assertNull(estimateFromBgr(ByteArray(w * h * 3), w, h))
    }

    @Test
    fun `无亮色像素返回 null`() {
        val arr = ByteArray(w * h * 3)
        for (i in arr.indices) arr[i] = 120.toByte() // 灰底，三通道都 < 220
        assertNull(estimateFromBgr(arr, w, h))
    }

    @Test
    fun `低强度噪声返回 null`() {
        val rnd = java.util.Random(1234L)
        val arr = ByteArray(w * h * 3)
        for (i in arr.indices) arr[i] = rnd.nextInt(180).toByte() // 全部 < 220
        assertNull(estimateFromBgr(arr, w, h))
    }

    @Test
    fun `图片过小返回 null`() {
        // 中心 24×24 ROI 放不下（上游 MapAlgorithm.cpp:154-156）。
        assertNull(estimateFromBgr(ByteArray(20 * 20 * 3), 20, 20))
    }

    @Test
    fun `字节数不足返回 null`() {
        assertNull(estimateFromBgr(ByteArray(3), w, h))
    }

    @Test
    fun `箭头远离中心返回 null`() {
        // 质心离 ROI 中心 > 5px（上游 minDistSq > 25，MapAlgorithm.cpp:201）。
        val img = darkImage()
        drawArrow(img, 0.0, offsetX = -7.0, offsetY = -7.0)
        assertNull(estimateFromBgr(img, w, h))
    }

    @Test
    fun `真机几何：玩家箭头偏离中心，须按标定偏移采样才能估出方向`() {
        // 复现本设备实测（见 MapLocatorCalibration）：小地图 ROI 中心不是玩家箭头中心，
        // 箭头稳定偏约 (+25,+19)。不传偏移时采样窗（中心 ±12）完全落空 → null；
        // 传入标定偏移后采样窗落到箭头上 → 正常估出方向。
        val bw = 118
        val bh = 120
        val img = ByteArray(bw * bh * 3)
        for (i in img.indices step 3) {
            img[i] = 18
            img[i + 1] = 20
            img[i + 2] = 22
        }
        // 在 (59+25, 60+19) 处画一个朝东（90°）的箭头。
        val cx = bw / 2.0 + 25.0
        val cy = bh / 2.0 + 19.0
        val rad = 90.0 * Math.PI / 180.0
        val c = cos(rad)
        val s = sin(rad)
        val rel = arrayOf(
            doubleArrayOf(0.0, -9.0),
            doubleArrayOf(-6.0, 4.5),
            doubleArrayOf(6.0, 4.5),
        )
        val tri = Array(3) { i ->
            doubleArrayOf(cx + rel[i][0] * c - rel[i][1] * s, cy + rel[i][0] * s + rel[i][1] * c)
        }
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                if (pointInTriangle(x.toDouble(), y.toDouble(), tri[0], tri[1], tri[2])) {
                    val idx = (y * bw + x) * 3
                    img[idx] = arrowB.toByte()
                    img[idx + 1] = arrowG.toByte()
                    img[idx + 2] = arrowR.toByte()
                }
            }
        }

        assertNull("无偏移时上游口径应落空", estimateFromBgr(img, bw, bh))
        val got = estimateFromBgr(img, bw, bh, sampleOffsetX = 25.0, sampleOffsetY = 19.0)
        assertTrue("传偏移后仍 null", got != null)
        assertTrue("got=$got", angularError(got!!, 90.0) < 10.0)
    }

    @Test
    fun `纯饱和黄不匹配上游白色掩膜`() {
        // 上游掩膜是每通道 ∈[220,255] 的白，而不是色相上的黄：
        // BGR(0,255,255) 的 B 通道为 0，过不了掩膜 → 返回 null。逐条对齐 `MapAlgorithm.cpp:170`。
        val img = darkImage()
        drawArrow(img, 0.0, b = 0, g = 255, r = 255)
        assertNull(estimateFromBgr(img, w, h))
    }

    // ───────────────────────── 半透明 / 部分遮挡 ─────────────────────────

    @Test
    fun `半透明箭头仍能估出方向`() {
        // alpha=0.9：叠加后三通道仍 >=220（0.9*250+0.1*18=226），可被掩膜捕获。
        val img = darkImage()
        drawArrowBlended(img, 90.0, 0.9)
        val got = estimateFromBgr(img, w, h)
        assertTrue("got=null", got != null)
        assertTrue("got=$got err=${angularError(got!!, 90.0)}", angularError(got, 90.0) < 10.0)
    }

    @Test
    fun `半透明过深整块掉出阈值返回 null`() {
        // alpha=0.8：0.8*250+0.2*18=203 < 220，整块箭头像素都被掩膜丢弃 → null。
        val img = darkImage()
        drawArrowBlended(img, 90.0, 0.8)
        assertNull(estimateFromBgr(img, w, h))
    }

    @Test
    fun `底边被遮挡仍能估出方向`() {
        // 用深色盖掉底边下方的三角形，只留尖部：形状更小但仍指向正北。
        val img = darkImage()
        drawArrow(img, 0.0)
        for (y in 16 until h) {
            for (x in 0 until w) {
                setPixel(img, x, y, 18, 20, 22)
            }
        }
        val got = estimateFromBgr(img, w, h)
        assertTrue("got=null", got != null)
        assertTrue("got=$got err=${angularError(got!!, 0.0)}", angularError(got, 0.0) < 15.0)
    }
}
