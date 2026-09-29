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
     *
     * **三个可变参数一律显式写出**——这是所有 OCR 探针**共用的同一个节点名**，
     * 而 `MaaContextOverridePipeline` 会保留上次写入的定义：**本次没写的键会继承上一次的值**。
     *
     * 真机实测（2026-09-29）：山谷货卡读完之后、买货环节用 `onlyRec=true` 读了一次单价，
     * 于是共享节点被写成 `only_rec=true`；随后武陵货卡的 override **没写** `only_rec`，
     * 直接继承了 true → 整个 ROI 退化成一行文字 → 读空（更早一次表现为一个大框乱码）。
     * 这是跨调用的状态泄漏，只在真机上以"换了张图就突然读不到"的形式暴露。
     *
     * 所以：roi 无效时显式写 `[0,0,0,0]`（全屏，与框架默认一致）；
     * color_filter 为空时显式写 `""`（清掉上一次的颜色过滤）。
     */
    fun buildOverride(roi: IntArray?, onlyRec: Boolean, colorFilter: String? = null): String {
        val r = if (roi != null && roi.size >= 4 && roi[2] > 0 && roi[3] > 0) {
            roi
        } else {
            intArrayOf(0, 0, 0, 0)
        }
        val sb = StringBuilder(96)
        sb.append("{\"").append(NODE).append("\":{\"recognition\":\"OCR\"")
            .append(",\"roi\":[")
            .append(r[0]).append(',').append(r[1]).append(',')
            .append(r[2]).append(',').append(r[3]).append(']')
            .append(",\"only_rec\":").append(onlyRec)
            .append(",\"color_filter\":\"").append(colorFilter.orEmpty()).append('"')
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

    /**
     * 货卡探针第 [attempt] 次尝试（1-based，对应 `goodsOcrProbe` 的外层循环）该不该用
     * 框架回调给的 `image`，而不是自己重新截屏。
     *
     * 框架帧就是框架本次识别实际用的那一帧——用它做第一次 OCR，能保证「OCR 看到的就是
     * 框架看到的」，从根上消掉「自抓帧与框架帧不一致」造成的假失败（真机武陵：框架 on_error
     * 截图完全正常，探针自抓帧却稳定读出 `2そ22` 三连、跨三次取帧逐字一致）。
     *
     * 但**只有第一次尝试**能用它：
     *  - 后续重试的目的是「换一帧等画面进场」，复用同一张框架帧等于不换帧，重试失去意义；
     *  - 帧为空（`hasFrameworkFrame=false`，例如未来从别处调用）时无框架帧可用，只能自抓。
     */
    fun shouldUseFrameworkFrame(attempt: Int, hasFrameworkFrame: Boolean): Boolean =
        attempt == 1 && hasFrameworkFrame

    /**
     * 一次探针内部第 [attemptIndex] 轮（0-based，对应 `cachedOcrProbe` 的坏帧重试）该不该用
     * 外部传入的首选帧（框架帧）。
     *
     * 与 [shouldUseFrameworkFrame] 同一原则：首选帧只用于第一轮，之后是「坏帧重试」，
     * 必须自己重新取帧。
     */
    fun shouldUsePreferredFrame(attemptIndex: Int, hasPreferredFrame: Boolean): Boolean =
        attemptIndex == 0 && hasPreferredFrame
}
