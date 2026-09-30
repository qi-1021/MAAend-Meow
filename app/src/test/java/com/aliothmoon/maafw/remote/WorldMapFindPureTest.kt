package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldMapFindPure] 的纯逻辑测试：`ParseParam` 的全部校验分支、`BuildTargets`、
 * `MapIcons.json` 图标表解析与缺省覆盖、detail 载荷形状。
 */
class WorldMapFindPureTest {

    // ───────────────────── ParseParam：合法 ─────────────────────

    @Test
    fun `单点 at 解析成功`() {
        val r = WorldMapFindPure.parseFindParam("""{"zone":"ValleyIV","icon":"TeleportAnchor","at":[100.5,200.25]}""")
        assertNull(r.error)
        val p = r.param!!
        assertEquals("ValleyIV", p.zone)
        assertEquals("TeleportAnchor", p.icon)
        assertEquals(listOf(100.5, 200.25), p.at)
        assertEquals(4, p.maxAttempts)
        assertEquals(3, p.voteGrid)
    }

    @Test
    fun `candidates 解析成功`() {
        val json = """
            {"zone":"Wuling","icon":"DeliveryPoint","candidates":[
              {"at":[1,2],"next":"N1"},{"at":[3,4],"next":"N2"}]}
        """.trimIndent()
        val r = WorldMapFindPure.parseFindParam(json)
        assertNull(r.error)
        assertEquals(2, r.param!!.candidates.size)
        assertEquals("N1", r.param!!.candidates[0].next)
    }

    @Test
    fun `max_attempts 与 vote_grid 覆盖`() {
        val r = WorldMapFindPure.parseFindParam(
            """{"zone":"Z","at":[1,2],"max_attempts":2,"vote_grid":1}""",
        )
        assertEquals(2, r.param!!.maxAttempts)
        assertEquals(1, r.param!!.voteGrid)
    }

