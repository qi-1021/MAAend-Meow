package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.MapNaviParam.ActionType
import com.aliothmoon.maafw.remote.MapNaviParam.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MapNavigateAction 参数解析测试（对齐上游 navi_param_parser.cpp）。
 *
 * 重点在三类「错了会静默走错」的规则：
 *  1. 数组形态里「看起来像动作」的字符串**不许**被当成 zone_id（宁可整条失败）
 *  2. 动作列表含非 RUN 时要跳过其中的 RUN（作者写 `["RUN","COLLECT"]` 只想采集一次）
 *  3. 路线级默认值只补「没写」的点，且 FIND 的目标来源/完成条件必须齐
 */
class MapNaviParamTest {

    private fun ok(json: String): MapNaviParam.NaviParam {
        val outcome = MapNaviParam.parseText(json)
        assertTrue("期望解析成功，实际 $outcome", outcome is Outcome.Ok)
        return (outcome as Outcome.Ok).param
    }

    private fun invalid(json: String) {
        val outcome = MapNaviParam.parseText(json)
        assertTrue("期望解析失败，实际 $outcome", outcome is Outcome.Invalid)
    }

    // ───────────────────── 入口语义 ─────────────────────

    @Test
    fun `空参数视为空操作成功`() {
        assertEquals(Outcome.NoOp, MapNaviParam.parseText(null))
        assertEquals(Outcome.NoOp, MapNaviParam.parseText(""))
        // 空 path 也是空操作
        assertEquals(Outcome.NoOp, MapNaviParam.parseText("""{"path":[]}"""))
    }

    @Test
    fun `非法 JSON 或非对象判失败`() {
        invalid("不是 json")
        invalid("[1,2,3]")
    }

    @Test
    fun `path 不是数组判失败`() {
        invalid("""{"path":{}}""")
    }

    @Test
    fun `根参数默认值`() {
        val p = ok("""{"path":[[100,200]]}""")
        assertEquals(60_000L, p.arrivalTimeoutMs)
        assertEquals(16.0, p.sprintThreshold, 0.0001)
        assertTrue(p.enableLocalDriver)
        assertTrue(p.enableBootstrapNavmesh)
        assertFalse(p.ziplineEnabled)
        assertEquals(5.0, p.navmeshSnapRadius, 0.0001)
    }

    @Test
    fun `根参数别名优先级 nav_file 高于 navmesh_file snap_radius 高于 navmesh_snap_radius`() {
        val p = ok(
            """{"path":[[1,2]],"navmesh_file":"a.nav","nav_file":"b.nav",
               "navmesh_snap_radius":9.0,"snap_radius":3.0}""",
        )
        assertEquals("b.nav", p.navmeshFile)
        assertEquals(3.0, p.navmeshSnapRadius, 0.0001)
    }

    @Test
    fun `根参数类型写错判失败`() {
        invalid("""{"path":[[1,2]],"zip":"true"}""")
        invalid("""{"path":[[1,2]],"arrival_timeout":{}}""")
        invalid("""{"path":[[1,2]],"map_name":123}""")
    }

    // ───────────────────── 数组形态 ─────────────────────

    @Test
    fun `数组形态基本解析`() {
        val p = ok("""{"path":[[720,350]]}""")
        assertEquals(1, p.path.size)
        val wp = p.path[0]
        assertEquals(720.0, wp.x, 0.0001)
        assertEquals(350.0, wp.y, 0.0001)
        assertEquals(ActionType.RUN, wp.action)
        assertTrue(wp.hasPosition)
    }

    @Test
    fun `数组第三项布尔是 strict`() {
        val wp = ok("""{"path":[[720,350,true]]}""").path[0]
        assertTrue(wp.strictArrival)
        assertEquals(ActionType.RUN, wp.action)
    }

    @Test
    fun `数组第三项动作字符串`() {
        val wp = ok("""{"path":[[720,350,"SPRINT"]]}""").path[0]
        assertEquals(ActionType.SPRINT, wp.action)
    }

    @Test
    fun `数组里的普通字符串是 zone`() {
        val wp = ok("""{"path":[[688,350,"Wuling"]]}""").path[0]
        assertEquals("Wuling", wp.zoneId)
        assertEquals(ActionType.RUN, wp.action)
    }

