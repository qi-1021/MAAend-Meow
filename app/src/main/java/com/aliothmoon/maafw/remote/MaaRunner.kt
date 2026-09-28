package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.IMaaRunnerCallback
import com.aliothmoon.maafw.bridge.NativeBridgeLib
import com.aliothmoon.maafw.constant.DefaultDisplayConfig
import com.aliothmoon.maafw.constant.DisplayMode
import com.aliothmoon.maafw.diagnostics.GoodsProbeDumpPolicy
import com.aliothmoon.maafw.diagnostics.RunDiagnostics
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
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
        set(value) {
            field = value
            currentController = value
        }
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
        // 据点交易干员子系统按相对路径读 selection_data.json 与写快照缓存
        OperatorRuntime.projectRoot = path
        OperatorRuntime.logger = { message -> Ln.w(message) }
        // 情报档案目录也随 PI 根解析；PI 重装后必须重读，不能沿用旧缓存
        IntelArchiveSupport.clearCatalogCache()
    }

    /** JNA 回调必须被强引用住，否则会被 GC，native 回调时踩空 */
    private val eventSink = MaaFrameworkLibrary.MaaEventCallback { _, message, detailsJson, _ ->
        keepCallbackThread()
        Ln.i("MaaEventCallback on $message")
        // 结构化诊断：debug 才落盘，release 里是 no-op；在 MaaFrameworkRunnerPort 那边
        // 不再重复喂一份，事件源只此一处
        RunDiagnostics.event(message.orEmpty(), detailsJson.orEmpty())
        runCatching {
            callbackRef.get()?.onEvent(message.orEmpty(), detailsJson.orEmpty())
        }.onFailure {
            // 回调穿回 native 会直接崩进程
            Ln.w("MaaRunner: event dispatch failed: ${it.message}")
        }
    }

    /**
     * BetterSliding 的 MaaFramework 适配。
     *
     * 上游 `BetterSliding` 是「一次调用启动一张内部子流水线」，由 8 个 handler 按
     * 当前驱动节点逐节点推进。状态与编排都已经在 [BetterSlidingSession] 里实现并单测，
     * 这里只把 MaaFramework 的调用面接过去：
     *  - 覆盖流水线（`OverrideNext` 未绑定，改用覆盖 `next` 字段，等价）
     *  - 跑内部子流水线（`MaaContextRunTask`，同步执行，期间回调会重入同一 session）
     *  - 读调用节点定义（取 attach）
     *  - 按 recoId 读识别详情（滑条手柄框 / 数量 OCR 都在里面）
     *
     * `hostLib` / `hostContext` 是每次回调前刷新的——回调是单线程的，
     * 而 session 必须跨节点保持状态，所以 host 复用、context 每轮换。
     */
    private inner class MaaBetterSlidingHost : BetterSlidingHost {
        var hostLib: MaaFrameworkLibrary? = null
        var hostContext: Pointer? = null

        override fun overridePipeline(overrideJson: String): Boolean {
            val l = hostLib ?: return false
            val ctx = hostContext ?: return false
            return l.MaaContextOverridePipeline(ctx, overrideJson).toInt() != 0
        }

        override fun runSubTask(nodeName: String, overrideJson: String): Boolean {
            val l = hostLib ?: return false
            val ctx = hostContext ?: return false
            return l.MaaContextRunTask(ctx, nodeName, overrideJson) > 0L
        }

        override fun callerNodeJson(nodeName: String): String? {
            val l = hostLib ?: return null
            val ctx = hostContext ?: return null
            return nodeDefinitionJson(l, ctx, nodeName)
        }

        override fun recognitionDetailJson(recoId: Long): String? {
            val l = hostLib ?: return null
            val ctx = hostContext ?: return null
            val json = this@MaaRunner.recognitionDetailJson(l, ctx, recoId)
            // 真机诊断：BetterSliding 驱动节点走回调 recoId 查 detail。若这里 len=0/为 null，
            // 说明「查不到」；若 len>0 但上层仍读不到数量，则是解析问题（And 数组根，见 BetterSlidingOcr）。
            Ln.i("MaaRunner: BetterSliding recognitionDetail recoId=$recoId len=${json?.length ?: 0}")
            return json
        }

        override fun parseJson(text: String): Any? = MaaJsonTree.parse(text)

        override fun info(message: String) {
            Ln.i("MaaRunner: $message")
            // 调货滑条最容易出问题的就是「哪一步算了什么、路由到哪」，Session 已经在
            // 关键节点吐了人话，这里原样进报告，省得事后翻 logcat
            RunDiagnostics.note("bettersliding", message)
        }

        override fun warn(message: String) {
            Ln.w("MaaRunner: $message")
            RunDiagnostics.note("bettersliding", message, mapOf("level" to "warn"))
        }
    }

    private val betterSlidingHost = MaaBetterSlidingHost()
    private val betterSlidingSession = BetterSlidingSession(betterSlidingHost)

    private val betterSlidingCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, recoId, box, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null || nodeName == null) {
            Ln.w("MaaRunner: BetterSliding 收到空 context/node")
            return@MaaCustomActionCallback 0
        }
        betterSlidingHost.hostLib = lib
        betterSlidingHost.hostContext = context
        // 回调第 7 个参数就是本节点的命中框（const MaaRect*）。滑条起点/终点必须用它：
        // detail_json 里没有 And 节点的顶层 box，走那条路真机永远读不到（见 BetterSlidingSession）。
        val actionBox = readCallbackBox(lib, box)
        try {
            if (betterSlidingSession.run(nodeName, customActionParam, recoId, actionBox)) 1 else 0
        } catch (t: Throwable) {
            Ln.e("MaaRunner: BetterSliding 执行异常 node=$nodeName", t)
            0
        }
    }

    /**
     * 把回调里的 `const MaaRect*` 读成 [x,y,w,h]；退化框返回 null（交给调用方兜底）。
     *
     * JNA 取四个 Int 的动作留在这一层，是否有效交给纯函数 [BetterSlidingOcr.boxOfRect] 判定。
     */
    private fun readCallbackBox(lib: MaaFrameworkLibrary, rect: Pointer?): List<Int>? {
        if (rect == null) return null
        val r = getBoxRect(lib, rect)
        return BetterSlidingOcr.boxOfRect(r.x, r.y, r.w, r.h)
    }

    /**
     * `AutoStockStapleQuantityControlAction`：算出这一格该买多少，把 TargetQuantity
     * 覆盖到同级的 BetterSliding 节点上。拖动本身由 BetterSliding 负责。
     *
     * 上游走的是「读校验节点的表达式 -> 截图 OCR 当前持有量 -> target = 阈值 - 当前」，
     * 表达式形如 `20 > {AutoStockStapleGoodsCountValidate}`，阈值是字面整数、计数节点是占位符。
     */
    private val autoStockStapleQuantityControlCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null || customActionParam.isNullOrBlank()) {
            Ln.w("MaaRunner: AutoStockStapleQuantityControl 缺少 context/param (node=$nodeName)")
            return@MaaCustomActionCallback 0
        }
        try {
            val param = AutoStockStapleSupport.parseParam(MaaJsonTree.parse(customActionParam))
            if (param == null) {
                Ln.w("MaaRunner: AutoStockStapleQuantityControl 参数不合法: $customActionParam")
                return@MaaCustomActionCallback 0
            }

            val validatorNode = param.validatorNode.ifEmpty {
                AutoStockStapleSupport.buildValidatorNodeName(param.itemName)
            }
            val spec = AutoStockStapleSupport.resolveValidatorSpec(
                MaaJsonTree.parse(nodeDefinitionJson(lib, context, validatorNode)),
            )
            if (spec == null) {
                Ln.w("MaaRunner: AutoStockStapleQuantityControl 取不到校验表达式 (validator=$validatorNode)")
                return@MaaCustomActionCallback 0
            }

            val ctrl = controller
            if (ctrl == null) {
                Ln.w("MaaRunner: AutoStockStapleQuantityControl controller 为空，无法读取当前持有量")
                return@MaaCustomActionCallback 0
            }
            val currentCount = readOwnedQuantity(lib, context, spec.countNode, ctrl) ?: run {
                Ln.w("MaaRunner: AutoStockStapleQuantityControl 读不到持有量数字 (count=${spec.countNode})")
                return@MaaCustomActionCallback 0
            }

            val target = spec.threshold - currentCount
            if (target <= 0) {
                Ln.i(
                    "MaaRunner: AutoStockStapleQuantityControl 无需购买 " +
                        "(item=${param.itemName} threshold=${spec.threshold} current=$currentCount target=$target)",
                )
                return@MaaCustomActionCallback 1
            }

            val slidingNode = param.slidingNode.ifEmpty { AutoStockStapleSupport.DEFAULT_SLIDING_NODE }
            val overrideJson = JsonTree.toJson(AutoStockStapleSupport.buildQuantityControlOverride(slidingNode, target))
            if (lib.MaaContextOverridePipeline(context, overrideJson).toInt() == 0) {
                Ln.w("MaaRunner: AutoStockStapleQuantityControl 覆盖 BetterSliding 失败 (sliding=$slidingNode)")
                return@MaaCustomActionCallback 0
            }
            Ln.i(
                "MaaRunner: AutoStockStapleQuantityControl 目标数量已解析 " +
                    "(item=${param.itemName} expr=\"${spec.expression}\" threshold=${spec.threshold} " +
                    "current=$currentCount target=$target sliding=$slidingNode)",
            )
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: AutoStockStapleQuantityControl 执行异常 node=$nodeName", t)
            0
        }
    }

    /**
     * 截当前画面并跑一次计数识别，返回 OCR 文本里的第一个整数（当前持有量）。
     *
     * 抽成独立方法是因为「截图缓冲区的释放」与「提前失败」交织，
     * 写在回调里既难读也容易出现未初始化赋值；这里用 try/finally 收口。
     */
    private fun readOwnedQuantity(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        countNode: String,
        ctrl: Pointer,
    ): Int? {
        val capId = lib.MaaControllerPostScreencap(ctrl)
        if (capId > 0) lib.MaaControllerWait(ctrl, capId)

        val imgBuf = lib.MaaImageBufferCreate() ?: return null
        return try {
            if (lib.MaaControllerCachedImage(ctrl, imgBuf).toInt() == 0) {
                Ln.w("MaaRunner: AutoStockStaple 取当前画面失败")
                return null
            }
            if (lib.MaaImageBufferIsEmpty(imgBuf).toInt() != 0) {
                Ln.w("MaaRunner: AutoStockStaple 当前画面为空")
                return null
            }
            val recoId = lib.MaaContextRunRecognition(context, countNode, "{}", imgBuf)
            if (recoId <= 0L) {
                Ln.w("MaaRunner: AutoStockStaple 持有量识别未命中 (count=$countNode)")
                return null
            }
            val detail = BetterSlidingOcr.fromRecognizedDetail(
                MaaJsonTree.parse(recognitionDetailJson(lib, context, recoId)),
                countNode,
            )
            AutoStockStapleSupport.findFirstOcrText(detail)?.let { AutoStockStapleSupport.firstIntegerOfText(it) }
        } finally {
            lib.MaaImageBufferDestroy(imgBuf)
        }
    }

    /**
     * `ScheduleRecognition`：按调用节点 attach 里的 monday..sunday 决定今天是否执行。
     *
     * 之前在移动端注册成恒真，等于「用户设了只在周末跑也照跑」。节点 attach 默认为全 false，
     * 任务选项默认七天全选，所以默认行为与恒真一致；只有用户真去改周期时才有区别。
     */
    private val scheduleRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, _, _, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || nodeName == null) {
            Ln.w("MaaRunner: ScheduleRecognition 缺少 context/node")
            return@MaaCustomRecognitionCallback 0
        }
        try {
            val flags = ScheduleSupport.parseFlags(MaaJsonTree.parse(nodeDefinitionJson(lib, context, nodeName)))
            if (flags == null) {
                Ln.w("MaaRunner: ScheduleRecognition 取不到节点定义 (node=$nodeName)")
                return@MaaCustomRecognitionCallback 0
            }
            val day = ScheduleSupport.gameWeekday(java.time.LocalDateTime.now())
            if (!ScheduleSupport.isEnabledOn(flags, day)) {
                Ln.i("MaaRunner: ScheduleRecognition 今天($day)不在执行周期内，跳过 (node=$nodeName)")
                return@MaaCustomRecognitionCallback 0
            }
            // 上游把 arg.Roi 作为命中框回填，保持一致
            if (outBox != null && roi != null) {
                lib.MaaRectSet(
                    outBox,
                    lib.MaaRectGetX(roi),
                    lib.MaaRectGetY(roi),
                    lib.MaaRectGetW(roi),
                    lib.MaaRectGetH(roi),
                )
            }
            Ln.i("MaaRunner: ScheduleRecognition 周期命中 ($day, node=$nodeName)")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: ScheduleRecognition 执行异常 node=$nodeName", t)
            0
        }
    }

    /**
     * 据点交易「干员智能选择」的 MaaFramework 适配。
     *
     * 决策全部在 [OperatorRecognitions] / [OperatorSelection] / [OperatorScan] / [OperatorCache]，
     * 已经本地单测过。这里只把 lib/context/image/roi 接上去，并把结果写回 outBox。
     *
     * `hostXxx` 是每次回调前刷新的——回调单线程，而跨帧状态（扫描表、OCR 交接槽）
     * 必须持久，所以宿主复用、这四项每轮换。
     */
    private inner class MaaOperatorHost : OperatorRecognitions.Host {
        var hostLib: MaaFrameworkLibrary? = null
        var hostContext: Pointer? = null
        var hostImage: Pointer? = null
        var hostTaskId: Long = 0
        var hostLocation: String = ""

        override fun selectionData(): OperatorDataset.OperatorSelectionData? = OperatorRuntime.selectionData()

        override fun selectionFile(): OperatorDataset.SelectionFile? = OperatorRuntime.selectionFile()

        /**
         * 拼装选择参数，并按上游做校验：数据里有该据点、扫描域非空、启用集合非空、
         * 非 all 时该据点必须在启用集合里。任一不满足返回 null（识别判 miss）。
         */
        override fun resolveSelection(p: OperatorRecognitions.Param): OperatorSelection.SelectionParam? {
            val data = OperatorRuntime.selectionData() ?: return null
            if (p.usage != OperatorSelection.USAGE_ALL && data.targetCandidates[p.location] == null) {
                Ln.w("MaaRunner: operator 数据里没有据点 ${p.location}")
                return null
            }
            val scanCandidates = OperatorSelection.allOperatorScanCandidates(data)
            if (scanCandidates.isEmpty()) {
                Ln.w("MaaRunner: operator 已知干员表为空")
                return null
            }
            val snap = OperatorRuntime.session.snapshot()
            if (snap.activeLocations.isEmpty()) {
                Ln.w("MaaRunner: operator 启用据点集合为空（register 未执行？）")
                return null
            }
            if (p.usage != OperatorSelection.USAGE_ALL && p.location !in snap.activeLocations) {
                Ln.w("MaaRunner: operator 据点 ${p.location} 未启用")
                return null
            }
            return OperatorSelection.SelectionParam(
                usage = p.usage,
                location = p.location,
                // target 才需要该据点的候选；restore/all 留空
                candidates = if (p.usage == OperatorSelection.USAGE_TARGET) {
                    OperatorDataset.normalizeOperatorCandidates(data.targetCandidates[p.location] ?: emptyList())
                } else {
                    emptyList()
                },
                targetCandidatesByLocation = data.targetCandidates,
                restoreGroups = OperatorDataset.normalizeOperatorCandidateGroups(data.restoreGroups),
                scanCandidates = scanCandidates,
                knownOperators = data.knownOperators,
                activeLocations = snap.activeLocations,
                completedRestoreLocations = snap.completedRestoreLocations,
                targetAssignments = snap.targetAssignments,
                lockedRestoreAssignments = snap.lockedRestoreAssignments,
                excludedOperators = snap.excludedOperators,
                outpostProsperityMaxLocations = snap.outpostProsperityMaxLocations,
            )
        }

        override fun loadOwnedNames(): Set<String>? = OperatorRuntime.loadOwnedNames()

        override fun listOcr(roi: List<Int>): List<OperatorOcrMatch.Item>? = probe(roi)

        /** reuse=true 先吃交接槽；吃不到才重新 OCR，且**不再写回**（与上游一致）。 */
        override fun currentOcr(roi: List<Int>, reuse: Boolean): List<OperatorOcrMatch.Item>? {
            val key = OperatorScan.HandoffKey(hostTaskId, hostLocation, roi)
            if (reuse) {
                OperatorRuntime.ocrHandoff.take(key)?.let { return it }
                return probe(roi)
            }
            val items = probe(roi) ?: return null
            OperatorRuntime.ocrHandoff.store(key, items)
            return items
        }

        override val scanStates: OperatorScan.ScanStates = OperatorRuntime.scanStates

        override val session: OperatorSession = OperatorRuntime.session

        override fun cacheHasSnapshot(uid: String): Boolean = OperatorRuntime.hasSnapshot()

        override fun invalidateSnapshot(uid: String): Boolean = OperatorRuntime.invalidateSnapshot()

        override fun shouldWriteSnapshot(p: OperatorRecognitions.Param): Boolean =
            OperatorScan.shouldWriteSnapshot(p.usage, p.location, p.mode, OperatorRuntime.hasSnapshot())

        override fun writeSnapshot(
            p: OperatorRecognitions.Param,
            scanCandidates: List<OperatorDataset.OperatorCandidate>,
            observed: List<String>,
        ): Boolean {
            val ok = OperatorRuntime.writeSnapshot(scanCandidates, observed)
            RunDiagnostics.note(
                "operator",
                "写干员快照｜${if (ok) "成功" else "失败"} scan域=${scanCandidates.size} observed=${observed.size} " +
                    "usage=${p.usage} location=${p.location}",
                mapOf(
                    "stage" to "snapshot",
                    "result" to if (ok) "write_ok" else "write_fail",
                    "usage" to p.usage,
                    "location" to p.location,
                    "scan" to scanCandidates.size,
                    "observed" to observed.size,
                ),
            )
            return ok
        }

        override fun lastError(): String = OperatorRuntime.lastError()

        override fun currentUid(): String = OperatorRuntime.uidProvider()

        override fun log(message: String) {
            Ln.i("MaaRunner: $message")
        }

        private fun probe(roi: List<Int>): List<OperatorOcrMatch.Item>? {
            val lib = hostLib ?: return null
            val ctx = hostContext ?: return null
            val image = hostImage ?: return null
            if (roi.size != 4) return null
            // 干员列表一屏多行多卡片，要靠检测分出各个名字框，必须完整检测（onlyRec=false）
            val items = ocrProbe(lib, ctx, image, intArrayOf(roi[0], roi[1], roi[2], roi[3]), onlyRec = false)
            // OcrItem 的 box 可能为空（极少）；匹配仍然有效，但点击框退化到 0,0
            return items.map { item ->
                val b = item.box
                OperatorOcrMatch.Item(
                    item.text,
                    if (b != null && b.size >= 4) OcrBox(b[0], b[1], b[2], b[3]) else OcrBox(0, 0, 0, 0),
                )
            }
        }
    }

    private val operatorHost = MaaOperatorHost()

    /**
     * 干员子系统的决策埋点统一进 RunDiagnostics（tag=operator）。判定逻辑仍在
     * [OperatorRecognitions] 里，这里只把出口接到报告；`diagnosticsEnabled` 为 false
     * （release 或报告未开）时连昂贵的匹配摘要都不算。
     */
    private val operatorRecognitions = OperatorRecognitions(
        host = operatorHost,
        note = { message, extra -> RunDiagnostics.note("operator", message, extra) },
        diagnosticsEnabled = { RunDiagnostics.isEnabled() },
    )

    /** 六个干员识别共用的回调骨架：解析参数 → 刷新宿主 → 决策 → 回写 outBox。 */
    private fun makeOperatorRecognition(
        handler: (OperatorRecognitions, OperatorRecognitions.Param) -> OperatorRecognitions.Outcome,
    ): MaaFrameworkLibrary.MaaCustomRecognitionCallback =
        MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, taskId, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
            val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
            if (context == null || image == null || nodeName == null) return@MaaCustomRecognitionCallback 0
            val p = OperatorRecognitions.parseParam(MaaJsonTree.parse(customRecognitionParam))
            if (p == null) {
                Ln.w("MaaRunner: $nodeName 参数不合法: $customRecognitionParam")
                return@MaaCustomRecognitionCallback 0
            }
            operatorHost.hostLib = lib
            operatorHost.hostContext = context
            operatorHost.hostImage = image
            operatorHost.hostTaskId = taskId
            operatorHost.hostLocation = p.location
            try {
                val hit = handler(operatorRecognitions, p) as? OperatorRecognitions.Outcome.Hit
                    ?: return@MaaCustomRecognitionCallback 0
                if (outBox != null && hit.box != null) {
                    lib.MaaRectSet(outBox, hit.box.x, hit.box.y, hit.box.w, hit.box.h)
                }
                Ln.i("MaaRunner: $nodeName 命中 ${hit.detail}")
                1
            } catch (t: Throwable) {
                Ln.e("MaaRunner: $nodeName 执行异常", t)
                0
            }
        }

    private val outpostOperatorSelectBestCallback =
        makeOperatorRecognition { rec, p -> rec.decideSelectBest(p) }

    private val outpostOperatorCurrentBestCallback =
        makeOperatorRecognition { rec, p -> rec.decideCurrentBest(p) }

    private val outpostOperatorCurrentUncachedCallback =
        makeOperatorRecognition { rec, p -> rec.decideUncached(p) }

    private val outpostOperatorListBottomCallback =
        makeOperatorRecognition { rec, p -> rec.decideListBottom(p, operatorHost.listOcr(p.roi)) }

    private val outpostOperatorScanOutcomeCallback =
        makeOperatorRecognition { rec, p -> rec.decideScanOutcome(p) }

    /** CacheReady 的「就绪」来自磁盘快照或本任务的 refresh 标记。 */
    private val outpostOperatorCacheReadyCallback =
        makeOperatorRecognition { rec, p ->
            val mode = p.mode
            val ready = if (mode == OperatorSession.MODE_REFRESH) {
                OperatorRuntime.session.isRefreshed()
            } else {
                OperatorRuntime.hasSnapshot()
            }
            rec.decideCacheReady(p, OperatorRecognitions.CacheStatus(ready, OperatorRuntime.snapshotUpdatedAt().orEmpty()))
        }

    /** 冲突识别：ROI 来自回调本身（参数里没有 roi），OCR 弹窗文本后判定来源据点。 */
    private val outpostOperatorConflictCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null || nodeName == null) return@MaaCustomRecognitionCallback 0
        val p = OperatorRecognitions.parseConflictParam(MaaJsonTree.parse(customRecognitionParam))
        if (p == null) {
            Ln.w("MaaRunner: $nodeName 参数不合法: $customRecognitionParam")
            return@MaaCustomRecognitionCallback 0
        }
        operatorHost.hostLib = lib
        operatorHost.hostContext = context
        operatorHost.hostImage = image
        operatorHost.hostLocation = p.location
        try {
            val r = getBoxRect(lib, roi)
            // 冲突弹窗是多段文字（来源据点名 + 提示语），findConflictSource 按位置逐条匹配，需完整检测
            val items = ocrProbe(lib, context, image, intArrayOf(r.x, r.y, r.w, r.h), onlyRec = false).map { item ->
                val b = item.box
                OperatorOcrMatch.Item(
                    item.text,
                    if (b != null && b.size >= 4) OcrBox(b[0], b[1], b[2], b[3]) else OcrBox(0, 0, 0, 0),
                )
            }
            val hit = operatorRecognitions.decideConflict(p, items) as? OperatorRecognitions.Outcome.Hit
                ?: return@MaaCustomRecognitionCallback 0
            Ln.i("MaaRunner: $nodeName 命中 ${hit.detail}")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: $nodeName 执行异常", t)
            0
        }
    }

    /** `OutpostTradingOperatorSession`：7 种 operation 的状态机入口。 */
    private val outpostOperatorSessionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        val p = OperatorSession.parseParam(MaaJsonTree.parse(customActionParam))
        if (p == null) {
            Ln.w("MaaRunner: $nodeName 参数不合法: $customActionParam")
            return@MaaCustomActionCallback 0
        }
        try {
            val outcome = OperatorRuntime.session.run(p)
            // reset 会重建会话，扫描状态表也要一起清（上游同一处做）
            if (p.operation == "reset" && OperatorRuntime.session.lastResetClearedScanStates) {
                OperatorRuntime.scanStates.clear()
                OperatorRuntime.ocrHandoff.clear()
                RunDiagnostics.note(
                    "operator",
                    "会话重置｜mode=${p.mode}，已清扫描状态与 OCR 交接槽",
                    mapOf("stage" to "session", "operation" to "reset", "mode" to p.mode),
                )
            }
            when (outcome) {
                is OperatorSession.SessionOutcome.Failed -> {
                    Ln.w("MaaRunner: $nodeName ${outcome.reason}")
                    RunDiagnostics.note(
                        "operator",
                        "会话动作失败｜operation=${p.operation} reason=${outcome.reason}",
                        mapOf(
                            "stage" to "session",
                            "operation" to p.operation,
                            "result" to "failed",
                            "location" to p.location,
                            "usage" to p.usage,
                            "reason" to outcome.reason,
                        ),
                    )
                    0
                }

                is OperatorSession.SessionOutcome.Assignment -> {
                    Ln.i(
                        "MaaRunner: $nodeName ${outcome.usage} 干员=${outcome.candidate.name} " +
                            "changed=${outcome.changed} 据点=${outcome.location}",
                    )
                    RunDiagnostics.note(
                        "operator",
                        "会话选定｜${outcome.usage} 干员=${outcome.candidate.name} " +
                            "changed=${outcome.changed} 据点=${outcome.location}",
                        mapOf(
                            "stage" to "session",
                            "operation" to p.operation,
                            "result" to "assigned",
                            "usage" to outcome.usage,
                            "location" to outcome.location,
                            "picked" to outcome.candidate.name,
                            "changed" to outcome.changed,
                        ),
                    )
                    1
                }

                is OperatorSession.SessionOutcome.RestoreSkipped -> {
                    Ln.i("MaaRunner: $nodeName 跳过售后派驻 据点=${outcome.location}")
                    RunDiagnostics.note(
                        "operator",
                        "跳过售后派驻｜据点=${outcome.location}",
                        mapOf(
                            "stage" to "session",
                            "operation" to "skip_restore",
                            "result" to "skipped",
                            "location" to outcome.location,
                        ),
                    )
                    1
                }

                is OperatorSession.SessionOutcome.ConflictExcluded -> {
                    Ln.i("MaaRunner: $nodeName 拉黑干员=${outcome.candidate.name} 据点=${outcome.location}")
                    RunDiagnostics.note(
                        "operator",
                        "冲突拉黑｜干员=${outcome.candidate.name} usage=${outcome.usage} 据点=${outcome.location}",
                        mapOf(
                            "stage" to "session",
                            "operation" to "exclude_selected",
                            "result" to "excluded",
                            "usage" to outcome.usage,
                            "location" to outcome.location,
                            "excluded" to outcome.candidate.name,
                        ),
                    )
                    1
                }

                OperatorSession.SessionOutcome.Ok -> 1
            }
        } catch (t: Throwable) {
            Ln.e("MaaRunner: $nodeName 执行异常", t)
            0
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

    // ── 操控类动作（采集/送货/协议空间共用）：对齐上游 CharacterController ──

    private fun motionParam(customActionParam: String?): JsonObject = runCatching {
        Json.parseToJsonElement(customActionParam.orEmpty()).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    private val yawDeltaCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        try {
            val delta = motionParam(customActionParam)["delta"]?.jsonPrimitive?.intOrNull ?: 0
            MotionSupport.yawDelta(delta % 360)
            Ln.i("MaaRunner: CharacterControllerYawDelta [$nodeName] delta=$delta")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: CharacterControllerYawDelta error on node=$nodeName", t)
            0
        }
    }

    private val pitchDeltaCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        try {
            val delta = motionParam(customActionParam)["delta"]?.jsonPrimitive?.intOrNull ?: 0
            MotionSupport.pitchDelta(delta % 360)
            Ln.i("MaaRunner: CharacterControllerPitchDelta [$nodeName] delta=$delta")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: CharacterControllerPitchDelta error on node=$nodeName", t)
            0
        }
    }

    private val forwardAxisCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        try {
            val axis = motionParam(customActionParam)["axis"]?.jsonPrimitive?.intOrNull ?: 0
            // 上游：1 单位 = 100ms 前进；负值后退
            val holdMs = (100 * axis).toLong()
            if (holdMs >= 0) {
                MotionSupport.setMovement(forward = true, left = false, backward = false, right = false)
                Thread.sleep(holdMs)
                MotionSupport.releaseJoystick()
            } else {
                MotionSupport.setMovement(forward = false, left = false, backward = true, right = false)
                Thread.sleep(-holdMs)
                MotionSupport.releaseJoystick()
            }
            Ln.i("MaaRunner: CharacterControllerForwardAxis [$nodeName] axis=$axis")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: CharacterControllerForwardAxis error on node=$nodeName", t)
            0
        }
    }

    private val relativeMoveCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        try {
            val p = motionParam(customActionParam)
            val dx = p["dx"]?.jsonPrimitive?.intOrNull ?: 0
            val dy = p["dy"]?.jsonPrimitive?.intOrNull ?: 0
            MotionSupport.rotateView(dx, dy)
            Ln.i("MaaRunner: CharacterControllerRelativeMove [$nodeName] dx=$dx dy=$dy")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: CharacterControllerRelativeMove error on node=$nodeName", t)
            0
        }
    }

    /**
     * CharacterMoveToTarget：向识别到的目标走近一步。每步最多 500ms，
     * 由管线的 next/重复机制驱动逐步逼近；对齐上游 moveToTarget 单步逻辑。
     */
    private val moveToTargetCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, box, _ ->
        try {
            val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
            val rect = getBoxRect(lib, box)
            if (rect.w <= 0 && rect.h <= 0) {
                Ln.w("MaaRunner: CharacterMoveToTarget [$nodeName] no target box")
                return@MaaCustomActionCallback 0
            }
            val targetCx = rect.x + rect.w / 2
            val targetCy = rect.y + rect.h / 2
            val offsetX = targetCx - MotionSupport.FRAME_W / 2
            val alignThreshold = 60
            when {
                offsetX < -alignThreshold -> MotionSupport.rotateView(offsetX / 3, 0)
                offsetX > alignThreshold -> MotionSupport.rotateView(offsetX / 3, 0)
                targetCy > 480 -> {
                    // 目标已走到下半屏：走过了，退一步
                    MotionSupport.setMovement(forward = false, left = false, backward = true, right = false)
                    Thread.sleep(200)
                    MotionSupport.releaseJoystick()
                }
                else -> {
                    MotionSupport.setMovement(forward = true, left = false, backward = false, right = false)
                    Thread.sleep(200)
                    MotionSupport.releaseJoystick()
                }
            }
            Ln.i("MaaRunner: CharacterMoveToTarget [$nodeName] target=($targetCx,$targetCy) off=$offsetX")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: CharacterMoveToTarget error on node=$nodeName", t)
            0
        }
    }

    /** 目标丢失时的收尾：清理摇杆/冲刺状态，返回成功让管线走 not-found 分支 */
    private val moveToTargetNotFoundCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, _, _, _, _ ->
        try {
            MotionSupport.releaseJoystick()
            MotionSupport.resetSprintState()
            Ln.i("MaaRunner: CharacterMoveToTargetNotFound [$nodeName] motion reset")
            1
        } catch (_: Throwable) {
            1
        }
    }

    /**
     * CharacterSearch：WASD 环绕一圈找目标（对齐上游固定路径），
     * 每步用管线注入的 wait_nodes 节点跑一次识别；找到即返回成功。
     */
    private val characterSearchCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val waitNodes = motionParam(customActionParam)["wait_nodes"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            if (waitNodes.isEmpty()) {
                Ln.w("MaaRunner: CharacterSearch [$nodeName] wait_nodes empty")
                return@MaaCustomActionCallback 0
            }
            // 上游固定环形路径：前2 左2 后4 右4 前4 左2，每步 100ms
            val path = listOf(
                MotionMove(true, false, false, false), MotionMove(true, false, false, false),
                MotionMove(false, true, false, false), MotionMove(false, true, false, false),
                MotionMove(false, false, true, false), MotionMove(false, false, true, false),
                MotionMove(false, false, true, false), MotionMove(false, false, true, false),
                MotionMove(false, false, false, true), MotionMove(false, false, false, true),
                MotionMove(false, false, false, true), MotionMove(false, false, false, true),
                MotionMove(true, false, false, false), MotionMove(true, false, false, false),
                MotionMove(true, false, false, false), MotionMove(true, false, false, false),
                MotionMove(false, true, false, false), MotionMove(false, true, false, false),
            )
            fun found(): Boolean {
                if (!MotionSupport.screencapFresh()) return false
                val imgBuf = lib.MaaImageBufferCreate() ?: return false
                try {
                    if (lib.MaaControllerCachedImage(MaaRunner.currentController, imgBuf).toInt() == 0) return false
                    if (lib.MaaImageBufferIsEmpty(imgBuf).toInt() != 0) return false
                    for (node in waitNodes) {
                        val res = runRecognitionOnce(lib, context, imgBuf, node)
                        if (res?.hit == true) {
                            Ln.i("MaaRunner: CharacterSearch [$nodeName] found '$node'")
                            return true
                        }
                    }
                    return false
                } finally {
                    lib.MaaImageBufferDestroy(imgBuf)
                }
            }
            if (found()) return@MaaCustomActionCallback 1
            for (step in path) {
                MotionSupport.setMovement(step.forward, step.left, step.backward, step.right)
                Thread.sleep(100)
                MotionSupport.releaseJoystick()
                Thread.sleep(1500) // searchInterval：让视角稳定再识别
                if (found()) return@MaaCustomActionCallback 1
            }
            Ln.i("MaaRunner: CharacterSearch [$nodeName] circle done, target not found")
            0
        } catch (t: Throwable) {
            Ln.e("MaaRunner: CharacterSearch error on node=$nodeName", t)
            0
        }
    }

    private data class MotionMove(val forward: Boolean, val left: Boolean, val backward: Boolean, val right: Boolean)

    /**
     * MapNavigateAction（Android 降级实现）。
     * 上游语义（navi_domain_types.h）：
     * - ZONE   无坐标区域声明，等定位稳定（本实现：截图确认画面可读）
     * - NAVMESH 语义寻路（本实现：脉冲前进近似，精确寻路待 MapLocator 移植）
     * - COLLECT 行进中检测采集物，停车触发采集子任务（本实现：到点按交互键）
     * - DIG    精确抵达后触发挖掘（本实现：连按两次交互键）
     * - INTERACT 到点交互（有文本表时 OCR 找点，本实现：直接按交互键）
     * - RUN/TRANSFER/PORTAL 经过式/转移式路点（本实现：脉冲前进）
     */
    /**
     * MapNavigateAction：参数解析已全量移植到 [MapNaviParam]。
     *
     * 执行侧仍是 P1 降级——没有小地图定位，所以 NAVMESH/RUN 用「前进近似」、
     * ZONE 只等画面稳定不做区域校验、TRANSFER/PORTAL 靠定时等待。
     * 但 COLLECT/DIG/INTERACT 走**真实子流水线**，FIND 用 find_stop 做视觉伺服。
     *
     * 参数不合法时整节点失败（上游语义），不做「跳过这个点继续」。
     */
    private val mapNavigateCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        if (context == null || nodeName == null) return@MaaCustomActionCallback 0
        try {
            when (val parsed = MapNaviParam.parseText(customActionParam)) {
                is MapNaviParam.Outcome.NoOp -> 1
                is MapNaviParam.Outcome.Invalid -> {
                    Ln.e("MaaRunner: MapNavigate [$nodeName] 参数不合法：${parsed.reason}")
                    RunDiagnostics.note(
                        "mapnavi",
                        "参数不合法：${parsed.reason}",
                        mapOf("node" to nodeName),
                    )
                    0
                }

                is MapNaviParam.Outcome.Ok -> {
                    RunDiagnostics.note(
                        "mapnavi",
                        "解析成功：${parsed.param.path.size} 个路点 map='${parsed.param.mapName}'",
                        mapOf(
                            "node" to nodeName,
                            "map" to parsed.param.mapName,
                            "waypoints" to parsed.param.path.size,
                        ),
                    )
                    if (runMapNavPath(context, nodeName, parsed.param)) 1 else 0
                }
            }
        } catch (t: Throwable) {
            Ln.e("MaaRunner: MapNavigate error on node=$nodeName", t)
            0
        }
    }

    private data class MapNavPromptSpec(val entry: String, val recognition: String, val exit: String)

    /** 采集点：命中采集物才触发采集。 */
    private val mapNavCollectSpec = MapNavPromptSpec(
        entry = "AutoCollectClickStart",
        recognition = "AutoCollectClick",
        exit = "AutoCollectClickEnd",
    )

    /** 交互点：带 expected 时用它认按钮。 */
    private val mapNavInteractSpec = MapNavPromptSpec(
        entry = "MapNavigatorInteractStart",
        recognition = "MapNavigatorInteract",
        exit = "MapNavigatorInteractEnd",
    )

    /** FIND 搜索预算与每步转视角幅度（上游 find_action.cpp 的 48 步 / 30°）。 */
    private val MAP_NAV_FIND_MAX_STEPS = 48
    private val MAP_NAV_FIND_TURN_DEGREES = 30

    /** P1 执行：无定位降级 + 真实子任务。返回是否整条走通。 */
    private fun runMapNavPath(
        context: Pointer,
        nodeName: String,
        param: MapNaviParam.NaviParam,
    ): Boolean {
        val lib = MaaFrameworkLoader.library ?: return false
        if (param.path.isEmpty()) return true

        // 开环朝向估计：读不到镜头角，HEADING 只能按累计转向推算
        var assumedYaw = 0.0

        for ((index, wp) in param.path.withIndex()) {
            when (wp.action) {
                MapNaviParam.ActionType.ZONE -> {
                    // 声明节点：等画面稳定（无定位，不做区域校验）
                    MotionSupport.screencapFresh()
                    Thread.sleep(600)
                    Ln.i("MaaRunner: MapNavigate [$nodeName] #$index ZONE=${wp.zoneId}")
                }

                MapNaviParam.ActionType.HEADING -> {
                    if (wp.headingUsesTarget) {
                        Ln.w("MaaRunner: MapNavigate [$nodeName] #$index HEADING 以 target 定朝向，需要定位（未移植），跳过")
                    } else {
                        val delta = normalizeDegrees(wp.headingAngle - assumedYaw)
                        MotionSupport.yawDelta(delta.toInt())
                        assumedYaw = wp.headingAngle
                        MotionSupport.pulseForward(270)
                        Thread.sleep(120)
                    }
                }

                MapNaviParam.ActionType.SPRINT -> MotionSupport.sprint(true)

                MapNaviParam.ActionType.JUMP -> {
                    MotionSupport.jump()
                    Thread.sleep(500)
                }

                MapNaviParam.ActionType.FIGHT -> {
                    MotionSupport.attack()
                    Thread.sleep(60)
                }

                MapNaviParam.ActionType.TRANSFER, MapNaviParam.ActionType.PORTAL -> {
                    // 无定位：盲走一段后等转场（真判定要等 P2）
                    MotionSupport.pulseForward(200)
                    Thread.sleep(1500)
                }

                MapNaviParam.ActionType.COLLECT -> runMapNavPromptSubtask(lib, context, mapNavCollectSpec, null, false)

                MapNaviParam.ActionType.DIG -> {
                    lib.MaaContextRunTask(context, "AutoCollectDigStart", """{"AutoCollectDigEnd":{"next":[]}}""")
                    Thread.sleep(80)
                }

                MapNaviParam.ActionType.INTERACT -> runMapNavInteract(lib, context, wp)

                MapNaviParam.ActionType.FIND -> {
                    if (!runMapNavFind(lib, context, nodeName, index, wp)) return false
                }

                MapNaviParam.ActionType.ZIPLINE -> {
                    // 上游用手写点跑滑索会因缺 hop plan 被拒绝；这里明确失败而不是静默按 RUN 走
                    Ln.e("MaaRunner: MapNavigate [$nodeName] #$index ZIPLINE 缺少规划数据（P1 不支持滑索）")
                    return false
                }

                // RUN / NAVMESH：真寻路待 MapLocator 移植，先用前进近似
                MapNaviParam.ActionType.RUN, MapNaviParam.ActionType.NAVMESH -> MotionSupport.pulseForward(400)
            }
        }

        MotionSupport.releaseJoystick()
        MotionSupport.resetSprintState()
        Ln.i("MaaRunner: MapNavigate [$nodeName] path done (${param.path.size} steps)")
        return true
    }

    /** 归一化到 (-180, 180]，用于把绝对朝向换算成相对转向量。 */
    private fun normalizeDegrees(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /**
     * INTERACT：带文字（或 `{"node":...}` 解出来的文字）走子流水线，
     * `rec` 时只认不按；没有文字时连按交互键 5 次（上游 navi_config 的 5 连击）。
     */
    private fun runMapNavInteract(lib: MaaFrameworkLibrary, context: Pointer, wp: MapNaviParam.Waypoint) {
        val expected = resolveMapNavExpectedText(lib, context, wp.interactText, wp.interactTextNode)
        if (expected.isNotEmpty()) {
            runMapNavPromptSubtask(lib, context, mapNavInteractSpec, expected, wp.interactRec)
            return
        }
        if (wp.interactRec) {
            Ln.i("MaaRunner: MapNavigate INTERACT 只认不按（rec=true）")
            return
        }
        repeat(5) { MotionSupport.interact(100) }
    }

    /** 把 `{"node": "..."}` 形态解成该节点 recognition.param.expected 的文字列表。 */
    private fun resolveMapNavExpectedText(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        inline: List<String>,
        nodeName: String,
    ): List<String> {
        if (inline.isNotEmpty()) return inline
        if (nodeName.isEmpty()) return emptyList()
        val nodeJson = nodeDefinitionJson(lib, context, nodeName) ?: return emptyList()
        return try {
            val expected = Json.parseToJsonElement(nodeJson).jsonObject["recognition"]
                ?.jsonObject?.get("param")?.jsonObject?.get("expected")
            when (expected) {
                is JsonArray -> expected.mapNotNull { it.jsonPrimitive.contentOrNull }
                is JsonPrimitive -> listOfNotNull(expected.contentOrNull)
                else -> emptyList()
            }
        } catch (t: Throwable) {
            Ln.w("MaaRunner: MapNavigate 解析交互文字节点 '$nodeName' 失败：${t.message}")
            emptyList()
        }
    }

    /**
     * 跑一个「提示驱动」子流水线：截断共用出口，按需注入 expected 与「只认不按」。
     * 对齐上游 async_prompt_action.cpp。
     */
    private fun runMapNavPromptSubtask(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        spec: MapNavPromptSpec,
        expected: List<String>?,
        rec: Boolean,
    ) {
        val override = buildJsonObject {
            put(spec.exit, buildJsonObject { put("next", JsonArray(emptyList())) })
            if (expected != null || rec) {
                put(spec.recognition, buildJsonObject {
                    if (expected != null) {
                        put("recognition", buildJsonObject {
                            put("param", buildJsonObject {
                                put("expected", JsonArray(expected.map { JsonPrimitive(it) }))
                            })
                        })
                    }
                    if (rec) put("action", buildJsonObject { put("type", "DoNothing") })
                })
            }
        }
        lib.MaaContextRunTask(context, spec.entry, override.toString())
        Thread.sleep(80)
    }

    /**
     * FIND：只用 find_stop 做视觉伺服（转视角搜索 + 识别）。
     *
     * 只配了 `find_arrive` 的点在 P1 无法完成（需要定位），**明确失败**而不是静默跳过——
     * 静默跳过会让调用方以为走到了。
     */
    private fun runMapNavFind(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        nodeName: String,
        index: Int,
        wp: MapNaviParam.Waypoint,
    ): Boolean {
        if (wp.findStop.isEmpty()) {
            Ln.e("MaaRunner: MapNavigate [$nodeName] #$index FIND 只配了 find_arrive，需要定位（未移植）")
            return false
        }
        repeat(MAP_NAV_FIND_MAX_STEPS) { step ->
            if (probeMapNavRecognition(lib, context, wp.findStop)) {
                Ln.i("MaaRunner: MapNavigate [$nodeName] #$index FIND 命中 ${wp.findStop}（第 ${step + 1} 步）")
                return true
            }
            MotionSupport.yawDelta(MAP_NAV_FIND_TURN_DEGREES)
            Thread.sleep(150)
        }
        // 上游到预算是放弃该点；这里保留这个语义但打 warn，不拖垮整条路线
        Ln.w("MaaRunner: MapNavigate [$nodeName] #$index FIND 用尽搜索预算未命中 ${wp.findStop}")
        return true
    }

    /** 截图后跑一次识别节点，返回是否命中。 */
    private fun probeMapNavRecognition(lib: MaaFrameworkLibrary, context: Pointer, nodeName: String): Boolean {
        val ctrl = controller ?: return false
        val capId = lib.MaaControllerPostScreencap(ctrl)
        if (capId > 0) lib.MaaControllerWait(ctrl, capId)
        val imgBuf = lib.MaaImageBufferCreate() ?: return false
        return try {
            if (lib.MaaControllerCachedImage(ctrl, imgBuf).toInt() == 0) return false
            lib.MaaContextRunRecognition(context, nodeName, "{}", imgBuf) > 0L
        } catch (t: Throwable) {
            Ln.w("MaaRunner: MapNavigate 识别 '$nodeName' 失败：${t.message}")
            false
        } finally {
            lib.MaaImageBufferDestroy(imgBuf)
        }
    }

    /** 读全局滑索偏好（MapNavigatorZiplinePreference.attach.zipline）；auto/true→true */
    private fun readZiplinePreference(lib: MaaFrameworkLibrary, context: Pointer): Boolean {
        return try {
            val nodeJson = nodeDefinitionJson(lib, context, "MapNavigatorZiplinePreference") ?: return true
            val attach = Json.parseToJsonElement(nodeJson).jsonObject["attach"]?.jsonObject
            attach?.get("zipline")?.jsonPrimitive?.contentOrNull != "false"
        } catch (_: Exception) {
            true
        }
    }

    /**
     * AutoDeliveryResolveDepotAction：从任务详情 OCR 的区域文本匹配仓储点，
     * 生成对应的导航路线 override（对齐上游同名 go action，走路/滑索二选一）。
     */
    private val resolveDepotCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null || nodeName == null) return@MaaCustomActionCallback 0
        try {
            val p = motionParam(customActionParam)
            // 区域文本优先取参数显式传入，否则用当前节点定义里的 OCR expected 文本
            val areaText = p["area_text"]?.jsonPrimitive?.contentOrNull
                ?: nodeDefinitionJson(lib, context, nodeName).orEmpty()
                    .let { RecoDetail.collectOcrTexts(it).firstOrNull() }
                    .orEmpty()
            if (areaText.isBlank()) throw AutoDeliverySupport.ResolveException("区域 OCR 文本缺失")
            val (area, match) = AutoDeliverySupport.resolveArea(areaText)
            val route = AutoDeliverySupport.depotOf(area.depotId)
            // zip 优先取 action 参数，其次读全局滑索偏好节点（auto→true）
            val zip = p["zip"]?.jsonPrimitive?.booleanOrNull ?: readZiplinePreference(lib, context)
            if (route.ziplineOnly && !zip) {
                throw AutoDeliverySupport.ResolveException("仓储「${route.nameZh}」只能经滑索抵达，请开启滑索偏好")
            }
            lib.MaaContextOverridePipeline(
                context,
                AutoDeliverySupport.depotNavigationOverride(route, zip).toString(),
            )
            Ln.i("MaaRunner: AutoDeliveryResolveDepot [$nodeName] area='${area.id}' sim=%.3f depot='${route.id}' zip=$zip".format(match.similarity))
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: AutoDeliveryResolveDepot error on node=$nodeName", t)
            0
        }
    }

    /**
     * AutoDeliveryResolveDestinationAction：OCR 目标文本（或参数指定 destination_id）
     * 匹配终点，生成导航路线 override。
     */
    private val resolveDestinationCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null || nodeName == null) return@MaaCustomActionCallback 0
        try {
            val p = motionParam(customActionParam)
            val destText = p["destination_text"]?.jsonPrimitive?.contentOrNull
                ?: nodeDefinitionJson(lib, context, nodeName).orEmpty()
                    .let { RecoDetail.collectOcrTexts(it).firstOrNull() }
                    .orEmpty()
            if (destText.isBlank()) throw AutoDeliverySupport.ResolveException("目标 OCR 文本缺失")
            val (dest, match) = AutoDeliverySupport.resolveDestination(destText)
            val zip = p["zip"]?.jsonPrimitive?.booleanOrNull ?: readZiplinePreference(lib, context)
            if (dest.ziplineOnly && !zip) {
                throw AutoDeliverySupport.ResolveException("终点「${dest.nameTexts.firstOrNull()}」只能经滑索抵达，请开启滑索偏好")
            }
            lib.MaaContextOverridePipeline(
                context,
                AutoDeliverySupport.destinationNavigationOverride(dest, zip).toString(),
            )
            Ln.i("MaaRunner: AutoDeliveryResolveDestination [$nodeName] dest='${dest.id}' sim=%.3f zip=$zip".format(match.similarity))
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: AutoDeliveryResolveDestination error on node=$nodeName", t)
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

    /** 售卖扫描按 region 缓存的物资名（对齐上游 autosell.regionItemMap） */
    private val regionItemMap = ConcurrentHashMap<String, List<String>>()

    private data class RecoResult(val hit: Boolean, val detailJson: String?, val box: IntArray?)

    private fun runRecognitionOnce(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        nodeName: String,
    ): RecoResult? {
        val recoId = lib.MaaContextRunRecognition(context, nodeName, "{}", image)
        if (recoId <= 0L) return null
        val tasker = lib.MaaContextGetTasker(context) ?: return null
        val hitMem = Memory(1)
        val tempRect = lib.MaaRectCreate()
        val detailBuf = lib.MaaStringBufferCreate()
        return try {
            if (lib.MaaTaskerGetRecognitionDetail(tasker, recoId, null, null, hitMem, tempRect, detailBuf, null, null).toInt() == 0) {
                return null
            }
            val hit = hitMem.getByte(0).toInt() != 0
            val detailJson = detailBuf?.let { lib.MaaStringBufferGet(it) }
            val box = tempRect?.let {
                val r = getBoxRect(lib, it)
                intArrayOf(r.x, r.y, r.w, r.h)
            }
            RecoResult(hit, detailJson, box)
        } finally {
            if (tempRect != null) lib.MaaRectDestroy(tempRect)
            if (detailBuf != null) lib.MaaStringBufferDestroy(detailBuf)
        }
    }

    /** 按 recoId 取识别详情 JSON。BetterSliding 宿主与数量控制共用。 */
    private fun recognitionDetailJson(lib: MaaFrameworkLibrary, context: Pointer, recoId: Long): String? {
        if (recoId <= 0L) {
            // 回调 recoId 为 0 只会出现在 action-only 节点（框架 Go 绑定 custom_action.go 注释，
            // 与 `Context.RunAction` 同源）；正常 pipeline 驱动节点不会走到这里。
            Ln.w("MaaRunner: recognitionDetailJson 收到非法 recoId=$recoId")
            return null
        }
        val tasker = lib.MaaContextGetTasker(context) ?: run {
            Ln.w("MaaRunner: recognitionDetailJson MaaContextGetTasker 返回空 (recoId=$recoId)")
            return null
        }
        val detailBuf = lib.MaaStringBufferCreate() ?: return null
        val tempRect = lib.MaaRectCreate()
        val hitMem = Memory(1)
        return try {
            val ret = lib.MaaTaskerGetRecognitionDetail(
                tasker, recoId, null, null, hitMem, tempRect, detailBuf, null, null,
            ).toInt()
            if (ret == 0) {
                Ln.w("MaaRunner: MaaTaskerGetRecognitionDetail 返回 0 (recoId=$recoId)")
                null
            } else {
                lib.MaaStringBufferGet(detailBuf)
            }
        } catch (t: Throwable) {
            Ln.w("MaaRunner: 读取识别详情失败: ${t.message}")
            null
        } finally {
            if (tempRect != null) lib.MaaRectDestroy(tempRect)
            lib.MaaStringBufferDestroy(detailBuf)
        }
    }

    private fun nodeDefinitionJson(lib: MaaFrameworkLibrary, context: Pointer, nodeName: String): String? {
        val buf = lib.MaaStringBufferCreate() ?: return null
        return try {
            if (lib.MaaContextGetNodeData(context, nodeName, buf).toInt() == 0) null
            else lib.MaaStringBufferGet(buf)
        } finally {
            lib.MaaStringBufferDestroy(buf)
        }
    }

    /** 对齐上游 expressionrecognition.runNumericRecognition：解析节点 OCR 数字 */
    private fun recognitionNumber(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        nodeName: String,
    ): Int {
        val res = runRecognitionOnce(lib, context, image, nodeName)
            ?: throw IllegalArgumentException("run recognition failed: $nodeName")
        val nodeJson = nodeDefinitionJson(lib, context, nodeName)
        val text = selectOcrText(res.detailJson, nodeJson)
            ?: throw IllegalArgumentException("no ocr text for $nodeName")
        return OcrNum.parse(text)
    }

    /** 按节点形状（And/box_index）从 detail 里取目标 OCR 文本 */
    private fun selectOcrText(detailJson: String?, nodeJson: String?): String? {
        if (detailJson.isNullOrBlank()) return null
        return try {
            val root = Json.parseToJsonElement(detailJson).jsonObject
            val shape = RecoDetail.parseNodeShape(nodeJson)
            val target = if (shape?.type == "And") {
                val all = root["all"]?.jsonArray
                if (all != null && all.isNotEmpty()) {
                    all[shape.boxIndex.coerceIn(0, all.size - 1)].jsonObject
                } else root
            } else root
            val inner = target["detail"]?.jsonObject ?: target
            val texts = RecoDetail.collectOcrTexts(inner.toString())
            // 价格/数量场景优先取含数字的，否则取第一个文本
            texts.firstOrNull { it.any(Char::isDigit) } ?: texts.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** 对齐上游 ExpressionRecognition：`{节点}` 数字表达式求值 */
    private val expressionRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val paramObj = runCatching {
                Json.parseToJsonElement(customRecognitionParam.orEmpty()).jsonObject
            }.getOrNull() ?: return@MaaCustomRecognitionCallback 0
            val expression = paramObj["expression"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val boxNode = paramObj["box_node"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (expression.isEmpty()) return@MaaCustomRecognitionCallback 0
            val matched: Boolean = try {
                BoolExpr.evaluate(expression) { name ->
                    recognitionNumber(lib, context, image, name)
                }
            } catch (_: Exception) {
                // 上游同样语义：画面/节点结果不稳定时本次不匹配，等下一次重试
                return@MaaCustomRecognitionCallback 0
            }
            Ln.i("MaaRunner: ExpressionRecognition [$nodeName] expr='$expression' matched=$matched")
            if (!matched) return@MaaCustomRecognitionCallback 0
            if (outBox != null) {
                val box: IntArray? = if (boxNode.isNotEmpty()) {
                    runRecognitionOnce(lib, context, image, boxNode)?.box
                } else {
                    val r = getBoxRect(lib, roi)
                    intArrayOf(r.x, r.y, r.w, r.h)
                }
                if (box != null) lib.MaaRectSet(outBox, box[0], box[1], box[2], box[3])
            }
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: ExpressionRecognition error on node=$nodeName", t)
            0
        }
    }

    /** 对齐上游 autosell.AutoSellScanItemRecognition：OCR 物资名并按 region 缓存 */
    private val autoSellScanItemRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val region = runCatching {
                Json.parseToJsonElement(customRecognitionParam.orEmpty()).jsonObject["region"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty()
            if (region.isBlank()) {
                Ln.w("MaaRunner: AutoSellScanItemRecognition empty region on node=$nodeName")
                return@MaaCustomRecognitionCallback 0
            }
            val res = runRecognitionOnce(lib, context, image, "AutoSellStockRedistributionItemText")
                ?: return@MaaCustomRecognitionCallback 0
            if (!res.hit) {
                Ln.w("MaaRunner: AutoSellScanItemRecognition item text not hit")
                return@MaaCustomRecognitionCallback 0
            }
            val names = RecoDetail.collectOcrTexts(res.detailJson)
            if (names.isEmpty()) {
                Ln.w("MaaRunner: AutoSellScanItemRecognition no item names")
                return@MaaCustomRecognitionCallback 0
            }
            regionItemMap[region] = names
            Ln.i("MaaRunner: AutoSellScanItemRecognition region=$region items=$names")
            if (outBox != null) {
                val r = getBoxRect(lib, roi)
                lib.MaaRectSet(outBox, r.x, r.y, r.w, r.h)
            }
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: AutoSellScanItemRecognition error on node=$nodeName", t)
            0
        }
    }

    /** 对齐上游 autosell.AutoSellItemExecuteItemTaskAction：按关键词定价并逐件执行售卖子任务 */
    private val autoSellItemExecuteItemTaskActionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val json = Json.parseToJsonElement(customActionParam.orEmpty()).jsonObject
            val region = json["region"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val moderatePrice = json["moderate_price"]?.jsonPrimitive?.intOrNull ?: 0
            val largePrice = json["large_price"]?.jsonPrimitive?.intOrNull ?: 0
            val massivePrice = json["massive_price"]?.jsonPrimitive?.intOrNull ?: 0
            if (region.isBlank()) {
                Ln.w("MaaRunner: AutoSellItemExecuteItemTaskAction empty region on node=$nodeName")
                return@MaaCustomActionCallback 0
            }
            val names = regionItemMap[region] ?: return@MaaCustomActionCallback 1
            for (name in names) {
                if (isStopRequested()) return@MaaCustomActionCallback 0
                val modKey = AutoSellKeywords.firstContainedKeyword(name, AutoSellKeywords.moderate)
                val largeKey = AutoSellKeywords.firstContainedKeyword(name, AutoSellKeywords.large)
                val massiveKey = AutoSellKeywords.firstContainedKeyword(name, AutoSellKeywords.massive)
                val targetPrice: Int
                val targetName: String
                when {
                    modKey.isNotEmpty() -> { targetPrice = moderatePrice; targetName = modKey }
                    largeKey.isNotEmpty() -> { targetPrice = largePrice; targetName = largeKey }
                    massiveKey.isNotEmpty() -> { targetPrice = massivePrice; targetName = massiveKey }
                    else -> {
                        Ln.w("MaaRunner: AutoSellItemExecuteItemTaskAction unknown item '$name', skip")
                        continue
                    }
                }
                Ln.i("MaaRunner: AutoSellItemExecuteItemTaskAction [$nodeName] sell '$name' as '$targetName' price>=$targetPrice")
                val override = buildJsonObject {
                    put("AutoSellStockRedistributionItemOpenPrepareRegionalDevelopmentValleyIV", buildJsonObject {
                        put("enabled", region == "ValleyIV")
                    })
                    put("AutoSellStockRedistributionItemOpenPrepareRegionalDevelopmentWuling", buildJsonObject {
                        put("enabled", region == "Wuling")
                    })
                    put("AutoSellStockRedistributionItemOpenPrepareFriendsSwitchValleyIV", buildJsonObject {
                        put("enabled", region == "ValleyIV")
                    })
                    put("AutoSellStockRedistributionItemOpenPrepareFriendsSwitchWuling", buildJsonObject {
                        put("enabled", region == "Wuling")
                    })
                    put("AutoSellStockRedistributionItemOpenPrepareFriendsFailedToValleyIV", buildJsonObject {
                        put("enabled", region == "ValleyIV")
                    })
                    put("AutoSellStockRedistributionItemOpenPrepareFriendsFailedToWuling", buildJsonObject {
                        put("enabled", region == "Wuling")
                    })
                    put("AutoSellFriendsPricesExpected", buildJsonObject {
                        put("custom_recognition_param", buildJsonObject {
                            put("expression", "{AutoSellFriendsPriceRecognition} >= $targetPrice")
                            put("focus_matched_resolved_expression", true)
                            put("focus_unmatched_resolved_expression", true)
                        })
                    })
                    put("AutoSellFriendsPricesExpectedBuy", buildJsonObject {
                        put("custom_recognition_param", buildJsonObject {
                            put("expression", "{AutoSellFriendsPriceCurrentRecognition} >= $targetPrice")
                        })
                    })
                    put("AutoSellStockRedistributionItemFindTextRecognition", buildJsonObject {
                        put("expected", targetName)
                    })
                }.toString()
                val subId = lib.MaaContextRunTask(context, "AutoSellStockRedistributionItemOpenPrepare", override)
                if (subId <= 0L) {
                    Ln.w("MaaRunner: AutoSellItemExecuteItemTaskAction prepare failed for '$name'")
                    return@MaaCustomActionCallback 0
                }
            }
            1
        } catch (t: Throwable) {
                Ln.e("MaaRunner: AutoSellItemExecuteItemTaskAction error on node=$nodeName", t)
            0
        }
    }

    /** 据点各 location 已尝试过的货物名（select 去重；exhausted 确认后清空） */
    private val outpostTried = ConcurrentHashMap<String, MutableSet<String>>()

    /** 任务 option 注入的选品策略（对齐上游 configure_strategy：price/rarity/stock） */
    @Volatile
    private var outpostStrategy: String = ""

    /**
     * 低买高卖套利排序：对每个候选货物算跨据点价差
     * V = max(他据点卖价) − 本据点买价，价差大者优先（买得相对便宜、卖得相对最贵）。
     * 无价差数据的货物排最后；排序稳定，同价差保持原表顺序。
     */
    private fun outpostArbitrageOrder(names: List<String>, location: String): List<String> {
        val buyPrices = OutpostData.unitPriceByLocation[location]
        if (buyPrices.isNullOrEmpty()) return names
        fun spread(name: String): Int {
            val buy = buyPrices[name] ?: return Int.MIN_VALUE
            var bestSell = Int.MIN_VALUE
            for ((_, sellPrices) in OutpostData.unitPriceByLocation) {
                if (sellPrices === buyPrices) continue
                val p = sellPrices[name] ?: continue
                if (p > bestSell) bestSell = p
            }
            return if (bestSell == Int.MIN_VALUE) Int.MIN_VALUE else bestSell - buy
        }
        return names.sortedWith(compareByDescending { spread(it) })
    }

    /** 解析 JSON 里的 [x,y,w,h] 数组；不是四个整数就当没给 */
    private fun intArrayOrNull(el: kotlinx.serialization.json.JsonElement?): IntArray? {
        val arr = el as? kotlinx.serialization.json.JsonArray ?: return null
        val nums = arr.mapNotNull { it.jsonPrimitive.intOrNull }
        return if (nums.size >= 4) intArrayOf(nums[0], nums[1], nums[2], nums[3]) else null
    }

    /** 一帧 OCR 探针的原始结果：hit 与从 detail 收集到的条目。 */
    private data class OcrProbeFrame(val hit: Boolean, val items: List<GoodsSupport.OcrItem>)

    /**
     * 跑一帧探针 OCR（不重试）。hit 单独留着：`best_result_=null` 时命中为假，
     * 这正是坏帧防御要识别的信号，不能只看 collect 出来的条目。
     */
    private fun runOcrProbeFrame(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        roiBox: IntArray?,
        onlyRec: Boolean,
        colorFilter: String?,
    ): OcrProbeFrame {
        // 货卡上大片彩色插画，不做颜色过滤的话 OCR 只能读出零星几个偏旁
        // （实测整屏只回 ['手','手','手']）。上游 AutoStockpileGetGoods 用的就是
        // AutoStockpileGoodsFilter：把灰度 60~150 的像素留下、其余刷黑，等价于
        // 「只保留价格与商品名的浅色文字」。
        //
        // 默认关掉：这个过滤是为货卡调的，套到据点交易等别的界面上会把内容刷没。
        // 只有 [goodsOcrProbe] 显式打开。
        lib.MaaContextOverridePipeline(context, OcrProbeSupport.buildOverride(roiBox, onlyRec, colorFilter))
        val res = runRecognitionOnce(lib, context, image, OcrProbeSupport.NODE)
        val hit = res?.hit == true
        val items = if (res == null) emptyList() else GoodsSupport.collectOcrItems(res.detailJson)
        return OcrProbeFrame(hit, items)
    }

    /**
     * 用临时 OCR 探针节点识别指定区域，返回 (文本, 框)。
     *
     * [onlyRec] 默认关：只有 ROI 里确定只有一个数值（价格、数量…）时才开，
     * 多条目网格/列表必须保持 false，否则整段 ROI 会被当成一行文字、只回一个大框。
     * 判定原则见 [OcrProbeSupport]。
     */
    private fun ocrProbe(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        roiBox: IntArray?,
        allowRetry: Boolean = true,
        colorFilter: String? = null,
        onlyRec: Boolean = false,
    ): List<GoodsSupport.OcrItem> {
        val frame = runOcrProbeFrame(lib, context, image, roiBox, onlyRec, colorFilter)
        if (!OcrProbeSupport.isSuspicious(frame.hit, frame.items) || !allowRetry) {
            // 非命中帧里的条目只是 all_results_ 的原始候选/噪声，不交给上层做文本匹配
            return if (frame.hit) frame.items else emptyList()
        }
        // 坏帧防御：撕裂帧的 OCR 会整体空掉、整帧单框，或所有候选都不过阈值（hit=false）。
        // 这些情况都刷帧重探；cachedOcrProbe 内部直接走 runOcrProbeFrame，不会递归重试。
        val ctrl = controller ?: return if (frame.hit) frame.items else emptyList()
        return cachedOcrProbe(lib, ctrl, context, roiBox, onlyRec = onlyRec, colorFilter = colorFilter)
    }

    /**
     * 探针帧捕获：把「实际送去 OCR 的那一帧」的 PNG 字节留在内存里，供失败时导出。
     *
     * 只在诊断开启时由 [goodsOcrProbe] 建一个；[cachedOcrProbe] 每成功取到一帧就覆盖，
     * 所以最终留下的是本轮**最后一次** OCR 用的帧。写盘与否由上层在确认「全 0 候选」后决定，
     * 避免每次尝试都写盘。
     */
    private class ProbeFrameCapture {
        var pngBytes: ByteArray? = null
    }

    /**
     * 从图像 buffer 读出编码后（PNG）字节；失败返回 null，绝不抛。
     *
     * 只用于诊断导出；正常识别路径不读字节，避免无谓拷贝。
     */
    private fun readEncodedImage(lib: MaaFrameworkLibrary, image: Pointer): ByteArray? = runCatching {
        val size = lib.MaaImageBufferGetEncodedSize(image)
        if (size <= 0) return@runCatching null
        val data = lib.MaaImageBufferGetEncoded(image) ?: return@runCatching null
        data.getByteArray(0, size.toInt())
    }.getOrNull()

    /**
     * 从 controller 缓存帧做 OCR 探针；可疑结果时刷帧重试（坏帧防御）。
     *
     * [preferredImage] 是调用方（识别回调）手上那一帧（框架本次识别用的 `image`）：非空时
     * **第一轮**直接拿它 OCR，不再自己 `PostScreencap`——「OCR 看到的就是框架看到的」。
     * 之后的重试轮、以及 [preferredImage] 为空/空 buffer 时，仍走原来的自抓退路。
     * 这样既优先用框架帧，又保留「坏帧重试要换新帧」与「动作回调没有 image」两类自抓场景。
     */
    private fun cachedOcrProbe(
        lib: MaaFrameworkLibrary,
        ctrl: Pointer,
        context: Pointer,
        roiBox: IntArray?,
        attempts: Int = 2,
        colorFilter: String? = null,
        onlyRec: Boolean = false,
        capture: ProbeFrameCapture? = null,
        preferredImage: Pointer? = null,
    ): List<GoodsSupport.OcrItem> {
        var items: List<GoodsSupport.OcrItem> = emptyList()
        repeat(attempts) { attemptIndex ->
            // 首选帧只用于第一轮；重试要换新帧。指针为空、或 buffer 为空（未来从别处调用）
            // 都明确回退自抓，绝不把空帧送去 OCR。
            val usePreferred = preferredImage != null &&
                OcrProbeSupport.shouldUsePreferredFrame(attemptIndex, hasPreferredFrame = true) &&
                lib.MaaImageBufferIsEmpty(preferredImage).toInt() == 0
            var owned = false
            var capId = 0L
            val imgBuf: Pointer = if (usePreferred) {
                Ln.i("MaaRunner: cachedOcrProbe 使用框架帧 attempt=$attemptIndex")
                preferredImage!!
            } else {
                capId = lib.MaaControllerPostScreencap(ctrl)
                if (capId > 0) lib.MaaControllerWait(ctrl, capId)
                val buf = lib.MaaImageBufferCreate() ?: return emptyList()
                owned = true
                buf
            }
            try {
                if (owned) {
                    val cachedRc = lib.MaaControllerCachedImage(ctrl, imgBuf).toInt()
                    if (cachedRc == 0) {
                        // 取帧失败会静默跳过本轮，现象也是 0 候选；记下返回值便于与「真读到乱码」区分
                        Ln.w("MaaRunner: cachedOcrProbe 取帧失败 capId=$capId cachedImage=$cachedRc")
                        return@repeat
                    }
                    val emptyRc = lib.MaaImageBufferIsEmpty(imgBuf).toInt()
                    if (emptyRc != 0) {
                        Ln.w("MaaRunner: cachedOcrProbe 帧为空 capId=$capId cachedImage=$cachedRc isEmpty=$emptyRc")
                        return@repeat
                    }
                    Ln.i("MaaRunner: cachedOcrProbe 取帧 ok capId=$capId cachedImage=$cachedRc isEmpty=$emptyRc")
                }
                // 诊断：留一份实际输入帧，只有全 0 候选时上层才会真正落盘
                capture?.let { holder -> readEncodedImage(lib, imgBuf)?.let { holder.pngBytes = it } }
                val frame = runOcrProbeFrame(lib, context, imgBuf, roiBox, onlyRec, colorFilter)
                // 非命中帧的条目只是 all_results_ 噪声，别让上层拿去匹配文本（保持旧语义）
                items = if (frame.hit) frame.items else emptyList()
                if (!OcrProbeSupport.isSuspicious(frame.hit, frame.items)) return items
            } finally {
                if (owned) lib.MaaImageBufferDestroy(imgBuf)
            }
        }
        return items
    }

    /** 对齐上游 OutpostTradingPriorityItem：按 location 贪心选首个未尝试货物 */
    private val outpostTradingPriorityItemCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val p = Json.parseToJsonElement(customRecognitionParam.orEmpty()).jsonObject
            val location = p["location"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val result = p["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // 低买：custom_recognition_param.bias（价升序优先表）优先，其次 strategy=price 时查价排序
            val bias = runCatching {
                p["bias"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty()
            val names = OutpostData.locationItems[location]
            if (location.isBlank() || names.isNullOrEmpty()) {
                Ln.w("MaaRunner: OutpostTradingPriorityItem unknown location='$location' on node=$nodeName")
                return@MaaCustomRecognitionCallback 0
            }
            val prefer: List<String>? = when {
                bias.isNotBlank() -> bias.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                outpostStrategy == "price" -> outpostArbitrageOrder(names, location)
                else -> null
            }
            val r = getBoxRect(lib, roi)
            // 货卡网格：一个 ROI 里多个货名+价格，只有完整检测才能分出多条（onlyRec=false）
            val items = ocrProbe(lib, context, image, intArrayOf(r.x, r.y, r.w, r.h), onlyRec = false)
            if (result == "exhausted") {
                val tried = outpostTried[location].orEmpty()
                val m = GoodsSupport.findBestMatch(items, names, tried, prefer)
                if (m == null) {
                    outpostTried.remove(location)
                    Ln.i("MaaRunner: OutpostTradingPriorityItem [$nodeName] location=$location exhausted")
                    if (outBox != null) lib.MaaRectSet(outBox, r.x, r.y, r.w, r.h)
                    return@MaaCustomRecognitionCallback 1
                }
                return@MaaCustomRecognitionCallback 0
            }
            val tried = outpostTried.getOrPut(location) { ConcurrentHashMap.newKeySet() }
            val m = GoodsSupport.findBestMatch(items, names, tried, prefer)
                ?: return@MaaCustomRecognitionCallback 0
            GoodsSupport.standardName(m.text, names)?.let { tried += it }
            if (outBox != null && m.box != null) {
                lib.MaaRectSet(outBox, m.box[0], m.box[1], m.box[2], m.box[3])
            }
            Ln.i("MaaRunner: OutpostTradingPriorityItem [$nodeName] location=$location select='${m.text}'")
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingPriorityItem error on node=$nodeName", t)
            0
        }
    }

    /** OutpostTradingCurrentGoods：返回当前可见的首个货物框 */
    private val outpostTradingCurrentGoodsCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val p = runCatching {
                Json.parseToJsonElement(customRecognitionParam.orEmpty()).jsonObject
            }.getOrNull()
            val location = p?.get("location")?.jsonPrimitive?.contentOrNull.orEmpty()
            val names = if (location.isNotBlank()) {
                OutpostData.locationItems[location]
            } else {
                OutpostData.locationItems.values.flatten().distinct()
            } ?: return@MaaCustomRecognitionCallback 0
            val r = getBoxRect(lib, roi)
            // 货卡网格：一个 ROI 里多个货名+价格，只有完整检测才能分出多条（onlyRec=false）
            val items = ocrProbe(lib, context, image, intArrayOf(r.x, r.y, r.w, r.h), onlyRec = false)
            val m = GoodsSupport.findFirstMatch(items, names)
                ?: return@MaaCustomRecognitionCallback 0
            if (outBox != null && m.box != null) {
                lib.MaaRectSet(outBox, m.box[0], m.box[1], m.box[2], m.box[3])
            }
            Ln.i("MaaRunner: OutpostTradingCurrentGoods [$nodeName] '${m.text}'")
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingCurrentGoods error on node=$nodeName", t)
            0
        }
    }

    /**
     * OutpostTradingPrioritySession：对齐上游据点交易会话 action。Android 侧已移植
     * configure_strategy：接收任务 option 注入的选品策略（price = 价升序，低买高卖用），
     * 买侧 OutpostTradingPriorityItem 识别回调按该策略重排候选优先序。
     * 其余 operation（reset_goods_selection/adopt/configure）尚未移植，保持 noop 成功。
     */
    private val outpostPrioritySessionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        try {
            val p = runCatching {
                Json.parseToJsonElement(customActionParam.orEmpty()).jsonObject
            }.getOrNull()
            when (p?.get("operation")?.jsonPrimitive?.contentOrNull.orEmpty()) {
                "configure_strategy" -> {
                    val strategy = p?.get("strategy")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (strategy in listOf("price", "rarity", "stock", "")) {
                        outpostStrategy = strategy
                        Ln.i("MaaRunner: OutpostTradingPrioritySession [$nodeName] configure_strategy=$strategy")
                    } else {
                        Ln.w("MaaRunner: OutpostTradingPrioritySession [$nodeName] unknown strategy=$strategy, keep=$outpostStrategy")
                    }
                }
                else -> {}
            }
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingPrioritySession error on node=$nodeName", t)
            1
        }
    }

    /**
     * 对齐上游 AutoStockpile.Recognition：OCR 本页货组、绑价、选货。
     *
     * 上游把整页货组、配额、阈值都算完塞进 detail；移动端没有那套配置，退化成
     * 「本页可见货组里挑最便宜的」，选中项放 [AutoStockpileSupport.Session]，
     * 由 SelectItem 把它接到 SelectedGoodsClick 上。
     *
     * region 从节点名 AutoStockpileDecision<Region> 取，与上游
     * resolveGoodsRegionFromTaskNode 同源。
     */
    private val autoStockpileRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, _, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) return@MaaCustomRecognitionCallback 0
        try {
            val node = nodeName.orEmpty()
            val region = node.removePrefix("AutoStockpileDecision")
            if (AutoStockpileSupport.itemDefsFor(region).isEmpty()) {
                Ln.w("MaaRunner: AutoStockpile.Recognition unknown region '$region' on node=$node")
                return@MaaCustomRecognitionCallback 0
            }
            val ctrl = controller ?: return@MaaCustomRecognitionCallback 0
            // image 是框架本次识别用的那一帧，透传下去优先 OCR（可能为空，探针内部回退自抓）
            val items = goodsOcrProbe(lib, ctrl, context, AutoStockpileSupport.goodsRoi, region, image)
            val candidates = AutoStockpileSupport.scan(items, region)
            val pick = AutoStockpileSupport.Session.decide(region, node, candidates)
            if (pick == null) {
                // 本区候选已试完：把中继指向 Skip，本区就此收尾而不是把整任务判失败
                Ln.i("MaaRunner: AutoStockpile.Recognition [$nodeName] no candidate left (${candidates.size} seen), route to skip")
                RunDiagnostics.note(
                    "autostockpile",
                    "本区候选已试完，转 Skip（region=$region seen=${candidates.size}）",
                    mapOf("node" to node, "region" to region, "candidates" to candidates.size),
                )
                lib.MaaContextOverridePipeline(
                    context,
                    "{\"AutoStockpileRelayNodeDecision\":{\"next\":[\"AutoStockpileSkip\"]}," +
                        "\"AutoStockpileSkip\":{\"enabled\":true}}",
                )
                return@MaaCustomRecognitionCallback 1
            }
            AutoStockpileSupport.Session.select(pick)
            if (outBox != null) {
                lib.MaaRectSet(outBox, pick.box[0], pick.box[1], pick.box[2], pick.box[3])
            }
            Ln.i(
                "MaaRunner: AutoStockpile.Recognition [$nodeName] pick '${pick.name}' " +
                    "price=${pick.price ?: -1} box=${pick.box.joinToString(",")} of ${candidates.size} candidates",
            )
            RunDiagnostics.note(
                "autostockpile",
                "选中 '${pick.name}' 价格=${pick.price ?: -1} 候选=${candidates.size}",
                mapOf(
                    "node" to node,
                    "region" to region,
                    "name" to pick.name,
                    "price" to pick.price,
                    "candidates" to candidates.size,
                    "box" to pick.box.joinToString(","),
                ),
            )
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: AutoStockpile.Recognition error on node=$nodeName", t)
            0
        }
    }

    /**
     * SelectedGoodsClick 的识别替身：把 Session 里选中的货组框交回给流水线，
     * 好让这个节点自带的 Click 动作点下去。
     *
     * 上游这里是 TemplateMatch（Go 在运行时覆盖 template 指向选中商品），
     * 移动端没有按商品切模板的资源，用 Session 里的 OCR 框替代。
     */
    private val autoStockpileSelectedGoodsHitCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { _, _, nodeName, _, _, _, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        val pick = AutoStockpileSupport.Session.selection() ?: return@MaaCustomRecognitionCallback 0
        if (outBox != null) {
            lib.MaaRectSet(outBox, pick.box[0], pick.box[1], pick.box[2], pick.box[3])
        }
        Ln.i("MaaRunner: AutoStockpile.SelectedGoodsHit [$nodeName] hit '${pick.name}'")
        1
    }

    /**
     * 货卡 OCR：带颜色过滤读一遍，认不出货组再读一遍不过滤的，两遍合并。
     *
     * 为什么要两遍：颜色过滤按上游 AutoStockpileGoodsFilter 只留灰度 60~150，
     * 本意是压掉货卡插画（不过滤时整屏只回 ['手','手','手']）；但商品名是深色条上的
     * 白字，白字会被刷成黑、连同深色条一起变成纯黑块，名字反而读不出来。
     * 哪种更管用要看真机，所以两遍都读、合并，让日志直接给出两遍各认出几个货组名。
     *
     * 为什么要重试：真机实测管道刚导航进武陵市场时货卡网格还在进场，两遍 OCR 都只回
     * 三条一模一样的乱码（scan 出 0 个候选），而失败后的截图里网格早已铺满。所以
     * 「一个候选都没扫到」时有界重试，等网格铺完；正常路径首帧命中即返回，不额外取帧。
     *
     * [frameworkImage] 是识别回调手上那一帧（框架本次识别实际用的 `image`）：第一次尝试
     * 两遍 OCR 都用它，保证「OCR 看到的就是框架看到的」；之后的重试要换新帧等画面进场，
     * 一律自抓。为空时全程自抓，不崩。
     */
    private fun goodsOcrProbe(
        lib: MaaFrameworkLibrary,
        ctrl: Pointer,
        context: Pointer,
        roiBox: IntArray?,
        region: String,
        frameworkImage: Pointer? = null,
    ): List<GoodsSupport.OcrItem> {
        var best: List<GoodsSupport.OcrItem> = emptyList()
        // 诊断：开启时才有额外开销——捕获「实际送去 OCR 的最后一帧」并逐轮记文本，
        // 供「所有尝试都 0 候选」时导出证据。关闭时两者都是 null，不拷贝、不落盘。
        val diagnosticsOn = RunDiagnostics.isEnabled()
        val capture = if (diagnosticsOn) ProbeFrameCapture() else null
        val attemptTexts = if (diagnosticsOn) mutableListOf<Map<String, Any?>>() else null
        for (attempt in 1..AutoStockpileSupport.GOODS_PROBE_MAX_ATTEMPTS) {
            // 第一次尝试用框架帧，之后重试自抓；框架帧为空则全程自抓。
            val preferred = if (OcrProbeSupport.shouldUseFrameworkFrame(attempt, frameworkImage != null)) {
                frameworkImage
            } else {
                null
            }
            // 货卡是一页多张卡，必须完整检测分框；only_rec 会把整段 ROI 退化成一个大框
            val filtered = cachedOcrProbe(
                lib, ctrl, context, roiBox, colorFilter = GOODS_COLOR_FILTER, onlyRec = false,
                capture = capture, preferredImage = preferred,
            )
            val filteredHits = AutoStockpileSupport.scan(filtered, region).size
            if (filteredHits > 0) {
                Ln.i(
                    "MaaRunner: goods OCR [$region] filtered hits=$filteredHits texts=${filtered.take(10).map { it.text }}",
                )
                return filtered
            }
            val plain = cachedOcrProbe(
                lib, ctrl, context, roiBox, colorFilter = null, onlyRec = false,
                capture = capture, preferredImage = preferred,
            )
            val plainHits = AutoStockpileSupport.scan(plain, region).size
            Ln.i(
                "MaaRunner: goods OCR [$region] attempt=$attempt/${AutoStockpileSupport.GOODS_PROBE_MAX_ATTEMPTS} " +
                    "filtered=$filteredHits plain=$plainHits " +
                    "filteredTexts=${filtered.take(8).map { it.text }} plainTexts=${plain.take(8).map { it.text }}",
            )
            attemptTexts?.add(
                mapOf(
                    "attempt" to attempt,
                    "filteredHits" to filteredHits,
                    "plainHits" to plainHits,
                    "filteredTexts" to filtered.take(8).map { it.text },
                    "plainTexts" to plain.take(8).map { it.text },
                ),
            )
            if (plainHits > 0) return mergeOcrItems(filtered, plain)
            val probe = if (filtered.size >= plain.size) filtered else plain
            if (probe.size > best.size) best = probe
            // 一个候选都没扫到且还有额度：等网格进场后重新取帧再探
            if (!AutoStockpileSupport.shouldRetryScan(
                    candidateCount = 0,
                    attempt = attempt,
                    maxAttempts = AutoStockpileSupport.GOODS_PROBE_MAX_ATTEMPTS,
                )
            ) {
                // 额度用尽仍是 0 候选：导最后一次的帧，供判定「我们的帧与框架的帧是否同一张」。
                // 判据纯逻辑化（GoodsProbeDumpPolicy）：诊断关或曾扫到候选都不导。
                if (GoodsProbeDumpPolicy.shouldDump(RunDiagnostics.isEnabled(), allAttemptsZeroCandidates = true)) {
                    dumpGoodsProbeFrame(region, roiBox, capture, attemptTexts)
                }
                return best
            }
            Thread.sleep(AutoStockpileSupport.GOODS_PROBE_RETRY_DELAY_MS)
        }
        return best
    }

    /**
     * 全 0 候选时导出探针**实际输入的那张原图**并记一条诊断 note。
     *
     * 只导最后一次的帧：[capture] 里存的就是本轮最后一次送去 OCR 的图。原图不裁剪，
     * 事后可与框架 on_error 截图逐像素比对、并把 ROI 叠上去。写盘与 note 共用
     * [RunDiagnostics] 的后台队列，先落图后记路径，且不阻塞 MAA 工作线程。
     */
    private fun dumpGoodsProbeFrame(
        region: String,
        roiBox: IntArray?,
        capture: ProbeFrameCapture?,
        attemptTexts: List<Map<String, Any?>>?,
    ) {
        val relPath = GoodsProbeDumpPolicy.dumpRelativePath(region, System.currentTimeMillis())
        val bytes = capture?.pngBytes
        val accepted = bytes != null && RunDiagnostics.saveDump(relPath, bytes)
        val extra: Map<String, Any?> = mapOf(
            "region" to region,
            "roi" to (roiBox?.joinToString(",") ?: ""),
            "dumpPath" to if (accepted) relPath else "",
            "attempts" to attemptTexts,
        )
        RunDiagnostics.note(
            "autostockpile",
            if (accepted) {
                "货卡探针全 0 候选，已导出实际输入帧 $relPath"
            } else {
                "货卡探针全 0 候选，但帧导出未受理（无帧或诊断已关）"
            },
            extra,
        )
    }

    /** 两遍 OCR 结果合并去重，过滤那遍优先 */
    private fun mergeOcrItems(
        a: List<GoodsSupport.OcrItem>,
        b: List<GoodsSupport.OcrItem>,
    ): List<GoodsSupport.OcrItem> {
        val seen = HashSet<String>()
        val out = ArrayList<GoodsSupport.OcrItem>(a.size + b.size)
        for (item in a + b) {
            val box = item.box
            val key = if (box == null) {
                "t:${item.text}"
            } else {
                // 框按 8px 量化：两遍之间卡面可能微移几个像素
                "b:${item.text}:${box[0] / 8},${box[1] / 8},${box[2] / 8},${box[3] / 8}"
            }
            if (seen.add(key)) out += item
        }
        return out
    }

    /**
     * 对齐上游 AutoStockpile.SelectItem：上游只做决策不点击（点击归 SelectedGoodsClick），
     * 这里同样不直接点，而是把 SelectedGoodsClick 打开并换成我们的识别，
     * 让它自带的 Click 点中 Session 里的选择。
     */
    private val autoStockpileSelectItemCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, _, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        val pick = AutoStockpileSupport.Session.selection()
        if (pick == null) {
            Ln.w("MaaRunner: AutoStockpile.SelectItem [$nodeName] no selection in session")
            return@MaaCustomActionCallback 0
        }
        runCatching {
            lib.MaaContextOverridePipeline(
                context,
                "{\"AutoStockpileSelectedGoodsClick\":{\"enabled\":true,\"recognition\":{\"type\":\"Custom\"," +
                    "\"param\":{\"custom_recognition\":\"AutoStockpile.SelectedGoodsHit\"," +
                    "\"custom_recognition_param\":{}}}},\"AutoStockpileSkip\":{\"enabled\":false}}",
            )
            Ln.i("MaaRunner: AutoStockpile.SelectItem [$nodeName] armed click for '${pick.name}'")
        }.onFailure { Ln.e("MaaRunner: AutoStockpile.SelectItem override failed on node=$nodeName", it) }
        1
    }

    /**
     * 对齐上游 AutoStockpile.ReconcileDecision：拿详情页价格校正选择，然后决定
     * 「进入购买链」还是「回列表换下一个货」。
     *
     * 上游比较的是「详情页实价 vs 列表页 OCR 价」，超阈值就重算决策；移动端没有阈值配置，
     * 保留可比的那部分：详情页价比列表页价高出 50% 以上就认为看错/涨价，换下一个候选。
     *
     * 购买链的闸门是 AutoStockpileRelayNodeDecisionReady，它在基础流水线里 enabled=false，
     * 只有这里放行才会继续到 CheckBuy/SwipeMax，所以这一步必须成功。
     */
    private val autoStockpileReconcileDecisionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, _, _, box, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val pick = AutoStockpileSupport.Session.selection()
            if (pick == null) {
                Ln.w("MaaRunner: AutoStockpile.ReconcileDecision [$nodeName] no selection, skip region")
                lib.MaaContextOverridePipeline(
                    context,
                    "{\"AutoStockpileRelayNodeDecisionReady\":{\"enabled\":false},\"AutoStockpileSkip\":{\"enabled\":true}}",
                )
                return@MaaCustomActionCallback 1
            }
            val r = getBoxRect(lib, box)
            val detailPrice = if (r.w > 0 && r.h > 0) ocrPriceIn(lib, context, r) else null
            val listed = pick.price
            val tooExpensive = detailPrice != null && listed != null && detailPrice > listed * 3 / 2
            if (tooExpensive) {
                Ln.i(
                    "MaaRunner: AutoStockpile.ReconcileDecision [$nodeName] '${pick.name}' detail=$detailPrice " +
                        "listed=$listed too expensive, retry another candidate",
                )
                AutoStockpileSupport.Session.reject()
                val decisionNode = AutoStockpileSupport.Session.decisionNode()
                val retryNext = decisionNode?.let { "\"$it\",\"AutoStockpileSkip\"" } ?: "\"AutoStockpileSkip\""
                lib.MaaContextOverridePipeline(
                    context,
                    "{\"AutoStockpileRelayNodeDecisionReady\":{\"enabled\":false},\"AutoStockpileSkip\":{\"enabled\":true}," +
                        "\"AutoStockpileRelayNodeDecision\":{\"next\":[$retryNext]}}",
                )
            } else {
                Ln.i(
                    "MaaRunner: AutoStockpile.ReconcileDecision [$nodeName] '${pick.name}' detail=$detailPrice " +
                        "listed=$listed accepted, proceed to purchase",
                )
                // 放行购买链：DecisionReady 走到 CheckBuy/CheckQuota，SwipeMax 负责买满
                lib.MaaContextOverridePipeline(
                    context,
                    "{\"AutoStockpileRelayNodeDecisionReady\":{\"enabled\":true}," +
                        "\"AutoStockpileSwipeMax\":{\"enabled\":true}," +
                        "\"AutoStockpileSwipeSpecificQuantity\":{\"enabled\":false}," +
                        "\"AutoStockpileSkip\":{\"enabled\":false}}",
                )
            }
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: AutoStockpile.ReconcileDecision error on node=$nodeName", t)
            0
        }
    }

    /** 在给定小 ROI 里 OCR 一个价格（详情页实价），认不出返回 null */
    private fun ocrPriceIn(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        r: RectData,
    ): Int? {
        val ctrl = controller ?: return null
        // 详情页实价是这个 ROI 里唯一的一个数值，只识别不做检测更稳
        // （对齐上游 bettersliding 对单个数值开 only_rec 的做法）
        val items = cachedOcrProbe(lib, ctrl, context, intArrayOf(r.x, r.y, r.w, r.h), onlyRec = true)
        for (item in items) {
            val digits = item.text.replace("[^\\d]".toRegex(), "")
            val value = digits.toIntOrNull() ?: continue
            if (value in 1..9999) return value
        }
        return null
    }

    /** 通用 OCR 点选：识别指定文本并点击首个命中（替代脆弱的小模板点选） */
    private val ocrTapActionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val p = Json.parseToJsonElement(customActionParam.orEmpty()).jsonObject
            val text = p["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isBlank()) return@MaaCustomActionCallback 0
            val roi = intArrayOrNull(p["roi"])
            val ctrl = controller ?: return@MaaCustomActionCallback 0
            val capId = lib.MaaControllerPostScreencap(ctrl)
            if (capId > 0) lib.MaaControllerWait(ctrl, capId)
            val imgBuf = lib.MaaImageBufferCreate() ?: return@MaaCustomActionCallback 0
            try {
                if (lib.MaaControllerCachedImage(ctrl, imgBuf).toInt() == 0) return@MaaCustomActionCallback 0
                if (lib.MaaImageBufferIsEmpty(imgBuf).toInt() != 0) return@MaaCustomActionCallback 0
                // 页签这类小目标必须限 ROI：整屏彩色插画会把 OCR 淹掉，实测只剩几个偏旁
                // 通用点选：ROI 内可能是单个页签也可能是整列菜单，判断不了时保守用完整检测
                val items = cachedOcrProbe(lib, ctrl, context, roi, colorFilter = null, onlyRec = false)
                val m = items.firstOrNull { it.text.contains(text) || text.contains(it.text) }
                if (m == null) {
                    Ln.w("MaaRunner: OcrTapAction [$nodeName] '$text' not found, roi=${roi?.joinToString(",")} got=${items.take(8).map { it.text }}")
                    return@MaaCustomActionCallback 0
                }
                val b = m.box ?: return@MaaCustomActionCallback 0
                val cx = (b[0] + b[2] / 2).coerceIn(0, 4000)
                val cy = (b[1] + b[3] / 2).coerceIn(0, 4000)
                Ln.i("MaaRunner: OcrTapAction [$nodeName] '$text' -> '${m.text}' ($cx,$cy)")
                val clickBox = lib.MaaRectCreate()
                try {
                    if (clickBox != null) lib.MaaRectSet(clickBox, cx, cy, 1, 1)
                    lib.MaaContextRunAction(context, "__OcrTapInner", "{\"__OcrTapInner\":{\"action\":\"Click\"}}", clickBox, "")
                } finally {
                    if (clickBox != null) lib.MaaRectDestroy(clickBox)
                }
                1
            } finally {
                lib.MaaImageBufferDestroy(imgBuf)
            }
        } catch (t: Throwable) {
            Ln.e("MaaRunner: OcrTapAction error on node=$nodeName", t)
            0
        }
    }

    /** 通用 OCR 存在性检查：找到指定文本即命中（用于替代脆弱模板校验） */
    private val ocrCheckRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val p = runCatching {
                Json.parseToJsonElement(customRecognitionParam.orEmpty()).jsonObject
            }.getOrNull() ?: return@MaaCustomRecognitionCallback 0
            val text = p["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isBlank()) return@MaaCustomRecognitionCallback 0
            val roi = intArrayOrNull(p["roi"])
            // 页签文字是浅色，压在深色页签条上；套货卡的颜色过滤会把它刷成全黑，
            // 所以这里显式关掉过滤，只靠 ROI 把范围收窄。
            // 这是通用存在性检查，ROI 内可能是单个页签、也可能是多项文本，判断不了时
            // 保守用完整检测（onlyRec=false）。
            lib.MaaContextOverridePipeline(
                context,
                OcrProbeSupport.buildOverride(roi, onlyRec = false, colorFilter = null),
            )
            val res = runRecognitionOnce(lib, context, image, OcrProbeSupport.NODE)
                ?: return@MaaCustomRecognitionCallback 0
            if (!res.hit) return@MaaCustomRecognitionCallback 0
            val items = GoodsSupport.collectOcrItems(res.detailJson)
            val m = items.firstOrNull { it.text.contains(text) || text.contains(it.text) }
            if (m == null) {
                Ln.w("MaaRunner: OcrCheckRecognition [$nodeName] '$text' not found, roi=${roi?.joinToString(",")} got=${items.take(8).map { it.text }}")
                return@MaaCustomRecognitionCallback 0
            }
            if (outBox != null && m.box != null) {
                lib.MaaRectSet(outBox, m.box[0], m.box[1], m.box[2], m.box[3])
            }
            Ln.i("MaaRunner: OcrCheckRecognition [$nodeName] '$text' found as '${m.text}'")
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OcrCheckRecognition error on node=$nodeName", t)
            0
        }
    }

    // ───────────────────── 会客室·交流倒计时 ─────────────────────
    // 对齐上游 dijjiangrewards/reception_room.go：两个识别共用
    // `RunRecognition(ReceptionRoomExchangeCountdownText)` 的 OCR 文本。

    /** 上游两个 recognition struct 里的节流状态机。 */
    private val receptionWaitingReportGate = ReceptionRoomSupport.WaitingReportGate()
    private val receptionKeepAliveGate = ReceptionRoomSupport.KeepAliveGate()

    private data class ReceptionCountdown(val text: String, val seconds: Int, val box: IntArray?)

    /** 对齐上游 `recognizeCountdownSeconds`：跑 OCR 节点 → best 文本 → 秒数。 */
    private fun recognizeReceptionCountdown(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
    ): ReceptionCountdown? {
        val res = runRecognitionOnce(lib, context, image, ReceptionRoomSupport.COUNTDOWN_TEXT_NODE)
            ?: return null
        val text = ReceptionRoomSupport.bestOcrText(MaaJsonTree.parse(res.detailJson)) ?: return null
        val seconds = when (val outcome = ReceptionRoomSupport.parseCountdownSeconds(text)) {
            is ReceptionRoomSupport.SecondsOutcome.Ok -> outcome.seconds
            is ReceptionRoomSupport.SecondsOutcome.Invalid -> {
                Ln.d("MaaRunner: 会客室倒计时解析失败：${outcome.reason}")
                return null
            }
        }
        return ReceptionCountdown(text, seconds, res.box)
    }

    private fun setReceptionOutBox(lib: MaaFrameworkLibrary, outBox: Pointer?, box: IntArray?) {
        if (outBox == null || box == null || box.size < 4) return
        lib.MaaRectSet(outBox, box[0], box[1], box[2], box[3])
    }

    /**
     * `ReceptionRoomExchangeCountdownWithinThresholdRecognition`：
     * 倒计时 ≤ `threshold_minutes`（默认 5）时命中；`report_waiting` 时每 10 秒放行一次提示。
     *
     * 上游提示走 maafocus 大内容输出；移动端没有该通道，这里保留同样的节流门控，
     * 改成一条日志，不影响命中判定。
     */
    private val receptionCountdownWithinThresholdCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val params = when (val outcome = ReceptionRoomSupport.parseCountdownParams(customRecognitionParam)) {
                is ReceptionRoomSupport.ParamsOutcome.Ok -> outcome.params
                is ReceptionRoomSupport.ParamsOutcome.Invalid -> {
                    Ln.e("MaaRunner: ReceptionRoomCountdown [$nodeName] 参数非法：${outcome.reason}")
                    return@MaaCustomRecognitionCallback 0
                }
            }

            val reading = recognizeReceptionCountdown(lib, context, image)
                ?: return@MaaCustomRecognitionCallback 0
            val thresholdSeconds = params.thresholdMinutes * 60
            if (reading.seconds > thresholdSeconds) return@MaaCustomRecognitionCallback 0

            if (params.reportWaiting &&
                receptionWaitingReportGate.shouldReportWaiting(System.currentTimeMillis())
            ) {
                Ln.i("MaaRunner: 会客室等待交流结束，剩余 ${ReceptionRoomSupport.formatCountdown(reading.seconds)}")
            }
            setReceptionOutBox(lib, outBox, reading.box)
            Ln.i("MaaRunner: ReceptionRoomCountdown [$nodeName] text='${reading.text}' seconds=${reading.seconds} threshold=$thresholdSeconds matched=true")
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: ReceptionRoomCountdown error on node=$nodeName", t)
            0
        }
    }

    /**
     * `ReceptionRoomWaitExchangeKeepAliveDueRecognition`：
     * 距上次保活满 20 分钟命中一次；判定间隔超 2 分钟视为会话中断，保活计时重置。
     */
    private val receptionKeepAliveDueCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, _, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val reading = recognizeReceptionCountdown(lib, context, image)
                ?: return@MaaCustomRecognitionCallback 0
            if (!receptionKeepAliveGate.shouldKeepAlive(System.currentTimeMillis())) {
                return@MaaCustomRecognitionCallback 0
            }
            setReceptionOutBox(lib, outBox, reading.box)
            val intervalSeconds = (ReceptionRoomSupport.KEEP_ALIVE_INTERVAL_MILLIS / 1000L).toInt()
            Ln.i("MaaRunner: ReceptionRoomKeepAlive [$nodeName] text='${reading.text}' seconds=${reading.seconds} interval=$intervalSeconds matched=true")
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: ReceptionRoomKeepAlive error on node=$nodeName", t)
            0
        }
    }

    // ───────────────────────── itemTransfer（物品转移）─────────────────────────
    // 上游 itemtransfer 整包（1078 行）的纯逻辑收在 [ItemTransferSupport]。
    //
    // 三个注册名里**只有** `ItemTransferSameItemRecognition` 仍被 pipeline 引用
    // （节点 `ItemTransferSkipSameItem`），所以只有它必须真实实现。
    // `ItemTransferFallbackAction` / `ItemTransferOCRAction` 是上游 README 明说的兼容实现：
    // pipeline 已不再引用，且它们依赖的 `ItemTransferDetectAllItems` /
    // `ItemTransferDetectAllItemsBag` / `ItemTransferTooltipOCR` 三个节点在当前上游 assets
    // 里根本不存在，故不在此注册假成功（详见 devlog / 报告）。
    private val itemTransferSameItemRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, _, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) return@MaaCustomRecognitionCallback 0
        try {
            val params = ItemTransferSupport.parseSameItemParams(customRecognitionParam)
            if (params == null || params.forwardItemNode.isEmpty() || params.returnItemNode.isEmpty()) {
                Ln.w("MaaRunner: ItemTransferSameItem [$nodeName] 参数非法或缺 item 节点: $customRecognitionParam")
                return@MaaCustomRecognitionCallback 0
            }
            // 读取最终节点的 item_ids，可避免两个下拉选项争抢同一个参数对象（上游注释）
            val forward = ItemTransferSupport.parseSelectedItemId(
                nodeDefinitionJson(lib, context, params.forwardItemNode),
            )
            if (forward == null) {
                Ln.w("MaaRunner: ItemTransferSameItem [$nodeName] 去程节点无唯一 item_id: ${params.forwardItemNode}")
                return@MaaCustomRecognitionCallback 0
            }
            val ret = ItemTransferSupport.parseSelectedItemId(
                nodeDefinitionJson(lib, context, params.returnItemNode),
            )
            if (ret == null) {
                Ln.w("MaaRunner: ItemTransferSameItem [$nodeName] 返程节点无唯一 item_id: ${params.returnItemNode}")
                return@MaaCustomRecognitionCallback 0
            }
            if (forward != ret) return@MaaCustomRecognitionCallback 0
            // 上游命中时把入参 ROI 原样作为结果框
            if (outBox != null) {
                val r = getBoxRect(lib, roi)
                lib.MaaRectSet(outBox, r.x, r.y, r.w, r.h)
            }
            Ln.i("MaaRunner: ItemTransferSameItem [$nodeName] forward=$forward == return=$ret，命中")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: ItemTransferSameItem error on node=$nodeName", t)
            0
        }
    }

    // ───────────────────────── intelarchive（情报档案库）─────────────────────────
    // 上游 intelarchive 整包（904 行）的纯逻辑收在 [IntelArchiveSupport]。5 个注册名
    // 原先全在 noop 名单里，等于功能不存在；这里全部接成真实实现。
    //
    // 降级点：上游的 maafocus 浮层提示在本项目没有通道，改为 Ln 日志；上游
    // ShowInventoryAction 用系统浏览器打开导入链接，而 MaaRunner 拿不到 app Context，
    // 改为把链接写入日志（链接本身照常生成）。
    //
    // 交接点：列表识别本应把「待点开条目」经识别详情 detail 传给动作，但本项目 JNA 库
    // 未绑定 MaaStringBufferSet，无法回写 out_detail，改为 [IntelArchiveSupport] 内存交接；
    // 两个回调同线程相邻执行，取走即清空，不会串屏。

    /** 从 pi.zip 解包根读取目录；读不到返回 null，调用方按节点失败处理。 */
    private fun loadIntelArchiveCatalog(): IntelArchiveSupport.CatalogIndex? {
        IntelArchiveSupport.loadedCatalog()?.let { return it }
        val root = projectRoot
        if (root.isNullOrBlank()) {
            Ln.w("MaaRunner: IntelArchive 缺少 PI 根，无法读取目录")
            return null
        }
        val catalogFile = File(root, IntelArchiveSupport.CATALOG_RELATIVE)
        val itemsFile = File(root, IntelArchiveSupport.ITEMS_RELATIVE)
        if (!catalogFile.isFile || !itemsFile.isFile) {
            Ln.w("MaaRunner: IntelArchive 缺少数据文件 ${catalogFile.path} / ${itemsFile.path}")
            return null
        }
        return when (val outcome = IntelArchiveSupport.ensureLoaded(catalogFile.readText(), itemsFile.readText())) {
            is IntelArchiveSupport.CatalogOutcome.Ok -> outcome.index
            is IntelArchiveSupport.CatalogOutcome.Invalid -> {
                Ln.e("MaaRunner: IntelArchive 目录加载失败：${outcome.reason}")
                null
            }
        }
    }

    /** 跑一段组合识别并取第 [index] 段的 filtered 条目（对齐上游 recognizeFiltered）。 */
    private fun intelArchiveFilteredItems(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        node: String,
        index: Int,
    ): List<IntelArchiveSupport.TruncatedItem> {
        val res = runRecognitionOnce(lib, context, image, node) ?: return emptyList()
        if (!res.hit) return emptyList()
        val tree = MaaJsonTree.parse(res.detailJson) ?: return emptyList()
        return IntelArchiveSupport.filteredItems(tree, index)
    }

    /**
     * `IntelArchiveScanItemsRecognition`：列表页两段组合识别 → 目录比对入库；
     * 多页 / 省略号对不上 / 非 digital 的无名密文 → 交给 [intelArchiveResolveTruncCallback]。
     */
    private val intelArchiveScanItemsCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val titles = intelArchiveFilteredItems(
                lib, context, image,
                IntelArchiveSupport.ITEM_TEXT_NODE, IntelArchiveSupport.ITEM_OCR_INDEX,
            )
            val secret = intelArchiveFilteredItems(
                lib, context, image,
                IntelArchiveSupport.ITEM_SECRET_NODE, IntelArchiveSupport.ITEM_SECRET_BOX_INDEX,
            )
            val fileCategory = IntelArchiveSupport.parseScanFileCategory(customRecognitionParam)
            IntelArchiveSupport.setListFileCategory(fileCategory)
            val idx = loadIntelArchiveCatalog() ?: return@MaaCustomRecognitionCallback 0

            val classified = IntelArchiveSupport.classifyListItems(idx, titles, secret, fileCategory)
            if (classified.secretForceUnlock) {
                for (id in IntelArchiveSupport.unlockItems(listOf(IntelArchiveSupport.SECRET_UNLOCK_ID))) {
                    Ln.i("MaaRunner: IntelArchive 解锁密文档案 ${IntelArchiveSupport.SECRET_UNLOCK_NAME} ($id)")
                }
            }
            val match = IntelArchiveSupport.matchNames(idx, classified.names, fileCategory)
            val added = IntelArchiveSupport.unlockItems(match.ids)
            for (id in added) {
                Ln.i("MaaRunner: IntelArchive 解锁 ${match.idToName[id] ?: id} ($id)")
            }
            for (miss in match.misses) {
                Ln.i("MaaRunner: IntelArchive 目录未命中 '$miss' (file_category='$fileCategory')")
            }
            IntelArchiveSupport.storePendingTruncated(classified.truncated)

            if (outBox != null) {
                val r = getBoxRect(lib, roi)
                lib.MaaRectSet(outBox, r.x, r.y, r.w, r.h)
            }
            Ln.i(
                "MaaRunner: IntelArchiveScanItems [$nodeName] file_category='$fileCategory' " +
                    "titles=${titles.size} secret=${secret.size} unlocked=${added.size} " +
                    "truncated=${classified.truncated.size}",
            )
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: IntelArchiveScanItems error on node=$nodeName", t)
            0
        }
    }

    /**
     * `IntelArchiveScanDetailRecognition`：详情页标题 OCR 与目录比对入库。
     * 上游在此节点**始终返回命中**（识别失败也只是不匹配），否则详情子流水线的翻页/关闭会断。
     */
    private val intelArchiveScanDetailCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, _, image, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val items = intelArchiveFilteredItems(
                lib, context, image,
                IntelArchiveSupport.DETAIL_RECOGNITION_NODE, IntelArchiveSupport.DETAIL_OCR_INDEX,
            )
            val idx = loadIntelArchiveCatalog()
            if (idx != null) {
                val fileCategory = IntelArchiveSupport.currentListFileCategory()
                val match = IntelArchiveSupport.matchNames(idx, items.map { it.text }, fileCategory)
                val added = IntelArchiveSupport.unlockItems(match.ids)
                for (id in added) {
                    Ln.i("MaaRunner: IntelArchive 详情解锁 ${match.idToName[id] ?: id} ($id)")
                }
                for (miss in match.misses) {
                    Ln.i("MaaRunner: IntelArchive 详情目录未命中 '$miss' (file_category='$fileCategory')")
                }
            }
            if (outBox != null) {
                val r = getBoxRect(lib, roi)
                lib.MaaRectSet(outBox, r.x, r.y, r.w, r.h)
            }
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: IntelArchiveScanDetail error on node=$nodeName", t)
            1
        }
    }

    /** `IntelArchiveResolveTruncAction`：逐个点开待处理条目并跑详情解析子流水线。 */
    private val intelArchiveResolveTruncCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, _, recoId, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        val pending = IntelArchiveSupport.takePendingTruncated()
        // 上游从识别详情 detail 里取待点开条目；本项目回写不了 out_detail，正常情况下 detail 为空，
        // 退回到 [IntelArchiveSupport] 的内存交接。两条路都试，避免框架形态变化时静默丢条目。
        val fromDetail = IntelArchiveSupport.parseTruncated(
            MaaJsonTree.parse(recognitionDetailJson(lib, context, recoId)),
        )
        val items = fromDetail.ifEmpty { pending }
        if (items.isEmpty()) {
            Ln.d("MaaRunner: IntelArchiveResolveTrunc [$nodeName] 无待点开条目")
            return@MaaCustomActionCallback 1
        }
        for (item in items) {
            val box = item.box
            if (box.size != 4 || box[2] <= 0 || box[3] <= 0) {
                Ln.w("MaaRunner: IntelArchiveResolveTrunc [$nodeName] 条目框非法: text='${item.text}' box=$box")
                continue
            }
            val override = """{"${IntelArchiveSupport.TRUNCATED_ITEM_NODE}":{"target":[${box[0]},${box[1]},${box[2]},${box[3]}]}}"""
            if (lib.MaaContextOverridePipeline(context, override).toInt() == 0) {
                Ln.e("MaaRunner: IntelArchiveResolveTrunc [$nodeName] 覆盖点击目标失败 box=$box")
                continue
            }
            val taskId = lib.MaaContextRunTask(context, IntelArchiveSupport.TRUNCATED_ITEM_NODE, "{}")
            if (taskId <= 0L) {
                Ln.e("MaaRunner: IntelArchiveResolveTrunc [$nodeName] 详情子流水线失败 text='${item.text}' box=$box")
                return@MaaCustomActionCallback 0
            }
            // 上游查 Status.Success()；MaaContextRunTask 同步返回，失败时状态为 FAILED。
            val tasker = lib.MaaContextGetTasker(context)
            if (tasker != null && lib.MaaTaskerStatus(tasker, taskId) == MaaStatus.FAILED) {
                Ln.e("MaaRunner: IntelArchiveResolveTrunc [$nodeName] 详情子流水线未成功 text='${item.text}' box=$box")
                return@MaaCustomActionCallback 0
            }
        }
        Ln.i("MaaRunner: IntelArchiveResolveTrunc [$nodeName] 处理 ${items.size} 个待点开条目")
        1
    }

    /** `IntelArchiveResetSessionAction`：清空本次扫描会话与内存交接。 */
    private val intelArchiveResetSessionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, _, _, _, _ ->
        IntelArchiveSupport.resetSession()
        Ln.i("MaaRunner: IntelArchiveResetSession [$nodeName] 会话已清空")
        1
    }

    /** `IntelArchiveShowInventoryAction`：生成 OEA 导入链接（浏览器打开降级为日志）。 */
    private val intelArchiveShowInventoryCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, _, _, _, _ ->
        val collected = IntelArchiveSupport.sessionUnlockedIds()
        if (collected.isEmpty()) {
            Ln.w("MaaRunner: IntelArchiveShowInventory [$nodeName] 会话解锁列表为空")
        }
        val idx = loadIntelArchiveCatalog() ?: return@MaaCustomActionCallback 0
        try {
            val url = IntelArchiveSupport.buildIntelImportUrl(collected, idx.allUnlockIds)
            Ln.i(
                "MaaRunner: IntelArchiveShowInventory [$nodeName] collected=${collected.size} " +
                    "catalog=${idx.allUnlockIds.size} url_len=${url.length}",
            )
            Ln.i("MaaRunner: IntelArchive 导入链接（移动端不自动打开浏览器，请手动打开）：$url")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: IntelArchiveShowInventory error on node=$nodeName", t)
            0
        }
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

    // ────────────────────── debug CLI 能力面（仅 debug 构建接线） ──────────────────────
    //
    // 这些方法只被 DebugCliServer 的宿主适配器调用。release 下没有调用方（服务端不启动），
    // 但它们本身不碰文件系统，因此保留也不会产生副作用。

    fun debugProjectRoot(): String? = projectRoot

    /** controller 是否已连接、能取到缓存帧。 */
    fun debugControllerReady(): Boolean {
        val lib = MaaFrameworkLoader.library ?: return false
        val ctrl = controller ?: return false
        return runCatching { lib.MaaControllerConnected(ctrl).toInt() != 0 }.getOrDefault(false)
    }

    /** 把当前缓存帧写到 [path]；成功返回 null，失败返回原因。 */
    fun debugSaveCachedImage(path: String): String? {
        val lib = MaaFrameworkLoader.library ?: return "MaaFramework 未加载"
        val ctrl = controller ?: return "controller 未建立（先跑一次任务）"
        val buffer = lib.MaaImageBufferCreate() ?: return "MaaImageBufferCreate 失败"
        return try {
            if (lib.MaaControllerCachedImage(ctrl, buffer).toInt() == 0) return "取缓存帧失败（当前无帧）"
            if (lib.MaaImageBufferIsEmpty(buffer).toInt() != 0) return "缓存帧为空"
            val size = lib.MaaImageBufferGetEncodedSize(buffer)
            if (size <= 0) return "缓存帧编码为空"
            val data = lib.MaaImageBufferGetEncoded(buffer) ?: return "读取缓存帧字节失败"
            val bytes = data.getByteArray(0, size.toInt())
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            null
        } catch (t: Throwable) {
            "导出缓存帧失败：${t.javaClass.simpleName}: ${t.message}"
        } finally {
            lib.MaaImageBufferDestroy(buffer)
        }
    }

    /**
     * 对当前帧跑一次识别节点，返回 best 文本。
     *
     * MaaFramework 的 `MaaContextRunRecognition` 需要 task context，而 context 只在任务回调里存在；
     * 外部拿不到。于是注册一个探针识别 `DebugCliOcr`，用 `MaaTaskerPostTask` 跑一个只含该探针的
     * 临时节点——探针回调里再用现成的 [runRecognitionOnce] 跑目标节点。
     */
    fun debugOcrOnce(nodeName: String): DebugRecoOutcome {
        val lib = MaaFrameworkLoader.library ?: return DebugRecoOutcome(false, null, "MaaFramework 未加载")
        if (isRunning()) return DebugRecoOutcome(false, null, "任务运行中，无法执行调试识别")
        val tasker = synchronized(lifecycleLock) { tasker }
            ?: return DebugRecoOutcome(false, null, "tasker 未初始化（先跑一次任务）")
        if (lib.MaaTaskerInited(tasker).toInt() == 0) {
            return DebugRecoOutcome(false, null, "tasker 未就绪")
        }
        synchronized(debugOcrLock) {
            debugOcrResult = null
            // MaaTaskerPostTask 的 pipeline_override 是**对象数组**（按序合并），不是单个对象
            val probeNode = buildJsonObject {
                put(DEBUG_OCR_NODE, buildJsonObject {
                    put("recognition", buildJsonObject {
                        put("type", "Custom")
                        put("param", buildJsonObject {
                            put("custom_recognition", DEBUG_OCR_RECO)
                            put("custom_recognition_param", buildJsonObject { put("node", nodeName) })
                        })
                    })
                    // 只认不按：临时节点不触发任何动作
                    put("action", buildJsonObject { put("type", "DoNothing") })
                })
            }
            val override = JsonArray(listOf(probeNode)).toString()
            val id = lib.MaaTaskerPostTask(tasker, DEBUG_OCR_NODE, override)
            if (id == INVALID_ID) return DebugRecoOutcome(false, null, "PostTask 被拒绝")
            lib.MaaTaskerWait(tasker, id)
            return debugOcrResult ?: DebugRecoOutcome(false, null, "识别未返回结果")
        }
    }

    /** 跑一次节点 [nodeName]（截断其 next，避免顺链跑下去），返回结果描述。 */
    fun debugRunOnce(nodeName: String): String {
        val lib = MaaFrameworkLoader.library ?: return "MaaFramework 未加载"
        if (isRunning()) return "任务运行中，无法执行调试节点"
        val tasker = synchronized(lifecycleLock) { tasker }
            ?: return "tasker 未初始化（先跑一次任务）"
        // 同 debugOcrOnce：pipeline_override 是对象数组
        val patch = buildJsonObject {
            put(nodeName, buildJsonObject { put("next", JsonArray(emptyList())) })
        }
        val override = JsonArray(listOf(patch)).toString()
        val id = lib.MaaTaskerPostTask(tasker, nodeName, override)
        if (id == INVALID_ID) return "PostTask 被拒绝（节点不存在？）"
        val status = lib.MaaTaskerWait(tasker, id)
        return statusText(status)
    }

    private val debugOcrLock = Any()

    @Volatile
    private var debugOcrResult: DebugRecoOutcome? = null

    /** 探针识别：把目标节点名放进 `custom_recognition_param.node`，在回调里跑目标识别。 */
    private val debugCliOcrCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, _, _, param, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) {
            debugOcrResult = DebugRecoOutcome(false, null, "识别回调缺少 context/image")
            return@MaaCustomRecognitionCallback 0
        }
        val node = runCatching {
            Json.parseToJsonElement(param.orEmpty()).jsonObject["node"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        if (node.isBlank()) {
            debugOcrResult = DebugRecoOutcome(false, null, "custom_recognition_param 缺少 node")
            return@MaaCustomRecognitionCallback 0
        }
        try {
            val res = runRecognitionOnce(lib, context, image, node)
            if (res == null) {
                debugOcrResult = DebugRecoOutcome(false, null, "识别调用失败（节点不存在或 runtime 未就绪）")
                return@MaaCustomRecognitionCallback 0
            }
            val nodeJson = nodeDefinitionJson(lib, context, node)
            val text = selectOcrText(res.detailJson, nodeJson)
                ?: ReceptionRoomSupport.bestOcrText(MaaJsonTree.parse(res.detailJson))
            debugOcrResult = when {
                text != null -> DebugRecoOutcome(res.hit, text, null)
                res.hit -> DebugRecoOutcome(true, null, "识别命中但没有 OCR 文本（可能是模板/颜色类节点）")
                else -> DebugRecoOutcome(false, null, "识别未命中")
            }
            if (res.hit && outBox != null && res.box != null && res.box.size >= 4) {
                lib.MaaRectSet(outBox, res.box[0], res.box[1], res.box[2], res.box[3])
            }
            if (res.hit) 1 else 0
        } catch (t: Throwable) {
            debugOcrResult = DebugRecoOutcome(false, null, "${t.javaClass.simpleName}: ${t.message}")
            0
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
            // 报告收尾：结局行排在所有已入队事件之后，随后关文件。
            // finish 只是排队，不阻塞这里；写盘进度落后于 onFinished 无妨
            val outcomeName = when (outcome) {
                RunOutcome.COMPLETED -> "COMPLETED"
                RunOutcome.COMPLETED_WITH_FAILURES -> "COMPLETED_WITH_FAILURES"
                RunOutcome.CANCELLED -> "CANCELLED"
                else -> "FAILED"
            }
            RunDiagnostics.finish(if (reason.isEmpty()) outcomeName else "$outcomeName: $reason")
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
            // 操控类支持数据：送货目录从 APK 内 assets 读取（data/ 目录打包白名单已带入）
            loadDeliveryCatalogFromApk(payload.apkPath)
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

    /**
     * JNA 会把「首次被 native 回调的线程」自动 attach 到 JVM，并在回调返回后
     * DetachCurrentThread。问题在于 MaaFramework 的 Custom Action 允许在回调里
     * 再同步调 `MaaContextRunTask` / `MaaContextRunAction` / `MaaContextRunRecognition`，
     * 于是同一个 native 线程会在「外层回调还没返回」时嵌套进入内层回调；内层回调
     * 结束时 JNA 会再次 DetachCurrentThread，而此刻线程上仍有运行中的 Java 栈帧，
     * ART 直接 `SIGABRT: attempting to detach while still running code`。
     *
     * 每个回调入口先调一次 [Native.detach]`(false)`，把该 native 线程标记为
     * 「不随回调返回而 detach」，从此嵌套再深也不会被 ART 掐断。
     */
    private fun keepCallbackThread() {
        runCatching { Native.detach(false) }
    }

    /** 包一层后的回调必须被强引用，否则 JNA 侧拿到的函数指针会被 GC 回收 */
    private val callbackKeepAlive = mutableListOf<Any>()

    /** 从 APK 本体读取送货目录；读不到时留空，Resolve 动作会在用到时报清晰错误 */
    private fun loadDeliveryCatalogFromApk(apkPath: String) {
        if (apkPath.isBlank()) return
        try {
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("assets/data/AutoDelivery/catalog.json") ?: return
                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                AutoDeliverySupport.ensureLoaded(text)
                Ln.i("MaaRunner: delivery catalog loaded (${text.length} bytes)")
            }
        } catch (t: Throwable) {
            Ln.w("MaaRunner: load delivery catalog failed: ${t.message}")
        }
    }

    // ───────────────────────── autoEcoFarm（生态农场）─────────────────────────
    // 上游 autoecofarm 包（622 行）在移动端曾整组是 stub：两个识别恒假、三个动作 noop。
    // 注意这三个动作**不是可选项**：override 把识别模板在「追踪标记」与「农田标记」间切换，
    // reset 清掉跨任务的滑动状态，interruptibleSleep 是等待队友的 60s。
    // 保持 noop 会让分支「能进但一直找错目标 / 提前退出」，正是本项目反复踩过的静默成功。

    /**
     * `autoEcoFarmCalculateSwipeTarget`：按目标 ROI 与拉近比例算出 swipe 终点。
     * 几何与状态在 [AutoEcoFarmSwipe]（已单测）；这里只取截图尺寸/ROI 并回写 outBox。
     */
    private val autoEcoFarmSwipeTargetCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { _, _, nodeName, _, customRecognitionParam, _, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        val param = AutoEcoFarmSwipe.parseParam(customRecognitionParam) ?: run {
            Ln.w("MaaRunner: autoEcoFarmCalculateSwipeTarget 参数不合法: $customRecognitionParam")
            return@MaaCustomRecognitionCallback 0
        }
        try {
            val rect = getBoxRect(lib, roi)
            // 上游用截图尺寸 arg.Img.Bounds()；JNA 侧没暴露 image 宽高，用绑定的虚拟屏分辨率
            // （截图就是它）。极少数拿不到时退回 MAA 规范分辨率 1280x720。
            val (screenW, screenH) = boundResolution ?: (1280 to 720)
            val box = AutoEcoFarmSwipe.run(
                AutoEcoFarmSwipe.Rect(rect.x, rect.y, rect.w, rect.h),
                param,
                screenW,
                screenH,
            )
            if (outBox != null) lib.MaaRectSet(outBox, box.x, box.y, box.w, box.h)
            Ln.i(
                "MaaRunner: autoEcoFarmCalculateSwipeTarget [$nodeName] " +
                    "roi=(${rect.x},${rect.y},${rect.w},${rect.h}) -> (${box.x},${box.y})",
            )
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: autoEcoFarmCalculateSwipeTarget error on node=$nodeName", t)
            0
        }
    }

    /** `autoEcoFarmResetSwipeState`：清空 [AutoEcoFarmSwipe] 的跨任务状态。 */
    private val autoEcoFarmResetSwipeStateCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, _, _, _, _ ->
        AutoEcoFarmSwipe.resetState()
        Ln.i("MaaRunner: autoEcoFarmResetSwipeState [$nodeName] state cleared")
        1
    }

    /**
     * `autoEcoFarmOverrideTargetTemplate`：把若干节点的 template 覆写成目标模板。
     * 构造在 [AutoEcoFarmOverride]（已单测）；参数不合法**返回失败**而不是静默放行。
     */
    private val autoEcoFarmOverrideTargetTemplateCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        val param = AutoEcoFarmOverride.parseParam(customActionParam) ?: run {
            Ln.w("MaaRunner: autoEcoFarmOverrideTargetTemplate 参数不合法: $customActionParam")
            return@MaaCustomActionCallback 0
        }
        val override = AutoEcoFarmOverride.buildOverride(param) ?: run {
            Ln.w("MaaRunner: autoEcoFarmOverrideTargetTemplate 缺少 template 或 nodeNames (node=$nodeName)")
            return@MaaCustomActionCallback 0
        }
        try {
            if (lib.MaaContextOverridePipeline(context, JsonTree.toJson(override)).toInt() == 0) {
                Ln.w("MaaRunner: autoEcoFarmOverrideTargetTemplate 覆写 pipeline 失败 (node=$nodeName)")
                return@MaaCustomActionCallback 0
            }
            Ln.i(
                "MaaRunner: autoEcoFarmOverrideTargetTemplate [$nodeName] " +
                    "template='${param.template.trim()}' nodes=${override.keys}",
            )
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: autoEcoFarmOverrideTargetTemplate error on node=$nodeName", t)
            0
        }
    }

    /** `autoEcoFarmFindNearestRecognitionResult`：跑子识别，挑离指定比例位置最近的框。 */
    private val autoEcoFarmFindNearestCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        val param = AutoEcoFarmNearest.parseParam(customRecognitionParam) ?: run {
            Ln.w("MaaRunner: autoEcoFarmFindNearestRecognitionResult 参数不合法: $customRecognitionParam")
            return@MaaCustomRecognitionCallback 0
        }
        if (!AutoEcoFarmNearest.validate(param)) {
            Ln.w("MaaRunner: autoEcoFarmFindNearestRecognitionResult 参数越界或节点名为空: $customRecognitionParam")
            return@MaaCustomRecognitionCallback 0
        }
        try {
            // 上游 ctx.RunRecognition(name, arg.Img, nil)：沿用当前截图，不重新截图
            val recoId = lib.MaaContextRunRecognition(context, param.recognitionNodeName, "{}", image)
            if (recoId <= 0L) {
                Ln.i("MaaRunner: autoEcoFarmFindNearestRecognitionResult 子识别未命中 (node=${param.recognitionNodeName})")
                return@MaaCustomRecognitionCallback 0
            }
            val detail = MaaJsonTree.parse(recognitionDetailJson(lib, context, recoId))
            val box = AutoEcoFarmNearest.pickNearest(detail, param.xRatio, param.yRatio) ?: run {
                Ln.i("MaaRunner: autoEcoFarmFindNearestRecognitionResult 没有可用结果 (node=${param.recognitionNodeName})")
                return@MaaCustomRecognitionCallback 0
            }
            if (outBox != null) lib.MaaRectSet(outBox, box.x, box.y, box.w, box.h)
            Ln.i(
                "MaaRunner: autoEcoFarmFindNearestRecognitionResult [$nodeName] " +
                    "node=${param.recognitionNodeName} -> (${box.x},${box.y},${box.w},${box.h})",
            )
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: autoEcoFarmFindNearestRecognitionResult error on node=$nodeName", t)
            0
        }
    }

    /**
     * `autoEcoFarmInterruptibleSleep`：250ms 分片休眠，期间按间隔报倒计时，可响应停止。
     * 分片计划在 [AutoEcoFarmSleep]（已单测）；这里只做 sleep 与停止检查。
     */
    private val autoEcoFarmInterruptibleSleepCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 1
        val param = AutoEcoFarmSleep.parseParam(customActionParam) ?: run {
            Ln.w("MaaRunner: autoEcoFarmInterruptibleSleep 参数不合法: $customActionParam")
            return@MaaCustomActionCallback 0
        }
        if (param.durationMs <= 0) return@MaaCustomActionCallback 1
        val tasker = context?.let { lib.MaaContextGetTasker(it) }
        for (segment in AutoEcoFarmSleep.plan(param.durationMs, param.reportIntervalMs)) {
            if (isStopRequested() || (tasker != null && lib.MaaTaskerStopping(tasker).toInt() != 0)) {
                Ln.i("MaaRunner: autoEcoFarmInterruptibleSleep [$nodeName] 任务停止，提前结束")
                return@MaaCustomActionCallback 1
            }
            segment.reportRemainingMs?.let {
                Ln.i("MaaRunner: autoEcoFarmInterruptibleSleep [$nodeName] 剩余 ${AutoEcoFarmSleep.formatRemaining(it)}")
            }
            try {
                Thread.sleep(segment.chunkMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@MaaCustomActionCallback 1
            }
        }
        Ln.i("MaaRunner: autoEcoFarmInterruptibleSleep [$nodeName] 完成")
        1
    }

    private fun registerCustomActions(lib: MaaFrameworkLibrary, res: Pointer) {
        registeredCustomActions.clear()
        registeredCustomRecognitions.clear()
        listCompleteInvocations.clear()
        callbackKeepAlive.clear()

        fun regAction(name: String, cb: MaaFrameworkLibrary.MaaCustomActionCallback) {
            val wrapped = MaaFrameworkLibrary.MaaCustomActionCallback { context, taskId, nodeName, actionName, actionParam, recoId, box, transArg ->
                keepCallbackThread()
                cb(context, taskId, nodeName, actionName, actionParam, recoId, box, transArg)
            }
            callbackKeepAlive += wrapped
            lib.MaaResourceRegisterCustomAction(res, name, wrapped, null)
            registeredCustomActions += name
        }

        fun regReco(name: String, cb: MaaFrameworkLibrary.MaaCustomRecognitionCallback) {
            val wrapped = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, taskId, nodeName, recoName, recoParam, image, roi, transArg, outBox, outDetail ->
                keepCallbackThread()
                cb(context, taskId, nodeName, recoName, recoParam, image, roi, transArg, outBox, outDetail)
            }
            callbackKeepAlive += wrapped
            lib.MaaResourceRegisterCustomRecognition(res, name, wrapped, null)
            registeredCustomRecognitions += name
        }

        regAction("SubTask", subTaskCallback)
        // BetterSliding 不再是 noop：滑条数量是据点交易/囤货/稳定物资购买的共同依赖，
        // noop 成功的后果不是「少个功能」而是**买错卖错数量**
        regAction("BetterSliding", betterSlidingCallback)
        // 稳定物资购买：算出该买多少，覆盖给同级的 BetterSliding
        regAction("AutoStockStapleQuantityControlAction", autoStockStapleQuantityControlCallback)
        regAction("AutoDeliveryResolveDepotAction", resolveDepotCallback)
        regAction("AutoDeliveryResolveDestinationAction", resolveDestinationCallback)
        regAction("CharacterControllerYawDeltaAction", yawDeltaCallback)
        regAction("CharacterControllerPitchDeltaAction", pitchDeltaCallback)
        regAction("CharacterControllerForwardAxisAction", forwardAxisCallback)
        regAction("CharacterControllerRelativeMoveAction", relativeMoveCallback)
        regAction("CharacterMoveToTargetAction", moveToTargetCallback)
        regAction("CharacterMoveToTargetNotFoundAction", moveToTargetNotFoundCallback)
        regAction("CharacterSearchAction", characterSearchCallback)
        regAction("MapNavigateAction", mapNavigateCallback)
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
        regAction("AutoSellItemExecuteItemTaskAction", autoSellItemExecuteItemTaskActionCallback)
        regAction("AutoStockpile.SelectItem", autoStockpileSelectItemCallback)
        // AutoStockpile.ReconcileDecision 不在下面的 noop 列表：它放行 AutoStockpileRelayNodeDecisionReady
        regAction("AutoStockpile.ReconcileDecision", autoStockpileReconcileDecisionCallback)
        regAction("OcrTapAction", ocrTapActionCallback)

        // autoEcoFarm 整组不再是 stub：两个识别 + 三个动作全部真实实现（含 override/reset，
        // 它们是识别链能正确选模板、正确累计滑动状态的前提）
        regReco("autoEcoFarmCalculateSwipeTarget", autoEcoFarmSwipeTargetCallback)
        regReco("autoEcoFarmFindNearestRecognitionResult", autoEcoFarmFindNearestCallback)
        regAction("autoEcoFarmResetSwipeState", autoEcoFarmResetSwipeStateCallback)
        regAction("autoEcoFarmOverrideTargetTemplate", autoEcoFarmOverrideTargetTemplateCallback)
        regAction("autoEcoFarmInterruptibleSleep", autoEcoFarmInterruptibleSleepCallback)

        // itemTransfer：唯一被 pipeline 引用的 `ItemTransferSameItemRecognition` 不再是恒假，
        // 必须在下面的 noop 循环之前注册（它已从 falseRecognitions 名单里删除）。
        // 另外两个动作（OCR/Fallback）上游已弃用且依赖不存在的节点，见回调处注释，不注册假成功。
        regReco("ItemTransferSameItemRecognition", itemTransferSameItemRecognitionCallback)

        // intelarchive：5 个注册名原先全在 noop 名单里，这里全部换成真实实现。
        // 两个 Scan 识别 + 三个动作的先后关系见回调处注释；注册必须在 noop 循环之前。
        regReco("IntelArchiveScanItemsRecognition", intelArchiveScanItemsCallback)
        regReco("IntelArchiveScanDetailRecognition", intelArchiveScanDetailCallback)
        regAction("IntelArchiveResolveTruncAction", intelArchiveResolveTruncCallback)
        regAction("IntelArchiveResetSessionAction", intelArchiveResetSessionCallback)
        regAction("IntelArchiveShowInventoryAction", intelArchiveShowInventoryCallback)

        val otherActions = listOf(
            "CreditShoppingScanItemAction",
            "AddItemData",
            "SyncItemData",
            "UpdateItemQuantity",
            "DeliveryJobsResolveOngoingDepotAction",
            // AutoDeliveryResolveDepot/Destination 不在此列：已注册为真实实现
            "CaptureUid",
            "CloseGameAction",
            "ImageCheckSetResultAction",
            "SeizeDeliveryJobsResetScanStateAction",
            "SeizeDeliveryJobsScanTargetAction",
            // autoEcoFarmResetSwipeState/InterruptibleSleep/OverrideTargetTemplate 不在此列：
            // 已注册为真实实现
            "AutoFightMainAction",
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
            // CharacterController*/MapNavigateAction 不在此列：已注册为真实实现
            "FocusOCRAction",
            "FailureCollectorFinish",
            "FailureCollectorReset",
            "FailureCollectorRunTask",
            "ImportBluePrintsEnterCodeAction",
            "ImportBluePrintsFinishAction",
            "ImportBluePrintsInitTextAction",

            "OutpostTradingLocationPlan",
            // OutpostTradingPrioritySession 不在此列：已显式注册为 outpostPrioritySessionCallback
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
        // 上游 ScreenshotStableRecognition 仅在 Win32-Front 下判定「画面连续三帧不变」，
        // 其余平台固定返回 false。之前注册成恒 true，会让 __ScenePrivateScreenshotStableAbnormal
        // 永远命中并把 FalseAction 判失败，直接掐断 SceneAnyEnterWorld 的 next 链，
        // 导致 __ScenePrivateAnyExit（返回键）根本执行不到——游戏留在菜单里，后续任务全部失败。
        regReco("ScreenshotStableRecognition", noopFalseRecognitionCallback)
        // ScheduleRecognition 不再是恒真：恒真会让「只勾了某几天」的周期设置完全失效
        regReco("ScheduleRecognition", scheduleRecognitionCallback)
        // debug CLI 的 OCR 探针：外部没有 task context，只能借一个临时节点回调拿到 context，
        // 再在回调里跑目标节点识别（见 debugOcrOnce）
        regReco(DEBUG_OCR_RECO, debugCliOcrCallback)
        regReco("ItemQuantitySatisfied", noopTrueRecognitionCallback)
        regReco("ItemDataReady", noopTrueRecognitionCallback)
        regReco("AutoSellScanItemRecognition", autoSellScanItemRecognitionCallback)
        regReco("ExpressionRecognition", expressionRecognitionCallback)
        regReco("OutpostTradingPriorityItem", outpostTradingPriorityItemCallback)
        regReco("OutpostTradingCurrentGoods", outpostTradingCurrentGoodsCallback)
        regAction("OutpostTradingPrioritySession", outpostPrioritySessionCallback)
        // 干员智能选择：数据/选择/缓存/会话/扫描全部已移植并单测
        regReco("OutpostTradingSelectBestOperator", outpostOperatorSelectBestCallback)
        regReco("OutpostTradingCurrentBestOperator", outpostOperatorCurrentBestCallback)
        regReco("OutpostTradingCurrentOperatorUncached", outpostOperatorCurrentUncachedCallback)
        regReco("OutpostTradingOperatorCacheReady", outpostOperatorCacheReadyCallback)
        regReco("OutpostTradingOperatorListBottom", outpostOperatorListBottomCallback)
        regReco("OutpostTradingOperatorScanOutcome", outpostOperatorScanOutcomeCallback)
        regReco("OutpostTradingOperatorConflict", outpostOperatorConflictCallback)
        regAction("OutpostTradingOperatorSession", outpostOperatorSessionCallback)
        regReco("OcrCheckRecognition", ocrCheckRecognitionCallback)
        // AutoStockpile 的两个识别不再返回恒假：Recognition 恒假会让
        // AutoStockpileDecision<Region> 永不命中，整条决策—购买链直接断掉
        regReco("AutoStockpile.Recognition", autoStockpileRecognitionCallback)
        regReco("AutoStockpile.SelectedGoodsHit", autoStockpileSelectedGoodsHitCallback)
        // 会客室·交流倒计时不再恒假：恒假会让「等待交流结束/长时间保活」分支永不可达
        regReco(
            "ReceptionRoomExchangeCountdownWithinThresholdRecognition",
            receptionCountdownWithinThresholdCallback,
        )
        regReco(
            "ReceptionRoomWaitExchangeKeepAliveDueRecognition",
            receptionKeepAliveDueCallback,
        )

        val falseRecognitions = listOf(
            "ImageCheckNotPassedRecognition",
            "IconRecognition",
            "AeroSalvageBalloonStateRecognition",
            "AeroSalvageGridRecognition",
            "AeroSalvageInitialStateRecognition",
            "AutoFightEntryRecognition",
            "EssenceFilterAfterBattleNthRecognition",
            "EssenceGridAdvanceRecognition",
            "EssenceGridPendingRecognition",
            "MapFind",
            "MapLocateAssertLocation",
            "PuzzleRecognition",
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
            callbackKeepAlive.clear()
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

    internal companion object {
        /**
         * 货卡 OCR 的颜色过滤，同上游 AutoStockpileGoodsFilter：
         * 灰度 60~150 留下、其余刷黑，等价于「只保留价格与商品名的浅色文字」。
         * 顶部页签那种浅色压深色的文字不能用它，会被刷成全黑。
         */
        const val GOODS_COLOR_FILTER = "AutoStockpileGoodsFilter"

        /**
         * controller 配置里的 library_path 用裸名：bridge 已在本进程 System.loadLibrary 过，
         * 控制单元按名 dlopen 才会命中同一份。名字必须与 NativeBridgeLib 初始化时一致。
         */
        const val BRIDGE_LIBRARY_NAME = "bridge"

        /** MotionSupport 等外部单例需要拿 controller 发触摸/转向事件（单实例 runner） */
        @Volatile
        var currentController: Pointer? = null
            private set

        /** MaaInvalidId */
        const val INVALID_ID = 0L

        /** debug CLI 探针识别用：临时节点名与注册的识别名 */
        const val DEBUG_OCR_NODE = "__DebugCliOcr__"
        const val DEBUG_OCR_RECO = "DebugCliOcr"


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

/**
 * debug CLI 的识别结果。
 *
 * [text] 为 null 时看 [reason]；[hit] 只表示节点是否命中，模板类节点可能命中但无 OCR 文本。
 */
data class DebugRecoOutcome(
    val hit: Boolean,
    val text: String?,
    val reason: String?,
)
