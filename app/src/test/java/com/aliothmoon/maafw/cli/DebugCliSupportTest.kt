package com.aliothmoon.maafw.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DebugCliSupport] 的纯逻辑测试。
 *
 * 这里钉死的是协议本身：哪个命令接受什么参数、controller 没好时哪些命令必须拒绝、
 * 行数怎么夹到上限。socket 那层编不了，命令语义这层必须本地可回归。
 */
class DebugCliSupportTest {

    private val readyCtx = DebugCliContext(
        projectRoot = "/data/user/0/pkg/files/pi",
        controllerReady = true,
        taskRunning = false,
        reportDir = "/data/user/0/pkg/files/log/report",
        logDir = "/data/user/0/pkg/files/log",
    )

    private fun ctx(
        controllerReady: Boolean = true,
        taskRunning: Boolean = false,
        projectRoot: String? = "/pi",
        reportDir: String? = "/report",
        logDir: String? = "/log",
    ) = DebugCliContext(projectRoot, controllerReady, taskRunning, reportDir, logDir)

    private fun ok(line: String, context: DebugCliContext = readyCtx): DebugCliIntent =
        (DebugCliSupport.parse(line, context) as DebugCliParse.Ok).intent

    private fun failure(line: String, context: DebugCliContext = readyCtx): String =
        (DebugCliSupport.parse(line, context) as DebugCliParse.Failure).message

    // ───────────────────── 基础命令 ─────────────────────

    @Test
    fun `help 返回 Help 意图`() {
        assertEquals(DebugCliIntent.Help, ok("help"))
    }

    @Test
    fun `status 返回 Status 意图`() {
        assertEquals(DebugCliIntent.Status, ok("status"))
    }

    @Test
    fun `help 不接受参数`() {
        assertTrue(failure("help me").contains("不接受参数"))
    }

    @Test
    fun `status 不接受参数`() {
        assertTrue(failure("status now").contains("不接受参数"))
    }

    @Test
    fun `命令名大小写不敏感`() {
        assertEquals(DebugCliIntent.Help, ok("HELP"))
        assertEquals(DebugCliIntent.Status, ok(" Status "))
    }

    @Test
    fun `未知命令报错并提示 help`() {
        assertTrue(failure("frobnicate").contains("未知命令"))
        assertTrue(failure("frobnicate").contains("help"))
    }

    @Test
    fun `空行报错`() {
        assertTrue(failure("").contains("空命令"))
    }

    @Test
    fun `纯空白报错`() {
        assertTrue(failure("   \t  ").contains("空命令"))
    }

    @Test
    fun `多余空白被折叠`() {
        assertEquals(DebugCliIntent.Report(7), ok("report    7"))
    }

    // ───────────────────── report ─────────────────────

    @Test
    fun `report 默认 50 行`() {
        assertEquals(DebugCliIntent.Report(50), ok("report"))
    }

    @Test
    fun `report 指定行数`() {
        assertEquals(DebugCliIntent.Report(3), ok("report 3"))
    }

    @Test
    fun `report 超上限夹到上限`() {
        assertEquals(DebugCliIntent.Report(DEBUG_CLI_MAX_TAIL), ok("report 999999"))
    }

    @Test
    fun `report 行数非整数报错`() {
        assertTrue(failure("report abc").contains("必须是整数"))
    }

    @Test
    fun `report 行数为零报错`() {
        assertTrue(failure("report 0").contains("必须大于 0"))
    }

    @Test
    fun `report 负数报错`() {
        assertTrue(failure("report -5").contains("必须大于 0"))
    }

    @Test
    fun `report 多余参数报错`() {
        assertTrue(failure("report 10 20").contains("最多接受一个"))
    }

    // ───────────────────── logtail ─────────────────────

    @Test
    fun `logtail 默认 50 行`() {
        assertEquals(DebugCliIntent.LogTail(50), ok("logtail"))
    }

    @Test
    fun `logtail 指定行数`() {
        assertEquals(DebugCliIntent.LogTail(12), ok("logtail 12"))
    }

    @Test
    fun `logtail 非法行数报错`() {
        assertTrue(failure("logtail x").contains("必须是整数"))
    }

    // ───────────────────── screenshot ─────────────────────

    @Test
    fun `screenshot controller 就绪返回意图`() {
        assertEquals(DebugCliIntent.Screenshot, ok("screenshot"))
    }

