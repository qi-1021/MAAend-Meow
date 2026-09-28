package com.aliothmoon.maafw.remote

/**
 * 临时 OCR 探针（`__GoodsOcrProbe`）的纯逻辑：OverridePipeline JSON 构造 + 坏帧判定。
 *
 * 从 MaaRunner 抽出来是为了可测：真机确认过「多条目画面 + only_rec=true」会退化成
 * 整个 ROI 一行文字、只回一个大框乱码（实测一个 700×430 框里只有 0.21 分的「注」，
 * `best_result_=null`）。这类静默失准只有上设备才暴露，所以生成参数与坏帧判定必须能单测。
 *
 * 关于 only_rec：语义是「只识别、不做文字检测」，把整个 ROI 当成一行文字。上游只在
 * bettersliding 的滑块数量 / 可用数量两个**单个数值** OCR 上开它（`overrides.go:119/143`），
 * 货卡网格（AutoStockpileGetGoods）从不使用。对齐该原则：
 *  - **单个数值**（价格、数量、倒计时数字…）→ [buildOverride] 传 onlyRec=true；
 *  - **多条目网格/列表** → onlyRec=false；否则检测阶段被打掉，只剩一个大框。
 */
object OcrProbeSupport {
    /** 临时探针节点名，与 MaaRunner 里 Override 的目标一致。 */
    const val NODE = "__GoodsOcrProbe"

    /**
     * 构造 `{"__GoodsOcrProbe":{"recognition":"OCR",...}}`。
     * ROI 宽或高非正时省略（与旧实现一致，避免写出无效 roi）。
     */
    fun buildOverride(roi: IntArray?, onlyRec: Boolean, colorFilter: String? = null): String {
        val sb = StringBuilder(64)
        sb.append("{\"").append(NODE).append("\":{\"recognition\":\"OCR\"")
        if (roi != null && roi.size >= 4 && roi[2] > 0 && roi[3] > 0) {
            sb.append(",\"roi\":[")
                .append(roi[0]).append(',').append(roi[1]).append(',')
                .append(roi[2]).append(',').append(roi[3]).append(']')
        }
        if (!colorFilter.isNullOrBlank()) {
            sb.append(",\"color_filter\":\"").append(colorFilter).append('"')
        }
        if (onlyRec) sb.append(",\"only_rec\":true")
        sb.append("}}")
        return sb.toString()
    }

    /**
     * 这次 OCR 是不是「坏帧 / 整帧噪声」，值得刷一帧重试。
     *
     * [hit] 必须单独传：MaaFramework 的 `best_result_=null`（所有候选都低于阈值）时
     * hit=false，detail 里只剩 `all_results_` 的原始候选。多条目 OCR 一条都不过阈值，
     * 本身就说明这帧没读出来——旧的实现只看 collect 出来的 items 并提前 return，
     * 会把「hit=false 但 all_results_ 带一条大框噪声」当成正常结果，坏帧防御失效。
     *
     * 其余启发式沿用旧实现：
     *  - 空结果可疑；
     *  - 单条且框盖满全帧（≥1200×640）可疑；
     *  - 只剩 ≤4 个单字（颜色过滤失效时整屏回 ['手','手','手']）可疑；
     *  - 2~4 条**完全相同的短文本**可疑：真机武陵首帧回 ['2そ22','2そ22','2そ22']，
     *    正常的货卡网格是多条不同的名字+价格，≤4 条一模一样基本只可能是帧没渲染好
     *    或颜色过滤失效。只在 2 条及以上判定：单条合法数值（only_rec 价格/数量）走上面的
     *    单条分支，不会被误伤。
     */
    fun isSuspicious(hit: Boolean, items: List<GoodsSupport.OcrItem>): Boolean {
        if (!hit) return true
        if (items.isEmpty()) return true
        if (items.size == 1) {
            val b = items[0].box ?: return true
            return b.size >= 4 && b[2] >= 1200 && b[3] >= 640
        }
        // 全是单字：颜色过滤失效 / 帧上真的没有可读文字
        if (items.size <= 4 && items.all { it.text.length == 1 }) return true
        // 2~4 条完全相同：帧没渲染好 / 颜色过滤失效（武陵首帧的 2そ22 三连）
        if (items.size in 2..4 && items.map { it.text }.distinct().size == 1) return true
        return false
    }
}
