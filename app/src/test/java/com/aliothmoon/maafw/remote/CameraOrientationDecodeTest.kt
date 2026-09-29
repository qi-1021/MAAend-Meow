package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.CameraOrientationDecode.decodePmf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CameraOrientation PMF 解码测试（对齐 `CameraOrientationPredictor.cpp:234-291`）。
 *
 * 重点：跨 0/360 的绿窗圆均值不能翻到反向、置信度的合成模长 × cos 对齐、
 * 以及 [0,1] 裁剪。
 */
class CameraOrientationDecodeTest {

    @Test
    fun `空 pmf 返回 null`() {
        assertNull(decodePmf(FloatArray(0)))
    }

    @Test
    fun `单峰对准 0 度且置信度为 1`() {
        val pmf = FloatArray(360)
        pmf[0] = 1f
        val r = decodePmf(pmf)!!
        assertEquals(0.0, r.rot, 1e-9)
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test
    fun `单峰对准 90 与 180 度`() {
        val a = FloatArray(360).also { it[90] = 1f }
        assertEquals(90.0, decodePmf(a)!!.rot, 1e-9)

        val b = FloatArray(360).also { it[180] = 1f }
        assertEquals(180.0, decodePmf(b)!!.rot, 1e-9)
    }

    @Test
    fun `单峰在 359 度归一化到 0 到 360 区间`() {
        val pmf = FloatArray(360)
        pmf[359] = 1f
        val r = decodePmf(pmf)!!
        assertEquals(359.0, r.rot, 1e-6)
        assertTrue(r.rot >= 0.0)
        assertEquals(1.0, r.confidence, 1e-6)
    }

    @Test
    fun `跨 0 度窗口的圆均值不翻转`() {
        // 0/1/359 三处等权，圆均值应贴在 0 度附近，而不是 180
        val pmf = FloatArray(360)
        pmf[0] = 1f
        pmf[1] = 1f
        pmf[359] = 1f
        val r = decodePmf(pmf)!!
        assertTrue("rot=${r.rot}", r.rot < 1e-6 || r.rot > 360.0 - 1e-6)
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test
    fun `均匀分布置信度趋零`() {
        val pmf = FloatArray(360) { 1.0f / 360f }
        val r = decodePmf(pmf)!!
        assertTrue("confidence=${r.confidence}", r.confidence < 1e-6)
    }

    @Test
    fun `两个反向等权峰合成模长趋零`() {
        val pmf = FloatArray(360)
        pmf[0] = 1f
        pmf[180] = 1f
        val r = decodePmf(pmf)!!
        assertTrue("confidence=${r.confidence}", r.confidence < 1e-6)
    }

    @Test
    fun `非 360 bin 时按等角 bin 解码`() {
        // 8 bin -> 每 bin 45 度；bin7 = 315 度
        val pmf = FloatArray(8)
        pmf[7] = 1f
        val r = decodePmf(pmf)!!
        assertEquals(315.0, r.rot, 1e-6)
        assertEquals(1.0, r.confidence, 1e-6)
    }

    @Test
    fun `非 360 bin 的跨零圆均值`() {
        // bin0=0 度、bin7=315 度等权，窗口均值应为 337.5 度
        val pmf = FloatArray(8)
        pmf[0] = 1f
        pmf[7] = 1f
        val r = decodePmf(pmf)!!
        assertEquals(337.5, r.rot, 1e-6)
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test
    fun `解码方向与合成方向强烈不一致时置信度裁到 0`() {
        // argmax 在 0 度（窗口均值 ~0），但整体质量在 180/181 度 -> 夹角 > 90 度
        val pmf = FloatArray(360)
        pmf[0] = 1f
        pmf[180] = 0.9f
        pmf[181] = 0.9f
        val r = decodePmf(pmf)!!
        assertEquals(0.0, r.rot, 1e-9)
        assertEquals(0.0, r.confidence, 1e-9)
    }

    @Test
    fun `置信度上限裁剪到 1`() {
        val pmf = FloatArray(360)
        pmf[0] = 2f // 非归一化，合成模长 > 1
        val r = decodePmf(pmf)!!
        assertEquals(0.0, r.rot, 1e-9)
        assertEquals(1.0, r.confidence, 0.0)
    }

    @Test
    fun `全零 pmf 返回零朝向与零置信度`() {
        val r = decodePmf(FloatArray(360))!!
        assertEquals(0.0, r.rot, 0.0)
        assertEquals(0.0, r.confidence, 0.0)
    }

    @Test
    fun `单 bin 输入不崩且解码为 0`() {
        val r = decodePmf(floatArrayOf(1f))!!
        assertEquals(0.0, r.rot, 0.0)
        assertEquals(1.0, r.confidence, 1e-9)
    }
}
