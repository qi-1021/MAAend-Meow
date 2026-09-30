package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MapLocateAssertLocation` 裁决纯逻辑测试（对齐 `MapLocateAction.cpp:404-471`）。
 *
 * 重点：首帧命中即停、未定位帧跳过、左闭右开矩形判定、最后一帧回填、空序列兜底、
 * 以及 `buildAssertOptions` 的两个上游固定值。
 */
class MapLocateAssertPureTest {

    private fun located(x: Double, y: Double, score: Double = 0.9) =
        MapLocateAssertPure.AssertFrame(
            status = LocateStatus.SUCCESS,
            position = MapPosition(zoneId = "Z", x = x, y = y, score = score),
        )

    private fun lost(message: String = "Global search failed.") =
        MapLocateAssertPure.AssertFrame(status = LocateStatus.TRACKING_LOST, position = null, debugMessage = message)

    private val target = MapRect(x = 100, y = 100, width = 20, height = 20)

    @Test
    fun `常量对齐上游`() {
        assertEquals(60, MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES)
        assertEquals(250L, MapLocateAssertPure.ASSERT_LOCATE_POLL_DELAY_MS)
    }

    @Test
    fun `定位帧要求 Success 且有位置`() {
        assertTrue(located(1.0, 2.0).located)
        assertFalse(lost().located)
        assertFalse(MapLocateAssertPure.AssertFrame(LocateStatus.SUCCESS, null).located)
        assertFalse(MapLocateAssertPure.AssertFrame(LocateStatus.TRACKING_LOST, MapPosition(x = 1.0)).located)
    }

    @Test
    fun `首帧命中即停`() {
        val frames = listOf(located(105.0, 110.0), located(200.0, 200.0))
        val outcome = MapLocateAssertPure.evaluateAssertFrames(frames, target)
        assertTrue(outcome.matched)
        assertEquals(0, outcome.matchedFrame)
        assertEquals(2, outcome.framesPolled)
    }

    @Test
    fun `跳过未定位帧直到命中`() {
        val frames = listOf(lost(), lost(), located(110.0, 105.0))
        val outcome = MapLocateAssertPure.evaluateAssertFrames(frames, target)
        assertTrue(outcome.matched)
        assertEquals(2, outcome.matchedFrame)
    }

    @Test
    fun `定位到但落在矩形外不算命中`() {
        val frames = listOf(located(0.0, 0.0), located(300.0, 300.0))
        val outcome = MapLocateAssertPure.evaluateAssertFrames(frames, target)
        assertFalse(outcome.matched)
        assertEquals(-1, outcome.matchedFrame)
    }

    @Test
    fun `矩形左闭右开`() {
        // 左/上边界命中
        assertTrue(MapLocateAssertPure.evaluateAssertFrames(listOf(located(100.0, 100.0)), target).matched)
        // 右/下边界（x=120, y=120）落在开区间外
        assertFalse(MapLocateAssertPure.evaluateAssertFrames(listOf(located(120.0, 110.0)), target).matched)
        assertFalse(MapLocateAssertPure.evaluateAssertFrames(listOf(located(110.0, 120.0)), target).matched)
        // 右下角内一像素命中
        assertTrue(MapLocateAssertPure.evaluateAssertFrames(listOf(located(119.99, 119.99)), target).matched)
    }

    @Test
    fun `未命中时 finalFrame 取最后一帧`() {
        val last = lost("最后一帧")
        val frames = listOf(located(0.0, 0.0), last)
        val outcome = MapLocateAssertPure.evaluateAssertFrames(frames, target)
        assertFalse(outcome.matched)
        assertEquals(last, outcome.finalFrame)
        assertEquals("最后一帧", outcome.finalFrame.debugMessage)
    }

    @Test
    fun `命中时 finalFrame 仍是最后一帧`() {
        val frames = listOf(located(105.0, 105.0), lost("tail"))
        val outcome = MapLocateAssertPure.evaluateAssertFrames(frames, target)
        assertTrue(outcome.matched)
        // evaluate 不提前截断序列；命中下标单独给出
        assertEquals(0, outcome.matchedFrame)
        assertEquals("tail", outcome.finalFrame.debugMessage)
    }

    @Test
    fun `空序列兜底未命中`() {
        val outcome = MapLocateAssertPure.evaluateAssertFrames(emptyList(), target)
        assertFalse(outcome.matched)
        assertEquals(0, outcome.framesPolled)
        assertEquals(LocateStatus.NOT_INITIALIZED, outcome.finalFrame.status)
    }

    // ───────────────── 画面静止快速失败（isScreenStatic） ─────────────────

    @Test
    fun `静止阈值常量对齐任务建议`() {
        assertEquals(3, MapLocateAssertPure.ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL)
    }

    @Test
    fun `画面持续变化不会提前失败`() {
        val n = MapLocateAssertPure.ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL
        // 每帧都不同：一直轮询到上限
        assertFalse(MapLocateAssertPure.isScreenStatic((0 until 60).map { it.toLong() }))
        // 有相同但从不连续到 N 帧
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf(1L, 1L, 2L, 2L, 3L, 3L)))
        assertFalse(MapLocateAssertPure.isScreenStatic(List(n - 1) { 42L }))
    }

    @Test
    fun `连续N帧相同则判定静止`() {
        val n = MapLocateAssertPure.ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL
        assertTrue(MapLocateAssertPure.isScreenStatic(List(n) { 7L }))
        assertTrue(MapLocateAssertPure.isScreenStatic(listOf(1L, 2L, 3L) + List(n) { 9L }))
        // 全相同（远超阈值）
        assertTrue(MapLocateAssertPure.isScreenStatic(List(60) { 5L }))
    }

    @Test
    fun `第0帧与不足阈值的边界`() {
        assertFalse(MapLocateAssertPure.isScreenStatic(emptyList()))
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf(1L)))
        // 恰好 n-1 帧相同仍不算静止
        assertFalse(
            MapLocateAssertPure.isScreenStatic(
                List(MapLocateAssertPure.ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL - 1) { 1L },
            ),
        )
        // 恰好 n 帧相同才算
        assertTrue(
            MapLocateAssertPure.isScreenStatic(
                List(MapLocateAssertPure.ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL) { 1L },
            ),
        )
    }

    @Test
    fun `只认末尾连续窗口`() {
        // 前面连续 3 帧相同，但末尾变了 → 不算静止
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf(1L, 1L, 1L, 2L)))
        // 末尾恰好 3 帧相同 → 静止
        assertTrue(MapLocateAssertPure.isScreenStatic(listOf(1L, 2L, 3L, 3L, 3L)))
    }

    @Test
    fun `交替抖动不算静止`() {
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf(1L, 2L, 1L, 2L, 1L, 2L)))
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf(1L, 2L, 1L, 2L, 1L)))
    }

    @Test
    fun `取帧失败 null 打断静止判定`() {
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf<Long?>(3L, 3L, 3L, null)))
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf<Long?>(3L, null, 3L, 3L)))
        assertFalse(MapLocateAssertPure.isScreenStatic(listOf<Long?>(null, null, null)))
        // null 之后重新累积满阈值（且中间无 null）又可以判定静止
        assertTrue(MapLocateAssertPure.isScreenStatic(listOf<Long?>(1L, null, 4L, 4L, 4L)))
    }

    @Test
    fun `buildAssertOptions 固定 expected_zone_id 与 force_global_search`() {
        val options = MapLocateAssertPure.buildAssertOptions(AssertLocationParam(zoneId = "Wuling_Base"))
        assertEquals("Wuling_Base", options.expectedZoneId)
        assertTrue(options.forceGlobalSearch)
        // 其余字段保留默认
        assertEquals(LocateOptions.DEFAULT_LOC_THRESHOLD, options.locThreshold, 0.0)
        assertEquals(LocateOptions.DEFAULT_MAX_LOST_FRAMES, options.maxLostFrames)
    }

    @Test
    fun `tryBuildAssertRect 复用四元校验`() {
        assertNotNull(MapLocateAssertPure.tryBuildAssertRect(AssertLocationParam(target = listOf(1.0, 2.0, 3.0, 4.0))))
        assertNull(MapLocateAssertPure.tryBuildAssertRect(AssertLocationParam(target = listOf(1.0, 2.0, 3.0))))
        assertNull(MapLocateAssertPure.tryBuildAssertRect(AssertLocationParam(target = listOf(1.0, 2.0, 0.0, 4.0))))
    }

    @Test
    fun `detail JSON 全字段且顺序对齐 MEO_JSONIZATION`() {
        val output = MapLocateActionPure.buildAssertLocationOutput(
            LocateResult(
                status = LocateStatus.SUCCESS,
                position = MapPosition(zoneId = "Wuling_Base", x = 941.25, y = 1778.5, score = 0.856, latencyMs = 12L),
                debugMessage = "Global Search Success",
            ),
            AssertLocationParam(zoneId = "Wuling_Base", target = listOf(941.0, 1778.0, 20.0, 20.0)),
            matched = true,
        )
        val json = MapLocateActionPure.assertLocationDetailJson(output)
        // 字段顺序即 LinkedHashMap 顺序
        val keys = Regex("\"([a-zA-Z]+)\":").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "status", "matched", "inTarget", "message", "zoneId", "x", "y", "rot",
                "locConf", "camRot", "camRotConf", "latencyMs", "target",
            ),
            keys,
        )
        assertTrue(json.contains("\"matched\":true"))
        assertTrue(json.contains("\"inTarget\":true"))
        assertTrue(json.contains("\"zoneId\":\"Wuling_Base\""))
        assertTrue(json.contains("\"x\":941.25"))
        assertTrue(json.contains("\"target\":[941.0,1778.0,20.0,20.0]"))
    }

    // ───────────────── 墙钟上限 + 确定性失败提前结束（本轮新增） ─────────────────

    /** 确定性失败：YOLO 看到 zone 但与 selector 不匹配（真机 Route1 空转的那条）。 */
    private fun deterministicFail(message: String = "YOLO 约束未通过（zone 不匹配 selector）") =
        MapLocateAssertPure.AssertFrame(
            status = LocateStatus.YOLO_FAILED,
            position = null,
            debugMessage = message,
        )

    /** 等待态失败：加载过场 / 追踪冷启动会产生，等下去可能变好。 */
    private fun waitingFail(message: String = "本帧没有可定位区域") =
        MapLocateAssertPure.AssertFrame(
            status = LocateStatus.TRACKING_LOST,
            position = null,
            debugMessage = message,
        )

    /**
     * 逐帧喂入并每帧裁决一次，返回（首个非 CONTINUE 的原因, 已轮询帧数）。
     * [perFrameMs] 模拟真机每帧墙钟（含截图 + YOLO + 匹配，~1.1s）。
     */
    private fun simulateStop(
        frames: List<MapLocateAssertPure.AssertFrame>,
        perFrameMs: Long = 1100L,
    ): Pair<AssertStopReason, Int> {
        val seen = ArrayList<MapLocateAssertPure.AssertFrame>()
        for (frame in frames) {
            seen += frame
            val reason = MapLocateAssertPure.decideAssertStop(
                frames = seen,
                targetRect = target,
                elapsedMs = seen.size * perFrameMs,
            )
            if (reason != AssertStopReason.CONTINUE) return reason to seen.size
        }
        return AssertStopReason.CONTINUE to seen.size
    }

    @Test
    fun `新增常量默认值对齐任务建议`() {
        assertEquals(20_000L, MapLocateAssertPure.ASSERT_LOCATE_MAX_WALL_MS)
        assertEquals(6, MapLocateAssertPure.ASSERT_LOCATE_DETERMINISTIC_FAIL_FRAMES)
        // 帧数上限 × 轮询间隔 = 15s 只是**睡眠**预算；真机每帧计算 ~1s，实际墙钟远大于它。
        assertEquals(15_000L, MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES * MapLocateAssertPure.ASSERT_LOCATE_POLL_DELAY_MS)
    }

    @Test
    fun `确定性失败分类能区分两类`() {
        // 成功
        assertEquals(AssertFailKind.SUCCESS, MapLocateAssertPure.classifyAssertFailure(located(1.0, 2.0)))
        // 确定性失败：绑定目标/资产/设备几何
        assertEquals(
            AssertFailKind.DETERMINISTIC,
            MapLocateAssertPure.classifyAssertFailure(deterministicFail()),
        )
        assertEquals(
            AssertFailKind.DETERMINISTIC,
            MapLocateAssertPure.classifyAssertFailure(deterministicFail("地图资产缺 zone=Wuling_Base")),
        )
        assertEquals(
            AssertFailKind.DETERMINISTIC,
            MapLocateAssertPure.classifyAssertFailure(deterministicFail("小地图 ROI 越界")),
        )
        // 等待态：没有可定位区域 / 追踪未稳 / 取帧暂失败 → 不早停
        assertEquals(AssertFailKind.WAITING, MapLocateAssertPure.classifyAssertFailure(waitingFail()))
        assertEquals(
            AssertFailKind.WAITING,
            MapLocateAssertPure.classifyAssertFailure(waitingFail("全局搜索无有效峰")),
        )
        assertEquals(
            AssertFailKind.WAITING,
            MapLocateAssertPure.classifyAssertFailure(waitingFail("取帧失败")),
        )
        // 已定位但被追踪状态机拒（观测存在、消息仍是成功串）也是等待态
        assertEquals(
            AssertFailKind.WAITING,
            MapLocateAssertPure.classifyAssertFailure(waitingFail("Global Search Success")),
        )
    }

    @Test
    fun `确定性失败消息前缀判定`() {
        assertTrue(MapLocateAssertPure.isDeterministicAssertFailureMessage("YOLO 约束未通过（zone 不匹配 selector）"))
        assertTrue(MapLocateAssertPure.isDeterministicAssertFailureMessage("地图资产缺 zone=A"))
        assertFalse(MapLocateAssertPure.isDeterministicAssertFailureMessage(""))
        assertFalse(MapLocateAssertPure.isDeterministicAssertFailureMessage("本帧没有可定位区域"))
        assertFalse(MapLocateAssertPure.isDeterministicAssertFailureMessage("Global Search Success"))
    }

    @Test
    fun `连续N帧同一条确定性失败才算数`() {
        val n = MapLocateAssertPure.ASSERT_LOCATE_DETERMINISTIC_FAIL_FRAMES
        assertTrue(MapLocateAssertPure.hasDeterministicFailureStreak(List(n) { deterministicFail() }))
        assertFalse(MapLocateAssertPure.hasDeterministicFailureStreak(List(n - 1) { deterministicFail() }))
        // 消息不同：即使是确定性失败也不构成「稳定复现的一条」
        assertFalse(
            MapLocateAssertPure.hasDeterministicFailureStreak(
                List(n - 1) { deterministicFail() } + deterministicFail("地图资产缺 zone=A"),
            ),
        )
        // 等待态永不构成确定性失败
        assertFalse(MapLocateAssertPure.hasDeterministicFailureStreak(List(n) { waitingFail() }))
    }

    /**
     * 场景①：画面持续变化（screenStatic 恒 false）且定位持续失败（等待态，非确定性）
     * → 没有早停信号，只能靠**墙钟上限**在 20s 内结束，远早于 60 帧上限。
     */
    @Test
    fun `场景一 画面持续变化且定位持续失败 在时间上限内结束`() {
        val frames = List(MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES) { waitingFail("全局搜索无有效峰") }
        val (reason, polled) = simulateStop(frames)
        assertEquals(AssertStopReason.WALL_CLOCK_BUDGET, reason)
        // 20s / 1.1s ≈ 19 帧，远小于 60
        assertTrue("polled=$polled 应显著小于 60", polled in 15..25)
        assertTrue(polled < MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES)
    }

    /**
     * 场景②：正常「等地图」——先等待若干帧（< 阈值），随后成功命中 → 不被早停误杀。
     */
    @Test
    fun `场景二 等地图若干帧后成功 不被误杀`() {
        val frames = List(3) { waitingFail() } + located(105.0, 105.0)
        val (reason, polled) = simulateStop(frames)
        assertEquals(AssertStopReason.MATCHED, reason)
        assertEquals(4, polled)
    }

    @Test
    fun `等待态再多帧也不会触发确定性早停`() {
        // 10 帧等待态、时间与帧数都未到上限 → 继续（证明不会被误杀）
        val (reason, polled) = simulateStop(List(10) { waitingFail() })
        assertEquals(AssertStopReason.CONTINUE, reason)
        assertEquals(10, polled)
        // 但若随后成功 → 命中
        val (reason2, polled2) = simulateStop(List(10) { waitingFail() } + located(105.0, 105.0))
        assertEquals(AssertStopReason.MATCHED, reason2)
        assertEquals(11, polled2)
    }

    @Test
    fun `确定性失败在第N帧早停且远早于墙钟`() {
        val n = MapLocateAssertPure.ASSERT_LOCATE_DETERMINISTIC_FAIL_FRAMES
        val frames = List(60) { deterministicFail() }
        val (reason, polled) = simulateStop(frames)
        assertEquals(AssertStopReason.DETERMINISTIC_FAILURE, reason)
        assertEquals(n, polled) // 6 帧 ≈ 6.6s，远早于 20s
        assertTrue(polled * 1100L < MapLocateAssertPure.ASSERT_LOCATE_MAX_WALL_MS)
    }

    @Test
    fun `decideAssertStop 优先级与各上限`() {
        // 命中优先于一切（即使墙钟已超）
        assertEquals(
            AssertStopReason.MATCHED,
            MapLocateAssertPure.decideAssertStop(
                listOf(located(105.0, 105.0)), target, elapsedMs = 999_999L,
            ),
        )
        // 帧数上限
        assertEquals(
            AssertStopReason.MAX_FRAMES,
            MapLocateAssertPure.decideAssertStop(
                List(60) { waitingFail() }, target, elapsedMs = 1_000L,
            ),
        )
        // 时间上限
        assertEquals(
            AssertStopReason.WALL_CLOCK_BUDGET,
            MapLocateAssertPure.decideAssertStop(
                List(3) { waitingFail() }, target, elapsedMs = 25_000L,
            ),
        )
        // 画面静止
        assertEquals(
            AssertStopReason.SCREEN_STATIC,
            MapLocateAssertPure.decideAssertStop(
                List(3) { waitingFail() }, target, elapsedMs = 1_000L, screenStatic = true,
            ),
        )
        // 空序列继续
        assertEquals(
            AssertStopReason.CONTINUE,
            MapLocateAssertPure.decideAssertStop(emptyList(), target, elapsedMs = 0L),
        )
    }
}
