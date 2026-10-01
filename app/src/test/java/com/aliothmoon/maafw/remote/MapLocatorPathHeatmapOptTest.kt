package com.aliothmoon.maafw.remote

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapLocatorPathHeatmap.matchGlobal] 优化版与**朴素参考实现**的等价性测试。
 *
 * 参考实现 = 对每个候选位置直接调 [MapLocatorPathHeatmap.scoreAt]（逐位精确的带掩膜 ZNCC，
 * 该函数本身未改动）得到全图相关面，再按与 `matchGlobal` 相同的规则统计
 * best / second / delta / PSR。这就是优化前的语义。
 *
 * 断言分三块：
 *  - **强等价**：`best` 分数、峰值位置逐位一致（1e-9）。
 *  - **top-k 覆盖**：用**公开常量**（粗扫步长/候选 margin/精搜半径/top-k）在测试侧重建优化版的
 *    候选集（粗网格 + 精搜邻域），断言参考面的前 k 个位置与分数**全部落在这个候选集内且逐位一致**
 *    ——这正是「粗到细不漏峰」的可证伪性质。
 *  - **弱等价**：`secondScore` / `delta` / `psr` 允许小偏差（剪枝后旁瓣统计来源不同）。
 */
class MapLocatorPathHeatmapOptTest {

    private val H = MapLocatorPathHeatmap

    // ───────────────────── 合成「稀疏路网」工具（与既有测试同款）─────────────────────

    private fun hash(x: Int, y: Int, seed: Long): Long {
        var s = x * 374761393L + y * 668265263L + seed
        s = (s xor (s shr 13)) * 1274126177L
        s = s xor (s shr 16)
        return s
    }