    @Test
    fun `screenshot controller 未就绪被拒绝`() {
        assertTrue(failure("screenshot", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    @Test
    fun `screenshot 不接受参数`() {
        assertTrue(failure("screenshot now").contains("不接受参数"))
    }

    // ───────────────────── ocr ─────────────────────

    @Test
    fun `ocr 正常返回节点名`() {
        assertEquals(DebugCliIntent.Ocr("ReceptionCountdownText"), ok("ocr ReceptionCountdownText"))
    }

    @Test
    fun `ocr 节点名保留大小写`() {
        assertEquals(DebugCliIntent.Ocr("MyNode"), ok("ocr MyNode"))
    }

    @Test
    fun `ocr 缺少节点名报错`() {
        assertTrue(failure("ocr").contains("需要一个节点名"))
    }

    @Test
    fun `ocr controller 未就绪被拒绝`() {
        assertTrue(failure("ocr SomeNode", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    @Test
    fun `ocr 多余参数报错`() {
        assertTrue(failure("ocr A B").contains("只接受一个节点名"))
    }

    // ───────────────────── run ─────────────────────

    @Test
    fun `run 正常返回节点名`() {
        assertEquals(DebugCliIntent.Run("StartUp"), ok("run StartUp"))
    }

    @Test
    fun `run 缺少节点名报错`() {
        assertTrue(failure("run").contains("需要一个节点名"))
    }

    @Test
    fun `run controller 未就绪被拒绝`() {
        assertTrue(failure("run StartUp", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    // ───────────────────── overrideprobe ─────────────────────

    @Test
    fun `overrideprobe controller 就绪返回意图`() {
        assertEquals(DebugCliIntent.OverrideProbe, ok("overrideprobe"))
    }

    @Test
    fun `overrideprobe 命令名大小写不敏感`() {
        assertEquals(DebugCliIntent.OverrideProbe, ok("OverrideProbe"))
    }

    @Test
    fun `overrideprobe 不接受参数`() {
        assertTrue(failure("overrideprobe now").contains("不接受参数"))
    }

    @Test
    fun `overrideprobe controller 未就绪被拒绝`() {
        assertTrue(failure("overrideprobe", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    // ───────────────────── yoloprobe ─────────────────────

    @Test
    fun `yoloprobe 正常返回图片路径`() {
        assertEquals(
            DebugCliIntent.YoloProbe("/sdcard/minimap.png"),
            ok("yoloprobe /sdcard/minimap.png"),
        )
    }

    @Test
    fun `yoloprobe 命令名大小写不敏感`() {
        assertEquals(DebugCliIntent.YoloProbe("/a.png"), ok("YoloProbe /a.png"))
    }

    @Test
    fun `yoloprobe 缺少路径报错`() {
        assertTrue(failure("yoloprobe").contains("需要一个"))
    }

    @Test
    fun `yoloprobe 多余参数报错`() {
        assertTrue(failure("yoloprobe a.png b.png").contains("只接受一个"))
    }

    @Test
    fun `yoloprobe controller 未就绪被拒绝`() {
        assertTrue(failure("yoloprobe /a.png", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    // ───────────────────── coarselocate ─────────────────────

    @Test
    fun `coarselocate 只带路径 zone 为空`() {
        assertEquals(
            DebugCliIntent.CoarseLocate("/sdcard/frame.png", null),
            ok("coarselocate /sdcard/frame.png"),
        )
    }

    @Test
    fun `coarselocate 带可选 zone`() {
        assertEquals(
            DebugCliIntent.CoarseLocate("/sdcard/frame.png", "Wuling_Base"),
            ok("coarselocate /sdcard/frame.png Wuling_Base"),
        )
    }

    @Test
    fun `coarselocate 命令名大小写不敏感`() {
        assertEquals(DebugCliIntent.CoarseLocate("/a.png", null), ok("CoarseLocate /a.png"))
    }

    @Test
    fun `coarselocate 缺少路径报错`() {
        assertTrue(failure("coarselocate").contains("需要一张全帧截图路径"))
    }

    @Test
    fun `coarselocate 过多参数报错`() {
        assertTrue(failure("coarselocate a.png Wuling_Base extra").contains("接受 <截图路径> [zone]"))
    }

    @Test
    fun `coarselocate controller 未就绪被拒绝`() {
        assertTrue(failure("coarselocate /a.png", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    // ───────────────────── 渲染与常量 ─────────────────────

    @Test
    fun `结束标记是固定值`() {
        assertEquals("--END--", DEBUG_CLI_END_MARKER)
    }

    @Test
    fun `端口是 7777`() {
        assertEquals(7777, DEBUG_CLI_PORT)
    }

    @Test
    fun `默认末行数与上限常量`() {
        assertEquals(50, DEBUG_CLI_DEFAULT_TAIL)
        assertEquals(2000, DEBUG_CLI_MAX_TAIL)
    }

    @Test
    fun `helpText 列出全部命令`() {
        val text = DebugCliSupport.helpText()
        for (command in listOf("help", "status", "report", "logtail", "screenshot", "ocr", "run", "overrideprobe", "yoloprobe", "coarselocate")) {
            assertTrue("helpText 缺少 $command", text.contains(command))
        }
    }

    @Test
    fun `statusText 反映就绪与运行状态`() {
        val text = DebugCliSupport.statusText(
            DebugCliContext("/pi", controllerReady = true, taskRunning = true, reportDir = "/r", logDir = "/l"),
        )
        assertTrue(text.contains("controller   : ready"))
        assertTrue(text.contains("task         : running"))
        assertTrue(text.contains("/pi"))
        assertTrue(text.contains("/r"))
        assertTrue(text.contains("/l"))
    }

    @Test
    fun `statusText 未设置项目根时给占位`() {
        val text = DebugCliSupport.statusText(ctx(projectRoot = null, controllerReady = false))
        assertTrue(text.contains("(未设置)"))
        assertTrue(text.contains("controller   : not ready"))
        assertTrue(text.contains("task         : idle"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `statusText 空项目根也给占位`() {
        val text = DebugCliSupport.statusText(ctx(projectRoot = "  "))
        assertTrue(text.contains("(未设置)"))
    }
}
