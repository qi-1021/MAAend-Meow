package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapLocatorProbeSupport] 的纯逻辑单测：合成/裁剪/期望框判定/TemplateMatch 覆盖 JSON。
 *
 * 覆盖不了真机那半段（SetRawData → OverrideImage → RunRecognition），那部分交给 CI/真机；
 * 这里保证「喂给 native 的字节与 JSON 本身是对的」。
 */
class MapLocatorProbeSupportTest {

    // ── 常量 ──

    @Test
    fun cvTypeMatchesOpenCv() {
        // CV_8UC3 = CV_MAKETYPE(CV_8U, 3) = 16，与 Go 绑定 cvType8UC3 一致
        assertEquals(16, MapLocatorProbeSupport.CV_8UC3)
        assertEquals(3, MapLocatorProbeSupport.BGR_CHANNELS)
    }

    @Test
    fun defaultMethodIsCcoeffNormed() {
        assertEquals(5, MapLocatorProbeSupport.DEFAULT_METHOD)
    }

    // ── 合成 ──

    @Test
    fun synthesizeSizeIsWidthHeightChannels() {
        val bytes = MapLocatorProbeSupport.synthesizeBgr(10, 7)
        assertEquals(10 * 7 * 3, bytes.size)
    }

    @Test
    fun synthesizeIsDeterministic() {
        val a = MapLocatorProbeSupport.synthesizeBgr(16, 12, 1234L)
        val b = MapLocatorProbeSupport.synthesizeBgr(16, 12, 1234L)
        assertTrue("同一 seed 必须逐字节一致", a.contentEquals(b))
    }

    @Test
    fun synthesizeDiffersBySeed() {
        val a = MapLocatorProbeSupport.synthesizeBgr(16, 12, 1L)
        val b = MapLocatorProbeSupport.synthesizeBgr(16, 12, 2L)
        assertFalse("不同 seed 不应逐字节相同", a.contentEquals(b))
    }

    @Test
    fun synthesizeNotConstant() {
        val bytes = MapLocatorProbeSupport.synthesizeBgr(8, 8)
        assertTrue("噪声图不应是常数（CCOEFF_NORMED 会退化）", bytes.distinct().size > 8)
    }