    private fun sparseRoadMap(w: Int, h: Int, seed: Long): ByteArray {
        val out = ByteArray(w * h * 3)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val road = (hash(x, y, seed) % 100L).let { if (it < 0) it + 100 else it } < 12L
                if (road) {
                    out[i] = H.PATH_TARGET_B.toByte()
                    out[i + 1] = H.PATH_TARGET_G.toByte()
                    out[i + 2] = H.PATH_TARGET_R.toByte()
                } else {
                    out[i] = 30
                    out[i + 1] = 40
                    out[i + 2] = 50
                }
                i += 3
            }
        }
        return out
    }

    private fun cropGray(full: ByteArray, fw: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(full, (y + row) * fw + x, out, row * w, w)
        }
        return out
    }

    // ───────────────────── 朴素参考实现（优化前语义）─────────────────────

    /** 全图相关面（每个合法位置一次 [MapLocatorPathHeatmap.scoreAt]）。 */
    private fun referenceSurface(
        search: ByteArray, sw: Int, sh: Int,
        templ: ByteArray, tw: Int, th: Int, mask: BooleanArray,
    ): DoubleArray {
        val rw = sw - tw + 1
        val rh = sh - th + 1
        val surface = DoubleArray(rw * rh)
        for (j in 0 until rh) {
            for (i in 0 until rw) surface[j * rw + i] = H.scoreAt(search, sw, sh, templ, tw, th, mask, i, j)
        }
        return surface
    }

    private fun peek(
        surface: DoubleArray, rw: Int, rh: Int, tw: Int, th: Int,
    ): MapLocatorPathHeatmap.PathHeatmapMatch? {
        var best = H.NO_SCORE
        var bi = 0
        var bj = 0
        for (j in 0 until rh) {
            val rowBase = j * rw
            for (i in 0 until rw) {
                val s = surface[rowBase + i]
                if (s > best) { best = s; bi = i; bj = j }
            }
        }
        if (best <= H.NO_SCORE) return null
        val ex = maxOf(H.PEAK_EXCLUDE_MIN, minOf(tw, th) / 10)
        var sideSum = 0.0
        var sideSumSq = 0.0
        var sideCount = 0
        var second = H.NO_SCORE
        for (j in 0 until rh) {
            val rowBase = j * rw
            for (i in 0 until rw) {
                val s = surface[rowBase + i]
                if (s <= H.NO_SCORE) continue
                if (abs(i - bi) <= ex && abs(j - bj) <= ex) continue
                sideSum += s
                sideSumSq += s * s
                sideCount++
                if (s > second) second = s
            }
        }
        val psr = if (sideCount > 0) {
            val mean = sideSum / sideCount
            val variance = (sideSumSq / sideCount - mean * mean).coerceAtLeast(0.0)
            (best - mean) / (kotlin.math.sqrt(variance) + 1e-6)
        } else {
            0.0
        }
        val delta = if (second > H.NO_SCORE) best - second else 0.0
        return MapLocatorPathHeatmap.PathHeatmapMatch(bi, bj, best, second, delta, psr)
    }

    /** 参考面里有效位置的 (score, x, y) 按分数降序、并列按行优先取前 k。 */
    private fun topK(surface: DoubleArray, rw: Int, rh: Int, k: Int): List<Triple<Double, Int, Int>> {
        val all = ArrayList<Triple<Double, Int, Int>>()
        for (j in 0 until rh) {
            val rowBase = j * rw
            for (i in 0 until rw) {
                val s = surface[rowBase + i]
                if (s > H.NO_SCORE) all.add(Triple(s, i, j))
            }
        }
        all.sortWith(
            compareByDescending<Triple<Double, Int, Int>> { it.first }
                .thenBy { it.third }
                .thenBy { it.second },
        )
        return all.take(k)
    }

    /**
     * 用**公开常量**在测试侧重建优化版的候选集（粗网格 ∪ 精搜邻域），返回其中分数前 k 的位置。
     * 用于验证「参考面前 k 名都落在优化版实际求值的候选集内且逐位一致」。
     */
    private fun optimizedCandidateTopK(
        surface: DoubleArray, rw: Int, rh: Int, k: Int,
    ): List<Triple<Double, Int, Int>> {
        val stride = H.COARSE_STRIDE
        val xs = ArrayList<Int>()
        var sx = 0
        while (sx < rw) { xs.add(sx); sx += stride }
        if (xs[xs.size - 1] != rw - 1) xs.add(rw - 1)
        val ys = ArrayList<Int>()
        var sy = 0
        while (sy < rh) { ys.add(sy); sy += stride }
        if (ys[ys.size - 1] != rh - 1) ys.add(rh - 1)
        val cw = xs.size
        val ch = ys.size
        val coarse = DoubleArray(cw * ch)
        var maxCoarse = H.NO_SCORE
        for (yy in 0 until ch) {
            val py = ys[yy]
            for (xx in 0 until cw) {
                val s = surface[py * rw + xs[xx]]
                coarse[yy * cw + xx] = s
                if (s > maxCoarse) maxCoarse = s
            }
        }
        if (maxCoarse <= H.NO_SCORE) return emptyList()
        val floor = maxCoarse - H.COARSE_CANDIDATE_MARGIN
        val picked = BooleanArray(coarse.size)
        for (idx in coarse.indices) if (coarse[idx] >= floor) picked[idx] = true
        val topK = minOf(H.COARSE_TOP_CANDIDATES, coarse.size)
        if (topK > 0) {
            val order = Array(coarse.size) { it }
            order.sortWith(Comparator { a, b -> coarse[b].compareTo(coarse[a]) })
            for (t in 0 until topK) picked[order[t]] = true
        }
        val cand = ArrayList<Int>()
        for (idx in coarse.indices) if (picked[idx]) cand.add(idx)
        if (cand.size > H.MAX_REFINE_CANDIDATES) {
            cand.sortWith(Comparator { a, b -> coarse[b].compareTo(coarse[a]) })
            while (cand.size > H.MAX_REFINE_CANDIDATES) cand.removeAt(cand.size - 1)
        }

        val evaluated = BooleanArray(rw * rh)
        for (yy in 0 until ch) {
            val rowBase = ys[yy] * rw
            for (xx in 0 until cw) evaluated[rowBase + xs[xx]] = true
        }
        val r = H.COARSE_REFINE_RADIUS
        for (c in cand) {
            val cx = xs[c % cw]
            val cy = ys[c / cw]
            val x0 = maxOf(0, cx - r)
            val y0 = maxOf(0, cy - r)
            val x1 = minOf(rw - 1, cx + r)
            val y1 = minOf(rh - 1, cy + r)
            for (j in y0..y1) {
                val rowBase = j * rw
                for (i in x0..x1) evaluated[rowBase + i] = true
            }
        }
        val all = ArrayList<Triple<Double, Int, Int>>()
        for (j in 0 until rh) {
            val rowBase = j * rw
            for (i in 0 until rw) {
                if (!evaluated[rowBase + i]) continue
                val s = surface[rowBase + i]
                if (s > H.NO_SCORE) all.add(Triple(s, i, j))
            }
        }
        all.sortWith(
            compareByDescending<Triple<Double, Int, Int>> { it.first }
                .thenBy { it.third }
                .thenBy { it.second },
        )
        return all.take(k)
    }

    // ───────────────────── 主等价性断言 ─────────────────────

    private data class Case(
        val name: String,
        val sw: Int,
        val sh: Int,
        val tw: Int,
        val th: Int,
        val seed: Long,
        val circle: Boolean,
    )

    private fun runCase(c: Case) {
        val map = sparseRoadMap(c.sw, c.sh, c.seed)
        val heat = H.extractPathHeatmap(map, c.sw, c.sh)!!
        val tx = c.sw / 3
        val ty = c.sh / 3
        val templ = cropGray(heat, c.sw, tx, ty, c.tw, c.th)
        val mask = if (c.circle) {
            MapLocatorRefinePure.circleValidMask(c.tw, c.th, c.tw / 2, c.th / 2, minOf(c.tw, c.th) / 2 - 8)
        } else {
            BooleanArray(c.tw * c.th) { true }
        }
        val rw = c.sw - c.tw + 1
        val rh = c.sh - c.th + 1

        val surface = referenceSurface(heat, c.sw, c.sh, templ, c.tw, c.th, mask)
        val ref = peek(surface, rw, rh, c.tw, c.th)
        val opt = H.matchGlobal(heat, c.sw, c.sh, templ, c.tw, c.th, mask)
        assertNotNull("${c.name}: optimized returned null", opt)
        assertNotNull("${c.name}: reference returned null", ref)

        // 强等价：峰值位置 + best 分逐位一致
        assertEquals("${c.name}: peak x", ref!!.x, opt!!.x)
        assertEquals("${c.name}: peak y", ref.y, opt.y)
        assertEquals("${c.name}: best score", ref.score, opt.score, 1e-9)

        // top-k 覆盖：参考面前 k 名必须都落在优化版候选集内、且分数逐位一致
        val k = 5
        val refTop = topK(surface, rw, rh, k)
        val optTop = optimizedCandidateTopK(surface, rw, rh, k)
        assertEquals("${c.name}: top-k count", refTop.size, optTop.size)
        for (i in 0 until minOf(refTop.size, optTop.size)) {
            assertEquals("${c.name}: top[$i] score", refTop[i].first, optTop[i].first, 1e-9)
            assertEquals("${c.name}: top[$i] x", refTop[i].second, optTop[i].second)
            assertEquals("${c.name}: top[$i] y", refTop[i].third, optTop[i].third)
        }

        // 弱等价：second/delta/psr（打印实测偏差，容差留裕量）
        val psrRel = if (abs(ref.psr) > 1e-9) abs(ref.psr - opt.psr) / abs(ref.psr) else abs(ref.psr - opt.psr)
        println(
            "[opt] ${c.name}: pos=(${ref.x},${ref.y})==(${opt.x},${opt.y}) " +
                "best=${ref.score}/${opt.score} second=${ref.secondScore}/${opt.secondScore} " +
                "delta=${ref.delta}/${opt.delta} psr=${ref.psr}/${opt.psr} psrRel=$psrRel",
        )
        assertEquals("${c.name}: second", ref.secondScore, opt.secondScore, 0.05)
        assertEquals("${c.name}: delta", ref.delta, opt.delta, 0.05)
        assertTrue("${c.name}: psr drift $psrRel", psrRel < 0.05)
    }

    @Test
    fun `优化版与朴素参考在合成稀疏路网上逐位一致`() {
        val cases = listOf(
            Case("200x160/56 all", 200, 160, 56, 56, 0x1234ABCDL, circle = false),
            Case("200x160/56 circle", 200, 160, 56, 56, 0x1234ABCDL, circle = true),
            Case("220x170/64 all", 220, 170, 64, 64, 0x4D41504CL, circle = false),
            Case("220x170/64 circle", 220, 170, 64, 64, 0x4D41504CL, circle = true),
            Case("160x140/48", 160, 140, 48, 48, 0x5EEDL, circle = false),
            Case("180x150/64", 180, 150, 64, 64, 0x0BADF00DL, circle = true),
        )
        for (c in cases) runCase(c)
    }

    /** 多组随机种子：验证 top-1/top-k 一致不是单组输入的巧合。 */
    @Test
    fun `多组随机种子下 top-1 与 top-k 均一致`() {
        var seed = 0x9E3779B97F4A7C15uL
        for (t in 0 until 8) {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            val circle = t % 2 == 1
            runCase(Case("rand$t", 192, 152, 48 + (t % 3) * 8, 48 + (t % 3) * 8, seed.toLong(), circle))
        }
    }

    /**
     * 真实链路掩膜（[MapLocatorPathHeatmap.extractTemplatePathFeature] 产出的圆盘+UI+中心遮蔽）
     * 下的等价性：掩膜行区间可能不止一个，验证区间分解正确。
     */
    @Test
    fun `真实模板掩膜下仍与参考逐位一致`() {
        val sw = 200
        val sh = 180
        val map = sparseRoadMap(sw, sh, 0xC0FFEEL)
        val heat = H.extractPathHeatmap(map, sw, sh)!!
        val tw = 48
        val th = 48
        val tx = 60
        val ty = 50
        val minimap = ByteArray(tw * th * 3)
        for (row in 0 until th) {
            System.arraycopy(map, ((ty + row) * sw + tx) * 3, minimap, row * tw * 3, tw * 3)
        }
        val tmpl = H.extractTemplatePathFeature(minimap, tw, th, MapLocatorPathHeatmap.ImageProcessingConfig.Tier)!!
        val ref = peek(referenceSurface(heat, sw, sh, tmpl.feature, tw, th, tmpl.mask), sw - tw + 1, sh - th + 1, tw, th)
        val opt = H.matchGlobal(heat, sw, sh, tmpl.feature, tw, th, tmpl.mask)
        assertNotNull(ref)
        assertNotNull(opt)
        assertEquals(ref!!.x, opt!!.x)
        assertEquals(ref.y, opt.y)
        assertEquals(ref.score, opt.score, 1e-9)
        assertEquals(ref.secondScore, opt.secondScore, 0.05)
        println(
            "[opt-real] pos=(${ref.x},${ref.y}) score=${ref.score}/${opt.score} " +
                "psr=${ref.psr}/${opt.psr} second=${ref.secondScore}/${opt.secondScore}",
        )
        assertEquals(ref.psr, opt.psr, 0.1)
    }
}
