package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.IAppCommandCallback
import com.aliothmoon.maafw.ITouchEventCallback
import com.aliothmoon.maafw.RemoteService
import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.bridge.InputControlUtils
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.cli.DebugCliContext
import com.aliothmoon.maafw.cli.DebugCliHost
import com.aliothmoon.maafw.cli.DebugCliOcrResult
import com.aliothmoon.maafw.cli.DebugCliRelayClient
import com.aliothmoon.maafw.cli.DebugCliServer
import com.aliothmoon.maafw.cli.DebugCliSupplementStatus
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.diagnostics.RunDiagnostics
import com.aliothmoon.maafw.diagnostics.RunDiagnosticsPolicy
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.remote.internal.ActivityUtils
import com.aliothmoon.maafw.remote.internal.AppWatchdog
import com.aliothmoon.maafw.remote.internal.PermissionGrantHelper
import com.aliothmoon.maafw.service.AccessibilityHelperService
import com.aliothmoon.maafw.supplement.SupplementPackLocal
import com.aliothmoon.maafw.supplement.SupplementVersion
import com.aliothmoon.maafw.remote.internal.PowerController
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.ScreenManager
import com.aliothmoon.maafw.constant.PrivilegedGrant
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.remote.internal.WakeUnlockController
import com.aliothmoon.maafw.third.FakeContext
import com.aliothmoon.maafw.third.Ln
import com.aliothmoon.maafw.third.wrappers.ServiceManager
import com.aliothmoon.maafw.third.Workarounds
import android.view.Surface
import android.os.Process
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 特权进程的入口对象：由 Shizuku 或 root starter 反射实例化，实例化即完成进程内初始化
 * 构造函数不能抛：抛了 binder 回不去，app 侧只看得到连接超时
 */
class RemoteServiceImpl : RemoteService.Stub() {

