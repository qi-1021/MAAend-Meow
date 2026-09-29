package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [YoloClassifySupport] 的纯逻辑测试：detail JSON 解析、override 构造与类别→zone/ROI 裁决。
 *
 * 覆盖上游 `YoloPredictor.cpp:204-244` 的三条分支（越界 / `None` / 正常映射）。
 */
class YoloClassifySupportTest {

    private val config = YoloConfig(
        classes = listOf("None", "OMVBase01", "ValleyIVMap1Lv01Tier02", "ValleyIV_Base"),
        regionMapping = mapOf("Valle" to "ValleyIV", "OMVBa" to "OMVBase"),
    )

    private val tile = TileRegion(baseClass = "ValleyIV", x = 10, y = 20, w = 30, h = 40, inferMargin = 5)
    private val mapping = YoloMapping(config.regionMapping, mapOf("ValleyIVMap1Lv01Tier02" to tile))

    // ───────────────────── detail 解析 ─────────────────────

    @Test
    fun `解析 best 里的 cls_index 与 box`() {
        val json = """
            {"all":[{"cls_index":2,"label":"X","box":[0,0,128,128],"score":0.7}],
             "filtered":[{"cls_index":2,"label":"X","box":[0,0,128,128],"score":0.7}],
             "best":{"cls_index":2,"label":"X","box":[0,0,128,128],"score":0.9}}
        """.trimIndent()
        val r = YoloClassifySupport.parseClassifyDetail(json)!!
        assertEquals(2, r.clsIndex)
        assertEquals("X", r.label)
        assertEquals(0.9, r.score, 1e-9)
        assertEquals(listOf(0, 0, 128, 128), r.box!!.toList())
    }

    @Test
    fun `best 为 null 时回退 filtered 再回退 all`() {
        val filtered = """{"all":[],"filtered":[{"cls_index":3,"label":"","score":0.1}],"best":null}"""
        assertEquals(3, YoloClassifySupport.parseClassifyDetail(filtered)!!.clsIndex)

        val onlyAll = """{"all":[{"cls_index":1,"label":"","score":0.2}],"filtered":[],"best":null}"""
        assertEquals(1, YoloClassifySupport.parseClassifyDetail(onlyAll)!!.clsIndex)
    }

    @Test
    fun `缺 cls_index 或非法 JSON 返回 null`() {
        assertNull(YoloClassifySupport.parseClassifyDetail("""{"best":{"label":"x"}}"""))
        assertNull(YoloClassifySupport.parseClassifyDetail("not json"))
        assertNull(YoloClassifySupport.parseClassifyDetail(null))
        assertNull(YoloClassifySupport.parseClassifyDetail(""))
    }

    // ───────────────────── override 构造 ─────────────────────

    @Test
    fun `override 含模型名与动作且无模型时抛错由框架处理`() {
        val json = YoloClassifySupport.buildClassifyOverride(model = "cls.onnx", labels = config.classes)
        assertTrue(json.contains("\"NeuralNetworkClassify\""))
        assertTrue(json.contains("\"cls.onnx\""))
        assertTrue(json.contains("\"DoNothing\""))
        assertTrue(json.contains("\"labels\""))
        assertTrue(json.contains("ValleyIVMap1Lv01Tier02"))
        // 不应写入 expected（空 expected = 接受所有结果）
        assertFalse(json.contains("expected"))
    }

    @Test
    fun `无 labels 时省略 labels 字段`() {
        val json = YoloClassifySupport.buildClassifyOverride(model = "cls.onnx")
        assertFalse(json.contains("labels"))
        assertTrue(json.contains("\"cls.onnx\""))
    }

    // ───────────────────── 裁决：三条分支 ─────────────────────

    @Test
    fun `索引越界判无效并保留框架 label`() {
        val out = YoloClassifySupport.resolveClassify(
            YoloClassifySupport.YoloClassifyResult(99, "Unknown_99", 0.5),
            config,
            mapping,
        )
        assertFalse(out.valid)
        assertFalse(out.isNone)
        assertEquals("Unknown_99", out.rawClass)
        assertEquals("", out.zoneId)
    }

    @Test
    fun `None 类跳过定位`() {
        val out = YoloClassifySupport.resolveClassify(
            YoloClassifySupport.YoloClassifyResult(0, "None", 0.99),
            config,
            mapping,
        )
        assertTrue(out.valid)
        assertTrue(out.isNone)
        assertEquals("None", out.zoneId)
        assertFalse(out.hasRoi)
    }

    @Test
    fun `正常类映射到 zone 与 tile ROI`() {
        val out = YoloClassifySupport.resolveClassify(
            YoloClassifySupport.YoloClassifyResult(2, "Unknown_2", 0.8f.toDouble()),
            config,
            mapping,
        )
        assertTrue(out.valid)
        assertFalse(out.isNone)
        assertEquals("ValleyIVMap1Lv01Tier02", out.rawClass)
        assertEquals("ValleyIV_L1_2", out.zoneId)
        assertTrue(out.hasRoi)
        assertEquals(10, out.roiX)
        assertEquals(20, out.roiY)
        assertEquals(30, out.roiW)
        assertEquals(40, out.roiH)
        assertEquals(5, out.inferMargin)
        assertEquals("ValleyIV", out.baseClass)
    }

    @Test
    fun `无 tile 时有效但无 ROI`() {
        val out = YoloClassifySupport.resolveClassify(
            YoloClassifySupport.YoloClassifyResult(1, "", 0.4),
            config,
            mapping,
        )
        assertTrue(out.valid)
        assertEquals("OMVBase01", out.rawClass)
        assertFalse(out.hasRoi)
    }

    @Test
    fun `classes 为空时一律无效`() {
        val out = YoloClassifySupport.resolveClassify(
            YoloClassifySupport.YoloClassifyResult(0, "None", 0.9),
            YoloConfig(),
            YoloMapping(),
        )
        assertFalse(out.valid)
    }

    // ───────────────────── 回显 ─────────────────────

    @Test
    fun `describeOutcome 列出全部关键字段`() {
        val out = YoloClassifySupport.resolveClassify(
            YoloClassifySupport.YoloClassifyResult(2, "Unknown_2", 0.8),
            config,
            mapping,
        )
        val text = YoloClassifySupport.describeOutcome(2, "Unknown_2", out).joinToString("\n")
        assertTrue(text.contains("cls_index : 2"))
        assertTrue(text.contains("ValleyIVMap1Lv01Tier02"))
        assertTrue(text.contains("zone_id   : ValleyIV_L1_2"))
        assertTrue(text.contains("[10,20,30,40]"))
        assertTrue(text.contains("infer_margin=5"))
    }
}
