package com.aliothmoon.maafw.privileged

/**
 * Apple Silicon 上的 Android 模拟器会把宿主的 `sve2`/`sme`/`i8mm` 等扩展一并广播给 guest，
 * 而 OpenCV 的运行时派发据此选中了模拟器实际执行不了的高级指令路径，结果是特权进程在
 * 第一次 `matchTemplate` 就 SIGILL 死亡（现场见 `game-rescue/tombstones/`）。
 *
 * 对策是在**加载 OpenCV 之前**给进程设好 `OPENCV_CPU_DISABLE`：它只在静态初始化时读一次，
 * 之后再设无效（Shizuku 的 `newProcess` 的 env 参数会整体替换环境，丢掉 `BOOTCLASSPATH`
 * 之类会让服务进程根本起不来，所以走 shell 层 export）。
 *
 * 真机 arm64 没有这个问题，也不该关掉这些加速，因此只在模拟器上注入。
 * 本文件是纯逻辑：`Build` 相关取值由调用方传入，便于 verify_pure_logic.sh 覆盖。
 */
object OpenCvEmulatorCompat {

    const val ENV_NAME = "OPENCV_CPU_DISABLE"

    /**
     * 可禁用的都是「分派(dispatched)」分支；baseline 的 NEON/FP16 在 OpenCV 里关不掉，
     * 也不该关（arm64 上 baseline NEON 本身就是正常且必要的）。
     */
    const val DISABLED_FEATURES = "NEON_DOTPROD,NEON_FP16,NEON_BF16,SVE"

    private val EMULATOR_HARDWARE = setOf("ranchu", "goldfish", "goldfish_arm64", "cutf_cvm", "vbox86")

    private val EMULATOR_FINGERPRINT_MARKERS =
        listOf("generic", "emulator", "sdk_gphone", "vbox", "qemu", "ranchu")

    /**
     * 口径与 Android 上常见判定一致：`ro.kernel.qemu=1`、硬件名、或指纹里的模拟器标记。
     * 宁可漏判也不错判——错判会让真机白白关掉加速。
     */
    fun isEmulator(hardware: String?, fingerprint: String?, roKernelQemu: String?): Boolean {
        if (roKernelQemu == "1") return true
        if (hardware != null && hardware.trim().lowercase() in EMULATOR_HARDWARE) return true
        val fp = fingerprint?.lowercase() ?: return false
        return EMULATOR_FINGERPRINT_MARKERS.any { it in fp }
    }

    /**
     * 要前置到 shell 命令里的 `export ...; `，非模拟器返回空串。
     * 值里只有逗号与下划线，仍按单引号包住，避免调用方改动时踩到 shell 特殊字符。
     */
    fun shellPrefix(hardware: String?, fingerprint: String?, roKernelQemu: String?): String =
        if (isEmulator(hardware, fingerprint, roKernelQemu)) {
            "export $ENV_NAME='$DISABLED_FEATURES'; "
        } else {
            ""
        }
}
