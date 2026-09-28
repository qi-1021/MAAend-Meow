package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.remote.BetterSlidingOcr as OCR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BetterSliding 识别结果读取层测试（对齐上游 bettersliding/ocr.go）。
 *
 * 这里最重要的用例是「路径无关」：MaaFramework 的 detail JSON 没有固定的嵌套形状，
 * 仓库里既有的 [GoodsSupport.collectOcrItems] 都是靠递归遍历来避开路径假设的。
 * 所以下面的输入故意把 text/box 埋在**任意**嵌套里，而不是某个我猜的路径。
 *
 * 唯一未经真机确认的假设是节点名用 `"name"` 字段——见 [BetterSlidingOcr] 的说明。
 */
class BetterSlidingOcrTest {

    /** 滑条手柄节点（模板识别命中：其 box 即手柄框）。 */
    private fun swipeButton(x: Int) = mapOf(
        "name" to "BetterSlidingSwipeButton",
        "box" to listOf(x, 500, 20, 20),
    )

    private fun tree(
        name: String = "root",
        box: List<Int>? = null,
        text: String? = null,
        vararg extra: Pair<String, Any?>,
    ): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("name" to name)
        if (box != null) m["box"] = box
        if (text != null) m["text"] = text
        for ((k, v) in extra) m[k] = v
        return m
    }

    private fun detail(node: Any?, fallback: String = "root") =
        OCR.fromRecognizedDetail(node, fallback)

    @Test
    fun `findByName 深度优先按名字找子节点`() {
        val t = OCR.Detail(
            name = "root",
            children = listOf(
                OCR.Detail(name = "a", children = listOf(OCR.Detail(name = "BetterSlidingSwipeButton"))),
                OCR.Detail(name = "BetterSlidingSwipeButton"),
            ),
        )
        assertEquals("BetterSlidingSwipeButton", OCR.findByName(t, "BetterSlidingSwipeButton")?.name)
        assertNull(OCR.findByName(t, "nope"))
        assertNull(OCR.findByName(null, "BetterSlidingSwipeButton"))
    }

    @Test
    fun `readHitBox 优先用 SwipeButton 子节点的框`() {
        val t = detail(tree("root", box = listOf(999, 999, 1, 1), extra = arrayOf("results" to listOf(swipeButton(120)))))
        assertEquals(listOf(120, 500, 20, 20), OCR.readHitBox(t))
    }

    @Test
    fun `readHitBox 文本与框埋在任意嵌套里也能找到 路径无关`() {
        // 故意用三层以上、且 key 名各不相同（detail/children/payload）——不依赖任何具体路径
        val node = tree(
            "root",
            extra = arrayOf(
                "detail" to mapOf(
                    "children" to listOf(
                        mapOf("payload" to listOf(swipeButton(77))),
                    ),
                ),
            ),
        )
        assertEquals(listOf(77, 500, 20, 20), OCR.readHitBox(detail(node)))
    }

    @Test
    fun `readHitBox 没有子节点时退回根节点框`() {
        assertEquals(listOf(7, 8, 9, 10), OCR.readHitBox(detail(tree("root", box = listOf(7, 8, 9, 10)))))
    }

    @Test
    fun `readHitBox 找不到 SwipeButton 时用根框`() {
        val t = detail(tree("root", box = listOf(11, 12, 13, 14), extra = arrayOf("results" to listOf(tree("other")))))
        assertEquals(listOf(11, 12, 13, 14), OCR.readHitBox(t))
    }

    @Test
    fun `readHitBox 完全没有可用框时返回 null`() {
        assertNull(OCR.readHitBox(detail(tree("root"))))
        assertNull(OCR.readHitBox(null))
    }

    @Test
    fun `readQuantityValue 只挑数字`() {
        assertEquals(1024, OCR.readQuantityValue(detail(tree("root", text = "  共 1,024 件 "))))
    }

    @Test
    fun `readQuantityValue 优先读 GetSliderQuantity 子节点`() {
        val t = detail(
            tree(
                "root",
                text = "999",
                extra = arrayOf(
                    "results" to listOf(
                        mapOf("name" to "BetterSlidingGetSliderQuantity", "text" to "37"),
                    ),
                ),
            ),
        )
        assertEquals(37, OCR.readQuantityValue(t))
    }

    @Test
    fun `readQuantityValue 自身没文本时向下取第一个`() {
        val t = detail(
            tree(
                "root",
                box = listOf(1, 2, 3, 4),
                extra = arrayOf("results" to listOf(mapOf("text" to "x250"))),
            ),
        )
        assertEquals(250, OCR.readQuantityValue(t))
    }

    @Test
    fun `readQuantityValue 没有数字或没有文本时返回 null`() {
        assertNull(OCR.readQuantityValue(detail(tree("root", text = "无数字"))))
        assertNull(OCR.readQuantityValue(detail(tree("root", text = "   "))))
        assertNull(OCR.readQuantityValue(detail(tree("root"))))
        assertNull(OCR.readQuantityValue(null))
    }

    // ── 真机回归：And 组合识别的 detail_json 根是数组 ──
    // maafw.log 里 BetterSlidingGetSliderQuantity 已识别出 7299，但旧解析器对数组根
    // 直接返回 null → 「BetterSliding 读不到滑条上限」。形状对齐官方 Go 绑定
    // recognition_result.go:325 parseCombinedResult。

    /** 一条组合结果项：名字 + 自身 box + 嵌套的 OCR 结果（all/best/filtered）。 */
    private fun combinedItem(name: String, box: List<Int>, text: String, algorithm: String = "OCR"): Map<String, Any?> =
        mapOf(
            "algorithm" to algorithm,
            "box" to box,
            "detail" to linkedMapOf(
                "all" to listOf(mapOf("box" to box, "score" to 0.99, "text" to text)),
                "best" to mapOf("box" to box, "score" to 0.99, "text" to text),
                "filtered" to listOf(mapOf("box" to box, "score" to 0.99, "text" to text)),
            ),
            "name" to name,
            "reco_id" to 42L,
        )

    @Test
    fun `fromRecognizedDetail 组合识别数组根不再返回 null`() {
        val t = detail(listOf(combinedItem("BetterSlidingGetSliderQuantity", listOf(1065, 499, 78, 36), "7299")))
        // 合成根沿用兜底名，数组各项成为它的子节点
        assertEquals("root", t?.name)
        assertEquals(1, t?.children?.size)
    }

    @Test
    fun `readQuantityValue 能从 And 组合结果数组读到滑条上限`() {
        // 真实 7299 场景：BetterSlidingGetSliderMaxQuantity 是 And，detail 根为数组
        val t = detail(
            listOf(
                combinedItem("BetterSlidingGetSliderQuantity", listOf(1065, 499, 78, 36), "7299"),
            ),
        )
        assertEquals(7299, OCR.readQuantityValue(t))
    }

    @Test
    fun `readQuantityValue 优先 best 而非 all 首个结果`() {
        // only_rec=false / 多结果时，上游读的是 Results.Best；旧实现按 JSON 键序取到 all[0]
        val node = linkedMapOf<String, Any?>(
            "name" to "root",
            "all" to listOf(mapOf("text" to "1")),
            "best" to mapOf("text" to "7299"),
            "filtered" to listOf(mapOf("text" to "2")),
        )
        assertEquals(7299, OCR.readQuantityValue(detail(node)))
    }

    @Test
    fun `readHitBox 能从 And 组合结果数组取 SwipeButton 框`() {
        val t = detail(
            listOf(
                combinedItem("BetterSlidingSwipeButton", listOf(500, 520, 20, 20), "x", algorithm = "TemplateMatch"),
            ),
        )
        assertEquals(listOf(500, 520, 20, 20), OCR.readHitBox(t))
    }

    @Test
    fun `fromRecognizedDetail 缺 name 时用兜底名`() {
        val t = detail(mapOf("text" to "5"), fallback = "fallbackName")
        assertEquals("fallbackName", t?.name)
        assertEquals(5, OCR.readQuantityValue(t))
    }

    @Test
    fun `fromRecognizedDetail 拿不到对象就返回 null`() {
        assertNull(OCR.fromRecognizedDetail(null, "x"))
        assertNull(OCR.fromRecognizedDetail("not a map", "x"))
    }

    @Test
    fun `fromRecognizedDetail 任意层级的子节点都能找到`() {
        val node = tree(
            "root",
            text = "128",
            extra = arrayOf(
                "detail" to mapOf(
                    "query" to listOf(
                        swipeButton(50),
                        mapOf("name" to "BetterSlidingGetSliderQuantity", "text" to "9"),
                    ),
                ),
            ),
        )
        val t = detail(node)
        assertEquals("root", t?.name)
        // 关键：不管埋多深，两个目标节点都要能被找到
        // （中间层没有 name 字段，名字是空串——节点名只来自自身 name，与 key 无关）
        assertEquals(listOf(50, 500, 20, 20), OCR.findByName(t, "BetterSlidingSwipeButton")?.box)
        assertEquals("9", OCR.findByName(t, "BetterSlidingGetSliderQuantity")?.text)
        assertEquals(listOf(50, 500, 20, 20), OCR.readHitBox(t))
    }

    @Test
    fun `box 少于四个数时视为无框`() {
        val t = detail(tree("root", box = listOf(1, 2, 3)))
        assertNull(OCR.readHitBox(t))
    }

    @Test
    fun `box 多于四个数时只取前四个`() {
        assertEquals(listOf(1, 2, 3, 4), OCR.readHitBox(detail(tree("root", box = listOf(1, 2, 3, 4, 5, 6)))))
    }

    // ── 回调 box（MaaRect 四元组）构造与有效性 ──
    // 真机 bug：起点/终点框走 detail_json 读不到，改由回调第 7 个参数解出四元组。
    // 这一段是那条路的纯逻辑收口。

    @Test
    fun `boxOfRect 正宽高才构造`() {
        assertEquals(listOf(487, 521, 39, 37), OCR.boxOfRect(487, 521, 39, 37))
    }

    @Test
    fun `boxOfRect 宽或高非正时视为无效`() {
        assertNull(OCR.boxOfRect(1, 2, 0, 4))
        assertNull(OCR.boxOfRect(1, 2, 3, 0))
        assertNull(OCR.boxOfRect(1, 2, -3, 4))
        assertNull(OCR.boxOfRect(1, 2, 3, -4))
    }

    @Test
    fun `boxOfRect 允许识别结果贴屏幕边`() {
        assertEquals(listOf(0, 0, 5, 5), OCR.boxOfRect(0, 0, 5, 5))
        assertEquals(listOf(0, 700, 10, 10), OCR.boxOfRect(0, 700, 10, 10))
    }
}
