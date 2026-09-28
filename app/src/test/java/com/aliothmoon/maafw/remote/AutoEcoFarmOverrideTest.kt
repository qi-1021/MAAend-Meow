package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.AutoEcoFarmOverride.Param
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * autoEcoFarmOverrideTargetTemplate 测试（对齐上游 targettemplateoverride.go）。
 *
 * 这个 action 是农田识别链能否用对模板的前提：nodeNames 必须显式给出、只改
 * recognition.param.template，且参数不合法要整节点失败（不能静默 noop）。
 */
class AutoEcoFarmOverrideTest {

    @Test
    fun `空或非法参数判失败`() {
        assertNull(AutoEcoFarmOverride.parseParam(null))
        assertNull(AutoEcoFarmOverride.parseParam(""))
        assertNull(AutoEcoFarmOverride.parseParam("   "))
        assertNull(AutoEcoFarmOverride.parseParam("不是 json"))
        assertNull(AutoEcoFarmOverride.parseParam("[1,2]"))
    }

    @Test
    fun `字段类型不对判失败`() {
        assertNull(AutoEcoFarmOverride.parseParam("""{"template":1,"nodeNames":["A"]}"""))
        assertNull(AutoEcoFarmOverride.parseParam("""{"template":"T","nodeNames":"A"}"""))
        assertNull(AutoEcoFarmOverride.parseParam("""{"template":"T","nodeNames":["A",1]}"""))
    }

    @Test
    fun `字段缺失时用默认空值`() {
        assertEquals(Param("T", emptyList()), AutoEcoFarmOverride.parseParam("""{"template":"T"}"""))
        assertEquals(Param("", listOf("A")), AutoEcoFarmOverride.parseParam("""{"nodeNames":["A"]}"""))
        assertEquals(Param("T", emptyList()), AutoEcoFarmOverride.parseParam("""{"template":"T","nodeNames":null}"""))
    }

    @Test
    fun `节点名清洗去空白并丢空串`() {
        assertEquals(
            listOf("A", "B"),
            AutoEcoFarmOverride.normalizeNodeNames(listOf(" A ", "", "  ", "B")),
        )
    }

    @Test
    fun `template 为空或节点名清洗后为空则不构造`() {
        assertNull(AutoEcoFarmOverride.buildOverride(Param("  ", listOf("A"))))
        assertNull(AutoEcoFarmOverride.buildOverride(Param("T", listOf("", "  "))))
        assertNull(AutoEcoFarmOverride.buildOverride(Param("T", emptyList())))
    }

    @Test
    fun `构造的覆盖片段只改 recognition param template`() {
        val override = AutoEcoFarmOverride.buildOverride(
            Param("AutoEcoFarm/AutoEcoFarmFarmlandWithBack.png", listOf(" A ", "B")),
        )!!
        assertEquals(setOf("A", "B"), override.keys)
        assertEquals(
            """{"A":{"recognition":{"param":{"template":["AutoEcoFarm/AutoEcoFarmFarmlandWithBack.png"]}}},""" +
                """"B":{"recognition":{"param":{"template":["AutoEcoFarm/AutoEcoFarmFarmlandWithBack.png"]}}}}""",
            JsonTree.toJson(override),
        )
    }

    @Test
    fun `template 会去首尾空白`() {
        val override = AutoEcoFarmOverride.buildOverride(Param("  T.png ", listOf("A")))!!
        assertTrue(JsonTree.toJson(override).contains("\"T.png\""))
    }
}
