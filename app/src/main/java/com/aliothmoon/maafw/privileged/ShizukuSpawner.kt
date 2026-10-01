package com.aliothmoon.maafw.privileged

import android.os.Build
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Shizuku newProcess 前台拉起：adb 模式下 launcher 已是 shell 身份，直接 exec 成服务进程，
 * IRemoteProcess 追踪到的就是服务进程本身；root 模式下 launcher fork 降权后 waitpid 子进程，
 * 追踪到的是 launcher；两种情况下进程退出（exec 被拒 / 服务进程死）都经 alive 轮询秒级可见
 *
 * 不经 Shizuku manager App 中转，规避 OEM 杀后台断链与 MTK makeApplication 崩溃
 * Shizuku.newProcess 已 private（API 14 计划移除），直接走 IShizukuService AIDL
 */
object ShizukuSpawner : ProcessSpawner {

    // test -x：DAC 层不可执行直接 126 退出，不白等超时
    // stdio 断开防止管道写满卡住服务进程，日志全走 --log-file
    // 前置的 OpenCV 兼容前缀只在模拟器上非空：宿主 CPU 特性会被透传，OpenCV 会选中执行不了的
    // 指令路径，而它的分派只在静态初始化时读一次环境，所以必须在服务进程 exec 之前就 export 好
    override fun wrapCommand(launcherPath: String, invocation: String): String {
        val openCvCompat = OpenCvEmulatorCompat.shellPrefix(
            Build.HARDWARE, Build.FINGERPRINT, roKernelQemu()
        )
        return "${openCvCompat}test -x ${shellQuote(launcherPath)} || exit 126;" +
            " exec $invocation </dev/null >/dev/null 2>&1"
    }

    /** `ro.kernel.qemu` 是最权威的模拟器信号，但它没有公开 API，取不到就按「不是」处理 */
    private fun roKernelQemu(): String? = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod("get", String::class.java)
            .invoke(null, "ro.kernel.qemu") as? String
    }.getOrNull()

    override fun spawn(command: String): SpawnHandle =
        RemoteProcessHandle(requireServer().newProcess(arrayOf("sh", "-c", command), null, null))

    override fun killResidual(processName: String) {
        runCatching {
            val process = requireServer().newProcess(
                arrayOf("sh", "-c", killByNameCommand(processName)), null, null
            )
            // 同步等 kill 完成，避免与下一次拉起交错
            runCatching { process.waitFor() }
            runCatching { process.destroy() }
        }.onFailure {
            Timber.w(it, "kill residual %s failed", processName)
        }
    }

    private fun requireServer(): IShizukuService {
        val binder = Shizuku.getBinder() ?: error("Shizuku binder unavailable")
        return IShizukuService.Stub.asInterface(binder)
    }

    private class RemoteProcessHandle(private val process: IRemoteProcess) : SpawnHandle {

        // server 侧异常（如 Shizuku 自身重启）判不了生死，按存活继续等
        override fun isAlive(): Boolean = runCatching { process.alive() }.getOrDefault(true)

        override fun exitCode(): Int? = runCatching { process.exitValue() }.getOrNull()
    }
}
