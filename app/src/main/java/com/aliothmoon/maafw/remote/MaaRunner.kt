package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.maa.MaaAgentClientLibrary
import com.aliothmoon.maafw.maa.MaaAgentClientLoader
import com.aliothmoon.maafw.maa.MaaFrameworkLibrary
import com.aliothmoon.maafw.maa.MaaFrameworkLoader
import com.aliothmoon.maafw.maa.MaaGlobalOption
import com.aliothmoon.maafw.maa.MaaLoggingLevel
import com.aliothmoon.maafw.maa.MaaStatus
import com.aliothmoon.maafw.remote.internal.PrimaryDisplayManager
import com.aliothmoon.maafw.remote.internal.VirtualDisplayManager
import com.aliothmoon.maafw.runner.AgentPayload
import com.aliothmoon.maafw.runner.RunOutcome
import com.aliothmoon.maafw.runner.RunPlanPayload
import com.aliothmoon.maafw.runner.RuntimeTaskPayload
import com.aliothmoon.maafw.runner.runPlanWireJson
import com.aliothmoon.maafw.third.Ln
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 特权进程内的 MaaFramework 执行器
 *
 * native handle 全部只存在于这里；app 侧只经 binder 拿事件与结果
 * 单工作线程串行：MaaFramework 的一个 Tasker 同时只跑一轮
 */
