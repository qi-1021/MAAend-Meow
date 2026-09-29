package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MapLocator 路线 (b+) 的**前提验证纯逻辑**：合成一张图、从已知位置裁一块当模板、
 * 算出期望框、判定框架返回框是否落在期望位置附近，并构造 `TemplateMatch` 节点覆盖 JSON。
 *
 * 为什么抽成纯逻辑：这条链的**真机部分**（`MaaImageBufferSetRawData` →
 * `MaaContextOverrideImage` → `MaaContextRunRecognition(TemplateMatch)`）本机编不了、跑不了，
 * 只能交给 CI/真机；但「裁的是不是那个位置、期望框对不对、覆盖 JSON 字段对不对」这些
 * 是可以脱离 Android/JNA 断言的。把可测的部分钉死，真机失败时就能快速区分
 * 「逻辑错」还是「框架前提不成立（覆盖模板没被识别读到）」。
 *
 * 设计成**合成图**而不是读资源 PNG：无需游戏、无需补充包、无需 assets，一张
 * 确定性伪随机噪声图 + 已知裁剪点即可让 `TM_CCOEFF_NORMED` 唯一命中。
 *
 * 数据布局：MaaImageBuffer 内部按 **BGR 三通道 8 位**（`CV_8UC3=16`）存裸像素，
 * 与上游 Go 绑定 `buffer/image_buffer.go` 的 `Set()` 一致；本文件所有像素数组都按 BGR 交错。
 */
object MapLocatorProbeSupport {

    /** OpenCV `CV_8UC3 = CV_MAKETYPE(CV_8U, 3)`。与 [com.aliothmoon.maafw.maa.MaaImageType.CV_8UC3] 必须一致。 */
    const val CV_8UC3 = 16

    /** BGR 交错，每像素 3 字节。 */
    const val BGR_CHANNELS = 3

    /** 运行时模板名：`MaaContextOverrideImage` 写它、`TemplateMatch.template` 读它。 */
    const val DEFAULT_TEMPLATE_NAME = "__MapLocatorProbe/patch.png"

    /** 临时 `TemplateMatch` 节点名（只在 pipeline_override 里定义，不落资源）。 */
    const val DEFAULT_PROBE_NODE = "__MapLocatorProbeTemplateMatch"

    /** `cv::TemplateMatchMatchingMethod`：TM_CCOEFF_NORMED = 5（框架 method 取值）。 */
    const val DEFAULT_METHOD = 5

    /** 框架 TemplateMatch 默认阈值；裁剪精确时应接近 1.0。 */
    const val DEFAULT_THRESHOLD = 0.8

    /** 合成噪声模板无需绿幕掩膜。 */
    const val DEFAULT_GREEN_MASK = false

    /** 允许返回框与裁剪位置的像素偏差（模板匹配的取整/亚像素抖动）。 */
    const val DEFAULT_TOLERANCE = 2

    /** 合成图默认尺寸与裁剪点；裁剪块完整落在图内且不贴边（避免边界效应）。 */
    const val FULL_WIDTH = 320
    const val FULL_HEIGHT = 240
    const val PATCH_X = 137
    const val PATCH_Y = 88
    const val PATCH_W = 48
    const val PATCH_H = 40

    /** 确定性种子，保证同一构建每次合成同一张图。 */
    const val SYNTH_SEED = 0x4D41504CL

    /**
     * 一次探针所需的全部输入：合成图、裁剪出的模板、期望框与名字。
     *
     * [fullBgr] 与 [patchBgr] 都是 BGR 交错裸像素；[expectedBox] 即模板匹配应命中的位置。
     */
    data class SyntheticPlan(
        val fullWidth: Int,
        val fullHeight: Int,
        val patchX: Int,
        val patchY: Int,
        val patchW: Int,
        val patchH: Int,
        val fullBgr: ByteArray,
        val patchBgr: ByteArray,
        val templateName: String = DEFAULT_TEMPLATE_NAME,
        val probeNode: String = DEFAULT_PROBE_NODE,
    ) {
        /** `[x, y, w, h]`，模板匹配返回框应落在这附近。 */
        val expectedBox: IntArray get() = intArrayOf(patchX, patchY, patchW, patchH)
    }

    /**
     * 生成确定性伪随机 BGR 噪声图（LCG，取高位）。噪声方差大，
     * `TM_CCOEFF_NORMED` 在正确位置得到接近 1.0 的唯一峰值，别处接近 0。
     */
    fun synthesizeBgr(width: Int, height: Int, seed: Long = SYNTH_SEED): ByteArray {
        require(width > 0 && height > 0) { "width/height must be positive: ${width}x$height" }
        val out = ByteArray(width * height * BGR_CHANNELS)
        var state = seed
        for (i in out.indices) {
            state = state * 6364136223846793005L + 1442695040888963407L
            out[i] = ((state ushr 33).toInt() and 0xFF).toByte()
        }
        return out
    }

