package com.aliothmoon.maafw.remote

/**
 * YOLO 分区分类（`cls.onnx`）的**纯逻辑预处理**：把「小地图裁剪图」居中裁/贴进 128×128
 * 黑画布，再叠直径 106 的圆形 mask。对应上游
 * `upstream/maaend/agent/cpp-algo/source/MapLocator/YoloPredictor.cpp:101-164`
 * （`YoloPredictor::predictCoarseByYOLO` 的 `OUTPUT_SIZE` / `MASK_DIAMETER` / `canvas` /
 * `cv::circle` / `cv::bitwise_and` 段）。
 *
 * ## 为什么这里**不**转 RGB / 不做 /255 / 不做 HWC→CHW
 *
 * 我们复用框架内置的 `NeuralNetworkClassify`（路线 (b+)，不引入 ONNX Runtime）。核实自
 * MaaFramework v5.14.0 `source/MaaFramework/Vision/VisionUtils.hpp` 的 `image_to_tensor`：
 *
 * ```cpp
 * cv::cvtColor(src, src, cv::COLOR_BGR2RGB);   // 框架自己做 BGR→RGB
 * cv::Mat chw = hwc_to_chw(src);               // 框架自己做 HWC→CHW
 * chw.convertTo(chw_32f, CV_32F, 1.0 / 255.0); // 框架自己做 /255
 * ```
 *
 * 和上游 `YoloPredictor.cpp:149-164` 完全一致。因此**颜色顺序必须保持 BGR**：Kotlin 交出去的
 * 像素经 `MaaImageBufferSetRawData(..., CV_8UC3)` 按 OpenCV 约定当作 BGR，再由框架转成 RGB；
 * 若这里先转成 RGB，框架会二次交换，张量就错了。所以本文件输出 BGR，并只负责裁剪 + mask。
 *
 * ## 与框架的交界
 *
 * 框架 `NeuralNetworkClassifier::classify()`（`Vision/NeuralNetworkClassifier.cpp`）会
 * `cv::resize(image_with_roi(), modelInputSize, INTER_AREA)`。我们把 128×128 图连同
 * 覆盖整图的 ROI 一起交给识别节点，模型输入也是 128×128，resize 是 1:1 无操作，
 * 不会破坏本文的预处理。
 *
 * 本文件不碰 Android / JNA / 文件系统，可在 [scripts/verify_pure_logic.sh] 本机回归。
 */
object YoloPreprocess {

    /** 上游 `OUTPUT_SIZE`（`YoloPredictor.cpp:115`）。 */
    const val OUTPUT_SIZE = 128

    /**
     * 上游 `MASK_DIAMETER`（`YoloPredictor.cpp:117`）：只取小地图中心干净视野，
     * 避开外围固定装饰/黑边干扰分类。
     */
    const val MASK_DIAMETER = 106

    /** BGR 交错，每像素 3 字节（[com.aliothmoon.maafw.maa.MaaImageType.CV_8UC3]）。 */
    const val BGR_CHANNELS = 3

    /**
     * 居中裁/贴到 128×128 黑画布，再叠圆形 mask。输入 [src] 为 **BGR** 或 **BGRA** 交错裸像素。
     *
     * 复刻 `YoloPredictor.cpp:119-147`：
     *  - 4 通道先丢 alpha（上游 `COLOR_BGRA2BGR`）；
     *  - `canvas` 置黑，图居中：`start = max(0, (128 - dim) / 2)`，`crop = min(dim, 128)`，
     *    源图取中心 `crop` 区域（`(dim - crop) / 2` 起）——即大图中心裁、小图居中贴；
     *  - `mask`：`cv::circle(center=(64,64), radius=106/2=53, 实心)`，圆外清零。
     *
     * @param channels 3（BGR）或 4（BGRA）；其它值视为非法。
     * @return 128×128×3 的 BGR 裸像素；宽度/高度非正、通道非法、或 [src] 长度不匹配时返回 null。
     */
    fun preprocessBgr(
        src: ByteArray,
        width: Int,
        height: Int,
        channels: Int = BGR_CHANNELS,
    ): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (channels != 3 && channels != 4) return null
        if (src.size != width * height * channels) return null

        val out = ByteArray(OUTPUT_SIZE * OUTPUT_SIZE * BGR_CHANNELS)
        val cropW = minOf(width, OUTPUT_SIZE)
        val cropH = minOf(height, OUTPUT_SIZE)
        val startX = maxOf(0, (OUTPUT_SIZE - width) / 2)
        val startY = maxOf(0, (OUTPUT_SIZE - height) / 2)
        val imgX = (width - cropW) / 2
        val imgY = (height - cropH) / 2

        for (row in 0 until cropH) {
            var srcIdx = (((imgY + row) * width) + imgX) * channels
            var dstIdx = ((((startY + row) * OUTPUT_SIZE) + startX) * BGR_CHANNELS)
            repeat(cropW) {
                out[dstIdx] = src[srcIdx]             // B
                out[dstIdx + 1] = src[srcIdx + 1]     // G
                out[dstIdx + 2] = src[srcIdx + 2]     // R（BGRA 的第 4 字节 alpha 被丢弃）
                srcIdx += channels
                dstIdx += BGR_CHANNELS
            }
        }

