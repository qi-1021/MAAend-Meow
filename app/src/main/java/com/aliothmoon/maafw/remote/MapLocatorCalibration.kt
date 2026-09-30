package com.aliothmoon.maafw.remote

import kotlin.math.roundToInt

/**
 * 本设备（1280×720）上小地图定位的**经验校准层**。
 *
 * ## 为什么单独一层
 *
 * 上游 `MapLocator` 把「模板中心」直接当作玩家位置（`MapLocator.cpp:501-502` /
 * `1248-1249`：`rawPos = constrainedRect + loc + scaledTemplSize/2`），这隐含假设
 * **玩家箭头正好在小地图裁剪 ROI 的正中心**。上游 `InferYellowArrowRotation`
 * （`MapAlgorithm.cpp:143-160`）也把取样半径限制在中心 ±12px，进一步印证这一假设。
 *
 * 真机（终末地 Android 原生控制器，1280×720）实测该假设**不成立**：玩家白箭头/金色
 * 环相对 `DEFAULT_MINIMAP_ROI=(49,51,118,120)` 的中心稳定偏 **约 (+25,+19)px**。
 * 实测方法：在 on_error 真机帧上对 roi 裁剪做「金色掩膜（箭头环）+ 附近白色像素」
 * 的质心，13 帧里偏移稳定落在 (+23..+30, +16..+23)，均值 ≈ (+26.5,+19.6)；换成
 * 箭头朝北（Route1）与朝东（Route3）两帧，偏移未随朝向翻转，判定为**位置偏移**
 * 而非箭头形状/朝向导致的质心偏移（形状只贡献 ±4px）。
 * 另有独立佐证：`AutoCollectRoute3AssertLocation`（target=[706,1893,20,20]，真值中心
 * (716,1903)）无修正时报告 (691.5,1876.5)，误差 (24.5,26.5)≈该偏移。
 *
 * 因此这里把「匹配到的模板中心」修正到「玩家标记中心」。它**不改变匹配本身**，
 * 只在输出坐标上加一个常量偏移，是最小、可回退的改动。
 *
 * ## 与模板缩放的关系
 *
 * 匹配前模板会按 [zoneTemplateScale] 缩放（上游 `GlobalSearchBatch` 的
 * `templateScale`）。缩放后模板的 1px 对应 1 个地图 px，裁剪图上的偏移 `d_c` 在
 * 匹配坐标系（地图 px）里变成 `d_c * scale`。
 *
 * 本文件不依赖 Android / JNA / 文件系统，可在 [scripts/verify_pure_logic.sh] 本机回归。
 */
object MapLocatorCalibration {

    /**
     * 玩家箭头质心相对小地图 ROI 中心的偏移（**裁剪图像素**，向右下为正）。
     *
     * 标定数据见类注释。四舍五入到整数：真机复测若发现残差仍偏，先改这里再复跑
     * `assert_location` 的 `position`/`target` 误差。
     */
    const val PLAYER_MARKER_OFFSET_X = 25.0
    const val PLAYER_MARKER_OFFSET_Y = 19.0

    /**
     * 把「模板中心」位置修正为「玩家标记中心」位置。
     *
     * @param templateCenter 匹配到的模板中心（地图坐标）。
     * @param templateScale 该 zone 的模板缩放（[zoneTemplateScale]）；≤0 时原样返回。
     */
    fun toPlayerPosition(
        templateCenter: MapPosition,
        templateScale: Double = 1.0,
        offsetX: Double = PLAYER_MARKER_OFFSET_X,
        offsetY: Double = PLAYER_MARKER_OFFSET_Y,
    ): MapPosition {
        if (templateScale <= 0.0) return templateCenter
        return templateCenter.copy(
            x = templateCenter.x + offsetX * templateScale,
            y = templateCenter.y + offsetY * templateScale,
        )
    }

    /**
     * 缩放后的模板尺寸，对齐 OpenCV `cv::resize(src, dst, Size(), fx, fy)`：
     * `dst = cvRound(src * factor)`（四舍五入），下限 1。
     */
    fun scaledSize(size: Int, factor: Double): Int = when {
        size <= 0 -> 0
        factor <= 0.0 -> 0
        else -> kotlin.math.max(1, (size * factor).roundToInt())
    }