    /**
     * 从 BGR 整图裁出 `[x, y, w, h]`，按行拷贝成新的连续 BGR 数组。
     *
     * 越界、非正尺寸、或 [full] 长度与 [fullWidth]×[fullHeight]×3 不符时返回 null——
     * 调用方据此判定「探针输入非法」，而不是让 native 侧拿到错长内存。
     */
    fun cropBgr(
        full: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): ByteArray? {
        if (fullWidth <= 0 || fullHeight <= 0 || w <= 0 || h <= 0) return null
        if (x < 0 || y < 0 || x + w > fullWidth || y + h > fullHeight) return null
        if (full.size != fullWidth * fullHeight * BGR_CHANNELS) return null
        val rowBytes = w * BGR_CHANNELS
        val out = ByteArray(rowBytes * h)
        for (row in 0 until h) {
            val srcOffset = ((y + row) * fullWidth + x) * BGR_CHANNELS
            System.arraycopy(full, srcOffset, out, row * rowBytes, rowBytes)
        }
        return out
    }

    /** 按默认参数合成一张图并裁出模板；区域非法时返回 null。 */
    fun syntheticPlan(
        fullWidth: Int = FULL_WIDTH,
        fullHeight: Int = FULL_HEIGHT,
        patchX: Int = PATCH_X,
        patchY: Int = PATCH_Y,
        patchW: Int = PATCH_W,
        patchH: Int = PATCH_H,
        seed: Long = SYNTH_SEED,
    ): SyntheticPlan? {
        val full = synthesizeBgr(fullWidth, fullHeight, seed)
        val patch = cropBgr(full, fullWidth, fullHeight, patchX, patchY, patchW, patchH) ?: return null
        return SyntheticPlan(fullWidth, fullHeight, patchX, patchY, patchW, patchH, full, patch)
    }

    /**
     * 判定框架返回的 [actual] 是否落在 [expected] 附近：x/y/w/h 四项偏差都不超过 [tolerance]。
     *
     * [actual] 为 null 或长度不足 4 直接判 false；[tolerance] 为负按 0 处理。
     */
    fun isBoxNearExpected(
        actual: IntArray?,
        expected: IntArray,
        tolerance: Int = DEFAULT_TOLERANCE,
    ): Boolean {
        if (actual == null || actual.size < 4 || expected.size < 4) return false
        val tol = if (tolerance < 0) 0 else tolerance
        return kotlin.math.abs(actual[0] - expected[0]) <= tol &&
            kotlin.math.abs(actual[1] - expected[1]) <= tol &&
            kotlin.math.abs(actual[2] - expected[2]) <= tol &&
            kotlin.math.abs(actual[3] - expected[3]) <= tol
    }

    /** 一行人话摘要，供日志/诊断用。 */
    fun describe(expected: IntArray, actual: IntArray?, tolerance: Int): String {
        val e = expected.joinToString(",")
        val a = actual?.joinToString(",") ?: "null"
        return "expected=[$e] actual=[$a] tolerance=$tolerance"
    }

    /**
     * 构造 `MaaContextRunRecognition` 的 pipeline_override（**必须是 JSON 对象**，
     * 见 `Context::run_recognition` / Go 绑定 `context.go` 示例）。
     *
     * 结构：`{"<node>":{"recognition":{"type":"TemplateMatch","param":{...}},"action":{"type":"DoNothing"}}}`
     *  - `template` 写成**数组**（框架同时接受字符串与数组，数组更明确）；
     *  - `method`/`green_mask`/`threshold` 显式写出，避免跨调用继承旧值；
     *  - `roi` 仅在有效时写入，否则让框架默认全图。
     */
    fun buildTemplateMatchOverride(
        nodeName: String = DEFAULT_PROBE_NODE,
        templateName: String = DEFAULT_TEMPLATE_NAME,
        method: Int = DEFAULT_METHOD,
        greenMask: Boolean = DEFAULT_GREEN_MASK,
        threshold: Double = DEFAULT_THRESHOLD,
        roi: IntArray? = null,
    ): String {
        val param = buildJsonObject {
            put("template", JsonArray(listOf(JsonPrimitive(templateName))))
            put("method", method)
            put("green_mask", greenMask)
            put("threshold", threshold)
            if (roi != null && roi.size >= 4) {
                put("roi", JsonArray(roi.map { JsonPrimitive(it) }))
            }
        }
        val node = buildJsonObject {
            put(
                "recognition",
                buildJsonObject {
                    put("type", "TemplateMatch")
                    put("param", param)
                },
            )
            // 只认不按：临时节点不触发任何动作
            put("action", buildJsonObject { put("type", "DoNothing") })
        }
        return buildJsonObject { put(nodeName, node) }.toString()
    }
}
