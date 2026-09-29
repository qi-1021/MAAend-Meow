package com.aliothmoon.maafw.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 残留送货仓储解析的纯逻辑测试（对齐上游 deliveryjobs/ongoing_delivery.go + autodelivery/）。
 *
 * 重点钉住最容易写错的两处：
 *  1. 区域节点 ID = `en_us` 区域名去非字母数字、**保留大小写**，直接拼成 next 节点名。
 *  2. 解析失败必须抛错（上游返回 false），不能静默走默认分支。
 */
class OngoingDeliverySupportTest {

    // 与真实 catalog.json 同构：同一区域可由多个目的地共享；多语言区域文本都参与匹配。
    private val catalog = """
    {
      "depots": [
        {"id":"depot_a","name":{"zh_cn":"源石研究园","en_us":"Originium Science Park"},"map":"map01","route_node":"RouteA","zip_route_node":"RouteAZip"},
        {"id":"depot_b","name":{"zh_cn":"武陵城","en_us":"Wuling City"},"map":"map02","route_node":"RouteB","zip_route_node":"RouteBZip"},
        {"id":"depot_c","name":{"zh_cn":"甲","en_us":"AlphaBetaGammaDelta"},"map":"map03","route_node":"RouteC","zip_route_node":"RouteCZip"},
        {"id":"depot_d","name":{"zh_cn":"乙","en_us":"AlphaBetaGammaDeltb"},"map":"map04","route_node":"RouteD","zip_route_node":"RouteDZip"}
      ],
      "destinations": [
        {"id":"d1","kind":"npc","depot_id":"depot_a","name":{"zh_cn":"英格","en_us":"Ingol"},"mission":{"zh_cn":"交给英格","en_us":"Deliver to Ingol"},"area":{"zh_cn":"源石研究园","zh_tw":"源石研究園","en_us":"Originium Science Park","ja_jp":"源石研究パーク"},"route_node":"DR1","zip_route_node":"DR1Z"},
        {"id":"d2","kind":"npc","depot_id":"depot_a","name":{"zh_cn":"安洁","en_us":"Angel"},"mission":{"zh_cn":"交给安洁","en_us":"Deliver to Angel"},"area":{"zh_cn":"源石研究园","en_us":"Originium Science Park"},"route_node":"DR2","zip_route_node":"DR2Z"},
        {"id":"d3","kind":"npc","depot_id":"depot_b","name":{"zh_cn":"绛","en_us":"Jiang"},"mission":{"zh_cn":"交给绛","en_us":"Deliver to Jiang"},"area":{"zh_cn":"武陵城","en_us":"Wuling City","ko_kr":"무릉성"},"route_node":"DR3","zip_route_node":"DR3Z"},
        {"id":"d4","kind":"npc","depot_id":"depot_c","name":{"zh_cn":"甲客","en_us":"Ian"},"mission":{"zh_cn":"交给甲客","en_us":"Deliver to Ian"},"area":{"zh_cn":"甲","en_us":"AlphaBetaGammaDelta"},"route_node":"DR4","zip_route_node":"DR4Z"},
        {"id":"d5","kind":"npc","depot_id":"depot_d","name":{"zh_cn":"乙客","en_us":"Ian2"},"mission":{"zh_cn":"交给乙客","en_us":"Deliver to Ian2"},"area":{"zh_cn":"乙","en_us":"AlphaBetaGammaDeltb"},"route_node":"DR5","zip_route_node":"DR5Z"}
      ]
    }
    """.trimIndent()

    private fun ensureCatalog() = AutoDeliverySupport.ensureLoaded(catalog)

    // ── 区域 ID 映射：大小写 / 空格 / 缺失 ──

    @Test
    fun `localizedAreaID 去掉空格保留大小写`() {
        assertEquals(
            "OriginiumSciencePark",
            AutoDeliverySupport.localizedAreaID(mapOf("en_us" to "Originium Science Park")),
        )
    }

    @Test
    fun `localizedAreaID 只保留 ASCII 字母数字`() {
        assertEquals(
            "WulingCity2",
            AutoDeliverySupport.localizedAreaID(mapOf("en_us" to " Wuling City-2! ")),
        )
    }

    @Test
    fun `localizedAreaID 缺失 en_us 返回 null`() {
        assertNull(AutoDeliverySupport.localizedAreaID(mapOf("zh_cn" to "源石研究园")))
        assertNull(AutoDeliverySupport.localizedAreaID(mapOf("en_us" to "   ")))
        assertNull(AutoDeliverySupport.localizedAreaID(mapOf("en_us" to "---")))
    }