    private val virtualDisplayMode = AtomicInteger(DisplayMode.BACKGROUND)
    private val appPid = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)
    private var piRoot: String? = null

    /** 调试 CLI 的 start/stop 反向桥；由 app 进程注册，CLI 收到命令时回调。 */
    @Volatile
    private var appCommandCallback: IAppCommandCallback? = null

    /** 当前 CLI 宿主；中继客户端复用它执行命令（与 [DebugCliServer] 同一份能力）。 */
    @Volatile
    private var debugCliHost: DebugCliRunnerHost? = null

    // 两者互相引用：host 要把 child 的输出交回 runner 的回调。用 lazy 打破初始化顺序——
    // host 的 lambda 到真正有输出时才读 runner，那会儿它早已建好
    private val runner: MaaRunner by lazy { MaaRunner(agentHost) }
    private val agentHost: ExecAgentHost by lazy {
        ExecAgentHost { line, fromStderr ->
            runner.onAgentLine(line, fromStderr)
        }
    }

    init {
        RemoteBootTrace.mark("CTOR_START")
        Workarounds.apply()
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching(::cleanup) }.apply { name = "remote-shutdown-hook" }
        )
        startHeartbeatWatchdog()
        RemoteBootTrace.mark("CTOR_DONE")
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        Ln.i("$TAG: destroy()")
        AppWatchdog.stopWatching()
        InputControlUtils.setTouchCallback(null)
        runner.destroy()
        DebugCliRelayClient.stop()
        DebugCliServer.stop()
        cleanup()
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun version(): String = buildString {
        append("bridge=").append(if (NativeBridgeLib.LOADED) NativeBridgeLib.ping() else "not loaded")
        append(" uid=").append(Process.myUid())
        append(" pid=").append(Process.myPid())
        append(" pi=").append(piRoot ?: "unset")
    }

    override fun pid(): Int = Process.myPid()

    override fun watchdogState(): Int = AppWatchdog.state.value

    override fun watchdogTargetPackage(): String = AppWatchdog.targetPackage.orEmpty()

    // ── 亮屏与解锁 ──

    override fun unlock(credential: String?): Int =
        WakeUnlockController.unlock(credential.orEmpty())

    override fun testUnlock(credential: String?): Int =
        WakeUnlockController.testUnlock(credential.orEmpty())

    override fun lockAndSleep(): Int = WakeUnlockController.lockAndSleep()

    override fun isScreenOn(): Boolean =
        runCatching { ServiceManager.getPowerManager().isScreenOn(0) }.getOrDefault(true)

    override fun stopTargetApp(): Boolean {
        val target = AppWatchdog.targetPackage ?: run {
            Ln.i("$TAG: stopTargetApp skipped, watchdog never acquired a target")
            return false
        }
        return runCatching {
            ServiceManager.getActivityManager().forceStopPackage(target)
            Ln.i("$TAG: force-stopped $target")
            true
        }.getOrElse {
            Ln.w("$TAG: stopTargetApp failed: $it")
            false
        }
    }

    override fun startTargetApp(packageName: String?): Boolean {
        return runCatching {
            val displayId = when (virtualDisplayMode.get()) {
                DisplayMode.PRIMARY -> 0
                DisplayMode.BACKGROUND -> {
                    var vdId = VirtualDisplayManager.getDisplayId()
                    if (vdId == DefaultDisplayConfig.DISPLAY_NONE) {
                        vdId = startVirtualDisplay()
                    }
                    vdId
                }
                else -> 0
            }
            val targetSpec = if (!packageName.isNullOrBlank()) packageName else "com.hypergryph.endfield"
            Ln.i("$TAG: startTargetApp spec=$targetSpec displayId=$displayId")
            val resolvedPkg = ActivityUtils.packageNameOf(targetSpec)
            val success = ActivityUtils.startApp(resolvedPkg, displayId, forceStop = false, excludeFromRecents = true)
            if (success) {
                AppWatchdog.setExplicitTarget(resolvedPkg)
            }
            success
        }.getOrElse {
            Ln.e("$TAG: startTargetApp failed", it)
            false
        }
    }

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
    }

    override fun setup(piRoot: String?, logDir: String?, isDebug: Boolean): Boolean {
        if (piRoot.isNullOrBlank() || !File(piRoot).isDirectory) {
            Ln.e("$TAG: setup failed - PI root not readable: $piRoot")
            return false
        }
        this.piRoot = piRoot
        // agent child 的 cwd 与上游 MaaPiCli 对齐，取 PI 根
        runner.setProjectRoot(piRoot)
        // Android 12 起子进程会被 phantom process killer 收割，接 native 前先关掉
        // agent child 同样吃这条：它是特权进程 fork 出来的，不关就会被一起收走
        PermissionGrantHelper.disablePhantomProcessKiller()
        // 特权进程是 shell/root 身份，app 建的目录未必可写，这里自己建一遍
        if (!logDir.isNullOrBlank() && ensureWritableDir(logDir)) {
            runner.applyGlobalOptions(logDir, isDebug)
            // debug 才开结构化报告；release 下这里是 no-op，不建任何文件
            RunDiagnostics.start(isDebug, logDir)
            // debug CLI：仅 debug 构建且开启调试模式时监听 127.0.0.1；release 下 BuildConfig.DEBUG 为
            // false，完全不启动（不监听端口、不建文件）
            if (BuildConfig.DEBUG && isDebug) {
                val cliHost = DebugCliRunnerHost(logDir)
                debugCliHost = cliHost
                DebugCliServer.start(cliHost)
            }
        } else {
            Ln.w("$TAG: log dir unusable, MaaFramework will write to process CWD: $logDir")
            // 目录不可用就明确关掉，免得沿用上一轮的开启状态
            RunDiagnostics.start(false)
        }
        Ln.i("$TAG: setup ok, piRoot=$piRoot")
        return true
    }

    private fun ensureWritableDir(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Ln.e("$TAG: mkdirs failed: $path")
            return false
        }
        return dir.canWrite()
    }

    // ── 显示 ──

    override fun setVirtualDisplayMode(mode: Int): Boolean = when (mode) {
        DisplayMode.PRIMARY -> {
            VirtualDisplayManager.stop()
            virtualDisplayMode.set(mode)
            true
        }

        DisplayMode.BACKGROUND -> {
            PrimaryDisplayManager.stop()
            virtualDisplayMode.set(mode)
            true
        }

        else -> false
    }

    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) {
        VirtualDisplayManager.setResolution(width, height, dpi)
    }

    override fun startVirtualDisplay(): Int = when (virtualDisplayMode.get()) {
        DisplayMode.PRIMARY -> PrimaryDisplayManager.start()
        DisplayMode.BACKGROUND -> VirtualDisplayManager.start().also { displayId ->
            if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
                PowerController.startUserActivityKeepAlive(displayId)
            }
        }

        else -> DefaultDisplayConfig.DISPLAY_NONE
    }

    override fun stopVirtualDisplay() {
        AppWatchdog.stopWatching()
        when (virtualDisplayMode.get()) {
            DisplayMode.PRIMARY -> PrimaryDisplayManager.stop()
            DisplayMode.BACKGROUND -> {
                PowerController.stopUserActivityKeepAlive()
                VirtualDisplayManager.stop()
            }
        }
    }

    /** 没有虚拟屏时返回 true：调用方据此判断「是否需要拉回」，无屏可拉即无需处理 */
    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) return true
        return ActivityUtils.isAppOnDisplay(packageName, displayId)
    }

    override fun moveAppToVirtualDisplay(packageName: String): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            Ln.w("$TAG: moveAppToVirtualDisplay: no active virtual display")
            return false
        }
        return ActivityUtils.repinAppToDisplay(packageName, displayId)
    }

    override fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) {
        ActivityUtils.forceFullscreenOnVirtualDisplay = enabled
    }

    override fun setDisplayPower(on: Boolean) {
        PowerController.setDisplayPower(on)
    }

    /**
     * 改主屏分辨率会把整个系统的 UI 重排一遍，失败要报出去而不是吞掉——
     * 用户看到「已修改」却什么都没变，只会以为是自己屏幕不支持
     */
    override fun setForcedDisplaySize(width: Int, height: Int): Boolean {
        Ln.i("$TAG: setForcedDisplaySize(${width}x$height)")
        return runCatching { ScreenManager.setForcedDisplaySize(width, height) }
            .onFailure { Ln.e("$TAG: setForcedDisplaySize failed: ${it.message}") }
            .getOrDefault(false)
    }

    override fun clearForcedDisplaySize(): Boolean {
        Ln.i("$TAG: clearForcedDisplaySize")
        return runCatching { ScreenManager.clearForcedDisplaySize() }
            .onFailure { Ln.e("$TAG: clearForcedDisplaySize failed: ${it.message}") }
            .getOrDefault(false)
    }

    // ── 预览 ──

    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG: setMonitorSurface(${surface != null})")
        VirtualDisplayManager.setMonitorSurface(surface)
        NativeBridgeLib.setPreviewSurface(surface)
    }

    override fun setTouchCallback(callback: ITouchEventCallback?) {
        InputControlUtils.setTouchCallback(callback)
    }

    // ── 预览上的手动操作；主屏模式下不接管输入 ──

    override fun touchDown(x: Int, y: Int, contact: Int) =
        withVirtualDisplay { InputControlUtils.down(x, y, contact, it) }

    override fun touchMove(x: Int, y: Int, contact: Int) =
        withVirtualDisplay { InputControlUtils.move(x, y, contact, it) }

    override fun touchUp(x: Int, y: Int, contact: Int) =
        withVirtualDisplay { InputControlUtils.up(x, y, contact, it) }

    private inline fun withVirtualDisplay(action: (Int) -> Unit) {
        if (virtualDisplayMode.get() == DisplayMode.PRIMARY) return
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) action(displayId)
    }

    // ── 执行 ──

    override fun setRunnerCallback(callback: IMaaRunnerCallback?) {
        runner.setCallback(callback)
    }

    override fun setAppCommandCallback(callback: IAppCommandCallback?) {
        appCommandCallback = callback
        Ln.i("$TAG: setAppCommandCallback registered=${callback != null}")
    }

    /**
     * 预初始化调试 CLI，让它在 App 启动（调试模式开启）时就监听，而不必先跑一次任务。
     *
     * 只提前 CLI 真正需要的部分：[MaaRunner.setProjectRoot]（纯路径赋值 + 清两处缓存，无 IO/无 native）
     * 与 [DebugCliServer.configure]。**不搬** setup() 的其余副作用：
     *  - `disablePhantomProcessKiller()`：改系统设置，只在真要 fork agent child 前才该做；
     *  - `applyGlobalOptions()`：配置 MaaFramework native 日志，需要 native 库与明确的 logDir；
     *  - `RunDiagnostics.start()`：会建目录 / 落盘，属于「开跑」才发生的事。
     * 这三项继续留在 setup()，从「提前」改为「维持原位」。
     *
     * 硬门控：release 构建 BuildConfig.DEBUG 为 false，整段 no-op，不监听端口、不建文件。
     */
    override fun configureDebugCli(
        piRoot: String?,
        logDir: String?,
        enabled: Boolean,
        remoteEnabled: Boolean,
        token: String?,
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (!enabled) {
            DebugCliRelayClient.stop()
            debugCliHost = null
            DebugCliServer.stop()
            return false
        }
        if (!piRoot.isNullOrBlank() && File(piRoot).isDirectory) {
            // 纯路径赋值：CLI 的 status / 探针要它；与 setup() 同源，重复设置幂等
            runner.setProjectRoot(piRoot)
        }
        val dir = logDir?.takeIf { it.isNotBlank() } ?: return false
        val cliHost = DebugCliRunnerHost(dir)
        debugCliHost = cliHost
        DebugCliServer.configure(cliHost, remoteEnabled, token)
        return true
    }

    /**
     * 启动中继客户端：手机出站连公网桥，长轮询取命令后直接走 [DebugCliHost] 执行。
     *
     * 前提是调试 CLI 已配置（[configureDebugCli] 已建好宿主）；没有宿主时如实返回 false，
     * 由 App 侧把失败原因呈现给用户，而不是假装连上了。
     */
    override fun startDebugRelay(relayUrl: String?, token: String?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val host = debugCliHost
        if (host == null) {
            Ln.w("$TAG: startDebugRelay rejected, debug CLI host not ready")
            return false
        }
        return DebugCliRelayClient.start(host, relayUrl.orEmpty(), token.orEmpty())
    }

    override fun stopDebugRelay() {
        DebugCliRelayClient.stop()
    }

    override fun debugRelayState(): Int = DebugCliRelayClient.state()

    override fun debugRelayHandled(): Long = DebugCliRelayClient.handled()

    override fun debugRelayDetail(): String = DebugCliRelayClient.detail()

    override fun debugRelaySessionId(): String = DebugCliRelayClient.sessionId()

    override fun startRun(runPlanJson: String?): Boolean {
        if (runPlanJson.isNullOrBlank()) return false
        val started = runner.start(runPlanJson)
        if (started) AppWatchdog.startWatching()
        return started
    }

    override fun stopRun(): Boolean {
        AppWatchdog.stopWatching()
        return runner.stop()
    }

    override fun isRunning(): Boolean = runner.isRunning()

    override fun saveCachedImage(path: String?): Boolean =
        !path.isNullOrBlank() && runner.saveCachedImage(path)

    override fun maaVersion(): String? = MaaFrameworkLoader.library?.MaaVersion()

    /**
     * 逐项独立执行：一项失败不影响其余，返回实际授到的位
     * 失败不抛——app 侧据返回值决定要不要再引导用户手点
     */
    override fun grantPermissions(packageName: String?, uid: Int, permissions: Int): Int {
        if (packageName.isNullOrBlank()) return 0
        var granted = 0
        if (permissions and PrivilegedGrant.NOTIFICATION != 0 &&
            PermissionGrantHelper.grantNotificationPermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.NOTIFICATION
        }
        if (permissions and PrivilegedGrant.BATTERY != 0 &&
            PermissionGrantHelper.grantBatteryOptimizationExemption(packageName)
        ) {
            granted = granted or PrivilegedGrant.BATTERY
        }
        if (permissions and PrivilegedGrant.BACKGROUND != 0 &&
            PermissionGrantHelper.grantBackgroundUnrestricted(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.BACKGROUND
        }
        if (permissions and PrivilegedGrant.OVERLAY != 0 &&
            PermissionGrantHelper.grantFloatingWindowPermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.OVERLAY
        }
        // 服务 id 不用过 binder 传：特权进程跑的就是这个 APK，直接引用常量即可
        if (permissions and PrivilegedGrant.ACCESSIBILITY != 0 &&
            PermissionGrantHelper.grantAccessibilityService(AccessibilityHelperService.SERVICE_ID)
        ) {
            granted = granted or PrivilegedGrant.ACCESSIBILITY
        }
        if (permissions and PrivilegedGrant.STORAGE != 0 &&
            PermissionGrantHelper.grantStoragePermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.STORAGE
        }
        Ln.i("$TAG: grantPermissions($packageName) requested=$permissions granted=$granted")
        return granted
    }

    override fun isPackageInstalled(packageName: String): Boolean = try {
        FakeContext.get().packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        Ln.w("$TAG: isPackageInstalled: $packageName not found", e)
        false
    }

    /**
     * 逐项隔离，不共用一个 runCatching：原先四项串在一个块里，头一项抛了后面全跳过
     *
     * [ScreenManager.destroy] 尤其漏不得——它撤的是**物理主屏**的强改尺寸，
     * 漏掉的话用户会留在一块被改小的屏幕上，而且只能靠再拉一次特权进程才撤得回来。
     * 它自己按 flag 文件判要不要动手，没改过时是空操作
     */
    private fun cleanup() {
        step("screen size") { ScreenManager.destroy() }
        step("power") { PowerController.destroy() }
        step("primary display") { PrimaryDisplayManager.stop() }
        step("virtual display") { VirtualDisplayManager.stop() }
    }

    private inline fun step(name: String, action: () -> Unit) {
        runCatching(action).onFailure { Ln.e("$TAG: cleanup $name failed: ${it.message}") }
    }

    /**
     * app 进程消失后特权进程必须自杀
     * linkToDeath 是主路径，这里兜住「binder 还没建立就崩了」的窗口
     */
    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val pid = appPid.get()
                if (pid <= 0) continue
                if (!File("/proc/$pid").exists()) {
                    Ln.w("$TAG: app process (pid=$pid) gone, destroying remote service")
                    destroy()
                    return@Thread
                }
            }
        }.apply {
            name = "remote-heartbeat-watchdog"
            isDaemon = true
        }.start()
    }

    /**
     * debug CLI 的能力宿主：把 [DebugCliServer] 的意图接到 [runner]（controller / tasker / resource
     * 都在它手里）。跑在特权进程里，所以能直接取缓存帧、跑识别；不做任何 Android UI 依赖。
     */
    private inner class DebugCliRunnerHost(private val logDir: String) : DebugCliHost {

        /** 截图落点：`<root>/files/cli/`（logDir 是 `<root>/files/log`）。 */
        private val cliDir: File = File(File(logDir).parentFile, "cli")

        /** 补充包目录：与 logDir 同级的 `<root>/files/supplements`（见 SupplementPackInstaller）。 */
        private val supplementsDir: File = File(File(logDir).parentFile, "supplements")

        override fun context(): DebugCliContext = DebugCliContext(
            projectRoot = runner.debugProjectRoot(),
            controllerReady = runner.debugControllerReady(),
            taskRunning = runner.isRunning(),
            reportDir = File(logDir, RunDiagnosticsPolicy.REPORT_DIR).absolutePath,
            logDir = logDir,
            supplements = supplementStatuses(),
        )

        /**
         * 补充包诊断：包清单（要求版本）是 App 侧常量，已装版本从各包目录里的版本标记读。
         * 特权进程拿不到 app 的 assets，所以不读清单 JSON——要求版本本来就不来自清单。
         */
        private fun supplementStatuses(): List<DebugCliSupplementStatus> =
            SupplementVersion.REQUIRED.keys.map { packId ->
                DebugCliSupplementStatus(
                    packId = packId,
                    installedVersion = SupplementPackLocal.readInstalledVersion(
                        File(supplementsDir, packId),
                    ),
                    requiredVersion = SupplementVersion.requiredFor(packId),
                )
            }

        override fun newestReportFile(): File? {
            val dir = File(logDir, RunDiagnosticsPolicy.REPORT_DIR)
            return dir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name.startsWith(RunDiagnosticsPolicy.FILE_PREFIX) &&
                        it.name.endsWith(RunDiagnosticsPolicy.FILE_SUFFIX)
                }
                ?.maxByOrNull { it.lastModified() }
        }

        override fun mainLogFile(): File? {
            // MaaFramework 的 maa.log 信息最全；没有就退到 app.log，再退到目录里最新的 .log
            for (name in listOf("maa.log", "app.log")) {
                val file = File(logDir, name)
                if (file.isFile) return file
            }
            return File(logDir).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") }
                ?.maxByOrNull { it.lastModified() }
        }

        override fun screenshotDir(): File = cliDir

        override fun screenshot(target: File): String? = runner.debugSaveCachedImage(target.absolutePath)

        /**
         * 启动任务：特权进程拿不到 RunPlan（在 app 进程构建），经反向桥请求 app 侧用
         * 既有 RunLauncher → RunnerPort 代发。桥未注册时如实说明，不硬造。
         */
        override fun start(taskNames: List<String>): String {
            val callback = appCommandCallback
                ?: return "error: app 未注册启动桥（确认 app 在前台且调试模式已开启）"
            val json = JsonArray(taskNames.map { JsonPrimitive(it) }).toString()
            return runCatching {
                callback.onStartTasks(json)
                val target = if (taskNames.isEmpty()) "当前激活配置全部任务" else taskNames.joinToString(" ")
                "已请求 app 侧启动：$target（用 status / logtail 观察进度）"
            }.getOrElse { "error: 请求 app 启动失败：${it.javaClass.simpleName}: ${it.message}" }
        }

        /** 停止任务：同样回落给 app 侧 [com.aliothmoon.maafw.runner.RunnerPort.stop]。 */
        override fun stop(): String {
            val callback = appCommandCallback
                ?: return "error: app 未注册启动桥（确认 app 在前台且调试模式已开启）"
            return runCatching {
                callback.onStopRun()
                "已请求 app 侧停止当前任务"
            }.getOrElse { "error: 请求 app 停止失败：${it.javaClass.simpleName}: ${it.message}" }
        }

        override fun ocr(nodeName: String): DebugCliOcrResult {
            val outcome = runner.debugOcrOnce(nodeName)
            return DebugCliOcrResult(ok = outcome.hit, text = outcome.text, reason = outcome.reason)
        }

        override fun run(nodeName: String): String = runner.debugRunOnce(nodeName)

        override fun probeResult(): List<String> = runner.debugLastProbeResult()

        override fun overrideProbe(): List<String> = runner.debugOverrideProbe()

        override fun yoloProbe(imagePath: String): List<String> = runner.debugYoloProbe(imagePath)

        override fun coarseLocate(imagePath: String, zone: String?): List<String> =
            runner.debugCoarseLocate(imagePath, zone)

        override fun trackLocate(imagePath: String?, zone: String?): List<String> =
            runner.debugTrackLocate(imagePath, zone)

        override fun mapFind(zone: String, atX: Double, atY: Double, icon: String?): List<String> =
            runner.debugMapFind(zone, atX, atY, icon)

        override fun walk(x: Double, y: Double, zone: String?): List<String> =
            runner.debugWalk(x, y, zone)

        override fun screencap(target: File): String? = runner.debugScreencap(target.absolutePath)

        override fun click(x: Int, y: Int): List<String> {
            val err = runner.debugClick(x, y)
            return if (err == null) listOf("ok: click ($x, $y)") else listOf("error: $err")
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): List<String> {
            val err = runner.debugSwipe(x1, y1, x2, y2, durationMs)
            return if (err == null) listOf("ok: swipe ($x1, $y1) -> ($x2, $y2) ${durationMs}ms") else listOf("error: $err")
        }

        override fun touchDown(contact: Int, x: Int, y: Int, pressure: Int): List<String> {
            val err = runner.debugTouchDown(contact, x, y, pressure)
            return if (err == null) listOf("ok: touchDown contact=$contact ($x, $y) p=$pressure") else listOf("error: $err")
        }

        override fun touchMove(contact: Int, x: Int, y: Int, pressure: Int): List<String> {
            val err = runner.debugTouchMove(contact, x, y, pressure)
            return if (err == null) listOf("ok: touchMove contact=$contact ($x, $y) p=$pressure") else listOf("error: $err")
        }

        override fun touchUp(contact: Int): List<String> {
            val err = runner.debugTouchUp(contact)
            return if (err == null) listOf("ok: touchUp contact=$contact") else listOf("error: $err")
        }

        override fun key(key: String): List<String> {
            val code = when (key.lowercase()) {
                "back" -> 4
                "home" -> 3
                "enter" -> 66
                "power" -> 26
                "tab" -> 61
                "space" -> 62
                else -> key.toIntOrNull()
            }
            if (code == null) return listOf("error: 未知按键：$key（支持 back, home, enter, power, tab, space 或整数 keycode）")
            return rootCmd("input keyevent $code")
        }

        override fun pullFile(remotePath: String, offsetBytes: Long, maxBytes: Int): List<String> {
            val file = File(remotePath)
            if (!file.exists()) return listOf("error: 文件不存在：$remotePath")
            if (file.isDirectory) return listOf("error: 目标是目录：$remotePath")
            val total = file.length()
            if (offsetBytes >= total) {
                return listOf("ok: offset=$offsetBytes total=$total chunk=0 eof=true", "b64:")
            }
            return try {
                val clampedMax = maxBytes.coerceIn(1, 2 * 1024 * 1024)
                val readLen = (total - offsetBytes).coerceAtMost(clampedMax.toLong()).toInt()
                val bytes = ByteArray(readLen)
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offsetBytes)
                    raf.readFully(bytes)
                }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val eof = offsetBytes + readLen >= total
                listOf("ok: offset=$offsetBytes total=$total chunk=$readLen eof=$eof", "b64:$b64")
            } catch (t: Throwable) {
                listOf("error: 读取失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }

        override fun pushFile(remotePath: String, base64Data: String, append: Boolean): List<String> {
            val file = File(remotePath)
            return try {
                file.parentFile?.mkdirs()
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                if (append) {
                    file.appendBytes(bytes)
                } else {
                    file.writeBytes(bytes)
                }
                listOf("ok: written ${bytes.size} bytes (total: ${file.length()}) to ${file.absolutePath}")
            } catch (t: Throwable) {
                listOf("error: 写入失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }

        override fun ls(remotePath: String): List<String> {
            val dir = File(remotePath)
            if (!dir.exists()) return listOf("error: 路径不存在：$remotePath")
            if (dir.isFile) {
                return listOf("file: ${dir.absolutePath} ${dir.length()} bytes")
            }
            val files = dir.listFiles() ?: return listOf("error: 无法列出目录内容：$remotePath")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            return buildList {
                add("dir: ${dir.absolutePath} (total ${files.size})")
                files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
                    val type = if (f.isDirectory) "d" else "-"
                    val time = sdf.format(Date(f.lastModified()))
                    add(String.format(Locale.US, "%s %10d  %s  %s", type, f.length(), time, f.name))
                }
            }
        }

        override fun rm(remotePath: String): List<String> {
            val file = File(remotePath)
            if (!file.exists()) return listOf("error: 文件不存在：$remotePath")
            return try {
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) listOf("ok: removed $remotePath") else listOf("error: 删除失败")
            } catch (t: Throwable) {
                listOf("error: 删除异常：${t.javaClass.simpleName}: ${t.message}")
            }
        }

        override fun game(action: String, displayId: Int?): List<String> {
            val pkg = "com.hypergryph.endfield"
            val activity = "com.u8.sdk.U8UnityContext"
            return when (action.lowercase()) {
                "kill" -> rootCmd("am force-stop $pkg")
                "top" -> rootCmd("pidof $pkg")
                "launch" -> {
                    val dispArg = if (displayId != null) "--display $displayId" else ""
                    rootCmd("am start -n $pkg/$activity $dispArg")
                }
                else -> listOf("error: 未知 game 操作：$action")
            }
        }

        override fun deviceStatus(): List<String> {
            val memInfo = Runtime.getRuntime()
            val freeMb = memInfo.freeMemory() / (1024 * 1024)
            val totalMb = memInfo.totalMemory() / (1024 * 1024)
            val maxMb = memInfo.maxMemory() / (1024 * 1024)
            return buildList {
                add("=== Device Status ===")
                add("app_jvm_memory : free=${freeMb}MB total=${totalMb}MB max=${maxMb}MB")
                add("task_running   : ${runner.isRunning()}")
                add("controller     : ${if (runner.debugControllerReady()) "ready" else "not ready"}")
                // 补充系统 dumpsys 摘要
                val sysRes = rootCmd("dumpsys battery | grep -E 'level|temperature' && dumpsys display | grep -E 'DisplayDeviceInfo.*MaaFwVirtualDisplay'")
                addAll(sysRes)
            }
        }

        override fun tasks(): List<String> {
            val dir = File(runner.debugProjectRoot() ?: "", "pipeline")
            return if (!dir.isDirectory) {
                listOf("error: pipeline 目录不存在")
            } else {
                val pipelines = dir.listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension } ?: emptyList()
                listOf("available_pipelines (${pipelines.size}): " + pipelines.joinToString(", "))
            }
        }

        override fun updateApk(apkPath: String): List<String> {
            val apk = File(apkPath)
            if (!apk.exists() || !apk.isFile) return listOf("error: APK 文件不存在：$apkPath")
            return rootCmd("pm install -r -d $apkPath")
        }

        /**
         * 特权进程内递归复制：源路径限定在常用数据根下，目标必须落在本 App 自己的外部
         * files 目录，避免调试接口被用来乱写别处。root 授权后即可读 `/data/data/<pkg>`。
         *
         * 只做「基本校验」：两边都要求绝对路径（解析层已保证），这里再做范围与存在性判定；
         * 任一步失败如实返回 `error:`，绝不静默产出半份数据。
         */
        override fun copyOut(srcPath: String, dstDir: String): List<String> {
            val src = File(srcPath)
            val dst = File(dstDir)
            val allowedRoot = canonicalOrNull(
                File("/sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files")
            ) ?: return listOf("error: 无法解析 app 外部目录")
            val dstCanonical = canonicalOrNull(dst)
                ?: return listOf("error: 无法解析目标目录：$dstDir")
            if (dstCanonical != allowedRoot &&
                !dstCanonical.path.startsWith(allowedRoot.path + File.separator)
            ) {
                return listOf("error: dstDir 必须位于 ${allowedRoot.path} 之下（当前：${dstCanonical.path}）")
            }
            val srcCanonical = canonicalOrNull(src)
                ?: return listOf("error: 无法解析源路径：$srcPath")
            val allowedSrcRoots = listOf("/data/data", "/data/user", "/data/app", "/sdcard", "/storage")
            if (allowedSrcRoots.none {
                    srcCanonical.path == it || srcCanonical.path.startsWith(it + File.separator)
                }
            ) {
                return listOf("error: srcPath 不在允许范围内（/data/data, /data/user, /data/app, /sdcard, /storage）")
            }
            if (!src.exists()) return listOf("error: 源路径不存在：$srcPath")
            return try {
                val target = File(dst, src.name)
                target.parentFile?.mkdirs()
                val stats = CopyStats()
                copyRecursive(src, target, stats, HashSet())
                listOf(
                    "ok: copied ${stats.files} files, ${stats.bytes} bytes",
                    "src: ${srcCanonical.path}",
                    "dst: ${target.absolutePath}",
                )
            } catch (t: Throwable) {
                listOf("error: copyout 失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }

        private fun canonicalOrNull(file: File): File? =
            runCatching { file.canonicalFile }.getOrNull()

        /** 递归复制；[visited] 记已访问规范路径，挡住符号链接自环。 */
        private fun copyRecursive(src: File, dst: File, stats: CopyStats, visited: MutableSet<String>) {
            val canonical = src.canonicalPath
            if (!visited.add(canonical)) return
            when {
                src.isDirectory -> {
                    dst.mkdirs()
                    src.listFiles()?.forEach { child ->
                        copyRecursive(child, File(dst, child.name), stats, visited)
                    }
                }

                src.isFile -> {
                    dst.parentFile?.mkdirs()
                    src.inputStream().use { input ->
                        dst.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    }
                    stats.files++
                    stats.bytes += dst.length()
                }
                // socket/fifo 等特殊文件救不了也不该救，跳过
            }
        }

        /**
         * 以 root 跑一条 shell 命令并回显。硬门控在 debug 构建内；实现见 [runRootCommand]。
         */
        override fun rootCmd(command: String): List<String> {
            if (!BuildConfig.DEBUG) return listOf("error: rootcmd 仅 debug 构建可用")
            return runCatching { runRootCommand(command) }
                .getOrElse { listOf("error: rootcmd 失败：${it.javaClass.simpleName}: ${it.message}") }
        }

        /**
         * 优先 `su -c`：这是 KernelSU 按应用授权、弹窗放行的正规入口。逐个候选路径尝试启动，
         * 起不来的（不存在 / 不可执行）换下一个。若候选全起不来而本进程本身已是 uid 0
         * （Root 后端的特权进程就是这种），直接 `sh -c`——不为了形式上的 su 把能成的也赔进去。
         */
        private fun runRootCommand(command: String): List<String> {
            val suCandidates = listOf(
                "su",
                "/system/bin/su",
                "/system/xbin/su",
                "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su",
            )
            var lastError: String? = null
            for (su in suCandidates) {
                try {
                    return execCollect(listOf(su, "-c", command), "su=$su")
                } catch (e: java.io.IOException) {
                    lastError = "$su: ${e.message}"
                }
            }
            if (Process.myUid() == 0) {
                return execCollect(listOf("sh", "-c", command), "direct(uid=0)")
            }
            return listOf(
                "error: 没有可用的 su（试过：${suCandidates.joinToString(", ")}）",
                "hint: 在 KernelSU 里对本 App 授权，或把运行后端切到 Root 后重试",
                "last: ${lastError ?: "-"}",
            )
        }

        /** 起进程、并发抽干 stdout/stderr（避免缓冲区写满死锁），带超时；回显退出码与两路输出。 */
        private fun execCollect(argv: List<String>, source: String): List<String> {
            val process = ProcessBuilder(argv).redirectErrorStream(false).start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = drain(process.inputStream, stdout)
            val errThread = drain(process.errorStream, stderr)
            val finished = process.waitFor(ROOT_CMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(1_000L)
                errThread.join(1_000L)
                return listOf(
                    "error: rootcmd 超时（${ROOT_CMD_TIMEOUT_SECONDS}s，$source）",
                    "hint: 若 KernelSU 弹窗未处理，请手动允许后重试",
                )
            }
            outThread.join(2_000L)
            errThread.join(2_000L)
            val out = stdout.toString().trimEnd('\n')
            val err = stderr.toString().trimEnd('\n')
            return buildList {
                add("exit: ${process.exitValue()}")
                add("via: $source")
                if (out.isNotEmpty()) {
                    add("stdout:")
                    out.lines().forEach { add(it) }
                }
                if (err.isNotEmpty()) {
                    add("stderr:")
                    err.lines().forEach { add(it) }
                }
            }
        }

        /** 后台线程把 [stream] 逐行读进 [sink]；进程被杀后流关闭，线程自然结束。 */
        private fun drain(stream: java.io.InputStream, sink: StringBuilder): Thread =
            Thread {
                runCatching { stream.bufferedReader().use { r -> r.forEachLine { sink.appendLine(it) } } }
            }.apply {
                name = "debug-cli-rootcmd-drain"
                isDaemon = true
                start()
            }
    }

    private companion object {
        const val TAG = "RemoteService"
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** `rootcmd` 单次执行超时；KernelSU 弹窗需要人点，给足时间。 */
        const val ROOT_CMD_TIMEOUT_SECONDS = 120L
    }
}

/** copyout 复制计数：文件数与总字节数，用于回执与本地核对（不能嵌在 inner class 里）。 */
private class CopyStats {
    var files = 0L
    var bytes = 0L
}
