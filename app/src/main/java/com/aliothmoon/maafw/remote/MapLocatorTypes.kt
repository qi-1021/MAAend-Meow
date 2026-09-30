package com.aliothmoon.maafw.remote

/**
 * MapLocator 纯逻辑层共享的数据类型与常量。
 *
 * 上游对应 `upstream/maaend/agent/cpp-algo/source/MapLocator/MapTypes.h`。
 * 这里只放**不碰 `cv::Mat`/ONNX/文件系统**的部分：坐标、裁决结果、配置常量、
 * 小地图 ROI 配置、模板缩放与热力图分区判定。所有涉及像素/图像的能力
 * （`TryExtractMinimap`、`MatchFeature`…）都刻意不移植到本文件。
 *
 * 行号均指 `MapTypes.h` 的原始行号。
 */

/** 上游 `MapPosition`（`MapTypes.h:13-22`）。 */
data class MapPosition(
    val zoneId: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val score: Double = 0.0,
    val sliceIndex: Int = 0,
    val angle: Double = 0.0,
    val latencyMs: Long = 0L,
)

/**
 * 上游 `SearchHint`（`MapTypes.h:36-44`）。调用方对「人大概在哪」的先验，
 * 坐标是 `zone_id` 那张图自己的像素。所有字段在 meojson 里都是**必填**：
 * `MEO_JSONIZATION(zone_id, x, y, radius)` 没有 `MEO_OPT`，缺一即整段解析失败。
 */
data class SearchHint(
    val zoneId: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val radius: Double = 0.0,
)

/** 上游 `LocateOptions`（`MapTypes.h:46-62`）。 */
data class LocateOptions(
    val locThreshold: Double = DEFAULT_LOC_THRESHOLD,
    val yoloThreshold: Double = DEFAULT_YOLO_THRESHOLD,
    val forceGlobalSearch: Boolean = false,
    val maxLostFrames: Int = DEFAULT_MAX_LOST_FRAMES,
    val expectedZoneId: String = "",
    val searchHints: List<SearchHint> = emptyList(),
) {
    companion object {
        /** 上游默认值 `loc_threshold = 0.55`。 */
        const val DEFAULT_LOC_THRESHOLD = 0.55

        /** 上游默认值 `yolo_threshold = 0.70`。 */
        const val DEFAULT_YOLO_THRESHOLD = 0.70

        /** 上游默认值 `max_lost_frames = 3`。 */
        const val DEFAULT_MAX_LOST_FRAMES = 3
    }
}

/** 上游 `LocateStatus`（`MapTypes.h:65-73`）。ordinal 即 `static_cast<int>(status)`。 */
enum class LocateStatus {
    SUCCESS,
    TRACKING_LOST,
    SCREEN_BLOCKED,
    TELEPORTED,
    YOLO_FAILED,
    NOT_INITIALIZED,
}

/** 上游 `CameraOrientation`（`MapTypes.h:77-81`）：rot ∈ [0,360)，confidence ∈ [0,1]。 */
data class CameraOrientation(
    val rot: Double = 0.0,
    val confidence: Double = 0.0,
)

/** 上游 `LocateResult`（`MapTypes.h:83-89`）。 */
data class LocateResult(
    val status: LocateStatus = LocateStatus.NOT_INITIALIZED,
    val position: MapPosition? = null,
    val debugMessage: String = "",
    val camRot: CameraOrientation? = null,
)

/** 上游 `GlobalSearchMode`（`MapTypes.h:91-95`）。 */
enum class GlobalSearchMode {
    FULL_MAP_FINE,
    ROI_FINE,
}

/**
 * 上游 `cv::Rect` 的纯逻辑替身（x, y, width, height）。
 *
 * `intersect` 与 [isEmpty] 严格对齐 OpenCV `Rect_::operator&` / `Rect_::empty()`：
 * 相交结果允许出现 **非正的 width/height**，`empty` 即 `width <= 0 || height <= 0`。
 */
data class MapRect(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    /** OpenCV `Rect_::operator&` 语义（`x2 = min(x+w, r.x+r.w)`，w 可为负）。 */
    fun intersect(other: MapRect): MapRect {
        val x1 = maxOf(x, other.x)
        val y1 = maxOf(y, other.y)
        val x2 = minOf(x + width, other.x + other.width)
        val y2 = minOf(y + height, other.y + other.height)
        return MapRect(x1, y1, x2 - x1, y2 - y1)
    }
}