    // ── next 目标映射 ──

    @Test
    fun `中文区域解析到对应仓储分派节点`() {
        ensureCatalog()
        val r = OngoingDeliverySupport.resolve("源石研究园")
        assertEquals("OriginiumSciencePark", r.areaId)
        assertEquals("depot_a", r.depotId)
        assertEquals("DeliveryJobsOngoingDeliveryForOriginiumSciencePark", r.nextNode)
        assertEquals(1.0, r.similarity, 1e-9)
    }

    @Test
    fun `英文区域名保留大小写拼节点`() {
        ensureCatalog()
        assertEquals(
            "DeliveryJobsOngoingDeliveryForWulingCity",
            OngoingDeliverySupport.resolve("Wuling City").nextNode,
        )
    }

    @Test
    fun `别名 同一区域的多语言文本都命中同一节点`() {
        ensureCatalog()
        val expected = "DeliveryJobsOngoingDeliveryForOriginiumSciencePark"
        assertEquals(expected, OngoingDeliverySupport.resolve("Originium Science Park").nextNode)
        assertEquals(expected, OngoingDeliverySupport.resolve("源石研究園").nextNode)
        assertEquals(expected, OngoingDeliverySupport.resolve("源石研究パーク").nextNode)
    }

    @Test
    fun `全角与多余空格归一化后仍命中`() {
        ensureCatalog()
        assertEquals(
            "DeliveryJobsOngoingDeliveryForOriginiumSciencePark",
            OngoingDeliverySupport.resolve("Ｏｒｉｇｉｎｉｕｍ　Ｓｃｉｅｎｃｅ　Ｐａｒｋ").nextNode,
        )
    }

    // ── 失败路径：必须抛错，不能静默成功 ──

    @Test
    fun `相似度不足时抛错`() {
        ensureCatalog()
        assertThrows(AutoDeliverySupport.ResolveException::class.java) {
            OngoingDeliverySupport.resolve("zzzzzzzzzz")
        }
    }

    @Test
    fun `两个仓储过于接近时判定歧义并抛错`() {
        ensureCatalog()
        // 与两个 Alpha... 各差一个字符，相似度相同、边际为 0，低于 0.05
        assertThrows(AutoDeliverySupport.ResolveException::class.java) {
            OngoingDeliverySupport.resolve("AlphaBetaGammaDeltz")
        }
    }

    @Test
    fun `空白区域文本直接抛错`() {
        assertThrows(AutoDeliverySupport.ResolveException::class.java) {
            OngoingDeliverySupport.resolve("   ")
        }
    }

    // ── 识别结果树取文本 ──

    @Test
    fun `extractAreaText 按节点名取文本并 trim`() {
        val tree = BetterSlidingOcr.Detail(
            name = "DeliveryJobsResolveOngoingDepot",
            children = listOf(
                BetterSlidingOcr.Detail(name = "AutoDeliveryInDeliveryMissionDetail"),
                BetterSlidingOcr.Detail(name = "AutoDeliveryCheckAreaText", text = "  源石研究园  "),
            ),
        )
        assertEquals("源石研究园", OngoingDeliverySupport.extractAreaText(tree))
    }

    @Test
    fun `extractAreaText 缺节点或空文本抛错`() {
        assertThrows(AutoDeliverySupport.ResolveException::class.java) {
            OngoingDeliverySupport.extractAreaText(BetterSlidingOcr.Detail(name = "root"))
        }
        val blank = BetterSlidingOcr.Detail(
            name = "root",
            children = listOf(BetterSlidingOcr.Detail(name = "AutoDeliveryCheckAreaText", text = "   ")),
        )
        assertThrows(AutoDeliverySupport.ResolveException::class.java) {
            OngoingDeliverySupport.extractAreaText(blank)
        }
    }

    // ── override 生成 ──

    @Test
    fun `nextOverrideJson 覆盖目标节点的 next 字段`() {
        val text = OngoingDeliverySupport.nextOverrideJson("DeliveryJobsOngoingDeliveryForWulingCity")
        val obj = Json.parseToJsonElement(text).jsonObject
        val node = obj["DeliveryJobsResolveOngoingDepot"]!!.jsonObject
        val next = node["next"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("DeliveryJobsOngoingDeliveryForWulingCity"), next)
    }
}