    @Test
    fun `max_attempts 小于 1 归一到 1`() {
        val r = WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1,2],"max_attempts":0}""")
        assertEquals(1, r.param!!.maxAttempts)
    }

    // ───────────────────── ParseParam：非法 ─────────────────────

    @Test
    fun `空串与非法 JSON 拒绝`() {
        assertNull(WorldMapFindPure.parseFindParam(null).param)
        assertNull(WorldMapFindPure.parseFindParam("").param)
        assertNull(WorldMapFindPure.parseFindParam("{").param)
        assertNull(WorldMapFindPure.parseFindParam("[1,2]").param)
    }

    @Test
    fun `zone 必须非空字符串`() {
        assertNull(WorldMapFindPure.parseFindParam("""{"at":[1,2]}""").param)
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"","at":[1,2]}""").param)
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":3,"at":[1,2]}""").param)
    }

    @Test
    fun `at 与 candidates 恰给一个`() {
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"Z"}""").param)
        val both = """
            {"zone":"Z","at":[1,2],"icon":"I","candidates":[{"at":[3,4],"next":"N"}]}
        """.trimIndent()
        assertNull(WorldMapFindPure.parseFindParam(both).param)
    }

    @Test
    fun `at 必须两个数`() {
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1,2,3]}""").param)
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1]}""").param)
    }

    @Test
    fun `candidate 需两个数与非空 next`() {
        assertNull(
            WorldMapFindPure.parseFindParam(
                """{"zone":"Z","icon":"I","candidates":[{"at":[1],"next":"N"}]}""",
            ).param,
        )
        assertNull(
            WorldMapFindPure.parseFindParam(
                """{"zone":"Z","icon":"I","candidates":[{"at":[1,2],"next":""}]}""",
            ).param,
        )
    }

    @Test
    fun `candidates 需要 icon`() {
        assertNull(
            WorldMapFindPure.parseFindParam(
                """{"zone":"Z","candidates":[{"at":[1,2],"next":"N"}]}""",
            ).param,
        )
    }

    @Test
    fun `state 取值与 icon 依赖`() {
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1,2],"state":"bad"}""").param)
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1,2],"state":"locked"}""").param)
        val ok = WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1,2],"icon":"I","state":"locked"}""")
        assertEquals("locked", ok.param!!.state)
    }

    @Test
    fun `max_attempts 类型错整段拒绝`() {
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1,2],"max_attempts":"x"}""").param)
        assertNull(WorldMapFindPure.parseFindParam("""{"zone":"Z","at":[1,2],"max_attempts":1.5}""").param)
    }

    // ───────────────────── BuildTargets ─────────────────────

    @Test
    fun `buildTargets 单点与候选`() {
        val single = WorldMapFindPure.buildTargets(
            WorldMapFindPure.FindParam(zone = "Z", at = listOf(1.0, 2.0)),
        )
        assertEquals(1, single.size)
        assertEquals("", single[0].next)

        val multi = WorldMapFindPure.buildTargets(
            WorldMapFindPure.FindParam(
                zone = "Z", icon = "I",
                candidates = listOf(
                    WorldMapFindPure.Candidate(listOf(1.0, 2.0), "A"),
                    WorldMapFindPure.Candidate(listOf(3.0, 4.0), "B"),
                ),
            ),
        )
        assertEquals(2, multi.size)
        assertEquals("B", multi[1].next)
    }

    // ───────────────────── 图标表 ─────────────────────

    @Test
    fun `图标表解析与实际 MapIcons 形状一致`() {
        val json = """
            {
              "TeleportAnchor": {"templates":["MapTeleportAnchor.png"],"scale":[0.9,1.35],
                "scale_step":0.025,"threshold":0.55,"gate":10.0,"occluded_by_player":true},
              "Core": {"templates":["MapTeleportCoreHub.png","MapTeleportCoreSettlement.png"],
                "scale":[0.9,1.15],"scale_step":0.025,"threshold":0.82,"radius":60.0,
                "gold_ratio":0.5,"occluded_by_player":true},
              "DeliveryPoint": {"templates":["../SeizeDeliveryJobs/DeliveryPoint.png"],"threshold":0.8}
            }
        """.trimIndent()
        val table = WorldMapFindPure.parseIconTable(json)
        assertEquals(3, table.size)

        val anchor = table.getValue("TeleportAnchor")
        assertEquals(listOf("MapTeleportAnchor.png"), anchor.spot.templates)
        assertEquals(0.9, anchor.spot.scaleMin, 1e-12)
        assertEquals(1.35, anchor.spot.scaleMax, 1e-12)
        assertEquals(0.025, anchor.spot.scaleStep, 1e-12)
        assertEquals(0.55, anchor.spot.minScore, 1e-12)
        assertEquals(10.0, anchor.spot.gateBase, 1e-12)
        assertEquals(0.0, anchor.spot.minGoldRatio, 1e-12)
        assertTrue(anchor.occludedByPlayer)

        val core = table.getValue("Core")
        assertEquals(60.0, core.spot.radiusBase, 1e-12)
        assertEquals(0.82, core.spot.minScore, 1e-12)
        assertEquals(0.5, core.spot.minGoldRatio, 1e-12)

        val delivery = table.getValue("DeliveryPoint")
        // threshold=0.8 覆盖缺省 0.55；未写的 radiusScreen 走缺省
        assertEquals(0.8, delivery.spot.minScore, 1e-12)
        assertEquals(40, delivery.spot.radiusScreen)
    }

    @Test
    fun `图标表非法条目跳过`() {
        val json = """
            {
              "Bad": {"no_templates":1},
              "Empty": {"templates":[]},
              "BadType": {"templates":"x"},
              "Good": {"templates":["a.png"]}
            }
        """.trimIndent()
        val table = WorldMapFindPure.parseIconTable(json)
        assertEquals(1, table.size)
        assertTrue(table.containsKey("Good"))
    }

    @Test
    fun `图标表非对象返回空`() {
        assertTrue(WorldMapFindPure.parseIconTable("[]").isEmpty())
        assertTrue(WorldMapFindPure.parseIconTable("nonsense").isEmpty())
    }

    @Test
    fun `scale 非法时不覆盖缺省`() {
        val spec = WorldMapFindPure.iconEntryToSpec(
            WorldMapFindPure.IconEntry(
                templates = listOf("x"),
                scale = listOf(0.0, 1.0),
            ),
        )
        assertEquals(0.9, spec.spot.scaleMin, 1e-12)
        assertEquals(1.35, spec.spot.scaleMax, 1e-12)
    }

    // ───────────────────── 底图选取 ─────────────────────

    @Test
    fun `findZoneBaseFile 取含 base 不含 tier 的 png`() {
        assertEquals("Base.png", WorldMapFindPure.findZoneBaseFile(listOf("Base.png", "Lv001Tier114.png")))
        assertEquals("Dung01Base.png", WorldMapFindPure.findZoneBaseFile(listOf("Dung01Tier1.png", "Dung01Base.png")))
        assertNull(WorldMapFindPure.findZoneBaseFile(listOf("Lv001Tier114.png")))
        // 后缀非 png / 含 tier 都不算
        assertNull(WorldMapFindPure.findZoneBaseFile(listOf("Base.jpeg")))
        assertNull(WorldMapFindPure.findZoneBaseFile(listOf("BaseTier2.png")))
    }

    // ───────────────────── detail ─────────────────────

    @Test
    fun `findDetailJson 关键字段`() {
        val text = WorldMapFindPure.findDetailJson(
            zone = "ValleyIV", index = 0, atX = 100.0, atY = 200.0,
            screenX = 300.0, screenY = 400.0, viewportScale = 0.4, viewportVote = 1,
            icon = "TeleportAnchor", templateName = "t.png", score = 0.88,
            unlocked = true, clickX = 305.0, clickY = 405.0,
        )
        assertTrue(text.contains("\"zone\":\"ValleyIV\""))
        assertTrue(text.contains("\"viewport_scale\":0.4"))
        assertTrue(text.contains("\"icon\":\"TeleportAnchor\""))
        assertTrue(text.contains("\"unlocked\":true"))
        assertTrue(text.contains("\"click\":[305.0,405.0]"))
    }

    @Test
    fun `findDetailJson 无可选项时不含 icon`() {
        val text = WorldMapFindPure.findDetailJson(
            zone = "Z", index = 3, atX = 1.0, atY = 2.0,
            screenX = 3.0, screenY = 4.0, viewportScale = 1.0, viewportVote = 2,
        )
        assertFalse(text.contains("\"icon\""))
        assertFalse(text.contains("\"next\""))
    }

    // ───────────────────── mapfind 探针渲染 ─────────────────────

    private val sampleViewport = WorldMapViewport(
        scale = 0.5,
        roiOriginX = 100.0,
        roiOriginY = 200.0,
        baseOriginX = 1000.0,
        baseOriginY = 2000.0,
        roiWidth = 800,
        roiHeight = 600,
        score = 0.9,
        delta = 0.1,
        psr = 1.5,
        voteGrid = 3,
    )

    @Test
    fun `describeProbe viewport 解不出时给 FAILED`() {
        val lines = WorldMapFindPure.describeProbe(
            WorldMapFindPure.ProbeOutcome(
                zone = "Wuling", baseWidth = 4096, baseHeight = 4096,
                atX = 1.0, atY = 2.0, viewport = null,
                icon = "TeleportAnchor", hit = null, wantUnlocked = true,
            ),
        )
        val text = lines.joinToString("\n")
        assertTrue(text.contains("zone: Wuling"))
        assertTrue(text.contains("base: 4096x4096"))
        assertTrue(text.contains("viewport: FAILED"))
        assertTrue(text.contains("hit: false"))
    }

    @Test
    fun `describeProbe 无 icon 时只解坐标`() {
        val lines = WorldMapFindPure.describeProbe(
            WorldMapFindPure.ProbeOutcome(
                zone = "Wuling", baseWidth = 4096, baseHeight = 4096,
                atX = 1200.0, atY = 2400.0, viewport = sampleViewport,
                icon = null, hit = null, wantUnlocked = true,
            ),
        )
        val text = lines.joinToString("\n")
        // base=(1200,2400) → screen=((1200-1000)/0.5+100,(2400-2000)/0.5+200)=(500,1000)
        assertTrue(text.contains("viewport: scale=0.500 score=0.900 delta=0.100"))
        assertTrue(text.contains("base=(1000.000,2000.000)"))
        assertTrue(text.contains("target screen: (500.000,1000.000) point_box=[500,1000,1,1]"))
        assertTrue(text.contains("仅解坐标"))
    }

    @Test
    fun `describeProbe 命中给模板与点框`() {
        val hit = WorldMapSpotHit(
            templateName = "TeleportAnchor.png",
            centerX = 505.0, centerY = 1005.0,
            hotspotX = 506.0, hotspotY = 1007.0,
            sizeWidth = 24, sizeHeight = 24,
            score = 0.88, matchScale = 1.1,
            offsetBase = 3.5, goldRatio = 0.0, unlocked = true,
        )
        val lines = WorldMapFindPure.describeProbe(
            WorldMapFindPure.ProbeOutcome(
                zone = "Wuling", baseWidth = 4096, baseHeight = 4096,
                atX = 1200.0, atY = 2400.0, viewport = sampleViewport,
                icon = "TeleportAnchor", hit = hit, wantUnlocked = true,
            ),
        )
        val text = lines.joinToString("\n")
        assertTrue(text.contains("hit: true template=TeleportAnchor.png score=0.880"))
        assertTrue(text.contains("offset_base=3.500"))
        assertTrue(text.contains("hotspot=(506.000,1007.000)"))
        assertTrue(text.contains("box=[506,1007,1,1]"))
    }

    @Test
    fun `describeProbe 给了 icon 但认不出`() {
        val lines = WorldMapFindPure.describeProbe(
            WorldMapFindPure.ProbeOutcome(
                zone = "Wuling", baseWidth = 4096, baseHeight = 4096,
                atX = 1200.0, atY = 2400.0, viewport = sampleViewport,
                icon = "TeleportAnchor", hit = null, wantUnlocked = true,
            ),
        )
        val text = lines.joinToString("\n")
        assertTrue(text.contains("hit: false（icon=TeleportAnchor"))
    }
}
