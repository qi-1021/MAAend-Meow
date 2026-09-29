package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YoloPredictor 名字映射与 sidecar 结构解析测试
 * （对齐 `YoloPredictor.cpp:81-99`、`YoloPredictor.h:33-43`）。
 */
class YoloMappingTest {

    // 键是类别名的前 5 个字符
    private val mapping = YoloMapping(
        regionMapping = mapOf(
            "Map01" to "RegionA",
            "Map00" to "BaseZone",
        ),
    )

    // ───────────────────── convertYoloNameToZoneId ─────────────────────

    @Test
    fun `正则分支生成 L 层级 档位并去掉前导零`() {
        assertEquals("RegionA_L4_3", mapping.convertYoloNameToZoneId("Map01Lv04Tier03"))
        assertEquals("RegionA_L4_3", mapping.convertYoloNameToZoneId("Map01Lv004Tier003"))
        assertEquals("RegionA_L12_10", mapping.convertYoloNameToZoneId("Map01Lv12Tier10"))
        assertEquals("RegionA_L0_0", mapping.convertYoloNameToZoneId("Map01Lv0Tier0"))
        assertEquals("RegionA_L4_3", mapping.convertYoloNameToZoneId("Map01Lv4Tier3"))
    }

    @Test
    fun `同时含 Base 与 Map 时优先走 Base 分支`() {
        assertEquals("RegionA_Base", mapping.convertYoloNameToZoneId("Map01BaseMap"))
        assertEquals("RegionA_Base", mapping.convertYoloNameToZoneId("Map01MapBaseLv04Tier03"))
    }

    @Test
    fun `含 Base 但不含 Map 时不会被 Base 分支接管`() {
        // 前缀本身不含 "Map"，名字含 Base 不含 Map：既不进 Base 分支，正则也要求字面 "Map" -> 原样返回
        val m2 = YoloMapping(mapOf("Othr1" to "RegionB"))
        assertEquals("Othr1BaseLv04Tier03", m2.convertYoloNameToZoneId("Othr1BaseLv04Tier03"))
    }

    @Test
    fun `前缀命中但无 Base 无正则时原样返回`() {
        assertEquals("Map01Something", mapping.convertYoloNameToZoneId("Map01Something"))
        assertEquals("Map01", mapping.convertYoloNameToZoneId("Map01"))
        assertEquals("Map01Lv04Tier", mapping.convertYoloNameToZoneId("Map01Lv04Tier"))
    }

    @Test
    fun `前缀未命中时原样返回`() {
        assertEquals("Map99Lv01Tier01", mapping.convertYoloNameToZoneId("Map99Lv01Tier01"))
        assertEquals("Map0", mapping.convertYoloNameToZoneId("Map0")) // 不足 5 字符，整串作前缀
        assertEquals("", mapping.convertYoloNameToZoneId(""))
    }

    @Test
    fun `正则从任意位置搜索对齐 boost regex_search`() {
        // 前 5 字符命中，中间夹东西，正则仍能在后半段找到
        assertEquals("RegionA_L4_3", mapping.convertYoloNameToZoneId("Map01FooMap01Lv04Tier03"))
    }

    @Test
    fun `前 5 字符之外的部分不参与前缀匹配`() {
        // 名字前 5 字符是 "Map00" -> BaseZone；正则里的 Map00 也在
        assertEquals("BaseZone_L4_3", mapping.convertYoloNameToZoneId("Map00Lv04Tier03"))
    }

    // ───────────────────── cls.json 解析 ─────────────────────

    @Test
    fun `parseConfig 解析完整 cls json`() {
        val json = """
            {
              "input_name": "images",
              "output_name": "output0",
              "classes": ["Map01Lv01Tier01", "None"],
              "region_mapping": {"Map01": "RegionA"}
            }
        """.trimIndent()
        val c = YoloConfigParser.parseConfig(json)
        assertEquals("images", c.inputName)
        assertEquals("output0", c.outputName)
        assertEquals(listOf("Map01Lv01Tier01", "None"), c.classes)
        assertEquals(mapOf("Map01" to "RegionA"), c.regionMapping)
    }