    @Test
    fun `数组里大小写不符的动作名判失败而不是当成 zone`() {
        // 这条是关键：写错大小写若被当成 zone，会静默走错区域
        invalid("""{"path":[[688,350,"sprint"]]}""")
        invalid("""{"path":[[688,350,"Run"]]}""")
    }

    @Test
    fun `数组里含下划线或全大写的字符串判失败`() {
        invalid("""{"path":[[688,350,"Wuling_Base"]]}""")
        invalid("""{"path":[[688,350,"SPRINT2"]]}""")
    }

    @Test
    fun `数组多项可任意顺序混合 zone strict 动作`() {
        val wp = ok("""{"path":[[720,350,"Wuling",true,"SPRINT"]]}""").path[0]
        assertEquals("Wuling", wp.zoneId)
        assertTrue(wp.strictArrival)
        assertEquals(ActionType.SPRINT, wp.action)
    }

    @Test
    fun `数组前两项必须是数字`() {
        invalid("""{"path":[["a",350]]}""")
        invalid("""{"path":[[720]]}""")
    }

    @Test
    fun `数组元素类型不支持时判失败`() {
        invalid("""{"path":[[720,350,{"a":1}]]}""")
        invalid("""{"path":[[720,350,42]]}""")
    }

    // ───────────────────── 动作展开 ─────────────────────

    @Test
    fun `无动作时默认 RUN`() {
        assertEquals(ActionType.RUN, ok("""{"path":[{"target":[1,2]}]}""").path[0].action)
    }

    @Test
    fun `含非 RUN 时跳过其中的 RUN`() {
        val p = ok("""{"path":[{"action":["RUN","COLLECT"],"target":[1,2]}]}""")
        assertEquals(1, p.path.size)
        assertEquals(ActionType.COLLECT, p.path[0].action)
    }

    @Test
    fun `全是 RUN 时每个都保留`() {
        val p = ok("""{"path":[{"action":["RUN","RUN"],"target":[1,2]}]}""")
        assertEquals(2, p.path.size)
        assertTrue(p.path.all { it.action == ActionType.RUN })
    }

    @Test
    fun `非法动作名判失败`() {
        invalid("""{"path":[{"action":"FLY","target":[1,2]}]}""")
        invalid("""{"path":[{"action":["RUN","FLY"],"target":[1,2]}]}""")
        invalid("""{"path":[{"actions":"run","target":[1,2]}]}""")
    }

    // ───────────────────── 各动作的必填校验 ─────────────────────

    @Test
    fun `ZONE 必须有 zone_id 或继承上下文`() {
        val wp = ok("""{"path":[{"action":"ZONE","zone_id":"L1"}]}""").path[0]
        assertEquals(ActionType.ZONE, wp.action)
        assertFalse(wp.hasPosition)

        // 没有 zone 也没有 map_name -> 失败
        invalid("""{"path":[{"action":"ZONE"}]}""")
        // 可以从 map_name 继承
        assertEquals("L1", ok("""{"map_name":"L1","path":[{"action":"ZONE"}]}""").path[0].zoneId)
    }

    @Test
    fun `HEADING 接受角度或 target`() {
        val byAngle = ok("""{"path":[{"action":"HEADING","angle":90}]}""").path[0]
        assertEquals(ActionType.HEADING, byAngle.action)
        assertFalse(byAngle.hasPosition)
        assertEquals(90.0, byAngle.headingAngle, 0.0001)
        assertFalse(byAngle.headingUsesTarget)

        val byTarget = ok("""{"path":[{"action":"HEADING","target":[1,2]}]}""").path[0]
        assertTrue(byTarget.headingUsesTarget)

        invalid("""{"path":[{"action":"HEADING"}]}""")
    }

    @Test
    fun `HEADING 的角度别名优先级 angle 高于 heading 高于 yaw`() {
        // 优先级 angle > heading > yaw（上游同）
        assertEquals(3.0, ok("""{"path":[{"action":"HEADING","yaw":1,"heading":2,"angle":3}]}""").path[0].headingAngle, 0.0001)
        assertEquals(2.0, ok("""{"path":[{"action":"HEADING","yaw":1,"heading":2}]}""").path[0].headingAngle, 0.0001)
        assertEquals(1.0, ok("""{"path":[{"action":"HEADING","yaw":1}]}""").path[0].headingAngle, 0.0001)
    }

