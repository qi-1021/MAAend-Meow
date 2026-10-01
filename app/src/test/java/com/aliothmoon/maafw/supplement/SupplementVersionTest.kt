package com.aliothmoon.maafw.supplement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补充包版本比较纯逻辑测试。
 *
 * 这里钉死的是比较口径：数字段按数值而不是字典序、段数不等时缺的段当 0、
 * 未声明版本不判为过期。装错版本要能看到提示，前提是「低于要求」这个判断本身可信。
 */
class SupplementVersionTest {

    @Test
    fun `数字段按数值比较而不是字典序`() {
        // 字典序会得到 "2026.10.1" < "2026.9.28"（'1' < '9'），这是错的
        assertTrue(SupplementVersion.compare("2026.10.1", "2026.9.28") > 0)
        assertTrue(SupplementVersion.compare("1.10.0", "1.9.0") > 0)
        assertTrue(SupplementVersion.compare("1.2.10", "1.2.9") > 0)
    }

    @Test
    fun `相等版本返回零`() {
        assertEquals(0, SupplementVersion.compare("2026.9.28", "2026.9.28"))
        assertEquals(0, SupplementVersion.compare(" 1.0.0 ", "1.0.0"))
    }

    @Test
    fun `段数不等时缺的段当零`() {
        assertEquals(0, SupplementVersion.compare("2026.9", "2026.9.0"))
        assertEquals(0, SupplementVersion.compare("2026.9.1.0", "2026.9.1"))
        assertTrue(SupplementVersion.compare("2026.9", "2026.9.1") < 0)
        assertTrue(SupplementVersion.compare("2026.9.1", "2026.9") > 0)
    }

    @Test
    fun `非数字段退化为字符串比较`() {
        // 日期串与语义化串都可能带后缀；退化成字符串比较至少是确定且稳定的
        val c = SupplementVersion.compare("1.0.0-rc1", "1.0.0")
        assertTrue(c != 0)
        assertEquals(0, SupplementVersion.compare("1.0.0-rc1", "1.0.0-rc1"))
    }

    @Test
    fun `未声明版本不判为过期`() {
        assertFalse(SupplementVersion.isBelow(null, "1.0"))
        assertFalse(SupplementVersion.isBelow("", "1.0"))
        assertFalse(SupplementVersion.isBelow("   ", "1.0"))
        assertFalse(SupplementVersion.isBelow("1.0", null))
        assertFalse(SupplementVersion.isBelow("1.0", ""))
    }

    @Test
    fun `低于要求为过期 达到或超过不算`() {
        assertTrue(SupplementVersion.isBelow("2026.8.1", "2026.9.28"))
        assertFalse(SupplementVersion.isBelow("2026.9.28", "2026.9.28"))
        assertFalse(SupplementVersion.isBelow("2026.10.1", "2026.9.28"))
    }

    @Test
    fun `isOutdated 用 App 要求判定`() {
        assertTrue(SupplementVersion.isOutdated("map-locate", "2020.1.1"))
        assertFalse(
            SupplementVersion.isOutdated(
                "map-locate",
                SupplementVersion.requiredFor("map-locate"),
            ),
        )
        // 未收录的包没有要求 → 永不判为过期
        assertFalse(SupplementVersion.isOutdated("unknown-pack", "0"))
    }

    @Test
    fun `三个固定包都声明了要求版本`() {
        for (id in listOf("map-locate", "map-navmesh", "detect")) {
            assertTrue("缺少 $id 的要求版本", SupplementVersion.requiredFor(id).isNotBlank())
        }
    }
}
