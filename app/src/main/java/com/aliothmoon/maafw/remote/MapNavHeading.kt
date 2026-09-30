package com.aliothmoon.maafw.remote

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 从小地图 BGR 估玩家朝向（纯逻辑）。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MapAlgorithm.cpp:143-281`
 * 的 `InferYellowArrowRotation`（以及它的私有辅助 `EstimateArrowForward`，`MapAlgorithm.cpp:95-139`）。
 *
 * 上游用 OpenCV 在小地图**正中央**（半径 12 的 24×24 ROI）找那个代表玩家的高亮小三角，
 * 取"最尖的那个角"指向作为朝向，角度约定为 0=正上/北、顺时针增长（`atan2(dx, -dy)`）。
 * 本文件不引 OpenCV/Android，只用 [ByteArray] + `kotlin.math` 重写整条流水线，供
 * `MaaRunner` / 探针在无设备环境下复用。
 *
 * ───────────── 上游步骤 → 本实现（逐条对齐）─────────────
 *
 *  1. 空图 / ROI 越界失败            → `MapAlgorithm.cpp:145-156`；
 *  2. 白色掩膜 `inRange(BGR,220..255)` → `:170`；
 *  3. `findContours(RETR_EXTERNAL)`   → `:172-173`；这里用 8 连通域标注取外轮廓；
 *  4. 取质心离 ROI 中心最近的轮廓      → `:183-203`（`minDistSq > 25` 丢弃）；
 *  5. 只保留该轮廓并填充               → `:205-206`（`drawContours(FILLED)`）；
 *  6. 16× 上采样 + 阈值 127            → `:208-212`；
 *  7. 最大面积轮廓的质心               → `:220-234`（`moments`）；
 *  8. `minEnclosingTriangle`          → `:236-241`；
 *  9. `EstimateArrowForward`（凸包 + `approxPolyDP` 找 3 角 → 最尖角方向）→ `:95-139`；
 * 10. 三角形里与 forward 点积最大的顶点作为箭尖，否则取离质心最远的顶点 → `:243-269`；
 * 11. `atan2(tip - centroid)` 转度并归一                                → `:271-278`。
 *
 * ───────────── OpenCV 特有操作的可证明等价近似与差异 ─────────────
 *
 *  • **`inRange` → 逐通道比较**：`inRange(Scalar(220,220,220),Scalar(255,255,255))` 在 BGR 上
 *    就是每通道 ∈[220,255]。byte 上限即 255，故只需 `>=220`，严格等价。
 *  • **`findContours(RETR_EXTERNAL)` → 8 连通域**：对二值 mask，外层轮廓就是白色前景的
 *    连通分量（OpenCV 默认 8 连通），取每个分量的**填充像素集**。差异：OpenCV 的
 *    `moments(contour)` 用的是**边界折线**，这里用**填充区域的面积质心**。对实心三角二者都
 *    落在对称轴上，只影响"离中心最近"的挑选与最终质心的小数偏差（见 `centroid` 注释）。
 *  • **`drawContours(FILLED)` → 直接取分量像素**：等价（分量本就是填充后的形状）。
 *  • **16× `INTER_CUBIC` + 阈值 127 → 直接用原分辨率像素**：这步只为得到更平滑的边界
 *    供 `minEnclosingTriangle` 用；先插值再二值化得到的形状与原形状一致，顶点方向不变，
 *    故原分辨率像素的凸包与之等价。
 *  • **`minEnclosingTriangle` → 凸包顶点三元组穷举**：对三角形点集，最小面积外接三角形
 *    就是该三角形本身，故**精确**；对一般凸多边形，OpenCV 允许三角形顶点落在凸包边内部
 *    而非顶点上，本实现的顶点限定在凸包顶点，是**上界近似**。因为结果只用来提供候选顶点、
 *    再由 forward 方向挑尖，这一差异不影响三角箭头的方向。无穷举三元组都装不下全部点时
 *    （非凸/异形 blob），退化为"面积最大的三个凸包顶点"。
 *  • **`approxPolyDP(closed)` → 标准 Douglas-Peucker（闭合分裂）**：OpenCV 对闭合轮廓先取
 *    离首点最远的点把环拆成两条开放链再各自 DP，这里同法实现。逐 eps 尝试
 *    `{0.05,0.04,0.06,0.03,0.08,0.02,0.10}`（顺序与 `:104` 完全一致），命中恰好 3 个角即取。
 *
 * 失败（找不到箭头 / 退化 / 越界）一律返回 `null`；上游返回 `-1.0`，调用方按"小地图被遮挡"
 * 处理（见 `MapLocator.cpp:1842-1858`）。
 */
object MapNavHeading {

    /** `MapAlgorithm.cpp:152` `int radius = 12;` —— 只在小地图中心 24×24 内采样。 */
    const val SAMPLE_RADIUS = 12

    /** `MapAlgorithm.cpp:170` `inRange(..., Scalar(220,220,220), Scalar(255,255,255))`。 */
    const val WHITE_MIN = 220

    /** `MapAlgorithm.cpp:201` `minDistSq > 25.0` —— 最近轮廓到 ROI 中心的平方距离上限。 */
    const val MAX_CENTER_DIST_SQ = 25.0

    /** `MapAlgorithm.cpp:210` `resize(..., 16.0, 16.0, INTER_CUBIC)`。 */
    const val UPSCALE = 16

    /** `MapAlgorithm.cpp:212` `threshold(..., 127, 255, THRESH_BINARY)`。 */
    const val UPSAMPLE_THRESHOLD = 127

    private data class Pt(val x: Double, val y: Double)

    /**
     * 从 BGR 交错像素里估玩家朝向：返回角度（度，0=正上/北，顺时针增长），失败返回 null。
     *
     * @param bgr 交错 BGR 字节（`b,g,r,b,g,r,...`），长度至少 `width*height*3`。
     */
    fun estimateFromBgr(bgr: ByteArray, width: Int, height: Int): Double? {
        if (width <= 0 || height <= 0) return null
        if (bgr.size < width * height * 3) return null

        val cx = width / 2
        val cy = height / 2
        val r = SAMPLE_RADIUS
        // MapAlgorithm.cpp:154-156 —— ROI 越界（图太小）直接失败。
        if (cx - r < 0 || cy - r < 0 || cx + r > width || cy + r > height) return null

        val pw = r * 2
        val ph = r * 2

        // MapAlgorithm.cpp:170 —— 白色掩膜（每通道 ∈[220,255]）。
        val mask = BooleanArray(pw * ph)
        for (py in 0 until ph) {
            val sy = cy - r + py
            for (px in 0 until pw) {
                val sx = cx - r + px
                val i = (sy * width + sx) * 3
                val b = bgr[i].toInt() and 0xFF
                val g = bgr[i + 1].toInt() and 0xFF
                val rr = bgr[i + 2].toInt() and 0xFF
                if (b >= WHITE_MIN && g >= WHITE_MIN && rr >= WHITE_MIN) {
                    mask[py * pw + px] = true
                }
            }
        }

        // MapAlgorithm.cpp:172-198 —— 外轮廓 + 取离中心最近的实心分量。
        val components = connectedComponents(mask, pw, ph)
        if (components.isEmpty()) return null

        val centerX = r.toDouble()
        val centerY = r.toDouble()
        var best: List<Pt>? = null
        var bestDistSq = Double.MAX_VALUE
        for (comp in components) {
            var sx = 0.0
            var sy = 0.0
            for (p in comp) {
                sx += p.x
                sy += p.y
            }
            val cxx = sx / comp.size
            val cyy = sy / comp.size
            val dSq = (cxx - centerX) * (cxx - centerX) + (cyy - centerY) * (cyy - centerY)
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                best = comp
            }
        }
        if (best == null || bestDistSq > MAX_CENTER_DIST_SQ) return null

        // MapAlgorithm.cpp:230-234 的质心。差异：上游用高分辨率边界轮廓的 moments，
        // 这里用选中分量的**填充面积质心**（对实心三角二者同落在对称轴上）。
        val comp = best
        var csx = 0.0
        var csy = 0.0
        for (p in comp) {
            csx += p.x
            csy += p.y
        }
        val centroid = Pt(csx / comp.size, csy / comp.size)

        // MapAlgorithm.cpp:236-241 —— 最小外接三角形的 3 个候选顶点。
        val triangle = minimumEnclosingTriangle(convexHull(comp)) ?: return null

        // MapAlgorithm.cpp:95-139 —— 凸包找最尖角得到 forward 方向。
        val forward = estimateArrowForward(comp, centroid)

        // MapAlgorithm.cpp:243-269 —— 与 forward 最同向的候选顶点当箭尖；
        // forward 失败则退化为离质心最远的顶点。
        var tip = triangle[0]
        if (forward != null) {
            var maxDot = -2.0
            for (v in triangle) {
                val dx = v.x - centroid.x
                val dy = v.y - centroid.y
                val n = hypot(dx, dy)
                val s = if (n < 1e-9) -1.0 else (dx * forward.x + dy * forward.y) / n
                if (s > maxDot) {
                    maxDot = s
                    tip = v
                }
            }
        } else {
            var maxDistSq = -1.0
            for (v in triangle) {
                val dx = v.x - centroid.x
                val dy = v.y - centroid.y
                val dSq = dx * dx + dy * dy
                if (dSq > maxDistSq) {
                    maxDistSq = dSq
                    tip = v
                }
            }
        }

        // MapAlgorithm.cpp:271-278 —— 角度约定：0=正上/北，顺时针增长。
        val dx = tip.x - centroid.x
        val dy = tip.y - centroid.y
        if (hypot(dx, dy) < 1e-6) return null

        var angleDeg = atan2(dx, -dy) * 180.0 / PI
        if (angleDeg < 0.0) angleDeg += 360.0
        return angleDeg
    }

    // ───────────────────────── 轮廓/几何辅助 ─────────────────────────

    /** 8 连通的白色前景分量（每个分量是填充像素集合）。 */
    private fun connectedComponents(mask: BooleanArray, w: Int, h: Int): List<List<Pt>> {
        val visited = BooleanArray(mask.size)
        val out = ArrayList<List<Pt>>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            val pts = ArrayList<Pt>()
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val x = idx % w
                val y = idx / w
                pts.add(Pt(x.toDouble(), y.toDouble()))
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val ni = ny * w + nx
                        if (mask[ni] && !visited[ni]) {
                            visited[ni] = true
                            stack.addLast(ni)
                        }
                    }
                }
            }
            out.add(pts)
        }
        return out
    }

    private fun cross(o: Pt, a: Pt, b: Pt): Double =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** 单调链凸包，去掉共线点，返回逆时针顶点环（无重复首点）。 */
    private fun convexHull(points: List<Pt>): List<Pt> {
        if (points.size <= 1) return points.toList()
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<Pt>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0.0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = ArrayList<Pt>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0.0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        if (lower.isNotEmpty()) lower.removeAt(lower.size - 1)
        if (upper.isNotEmpty()) upper.removeAt(upper.size - 1)
        lower.addAll(upper)
        return lower
    }

    /**
     * 最小外接三角形的 3 个顶点。三角形点集时精确；一般情况是"凸包顶点三元组"上界近似
     * （见文件头）。找不到任何能包住全部凸包点的三元组时，退化为面积最大的三个凸包顶点。
     */
    private fun minimumEnclosingTriangle(hull: List<Pt>): List<Pt>? {
        val n = hull.size
        if (n < 3) return null
        if (n == 3) return hull.toList()

        var contain: List<Pt>? = null
        var containArea = Double.MAX_VALUE
        var any: List<Pt>? = null
        var anyArea = -1.0
        for (i in 0 until n - 2) {
            val a = hull[i]
            for (j in i + 1 until n - 1) {
                val b = hull[j]
                for (k in j + 1 until n) {
                    val c = hull[k]
                    val area = abs(cross(a, b, c))
                    if (area > anyArea) {
                        anyArea = area
                        any = listOf(a, b, c)
                    }
                    if (area < containArea && containsAll(a, b, c, hull)) {
                        containArea = area
                        contain = listOf(a, b, c)
                    }
                }
            }
        }
        return contain ?: any
    }

    private fun containsAll(a: Pt, b: Pt, c: Pt, pts: List<Pt>): Boolean {
        val s = cross(a, b, c)
        if (abs(s) < 1e-9) return false
        val sign = if (s > 0) 1.0 else -1.0
        for (p in pts) {
            if (cross(a, b, p) * sign < -1e-9) return false
            if (cross(b, c, p) * sign < -1e-9) return false
            if (cross(c, a, p) * sign < -1e-9) return false
        }
        return true
    }

    /** `MapAlgorithm.cpp:95-139` —— 凸包 + `approxPolyDP` 取 3 角，返回最尖角到质心的单位向量。 */
    private fun estimateArrowForward(contour: List<Pt>, centroid: Pt): Pt? {
        val hull = convexHull(contour)
        if (hull.size < 3) return null

        val peri = arcLength(hull)
        // 顺序与 MapAlgorithm.cpp:104 完全一致。
        val cornerEps = doubleArrayOf(0.05, 0.04, 0.06, 0.03, 0.08, 0.02, 0.10)
        var corners: List<Pt>? = null
        for (eps in cornerEps) {
            val approx = approxPolyDp(hull, eps * peri, closed = true)
            if (approx.size == 3) {
                corners = approx
                break
            }
        }
        val c = corners ?: return null

        // MapAlgorithm.cpp:119-130 —— 找夹角余弦最大（角度最尖）的顶点。
        var sharpest = -1
        var maxCos = -2.0
        for (i in 0..2) {
            val v1x = c[(i + 2) % 3].x - c[i].x
            val v1y = c[(i + 2) % 3].y - c[i].y
            val v2x = c[(i + 1) % 3].x - c[i].x
            val v2y = c[(i + 1) % 3].y - c[i].y
            val n = sqrt((v1x * v1x + v1y * v1y) * (v2x * v2x + v2y * v2y))
            val cosv = if (n < 1e-9) -1.0 else (v1x * v2x + v1y * v2y) / n
            if (cosv > maxCos) {
                maxCos = cosv
                sharpest = i
            }
        }
        if (sharpest < 0) return null

        val fx = c[sharpest].x - centroid.x
        val fy = c[sharpest].y - centroid.y
        val n = hypot(fx, fy)
        if (n < 1e-6) return null
        return Pt(fx / n, fy / n)
    }

    /** `MapAlgorithm.cpp:105` `arcLength(hullPts, true)`。 */
    private fun arcLength(pts: List<Pt>): Double {
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += hypot(b.x - a.x, b.y - a.y)
        }
        return sum
    }

    /** Douglas-Peucker。闭合轮廓先按"离首点最远的点"拆成两条开放链（同 OpenCV）。 */
    private fun approxPolyDp(points: List<Pt>, eps: Double, closed: Boolean): List<Pt> {
        if (points.size <= 2) return points.toList()
        if (!closed) return douglasPeucker(points, eps)

        var far = 0
        var best = -1.0
        for (i in points.indices) {
            val dx = points[i].x - points[0].x
            val dy = points[i].y - points[0].y
            val d = dx * dx + dy * dy
            if (d > best) {
                best = d
                far = i
            }
        }
        if (far == 0) return points.toList()

        val first = ArrayList<Pt>(far + 1)
        for (i in 0..far) first.add(points[i])
        val second = ArrayList<Pt>(points.size - far + 1)
        for (i in far until points.size) second.add(points[i])
        second.add(points[0])

        val a = douglasPeucker(first, eps)
        val b = douglasPeucker(second, eps)
        val out = ArrayList<Pt>(a.size + b.size)
        out.addAll(a) // 首=points[0]，尾=points[far]
        for (i in 1 until b.size - 1) out.add(b[i]) // 去掉与 a 首尾重复的端点
        return out
    }

    private fun douglasPeucker(points: List<Pt>, eps: Double): List<Pt> {
        if (points.size < 3) return points.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val seg = stack.removeLast()
            val s = seg[0]
            val e = seg[1]
            if (e <= s + 1) continue
            var maxD = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = perpendicularDistance(points[i], points[s], points[e])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (maxD > eps && idx > 0) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicularDistance(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-12) return hypot(p.x - a.x, p.y - a.y)
        return abs(dx * (a.y - p.y) - (a.x - p.x) * dy) / len
    }
}