    @Test
    fun `NAVMESH 必须用 target 且强制严格到达`() {
        val wp = ok("""{"path":[{"action":"NAVMESH","target":[1,2]}]}""").path[0]
        assertTrue(wp.strictArrival)
        // x/y 不算 target
        invalid("""{"path":[{"action":"NAVMESH","x":1,"y":2}]}""")
        invalid("""{"path":[{"action":"NAVMESH"}]}""")
    }

    @Test
    fun `普通动作必须有坐标`() {
        invalid("""{"path":[{"action":"RUN"}]}""")
        invalid("""{"path":[{"action":"COLLECT"}]}""")
    }

    @Test
    fun `x 与 y 必须成对`() {
        invalid("""{"path":[{"action":"RUN","x":1}]}""")
        assertTrue(ok("""{"path":[{"action":"RUN","x":1,"y":2}]}""").path[0].hasPosition)
    }

    @Test
    fun `target 优先于 x y`() {
        val wp = ok("""{"path":[{"action":"RUN","target":[9,8],"x":1,"y":2}]}""").path[0]
        assertEquals(9.0, wp.x, 0.0001)
        assertEquals(8.0, wp.y, 0.0001)
    }

    @Test
    fun `可选的 tier deck strict required 都保留`() {
        val wp = ok(
            """{"path":[{"action":"NAVMESH","target":[1,2],"target_tier":"t1",
               "target_deck_y":80,"strict_arrival":true,"required":true}]}""",
        ).path[0]
        assertEquals("t1", wp.targetTier)
        assertEquals(80.0, wp.targetDeckY!!, 0.0001)
        assertTrue(wp.strictArrival)
        assertTrue(wp.authoredStrictArrival)
        assertTrue(wp.routeRequired)
    }

    // ───────────────────── 文本三形态 ─────────────────────