        applyCircleMask(out)
        return out
    }

    /** ARGB_8888（`0xAARRGGBB`，Android `Bitmap.getPixels` 的输出）→ 紧凑 BGR。 */
    fun argbToBgr(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * BGR_CHANNELS)
        for (i in argb.indices) {
            val px = argb[i]
            val o = i * BGR_CHANNELS
            out[o] = (px and 0xFF).toByte()          // B
            out[o + 1] = ((px ushr 8) and 0xFF).toByte()  // G
            out[o + 2] = ((px ushr 16) and 0xFF).toByte() // R；alpha 丢弃
        }
        return out
    }

    /** [argbToBgr] + [preprocessBgr] 的组合，省去调用方手动指定 3 通道。 */
    fun preprocessArgb(argb: IntArray, width: Int, height: Int): ByteArray? =
        preprocessBgr(argbToBgr(argb), width, height, BGR_CHANNELS)

    /**
     * 把 [bgr]（128×128×3）中圆形 mask 之外的像素清零，原地修改。
     *
     * 等价于上游 `processedScratch = canvasScratch & maskScratch`
     * （`YoloPredictor.cpp:145-147`）。
     */
    fun applyCircleMask(bgr: ByteArray) {
        require(bgr.size == OUTPUT_SIZE * OUTPUT_SIZE * BGR_CHANNELS) {
            "expected ${OUTPUT_SIZE * OUTPUT_SIZE * BGR_CHANNELS} bytes, got ${bgr.size}"
        }
        val mask = circleMask()
        var idx = 0
        for (i in mask.indices) {
            if (!mask[i]) {
                bgr[idx] = 0
                bgr[idx + 1] = 0
                bgr[idx + 2] = 0
            }
            idx += BGR_CHANNELS
        }
    }

    /**
     * 复刻 OpenCV `cv::circle(img, (cx,cy), radius, 255, thickness=-1)` 的实心圆栅格化。
     *
     * 取自 OpenCV 4.x `modules/imgproc/src/drawing.cpp` 的 `Circle(..., fill=true)`：
     * `circle` 在 `thickness < 0 && line_type == LINE_8 && shift == 0` 时走这个 Bresenham
     * 变体。它对每个 `dy` 画两段水平线 `[cx-dx, cx+dx]`（在 `cy±dy` 行）与
     * `[cx-dy, cx+dy]`（在 `cy±dx` 行），并用 `err/plus/minus` 整数步进更新 `dx`。
     *
     * 说明：上游参数（size=128、center=(64,64)、radius=53）下，这个算法与精确圆盘
     * `dx²+dy² <= r²` 逐像素一致（本仓库测试 `YoloPreprocessTest` 有断言）。这里仍实现
     * 原始算法而非精确圆盘，是为了在 `MASK_DIAMETER` 被改动时也保持与 OpenCV 一致。
     *
     * @return 行主序布尔数组，`true` = 圆内（保留）。
     */
    fun circleMask(
        size: Int = OUTPUT_SIZE,
        centerX: Int = OUTPUT_SIZE / 2,
        centerY: Int = OUTPUT_SIZE / 2,
        radius: Int = MASK_DIAMETER / 2,
    ): BooleanArray {
        require(size > 0) { "size must be positive: $size" }
        require(radius >= 0) { "radius must be non-negative: $radius" }
        val mask = BooleanArray(size * size)

        var dx = radius.toLong()
        var dy = 0L
        var plus = 1L
        var minus = (radius.toLong() shl 1) - 1L
        var err = 0L

        while (dx >= dy) {
            val y11 = (centerY - dy).toInt()
            val y12 = (centerY + dy).toInt()
            val y21 = (centerY - dx).toInt()
            val y22 = (centerY + dx).toInt()
            val x11 = (centerX - dx).toInt()
            val x12 = (centerX + dx).toInt()
            val x21 = (centerX - dy).toInt()
            val x22 = (centerX + dy).toInt()

            fillSpan(mask, size, y11, x11, x12)
            fillSpan(mask, size, y12, x11, x12)
            fillSpan(mask, size, y21, x21, x22)
            fillSpan(mask, size, y22, x21, x22)

            dy++
            err += plus
            plus += 2
            // C++ `mask = (err <= 0) - 1`：err<=0 → 0，否则 → -1（全 1）。
            val m = if (err <= 0) 0L else -1L
            err -= minus and m
            dx += m
            minus -= m and 2L
        }
        return mask
    }

    /** 在 [size]×[size] 上把行 [yRow] 的 `[left, right]`（含端点）置 true，越界裁剪。 */
    private fun fillSpan(mask: BooleanArray, size: Int, yRow: Int, left: Int, right: Int) {
        if (yRow < 0 || yRow >= size) return
        val l = if (left < 0) 0 else left
        val r = if (right >= size) size - 1 else right
        if (l > r) return
        val base = yRow * size
        for (x in l..r) mask[base + x] = true
    }
}
