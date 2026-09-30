package com.aliothmoon.maafw.cli

import com.aliothmoon.maafw.BuildConfig
import com.aliothmoon.maafw.IAppCommandCallback
import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.PrivilegedServiceState
import com.aliothmoon.maafw.project.PiInstallCoordinator
import com.aliothmoon.maafw.project.PiInstallState
import com.aliothmoon.maafw.project.PiInstaller
import com.aliothmoon.maafw.runner.RunLaunchResult
import com.aliothmoon.maafw.runner.RunLauncher
import com.aliothmoon.maafw.runner.RunTrigger
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.settings.AppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * app 进程侧的调试 CLI 协调器。做两件事：
 *
 * 1. **预初始化**：调试模式下 App 一起来（特权进程连上）就把 CLI 监听拉起来，
 *    不必先跑一次任务。它只调 [com.aliothmoon.maafw.RemoteService.configureDebugCli]，
 *    该接口在特权侧只做 CLI 需要的部分，不搬 `setup()` 的其余副作用。
 * 2. **反向命令桥**：注册 [IAppCommandCallback]，让特权进程的 CLI 收到 `start` / `stop` 时，
 *    能回落给 app 进程走与 UI 相同的 [RunLauncher] → [RunnerPort] 路径。运行计划只在
 *    app 进程构建得出来，这是唯一不硬造的做法。
 *
 * 只在 debug 构建接线（Release 里 [start] 直接返回，不注册、不绑定）。
 *
 * PI 根不是启动那一下就固定的：App 一起来时若 PI 还没解包（首次安装 / versionCode 变了要重解，
 * [PiInstaller.installedDir] 会抛 [com.aliothmoon.maafw.project.PiNotInstalledException]），
 * 早先这里只读一次就永远空着。所以把 [PiInstallCoordinator.state] 也纳入触发源：
 * 一旦解包到 [PiInstallState.Ready]，重新 configure，把补上的 projectRoot 交给 CLI。
 */
class DebugCliCoordinator(
    private val settings: AppSettingsGateway,
    private val servicePort: PrivilegedServicePort,
    private val installer: PiInstaller,
    private val piInstall: PiInstallCoordinator,
    private val runLauncher: RunLauncher,
    private val runnerPort: RunnerPort,
    private val scope: CoroutineScope,
) {

    private val commandCallback = object : IAppCommandCallback.Stub() {
        override fun onStartTasks(taskNamesJson: String) {
            scope.launch(MaaDispatchers.IO) { handleStart(taskNamesJson) }
        }

        override fun onStopRun() {
            scope.launch(MaaDispatchers.IO) {
                runCatching { runnerPort.stop() }
                    .onFailure { Timber.w(it, "debug CLI stop request failed") }
            }
        }
    }

    fun start() {
        if (!BuildConfig.DEBUG) return
        scope.launch {
            combine(
                settings.debugMode,
                settings.remoteDebug,
                settings.remoteDebugToken,
                servicePort.serviceState,
                // 只关心「解包好了没」这一个跃迁：Unpacking 的进度是几千次的高频更新，
                // 若把状态本身塞进 combine，distinctUntilChanged 拦不住，会疯狂重复 configure。
                piInstall.state.map { it is PiInstallState.Ready },
            ) { debug, remote, token, state, piReady -> DebugCliConfig(debug, remote, token, state, piReady) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    private suspend fun apply(config: DebugCliConfig) {
        if (config.serviceState != PrivilegedServiceState.Connected) return
        val service = servicePort.serviceOrNull() ?: return
        // 反向桥只在调试模式注册；其余情况解注册，避免留着一条无用通道
        runCatching { service.setAppCommandCallback(if (config.debug) commandCallback else null) }
            .onFailure { Timber.w(it, "setAppCommandCallback failed") }

        if (!config.debug) {
            runCatching { service.configureDebugCli(null, null, false, false, null) }
                .onFailure { Timber.w(it, "stop debug CLI failed") }
            return
        }

        // PI 未装时 piRoot 为空：CLI 仍能起（status/help/start/stop），只是拿不到 controller。
        // 每次 apply 都现读：PI 解包完成后 piInstall.state 会跃到 Ready，触发这里重跑并补上根。
        val piRoot = runCatching { installer.installedDir().absolutePath }.getOrNull()
        val logDir = AppPaths.LOG_DIR.absolutePath
        runCatching {
            service.configureDebugCli(
                piRoot,
                logDir,
                true,
                config.remote,
                config.token.takeIf { it.isNotBlank() },
            )
        }.onFailure { Timber.w(it, "configureDebugCli failed") }
    }

    private suspend fun handleStart(taskNamesJson: String) {
        val taskNames = parseTaskNames(taskNamesJson)
        val result = runCatching { runLauncher.launch(RunTrigger.Manual, taskFilter = taskNames.toSet()) }
            .getOrElse {
                Timber.w(it, "debug CLI start request failed")
                return
            }
        Timber.i("debug CLI start result: %s (tasks=%s)", result, taskNames)
        if (result is RunLaunchResult.Rejected) {
            Timber.w("debug CLI start rejected: %s", result.reason)
        }
    }

    private fun parseTaskNames(json: String): List<String> =
        runCatching { Json.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())

    private data class DebugCliConfig(
        val debug: Boolean,
        val remote: Boolean,
        val token: String,
        val serviceState: PrivilegedServiceState,
        /** PI 是否已解包完成；仅用于在 Ready 那一下重新 configure 补 projectRoot。 */
        val piReady: Boolean,
    )
}
