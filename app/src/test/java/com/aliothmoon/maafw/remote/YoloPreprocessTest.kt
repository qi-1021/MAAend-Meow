package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YoloPreprocess] 的纯逻辑测试：裁剪/贴画布、圆形 mask、通道顺序与边界。
 *
 * 这些断言在**无 Android / 无 JNA** 下可跑（见 [scripts/verify_pure_logic.sh]），
 * 真机上 `yoloprobe` 若分类不对，先回到这里排除「预处理错」。
 */
class YoloPreprocessTest {

    private fun solidBgr(w: Int, h: Int, b: Int, g: Int, r: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            out[i * 3] = b.toByte()
            out[i * 3 + 1] = g.toByte()
            out[i * 3 + 2] = r.toByte()
        }
        return out
    }

    private fun pixel(buf: ByteArray, width: Int, x: Int, y: Int): Triple<Int, Int, Int> {
        val o = (y * width + x) * 3
        return Triple(buf[o].toInt() and 0xFF, buf[o + 1].toInt() and 0xFF, buf[o + 2].toInt() and 0xFF)
    }

    private fun setPixel(buf: ByteArray, width: Int, x: Int, y: Int, b: Int, g: Int, r: Int) {
        val o = (y * width + x) * 3
        buf[o] = b.toByte()
        buf[o + 1] = g.toByte()
        buf[o + 2] = r.toByte()
    }

    // ───────────────────── 非法输入 ─────────────────────

    @Test
    fun `尺寸非正返回 null`() {
        assertNull(YoloPreprocess.preprocessBgr(ByteArray(0), 0, 128))
        assertNull(YoloPreprocess.preprocessBgr(ByteArray(0), 128, 0))
    }

    @Test
    fun `长度与尺寸不匹配返回 null`() {
        assertNull(YoloPreprocess.preprocessBgr(ByteArray(10), 128, 128, 3))
    }

    @Test
    fun `非法通道数返回 null`() {
        assertNull(YoloPreprocess.preprocessBgr(ByteArray(128 * 128 * 2), 128, 128, 2))
    }

    // ───────────────────── 128×128 实心图：mask 内保留、外清零 ─────────────────────

    @Test
    fun `128x128 实心图输出尺寸与中心保留`() {
        val src = solidBgr(128, 128, 10, 20, 30)
        val out = YoloPreprocess.preprocessBgr(src, 128, 128)!!
        assertEquals(128 * 128 * 3, out.size)
        assertEquals(Triple(10, 20, 30), pixel(out, 128, 64, 64))
    }

    @Test
    fun `mask 边缘按 OpenCV 圆栅格裁剪`() {
        val src = solidBgr(128, 128, 10, 20, 30)
        val out = YoloPreprocess.preprocessBgr(src, 128, 128)!!
        // row 64 的可视区间是 [11, 117]（radius=53）
        assertEquals(Triple(10, 20, 30), pixel(out, 128, 11, 64))
        assertEquals(Triple(10, 20, 30), pixel(out, 128, 117, 64))
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 10, 64))
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 118, 64))
        // 竖直方向同理
        assertEquals(Triple(10, 20, 30), pixel(out, 128, 64, 11))
        assertEquals(Triple(10, 20, 30), pixel(out, 128, 64, 117))
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 64, 10))
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 64, 118))
        // 角落
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 0, 0))
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 127, 127))
    }

    @Test
    fun `默认参数下圆 mask 与精确圆盘逐像素一致`() {
        val mask = YoloPreprocess.circleMask()
        val r = 53
        var count = 0
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val dx = x - 64
                val dy = y - 64
                val inside = dx * dx + dy * dy <= r * r
                assertEquals("pixel ($x,$y)", inside, mask[y * 128 + x])
                if (mask[y * 128 + x]) count++
            }
        }
        assertEquals(8809, count)
    }

    // ───────────────────── 比 128 小：居中贴，其余黑边 ─────────────────────

    @Test
    fun `小图居中贴黑边且中心像素映射正确`() {
        val src = solidBgr(100, 60, 10, 20, 30)
        // 输入 (50,30) → 画布 (14+50, 34+30) = (64,64)
        setPixel(src, 100, 50, 30, 1, 2, 3)
        val out = YoloPreprocess.preprocessBgr(src, 100, 60)!!
        assertEquals(Triple(1, 2, 3), pixel(out, 128, 64, 64))
        // 左/上黑边（贴图之前）为 0
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 0, 0))
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 120, 120))
        // 图像落在画布 x∈[14,113]、y∈[34,93]；y=10 是上黑边
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 60, 10))
    }

    // ───────────────────── 非方形大图：中心裁 ─────────────────────

    @Test
    fun `非方形大图中心裁到 128 后中心像素映射正确`() {
        val src = solidBgr(200, 100, 10, 20, 30)
        // crop 128×100，源起点 (36, 0)，画布 y 从 14 起。
        // 画布中心 (64,64) ← 源 (36+64, 0+(64-14)) = (100, 50)
        setPixel(src, 200, 100, 50, 7, 8, 9)
        val out = YoloPreprocess.preprocessBgr(src, 200, 100)!!
        assertEquals(Triple(7, 8, 9), pixel(out, 128, 64, 64))
        // 画布 (0,0) ← 源 (36,0)，但被 mask 清零
        assertEquals(Triple(0, 0, 0), pixel(out, 128, 0, 0))
    }

    @Test
    fun `宽度大于 128 时两侧被裁掉`() {
        val src = solidBgr(300, 128, 10, 20, 30)
        // 源中心 (150,64)；crop 128 从 x=86 起
        setPixel(src, 300, 150, 64, 4, 5, 6)   // 映射到画布 (64,64)
        setPixel(src, 300, 0, 64, 1, 1, 1)     // 源最左被裁掉
        setPixel(src, 300, 299, 64, 2, 2, 2)   // 源最右被裁掉
        val out = YoloPreprocess.preprocessBgr(src, 300, 128)!!
        assertEquals(Triple(4, 5, 6), pixel(out, 128, 64, 64))
    }

    // ───────────────────── BGRA ─────────────────────

    @Test
    fun `BGRA 输入丢弃 alpha 保留 BGR`() {
        val src = ByteArray(128 * 128 * 4)
        for (i in 0 until 128 * 128) {
            src[i * 4] = 10
            src[i * 4 + 1] = 20
            src[i * 4 + 2] = 30
            src[i * 4 + 3] = 99 // alpha 应被忽略
        }
        val out = YoloPreprocess.preprocessBgr(src, 128, 128, 4)!!
        assertEquals(Triple(10, 20, 30), pixel(out, 128, 64, 64))
    }

    // ───────────────────── ARGB → BGR ─────────────────────

    @Test
    fun `argbToBgr 通道顺序与 alpha 丢弃`() {
        assertTrue(
            byteArrayOf(0x33, 0x22, 0x11)
                .contentEquals(YoloPreprocess.argbToBgr(intArrayOf(0x00112233))),
        )
        assertTrue(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
                .contentEquals(YoloPreprocess.argbToBgr(intArrayOf(0xFFFFFFFF.toInt()))),
        )
    }

    @Test
    fun `preprocessArgb 与手动两步一致`() {
        val argb = IntArray(128 * 128) { 0xFF112233.toInt() } // A=FF, R=11, G=22, B=33
        val viaHelper = YoloPreprocess.preprocessArgb(argb, 128, 128)!!
        val manual = YoloPreprocess.preprocessBgr(YoloPreprocess.argbToBgr(argb), 128, 128)!!
        assertTrue(manual.contentEquals(viaHelper))
        assertEquals(Triple(0x33, 0x22, 0x11), pixel(viaHelper, 128, 64, 64))
    }

    // ───────────────────── mask 辅助 ─────────────────────

    @Test
    fun `applyCircleMask 尺寸不符时抛错`() {
        var threw = false
        try {
            YoloPreprocess.applyCircleMask(ByteArray(10))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `半径为 0 只保留中心`() {
        val mask = YoloPreprocess.circleMask(size = 8, centerX = 4, centerY = 4, radius = 0)
        val kept = mask.indices.filter { mask[it] }
        assertEquals(listOf(4 * 8 + 4), kept)
    }
}
