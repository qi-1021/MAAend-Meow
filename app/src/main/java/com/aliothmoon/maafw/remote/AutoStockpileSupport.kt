package com.aliothmoon.maafw.remote

import kotlin.math.hypot

/**
 * 自动囤货决策支持。
 *
 * 上游 Go 版把配额、阈值、至少买一个、滑动定量都算在 agent 里；移动端没有 agent，
 * 只保留「认货 → 选货 → 点货 → 校正价格 → 买」这条主链能自己跑通的部分：
 * 货物表来自 autostockpile/item_map.json，识别走 OCR，决策退化为「本页可见货组里挑最便宜的」。
 *
 * 识别区固定取上游的 goods ROI（1280×720 坐标系），价格按上游 bindPriceToOCRGoods 的
 * 几何规则绑到商品名上：价格在名的右上方，距离不超过 120px，一个价格只绑一次。
 */
object AutoStockpileSupport {
    /** 上游 resolveGoodsRecognitionROI 的基准 ROI，找不到市场标记时就用它 */
    val goodsRoi = intArrayOf(63, 162, 1177, 553)

    /** 上游 maxGoodsPriceDistance */
    private const val MAX_PRICE_DISTANCE = 120

    data class ItemDef(val name: String, val region: String, val productId: String, val tier: Int)

    /** 24 个货组，值同 item_map.json 的 `Region/ProductId.TierN` */
    private val itemDefs: List<ItemDef> = listOf(
        ItemDef("岳研避瘴茶货组", "Wuling", "EurekaAnti-smogTincture", 1),
        ItemDef("冬虫夏草货组", "Wuling", "Nymphsprout", 1),
        ItemDef("武陵冻梨货组", "Wuling", "WulingFrozenPears", 1),
        ItemDef("武侠电影货组", "Wuling", "WuxiaMovies", 1),
        ItemDef("天师龙泡泡货组", "Wuling", "TianshChubbyLung", 2),
        ItemDef("息壤净水芯货组", "Wuling", "XiraniteFilterCores", 2),
        ItemDef("清波筏货组", "Wuling", "QingboRafts", 2),
        ItemDef("息壤色烟花货组", "Wuling", "XiranHueFireworks", 2),
        ItemDef("飞天迎宾员货组", "Wuling", "AerialReceptionists", 2),
        ItemDef("选剑铸炉货组", "Wuling", "SwordmancerForge", 2),
        ItemDef("息壤桥梁货组", "Wuling", "XiraniteBridge", 2),
        ItemDef("界石锁货组", "Wuling", "MarkerStoneLock", 2),
        ItemDef("锚点厨具货组", "ValleyIV", "AnkhorillingKitchenware", 1),
        ItemDef("悬空兽骨雕货组", "ValleyIV", "MusbeastScrimshawDangles", 1),
        ItemDef("巫木矿钻货组", "ValleyIV", "WitchcraftMiningDrill", 1),
        ItemDef("天使罐头货组", "ValleyIV", "AggeloiWarTins", 1),
        ItemDef("谷地水培肉货组", "ValleyIV", "ValleyHydrocultureFillets", 2),
        ItemDef("团结牌口服液货组", "ValleyIV", "UnitySyrup", 2),
        ItemDef("塞什卡髀石货组", "ValleyIV", "Seš'qamamKnucklebones", 2),
        ItemDef("星体晶块货组", "ValleyIV", "AstarronCrystals", 2),
        ItemDef("源石树幼苗货组", "ValleyIV", "OriginiumSaplings", 3),
        ItemDef("警戒者矿镐货组", "ValleyIV", "VigilantPickaxes", 3),
        ItemDef("硬脑壳头盔货组", "ValleyIV", "HardNogginHelmets", 3),
        ItemDef("边角料积木货组", "ValleyIV", "ScrapToyBlocks", 3),
    )

    private val byName: Map<String, ItemDef> = itemDefs.associateBy { it.name }

    fun itemDef(name: String): ItemDef? = byName[name]

    fun itemDefsFor(region: String): List<ItemDef> = itemDefs.filter { it.region == region }

    data class Candidate(
        val name: String,
        val productId: String,
        val tier: Int,
        val price: Int?,
        val box: IntArray,
    )

    /** 上游 matchGoodsName 的阈值：goods_scan.go 里按 2 传 */
    private const val MAX_NAME_DISTANCE = 2

    /** 价格合理区间：超出就当没读到价（单位是信用点，货卡上都是几万到几十万） */
    private const val MIN_PRICE = 1
    private const val MAX_PRICE = 10_000_000

    /**
     * 货卡上的价格不止一种写法，实测同屏里就有 `40万`、`24000`、`12万`。
     * 上游的 `^(\d{3,4})$` 只吃得住纯 3~4 位数字，所以 `40万` 和 `24000` 都会被丢掉，
     * 那样「挑最便宜的」其实退化成「挑最靠上的」。这里放宽成：整串就是一个数
     * （可带一位小数与 万/萬/亿 后缀）。
     *
     * 收紧的地方是**必须整串都是价格**：货卡上还有 `库存 12`、`5小时`、`-50%`，
     * 只抽数字会把 12 当成价格、然后被「最便宜」选中。带位数门槛 + 整串匹配后，
     * 这类文本进不来。
     */
    fun parsePrice(text: String): Int? {
        val t = text.trim().replace(" ", "").replace(",", "")
        if (t.isEmpty()) return null
        val m = priceTextRe.matchEntire(t) ?: return null
        val number = m.groupValues[1]
        val unit = m.groupValues[2]
        val digits = number.filter { it.isDigit() }.length
        val multiplier = when (unit) {
            "万", "萬" -> 10_000
            "亿" -> 100_000_000
            else -> 1
        }
        // 纯数字要 3 位起；带 万/亿 时 2 位就认（40万 是常见写法）
        if (multiplier == 1 && digits < 3) return null
        if (multiplier != 1 && digits < 2) return null
        val base = number.toDoubleOrNull() ?: return null
        val value = kotlin.math.round(base * multiplier)
        if (value < MIN_PRICE || value > MAX_PRICE) return null
        return value.toInt()
    }