/** 上游 `SearchConstraint`（`MapTypes.h:97-102`）。 */
data class SearchConstraint(
    val mode: GlobalSearchMode = GlobalSearchMode.FULL_MAP_FINE,
    val yoloValidated: Boolean = false,
    val roi: MapRect = MapRect(),
)

/** 上游 `YoloCoarseResult`（`MapTypes.h:104-120`）。 */
data class YoloCoarseResult(
    val valid: Boolean = false,
    val isNone: Boolean = false,
    val rawClass: String = "",
    val baseClass: String = "",
    val zoneId: String = "",
    val confidence: Float = 0.0f,
    val hasRoi: Boolean = false,
    val roiX: Int = 0,
    val roiY: Int = 0,
    val roiW: Int = 0,
    val roiH: Int = 0,
    val inferMargin: Int = 0,
)

/** 上游 `MinimapRoiConfig`（`MapTypes.h:122-128`）。 */
data class MinimapRoiConfig(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

/** 上游默认小地图 ROI（`MapTypes.h:131`）。 */
val DEFAULT_MINIMAP_ROI = MinimapRoiConfig(x = 49, y = 51, width = 118, height = 120)

/** 上游 `kAdbMinimapFullImageScale`（`MapTypes.h:132`）。 */
const val ADB_MINIMAP_FULL_IMAGE_SCALE = 0.8

/** 上游 `kAdbMinimapXOffset` / `kAdbMinimapYOffset`（`MapTypes.h:133-134`）。 */
const val ADB_MINIMAP_X_OFFSET = 0
const val ADB_MINIMAP_Y_OFFSET = -7

/** 上游 `kAdbMinimapRoi`（`MapTypes.h:135-140`）。 */
val ADB_MINIMAP_ROI = MinimapRoiConfig(
    x = DEFAULT_MINIMAP_ROI.x + ADB_MINIMAP_X_OFFSET,
    y = DEFAULT_MINIMAP_ROI.y + ADB_MINIMAP_Y_OFFSET,
    width = DEFAULT_MINIMAP_ROI.width,
    height = DEFAULT_MINIMAP_ROI.height,
)

/** 上游 `MinimapROIOriginX/Y`、`MinimapROIWidth/Height`（`MapTypes.h:141-144`）。 */
const val MINIMAP_ROI_ORIGIN_X = 49
const val MINIMAP_ROI_ORIGIN_Y = 51
const val MINIMAP_ROI_WIDTH = 118
const val MINIMAP_ROI_HEIGHT = 120

/**
 * 上游 `GetMinimapRoiConfig`（`MapTypes.h:146-149`）。
 *
 * @param useAdbMinimapRoi true 用 adb 偏移后的 ROI，false 用默认 ROI。
 */
fun getMinimapRoiConfig(useAdbMinimapRoi: Boolean): MinimapRoiConfig =
    if (useAdbMinimapRoi) ADB_MINIMAP_ROI else DEFAULT_MINIMAP_ROI

/** 上游 `MaxLostTrackingCount`（`MapTypes.h:174`）。 */
const val MAX_LOST_TRACKING_COUNT = 3

/**
 * 路径网区（`IsPathHeatmapZone`）的丢失上限放宽到 10。
 *
 * 上游是内联字面量 `IsPathHeatmapZone(...) ? 10 : options.max_lost_frames`
 * （`MapLocator.cpp:1008`、`1950`、`2036`）；这里提成命名常量，与
 * [MAX_LOST_TRACKING_COUNT] 并列，避免三处魔法数字漂移。
 */
const val PATH_HEATMAP_MAX_LOST_TRACKING_COUNT = 10

/** 上游 `MobileSearchRadius`（`MapTypes.h:175`）。 */
const val MOBILE_SEARCH_RADIUS = 50.0

/** 上游 `kColdStartConsensusFrames`（`MapTypes.h:178`）。 */
const val COLD_START_CONSENSUS_FRAMES = 3

/** 上游 `kPositionConsensusRadius`（`MapTypes.h:180`）。 */
const val POSITION_CONSENSUS_RADIUS = 12.0

/** 上游 `kFarJumpRejectDistance`（`MapTypes.h:181`）。 */
const val FAR_JUMP_REJECT_DISTANCE = 80.0

/** 上游 `kHighConfidenceOverride`（`MapTypes.h:182`）。 */
const val HIGH_CONFIDENCE_OVERRIDE = 0.85

/** 上游 `kColdStartCollectingMessage`（`MapTypes.h:183`）。 */
const val COLD_START_COLLECTING_MESSAGE = "Cold-start collecting."

/** 上游 `kSeamFallbackMinPeakScore`（`MapTypes.h:184`）。 */
const val SEAM_FALLBACK_MIN_PEAK_SCORE = 0.0

/** 上游 `kOcclusionRejectTimeoutMs`（`MapTypes.h:188`）。 */
const val OCCLUSION_REJECT_TIMEOUT_MS = 5000

/** 上游 `kFastTrackingPassScore`（`MapTypes.h:191`）。 */
const val FAST_TRACKING_PASS_SCORE = 0.75

/** 上游 `kStableDeadband`（`MapTypes.h:192`）。 */
const val STABLE_DEADBAND = 0.15

/** 上游 `kStableReleaseDist`（`MapTypes.h:193`）。 */
const val STABLE_RELEASE_DIST = 0.40

/** 上游 `kStableMinScore`（`MapTypes.h:194`）。 */
const val STABLE_MIN_SCORE = 0.80

/** 上游 `kTrackingHardScoreFloor`（`MapTypes.h:196`）。 */
const val TRACKING_HARD_SCORE_FLOOR = 0.60

/** 上游 `kVelocityUpdateMinScore`（`MapTypes.h:198`）。 */
const val VELOCITY_UPDATE_MIN_SCORE = 0.70

/** 上游 `kVelocityDeadband`（`MapTypes.h:199`）。 */
const val VELOCITY_DEADBAND = 0.25

/** 上游 `kTrackingOutlierDistance`（`MapTypes.h:201`）。 */
const val TRACKING_OUTLIER_DISTANCE = 25.0

/** 上游 `kTrackingOutlierMinScore`（`MapTypes.h:202`）。 */
const val TRACKING_OUTLIER_MIN_SCORE = 0.78

/** 上游 `kDualVerifyMinScore`（`MapTypes.h:204`）。 */
const val DUAL_VERIFY_MIN_SCORE = 0.45

/** 上游 `kDualVerifyMaxDistance`（`MapTypes.h:205`）。 */
const val DUAL_VERIFY_MAX_DISTANCE = 4.0

/** 上游 `kDualGlobalVerifyMinScore`（`MapTypes.h:206`）。 */
const val DUAL_GLOBAL_VERIFY_MIN_SCORE = 0.50

/** 上游 `kArbiterReclaimStreak`（`MapTypes.h:209`）。 */
const val ARBITER_RECLAIM_STREAK = 5

/** 上游 `kArbiterReclaimDriftDistance`（`MapTypes.h:210`）。 */
const val ARBITER_RECLAIM_DRIFT_DISTANCE = 6.0

/**
 * 上游 `ZoneTemplateScale`（`MapTypes.h:214-217`）。
 *
 * 小地图与底图的像素尺度比：只有 `ValleyIV_Base` 被放大过 16/15，其余为 1.0。
 */
fun zoneTemplateScale(zoneId: String): Double = if (zoneId == "ValleyIV_Base") 15.0 / 16.0 else 1.0

/**
 * 上游 `IsPathHeatmapZone`（`MapTypes.h:219-228`）。
 *
 * 判定 zoneId 是否包含标记 `OMVBase`（子串匹配，非全等）。
 */
fun isPathHeatmapZone(zoneId: String): Boolean = zoneId.contains("OMVBase")

/**
 * 上游 `TrackingConfig`（`MapTypes.h:230-237`）。
 */
data class TrackingConfig(
    /** px/s */
    val maxNormalSpeed: Double = 40.0,
    /** NCC correlation below this means blocked */
    val screenBlockedThreshold: Double = 0.4,
    val edgeSnapMargin: Int = 1,
    /** 平滑系数 */
    val velocitySmoothingAlpha: Double = 0.5,
    /** 超时则放弃速度预测 */
    val maxDtForPrediction: Double = 5.0,
)

/** 上游 `MatchConfig`（`MapTypes.h:239-244`）。 */
data class MatchConfig(
    /** 精搜半径(px) */
    val fineSearchRadius: Int = 40,
    /** 低于此分先跑第二策略和提示窗, 仍无更高峰则照样交付 */
    val passThreshold: Double = 0.55,
    val yoloConfThreshold: Double = 0.60,
)
