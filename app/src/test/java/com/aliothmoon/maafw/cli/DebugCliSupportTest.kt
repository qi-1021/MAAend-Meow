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

    // ───────────────────── probe-result ─────────────────────

    @Test
    fun `probe-result 返回 ProbeResult 意图`() {
        assertEquals(DebugCliIntent.ProbeResult, ok("probe-result"))
    }

    @Test
    fun `probe-result 命令名大小写不敏感`() {
        assertEquals(DebugCliIntent.ProbeResult, ok("Probe-Result"))
    }

    @Test
    fun `probe-result 不需要 controller 就绪`() {
        assertEquals(DebugCliIntent.ProbeResult, ok("probe-result", ctx(controllerReady = false)))
    }

    @Test
    fun `probe-result 不接受参数`() {
        assertTrue(failure("probe-result now").contains("不接受参数"))
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

    // ───────────────────── tracklocate ─────────────────────

    @Test
    fun `tracklocate 只带路径 zone 为空`() {
        assertEquals(
            DebugCliIntent.TrackLocate("/sdcard/frame.png", null),
            ok("tracklocate /sdcard/frame.png"),
        )
    }

    @Test
    fun `tracklocate 带可选 zone`() {
        assertEquals(
            DebugCliIntent.TrackLocate("/sdcard/frame.png", "Wuling_Base"),
            ok("tracklocate /sdcard/frame.png Wuling_Base"),
        )
    }

    @Test
    fun `tracklocate reset 清空状态`() {
        assertEquals(DebugCliIntent.TrackLocate(null, null), ok("tracklocate reset"))
        assertEquals(DebugCliIntent.TrackLocate(null, null), ok("TrackLocate RESET"))
    }

    @Test
    fun `tracklocate reset 不接受额外参数`() {
        assertTrue(failure("tracklocate reset extra").contains("reset 不接受额外参数"))
    }

    @Test
    fun `tracklocate 缺少参数报错`() {
        assertTrue(failure("tracklocate").contains("需要 <截图路径>"))
    }

    @Test
    fun `tracklocate 过多参数报错`() {
        assertTrue(failure("tracklocate a.png Wuling_Base extra").contains("接受 <截图路径> [zone] 或 reset"))
    }

    @Test
    fun `tracklocate controller 未就绪被拒绝`() {
        assertTrue(failure("tracklocate /a.png", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    @Test
    fun `tracklocate reset 不要求 controller`() {
        assertEquals(DebugCliIntent.TrackLocate(null, null), ok("tracklocate reset", ctx(controllerReady = false)))
    }

    // ───────────────────── mapfind ─────────────────────

    @Test
    fun `walk 两参数走到坐标`() {
        assertEquals(DebugCliIntent.Walk(706.0, 1893.0, null), ok("walk 706 1893"))
    }

    @Test
    fun `walk 可带 zone`() {
        assertEquals(DebugCliIntent.Walk(706.5, 1893.5, "Wuling_Base"), ok("walk 706.5 1893.5 Wuling_Base"))
    }

    @Test
    fun `walk 坐标必须是数字`() {
        assertTrue(failure("walk abc 1").contains("必须是数字"))
    }

    @Test
    fun `walk 参数个数校验`() {
        assertTrue(failure("walk 1").isNotEmpty())
        assertTrue(failure("walk 1 2 3 4").isNotEmpty())
    }

    @Test
    fun `mapfind 三参数只解坐标`() {
        assertEquals(
            DebugCliIntent.MapFind("Wuling", 942.6, 1781.2, null),
            ok("mapfind Wuling 942.6 1781.2"),
        )
    }

    @Test
    fun `mapfind 带可选 icon`() {
        assertEquals(
            DebugCliIntent.MapFind("Wuling", 942.6, 1781.2, "TeleportAnchor"),
            ok("mapfind Wuling 942.6 1781.2 TeleportAnchor"),
        )
    }

    @Test
    fun `mapfind 命令名大小写不敏感`() {
        assertEquals(
            DebugCliIntent.MapFind("Z", 1.0, 2.0, null),
            ok("MapFind Z 1 2"),
        )
    }

    @Test
    fun `mapfind 缺少参数报错`() {
        assertTrue(failure("mapfind").contains("需要 <zone> <at_x> <at_y>"))
        assertTrue(failure("mapfind Wuling").contains("需要 <zone> <at_x> <at_y>"))
        assertTrue(failure("mapfind Wuling 1").contains("需要 <zone> <at_x> <at_y>"))
    }

    @Test
    fun `mapfind 坐标非数字报错`() {
        assertTrue(failure("mapfind Wuling abc 2").contains("必须是数字"))
        assertTrue(failure("mapfind Wuling 1 xyz").contains("必须是数字"))
    }

    @Test
    fun `mapfind 过多参数报错`() {
        assertTrue(failure("mapfind Wuling 1 2 Icon extra").contains("接受 <zone> <at_x> <at_y> [icon]"))
    }

    @Test
    fun `mapfind controller 未就绪被拒绝`() {
        assertTrue(failure("mapfind Wuling 1 2", ctx(controllerReady = false)).contains("controller 未就绪"))
    }

    // ───────────────────── copyout ─────────────────────

    @Test
    fun `copyout 返回源与目标路径`() {
        assertEquals(
            DebugCliIntent.CopyOut("/data/data/com.hypergryph.endfield", "/sdcard/Android/data/app/files/rescue"),
            ok("copyout /data/data/com.hypergryph.endfield /sdcard/Android/data/app/files/rescue"),
        )
    }

    @Test
    fun `copyout 命令名大小写不敏感`() {
        assertEquals(
            DebugCliIntent.CopyOut("/data/data/x", "/sdcard/y"),
            ok("CopyOut /data/data/x /sdcard/y"),
        )
    }

    @Test
    fun `copyout 不需要 controller 就绪`() {
        assertEquals(
            DebugCliIntent.CopyOut("/data/data/x", "/sdcard/y"),
            ok("copyout /data/data/x /sdcard/y", ctx(controllerReady = false)),
        )
    }

    @Test
    fun `copyout 缺少参数报错`() {
        assertTrue(failure("copyout").contains("需要 <srcPath> <dstDir>"))
        assertTrue(failure("copyout /data/data/x").contains("需要 <srcPath> <dstDir>"))
    }

    @Test
    fun `copyout 多余参数报错`() {
        assertTrue(failure("copyout /a /b /c").contains("只接受 <srcPath> <dstDir>"))
    }

    @Test
    fun `copyout srcPath 必须绝对`() {
        assertTrue(failure("copyout relative/path /sdcard/y").contains("srcPath 必须是绝对路径"))
    }

    @Test
    fun `copyout dstDir 必须绝对`() {
        assertTrue(failure("copyout /data/data/x relative").contains("dstDir 必须是绝对路径"))
    }

    // ───────────────────── rootcmd ─────────────────────

    @Test
    fun `rootcmd 返回命令串`() {
        assertEquals(DebugCliIntent.RootCmd("id"), ok("rootcmd id"))
    }

    @Test
    fun `rootcmd 命令名大小写不敏感`() {
        assertEquals(DebugCliIntent.RootCmd("id -u"), ok("RootCmd id -u"))
    }

    @Test
    fun `rootcmd 多 token 原样拼回`() {
        assertEquals(
            DebugCliIntent.RootCmd("tar -czf /sdcard/x.tar.gz -C /data/data com.hypergryph.endfield"),
            ok("rootcmd tar -czf /sdcard/x.tar.gz -C /data/data com.hypergryph.endfield"),
        )
    }

    @Test
    fun `rootcmd 不需要 controller 就绪`() {
        assertEquals(DebugCliIntent.RootCmd("id"), ok("rootcmd id", ctx(controllerReady = false)))
    }

    @Test
    fun `rootcmd 缺参数报错`() {
        assertTrue(failure("rootcmd").contains("需要一条 shell 命令"))
    }

    @Test
    fun `rootcmd 命令过长报错`() {
        assertTrue(failure("rootcmd " + "a".repeat(DEBUG_CLI_MAX_ROOT_CMD + 10)).contains("命令过长"))
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
        for (command in listOf("help", "status", "report", "logtail", "screenshot", "ocr", "run", "probe-result", "overrideprobe", "yoloprobe", "coarselocate", "tracklocate", "mapfind", "copyout", "rootcmd")) {
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

    @Test
    fun `statusText 渲染补充包已装版本与要求版本`() {
        val text = DebugCliSupport.statusText(
            ctx().copy(
                supplements = listOf(
                    DebugCliSupplementStatus("map-locate", "2026.9.28", "2026.9.28"),
                    DebugCliSupplementStatus("detect", null, "2026.9.28"),
                ),
            ),
        )
        assertTrue(text.contains("supplements"))
        assertTrue(text.contains("map-locate(installed=2026.9.28, required=2026.9.28)"))
        assertTrue(text.contains("detect(installed=未安装, required=2026.9.28)"))
    }

    @Test
    fun `statusText 无补充包信息时给占位`() {
        val text = DebugCliSupport.statusText(ctx())
        assertTrue(text.contains("supplements  : -"))
    }

    // ───────────────────── 远程调试门控 ─────────────────────

    private fun remoteCtx(authenticated: Boolean = false) = DebugCliContext(
        projectRoot = "/pi",
        controllerReady = true,
        taskRunning = false,
        reportDir = "/r",
        logDir = "/l",
        remoteEnabled = true,
        connection = DebugCliConnection.REMOTE,
        authenticated = authenticated,
    )

    @Test
    fun `远程未鉴权时 status 被拒`() {
        assertTrue(failure("status", remoteCtx()).contains("未鉴权"))
    }

    @Test
    fun `远程未鉴权时 screenshot 被拒`() {
        assertTrue(failure("screenshot", remoteCtx()).contains("未鉴权"))
    }

    @Test
    fun `远程未鉴权时 start 被拒`() {
        assertTrue(failure("start", remoteCtx()).contains("未鉴权"))
    }

    @Test
    fun `远程未鉴权时未知命令也只报销鉴权`() {
        assertTrue(failure("frobnicate", remoteCtx()).contains("未鉴权"))
    }

    @Test
    fun `远程未鉴权时 auth 放行`() {
        assertEquals(DebugCliIntent.Auth("tok"), ok("auth tok", remoteCtx()))
    }

    @Test
    fun `远程未鉴权时 help 放行`() {
        assertEquals(DebugCliIntent.Help, ok("help", remoteCtx()))
    }

    @Test
    fun `远程已鉴权后可执行 status`() {
        assertEquals(DebugCliIntent.Status, ok("status", remoteCtx(authenticated = true)))
    }

    @Test
    fun `回环连接远程开关开启也免令牌`() {
        val loopback = remoteCtx().copy(connection = DebugCliConnection.LOOPBACK)
        assertEquals(DebugCliIntent.Status, ok("status", loopback))
    }

    @Test
    fun `远程开关关闭时远程连接不门控`() {
        val ctx = remoteCtx().copy(remoteEnabled = false)
        assertEquals(DebugCliIntent.Status, ok("status", ctx))
    }

    @Test
    fun `requiresAuth 只在远程未鉴权且开关开启时为真`() {
        assertTrue(remoteCtx().requiresAuth)
        assertFalse(remoteCtx(authenticated = true).requiresAuth)
        assertFalse(remoteCtx().copy(connection = DebugCliConnection.LOOPBACK).requiresAuth)
        assertFalse(remoteCtx().copy(remoteEnabled = false).requiresAuth)
    }

    // ───────────────────── auth ─────────────────────

    @Test
    fun `auth 缺少令牌报错`() {
        assertTrue(failure("auth").contains("需要一个令牌"))
    }

    @Test
    fun `auth 多余参数报错`() {
        assertTrue(failure("auth a b").contains("只接受一个令牌"))
    }

    @Test
    fun `auth 返回令牌意图`() {
        assertEquals(DebugCliIntent.Auth("AbC-123_"), ok("auth AbC-123_"))
    }

    // ───────────────────── start / stop ─────────────────────

    @Test
    fun `start 无参数表示当前激活配置`() {
        assertEquals(DebugCliIntent.Start(emptyList()), ok("start"))
    }

    @Test
    fun `start 带任务名按序保留`() {
        assertEquals(DebugCliIntent.Start(listOf("StartUp", "Reception")), ok("start StartUp Reception"))
    }

    @Test
    fun `start 命令名大小写不敏感`() {
        assertEquals(DebugCliIntent.Start(emptyList()), ok("START"))
    }

    @Test
    fun `stop 返回停止意图`() {
        assertEquals(DebugCliIntent.Stop, ok("stop"))
    }

    @Test
    fun `stop 不接受参数`() {
        assertTrue(failure("stop now").contains("不接受参数"))
    }

    @Test
    fun `helpText 列出 start stop auth`() {
        val text = DebugCliSupport.helpText()
        for (command in listOf("start", "stop", "auth", "click", "swipe", "pullfile", "pushfile", "game", "update-apk")) {
            assertTrue("helpText 缺少 $command", text.contains(command))
        }
    }

    @Test
    fun `screencap 正常与未就绪拦截`() {
        assertEquals(DebugCliIntent.Screencap(null), ok("screencap"))
        assertEquals(DebugCliIntent.Screencap("/sdcard/test.png"), ok("screencap /sdcard/test.png"))
        assertTrue(failure("screencap", ctx(controllerReady = false)).contains(DebugCliSupport.CONTROLLER_NOT_READY))
    }

    @Test
    fun `click 参数校验与 controller 就绪要求`() {
        assertEquals(DebugCliIntent.Click(100, 200), ok("click 100 200"))
        assertTrue(failure("click 100").contains("两个坐标参数"))
        assertTrue(failure("click a b").contains("必须是整数"))
        assertTrue(failure("click 100 200", ctx(controllerReady = false)).contains(DebugCliSupport.CONTROLLER_NOT_READY))
    }

    @Test
    fun `swipe 参数解析与默认耗时`() {
        assertEquals(DebugCliIntent.Swipe(10, 20, 30, 40, 300), ok("swipe 10 20 30 40"))
        assertEquals(DebugCliIntent.Swipe(10, 20, 30, 40, 500), ok("swipe 10 20 30 40 500"))
        assertTrue(failure("swipe 10 20").contains("格式"))
    }

    @Test
    fun `touchdown touchmove touchup 参数校验`() {
        assertEquals(DebugCliIntent.TouchDown(0, 100, 200, 50), ok("touchdown 0 100 200"))
        assertEquals(DebugCliIntent.TouchDown(1, 100, 200, 80), ok("touchdown 1 100 200 80"))
        assertEquals(DebugCliIntent.TouchMove(1, 150, 250, 80), ok("touchmove 1 150 250 80"))
        assertEquals(DebugCliIntent.TouchUp(1), ok("touchup 1"))
        assertTrue(failure("touchdown 10 100 200").contains("0..9"))
        assertTrue(failure("touchup").contains("格式"))
    }

    @Test
    fun `key 注入解析`() {
        assertEquals(DebugCliIntent.Key("back"), ok("key back"))
        assertEquals(DebugCliIntent.Key("4"), ok("key 4"))
    }

    @Test
    fun `pullfile 与 pushfile 绝对路径及参数解析`() {
        assertEquals(DebugCliIntent.PullFile("/sdcard/a.txt", 0L, 512 * 1024), ok("pullfile /sdcard/a.txt"))
        assertEquals(DebugCliIntent.PullFile("/sdcard/a.txt", 1024L, 2048), ok("pullfile /sdcard/a.txt 1024 2048"))
        assertTrue(failure("pullfile relative/path").contains("绝对路径"))

        assertEquals(DebugCliIntent.PushFile("/sdcard/b.txt", "aGVsbG8=", false), ok("pushfile /sdcard/b.txt aGVsbG8="))
        assertEquals(DebugCliIntent.PushFile("/sdcard/b.txt", "aGVsbG8=", true), ok("pushfile /sdcard/b.txt aGVsbG8= true"))
    }

    @Test
    fun `ls 与 rm 校验`() {
        assertEquals(DebugCliIntent.Ls("/sdcard"), ok("ls /sdcard"))
        assertEquals(DebugCliIntent.Rm("/sdcard/temp.png"), ok("rm /sdcard/temp.png"))
        assertTrue(failure("rm rel.txt").contains("绝对路径"))
    }

    @Test
    fun `game 子命令与 displayId 解析`() {
        assertEquals(DebugCliIntent.Game("kill", null), ok("game kill"))
        assertEquals(DebugCliIntent.Game("top", null), ok("game top"))
        assertEquals(DebugCliIntent.Game("launch", null), ok("game launch"))
        assertEquals(DebugCliIntent.Game("launch", 0), ok("game launch 0"))
        assertTrue(failure("game unknown").contains("未知 game 子命令"))
    }

    @Test
    fun `device-status tasks 与 update-apk 解析`() {
        assertEquals(DebugCliIntent.DeviceStatus, ok("device-status"))
        assertEquals(DebugCliIntent.Tasks, ok("tasks"))
        assertEquals(DebugCliIntent.UpdateApk("/data/local/tmp/app.apk"), ok("update-apk /data/local/tmp/app.apk"))
        assertTrue(failure("update-apk rel.apk").contains("绝对路径"))
    }
}
