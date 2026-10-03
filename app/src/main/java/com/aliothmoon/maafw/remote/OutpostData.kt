package com.aliothmoon.maafw.remote

/** location -> zh_cn 商品名（由 selection_data.json 生成） */
object OutpostData {
    val locationItems: Map<String, List<String>> = mapOf(
        "RefugeeCamp" to listOf("荞愈胶囊","紫晶质瓶","晶体外壳","紫晶零件","中容谷地电池","优质荞愈胶囊","柑实罐头","精选荞愈胶囊","高容谷地电池","优质柑实罐头","精选柑实罐头"),
        "InfraStation" to listOf("低容谷地电池","铁制零件","中容谷地电池","优质荞愈胶囊","精选荞愈胶囊","高容谷地电池","精选柑实罐头","优质柑实罐头","柑实罐头"),
        "ReconstructionHQ" to listOf("中容谷地电池","优质荞愈胶囊","钢制零件","精选荞愈胶囊","高容谷地电池","精选柑实罐头","优质柑实罐头","柑实罐头"),
        "SkyKingFlatsConstructionSite" to listOf("重息壤龙泡泡","息壤龙泡泡","低容武陵电池","芽针针剂","息壤","锦草软饮","赤铜零件","中容武陵电池","赫铜零件","优质芽针针剂","重息壤","优质锦草软饮"),
        "CardiacRemediationStation" to listOf("重息壤龙泡泡","息壤龙泡泡","重息壤","低容武陵电池","优质锦草软饮","优质芽针针剂","息壤","中容武陵电池","赫铜零件","灼铜零件"),
        "XiranflowCloudseederStation" to listOf("重息壤龙泡泡","息壤龙泡泡","灼铜零件","中容武陵电池","重息壤","优质锦草软饮","优质芽针针剂","分离芯","息壤"),
    )

    /** location -> zh_cn 商品基础单价（由 selection_data.json 生成，跨据点价差即套利空间） */
    val unitPriceByLocation: Map<String, Map<String, Int>> = mapOf(
        "RefugeeCamp" to mapOf("晶体外壳" to 1, "紫晶零件" to 1, "紫晶质瓶" to 2, "荞愈胶囊" to 10, "柑实罐头" to 10, "优质荞愈胶囊" to 27, "优质柑实罐头" to 27, "中容谷地电池" to 30, "精选荞愈胶囊" to 70, "高容谷地电池" to 70, "精选柑实罐头" to 70),
        "InfraStation" to mapOf("铁制零件" to 1, "柑实罐头" to 10, "低容谷地电池" to 16, "优质荞愈胶囊" to 27, "优质柑实罐头" to 27, "中容谷地电池" to 30, "精选荞愈胶囊" to 70, "高容谷地电池" to 70, "精选柑实罐头" to 70),
        "ReconstructionHQ" to mapOf("钢制零件" to 3, "柑实罐头" to 10, "优质荞愈胶囊" to 27, "优质柑实罐头" to 27, "中容谷地电池" to 30, "精选荞愈胶囊" to 70, "高容谷地电池" to 70, "精选柑实罐头" to 70),
        "SkyKingFlatsConstructionSite" to mapOf("息壤" to 1, "赤铜零件" to 1, "芽针针剂" to 16, "锦草软饮" to 16, "优质芽针针剂" to 22, "优质锦草软饮" to 22, "低容武陵电池" to 25, "重息壤" to 27, "赫铜零件" to 48, "中容武陵电池" to 54, "息壤龙泡泡" to 100, "重息壤龙泡泡" to 200),
        "CardiacRemediationStation" to mapOf("息壤" to 1, "优质锦草软饮" to 22, "优质芽针针剂" to 22, "低容武陵电池" to 25, "重息壤" to 27, "赫铜零件" to 48, "中容武陵电池" to 54, "灼铜零件" to 70, "息壤龙泡泡" to 100, "重息壤龙泡泡" to 200),
        "XiranflowCloudseederStation" to mapOf("分离芯" to 1, "息壤" to 1, "优质锦草软饮" to 22, "优质芽针针剂" to 22, "重息壤" to 27, "中容武陵电池" to 54, "灼铜零件" to 70, "息壤龙泡泡" to 100, "重息壤龙泡泡" to 200),
    )

