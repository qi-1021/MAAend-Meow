package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MapLocateAction 参数解析与输出构造测试（对齐 `MapLocateAction.cpp:113-226`）。
 *
 * 重点：解析失败整体退回默认、`target` 的四元校验、`std::lround` 的远离零取整、
 * 以及左闭右开的命中判定。
 */
class MapLocateActionPureTest {

    // ───────────────────── parseLocateOptions ─────────────────────

    @Test
    fun `LocateOptions 非法输入回退默认`() {
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions(null))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions(""))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("不是 json"))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("[1,2]"))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("null"))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("{}"))
    }

    @Test
    fun `LocateOptions 完整解析各字段`() {
        val json = """
            {"loc_threshold":0.9,"yolo_threshold":0.8,"force_global_search":true,
             "max_lost_frames":5,"expected_zone_id":"ZoneA",
             "search_hints":[{"zone_id":"H","x":1,"y":2,"radius":3}]}
        """.trimIndent()
        val o = MapLocateActionPure.parseLocateOptions(json)
        assertEquals(0.9, o.locThreshold, 0.0)
        assertEquals(0.8, o.yoloThreshold, 0.0)
        assertTrue(o.forceGlobalSearch)
        assertEquals(5, o.maxLostFrames)
        assertEquals("ZoneA", o.expectedZoneId)
        assertEquals(listOf(SearchHint(zoneId = "H", x = 1.0, y = 2.0, radius = 3.0)), o.searchHints)
    }

    @Test
    fun `LocateOptions 字段类型不符整体回退默认`() {
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("""{"loc_threshold":"0.9"}"""))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("""{"yolo_threshold":true}"""))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("""{"force_global_search":1}"""))
        // 小数不满足 is<int>
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("""{"max_lost_frames":3.5}"""))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("""{"expected_zone_id":5}"""))
    }

    @Test
    fun `LocateOptions search_hints 任一条目非法整体回退默认`() {
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("""{"search_hints":{}}"""))
        assertEquals(LocateOptions(), MapLocateActionPure.parseLocateOptions("""{"search_hints":[{}]}"""))
        assertEquals(
            LocateOptions(),
            MapLocateActionPure.parseLocateOptions("""{"search_hints":[{"zone_id":"H","x":1,"y":2}]}"""),
        )
        assertEquals(
            LocateOptions(),
            MapLocateActionPure.parseLocateOptions("""{"search_hints":[{"zone_id":"H","x":1,"y":2,"radius":3},null]}"""),
        )
    }

    @Test
    fun `LocateOptions search_hints 整数坐标解析为小数`() {
        val o = MapLocateActionPure.parseLocateOptions("""{"search_hints":[{"zone_id":"H","x":1,"y":2,"radius":3}]}""")
        assertEquals(1, o.searchHints.size)
        assertEquals(1.0, o.searchHints[0].x, 0.0)
    }

    // ───────────────────── parseAssertLocationParam ─────────────────────

    @Test
    fun `AssertLocationParam 非法输入回退默认`() {
        assertEquals(AssertLocationParam(), MapLocateActionPure.parseAssertLocationParam(null))
        assertEquals(AssertLocationParam(), MapLocateActionPure.parseAssertLocationParam(""))
        assertEquals(AssertLocationParam(), MapLocateActionPure.parseAssertLocationParam("{}"))
        assertEquals(AssertLocationParam(), MapLocateActionPure.parseAssertLocationParam("[]"))
        assertEquals(AssertLocationParam(), MapLocateActionPure.parseAssertLocationParam("""{"zone_id":1}"""))
        assertEquals(AssertLocationParam(), MapLocateActionPure.parseAssertLocationParam("""{"target":"x"}"""))
        assertEquals(AssertLocationParam(), MapLocateActionPure.parseAssertLocationParam("""{"target":[1,"a"]}"""))
    }

    @Test
    fun `AssertLocationParam 解析 zone 与 target`() {
        val p = MapLocateActionPure.parseAssertLocationParam("""{"zone_id":"ZoneA","target":[1,2,3,4]}""")
        assertEquals("ZoneA", p.zoneId)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), p.target)
    }

    // ───────────────────── buildLocateOutput ─────────────────────

    @Test
    fun `BuildLocateOutput 无位置只带状态与消息`() {
        val out = MapLocateActionPure.buildLocateOutput(
            LocateResult(status = LocateStatus.TRACKING_LOST, debugMessage = "lost"),
        )
        assertEquals(LocateStatus.TRACKING_LOST.ordinal, out.status)
        assertEquals("lost", out.message)
        assertEquals("", out.mapName)
        assertEquals(0.0, out.x, 0.0)
        assertEquals(0, out.latencyMs)
    }

    @Test
    fun `BuildLocateOutput 有位置与朝向时带全字段`() {
        val result = LocateResult(
            status = LocateStatus.SUCCESS,
            position = MapPosition(zoneId = "Z", x = 1.5, y = 2.5, score = 0.9, angle = 45.0, latencyMs = 123),
            debugMessage = "ok",
            camRot = CameraOrientation(rot = 90.0, confidence = 0.8),
        )
        val out = MapLocateActionPure.buildLocateOutput(result)
        assertEquals(0, out.status)
        assertEquals("ok", out.message)
        assertEquals("Z", out.mapName)
        assertEquals(1.5, out.x, 0.0)
        assertEquals(2.5, out.y, 0.0)
        assertEquals(45.0, out.rot, 0.0)
        assertEquals(0.9, out.locConf, 0.0)
        assertEquals(90.0, out.camRot, 0.0)
        assertEquals(0.8, out.camRotConf, 0.0)
        assertEquals(123, out.latencyMs)
    }

    @Test
    fun `BuildLocateOutput 无朝向时 camRot 保持零`() {
        val result = LocateResult(
            status = LocateStatus.SUCCESS,
            position = MapPosition(zoneId = "Z", x = 1.0, y = 2.0),
        )
        val out = MapLocateActionPure.buildLocateOutput(result)
        assertEquals(0.0, out.camRot, 0.0)
        assertEquals(0.0, out.camRotConf, 0.0)
    }

    @Test
    fun `LocateOutput toJsonObject 字段顺序与上游一致`() {
        val out = MapLocateActionPure.buildLocateOutput(LocateResult())
        assertEquals(
            listOf("status", "message", "mapName", "x", "y", "rot", "locConf", "camRot", "camRotConf", "latencyMs"),
            out.toJsonObject().keys.toList(),
        )
    }

    // ───────────────────── buildAssertLocationOutput ─────────────────────

    @Test
    fun `BuildAssertLocationOutput inTarget 跟随 matched`() {
        val param = AssertLocationParam(zoneId = "Z", target = listOf(1.0, 2.0, 3.0, 4.0))
        val miss = MapLocateActionPure.buildAssertLocationOutput(LocateResult(), param, matched = false)
        assertEquals("Z", miss.zoneId)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), miss.target)
        assertFalse(miss.matched)
        assertFalse(miss.inTarget)

        val hit = MapLocateActionPure.buildAssertLocationOutput(
            LocateResult(
                status = LocateStatus.SUCCESS,
                position = MapPosition(zoneId = "Z", x = 9.0, y = 8.0, score = 0.7, angle = 30.0, latencyMs = 50),
                camRot = CameraOrientation(10.0, 0.5),
            ),
            param,
            matched = true,
        )
        assertTrue(hit.matched)
        assertTrue(hit.inTarget)
        assertEquals(9.0, hit.x, 0.0)
        assertEquals(8.0, hit.y, 0.0)
        assertEquals(30.0, hit.rot, 0.0)
        assertEquals(0.7, hit.locConf, 0.0)
        assertEquals(10.0, hit.camRot, 0.0)
        assertEquals(0.5, hit.camRotConf, 0.0)
        assertEquals(50, hit.latencyMs)
    }

    @Test
    fun `AssertLocationOutput toJsonObject 字段顺序与上游一致`() {
        val out = MapLocateActionPure.buildAssertLocationOutput(
            LocateResult(),
            AssertLocationParam(),
            matched = false,
        )
        assertEquals(
            listOf(
                "status", "matched", "inTarget", "message", "zoneId", "x", "y",
                "rot", "locConf", "camRot", "camRotConf", "latencyMs", "target",
            ),
            out.toJsonObject().keys.toList(),
        )
    }

    // ───────────────────── MakePointBox ─────────────────────

    @Test
    fun `MakePointBox 用远离零的四舍五入且为 1x1`() {
        assertEquals(MapRect(2, -2, 1, 1), MapLocateActionPure.makePointBox(MapPosition(x = 1.5, y = -1.5)))
        assertEquals(MapRect(2, 3, 1, 1), MapLocateActionPure.makePointBox(MapPosition(x = 2.4, y = 2.6)))
    }

    // ───────────────────── TryBuildAssertRect ─────────────────────

    @Test
    fun `TryBuildAssertRect target 必须恰为四元且宽高为正`() {
        assertNull(MapLocateActionPure.tryBuildAssertRect(AssertLocationParam(target = listOf(1.0, 2.0, 3.0))))
        assertNull(MapLocateActionPure.tryBuildAssertRect(AssertLocationParam(target = listOf(1.0, 2.0, 3.0, 4.0, 5.0))))
        assertNull(MapLocateActionPure.tryBuildAssertRect(AssertLocationParam(target = emptyList())))
        assertNull(MapLocateActionPure.tryBuildAssertRect(AssertLocationParam(target = listOf(0.0, 0.0, 0.0, 5.0))))
        assertNull(MapLocateActionPure.tryBuildAssertRect(AssertLocationParam(target = listOf(0.0, 0.0, 5.0, -1.0))))
    }

    @Test
    fun `TryBuildAssertRect 宽高取 max 1 且坐标远离零取整`() {
        val rect = MapLocateActionPure.tryBuildAssertRect(AssertLocationParam(target = listOf(10.5, 20.4, 0.4, 3.6)))
        assertEquals(MapRect(11, 20, 1, 4), rect)
        assertEquals(
            MapRect(1, 2, 5, 6),
            MapLocateActionPure.tryBuildAssertRect(AssertLocationParam(target = listOf(1.0, 2.0, 5.0, 6.0))),
        )
    }

    // ───────────────────── IsPositionInsideRect ─────────────────────

    @Test
    fun `IsPositionInsideRect 左闭右开上闭下开`() {
        val rect = MapRect(10, 20, 5, 5)
        assertTrue(MapLocateActionPure.isPositionInsideRect(MapPosition(x = 10.0, y = 20.0), rect))
        assertTrue(MapLocateActionPure.isPositionInsideRect(MapPosition(x = 14.999, y = 24.999), rect))
        assertFalse(MapLocateActionPure.isPositionInsideRect(MapPosition(x = 15.0, y = 22.0), rect))
        assertFalse(MapLocateActionPure.isPositionInsideRect(MapPosition(x = 9.999, y = 22.0), rect))
        assertFalse(MapLocateActionPure.isPositionInsideRect(MapPosition(x = 12.0, y = 25.0), rect))
        assertFalse(MapLocateActionPure.isPositionInsideRect(MapPosition(x = 12.0, y = 19.999), rect))
    }
}
