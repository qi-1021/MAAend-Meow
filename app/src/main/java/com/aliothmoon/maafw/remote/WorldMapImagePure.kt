package com.aliothmoon.maafw.remote

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 世界地图视口求解所需的**纯逻辑图像原语**：降采样、双线性缩放、灰度复制成 BGR。
 *
 * 上游对应 `WorldMapSolver.cpp` 里那几处 OpenCV 调用：
 *  - `ScanScales`：`cv::resize(needle, scaled, ..., INTER_AREA/INTER_LINEAR)`（330）；
 *  - `SolveViewport`：`cv::resize(base, baseSmall, ..., INTER_AREA)`（863）；
 *  - `PrepareSearchFeature`/`ToGray`：BGR→灰度（`MatchStrategy.cpp:205-219`、`WorldMapSolver.cpp:134-147`）。
 *
 * 匹配本身交给框架 `TemplateMatch`（C++/OpenCV），本文件只把「喂给框架的那张图/模板」按
 * 正确的尺寸与插值造出来。所有函数只吃 `ByteArray`，不依赖 Android/JNA，可在本机单测。
 */
object WorldMapImagePure {

    /** BGR 交错、每像素 3 字节。 */
    const val BGR_CHANNELS = 3

    /** `INTER_AREA` 降采样的整数倍输出尺寸：`max(1, dim/factor)`。 */
    fun downscaledSize(width: Int, height: Int, factor: Int): Pair<Int, Int> {
        val f = max(1, factor)
        return max(1, width / f) to max(1, height / f)
    }

