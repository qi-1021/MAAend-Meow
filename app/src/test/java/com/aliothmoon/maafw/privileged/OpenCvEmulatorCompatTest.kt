package com.aliothmoon.maafw.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenCvEmulatorCompat] 只做一件事：判断当前是不是模拟器，据此决定要不要给特权进程注入
 * `OPENCV_CPU_DISABLE`。判错方向的成本不对称——真机被误判会白白关掉 OpenCV 的加速，
 * 模拟器漏判则继续在 `matchTemplate` 上 SIGILL，所以两边都要钉住。
 */
class OpenCvEmulatorCompatTest {

    /** 实测的模拟器指纹：硬件名 ranchu、指纹里带 sdk_gphone。 */
    private val emulatorFingerprint =
        "google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys"

    /** 真机样例：既不是已知模拟器硬件名，指纹里也没有任何模拟器标记。 */
    private val deviceFingerprint =
        "Redmi/rubens_global/rubens:14/UP1A.231005.007/OS2.0.6.0.UNPMIXM:user/release-keys"

    @Test
    fun `模拟器特征分别命中三条判据`() {
        // 1) ro.kernel.qemu
        assertTrue(OpenCvEmulatorCompat.isEmulator(null, null, "1"))
        // 2) 硬件名（含大小写与空白容错）
        assertTrue(OpenCvEmulatorCompat.isEmulator("ranchu", null, null))
        assertTrue(OpenCvEmulatorCompat.isEmulator("goldfish", null, null))
        assertTrue(OpenCvEmulatorCompat.isEmulator("  RANCHU  ", null, null))
        // 3) 指纹标记
        assertTrue(OpenCvEmulatorCompat.isEmulator(null, emulatorFingerprint, null))
    }

    @Test
    fun `真机不会被误判`() {
        assertFalse(OpenCvEmulatorCompat.isEmulator("rubens", deviceFingerprint, "0"))
        assertFalse(OpenCvEmulatorCompat.isEmulator("qcom", deviceFingerprint, null))
        // 全空只能判成「不是模拟器」，否则真机在拿不到属性时会无谓地关掉加速
        assertFalse(OpenCvEmulatorCompat.isEmulator(null, null, null))
    }

    @Test
    fun `模拟器上返回可执行的 export 前缀`() {
        val prefix = OpenCvEmulatorCompat.shellPrefix("ranchu", emulatorFingerprint, null)
        // 必须是 shell 能直接接在命令前的形态：export 名=值;
        assertTrue(prefix.startsWith("export "))
        assertTrue(prefix.endsWith("; "))
        assertTrue(prefix.contains(OpenCvEmulatorCompat.ENV_NAME))
        assertTrue(prefix.contains(OpenCvEmulatorCompat.DISABLED_FEATURES))
        // 值被单引号包住，防止以后往里加 $ 或空格时被 shell 解释
        assertTrue(prefix.contains("'" + OpenCvEmulatorCompat.DISABLED_FEATURES + "'"))
    }

    @Test
    fun `真机上前缀为空串`() {
        assertEquals("", OpenCvEmulatorCompat.shellPrefix("rubens", deviceFingerprint, "0"))
        assertEquals("", OpenCvEmulatorCompat.shellPrefix(null, null, null))
    }

    @Test
    fun `禁用清单只包含可分派的特性`() {
        // baseline 的 NEON/FP16 在 OpenCV 里关不掉，写进来只会误导后来人
        val features = OpenCvEmulatorCompat.DISABLED_FEATURES.split(",")
        assertFalse(features.contains("NEON"))
        assertFalse(features.contains("FP16"))
        assertTrue(features.contains("NEON_DOTPROD"))
        assertTrue(features.contains("SVE"))
    }
}