    @Test
    fun `interact_text 三种形态`() {
        assertEquals(
            listOf("确认"),
            ok("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":"确认"}]}""").path[0].interactText,
        )
        assertEquals(
            listOf("A", "B"),
            ok("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":["A","B"]}]}""").path[0].interactText,
        )
        assertEquals(
            "OcrNode",
            ok("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":{"node":"OcrNode"}}]}""").path[0].interactTextNode,
        )
    }

    @Test
    fun `interact_text 空值或不合法形状判失败`() {
        invalid("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":""}]}""")
        invalid("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":[]}]}""")
        invalid("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":["A",""]}]}""")
        invalid("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":{"node":""}}]}""")
        invalid("""{"path":[{"action":"INTERACT","target":[1,2],"interact_text":{"other":"x"}}]}""")
    }

    // ───────────────────── 路线级默认值 ─────────────────────

    @Test
    fun `路线级 interact 默认值只补没写的点`() {
        val p = ok(
            """{"interact_text":"路线级","path":[
               {"action":"INTERACT","target":[1,2]},
               {"action":"INTERACT","target":[3,4],"interact_text":"点上写的"}]}""",
        )
        assertEquals(listOf("路线级"), p.path[0].interactText)
        assertEquals(listOf("点上写的"), p.path[1].interactText)
    }

    @Test
    fun `interact_rec 只开不能关`() {
        val p = ok("""{"interact_rec":true,"path":[{"action":"INTERACT","target":[1,2]}]}""")
        assertTrue(p.path[0].interactRec)
    }

    @Test
    fun `interact_scan 也会补齐`() {
        val p = ok("""{"interact_scan":"ScanNode","path":[{"action":"INTERACT","target":[1,2]}]}""")
        assertEquals("ScanNode", p.path[0].interactScan)
    }

    // ───────────────────── FIND 校验 ─────────────────────

    @Test
    fun `FIND 的目标来源与完成条件必须齐`() {
        // FIND 的目标来源必须是 find_target / find_text；只有 find_stop 不够
        val wp = ok(
            """{"path":[{"action":"FIND","target":[1,2],"find_target":"TargetNode","find_stop":"StopNode"}]}""",
        ).path[0]
        assertEquals("StopNode", wp.findStop)
        assertEquals("TargetNode", wp.findTarget)

        // 缺完成条件
        invalid("""{"path":[{"action":"FIND","target":[1,2],"find_target":"T"}]}""")
    }

    @Test
    fun `FIND 同时给 find_target 与 find_text 判失败`() {
        invalid(
            """{"path":[{"action":"FIND","target":[1,2],"find_target":"T",
               "find_text":"文字","find_stop":"S"}]}""",
        )
    }

    @Test
    fun `FIND 缺目标来源判失败`() {
        invalid("""{"path":[{"action":"FIND","target":[1,2],"find_stop":"S"}]}""")
    }

    @Test
    fun `FIND 缺完成条件判失败`() {
        invalid("""{"path":[{"action":"FIND","target":[1,2],"find_text":"文字"}]}""")
    }

    @Test
    fun `find_arrive 必须恰好两个数字`() {
        val wp = ok(
            """{"path":[{"action":"FIND","target":[1,2],"find_target":"T","find_arrive":[10,20]}]}""",
        ).path[0]
        assertEquals(10.0 to 20.0, wp.findArrive)

        invalid("""{"path":[{"action":"FIND","target":[1,2],"find_target":"T","find_arrive":[10]}]}""")
        invalid("""{"path":[{"action":"FIND","target":[1,2],"find_target":"T","find_arrive":[10,20,30]}]}""")
        invalid("""{"path":[{"action":"FIND","target":[1,2],"find_target":"T","find_arrive":["a","b"]}]}""")
    }

    @Test
    fun `路线级 FIND 默认值可补进点里`() {
        val wp = ok(
            """{"find_text":"路线文字","find_stop":"StopNode",
               "path":[{"action":"FIND","target":[1,2]}]}""",
        ).path[0]
        assertEquals(listOf("路线文字"), wp.findText)
        assertEquals("StopNode", wp.findStop)
    }

    @Test
    fun `路线级同时给 find_target 与 find_text 且真有 FIND 点才失败`() {
        invalid(
            """{"find_target":"T","find_text":"文字","find_stop":"S",
               "path":[{"action":"FIND","target":[1,2]}]}""",
        )
        // 没有 FIND 点时只告警不失败
        val p = ok("""{"find_target":"T","find_text":"文字","path":[[1,2]]}""")
        assertEquals(1, p.path.size)
    }

    // ───────────────────── 根级单路点 ─────────────────────

    @Test
    fun `根级单路点形态`() {
        val p = ok("""{"action":"SPRINT","x":5,"y":6}""")
        assertEquals(1, p.path.size)
        assertEquals(ActionType.SPRINT, p.path[0].action)
        assertEquals(5.0, p.path[0].x, 0.0001)
    }

    @Test
    fun `path 存在时根级的 action 被忽略`() {
        val p = ok("""{"action":"SPRINT","path":[[1,2]]}""")
        assertEquals(1, p.path.size)
        assertEquals(ActionType.RUN, p.path[0].action)
    }

    // ───────────────────── 跨点 zone 上下文 ─────────────────────

    @Test
    fun `带 zone 的点会更新后续点的上下文`() {
        val p = ok(
            """{"map_name":"Base","path":[
               [1,2,"Wuling"],
               {"action":"ZONE"},
               {"action":"RUN","target":[3,4]}]}""",
        )
        assertEquals("Wuling", p.path[0].zoneId)
        assertEquals("Wuling", p.path[1].zoneId)
        assertEquals("Wuling", p.path[2].zoneId)
    }

    @Test
    fun `任一路点失败则整条失败并带上下标`() {
        val outcome = MapNaviParam.parseText("""{"path":[[1,2],["bad",2]]}""")
        assertTrue(outcome is Outcome.Invalid)
        assertTrue((outcome as Outcome.Invalid).reason.contains("path[1]"))
    }

    @Test
    fun `路线边界动作可识别`() {
        assertTrue(ok("""{"path":[{"action":"COLLECT","target":[1,2]}]}""").path[0].isRouteBoundary())
        assertFalse(ok("""{"path":[[1,2]]}""").path[0].isRouteBoundary())
    }
}
