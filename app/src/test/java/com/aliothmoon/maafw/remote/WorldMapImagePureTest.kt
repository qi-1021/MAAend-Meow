package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldMapImagePure] 的纯逻辑测试：整数倍 INTER_AREA 降采样、双线性缩放、
 * 灰度复制 BGR、窗口裁剪。
 */
class WorldMapImagePureTest {

    @Test
    fun `downscaledSize 整数倍`() {
        assertEquals(360 to 337, WorldMapImagePure.downscaledSize(1440, 1350, 4))
        assertEquals(504 to 744, WorldMapImagePure.downscaledSize(2016, 2976, 4))
        assertEquals(1 to 1, WorldMapImagePure.downscaledSize(3, 3, 4))
    }

    @Test
    fun `downscaleGrayArea 取块均值`() {
        val src = ByteArray(16) { it.toByte() } // value = y*4+x
        val out = WorldMapImagePure.downscaleGrayArea(src, 4, 4, 2)
        assertTrue("block mean", out!!.contentEquals(byteArrayOf(2, 4, 10, 12)))
    }

    @Test
    fun `downscaleGrayArea 长度不符返回 null`() {
        assertNull(WorldMapImagePure.downscaleGrayArea(ByteArray(15), 4, 4, 2))
    }

    @Test
    fun `downscaleBgrArea 三通道均值`() {
        val pixel = byteArrayOf(10, 20, 30)
        val src = ByteArray(4 * 4 * 3)
        for (i in 0 until 16) {
            System.arraycopy(pixel, 0, src, i * 3, 3)
        }
        val out = WorldMapImagePure.downscaleBgrArea(src, 4, 4, 2)!!
        assertEquals(2 * 2 * 3, out.size)
        assertTrue("px0", out.copyOfRange(0, 3).contentEquals(byteArrayOf(10, 20, 30)))
        assertTrue("px3", out.copyOfRange(9, 12).contentEquals(byteArrayOf(10, 20, 30)))
    }

    @Test
    fun `resizeGrayBilinear 同尺寸恒等`() {
        val src = byteArrayOf(1, 2, 3, 4)
        val out = WorldMapImagePure.resizeGrayBilinear(src, 2, 2, 2, 2)
        assertTrue("identity", out!!.contentEquals(src))
    }

    @Test
    fun `resizeGrayBilinear 均匀图保持均匀`() {
        val src = ByteArray(16) { 7 }
        val out = WorldMapImagePure.resizeGrayBilinear(src, 4, 4, 2, 2)
        assertTrue("uniform", out!!.contentEquals(byteArrayOf(7, 7, 7, 7)))
    }

    @Test
    fun `grayToBgr 三通道复制`() {
        val out = WorldMapImagePure.grayToBgr(byteArrayOf(1, 2))
        assertTrue("grayToBgr", out.contentEquals(byteArrayOf(1, 1, 1, 2, 2, 2)))
    }

    @Test
    fun `cropGray 越界返回 null`() {
        val src = ByteArray(16) { it.toByte() }
        val out = WorldMapImagePure.cropGray(src, 4, 4, 1, 1, 2, 2)
        assertTrue("crop", out!!.contentEquals(byteArrayOf(5, 6, 9, 10)))
        assertNull(WorldMapImagePure.cropGray(src, 4, 4, 3, 3, 2, 2))
    }
}