class MaaRunner(private val agentHost: AgentHost) {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "maa-runner").apply { isDaemon = true }
    }

    /** Binder stop can arrive while the worker is still preparing native handles. */
    private val lifecycleLock = Any()
    private var running = false
    private var stopRequested = false
    private val callbackRef = AtomicReference<IMaaRunnerCallback?>()

    // native handle
    private var resource: Pointer? = null
    private var controller: Pointer? = null
    private var tasker: Pointer? = null

    /** 已构建的 resource 对应的路径；变了就重建 */
    private var loadedResourcePaths: List<String> = emptyList()

    /**
     * 已建 controller 绑定的 display_id；变了必须重建
     *
     * 光判 `MaaControllerConnected` 不够：切运行模式后旧 controller 仍报 connected，
     * 但它的 display_id 指向的屏已经销毁了，`start_app` 会拿着废 id 去 launchDisplayId
     * 而被系统拒（`SecurityException: Permission Denial ... with launchDisplayId=<旧 id>`）
     */
    private var boundDisplayId: Int? = null
    private var boundResolution: Pair<Int, Int>? = null
    private var boundInferenceDevice: String? = null

    /** agent child 的 cwd，对齐上游 MaaPiCli 的 `agent.cwd = resource_dir_` */
    private var projectRoot: String? = null

    /** 与 resource 同生命周期：client 绑在 resource 上，resource 重建则整批重来 */
    private var agents: List<ActiveAgent> = emptyList()
    private var loadedAgents: List<AgentPayload> = emptyList()

    private class ActiveAgent(val client: Pointer, val session: AgentSession)

    fun setProjectRoot(path: String) {
        projectRoot = path
    }

    /** JNA 回调必须被强引用住，否则会被 GC，native 回调时踩空 */
    private val eventSink = MaaFrameworkLibrary.MaaEventCallback { _, message, detailsJson, _ ->
        Ln.i("MaaEventCallback on $message")
        runCatching {
            callbackRef.get()?.onEvent(message.orEmpty(), detailsJson.orEmpty())
        }.onFailure {
            // 回调穿回 native 会直接崩进程
            Ln.w("MaaRunner: event dispatch failed: ${it.message}")
        }
    }

    private val subTaskCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null || customActionParam.isNullOrBlank()) {
            Ln.w("MaaRunner: SubTask received empty context or param for node=$nodeName")
            return@MaaCustomActionCallback 0
        }
        try {
            val json = Json.parseToJsonElement(customActionParam).jsonObject
            val subTasks = json["sub"]?.jsonArray?.mapNotNull {
                (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            } ?: emptyList()
            if (subTasks.isEmpty()) {
                Ln.w("MaaRunner: SubTask has empty sub task list: $customActionParam")
                return@MaaCustomActionCallback 0
            }
            val continueOnFailure = json["continue"]?.jsonPrimitive?.booleanOrNull ?: false
            val strict = json["strict"]?.jsonPrimitive?.booleanOrNull ?: true
            val randomChoice = json["random_choice"]?.jsonPrimitive?.intOrNull

            val targetTasks = if (randomChoice != null && randomChoice > 0 && subTasks.size > randomChoice) {
                subTasks.shuffled().take(randomChoice)
            } else {
                subTasks
            }

            var hasSubFailure = false
            for (sub in targetTasks) {
                if (isStopRequested()) {
                    Ln.i("MaaRunner: SubTask interrupted by stop request")
                    return@MaaCustomActionCallback 0
                }
                Ln.i("MaaRunner: SubTask [$nodeName] -> executing subtask '$sub'")
                val subId = lib.MaaContextRunTask(context, sub, "{}")
                if (subId <= 0L) {
                    Ln.w("MaaRunner: SubTask subtask '$sub' failed (id=$subId)")
                    hasSubFailure = true
                    if (!continueOnFailure) {
                        break
                    }
                } else {
                    Ln.i("MaaRunner: SubTask subtask '$sub' succeeded (id=$subId)")
                }
            }
            if (hasSubFailure && strict) 0 else 1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: SubTask execution error on node=$nodeName", t)
            0
        }
    }

    private val clearHitCountCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, _, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 1
        if (context == null || customActionParam.isNullOrBlank()) return@MaaCustomActionCallback 1
        try {
            val json = Json.parseToJsonElement(customActionParam).jsonObject
            val nodes = json["nodes"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }
            nodes?.forEach { node ->
                lib.MaaContextClearHitCount(context, node)
            }
        } catch (t: Throwable) {
            Ln.w("MaaRunner: ClearHitCount error", t)
        }
        1
    }

    private val falseActionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, _, _, _, _, _, _ ->
        0
    }

    private val postStopCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, _, _, _, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 1
        synchronized(lifecycleLock) {
            tasker?.let(lib::MaaTaskerPostStop)
        }
        1
    }

    private data class RectData(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun getBoxRect(lib: MaaFrameworkLibrary, box: Pointer?): RectData {
        if (box == null) return RectData(0, 0, 0, 0)
        return try {
            val x = lib.MaaRectGetX(box)
            val y = lib.MaaRectGetY(box)
            val w = lib.MaaRectGetW(box)
            val h = lib.MaaRectGetH(box)
            RectData(x, y, w, h)
        } catch (_: Throwable) {
            try {
                RectData(box.getInt(0), box.getInt(4), box.getInt(8), box.getInt(12))
            } catch (_: Throwable) {
                RectData(0, 0, 0, 0)
            }
        }
    }

    private val autoAltClickCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, box, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val rect = getBoxRect(lib, box)
            var x = rect.x
            var y = rect.y
            var w = rect.w
            var h = rect.h
            if (!customActionParam.isNullOrBlank()) {
                runCatching {
                    val json = Json.parseToJsonElement(customActionParam).jsonObject
                    val offset = json["target_offset"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    if (offset != null && offset.size == 4) {
                        x += offset[0]
                        y += offset[1]
                        w += offset[2]
                        h += offset[3]
                    }
                }
            }
            if (w <= 0 && h <= 0 && x == 0 && y == 0) {
                Ln.w("MaaRunner: AutoAltClickAction [$nodeName] box is empty/zero, skipping click")
                return@MaaCustomActionCallback 1
            }
            val cx = (if (w > 0) x + w / 2 else x).coerceIn(0, 4000)
            val cy = (if (h > 0) y + h / 2 else y).coerceIn(0, 4000)
            Ln.i("MaaRunner: AutoAltClickAction [$nodeName] click ($cx, $cy)")
            val clickBox = lib.MaaRectCreate()
            try {
                if (clickBox != null) {
                    lib.MaaRectSet(clickBox, cx, cy, 1, 1)
                }
                lib.MaaContextRunAction(context, "__AutoAltClickMouseClickAction", "{}", clickBox ?: box, "")
            } finally {
                if (clickBox != null) {
                    lib.MaaRectDestroy(clickBox)
                }
            }
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: AutoAltClickAction error on node=$nodeName", t)
            0
        }
    }

    private val autoCtrlClickCallback = autoAltClickCallback

    private val autoAltSwipeCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, box, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val rect = getBoxRect(lib, box)
            var x1 = rect.x
            var y1 = rect.y
            var x2 = x1
            var y2 = y1
            var duration = 500
            if (!customActionParam.isNullOrBlank()) {
                runCatching {
                    val json = Json.parseToJsonElement(customActionParam).jsonObject
                    val begin = json["begin"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    if (begin != null && begin.size >= 2) {
                        x1 = begin[0]
                        y1 = begin[1]
                    }
                    val end = json["end"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    if (end != null && end.size >= 2) {
                        x2 = end[0]
                        y2 = end[1]
                    }
                    json["duration"]?.jsonPrimitive?.intOrNull?.let { duration = it }
                }
            }
            x1 = x1.coerceIn(0, 4000)
            y1 = y1.coerceIn(0, 4000)
            x2 = x2.coerceIn(0, 4000)
            y2 = y2.coerceIn(0, 4000)
            Ln.i("MaaRunner: AutoAltSwipeAction [$nodeName] swipe ($x1, $y1)->($x2, $y2) duration=$duration")
            val overrideJson = """{"__AutoAltSwipeMouseSwipeAction":{"begin":[$x1,$y1],"end":[$x2,$y2],"duration":$duration}}"""
            lib.MaaContextRunAction(context, "__AutoAltSwipeMouseSwipeAction", overrideJson, box, "")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: AutoAltSwipeAction error on node=$nodeName", t)
            0
        }
    }

    private val menuListClickItemCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, _, _, box, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val rect = getBoxRect(lib, box)
            if (rect.w <= 0 && rect.x == 0 && rect.y == 0) {
                Ln.w("MaaRunner: SceneManagerMenuListClickItemAction [$nodeName] box is empty/zero, skipping click")
                return@MaaCustomActionCallback 1
            }
            val cx = (rect.x + rect.w / 2).coerceIn(0, 4000)
            val cy = (rect.y - 15).coerceIn(0, 4000)
            Ln.i("MaaRunner: SceneManagerMenuListClickItemAction [$nodeName] click ($cx, $cy)")
            val clickBox = lib.MaaRectCreate()
            try {
                if (clickBox != null) {
                    lib.MaaRectSet(clickBox, cx, cy, 1, 1)
                }
                lib.MaaContextRunAction(context, "__SceneClickAction", "{}", clickBox ?: box, "")
            } finally {
                if (clickBox != null) {
                    lib.MaaRectDestroy(clickBox)
                }
            }
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: SceneManagerMenuListClickItemAction error on node=$nodeName", t)
            0
        }
    }

    private val repeatUntilFoundCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, box, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val rawJson = if (!customActionParam.isNullOrBlank()) {
                runCatching { Json.parseToJsonElement(customActionParam).jsonObject }.getOrNull()
            } else null
            val innerParamObj = rawJson?.get("custom_action_param")?.jsonObject
            val waitNodes = rawJson?.get("wait_nodes")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: innerParamObj?.get("wait_nodes")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            if (waitNodes.isEmpty()) {
                Ln.w("MaaRunner: RepeatUntilFoundAction has empty wait_nodes for node=$nodeName")
                return@MaaCustomActionCallback 0
            }
            val repeatCount = rawJson?.get("repeat_count")?.jsonPrimitive?.intOrNull
                ?: innerParamObj?.get("repeat_count")?.jsonPrimitive?.intOrNull
                ?: 3
            val intervalMs = (rawJson?.get("interval_ms")?.jsonPrimitive?.longOrNull
                ?: innerParamObj?.get("interval_ms")?.jsonPrimitive?.longOrNull
                ?: 3000L).coerceAtLeast(500L)
            val action = rawJson?.get("action")?.jsonPrimitive?.contentOrNull
                ?: innerParamObj?.get("action")?.jsonPrimitive?.contentOrNull
            val customAction = rawJson?.get("custom_action")?.jsonPrimitive?.contentOrNull
                ?: innerParamObj?.get("custom_action")?.jsonPrimitive?.contentOrNull
            val ctrl = controller
            val tasker = lib.MaaContextGetTasker(context)

            for (attempt in 1..repeatCount) {
                if (isStopRequested() || (tasker != null && lib.MaaTaskerStopping(tasker).toInt() != 0)) {
                    return@MaaCustomActionCallback 0
                }

                // 1. 执行内部动作，通过 MaaContextRunAction 分发，避免直接阻塞 controller
                val clickBox = lib.MaaRectCreate()
                try {
                    val rect = getBoxRect(lib, box)
                    var x = rect.x
                    var y = rect.y
                    var w = rect.w
                    var h = rect.h
                    val offset = (rawJson?.get("target_offset") as? JsonArray
                        ?: innerParamObj?.get("target_offset") as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    if (offset != null && offset.size == 4) {
                        x += offset[0]
                        y += offset[1]
                        w += offset[2]
                        h += offset[3]
                    }
                    val cx = (if (w > 0) x + w / 2 else x).coerceIn(0, 4000)
                    val cy = (if (h > 0) y + h / 2 else y).coerceIn(0, 4000)
                    if (clickBox != null) {
                        lib.MaaRectSet(clickBox, cx, cy, 1, 1)
                    }

                    if (customAction == "AutoAltClickAction" || action == "Click" || customAction == "AutoCtrlClickAction" || (customAction == null && action == null)) {
                        Ln.i("MaaRunner: RepeatUntilFoundAction [$nodeName] attempt $attempt/$repeatCount click ($cx, $cy)")
                        val innerOverride = """{"__RepeatUntilActionInner":{"pre_delay":0,"post_delay":0,"rate_limit":0,"action":"Click"}}"""
                        lib.MaaContextRunAction(context, "__RepeatUntilActionInner", innerOverride, clickBox ?: box, "")
                    } else if (!customAction.isNullOrBlank()) {
                        val innerOverride = """{"__RepeatUntilActionInner":{"pre_delay":0,"post_delay":0,"rate_limit":0,"action":"Custom","custom_action":"$customAction"}}"""
                        lib.MaaContextRunAction(context, "__RepeatUntilActionInner", innerOverride, clickBox ?: box, "")
                    }
                } finally {
                    if (clickBox != null) {
                        lib.MaaRectDestroy(clickBox)
                    }
                }

                // 2. 在 intervalMs 时间窗口内轮询等待目标节点命中（仅通过截图和识别检测，绝不递归调用 RunTask）
                val deadline = System.currentTimeMillis() + intervalMs
                while (System.currentTimeMillis() < deadline) {
                    if (isStopRequested() || (tasker != null && lib.MaaTaskerStopping(tasker).toInt() != 0)) {
                        return@MaaCustomActionCallback 0
                    }
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                        return@MaaCustomActionCallback 0
                    }

                    if (ctrl != null) {
                        val capId = lib.MaaControllerPostScreencap(ctrl)
                        if (capId > 0) lib.MaaControllerWait(ctrl, capId)
                    }

                    val imgBuf = lib.MaaImageBufferCreate()
                    var matched = false
                    try {
                        if (ctrl != null && imgBuf != null && lib.MaaControllerCachedImage(ctrl, imgBuf).toInt() != 0 && lib.MaaImageBufferIsEmpty(imgBuf).toInt() == 0) {
                            for (waitNode in waitNodes) {
                                val recoId = lib.MaaContextRunRecognition(context, waitNode, "{}", imgBuf)
                                if (recoId > 0L && tasker != null) {
                                    val hitMem = Memory(1)
                                    val tempRect = lib.MaaRectCreate()
                                    try {
                                        if (lib.MaaTaskerGetRecognitionDetail(tasker, recoId, null, null, hitMem, tempRect, null, null, null).toInt() != 0) {
                                            if (hitMem.getByte(0).toInt() != 0) {
                                                Ln.i("MaaRunner: RepeatUntilFoundAction [$nodeName] waitNode='$waitNode' matched via reco (attempt $attempt)")
                                                matched = true
                                                break
                                            }
                                        }
                                    } finally {
                                        if (tempRect != null) lib.MaaRectDestroy(tempRect)
                                    }
                                }
                            }
                        }
                    } finally {
                        if (imgBuf != null) lib.MaaImageBufferDestroy(imgBuf)
                    }

                    if (matched) return@MaaCustomActionCallback 1
                }
            }
            Ln.w("MaaRunner: RepeatUntilFoundAction [$nodeName] wait_nodes=$waitNodes not found after $repeatCount attempts")
            0
        } catch (t: Throwable) {
            Ln.e("MaaRunner: RepeatUntilFoundAction error on node=$nodeName", t)
            0
        }
    }

    private val repeatUntilNotFoundCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, box, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val rawJson = if (!customActionParam.isNullOrBlank()) {
                runCatching { Json.parseToJsonElement(customActionParam).jsonObject }.getOrNull()
            } else null
            val innerParamObj = rawJson?.get("custom_action_param")?.jsonObject
            val waitNode = rawJson?.get("wait_node")?.jsonPrimitive?.contentOrNull
                ?: innerParamObj?.get("wait_node")?.jsonPrimitive?.contentOrNull
                ?: rawJson?.get("wait_nodes")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: innerParamObj?.get("wait_nodes")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            if (waitNode.isNullOrBlank()) return@MaaCustomActionCallback 1
            val repeatCount = rawJson?.get("repeat_count")?.jsonPrimitive?.intOrNull
                ?: innerParamObj?.get("repeat_count")?.jsonPrimitive?.intOrNull
                ?: 3
            val intervalMs = (rawJson?.get("interval_ms")?.jsonPrimitive?.longOrNull
                ?: innerParamObj?.get("interval_ms")?.jsonPrimitive?.longOrNull
                ?: 3000L).coerceAtLeast(500L)
            val action = rawJson?.get("action")?.jsonPrimitive?.contentOrNull
                ?: innerParamObj?.get("action")?.jsonPrimitive?.contentOrNull
            val customAction = rawJson?.get("custom_action")?.jsonPrimitive?.contentOrNull
                ?: innerParamObj?.get("custom_action")?.jsonPrimitive?.contentOrNull
            val ctrl = controller
            val tasker = lib.MaaContextGetTasker(context)

            for (attempt in 1..repeatCount) {
                if (isStopRequested() || (tasker != null && lib.MaaTaskerStopping(tasker).toInt() != 0)) {
                    return@MaaCustomActionCallback 0
                }

                // 1. 执行内部动作，通过 MaaContextRunAction 分发
                val clickBox = lib.MaaRectCreate()
                try {
                    val rect = getBoxRect(lib, box)
                    val cx = (if (rect.w > 0) rect.x + rect.w / 2 else rect.x).coerceIn(0, 4000)
                    val cy = (if (rect.h > 0) rect.y + rect.h / 2 else rect.y).coerceIn(0, 4000)
                    if (clickBox != null) {
                        lib.MaaRectSet(clickBox, cx, cy, 1, 1)
                    }

                    if (customAction == "AutoAltClickAction" || action == "Click" || customAction == "AutoCtrlClickAction" || (customAction == null && action == null)) {
                        Ln.i("MaaRunner: RepeatUntilNotFoundAction [$nodeName] attempt $attempt/$repeatCount click ($cx, $cy)")
                        val innerOverride = """{"__RepeatUntilActionInner":{"pre_delay":0,"post_delay":0,"rate_limit":0,"action":"Click"}}"""
                        lib.MaaContextRunAction(context, "__RepeatUntilActionInner", innerOverride, clickBox ?: box, "")
                    } else if (!customAction.isNullOrBlank()) {
                        val innerOverride = """{"__RepeatUntilActionInner":{"pre_delay":0,"post_delay":0,"rate_limit":0,"action":"Custom","custom_action":"$customAction"}}"""
                        lib.MaaContextRunAction(context, "__RepeatUntilActionInner", innerOverride, clickBox ?: box, "")
                    }
                } finally {
                    if (clickBox != null) {
                        lib.MaaRectDestroy(clickBox)
                    }
                }

                // 2. 轮询等待目标节点消失
                val deadline = System.currentTimeMillis() + intervalMs
                while (System.currentTimeMillis() < deadline) {
                    if (isStopRequested() || (tasker != null && lib.MaaTaskerStopping(tasker).toInt() != 0)) {
                        return@MaaCustomActionCallback 0
                    }
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                        return@MaaCustomActionCallback 0
                    }

                    if (ctrl != null) {
                        val capId = lib.MaaControllerPostScreencap(ctrl)
                        if (capId > 0) lib.MaaControllerWait(ctrl, capId)
                    }

                    val imgBuf = lib.MaaImageBufferCreate()
                    var stillHit = false
                    try {
                        if (ctrl != null && imgBuf != null && lib.MaaControllerCachedImage(ctrl, imgBuf).toInt() != 0 && lib.MaaImageBufferIsEmpty(imgBuf).toInt() == 0) {
                            val recoId = lib.MaaContextRunRecognition(context, waitNode, "{}", imgBuf)
                            if (recoId > 0L && tasker != null) {
                                val hitMem = Memory(1)
                                val tempRect = lib.MaaRectCreate()
                                try {
                                    if (lib.MaaTaskerGetRecognitionDetail(tasker, recoId, null, null, hitMem, tempRect, null, null, null).toInt() != 0) {
                                        if (hitMem.getByte(0).toInt() != 0) {
                                            stillHit = true
                                        }
                                    }
                                } finally {
                                    if (tempRect != null) lib.MaaRectDestroy(tempRect)
                                }
                            }
                        }
                    } finally {
                        if (imgBuf != null) lib.MaaImageBufferDestroy(imgBuf)
                    }

                    if (!stillHit) {
                        Ln.i("MaaRunner: RepeatUntilNotFoundAction [$nodeName] node '$waitNode' disappeared (attempt $attempt)")
                        return@MaaCustomActionCallback 1
                    }
                }
            }
            0
        } catch (t: Throwable) {
            Ln.e("MaaRunner: RepeatUntilNotFoundAction error on node=$nodeName", t)
            0
        }
    }

    private val pipelineOverrideCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 1
        if (context == null || customActionParam.isNullOrBlank()) return@MaaCustomActionCallback 1
        try {
            val json = Json.parseToJsonElement(customActionParam).jsonObject
            val patch = json["patch"] ?: json
            lib.MaaContextOverridePipeline(context, patch.toString())
            Ln.i("MaaRunner: PipelineOverrideAction [$nodeName] applied patch")
        } catch (t: Throwable) {
            Ln.w("MaaRunner: PipelineOverrideAction error on node=$nodeName", t)
        }
        1
    }

    private val attachToExpectedRegexCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 1
        if (context == null || customActionParam.isNullOrBlank()) return@MaaCustomActionCallback 1
        try {
            val json = Json.parseToJsonElement(customActionParam).jsonObject
            val target = json["target"]?.jsonPrimitive?.contentOrNull
            val targets = json["targets"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val allTargets = (listOfNotNull(target) + targets).distinct()
            val substring = json["substring"]?.jsonPrimitive?.booleanOrNull ?: false

            for (t in allTargets) {
                val buffer = lib.MaaStringBufferCreate()
                try {
                    if (lib.MaaContextGetNodeData(context, t, buffer).toInt() != 0) {
                        val nodeStr = lib.MaaStringBufferGet(buffer)
                        if (!nodeStr.isNullOrBlank()) {
                            val nodeObj = Json.parseToJsonElement(nodeStr).jsonObject
                            val attachObj = nodeObj["attach"]?.jsonObject
                            val keywords = mutableListOf<String>()
                            attachObj?.values?.forEach { elem ->
                                when (elem) {
                                    is JsonPrimitive -> elem.contentOrNull?.takeIf(String::isNotBlank)?.let { keywords += it }
                                    is JsonArray -> elem.forEach { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { keywords += it } }
                                    else -> {}
                                }
                            }
                            if (keywords.isNotEmpty()) {
                                val regex = if (substring) {
                                    ".*(" + keywords.joinToString("|") { Regex.escape(it) } + ").*"
                                } else {
                                    "^(" + keywords.joinToString("|") { Regex.escape(it) } + ")$"
                                }
                                val overrideJson = buildJsonObject {
                                    put(t, buildJsonObject {
                                        put("expected", regex)
                                    })
                                }.toString()
                                lib.MaaContextOverridePipeline(context, overrideJson)
                                Ln.i("MaaRunner: AttachToExpectedRegexAction [$nodeName] target='$t' regex='$regex'")
                            }
                        }
                    }
                } finally {
                    lib.MaaStringBufferDestroy(buffer)
                }
            }
        } catch (t: Throwable) {
            Ln.w("MaaRunner: AttachToExpectedRegexAction error on node=$nodeName", t)
        }
        1
    }

    private val expendableRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val json = if (!customRecognitionParam.isNullOrBlank()) {
                runCatching { Json.parseToJsonElement(customRecognitionParam).jsonObject }.getOrNull()
            } else null
            val candidate = json?.get("candidate")?.jsonPrimitive?.contentOrNull
            if (candidate.isNullOrBlank()) return@MaaCustomRecognitionCallback 0

            val recoId = lib.MaaContextRunRecognition(context, candidate, "{}", image)
            if (recoId <= 0L) return@MaaCustomRecognitionCallback 0

            val tasker = lib.MaaContextGetTasker(context)
            val hitMem = Memory(1)
            val tempRect = lib.MaaRectCreate()
            try {
                if (tasker != null && lib.MaaTaskerGetRecognitionDetail(tasker, recoId, null, null, hitMem, tempRect, null, null, null).toInt() != 0) {
                    val hit = hitMem.getByte(0)
                    if (hit.toInt() != 0 && outBox != null && tempRect != null) {
                        val rx = lib.MaaRectGetX(tempRect)
                        val ry = lib.MaaRectGetY(tempRect)
                        val rw = lib.MaaRectGetW(tempRect)
                        val rh = lib.MaaRectGetH(tempRect)
                        lib.MaaRectSet(outBox, rx, ry, rw, rh)
                    }
                    return@MaaCustomRecognitionCallback hit
                }
            } finally {
                if (tempRect != null) {
                    lib.MaaRectDestroy(tempRect)
                }
            }
            0
        } catch (t: Throwable) {
            Ln.w("MaaRunner: ExpendableRecognition error on node=$nodeName", t)
            0
        }
    }

    private val listCompleteInvocations = ConcurrentHashMap<String, Int>()

    private val listCompleteRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { _, _, nodeName, _, _, _, _, _, _, _ ->
        val key = nodeName ?: "unknown"
        val count = (listCompleteInvocations[key] ?: 0) + 1
        listCompleteInvocations[key] = count
        Ln.i("MaaRunner: ListCompleteRecognition on node=$key (count=$count)")
        if (count >= 5) {
            listCompleteInvocations.remove(key)
            1
        } else {
            0
        }
    }

    private val scrollbarCompleteRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { _, _, nodeName, _, _, _, _, _, _, _ ->
        val key = nodeName ?: "unknown"
        val count = (listCompleteInvocations[key] ?: 0) + 1
        listCompleteInvocations[key] = count
        Ln.i("MaaRunner: ScrollbarCompleteRecognition on node=$key (count=$count)")
        if (count >= 4) {
            listCompleteInvocations.remove(key)
            1
        } else {
            0
        }
    }

    private val noopSuccessActionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, customActionName, _, _, _, _ ->
        Ln.i("MaaRunner: Custom action '$customActionName' on node='$nodeName' noop-success")
        1
    }

    private val noopFalseRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { _, _, _, _, _, _, _, _, _, _ ->
        0
    }

    private val noopTrueRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { _, _, _, _, _, _, _, _, _, _ ->
        1
    }

    fun setCallback(callback: IMaaRunnerCallback?) {
        callbackRef.set(callback)
    }

    /** agent child 的一行输出；由 [AgentHost] 的泵线程调用，app 侧不在时静默丢弃 */
    fun onAgentLine(line: String, fromStderr: Boolean) {
        notify { onAgentOutput(line, fromStderr) }
    }

    /**
     * 把 controller 手里那张缓存帧落到 [path]，供 focus 模板的 `{image}` 用
     *
     * 走文件而不是把字节回传：一张 720p PNG 动辄几百 KB，binder 事务缓冲总共才 1MB，
     * 直接传是在赌。落点由 app 侧给，它挑的是双方都读得到的外部私有目录
     */
    fun saveCachedImage(path: String): Boolean {
        val lib = MaaFrameworkLoader.library ?: return false
        val ctrl = controller ?: return false
        val buffer = lib.MaaImageBufferCreate() ?: return false
        return try {
            if (lib.MaaControllerCachedImage(ctrl, buffer).toInt() == 0) return false
            if (lib.MaaImageBufferIsEmpty(buffer).toInt() != 0) return false
            val size = lib.MaaImageBufferGetEncodedSize(buffer)
            if (size <= 0) return false
            val data = lib.MaaImageBufferGetEncoded(buffer) ?: return false
            val bytes = data.getByteArray(0, size.toInt())
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        } catch (e: Throwable) {
            Ln.w("MaaRunner: saveCachedImage failed: ${e.message}")
            false
        } finally {
            lib.MaaImageBufferDestroy(buffer)
        }
    }

    /**
     * 全局选项是进程级单例，setup 时设一次即可
     *
     * 不设 LOG_DIR 时 MaaFramework 按进程 CWD 解析 `maa.log` 与 Screencap 动作的落点，
     * 特权进程的 CWD 不可写，Screencap 会直接失败
     */
    fun applyGlobalOptions(logDir: String, debug: Boolean) {
        val lib = MaaFrameworkLoader.library ?: return
        if (!setStringOption(lib, MaaGlobalOption.LOG_DIR, logDir)) {
            Ln.w("MaaRunner: set LOG_DIR failed: $logDir")
        }
        setIntOption(
            lib,
            MaaGlobalOption.STDOUT_LEVEL,
            if (debug) MaaLoggingLevel.INFO else MaaLoggingLevel.ERROR,
        )
        // 节点出错时自动存一张现场图，比事后复现便宜；SAVE_DRAW 会每次识别都写盘，暂不开
        setBoolOption(lib, MaaGlobalOption.SAVE_ON_ERROR, debug)
        Ln.i("MaaRunner: global options applied, logDir=$logDir debug=$debug")
    }

    private fun setStringOption(lib: MaaFrameworkLibrary, key: Int, value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        // MaaFramework 按 val_size 截取，不看结尾 NUL；Memory 不能申请 0 字节
        val memory = Memory(bytes.size.coerceAtLeast(1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        return lib.MaaGlobalSetOption(key, memory, bytes.size.toLong()).toInt() != 0
    }

    private fun setIntOption(lib: MaaFrameworkLibrary, key: Int, value: Int): Boolean {
        val memory = Memory(Int.SIZE_BYTES.toLong())
        memory.setInt(0, value)
        return lib.MaaGlobalSetOption(key, memory, Int.SIZE_BYTES.toLong()).toInt() != 0
    }

    private fun setBoolOption(lib: MaaFrameworkLibrary, key: Int, value: Boolean): Boolean {
        val memory = Memory(1)
        memory.setByte(0, if (value) 1 else 0)
        return lib.MaaGlobalSetOption(key, memory, 1).toInt() != 0
    }

    fun isRunning(): Boolean = synchronized(lifecycleLock) { running }

    /** 立即返回；执行进度与结果走 [IMaaRunnerCallback] */
    fun start(payloadJson: String): Boolean {
        synchronized(lifecycleLock) {
            if (running) {
                Ln.w("MaaRunner: already running")
                return false
            }
            running = true
            stopRequested = false
        }
        val payload = runCatching { runPlanWireJson.decodeFromString<RunPlanPayload>(payloadJson) }
            .getOrElse {
                synchronized(lifecycleLock) { running = false }
                Ln.e("MaaRunner: bad payload: ${it.message}")
                return false
            }
        worker.execute { runPlan(payload) }
        return true
    }

    /** 幂等：未在跑时也返回 true，避免 app 侧为了停止先查状态 */
    fun stop(): Boolean {
        val lib = MaaFrameworkLoader.library ?: return false
        synchronized(lifecycleLock) {
            if (!running) return true
            stopRequested = true
            tasker?.let(lib::MaaTaskerPostStop)
        }
        return true
    }

    fun destroy() {
        worker.shutdownNow()
        releaseNative()
    }

    private fun runPlan(payload: RunPlanPayload) {
        var outcome = RunOutcome.FAILED
        var reason = ""
        try {
            val lib = MaaFrameworkLoader.library
            if (lib == null) {
                reason = "libMaaFramework.so 加载失败"
                return
            }
            val prepared = prepare(lib, payload)
            if (prepared != null) {
                reason = prepared
                if (isStopRequested()) {
                    outcome = RunOutcome.CANCELLED
                    reason = ""
                }
                return
            }

            var anyFailed = false
            var cancelled = false
            // 记录首次失败的任务，供重试
            val failedTasks = mutableListOf<RuntimeTaskPayload>()

            payload.tasks.forEachIndexed { index, task ->
                if (cancelled) return@forEachIndexed
                if (stopRequested(lib)) {
                    cancelled = true
                    return@forEachIndexed
                }
                notify { onTaskStarted(task.taskName, index, payload.tasks.size) }

                val overrides = JsonArray(task.pipelineOverrides).toString()
                val currentTasker = synchronized(lifecycleLock) { tasker }
                val taskId = lib.MaaTaskerPostTask(currentTasker, task.entry, overrides)
                if (taskId == INVALID_ID) {
                    anyFailed = true
                    failedTasks += task
                    notify { onTaskFinished(task.taskName, false, "PostTask 被拒绝") }
                    return@forEachIndexed
                }
                val status = lib.MaaTaskerWait(currentTasker, taskId)
                val success = status == MaaStatus.SUCCEEDED
                if (!success) {
                    anyFailed = true
                    failedTasks += task
                }
                notify { onTaskFinished(task.taskName, success, statusText(status)) }

                // Stop 之后 Tasker 会把剩余任务直接判失败，这里提前收尾避免刷一串假失败
                if (lib.MaaTaskerStopping(currentTasker).toInt() != 0) {
                    cancelled = true
                }
            }

            // 任务失败后重试（可选功能，需用户在设置中开启）
            // 主流程全部跑完后，对失败任务补跑一次；手动取消时不重试
            if (payload.retryFailedTasks && !cancelled && failedTasks.isNotEmpty()) {
                Ln.i("MaaRunner: retrying ${failedTasks.size} failed task(s) after main run")
                var retryAllSuccess = true
                val retryStart = payload.tasks.size  // 重试计数从已完成数量续接
                failedTasks.forEachIndexed { retryIndex, task ->
                    if (stopRequested(lib)) {
                        cancelled = true
                        return@forEachIndexed
                    }
                    notify { onTaskStarted("[重试] ${task.taskName}", retryStart + retryIndex, retryStart + failedTasks.size) }

                    val overrides = JsonArray(task.pipelineOverrides).toString()
                    val currentTasker = synchronized(lifecycleLock) { tasker }
                    val taskId = lib.MaaTaskerPostTask(currentTasker, task.entry, overrides)
                    if (taskId == INVALID_ID) {
                        retryAllSuccess = false
                        notify { onTaskFinished(task.taskName, false, "[重试] PostTask 被拒绝") }
                        return@forEachIndexed
                    }
                    val status = lib.MaaTaskerWait(currentTasker, taskId)
                    val success = status == MaaStatus.SUCCEEDED
                    if (!success) retryAllSuccess = false
                    notify { onTaskFinished(task.taskName, success, "[重试] ${statusText(status)}") }

                    if (lib.MaaTaskerStopping(currentTasker).toInt() != 0) {
                        cancelled = true
                    }
                }
                // 若重试全部成功，升级为 COMPLETED
                if (!cancelled && retryAllSuccess) {
                    anyFailed = false
                }
            }

            outcome = when {
                cancelled -> RunOutcome.CANCELLED
                anyFailed -> RunOutcome.COMPLETED_WITH_FAILURES
                else -> RunOutcome.COMPLETED
            }
        } catch (e: Throwable) {
            reason = "${e.javaClass.simpleName}: ${e.message}"
            Ln.e("MaaRunner: run failed: $reason")
        } finally {
            synchronized(lifecycleLock) {
                running = false
                stopRequested = false
            }
            notify { onFinished(outcome, reason) }
        }
    }

    private fun isStopRequested(): Boolean = synchronized(lifecycleLock) {
        running && stopRequested
    }

    private fun stopRequested(lib: MaaFrameworkLibrary): Boolean {
        val handle = synchronized(lifecycleLock) {
            if (!running || !stopRequested) return false
            tasker
        }
        if (handle != null) lib.MaaTaskerPostStop(handle)
        return true
    }

    /** 返回 null 表示就绪，否则返回失败原因 */
    private fun prepare(lib: MaaFrameworkLibrary, payload: RunPlanPayload): String? {
        // bridge 必须先 System.loadLibrary 进本进程，控制单元按名 dlopen 才能命中同一份
        if (!NativeBridgeLib.LOADED) {
            return "libbridge.so 未加载，无法建立 native controller"
        }

        val displayId = when (payload.displayMode) {
            DisplayMode.PRIMARY ->
                if (PrimaryDisplayManager.getCaptureSize() == null) {
                    return "主屏采集未启动"
                } else {
                    PrimaryDisplayManager.DISPLAY_ID
                }

            else -> VirtualDisplayManager.getDisplayId().takeIf {
                it != DefaultDisplayConfig.DISPLAY_NONE
            } ?: return "虚拟显示器未启动"
        }

        if (resource == null || loadedResourcePaths != payload.resourcePaths) {
            releaseResource(lib)
            val res = lib.MaaResourceCreate() ?: return "MaaResourceCreate 失败"
            lib.MaaResourceAddSink(res, eventSink, null)
            registerCustomActions(lib, res)
            payload.resourcePaths.forEach { path ->
                val id = lib.MaaResourcePostBundle(res, path)
                if (id == INVALID_ID || lib.MaaResourceWait(res, id) != MaaStatus.SUCCEEDED) {
                    lib.MaaResourceDestroy(res)
                    return "资源加载失败: $path"
                }
            }
            resource = res
            loadedResourcePaths = payload.resourcePaths
            // 资源换了，绑定关系也得重来
            releaseTasker(lib)
        }

        prepareAgents(lib, payload)?.let { return it }

        val (width, height) = when (payload.displayMode) {
            DisplayMode.PRIMARY -> PrimaryDisplayManager.getCaptureSize()
                ?: (DefaultDisplayConfig.WIDTH to DefaultDisplayConfig.HEIGHT)

            else -> {
                val vd = VirtualDisplayManager.getConfig()
                (payload.screenWidth.takeIf { it > 0 } ?: vd.width) to
                    (payload.screenHeight.takeIf { it > 0 } ?: vd.height)
            }
        }
        val currentResolution = width to height
        val currentInferenceDevice = payload.inferenceDevice

        if (controller == null ||
            boundDisplayId != displayId ||
            boundResolution != currentResolution ||
            boundInferenceDevice != currentInferenceDevice ||
            lib.MaaControllerConnected(controller).toInt() == 0
        ) {
            releaseController(lib)
            val config = buildControllerConfig(payload, displayId)
            val ctrl = lib.MaaAndroidNativeControllerCreate(config)
                ?: return "MaaAndroidNativeControllerCreate 失败: $config"
            lib.MaaControllerAddSink(ctrl, eventSink, null)
            val ctrlId = lib.MaaControllerPostConnection(ctrl)
            if (ctrlId == INVALID_ID || lib.MaaControllerWait(
                    ctrl,
                    ctrlId
                ) != MaaStatus.SUCCEEDED
            ) {
                lib.MaaControllerDestroy(ctrl)
                return "controller 连接失败"
            }
            controller = ctrl
            boundDisplayId = displayId
            boundResolution = currentResolution
            boundInferenceDevice = currentInferenceDevice
            releaseTasker(lib)
        }

        val needsTasker = synchronized(lifecycleLock) { tasker == null }
        if (needsTasker) {
            val tsk = lib.MaaTaskerCreate() ?: return "MaaTaskerCreate 失败"
            lib.MaaTaskerAddSink(tsk, eventSink, null)
            if (lib.MaaTaskerBindResource(tsk, resource).toInt() == 0 ||
                lib.MaaTaskerBindController(tsk, controller).toInt() == 0 ||
                lib.MaaTaskerInited(tsk).toInt() == 0
            ) {
                lib.MaaTaskerDestroy(tsk)
                return "Tasker 绑定失败"
            }
            synchronized(lifecycleLock) { tasker = tsk }
        }
        return null
    }

    /**
     * PI 声明的 agent：建 client → 绑 resource → 读回 identifier → 拉起 child → connect
     * 返回 null 表示就绪（含「本次无 agent」），否则返回失败原因
     *
     * client 绑在 resource 上，所以整批与 resource 同生命周期；child 死了也要连 client 一起重来，
     * 光重起 child 连不回已经 bind 在旧 socket 上的 client
     */
    private fun prepareAgents(lib: MaaFrameworkLibrary, payload: RunPlanPayload): String? {
        if (payload.agents.isEmpty()) {
            releaseAgents()
            return null
        }
        val agentLib = MaaAgentClientLoader.library
            ?: return "libMaaAgentClient.so 加载失败，无法拉起 agent"

        val reusable = loadedAgents == payload.agents &&
            agents.size == payload.agents.size &&
            agents.all { it.session.isAlive() && agentLib.MaaAgentClientAlive(it.client).toInt() != 0 }
        if (reusable) {
            notifyAgentsConnected()
            return null
        }

        releaseAgents()

        val workingDir = projectRoot ?: return "PI 根未就绪，agent 无法确定工作目录"
        val started = mutableListOf<ActiveAgent>()
        payload.agents.forEachIndexed { index, agent ->
            val client = agentLib.MaaAgentClientCreateTcp(AUTO_PORT)
                ?: return failAgents(started, "MaaAgentClientCreateTcp 失败")
            if (agentLib.MaaAgentClientBindResource(client, resource).toInt() == 0) {
                agentLib.MaaAgentClientDestroy(client)
                return failAgents(started, "agent 绑定 resource 失败")
            }
            val identifier = readIdentifier(lib, agentLib, client)
                ?: run {
                    agentLib.MaaAgentClientDestroy(client)
                    return failAgents(started, "读取 agent identifier 失败")
                }
            agentLib.MaaAgentClientSetTimeout(client, AGENT_CONNECT_TIMEOUT_MILLIS)

            val session = try {
                agentHost.launch(
                    AgentLaunchRequest(
                        index = index,
                        agent = agent,
                        identifier = identifier,
                        apkPath = payload.apkPath,
                        nativeLibraryDir = payload.nativeLibraryDir,
                        workingDir = workingDir,
                        piEnv = payload.piEnv,
                    ),
                )
            } catch (e: AgentLaunchException) {
                agentLib.MaaAgentClientDestroy(client)
                return failAgents(started, e.message.orEmpty())
            }

            if (agentLib.MaaAgentClientConnect(client).toInt() == 0) {
                session.close()
                agentLib.MaaAgentClientDestroy(client)
                return failAgents(started, "agent 连接超时：${agent.childExec}")
            }
            Ln.i("MaaRunner: agent[$index] connected, identifier=$identifier")
            notify { onAgentConnected(index, payload.agents.size, session.executable) }
            started += ActiveAgent(client, session)
        }

        agents = started
        loadedAgents = payload.agents
        // agent 注册的 custom 节点挂在 resource 上，绑定关系要重来
        releaseTasker(lib)
        return null
    }

    /** 复用还活着的 child 时也回投：新一轮会话日志不能因为没重新 connect 就缺这行 */
    private fun notifyAgentsConnected() {
        val alive = agents
        alive.forEachIndexed { index, active ->
            Ln.i("MaaRunner: agent[$index] reused, exec=${active.session.executable}")
            notify { onAgentConnected(index, alive.size, active.session.executable) }
        }
    }

    /** identifier 走 MaaStringBuffer 出参；buffer 由调用方负责销毁 */
    private fun readIdentifier(
        lib: MaaFrameworkLibrary,
        agentLib: MaaAgentClientLibrary,
        client: Pointer,
    ): String? {
        val buffer = lib.MaaStringBufferCreate() ?: return null
        return try {
            if (agentLib.MaaAgentClientIdentifier(client, buffer).toInt() == 0) null
            else lib.MaaStringBufferGet(buffer)?.takeIf(String::isNotEmpty)
        } finally {
            lib.MaaStringBufferDestroy(buffer)
        }
    }

    private fun failAgents(started: List<ActiveAgent>, reason: String): String {
        started.forEach { releaseAgent(it) }
        return reason
    }

    private fun releaseAgents() {
        agents.forEach { releaseAgent(it) }
        agents = emptyList()
        loadedAgents = emptyList()
    }

    private fun releaseAgent(agent: ActiveAgent) {
        val agentLib = MaaAgentClientLoader.library
        runCatching { agentLib?.MaaAgentClientDisconnect(agent.client) }
        runCatching { agent.session.close() }
        runCatching { agentLib?.MaaAgentClientDestroy(agent.client) }
    }

    /**
     * screen_resolution 必须与帧缓冲、触摸坐标空间三者一致，不一致时 screencap 立即失败
     * library_path 用裸名：bridge 已在本进程加载，控制单元 dlopen 同名即命中同一份
     *
     * 虚拟屏模式下 force_stop 必须为 true：目标应用若已在主屏上跑着，startActivity 会复用它在主屏的
     * 既有 task，虚拟屏上拿不到画面。先杀掉再拉起，进程才会落到虚拟屏上
     * 主屏模式反过来——目标就在主屏，杀掉只会把用户已经摆好的现场清空
     */
    private fun buildControllerConfig(payload: RunPlanPayload, displayId: Int): String {
        val (width, height) = when (payload.displayMode) {
            // 主屏尺寸跟着旋转变，app 侧发 payload 时算的值可能已经过期，只认采集器当下这一份
            DisplayMode.PRIMARY -> PrimaryDisplayManager.getCaptureSize()
                ?: (DefaultDisplayConfig.WIDTH to DefaultDisplayConfig.HEIGHT)

            else -> {
                val vd = VirtualDisplayManager.getConfig()
                (payload.screenWidth.takeIf { it > 0 } ?: vd.width) to
                    (payload.screenHeight.takeIf { it > 0 } ?: vd.height)
            }
        }
        return buildJsonObject {
            put("library_path", BRIDGE_LIBRARY_NAME)
            put("screen_resolution", buildJsonObject {
                put("width", width)
                put("height", height)
            })
            put("display_id", displayId)
            put("force_stop", payload.displayMode != DisplayMode.PRIMARY)
            // 推理后端：cpu（默认）/ nnapi / vulkan；不支持时 MaaFramework 自动回退 cpu
            if (payload.inferenceDevice.isNotBlank() && payload.inferenceDevice != "cpu") {
                put("inference_device", payload.inferenceDevice)
            }
        }.toString()
    }

    private fun releaseNative() {
        val lib = MaaFrameworkLoader.library ?: return
        releaseTasker(lib)
        releaseController(lib)
        releaseResource(lib)
    }


    private fun releaseTasker(lib: MaaFrameworkLibrary) {
        val handle = synchronized(lifecycleLock) {
            val current = tasker
            tasker = null
            current
        }
        handle?.let(lib::MaaTaskerDestroy)
    }

    private fun releaseController(lib: MaaFrameworkLibrary) {
        controller?.let(lib::MaaControllerDestroy)
        controller = null
        boundDisplayId = null
        boundResolution = null
        boundInferenceDevice = null
    }

    private val registeredCustomActions = mutableSetOf<String>()
    private val registeredCustomRecognitions = mutableSetOf<String>()

    private fun registerCustomActions(lib: MaaFrameworkLibrary, res: Pointer) {
        registeredCustomActions.clear()
        registeredCustomRecognitions.clear()
        listCompleteInvocations.clear()

        fun regAction(name: String, cb: MaaFrameworkLibrary.MaaCustomActionCallback) {
            lib.MaaResourceRegisterCustomAction(res, name, cb, null)
            registeredCustomActions += name
        }

        fun regReco(name: String, cb: MaaFrameworkLibrary.MaaCustomRecognitionCallback) {
            lib.MaaResourceRegisterCustomRecognition(res, name, cb, null)
            registeredCustomRecognitions += name
        }

        regAction("SubTask", subTaskCallback)
        regAction("ClearHitCount", clearHitCountCallback)
        regAction("FalseAction", falseActionCallback)
        regAction("PostStop", postStopCallback)
        regAction("RepeatUntilFoundAction", repeatUntilFoundCallback)
        regAction("RepeatUntilNotFoundAction", repeatUntilNotFoundCallback)
        regAction("AutoAltClickAction", autoAltClickCallback)
        regAction("AutoCtrlClickAction", autoCtrlClickCallback)
        regAction("AutoAltSwipeAction", autoAltSwipeCallback)
        regAction("SceneManagerMenuListClickItemAction", menuListClickItemCallback)
        regAction("PipelineOverrideAction", pipelineOverrideCallback)
        regAction("PipelineOverride", pipelineOverrideCallback)
        regAction("AttachToExpectedRegexAction", attachToExpectedRegexCallback)

        val otherActions = listOf(
            "CreditShoppingScanItemAction",
            "AddItemData",
            "SyncItemData",
            "UpdateItemQuantity",
            "DeliveryJobsResolveOngoingDepotAction",
            "AutoDeliveryResolveDepotAction",
            "AutoDeliveryResolveDestinationAction",
            "AutoStockpile.SelectItem",
            "AutoStockpile.ReconcileDecision",
            "AutoStockStapleQuantityControlAction",
            "BetterSliding",
            "CaptureUid",
            "CloseGameAction",
            "ImageCheckSetResultAction",
            "IntelArchiveResetSessionAction",
            "IntelArchiveResolveTruncAction",
            "IntelArchiveShowInventoryAction",
            "SeizeDeliveryJobsResetScanStateAction",
            "SeizeDeliveryJobsScanTargetAction",
            "autoEcoFarmResetSwipeState",
            "autoEcoFarmInterruptibleSleep",
            "autoEcoFarmOverrideTargetTemplate",
            "AutoFightMainAction",
            "AutoSellItemExecuteItemTaskAction",
            "AccountSwitchWindowAction",
            "BatchAddFriendsAction",
            "BatchAddFriendsFriendListFullAction",
            "BatchAddFriendsStrangersFinishAction",
            "BatchAddFriendsStrangersOnAddAction",
            "BatchAddFriendsUIDEnterAction",
            "BatchAddFriendsUIDFinishAction",
            "BatchAddFriendsUIDLoopTopAction",
            "BatchAddFriendsUIDOnAddAction",
            "BatchAddFriendsUIDOnEmptyAction",
            "CharacterControllerForwardAxisAction",
            "CharacterControllerPitchDeltaAction",
            "CharacterControllerRelativeMoveAction",
            "CharacterControllerYawDeltaAction",
            "CharacterMoveToTargetAction",
            "CharacterMoveToTargetNotFoundAction",
            "CharacterSearchAction",
            "FocusOCRAction",
            "FailureCollectorFinish",
            "FailureCollectorReset",
            "FailureCollectorRunTask",
            "ImportBluePrintsEnterCodeAction",
            "ImportBluePrintsFinishAction",
            "ImportBluePrintsInitTextAction",
            "MapNavigateAction",
            "OutpostTradingLocationPlan",
            "OutpostTradingOperatorSession",
            "OutpostTradingPrioritySession",
            "OutpostTradingReserveSession",
            "PullCountCalculatorAction",
            "PuzzleAction",
            "RealTimeTaskAction",
            "TrialOfSwordmancy.Decide",
            "WebEvent202605Action",
            "ZiplineImport",
            "AeroSalvageConfigureSwipeAction",
            "EssenceFilterAfterBattleSkillDecisionAction",
            "EssenceFilterAfterBattleTierGateAction",
            "EssenceFilterCheckItemAction",
            "EssenceFilterCheckItemLevelAction",
            "EssenceFilterFinishAction",
            "EssenceFilterInitAction",
            "EssenceFilterSkillDecisionAction"
        )
        for (name in otherActions) {
            regAction(name, noopSuccessActionCallback)
        }

        regReco("ExpendableRecognition", expendableRecognitionCallback)
        regReco("ListCompleteRecognition", listCompleteRecognitionCallback)
        regReco("ScrollbarCompleteRecognition", scrollbarCompleteRecognitionCallback)
        regReco("ScrollbarRecognition", noopTrueRecognitionCallback)
        regReco("ScreenshotStableRecognition", noopTrueRecognitionCallback)
        regReco("ScheduleRecognition", noopTrueRecognitionCallback)
        regReco("ItemQuantitySatisfied", noopTrueRecognitionCallback)
        regReco("ItemDataReady", noopTrueRecognitionCallback)

        val falseRecognitions = listOf(
            "ImageCheckNotPassedRecognition",
            "AutoStockpile.Recognition",
            "AutoSellScanItemRecognition",
            "ItemTransferSameItemRecognition",
            "ExpressionRecognition",
            "IconRecognition",
            "AeroSalvageBalloonStateRecognition",
            "AeroSalvageGridRecognition",
            "AeroSalvageInitialStateRecognition",
            "autoEcoFarmCalculateSwipeTarget",
            "autoEcoFarmFindNearestRecognitionResult",
            "AutoFightEntryRecognition",
            "EssenceFilterAfterBattleNthRecognition",
            "EssenceGridAdvanceRecognition",
            "EssenceGridPendingRecognition",
            "IntelArchiveScanDetailRecognition",
            "IntelArchiveScanItemsRecognition",
            "MapFind",
            "MapLocateAssertLocation",
            "OutpostTradingCurrentBestOperator",
            "OutpostTradingCurrentGoods",
            "OutpostTradingCurrentOperatorUncached",
            "OutpostTradingOperatorCacheReady",
            "OutpostTradingOperatorConflict",
            "OutpostTradingOperatorListBottom",
            "OutpostTradingOperatorScanOutcome",
            "OutpostTradingPriorityItem",
            "OutpostTradingSelectBestOperator",
            "PuzzleRecognition",
            "ReceptionRoomExchangeCountdownWithinThresholdRecognition",
            "ReceptionRoomWaitExchangeKeepAliveDueRecognition",
            "SeizeDeliveryJobsFindTargetRecognition",
            "SeizeDeliveryJobsScanTargetRecognition",
            "TrialOfSwordmancy.Recognize",
            "TrialOfSwordmancy.RecognizeAband",
            "TrialOfSwordmancy.RecognizeDeck"
        )
        for (name in falseRecognitions) {
            regReco(name, noopFalseRecognitionCallback)
        }

        Ln.i("MaaRunner: Registered ${registeredCustomActions.size} custom actions and ${registeredCustomRecognitions.size} custom recognitions")
    }

    /** agent client 绑在 resource 上，销毁 resource 前必须先把 client 与 child 收掉 */
    private fun releaseResource(lib: MaaFrameworkLibrary) {
        releaseAgents()
        resource?.let { res ->
            for (name in registeredCustomActions) {
                lib.MaaResourceUnregisterCustomAction(res, name)
            }
            for (name in registeredCustomRecognitions) {
                lib.MaaResourceUnregisterCustomRecognition(res, name)
            }
            registeredCustomActions.clear()
            registeredCustomRecognitions.clear()
            listCompleteInvocations.clear()
            lib.MaaResourceDestroy(res)
        }
        resource = null
        loadedResourcePaths = emptyList()
    }

    private inline fun notify(block: IMaaRunnerCallback.() -> Unit) {
        val callback = callbackRef.get() ?: return
        runCatching { callback.block() }
            .onFailure { Ln.w("MaaRunner: callback failed: ${it.message}") }
    }

    private fun statusText(status: Int): String = when (status) {
        MaaStatus.SUCCEEDED -> "succeeded"
        MaaStatus.FAILED -> "failed"
        MaaStatus.RUNNING -> "running"
        MaaStatus.PENDING -> "pending"
        else -> "invalid($status)"
    }

    private companion object {
        /** MaaInvalidId */
        const val INVALID_ID = 0L
        const val BRIDGE_LIBRARY_NAME = "libbridge.so"

        /** 解释器这类 child 冷启动要几秒，超时给宽一点；连不上会整批任务失败，宁可多等 */
        const val AGENT_CONNECT_TIMEOUT_MILLIS = 30_000L

        /**
         * 让系统分配回环端口；identifier 随即变成实际端口，原样传给 child
         *
         * 代价是同机任何带 INTERNET 权限的应用都能连上这个端口冒充 agent；
         * 但 unix socket 那条在 shell 域下建不出 sock_file，没有别的选择
         */
        const val AUTO_PORT: Short = 0
    }
}