    /**
     * 灰度 `INTER_AREA` 降采样（整数倍）：每个输出像素取 `factor×factor` 源块均值。
     * 末块不足 `factor` 宽/高时按实际像素数平均（与 OpenCV 边界行为近似）。
     *
     * @return `outW*outH` 灰度；尺寸非法/长度不符时 null。
     */
    fun downscaleGrayArea(src: ByteArray, width: Int, height: Int, factor: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (src.size != width * height) return null
        val f = max(1, factor)
        val (outW, outH) = downscaledSize(width, height, f)
        val out = ByteArray(outW * outH)
        for (oy in 0 until outH) {
            val y0 = oy * f
            val y1 = min(y0 + f, height)
            for (ox in 0 until outW) {
                val x0 = ox * f
                val x1 = min(x0 + f, width)
                var sum = 0
                var count = 0
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        sum += src[row + x].toInt() and 0xFF
                        count++
                    }
                }
                out[oy * outW + ox] = if (count > 0) (sum / count).toByte() else 0
            }
        }
        return out
    }

    /** BGR `INTER_AREA` 降采样（整数倍）：每个输出像素取源块三通道均值。 */
    fun downscaleBgrArea(src: ByteArray, width: Int, height: Int, factor: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (src.size != width * height * BGR_CHANNELS) return null
        val f = max(1, factor)
        val (outW, outH) = downscaledSize(width, height, f)
        val out = ByteArray(outW * outH * BGR_CHANNELS)
        for (oy in 0 until outH) {
            val y0 = oy * f
            val y1 = min(y0 + f, height)
            for (ox in 0 until outW) {
                val x0 = ox * f
                val x1 = min(x0 + f, width)
                var sb = 0
                var sg = 0
                var sr = 0
                var count = 0
                for (y in y0 until y1) {
                    var si = (y * width + x0) * BGR_CHANNELS
                    for (x in x0 until x1) {
                        sb += src[si].toInt() and 0xFF
                        sg += src[si + 1].toInt() and 0xFF
                        sr += src[si + 2].toInt() and 0xFF
                        si += BGR_CHANNELS
                        count++
                    }
                }
                val oi = (oy * outW + ox) * BGR_CHANNELS
                if (count > 0) {
                    out[oi] = (sb / count).toByte()
                    out[oi + 1] = (sg / count).toByte()
                    out[oi + 2] = (sr / count).toByte()
                }
            }
        }
        return out
    }

    /**
     * 灰度双线性缩放（任意目标尺寸），对齐 OpenCV `cv::resize(..., INTER_LINEAR)` 的
     * 像素中心映射：`fx = (dx + 0.5) * srcW / dstW - 0.5`，边界 clamp。
     */
    fun resizeGrayBilinear(src: ByteArray, width: Int, height: Int, dstWidth: Int, dstHeight: Int): ByteArray? {
        if (width <= 0 || height <= 0 || dstWidth <= 0 || dstHeight <= 0) return null
        if (src.size != width * height) return null
        val out = ByteArray(dstWidth * dstHeight)
        val scaleX = width.toDouble() / dstWidth
        val scaleY = height.toDouble() / dstHeight
        for (dy in 0 until dstHeight) {
            val fy = (dy + 0.5) * scaleY - 0.5
            val y0 = floor(fy).toInt()
            val wy = fy - y0
            for (dx in 0 until dstWidth) {
                val fx = (dx + 0.5) * scaleX - 0.5
                val x0 = floor(fx).toInt()
                val wx = fx - x0
                val v00 = sampleClamped(src, width, height, x0, y0)
                val v01 = sampleClamped(src, width, height, x0 + 1, y0)
                val v10 = sampleClamped(src, width, height, x0, y0 + 1)
                val v11 = sampleClamped(src, width, height, x0 + 1, y0 + 1)
                val top = v00 + (v01 - v00) * wx
                val bottom = v10 + (v11 - v10) * wx
                val value = top + (bottom - top) * wy
                out[dy * dstWidth + dx] = WorldMapTypes.lround(value).coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** BGR 双线性缩放（三通道各自插值）。 */
    fun resizeBgrBilinear(src: ByteArray, width: Int, height: Int, dstWidth: Int, dstHeight: Int): ByteArray? {
        if (width <= 0 || height <= 0 || dstWidth <= 0 || dstHeight <= 0) return null
        if (src.size != width * height * BGR_CHANNELS) return null
        val out = ByteArray(dstWidth * dstHeight * BGR_CHANNELS)
        val scaleX = width.toDouble() / dstWidth
        val scaleY = height.toDouble() / dstHeight
        for (dy in 0 until dstHeight) {
            val fy = (dy + 0.5) * scaleY - 0.5
            val y0 = floor(fy).toInt()
            val wy = fy - y0
            for (dx in 0 until dstWidth) {
                val fx = (dx + 0.5) * scaleX - 0.5
                val x0 = floor(fx).toInt()
                val wx = fx - x0
                val oi = (dy * dstWidth + dx) * BGR_CHANNELS
                for (c in 0 until BGR_CHANNELS) {
                    val v00 = sampleClampedBgr(src, width, height, x0, y0, c)
                    val v01 = sampleClampedBgr(src, width, height, x0 + 1, y0, c)
                    val v10 = sampleClampedBgr(src, width, height, x0, y0 + 1, c)
                    val v11 = sampleClampedBgr(src, width, height, x0 + 1, y0 + 1, c)
                    val top = v00 + (v01 - v00) * wx
                    val bottom = v10 + (v11 - v10) * wx
                    val value = top + (bottom - top) * wy
                    out[oi + c] = WorldMapTypes.lround(value).coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /** 单通道灰度复制成 BGR（三通道同值），喂框架 `MaaImageBufferSetRawData(CV_8UC3)`。 */
    fun grayToBgr(gray: ByteArray): ByteArray {
        val out = ByteArray(gray.size * BGR_CHANNELS)
        var oi = 0
        for (b in gray) {
            out[oi] = b
            out[oi + 1] = b
            out[oi + 2] = b
            oi += BGR_CHANNELS
        }
        return out
    }

    /**
     * 从灰度整图裁 `[x, y, w, h]`（越界返回 null）；供细解阶段裁底图窗口。
     */
    fun cropGray(src: ByteArray, srcWidth: Int, srcHeight: Int, x: Int, y: Int, w: Int, h: Int): ByteArray? {
        if (srcWidth <= 0 || srcHeight <= 0 || w <= 0 || h <= 0) return null
        if (src.size != srcWidth * srcHeight) return null
        if (x < 0 || y < 0 || x + w > srcWidth || y + h > srcHeight) return null
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(src, (y + row) * srcWidth + x, out, row * w, w)
        }
        return out
    }

    private fun sampleClamped(src: ByteArray, width: Int, height: Int, x: Int, y: Int): Double {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return (src[cy * width + cx].toInt() and 0xFF).toDouble()
    }

    private fun sampleClampedBgr(src: ByteArray, width: Int, height: Int, x: Int, y: Int, channel: Int): Double {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return (src[(cy * width + cx) * BGR_CHANNELS + channel].toInt() and 0xFF).toDouble()
    }
}