    @Test
    fun synthesizeRejectsNonPositive() {
        try {
            MapLocatorProbeSupport.synthesizeBgr(0, 5)
            throw AssertionError("0 宽应被拒绝")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    // ── 裁剪 ──

    @Test
    fun cropExtractsExactRows() {
        // 4x3 图，每像素 3 字节，值即线性下标
        val full = ByteArray(4 * 3 * 3) { it.toByte() }
        val patch = MapLocatorProbeSupport.cropBgr(full, 4, 3, 1, 1, 2, 2)
        assertNotNull(patch)
        val expected = byteArrayOf(
            15, 16, 17, 18, 19, 20, // 第 1 行：x=1..2
            27, 28, 29, 30, 31, 32, // 第 2 行：x=1..2
        )
        assertTrue("裁剪内容应为连续两行", patch!!.contentEquals(expected))
    }

    @Test
    fun cropRejectsOutOfBounds() {
        val full = ByteArray(4 * 3 * 3)
        assertNull(MapLocatorProbeSupport.cropBgr(full, 4, 3, 3, 0, 2, 2))
        assertNull(MapLocatorProbeSupport.cropBgr(full, 4, 3, 0, 2, 2, 2))
    }

    @Test
    fun cropRejectsNegativeCoords() {
        val full = ByteArray(4 * 3 * 3)
        assertNull(MapLocatorProbeSupport.cropBgr(full, 4, 3, -1, 0, 2, 2))
        assertNull(MapLocatorProbeSupport.cropBgr(full, 4, 3, 0, -1, 2, 2))
    }

    @Test
    fun cropRejectsZeroSize() {
        val full = ByteArray(4 * 3 * 3)
        assertNull(MapLocatorProbeSupport.cropBgr(full, 4, 3, 0, 0, 0, 2))
        assertNull(MapLocatorProbeSupport.cropBgr(full, 4, 3, 0, 0, 2, 0))
    }

    @Test
    fun cropRejectsWrongFullLength() {
        val full = ByteArray(4 * 3 * 3 - 1)
        assertNull(MapLocatorProbeSupport.cropBgr(full, 4, 3, 0, 0, 2, 2))
    }

    @Test
    fun cropRoundTripMatchesDirectRegion() {
        val full = MapLocatorProbeSupport.synthesizeBgr(32, 24)
        val patch = MapLocatorProbeSupport.cropBgr(full, 32, 24, 5, 6, 7, 8)
        assertNotNull(patch)
        // 重新裁同一区域应得到完全相同的字节
        val again = MapLocatorProbeSupport.cropBgr(full, 32, 24, 5, 6, 7, 8)
        assertTrue(patch!!.contentEquals(again!!))
    }

    // ── plan ──

    @Test
    fun syntheticPlanHasCorrectPatchSize() {
        val plan = MapLocatorProbeSupport.syntheticPlan()
        assertNotNull(plan)
        assertEquals(MapLocatorProbeSupport.PATCH_W * MapLocatorProbeSupport.PATCH_H * 3, plan!!.patchBgr.size)
        assertEquals(MapLocatorProbeSupport.FULL_WIDTH * MapLocatorProbeSupport.FULL_HEIGHT * 3, plan.fullBgr.size)
    }

    @Test
    fun syntheticPlanPatchIsCropOfFull() {
        val plan = MapLocatorProbeSupport.syntheticPlan()!!
        val cropped = MapLocatorProbeSupport.cropBgr(
            plan.fullBgr,
            plan.fullWidth,
            plan.fullHeight,
            plan.patchX,
            plan.patchY,
            plan.patchW,
            plan.patchH,
        )
        assertTrue(plan.patchBgr.contentEquals(cropped!!))
    }

    @Test
    fun syntheticPlanInvalidRegionIsNull() {
        assertNull(MapLocatorProbeSupport.syntheticPlan(fullWidth = 10, fullHeight = 10, patchX = 8, patchY = 8, patchW = 5, patchH = 5))
    }

    @Test
    fun syntheticPlanDefaultNames() {
        val plan = MapLocatorProbeSupport.syntheticPlan()!!
        assertEquals(MapLocatorProbeSupport.DEFAULT_TEMPLATE_NAME, plan.templateName)
        assertEquals(MapLocatorProbeSupport.DEFAULT_PROBE_NODE, plan.probeNode)
    }

    @Test
    fun expectedBoxIsCropRect() {
        val plan = MapLocatorProbeSupport.syntheticPlan()!!
        val box = plan.expectedBox
        assertEquals(4, box.size)
        assertEquals(plan.patchX, box[0])
        assertEquals(plan.patchY, box[1])
        assertEquals(plan.patchW, box[2])
        assertEquals(plan.patchH, box[3])
    }

    // ── 期望框判定 ──

    @Test
    fun boxNearExactIsTrue() {
        val expected = intArrayOf(100, 50, 20, 20)
        assertTrue(MapLocatorProbeSupport.isBoxNearExpected(expected.copyOf(), expected))
    }

    @Test
    fun boxNearWithinToleranceIsTrue() {
        val expected = intArrayOf(100, 50, 20, 20)
        val actual = intArrayOf(102, 48, 21, 19)
        assertTrue(MapLocatorProbeSupport.isBoxNearExpected(actual, expected, tolerance = 2))
    }

    @Test
    fun boxOutsideToleranceIsFalse() {
        val expected = intArrayOf(100, 50, 20, 20)
        val actual = intArrayOf(103, 50, 20, 20)
        assertFalse(MapLocatorProbeSupport.isBoxNearExpected(actual, expected, tolerance = 2))
    }

    @Test
    fun boxSizeMismatchIsFalse() {
        val expected = intArrayOf(100, 50, 20, 20)
        val actual = intArrayOf(100, 50, 25, 20)
        assertFalse(MapLocatorProbeSupport.isBoxNearExpected(actual, expected, tolerance = 2))
    }

    @Test
    fun boxNullIsFalse() {
        assertFalse(MapLocatorProbeSupport.isBoxNearExpected(null, intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun boxShortArrayIsFalse() {
        assertFalse(MapLocatorProbeSupport.isBoxNearExpected(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun boxNegativeToleranceBehavesAsZero() {
        val expected = intArrayOf(10, 10, 5, 5)
        assertTrue(MapLocatorProbeSupport.isBoxNearExpected(expected.copyOf(), expected, tolerance = -3))
        assertFalse(MapLocatorProbeSupport.isBoxNearExpected(intArrayOf(11, 10, 5, 5), expected, tolerance = -3))
    }

    @Test
    fun describeMentionsBothBoxes() {
        val text = MapLocatorProbeSupport.describe(intArrayOf(1, 2, 3, 4), intArrayOf(1, 2, 3, 5), 2)
        assertTrue(text.contains("expected=[1,2,3,4]"))
        assertTrue(text.contains("actual=[1,2,3,5]"))
    }

    @Test
    fun describeHandlesNullActual() {
        val text = MapLocatorProbeSupport.describe(intArrayOf(1, 2, 3, 4), null, 2)
        assertTrue(text.contains("actual=[null]"))
    }

    // ── TemplateMatch 覆盖 JSON ──

    @Test
    fun overrideHasTemplateMatchRecognition() {
        val json = Json.parseToJsonElement(
            MapLocatorProbeSupport.buildTemplateMatchOverride(),
        ).jsonObject
        val node = json[MapLocatorProbeSupport.DEFAULT_PROBE_NODE]!!.jsonObject
        val reco = node["recognition"]!!.jsonObject
        assertEquals("TemplateMatch", reco["type"]!!.jsonPrimitive.content)
        assertEquals("DoNothing", node["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun overrideTemplateIsArrayWithName() {
        val json = Json.parseToJsonElement(
            MapLocatorProbeSupport.buildTemplateMatchOverride(templateName = "Custom/Patch.png"),
        ).jsonObject
        val param = json[MapLocatorProbeSupport.DEFAULT_PROBE_NODE]!!.jsonObject["recognition"]!!.jsonObject["param"]!!.jsonObject
        val template = param["template"]!!.jsonArray
        assertEquals(1, template.size)
        assertEquals("Custom/Patch.png", template[0].jsonPrimitive.content)
    }

    @Test
    fun overrideCarriesMethodMaskThreshold() {
        val json = Json.parseToJsonElement(
            MapLocatorProbeSupport.buildTemplateMatchOverride(method = 5, greenMask = true, threshold = 0.9),
        ).jsonObject
        val param = json[MapLocatorProbeSupport.DEFAULT_PROBE_NODE]!!.jsonObject["recognition"]!!.jsonObject["param"]!!.jsonObject
        assertEquals(5, param["method"]!!.jsonPrimitive.int)
        assertTrue(param["green_mask"]!!.jsonPrimitive.boolean)
        assertEquals(0.9, param["threshold"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun overrideOmitsRoiWhenNull() {
        val json = Json.parseToJsonElement(
            MapLocatorProbeSupport.buildTemplateMatchOverride(roi = null),
        ).jsonObject
        val param = json[MapLocatorProbeSupport.DEFAULT_PROBE_NODE]!!.jsonObject["recognition"]!!.jsonObject["param"]!!.jsonObject
        assertNull(param["roi"])
    }

    @Test
    fun overrideIncludesRoiWhenValid() {
        val json = Json.parseToJsonElement(
            MapLocatorProbeSupport.buildTemplateMatchOverride(roi = intArrayOf(1, 2, 3, 4)),
        ).jsonObject
        val param = json[MapLocatorProbeSupport.DEFAULT_PROBE_NODE]!!.jsonObject["recognition"]!!.jsonObject["param"]!!.jsonObject
        val roi = param["roi"]!!.jsonArray
        assertEquals(4, roi.size)
        assertEquals(1, roi[0].jsonPrimitive.int)
        assertEquals(4, roi[3].jsonPrimitive.int)
    }

    @Test
    fun overrideUsesCustomNodeName() {
        val json = Json.parseToJsonElement(
            MapLocatorProbeSupport.buildTemplateMatchOverride(nodeName = "MyProbe"),
        ).jsonObject
        assertNotNull(json["MyProbe"])
        assertNull(json[MapLocatorProbeSupport.DEFAULT_PROBE_NODE])
    }
}
