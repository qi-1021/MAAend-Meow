package com.aliothmoon.maafw.maa

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer

/**
 * MaaFramework C API 的 JNA 声明
 *
 * 官方没有 Java binding（`source/binding/` 只有 NodeJS 与 Python），这里按 `include/MaaFramework/` 的
 * 头文件手写，只声明本项目用到的子集。改动前先对照头文件，签名以头文件为准
 *
 * 类型映射：`MaaBool`(uint8) → Byte，`MaaId`/`MaaSize`(int64/uint64) → Long，`MaaStatus`(int32) → Int
 */
interface MaaFrameworkLibrary : Library {

    fun MaaVersion(): String?

    fun MaaGlobalSetOption(key: Int, value: Pointer?, valSize: Long): Byte

    // ── Resource ──

    fun MaaResourceCreate(): Pointer?

    fun MaaResourceDestroy(res: Pointer?)

    fun MaaResourceAddSink(res: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaResourcePostBundle(res: Pointer?, path: String): Long

    fun MaaResourceWait(res: Pointer?, id: Long): Int

    fun MaaResourceLoaded(res: Pointer?): Byte

    fun MaaResourceClear(res: Pointer?): Byte

    fun MaaResourceRegisterCustomAction(
        res: Pointer?,
        name: String,
        action: MaaCustomActionCallback?,
        transArg: Pointer?,
    ): Byte

    fun MaaResourceUnregisterCustomAction(
        res: Pointer?,
        name: String,
    ): Byte

    fun MaaResourceRegisterCustomRecognition(
        res: Pointer?,
        name: String,
        recognition: MaaCustomRecognitionCallback?,
        transArg: Pointer?,
    ): Byte

    fun MaaResourceUnregisterCustomRecognition(
        res: Pointer?,
        name: String,
    ): Byte

    // ── Context ──

    fun MaaContextRunTask(
        context: Pointer?,
        entry: String,
        pipelineOverride: String,
    ): Long

    fun MaaContextOverridePipeline(
        context: Pointer?,
        pipelineOverride: String,
    ): Byte

    fun MaaContextGetNodeData(
        context: Pointer?,
        nodeName: String,
        buffer: Pointer?,
    ): Byte

    fun MaaContextClearHitCount(
        context: Pointer?,
        nodeName: String,
    )

    fun MaaContextGetTasker(
        context: Pointer?,
    ): Pointer?

    /**
     * 运行时把模板名 [imageName] 指向内存里的图 [image]（一个 MaaImageBuffer），**不落盘**。
     *
     * 框架侧走 `Context::override_image` → `image_override_`（`Task/Context.cpp:254-260`），
     * 识别时 `Context::get_images` 先查这张覆盖表、命中就用内存图
     * （`Task/Context.cpp:369-389`），而 `run_recognition` 克隆 context 时会复制覆盖表
     * （拷贝构造 `Context.cpp:49-55`）——所以「先 OverrideImage、再 RunRecognition」
     * 这条时序在框架内是自洽的。这条正好绕过被缓存的 `TemplateResMgr`。
     *
     * 对应 `MaaBool MaaContextOverrideImage(MaaContext* context, const char* image_name, const MaaImageBuffer* image)`
     * （`include/MaaFramework/Instance/MaaContext.h`）。
     */
    fun MaaContextOverrideImage(
        context: Pointer?,
        imageName: String,
        image: Pointer?,
    ): Byte

    fun MaaContextRunRecognition(
        context: Pointer?,
        entry: String,
        pipelineOverride: String,
        image: Pointer?,
    ): Long

    fun MaaContextRunAction(
        context: Pointer?,
        entry: String,
        pipelineOverride: String,
        box: Pointer?,
        detail: String,
    ): Long

    // ── Controller ──

    fun MaaAndroidNativeControllerCreate(configJson: String): Pointer?

    fun MaaControllerDestroy(ctrl: Pointer?)

    fun MaaControllerAddSink(ctrl: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaControllerPostConnection(ctrl: Pointer?): Long

    fun MaaControllerPostClick(
        ctrl: Pointer?,
        x: Int,
        y: Int,
    ): Long

    fun MaaControllerPostSwipe(
        ctrl: Pointer?,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        duration: Int,
    ): Long

    /** contact = 手指 id；AndroidNativeControlUnit 直接注入输入事件，多指不冲突 */
    fun MaaControllerPostTouchDown(
        ctrl: Pointer?,
        contact: Int,
        x: Int,
        y: Int,
        pressure: Int,
    ): Long

    fun MaaControllerPostTouchMove(
        ctrl: Pointer?,
        contact: Int,
        x: Int,
        y: Int,
        pressure: Int,
    ): Long

    fun MaaControllerPostTouchUp(
        ctrl: Pointer?,
        contact: Int,
    ): Long

    fun MaaControllerPostScreencap(ctrl: Pointer?): Long

    fun MaaControllerWait(ctrl: Pointer?, id: Long): Int

    fun MaaControllerConnected(ctrl: Pointer?): Byte

    // ── Tasker ──

    fun MaaTaskerCreate(): Pointer?

    fun MaaTaskerDestroy(tasker: Pointer?)

    fun MaaTaskerAddSink(tasker: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaTaskerBindResource(tasker: Pointer?, res: Pointer?): Byte

    fun MaaTaskerBindController(tasker: Pointer?, ctrl: Pointer?): Byte

    fun MaaTaskerInited(tasker: Pointer?): Byte

    fun MaaTaskerPostTask(tasker: Pointer?, entry: String, pipelineOverride: String): Long

    fun MaaTaskerStatus(tasker: Pointer?, id: Long): Int

    fun MaaTaskerWait(tasker: Pointer?, id: Long): Int

    fun MaaTaskerRunning(tasker: Pointer?): Byte

    fun MaaTaskerPostStop(tasker: Pointer?): Long

    fun MaaTaskerStopping(tasker: Pointer?): Byte

    fun MaaTaskerGetRecognitionDetail(
        tasker: Pointer?,
        recoId: Long,
        nodeName: Pointer?,
        algorithm: Pointer?,
        hit: Pointer?,
        box: Pointer?,
        detailJson: Pointer?,
        raw: Pointer?,
        draws: Pointer?,
    ): Byte

    // ── StringBuffer ──
    // MaaAgentClient 的 identifier 走的是 buffer 而不是 char*，用完必须 Destroy

    fun MaaStringBufferCreate(): Pointer?

    fun MaaStringBufferDestroy(handle: Pointer?)

    fun MaaStringBufferGet(handle: Pointer?): String?

    // ── Rect ──

    fun MaaRectCreate(): Pointer?

    fun MaaRectDestroy(handle: Pointer?)

    fun MaaRectGetX(handle: Pointer?): Int

    fun MaaRectGetY(handle: Pointer?): Int

    fun MaaRectGetW(handle: Pointer?): Int

    fun MaaRectGetH(handle: Pointer?): Int

    fun MaaRectSet(handle: Pointer?, x: Int, y: Int, w: Int, h: Int): Byte

    // ── ImageBuffer ──
    // focus 模板的 {image} 占位符取的是 controller 手里那张缓存帧

    fun MaaImageBufferCreate(): Pointer?

    fun MaaImageBufferDestroy(handle: Pointer?)

    fun MaaImageBufferIsEmpty(handle: Pointer?): Byte

    /**
     * 把裸像素写进 image buffer（**不落盘**），是「内存造图」的唯一入口。
     *
     * 签名与参数顺序照抄上游 Go 绑定
     * `internal/native/framework.go:311`：
     * `MaaImageBufferSetRawData(handle uintptr, data unsafe.Pointer, width, height, imageType int32) bool`
     * → `MaaBool MaaImageBufferSetRawData(MaaImageBuffer* handle, const void* data, int32_t width, int32_t height, int32_t image_type)`。
     *
     * [data] 指向 `width*height*channels` 字节的连续内存，[imageType] 用 [MaaImageType] 里的
     * OpenCV `cv::Mat::type()` 取值。Go 绑定 `buffer/image_buffer.go` 的 `Set` 就是按
     * BGR 三通道 + `cvType8UC3=16` 调的，所以 [MaaImageType.CV_8UC3] 的数据按 BGR 排列。
     *
     * 注：Go 绑定**故意不绑** `MaaImageBufferSetEncoded`（`framework.go:313-315` 有明确注释），
     * 所以本项目只走 SetRawData 这条路径。
     */
    fun MaaImageBufferSetRawData(
        handle: Pointer?,
        data: Pointer?,
        width: Int,
        height: Int,
        imageType: Int,
    ): Byte

    /** 拿的是编码后（PNG）的字节，不是裸位图；长度另取 [MaaImageBufferGetEncodedSize] */
    fun MaaImageBufferGetEncoded(handle: Pointer?): Pointer?

    fun MaaImageBufferGetEncodedSize(handle: Pointer?): Long

    /**
     * 取 controller 的缓存截图
     *
     * 尺寸按 controller 的 screenshot target size 缩放过，与设备物理分辨率未必一致
     */
    fun MaaControllerCachedImage(ctrl: Pointer?, buffer: Pointer?): Byte

    /**
     * 对应 `MaaEventCallback`：`void(void* handle, const char* message, const char* details_json, void* trans_arg)`
     * 由 native 线程回调，实现里不得阻塞，也不得抛异常穿回 native
     */
    fun interface MaaEventCallback : Callback {
        operator fun invoke(handle: Pointer?, message: String?, details: String?, transArg: Pointer?)
    }

    /**
     * 对应 `MaaCustomActionCallback`：
     * `MaaBool(MaaContext* context, MaaTaskId task_id, const char* node_name, const char* custom_action_name, const char* custom_action_param, MaaRecoId reco_id, const MaaRect* box, void* trans_arg)`
     */
    fun interface MaaCustomActionCallback : Callback {
        operator fun invoke(
            context: Pointer?,
            taskId: Long,
            nodeName: String?,
            customActionName: String?,
            customActionParam: String?,
            recoId: Long,
            box: Pointer?,
            transArg: Pointer?,
        ): Byte
    }

    /**
     * 对应 `MaaCustomRecognitionCallback`：
     * `MaaBool(MaaContext* context, MaaTaskId task_id, const char* node_name, const char* custom_recognition_name, const char* custom_recognition_param, const MaaImageBuffer* image, const MaaRect* roi, void* trans_arg, MaaRect* out_box, MaaStringBuffer* out_detail)`
     */
    fun interface MaaCustomRecognitionCallback : Callback {
        operator fun invoke(
            context: Pointer?,
            taskId: Long,
            nodeName: String?,
            customRecognitionName: String?,
            customRecognitionParam: String?,
            image: Pointer?,
            roi: Pointer?,
            transArg: Pointer?,
            outBox: Pointer?,
            outDetail: Pointer?,
        ): Byte
    }
}

/** `MaaStatusEnum` */
object MaaStatus {
    const val INVALID = 0
    const val PENDING = 1000
    const val RUNNING = 2000
    const val SUCCEEDED = 3000
    const val FAILED = 4000

    fun isDone(status: Int): Boolean = status == SUCCEEDED || status == FAILED
}

/** 本项目用到的 `MaaGlobalOptionEnum` */
object MaaGlobalOption {
    const val LOG_DIR = 1
    const val SAVE_DRAW = 2
    const val STDOUT_LEVEL = 4
    const val DEBUG_MODE = 6
    const val SAVE_ON_ERROR = 7
}

/**
 * `MaaImageBufferSetRawData` 的 imageType 参数取值，即 OpenCV `cv::Mat::type()`。
 *
 * 来源：上游 Go 绑定 `internal/buffer/image_buffer.go` 顶部
 * `const cvType8UC3 int32 = 16`，`Set()` 以它调用 `MaaImageBufferSetRawData`。
 * 与 OpenCV 的 `CV_8UC3 = CV_MAKETYPE(CV_8U, 3) = 0 + (3-1)<<3 = 16` 一致。
 * 该常量在随包 `MaaDef.h` 里没有单独枚举，故以 Go 绑定为准。
 */
object MaaImageType {
    /** BGR 三通道 8 位；MaaImageBuffer 内部即此格式。 */
    const val CV_8UC3 = 16
}

/** `MaaLoggingLevelEnum` */
object MaaLoggingLevel {
    const val OFF = 0
    const val ERROR = 2
    const val INFO = 4
    const val DEBUG = 5
}

/** `MaaMsg.h` 里本项目会分派的消息名 */
object MaaMsg {
    const val RESOURCE_LOADING_STARTING = "Resource.Loading.Starting"
    const val RESOURCE_LOADING_SUCCEEDED = "Resource.Loading.Succeeded"
    const val RESOURCE_LOADING_FAILED = "Resource.Loading.Failed"

    const val CONTROLLER_ACTION_STARTING = "Controller.Action.Starting"
    const val CONTROLLER_ACTION_SUCCEEDED = "Controller.Action.Succeeded"
    const val CONTROLLER_ACTION_FAILED = "Controller.Action.Failed"

    const val TASKER_TASK_STARTING = "Tasker.Task.Starting"
    const val TASKER_TASK_SUCCEEDED = "Tasker.Task.Succeeded"
    const val TASKER_TASK_FAILED = "Tasker.Task.Failed"

    const val NODE_PIPELINE_NODE_STARTING = "Node.PipelineNode.Starting"
    const val NODE_PIPELINE_NODE_SUCCEEDED = "Node.PipelineNode.Succeeded"
    const val NODE_PIPELINE_NODE_FAILED = "Node.PipelineNode.Failed"

    const val NODE_ACTION_STARTING = "Node.Action.Starting"
    const val NODE_ACTION_SUCCEEDED = "Node.Action.Succeeded"
    const val NODE_ACTION_FAILED = "Node.Action.Failed"
}
