package com.aliothmoon.maafw.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 探针纯逻辑测试。
 *
 * 锁住两件真机上踩过的事：
 *  1. 多条目画面绝不能带 `only_rec:true`（会把整个 ROI 当一行、只回一个大框乱码）；
 *  2. `best_result_=null`（hit=false）就是坏帧信号，必须判可疑、允许刷帧重试，
 *     不能被 `collectOcrItems` 从 `all_results_` 里捡到的一条噪声蒙混过去。
 */
class OcrProbeSupportTest {

    private fun ocr(text: String, w: Int, h: Int, x: Int = 0, y: Int = 0) =
        GoodsSupport.OcrItem(text, intArrayOf(x, y, w, h))

    // ───────────────── buildOverride ─────────────────
    //
    // 契约：三个可变字段（roi / only_rec / color_filter）**一律显式写出，绝不省略**。
    // 理由：探针节点是所有 OCR 共用的同一个名字，而 OverridePipeline 会保留上次写入的
    // 定义——本次没写的键会**继承上一次的值**。
    // 真机实测（2026-09-29）：山谷货卡读完后、买货环节用 onlyRec=true 读了一次单价，
    // 共享节点被写成 only_rec=true；随后武陵货卡的 override 没写 only_rec，继承了 true
    // → 整个 ROI 退化成一行文字 → 读空（更早一次表现为一个大框乱码 `2そ22`）。

    @Test
    fun `多条目探针显式关闭 only_rec 并清空过滤`() {
        assertEquals(
            "{\"__GoodsOcrProbe\":{\"recognition\":\"OCR\",\"roi\":[10,20,30,40]," +
                "\"only_rec\":false,\"color_filter\":\"\"}}",
            OcrProbeSupport.buildOverride(intArrayOf(10, 20, 30, 40), onlyRec = false),
        )
    }

    @Test
    fun `单数值探针显式打开 only_rec`() {
        assertEquals(
            "{\"__GoodsOcrProbe\":{\"recognition\":\"OCR\",\"roi\":[10,20,30,40]," +
                "\"only_rec\":true,\"color_filter\":\"\"}}",
            OcrProbeSupport.buildOverride(intArrayOf(10, 20, 30, 40), onlyRec = true),
        )
    }

    @Test
    fun `无 ROI 时显式写全屏而不是省略`() {
        assertEquals(
            "{\"__GoodsOcrProbe\":{\"recognition\":\"OCR\",\"roi\":[0,0,0,0]," +
                "\"only_rec\":false,\"color_filter\":\"\"}}",
            OcrProbeSupport.buildOverride(null, onlyRec = false),
        )
        assertEquals(
            "{\"__GoodsOcrProbe\":{\"recognition\":\"OCR\",\"roi\":[0,0,0,0]," +
                "\"only_rec\":true,\"color_filter\":\"\"}}",
            OcrProbeSupport.buildOverride(null, onlyRec = true),
        )
    }

    @Test
    fun `ROI 宽高非正时退化为全屏`() {
        assertEquals(
            "{\"__GoodsOcrProbe\":{\"recognition\":\"OCR\",\"roi\":[0,0,0,0]," +
                "\"only_rec\":false,\"color_filter\":\"\"}}",
            OcrProbeSupport.buildOverride(intArrayOf(10, 20, 0, 40), onlyRec = false),
        )
    }

    @Test
    fun `颜色过滤与 only_rec 同时出现`() {
        assertEquals(
            "{\"__GoodsOcrProbe\":{\"recognition\":\"OCR\",\"roi\":[1,2,3,4]," +
                "\"only_rec\":true,\"color_filter\":\"GoodsFilter\"}}",
            OcrProbeSupport.buildOverride(intArrayOf(1, 2, 3, 4), onlyRec = true, colorFilter = "GoodsFilter"),
        )
        assertEquals(
            "{\"__GoodsOcrProbe\":{\"recognition\":\"OCR\",\"roi\":[0,0,0,0]," +
                "\"only_rec\":false,\"color_filter\":\"GoodsFilter\"}}",
            OcrProbeSupport.buildOverride(null, onlyRec = false, colorFilter = "GoodsFilter"),
        )
    }

    @Test
    fun `三个可变字段永不被省略——任何组合都不给继承留余地`() {
        val cases: List<Triple<IntArray?, Boolean, String?>> = listOf(
            Triple(intArrayOf(10, 20, 30, 40), true, "GoodsFilter"),
            Triple(intArrayOf(10, 20, 30, 40), false, null),
            Triple(null, true, null),
            Triple(null, false, ""),
            Triple(intArrayOf(0, 0, 0, 0), false, null),
            Triple(intArrayOf(1, 2, -3, 4), true, "F"),
        )
        for ((roi, onlyRec, filter) in cases) {
            val json = OcrProbeSupport.buildOverride(roi, onlyRec, filter)
            assertTrue("缺 roi: $json", "\"roi\":[" in json)
            assertTrue("缺 only_rec: $json", "\"only_rec\":" in json)
            assertTrue("缺 color_filter: $json", "\"color_filter\":\"" in json)
        }
    }

    // ───────────────── isSuspicious ─────────────────

    @Test
    fun `hit 为假就是坏帧_哪怕 detail 里捡到一条大框噪声`() {
        // 真机现场：filtered_results_ 空、best_result_ 为 null，all_results_ 里只有
        // 一个 700x430、0.21 分的「注」。旧实现只看 items，这条框不到全帧阈值就被放过。
        val noise = listOf(ocr("注", w = 700, h = 430, x = 164, y = 121))
        assertTrue(OcrProbeSupport.isSuspicious(hit = false, items = noise))
    }

    @Test
    fun `hit 为假且无条目也是坏帧`() {
        assertTrue(OcrProbeSupport.isSuspicious(hit = false, items = emptyList()))
    }