    /**
     * item_id -> zh_cn 商品名（由 selection_data.json 生成）。
     *
     * 据点交易的 pipeline 用 `item_id`（如 `item_bottled_rec_hp_3`）表达保留/优先规则，
     * 而移动端识别只认 zh_cn 商品名；这层映射是两者唯一的桥。数据与
     * [locationItems] / [unitPriceByLocation] 同源，按 item_id 排序以便审阅。
     */
    val nameByItemId: Map<String, String> = mapOf(
        "item_activity_xiranite_enr_hulu" to "息壤玉葫芦",
        "item_activity_xiranite_enr_lung" to "重息壤龙泡泡",
        "item_activity_xiranite_hulu" to "息壤葫芦",
        "item_activity_xiranite_lung" to "息壤龙泡泡",
        "item_bottled_food_1" to "柑实罐头",
        "item_bottled_food_2" to "优质柑实罐头",
        "item_bottled_food_3" to "精选柑实罐头",
        "item_bottled_food_4" to "锦草软饮",
        "item_bottled_food_5" to "优质锦草软饮",
        "item_bottled_rec_hp_1" to "荞愈胶囊",
        "item_bottled_rec_hp_2" to "优质荞愈胶囊",
        "item_bottled_rec_hp_3" to "精选荞愈胶囊",
        "item_bottled_rec_hp_4" to "芽针针剂",
        "item_bottled_rec_hp_5" to "优质芽针针剂",
        "item_copper_cmpt" to "赤铜零件",
        "item_copper_enr2_cmpt" to "灼铜零件",
        "item_copper_enr_cmpt" to "赫铜零件",
        "item_crystal_shell" to "晶体外壳",
        "item_filter_core" to "分离芯",
        "item_glass_bottle" to "紫晶质瓶",
        "item_glass_cmpt" to "紫晶零件",
        "item_iron_cmpt" to "铁制零件",
        "item_iron_enr_cmpt" to "钢制零件",
        "item_proc_battery_1" to "低容谷地电池",
        "item_proc_battery_2" to "中容谷地电池",
        "item_proc_battery_3" to "高容谷地电池",
        "item_proc_battery_4" to "低容武陵电池",
        "item_proc_battery_5" to "中容武陵电池",
        "item_xiranite_enr_powder" to "重息壤",
        "item_xiranite_powder" to "息壤",
    )

    /** zh_cn 商品名 -> item_id；与 [nameByItemId] 互逆（selection_data 中 item_id 与 zh_cn 名一一对应） */
    val itemIdByName: Map<String, String> = nameByItemId.entries.associate { (id, name) -> name to id }

    /**
     * 活动限时物品的 zh_cn 名（selection_data 中带 `activity_id`）。
     *
     * 这类物品不适用「保留 N 件」语义：只按当前据点调度券额度整批卖出
     * （额度 ÷ 单价），对它们配置保留数量会被上游忽略。
     */
    val activityItemNames: Set<String> = setOf("重息壤龙泡泡", "息壤龙泡泡")

    /**
     * 据点 ID -> zh_cn 据点名（由 selection_data.json 生成）。
     */
    val locationNames: Map<String, String> = mapOf(
        "RefugeeCamp" to "难民暂居处",
        "InfraStation" to "基建前站",
        "ReconstructionHQ" to "重建指挥部",
        "SkyKingFlatsConstructionSite" to "天王坪援建点",
        "CardiacRemediationStation" to "心脏修缮站",
        "XiranflowCloudseederStation" to "盈天台建设站",
    )

    /** location ID 还原为 zh_cn 据点名；未知/空白回退 location 本身。 */
    fun nameOfLocation(location: String?): String {
        val trimmed = location?.trim().orEmpty()
        return locationNames[trimmed] ?: trimmed
    }

    /** item_id 还原为 zh_cn 名；未知/空白返回 null。 */
    fun nameOfItem(itemId: String?): String? = nameByItemId[itemId?.trim()]

    /** zh_cn 名还原为 item_id；未知/空白返回 null。 */
    fun itemIdOfName(name: String?): String? = itemIdByName[name?.trim()]

    /** 该商品名是否为活动限时物品（不适用保留规则）。 */
    fun isActivityItemName(name: String?): Boolean = name?.trim() in activityItemNames

    /**
     * 将快照 ISO-8601 时间戳格式化为本地可读时间（"yyyy-MM-dd HH:mm:ss"），
     * 空白或解析失败时返回 "未知"。对齐上游 runtimeLocalCacheUpdatedAt。
     */
    fun formatCacheTime(updatedAt: String?): String {
        if (updatedAt.isNullOrBlank()) return "未知"
        return try {
            val instant = java.time.Instant.parse(updatedAt)
            val zoneId = java.time.ZoneId.systemDefault()
            val localDt = java.time.LocalDateTime.ofInstant(instant, zoneId)
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            localDt.format(formatter)
        } catch (_: Throwable) {
            "未知"
        }
    }
}