    @Test
    fun `parseConfig 缺失字段走默认`() {
        assertEquals(YoloConfig(), YoloConfigParser.parseConfig("{}"))
        val onlyClasses = YoloConfigParser.parseConfig("""{"classes":["A"]}""")
        assertEquals(null, onlyClasses.inputName)
        assertEquals(emptyList<String>(), onlyClasses.regionMapping.keys.toList())
        assertEquals(listOf("A"), onlyClasses.classes)
    }

    @Test
    fun `parseConfig 非法 JSON 或非对象返回默认`() {
        assertEquals(YoloConfig(), YoloConfigParser.parseConfig(null))
        assertEquals(YoloConfig(), YoloConfigParser.parseConfig(""))
        assertEquals(YoloConfig(), YoloConfigParser.parseConfig("不是 json"))
        assertEquals(YoloConfig(), YoloConfigParser.parseConfig("[1,2,3]"))
        assertEquals(YoloConfig(), YoloConfigParser.parseConfig("null"))
    }

    @Test
    fun `parseConfig 对类型不符字段按缺失处理`() {
        val c = YoloConfigParser.parseConfig("""{"input_name":123,"classes":"x","region_mapping":[1]}""")
        assertEquals(null, c.inputName)
        assertEquals(emptyList<String>(), c.classes)
        assertTrue(c.regionMapping.isEmpty())
    }

    @Test
    fun `fromConfig 只带 regionMapping`() {
        val m = YoloMapping.fromConfig(YoloConfig(regionMapping = mapOf("Map01" to "RegionA")))
        assertEquals("RegionA_L1_1", m.convertYoloNameToZoneId("Map01Lv01Tier01"))
        assertTrue(m.tileRegions.isEmpty())
    }

    // ───────────────────── tile_mapping.json 解析 ─────────────────────

    @Test
    fun `parseTileMapping 解析完整条目`() {
        val json = """
            {
              "Map01Lv01Tier01": {"base_class":"RegionA_Base","x":10,"y":20,"w":300,"h":200,"infer_margin":64},
              "Map01Lv02Tier02": {"base_class":"RegionA_Base","x":1,"y":2,"w":3,"h":4,"infer_margin":64}
            }
        """.trimIndent()
        val tiles = YoloConfigParser.parseTileMapping(json)
        assertEquals(2, tiles.size)
        val t = tiles["Map01Lv01Tier01"]!!
        assertEquals("RegionA_Base", t.baseClass)
        assertEquals(10, t.x)
        assertEquals(20, t.y)
        assertEquals(300, t.w)
        assertEquals(200, t.h)
        assertEquals(64, t.inferMargin)
    }

    @Test
    fun `parseTileMapping 缺字段走 TileRegion 默认`() {
        val tiles = YoloConfigParser.parseTileMapping("""{"A":{}}""")
        assertEquals(TileRegion(), tiles["A"])
    }

    @Test
    fun `parseTileMapping 非法输入或非对象值被跳过`() {
        assertEquals(emptyMap<String, TileRegion>(), YoloConfigParser.parseTileMapping(null))
        assertEquals(emptyMap<String, TileRegion>(), YoloConfigParser.parseTileMapping("[]"))
        val tiles = YoloConfigParser.parseTileMapping("""{"A": 1, "B": {"x":5}}""")
        assertEquals(1, tiles.size)
        assertEquals(5, tiles["B"]!!.x)
    }

    @Test
    fun `YoloMapping 可携带 tileRegions 供粗搜取 ROI`() {
        val tiles = YoloConfigParser.parseTileMapping("""{"Map01Lv01Tier01":{"x":7,"w":8,"infer_margin":64}}""")
        val m = YoloMapping(mapOf("Map01" to "RegionA"), tiles)
        assertEquals(7, m.tileRegions["Map01Lv01Tier01"]!!.x)
        assertEquals(64, m.tileRegions["Map01Lv01Tier01"]!!.inferMargin)
    }
}