    @Test
    fun `命中但空结果可疑`() {
        assertTrue(OcrProbeSupport.isSuspicious(hit = true, items = emptyList()))
    }

    @Test
    fun `单条盖满全帧可疑`() {
        assertTrue(OcrProbeSupport.isSuspicious(hit = true, items = listOf(ocr("噪声", w = 1300, h = 700))))
    }

    @Test
    fun `单条正常大小文本框不算可疑`() {
        // only_rec 单数值场景：命中后本来就只有一条、框也不大
        assertFalse(OcrProbeSupport.isSuspicious(hit = true, items = listOf(ocr("2675", w = 160, h = 48))))
    }

    @Test
    fun `单条无框可疑`() {
        assertTrue(
            OcrProbeSupport.isSuspicious(hit = true, items = listOf(GoodsSupport.OcrItem("x", null))),
        )
    }

    @Test
    fun `只剩单字偏旁可疑`() {
        assertTrue(
            OcrProbeSupport.isSuspicious(
                hit = true,
                items = listOf(ocr("手", 40, 40), ocr("手", 45, 40), ocr("手", 50, 40)),
            ),
        )
    }

    @Test
    fun `多个正常货名不算可疑`() {
        assertFalse(
            OcrProbeSupport.isSuspicious(
                hit = true,
                items = listOf(
                    ocr("岳研避瘴茶货组", 200, 40),
                    ocr("冬虫夏草货组", 200, 40),
                    ocr("2675", 160, 48),
                ),
            ),
        )
    }

    @Test
    fun `五条以上即使全是单字也不触发偏旁判定`() {
        // 阈值是 <=4 条才当偏旁噪声，超过则交给上层按正常结果处理
        val many = (1..5).map { ocr("手", 40, 40) }
        assertFalse(OcrProbeSupport.isSuspicious(hit = true, items = many))
    }

    @Test
    fun `两到四条完全相同的短文本可疑_真机武陵的 2そ22 三连`() {
        // 首帧货卡没铺满时两遍 OCR 都只回三条一模一样的乱码，旧判据（全是单字）漏掉
        assertTrue(
            OcrProbeSupport.isSuspicious(
                hit = true,
                items = listOf(ocr("2そ22", 80, 30), ocr("2そ22", 82, 31), ocr("2そ22", 84, 30)),
            ),
        )
        // 两条相同也在判定内
        assertTrue(
            OcrProbeSupport.isSuspicious(hit = true, items = listOf(ocr("2そ22", 80, 30), ocr("2そ22", 82, 31))),
        )
    }

    @Test
    fun `多条但文本不同不算可疑`() {
        // 正常的货卡网格：名字/价格各不相同，别被上面的「相同文本」判据扫到
        assertFalse(
            OcrProbeSupport.isSuspicious(
                hit = true,
                items = listOf(ocr("1200", 80, 30), ocr("1200", 82, 31), ocr("950", 84, 30)),
            ),
        )
    }

    @Test
    fun `两条不同的短数值不是可疑帧`() {
        assertFalse(
            OcrProbeSupport.isSuspicious(hit = true, items = listOf(ocr("2675", 160, 48), ocr("1811", 160, 48))),
        )
    }

    // ───────────────── 帧来源选择（框架帧 vs 自抓）─────────────────────────

    @Test
    fun `第一次尝试且有框架帧时用框架帧`() {
        // 根因修复：识别回调把框架本次识别用的 image 透传进来，第一次 OCR 直接用它，
        // 「OCR 看到的就是框架看到的」，不再自抓一张可能不一致的帧（武陵 2そ22）。
        assertTrue(OcrProbeSupport.shouldUseFrameworkFrame(attempt = 1, hasFrameworkFrame = true))
    }

    @Test
    fun `重试时不用框架帧_要换新帧等画面进场`() {
        // 重试的意义就是换一帧；复用同一张框架帧等于不换帧。
        assertFalse(OcrProbeSupport.shouldUseFrameworkFrame(attempt = 2, hasFrameworkFrame = true))
        assertFalse(OcrProbeSupport.shouldUseFrameworkFrame(attempt = 3, hasFrameworkFrame = true))
    }

    @Test
    fun `image 为空时第一次也回退自抓`() {
        // image 可能为空（未来从别处调用），必须明确回退，不能崩也不该用空帧。
        assertFalse(OcrProbeSupport.shouldUseFrameworkFrame(attempt = 1, hasFrameworkFrame = false))
        assertFalse(OcrProbeSupport.shouldUseFrameworkFrame(attempt = 2, hasFrameworkFrame = false))
    }

    @Test
    fun `尝试序号非正时不使用框架帧`() {
        assertFalse(OcrProbeSupport.shouldUseFrameworkFrame(attempt = 0, hasFrameworkFrame = true))
    }

    @Test
    fun `探针内部第一轮用首选帧`() {
        assertTrue(OcrProbeSupport.shouldUsePreferredFrame(attemptIndex = 0, hasPreferredFrame = true))
    }

    @Test
    fun `探针内部坏帧重试第二轮起自抓`() {
        // cachedOcrProbe 的 attempts=2：第一轮用首选帧，第二轮是坏帧重试，必须自己取新帧。
        assertFalse(OcrProbeSupport.shouldUsePreferredFrame(attemptIndex = 1, hasPreferredFrame = true))
        assertFalse(OcrProbeSupport.shouldUsePreferredFrame(attemptIndex = 2, hasPreferredFrame = true))
    }

    @Test
    fun `没有首选帧时内部第一轮也自抓`() {
        assertFalse(OcrProbeSupport.shouldUsePreferredFrame(attemptIndex = 0, hasPreferredFrame = false))
    }
}
