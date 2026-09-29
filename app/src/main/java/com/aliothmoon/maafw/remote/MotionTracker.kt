package com.aliothmoon.maafw.remote

import kotlin.math.hypot
import kotlin.math.max

/**
 * 运动追踪器：速度 EMA、丢失计数、上一帧保留与「下一帧搜索窗」预测。
 *
 * 上游整份 `MotionTracker.cpp`（98 行）+ `MotionTracker.h`。这里不碰 `cv::Mat`：
 * 上游唯一的图像类型是返回的 `cv::Rect`，Kotlin 侧用 [MapRect] 表达。
 *
 * 时间统一用**单调时钟秒数** [Double]（对齐上游 `std::chrono::steady_clock::time_point`
 * 的差值 `dt.count()`，单位秒）；调用方负责传同一个时钟的读数。
 */
class MotionTracker(private val trackingCfg: TrackingConfig = TrackingConfig()) {

    private var lastKnownPos: MapPosition? = null

    /** 上游构造即 `MaxLostTrackingCount + 1`（`MotionTracker.cpp:10`）。 */
    private var lostTrackingCount: Int = MAX_LOST_TRACKING_COUNT + 1

    private var velocityX: Double = 0.0
    private var velocityY: Double = 0.0

    /** 单调时钟秒数，对应上游 `lastTime`。 */
    private var lastTime: Double = 0.0

    /**
     * 上游 `update`（`MotionTracker.cpp:16-39`）。
     *
     * 只有「已有上一帧 && 丢失计数为 0」时才估速：帧间位移小于死区则清零速度；
     * 否则仅在帧间隔合理（>0.016 且 < maxDtForPrediction）**且** 匹配分数达标
     * （>= kVelocityUpdateMinScore）时做 EMA。注意 `lastKnownPos`/`lastTime`/`lostTrackingCount=0`
     * 在**任何**情况下都会更新。
     */
    fun update(newPos: MapPosition, now: Double) {
        val last = lastKnownPos
        if (last != null && lostTrackingCount == 0) {
            val dtSec = now - lastTime
            val dx = newPos.x - last.x
            val dy = newPos.y - last.y
            if (hypot(dx, dy) < VELOCITY_DEADBAND) {
                velocityX = 0.0
                velocityY = 0.0
            } else if (dtSec > 0.016 && dtSec < trackingCfg.maxDtForPrediction &&
                newPos.score >= VELOCITY_UPDATE_MIN_SCORE
            ) {
                val rawVx = dx / dtSec
                val rawVy = dy / dtSec
                val alpha = trackingCfg.velocitySmoothingAlpha
                velocityX = velocityX * (1.0 - alpha) + rawVx * alpha
                velocityY = velocityY * (1.0 - alpha) + rawVy * alpha
            }
        }
        lastKnownPos = newPos
        lastTime = now
        lostTrackingCount = 0
    }

    /** 上游 `hold`（`MotionTracker.cpp:41-45`）：只保留位置与时间，**不动**丢失计数与速度。 */
    fun hold(oldPos: MapPosition, now: Double) {
        lastKnownPos = oldPos
        lastTime = now
    }

    /** 上游 `markLost`（`MotionTracker.cpp:47-50`）。 */
    fun markLost(increment: Int = 1) {
        lostTrackingCount += increment
    }

    /** 上游 `forceLost`（`MotionTracker.cpp:52-56`）。 */
    fun forceLost() {
        lostTrackingCount = MAX_LOST_TRACKING_COUNT + 100
        lastKnownPos = null
    }

    /** 上游 `isTracking`（`MotionTracker.cpp:58-61`）。 */
    fun isTracking(maxAllowedLost: Int): Boolean =
        lastKnownPos != null && lostTrackingCount <= maxAllowedLost

    fun getLastPos(): MapPosition? = lastKnownPos

    fun getLostCount(): Int = lostTrackingCount

    /** 上游 `getPredictedX`（`MotionTracker.cpp:63-74`）：无位置返回 0；超时不做速度外推。 */
    fun getPredictedX(now: Double): Double {
        val last = lastKnownPos ?: return 0.0
        val dtSec = now - lastTime
        if (dtSec > trackingCfg.maxDtForPrediction) return last.x
        return last.x + velocityX * dtSec
    }

    /** 上游 `getPredictedY`（`MotionTracker.cpp:76-87`）。 */
    fun getPredictedY(now: Double): Double {
        val last = lastKnownPos ?: return 0.0
        val dtSec = now - lastTime
        if (dtSec > trackingCfg.maxDtForPrediction) return last.y
        return last.y + velocityY * dtSec
    }

    fun getVelocityX(): Double = velocityX

    fun getVelocityY(): Double = velocityY

    fun getLastTime(): Double = lastTime

    /** 上游 `clearVelocity`（`MotionTracker.h:38-42`）。 */
    fun clearVelocity() {
        velocityX = 0.0
        velocityY = 0.0
    }

    /**
     * 上游 `predictNextSearchRect`（`MotionTracker.cpp:89-96`）。
     *
     * `pad = int(MobileSearchRadius + max(templCols, templRows) * trackScale / 2.0)`
     * ——注意 `static_cast<int>` 是**向零截断**，Kotlin `toInt()` 一致；
     * 预测中心 `(int)predX` 同样是向零截断。
     */
    fun predictNextSearchRect(trackScale: Double, templCols: Int, templRows: Int, now: Double): MapRect {
        val predX = getPredictedX(now)
        val predY = getPredictedY(now)
        val pad = (MOBILE_SEARCH_RADIUS + max(templCols, templRows) * trackScale / 2.0).toInt()
        return MapRect(
            x = predX.toInt() - pad,
            y = predY.toInt() - pad,
            width = pad * 2,
            height = pad * 2,
        )
    }
}
