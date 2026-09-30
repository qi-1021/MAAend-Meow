package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapLocatorCalibration] 纯逻辑测试。
 *
 * 覆盖：玩家标记偏移修正（含模板缩放因子）、缩放尺寸取整、灰/彩/掩膜三种缩放的
 * 恒等与缩放行为、越界与非法入参兜底。
 */
class MapLocatorCalibrationTest {

    private fun pos(x: Double, y: Double) = MapPosition(zoneId = "Z", x = x, y = y, score = 0.5)

    @Test
    fun `常量是标定值`() {
        assertEquals(25.0, MapLocatorCalibration.PLAYER_MARKER_OFFSET_X, 0.0)
        assertEquals(19.0, MapLocatorCalibration.PLAYER_MARKER_OFFSET_Y, 0.0)
    }

    @Test
    fun `默认缩放为1时直接加偏移`() {
        val out = MapLocatorCalibration.toPlayerPosition(pos(100.0, 200.0), templateScale = 1.0)
        assertEquals(125.0, out.x, 1e-9)
        assertEquals(219.0, out.y, 1e-9)
        assertEquals("Z", out.zoneId)
        assertEquals(0.5, out.score, 1e-9)
    }

    @Test
    fun `偏移随模板缩放因子缩放`() {
        val out = MapLocatorCalibration.toPlayerPosition(pos(0.0, 0.0), templateScale = 15.0 / 16.0)
        assertEquals(25.0 * 15.0 / 16.0, out.x, 1e-9)
        assertEquals(19.0 * 15.0 / 16.0, out.y, 1e-9)
    }

    @Test
    fun `非法缩放原样返回`() {
        assertEquals(pos(1.0, 2.0), MapLocatorCalibration.toPlayerPosition(pos(1.0, 2.0), templateScale = 0.0))
        assertEquals(pos(1.0, 2.0), MapLocatorCalibration.toPlayerPosition(pos(1.0, 2.0), templateScale = -1.0))
    }

    @Test
    fun `自定义偏移可覆盖默认`() {
        val out = MapLocatorCalibration.toPlayerPosition(pos(0.0, 0.0), 1.0, offsetX = 3.0, offsetY = -4.0)
        assertEquals(3.0, out.x, 1e-9)
        assertEquals(-4.0, out.y, 1e-9)
    }

    @Test
    fun `缩放尺寸四舍五入且下限为1`() {
        assertEquals(0, MapLocatorCalibration.scaledSize(0, 0.5))
        assertEquals(0, MapLocatorCalibration.scaledSize(10, 0.0))
        assertEquals(118, MapLocatorCalibration.scaledSize(118, 1.0))
        // 118 * 15/16 = 110.625 -> cvRound = 111
        assertEquals(111, MapLocatorCalibration.scaledSize(118, 15.0 / 16.0))
        assertEquals(1, MapLocatorCalibration.scaledSize(1, 0.4))
    }

    @Test
    fun `灰度缩放恒等返回拷贝`() {
        val src = byteArrayOf(1, 2, 3, 4)
        val out = MapLocatorCalibration.scaleGray(src, 2, 2, 1.0)
        assertTrue(out!!.contentEquals(src))
        // 长度不符 / 非法尺寸返回 null
        assertNull(MapLocatorCalibration.scaleGray(src, 3, 2, 1.0))
        assertNull(MapLocatorCalibration.scaleGray(src, 2, 2, 0.0))
        assertNull(MapLocatorCalibration.scaleGray(src, 0, 2, 1.0))
    }

    @Test
    fun `灰度缩小尺寸与均值趋势`() {
        // 2x2 全 100 -> 1x1 仍 100
        val src = ByteArray(4) { 100 }
        val out = MapLocatorCalibration.scaleGray(src, 2, 2, 0.5)
        assertEquals(1, out!!.size)
        assertEquals(100, out[0].toInt() and 0xFF)
    }

    @Test
    fun `彩色缩放保持通道与长度`() {
        val src = ByteArray(2 * 2 * 3) { 7 }
        val out = MapLocatorCalibration.scaleBgr(src, 2, 2, 0.5)
        assertEquals(1 * 1 * 3, out!!.size)
        assertEquals(7, out[0].toInt() and 0xFF)
        assertNull(MapLocatorCalibration.scaleBgr(src, 2, 2, 0.0))
        assertNull(MapLocatorCalibration.scaleBgr(ByteArray(3), 2, 2, 1.0))
    }

    @Test
    fun `掩膜最近邻缩放`() {
        val mask = booleanArrayOf(true, false, false, true)
        val out = MapLocatorCalibration.scaleMask(mask, 2, 2, 0.5)
        // 目标 (0,0) -> 源 (floor(0/0.5)=0,0) = true
        assertEquals(1, out!!.size)
        assertEquals(true, out[0])
        // 恒等拷贝
        assertTrue(MapLocatorCalibration.scaleMask(mask, 2, 2, 1.0)!!.contentEquals(mask))
        assertNull(MapLocatorCalibration.scaleMask(mask, 3, 2, 1.0))
    }

    @Test
    fun `放大掩膜按最近邻取源`() {
        val mask = booleanArrayOf(true, false, false, false)
        val out = MapLocatorCalibration.scaleMask(mask, 2, 2, 2.0)
        assertEquals(16, out!!.size)
        // 目标 (0..1,0..1) 源 (0,0)=true；(2,2)源(1,1)=false
        assertEquals(true, out[0])
        assertEquals(false, out[2 * 4 + 2])
    }
}
