package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OutpostTrading 运行时数据加载与派生测试
 * （对齐上游 internal/selectiondata/data.go + operator/data.go）。
 *
 * 用结构真实的内联 JSON 做 fixture，不依赖文件路径——CI 上 gradle test 的工作目录
 * 不固定，读仓库文件会变成脆弱测试。
 */
class OperatorDatasetTest {

    /** 与 selection_data.json 同构的最小样本：两个据点、两个干员。 */
    private val fixture = """
        {
          "items": { "itemA": { "names": { "zh_cn": "货品A" } } },
          "operators": {
            "OperatorB": { "names": { "zh_cn": "干员B", "en_us": "Operator B" } },
            "OperatorA": { "names": { "zh_cn": "干员A", "en_us": "[Operator A]" } }
          },
          "location_order": ["Loc1", "Loc2"],
          "locations": {
            "Loc1": {
              "names": { "zh_cn": "据点一" },
              "items": [],
              "target_operators": [
                { "name": "OperatorB", "bonus_tier": 1, "outpost_prosperity_max_bonus_tier": 0 },
                { "name": "OperatorA", "bonus_tier": 0, "outpost_prosperity_max_bonus_tier": 0 }
              ],
              "restore_operators": ["OperatorA"]
            },
            "Loc2": {
              "names": { "zh_cn": "据点二" },
              "items": [],
              "target_operators": [
                { "name": "OperatorA", "bonus_tier": 0, "outpost_prosperity_max_bonus_tier": 0 }
              ],
              "restore_operators": []
            }
          }
        }
    """.trimIndent()

    private fun load() = OperatorDataset.parse(fixture)

    @Test
    fun `解析出完整结构`() {
        val data = load()
        assertEquals(listOf("Loc1", "Loc2"), data?.locationOrder)
        assertEquals(2, data?.operators?.size)
        assertEquals(2, data?.locations?.size)
        assertEquals(1, data?.items?.size)
    }

    @Test
    fun `结构与格式不对时返回 null`() {
        assertNull(OperatorDataset.parse("不是 json"))
        assertNull(OperatorDataset.parse(null))
        assertNull(OperatorDataset.parse("""{"items":{}}"""))
    }

    @Test
    fun `validate 要求据点目录非空 validateOperators 还要求干员非空`() {
        val data = load()!!
        assertTrue(OperatorDataset.validate(data))
        assertTrue(OperatorDataset.validateOperators(data))

        val noOperators = OperatorDataset.parse(
            """{"items":{},"operators":{},"location_order":["L"],"locations":{"L":{"names":{},"target_operators":[],"restore_operators":[]}}}""",
        )!!
        assertTrue(OperatorDataset.validate(noOperators))
        assertEquals(false, OperatorDataset.validateOperators(noOperators))
    }

    @Test
    fun `expectedNames 按仓库语言顺序去重`() {
        val names = mapOf(
            "ko_kr" to "코",
            "zh_cn" to "中文名",
            "en_us" to "English",
            "zh_tw" to "中文名",
        )
        // 顺序固定 zh_cn -> zh_tw -> en_us -> ja_jp -> ko_kr，且去重
        assertEquals(listOf("中文名", "English", "코"), OperatorDataset.expectedNames(names))
    }

    @Test
    fun `expectedNames 跳过空值`() {
        assertEquals(emptyList<String>(), OperatorDataset.expectedNames(mapOf("zh_cn" to "   ")))
    }

    @Test
    fun `localizedName 当前语言优先再回退默认与入参`() {
        val names = mapOf("zh_cn" to "中文名", "en_us" to "English")
        OperatorDataset.currentLanguage = "en_us"
        assertEquals("English", OperatorDataset.localizedName(names, "fallback"))
        OperatorDataset.currentLanguage = "ja_jp" // 没有 -> 回退默认 zh_cn
        assertEquals("中文名", OperatorDataset.localizedName(names, "fallback"))
        OperatorDataset.currentLanguage = "zh_cn"

        assertEquals("fallback", OperatorDataset.localizedName(emptyMap(), " fallback "))
    }

    @Test
    fun `normalizeLang 不认识的语言回退默认`() {
        assertEquals("en_us", OperatorDataset.normalizeLang("en_us"))
        assertEquals("zh_cn", OperatorDataset.normalizeLang("fr_fr"))
        assertEquals("zh_cn", OperatorDataset.normalizeLang(""))
    }

