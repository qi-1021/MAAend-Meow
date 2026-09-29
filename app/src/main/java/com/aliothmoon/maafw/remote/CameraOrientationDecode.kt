package com.aliothmoon.maafw.remote

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 摄像机朝向 PMF 解码（纯逻辑）。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/CameraOrientationPredictor.cpp`
 * 的 `decodePmf`（234-291），以及常量 `kRefineRadius = 5`（`:26`）。
 *
 * 输入是分类器输出的均匀方位 bin 概率（列 j = 方位角 j 度），输出 [CameraOrientation]。
 * 这里不碰 ONNX：调用方拿到 `float*` 摊平成一维 [FloatArray] 传入即可。
 */
object CameraOrientationDecode {

    /** 上游 `kRefineRadius`（`CameraOrientationPredictor.cpp:26`）。 */
    const val REFINE_RADIUS = 5

    /**
     * 上游 `CameraOrientationPredictor::decodePmf`（`CameraOrientationPredictor.cpp:234-291`）。
     *
     * 步骤：
     *  1. `radianPerBin = 2π / count`；
     *  2. 全 bin 方向向量合成 `(resultantSin, resultantCos)`，用于置信度；
     *  3. argmax 求 `center`（严格 `>`，取第一个最大值）；
     *  4. `center ± 5` 窗口内按 pmf 加权圆均值，列号 `(center+offset+count) % count`
     *     ——**跨 0/360 接缝靠这里取模**（负偏移必须 floorMod，Kotlin `%` 会得负数）；
     *  5. `decoded = atan2(windowSin, windowCos)` 转度，`fmod(x+360, 360)` 归一；`>=360` 归 0；
     *  6. 置信度 = 合成模长 × `cos(|decoded - resultantAngle|)`，clamp 到 `[0,1]`。
     *
     * 空数组 → null（上游 `count == 0` 返回 nullopt）。
     */
    fun decodePmf(pmf: FloatArray): CameraOrientation? {
        val count = pmf.size
        if (count == 0) {
            return null
        }

        val radianPerBin = 2.0 * PI / count.toDouble()

        var resultantSin = 0.0
        var resultantCos = 0.0
        for (j in 0 until count) {
            val theta = j.toDouble() * radianPerBin
            resultantSin += pmf[j] * sin(theta)
            resultantCos += pmf[j] * cos(theta)
        }

        var center = 0
        for (j in 1 until count) {
            if (pmf[j] > pmf[center]) {
                center = j
            }
        }

        var windowSin = 0.0
        var windowCos = 0.0
        for (offset in -REFINE_RADIUS..REFINE_RADIUS) {
            // 跨 0/360：C++ 用 (center+offset+count) % count；这里 floorMod 等价且对负偏移安全。
            val col = Math.floorMod(center + offset, count)
            val theta = col.toDouble() * radianPerBin
            windowSin += pmf[col] * sin(theta)
            windowCos += pmf[col] * cos(theta)
        }

        var decoded = atan2(windowSin, windowCos) * (180.0 / PI)
        // 上游 std::fmod(decoded + 360, 360)；decoded ∈ [-180,180]，加 360 后为正。
        decoded = (decoded + 360.0) % 360.0
        if (decoded >= 360.0) {
            decoded = 0.0
        }

        val resultantAngle = atan2(resultantSin, resultantCos) * (180.0 / PI)
        val resultantLength = hypot(resultantSin, resultantCos)
        val alignmentCos = cos(abs(decoded - resultantAngle) * (PI / 180.0))
        val confidence = (resultantLength * alignmentCos).coerceIn(0.0, 1.0)

        return CameraOrientation(rot = decoded, confidence = confidence)
    }
}