    /**
     * 单通道图的双线性缩放（近似 OpenCV `INTER_LINEAR`，边界按最近邻 clamp）。
     *
     * 只服务于 `ZoneTemplateScale`（最大 6% 的整数倍缩放），不追求与 OpenCV 逐位一致；
     * 长度不符 / 尺寸非法返回 null。
     */
    fun scaleGray(src: ByteArray, width: Int, height: Int, factor: Double): ByteArray? {
        if (width <= 0 || height <= 0 || factor <= 0.0) return null
        if (src.size != width * height) return null
        if (kotlin.math.abs(factor - 1.0) < 1e-9) return src.copyOf()
        val dw = scaledSize(width, factor)
        val dh = scaledSize(height, factor)
        if (dw <= 0 || dh <= 0) return null
        val out = ByteArray(dw * dh)
        for (dy in 0 until dh) {
            val sy = ((dy + 0.5) / factor - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = sy.toInt()
            val y1 = kotlin.math.min(y0 + 1, height - 1)
            val fy = sy - y0
            for (dx in 0 until dw) {
                val sx = ((dx + 0.5) / factor - 0.5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = sx.toInt()
                val x1 = kotlin.math.min(x0 + 1, width - 1)
                val fx = sx - x0
                val v00 = src[y0 * width + x0].toInt() and 0xFF
                val v01 = src[y0 * width + x1].toInt() and 0xFF
                val v10 = src[y1 * width + x0].toInt() and 0xFF
                val v11 = src[y1 * width + x1].toInt() and 0xFF
                val top = v00 + (v01 - v00) * fx
                val bot = v10 + (v11 - v10) * fx
                val v = top + (bot - top) * fy
                out[dy * dw + dx] = (v + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** 三通道 BGR 交错图的双线性缩放（近似 OpenCV `INTER_LINEAR`）。 */
    fun scaleBgr(src: ByteArray, width: Int, height: Int, factor: Double): ByteArray? {
        if (width <= 0 || height <= 0 || factor <= 0.0) return null
        if (src.size != width * height * 3) return null
        if (kotlin.math.abs(factor - 1.0) < 1e-9) return src.copyOf()
        val dw = scaledSize(width, factor)
        val dh = scaledSize(height, factor)
        if (dw <= 0 || dh <= 0) return null
        val out = ByteArray(dw * dh * 3)
        for (dy in 0 until dh) {
            val sy = ((dy + 0.5) / factor - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = sy.toInt()
            val y1 = kotlin.math.min(y0 + 1, height - 1)
            val fy = sy - y0
            for (dx in 0 until dw) {
                val sx = ((dx + 0.5) / factor - 0.5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = sx.toInt()
                val x1 = kotlin.math.min(x0 + 1, width - 1)
                val fx = sx - x0
                val o = (dy * dw + dx) * 3
                for (c in 0 until 3) {
                    val v00 = src[(y0 * width + x0) * 3 + c].toInt() and 0xFF
                    val v01 = src[(y0 * width + x1) * 3 + c].toInt() and 0xFF
                    val v10 = src[(y1 * width + x0) * 3 + c].toInt() and 0xFF
                    val v11 = src[(y1 * width + x1) * 3 + c].toInt() and 0xFF
                    val top = v00 + (v01 - v00) * fx
                    val bot = v10 + (v11 - v10) * fx
                    val v = top + (bot - top) * fy
                    out[o + c] = (v + 0.5).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    /**
     * 布尔掩膜的**最近邻**缩放（对齐 OpenCV `INTER_NEAREST`）：
     * 目标像素取 `floor((d+0.5)/factor)` 的源像素。
     */
    fun scaleMask(src: BooleanArray, width: Int, height: Int, factor: Double): BooleanArray? {
        if (width <= 0 || height <= 0 || factor <= 0.0) return null
        if (src.size != width * height) return null
        if (kotlin.math.abs(factor - 1.0) < 1e-9) return src.copyOf()
        val dw = scaledSize(width, factor)
        val dh = scaledSize(height, factor)
        if (dw <= 0 || dh <= 0) return null
        val out = BooleanArray(dw * dh)
        for (dy in 0 until dh) {
            val sy = kotlin.math.min((dy / factor).toInt(), height - 1)
            for (dx in 0 until dw) {
                val sx = kotlin.math.min((dx / factor).toInt(), width - 1)
                out[dy * dw + dx] = src[sy * width + sx]
            }
        }
        return out
    }
}