    /** 整串价格：数字（可带一位小数）+ 可选 万/萬/亿 */
    private val priceTextRe = Regex("^(\\d+(?:\\.\\d)?)([万萬亿]?)$")

    /** 货组名匹配：先走子串（整名或 OCR 掉了尾字），再退回上游的编辑距离 ≤2 */
    fun matchName(text: String, defs: List<ItemDef>): ItemDef? {
        if (text.isBlank()) return null
        for (d in defs) {
            if (text.contains(d.name) || d.name.contains(text)) return d
        }
        var best: ItemDef? = null
        var bestDistance = MAX_NAME_DISTANCE + 1
        for (d in defs) {
            val dist = levenshtein(text, d.name)
            if (dist < bestDistance) {
                bestDistance = dist
                best = d
            }
        }
        return if (bestDistance <= MAX_NAME_DISTANCE) best else null
    }

    /** 编辑距离（上游 pkg/levenshtein 的等价实现，滚动两行） */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur.copyOf()
        }
        return prev[b.length]
    }

    /**
     * 把一帧 OCR 结果解析成本区可见的货组候选。
     * 名字命中 [itemDefsFor] 里的任一货组即算候选，价格按上游几何规则就近绑到名字上。
     */
    fun scan(items: List<GoodsSupport.OcrItem>, region: String): List<Candidate> {
        val defs = itemDefsFor(region)
        if (defs.isEmpty()) return emptyList()
        val names = items.filter { it.box != null }.sortedWith(
            compareBy({ it.box!![1] }, { it.box!![0] })
        )
        val prices = items.mapNotNull { o ->
            val b = o.box ?: return@mapNotNull null
            parsePrice(o.text)?.let { b to it }
        }
        val usedPrice = BooleanArray(prices.size)
        val out = mutableListOf<Candidate>()
        val seenNames = mutableSetOf<String>()
        for (o in names) {
            val box = o.box!!
            val def = matchName(o.text, defs) ?: continue
            // 两遍 OCR 合并后同一个货组可能出现两次（框差几像素），只留最靠上的那个
            if (!seenNames.add(def.name)) continue
            val price = bindPrice(box, prices, usedPrice)
            out += Candidate(def.name, def.productId, def.tier, price, box)
        }
        return out
    }

    /** 上游 bindPriceToOCRGoods：价格在名的右上方，取欧氏距离最近且未被占用的一条 */
    private fun bindPrice(nameBox: IntArray, prices: List<Pair<IntArray, Int>>, used: BooleanArray): Int? {
        var bestIdx = -1
        var bestDistance = Double.MAX_VALUE
        for ((idx, p) in prices.withIndex()) {
            if (used[idx]) continue
            val (box, _) = p
            if (box[1] >= nameBox[1]) continue
            if (box[0] <= nameBox[0]) continue
            val distance = hypot((nameBox[1] - box[1]).toDouble(), (box[0] - nameBox[0]).toDouble())
            if (distance > MAX_PRICE_DISTANCE) continue
            if (distance < bestDistance) {
                bestDistance = distance
                bestIdx = idx
            }
        }
        if (bestIdx < 0) return null
        used[bestIdx] = true
        return prices[bestIdx].second
    }

    /**
     * 选货：价低者优先；同价取 tier 高的（Tier3 更难得），再同价同 tier 取最靠上的
     * （阅读顺序）。没有价格的候选排在有价格的之后，避免拿没看清价格的当首选。
     */
    fun pick(candidates: List<Candidate>, tried: Set<String>): Candidate? =
        candidates.filter { it.name !in tried }.minWithOrNull(
            compareBy<Candidate> { it.price ?: Int.MAX_VALUE }
                .thenByDescending { it.tier }
                .thenBy { it.box[1] }
        )

    /**
     * 一次囤货任务里的决策状态。
     *
     * 流水线没有「任务开始」钩子可用，所以按 region 变化重新开局：节点名是
     * AutoStockpileDecisionWuling / AutoStockpileDecisionValleyIV，
     * 同一个 region 连续多轮（价格校正重试）沿用同一份 tried。
     */
    object Session {
        private var region: String? = null
        private var decisionNode: String? = null
        private val tried = mutableSetOf<String>()
        private var selection: Candidate? = null

        /**
         * 开一轮决策并直接给出选中项；region 变了就重开 tried，同 region 的重试沿用
         */
        @Synchronized
        fun decide(region: String, decisionNode: String, candidates: List<Candidate>): Candidate? {
            if (this.region != region) {
                this.region = region
                tried.clear()
            }
            this.decisionNode = decisionNode
            return pick(candidates, tried)
        }

        @Synchronized
        fun decisionNode(): String? = decisionNode

        @Synchronized
        fun selection(): Candidate? = selection

        @Synchronized
        fun select(candidate: Candidate) {
            selection = candidate
        }

        /** 放弃当前选择：详情页价格不合格时用，下一轮重新 OCR 挑下一个 */
        @Synchronized
        fun reject() {
            selection?.let { tried += it.name }
            selection = null
        }

        @Synchronized
        fun triedCount(): Int = tried.size

        @Synchronized
        fun reset() {
            region = null
            decisionNode = null
            tried.clear()
            selection = null
        }
    }
}