    @Test
    fun `normalizeOperatorCandidates 按名字去重并保留首次出现`() {
        val candidates = listOf(
            OperatorDataset.OperatorCandidate("A", listOf("甲"), priority = 5, bonusTier = 1, outpostProsperityMaxBonusTier = 0),
            OperatorDataset.OperatorCandidate("A", listOf("甲2"), priority = 9, bonusTier = 9, outpostProsperityMaxBonusTier = 0),
        )
        val normalized = OperatorDataset.normalizeOperatorCandidates(candidates)
        assertEquals(1, normalized.size)
        // 保留第一次出现的配置（priority=5），不是后面的
        assertEquals(5, normalized[0].priority)
        assertEquals(listOf("甲"), normalized[0].expected)
    }

    @Test
    fun `normalizeOperatorCandidates 先按加成档再按优先序排序`() {
        val candidates = listOf(
            OperatorDataset.OperatorCandidate("B", listOf("乙"), priority = 0, bonusTier = 1, outpostProsperityMaxBonusTier = 0),
            OperatorDataset.OperatorCandidate("A", listOf("甲"), priority = 3, bonusTier = 0, outpostProsperityMaxBonusTier = 0),
            OperatorDataset.OperatorCandidate("C", listOf("丙"), priority = 1, bonusTier = 0, outpostProsperityMaxBonusTier = 0),
        )
        val normalized = OperatorDataset.normalizeOperatorCandidates(candidates)
        // bonusTier 0 的全部排在 bonusTier 1 之前；同档按 priority
        assertEquals(listOf("C", "A", "B"), normalized.map { it.name })
    }

    @Test
    fun `normalizeOperatorCandidates 丢弃名字或候选名全空的`() {
        val candidates = listOf(
            OperatorDataset.OperatorCandidate("  ", listOf("甲"), priority = 0, bonusTier = 0, outpostProsperityMaxBonusTier = 0),
            OperatorDataset.OperatorCandidate("A", listOf("  "), priority = 1, bonusTier = 0, outpostProsperityMaxBonusTier = 0),
        )
        assertEquals(emptyList<OperatorDataset.OperatorCandidate>(), OperatorDataset.normalizeOperatorCandidates(candidates))
    }

    @Test
    fun `buildOperatorSelectionData 派生候选与恢复分组`() {
        val selection = OperatorDataset.buildOperatorSelectionData(load())
        assertEquals(listOf("Loc1", "Loc2"), selection?.locationOrder)

        // 已知干员按名字排序 -> OperatorA 的 priority 是 0
        assertEquals(listOf("OperatorA", "OperatorB"), selection?.knownOperators?.map { it.name })
        assertEquals(0, selection?.knownOperators?.first()?.priority)

        // Loc1 的 target 排序后 bonusTier 0 的 OperatorA 在前
        assertEquals(listOf("OperatorA", "OperatorB"), selection?.targetCandidates?.get("Loc1")?.map { it.name })

        // 只有 Loc1 有恢复干员，Loc2 的空列表不产生分组
        assertEquals(1, selection?.restoreGroups?.size)
        assertEquals("Loc1", selection?.restoreGroups?.first()?.location)
        assertEquals(listOf("OperatorA"), selection?.restoreGroups?.first()?.candidates?.map { it.name })
    }

    @Test
    fun `buildOperatorSelectionData 对无效数据返回 null`() {
        assertNull(OperatorDataset.buildOperatorSelectionData(null))
        val noOperators = OperatorDataset.parse(
            """{"items":{},"operators":{},"location_order":["L"],"locations":{"L":{"names":{},"target_operators":[],"restore_operators":[]}}}""",
        )
        assertNull(OperatorDataset.buildOperatorSelectionData(noOperators))
    }

    @Test
    fun `normalizeOperatorCandidateGroups 去重并丢弃空候选组`() {
        val a = OperatorDataset.OperatorCandidate("A", listOf("甲"), 0, 0, 0)
        val groups = listOf(
            OperatorDataset.OperatorCandidateGroup("  ", listOf(a)),
            OperatorDataset.OperatorCandidateGroup("Loc1", listOf(a)),
            OperatorDataset.OperatorCandidateGroup("Loc1", listOf(a)),
            OperatorDataset.OperatorCandidateGroup("Loc2", emptyList()),
        )
        val normalized = OperatorDataset.normalizeOperatorCandidateGroups(groups)
        assertEquals(listOf("Loc1"), normalized.map { it.location })
    }

    @Test
    fun `uniqueNonEmptyStrings trim 去重保序`() {
        assertEquals(
            listOf("a", "b"),
            OperatorDataset.uniqueNonEmptyStrings(listOf(" a ", "", "b", "a", "  ")),
        )
    }

    @Test
    fun `候选的 expected 带上本地化名 供 OCR 匹配`() {
        val selection = OperatorDataset.buildOperatorSelectionData(load())!!
        val operatorA = selection.knownOperators.first { it.name == "OperatorA" }
        assertEquals(listOf("干员A", "[Operator A]"), operatorA.expected)
    }
}
