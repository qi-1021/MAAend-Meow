package com.aliothmoon.maafw.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aliothmoon.maafw.BuildConfig
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
import com.aliothmoon.maafw.maa.MaaImageType
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
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

        /**
         * BetterSliding 越界分支手工触发的结果节点 satisfy。
         *
         * 复用 [satisfyOutpostReserve]——与 pipeline 里 `OutpostTradingReserveSession`
         * 的 `satisfy` 是同一份语义，绕过框架会间歇性 SIGSEGV 的 `enabled` 覆盖。
         */
        override fun satisfyReserveOutcome(): Boolean = satisfyOutpostReserve("BetterSliding outcome override")

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
     * CameraScanAction：转动相机并识别 wait_nodes，找到即成功。
     *
     * 对齐上游 `camerascan/action.go` + `path.go`：九宫格 8 步 → 复位 1 步（不识别）→
     * 中/上/下三个俯仰环（每环 1 步俯仰 + N 步右转）；除复位步外每步**移动前后各识别一次**。
     * 走完全部步数仍未命中返回失败（0），不是 noop-success。
     *
     * 参数解析 / 路径 / 超时判定都在 [CameraScanSupport]（已单测）；这里只接 MaaFramework：
     * 截图识别走 [runRecognitionOnce]，相机转动走 [MotionSupport.rotateView]
     * （像素量对齐上游默认移动节点 `Common/Private/CameraScan/Action.json`：上下 240、左右 160）。
     */
    private val cameraScanCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val param = CameraScanSupport.parseParam(customActionParam)
            if (param == null) {
                Ln.e(
                    "MaaRunner: CameraScanAction [$nodeName] 参数不合法" +
                        "（wait_nodes 必填；fallback_yaw_steps 4..72）: $customActionParam",
                )
                return@MaaCustomActionCallback 0
            }
            CameraScanSupport.customMoveWarn(param)?.let {
                Ln.w("MaaRunner: CameraScanAction [$nodeName] $it")
            }

            val tasker = lib.MaaContextGetTasker(context)
            val path = CameraScanSupport.buildPath(param.fallbackYawSteps)
            val budget = CameraScanSupport.budget(param.fallbackYawSteps)
            val startMs = System.currentTimeMillis()
            val stopping = {
                isStopRequested() || (tasker != null && lib.MaaTaskerStopping(tasker).toInt() != 0)
            }

            for ((index, step) in path.withIndex()) {
                val reason = CameraScanSupport.stopReason(
                    stepIndex = index,
                    budget = budget,
                    stopping = stopping(),
                    elapsedMs = System.currentTimeMillis() - startMs,
                )
                if (reason != CameraScanSupport.StopReason.NONE) {
                    Ln.w("MaaRunner: CameraScanAction [$nodeName] 第 ${index + 1}/${budget.totalSteps} 步前中止（$reason）")
                    return@MaaCustomActionCallback 0
                }

                if (step.needsRecognition) {
                    val hit = recognizeCameraScanTargets(lib, context, param.waitNodes, nodeName)
                    if (hit != null) return@MaaCustomActionCallback finishCameraScanHit(hit, param, nodeName)
                }

                for (move in CameraScanSupport.movesFor(step)) {
                    if (stopping()) return@MaaCustomActionCallback 0
                    val (dx, dy) = CameraScanSupport.pixelDelta(move)
                    MotionSupport.rotateView(dx, dy)
                }

                if (step.needsRecognition) {
                    val hit = recognizeCameraScanTargets(lib, context, param.waitNodes, nodeName)
                    if (hit != null) return@MaaCustomActionCallback finishCameraScanHit(hit, param, nodeName)
                }
            }

            Ln.i("MaaRunner: CameraScanAction [$nodeName] ${budget.totalSteps} 步扫完未找到 wait_nodes=${param.waitNodes}")
            0
        } catch (t: Throwable) {
            Ln.e("MaaRunner: CameraScanAction error on node=$nodeName", t)
            0
        }
    }

    /** 截图并依次识别 wait_nodes，返回首个命中详情（含框）；截图失败或无命中返回 null。 */
    private fun recognizeCameraScanTargets(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        waitNodes: List<String>,
        nodeName: String?,
    ): RecoResult? {
        val ctrl = MaaRunner.currentController ?: return null
        if (!MotionSupport.screencapFresh()) return null
        val imgBuf = lib.MaaImageBufferCreate() ?: return null
        return try {
            if (lib.MaaControllerCachedImage(ctrl, imgBuf).toInt() == 0) return null
            if (lib.MaaImageBufferIsEmpty(imgBuf).toInt() != 0) return null
            for (node in waitNodes) {
                val res = runRecognitionOnce(lib, context, imgBuf, node)
                if (res?.hit == true) {
                    Ln.i("MaaRunner: CameraScanAction [$nodeName] 命中 '$node'")
                    return res
                }
            }
            null
        } finally {
            lib.MaaImageBufferDestroy(imgBuf)
        }
    }

    /** 上游 `finishHit`：未开 aim_target 直接成功；开了则按命中框中心做对准滑动，空框判失败。 */
    private fun finishCameraScanHit(
        hit: RecoResult,
        param: CameraScanSupport.Param,
        nodeName: String?,
    ): Byte {
        if (!param.aimTarget) return 1
        val b = hit.box
        val rect = if (b != null && b.size >= 4) {
            CameraScanSupport.Rect(b[0], b[1], b[2], b[3])
        } else {
            CameraScanSupport.Rect(0, 0, 0, 0)
        }
        val (dx, dy) = CameraScanSupport.aimDelta(rect) ?: run {
            Ln.e("MaaRunner: CameraScanAction [$nodeName] aim_target 命中框为空，判失败")
            return 0
        }
        if (dx != 0 || dy != 0) MotionSupport.rotateView(dx, dy)
        return 1
    }

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

    /**
     * DeliveryJobsResolveOngoingDepotAction：从送货任务详情的区域 OCR 文本解析残留任务所属仓储，
     * 并把当前节点 next 覆盖到该仓储的分派节点 `DeliveryJobsOngoingDeliveryFor<AreaID>`
     * （对齐上游 deliveryjobs/ongoing_delivery.go；解析逻辑在 [OngoingDeliverySupport]）。
     *
     * 上游语义是「解析不出来就直接失败」，不是走默认分支：残留送货若静默走默认分支，
     * 整条流程会走偏到结束且节点报成功。所以这里任何一步失败都返回 0 并打日志。
     */
    private val resolveOngoingDepotCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, _, recoId, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        try {
            val detail = BetterSlidingOcr.fromRecognizedDetail(
                MaaJsonTree.parse(recognitionDetailJson(lib, context, recoId)),
                nodeName.orEmpty(),
            )
            val areaText = OngoingDeliverySupport.extractAreaText(detail)
            val res = OngoingDeliverySupport.resolve(areaText)
            if (lib.MaaContextOverridePipeline(context, OngoingDeliverySupport.nextOverrideJson(res.nextNode)).toInt() == 0) {
                Ln.e("MaaRunner: DeliveryJobsResolveOngoingDepot override 失败 next='${res.nextNode}'")
                return@MaaCustomActionCallback 0
            }
            Ln.i(
                "MaaRunner: DeliveryJobsResolveOngoingDepot [$nodeName] area='${res.areaId}' " +
                    "depot='${res.depotId}' next='${res.nextNode}' sim=%.3f".format(res.similarity),
            )
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: DeliveryJobsResolveOngoingDepot 解析失败，节点按上游语义失败 (node=$nodeName)", t)
            0
        }
    }

    /**
     * `PipelineOverride` / `PipelineOverrideAction`：运行时合并覆盖节点。
     * 注册表里两个名字指向同一 runner（对齐上游 register.go），所以两处行为天然一致。
     *
     * 语义对齐上游 `common/pipelineoverride/action.go`：
     *  - 参数解析 / 开关判定 / strip next 全在纯逻辑层 [PipelineOverrideSupport]；
     *  - 默认 strip 掉每个节点片段顶层的 `next`，`allow_next=true` 才保留；
     *  - `strict=true` 且 `allow_next=false` 时，patch 带 next 直接失败；
     *  - **解析失败即失败**（返回 0 + 日志），不再 warn 后返回 1。
     */
    private val pipelineOverrideCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library
        if (lib == null) {
            Ln.e("MaaRunner: PipelineOverrideAction [$nodeName] 框架库未加载，失败")
            return@MaaCustomActionCallback 0
        }
        if (context == null) {
            Ln.e("MaaRunner: PipelineOverrideAction [$nodeName] context 为空，失败")
            return@MaaCustomActionCallback 0
        }

        when (val outcome = PipelineOverrideSupport.parse(customActionParam)) {
            is PipelineOverrideSupport.Outcome.Rejected -> {
                Ln.e("MaaRunner: PipelineOverrideAction [$nodeName] 参数非法：${outcome.reason}")
                0
            }

            is PipelineOverrideSupport.Outcome.Apply -> {
                if (outcome.allowNext && outcome.strictRequested) {
                    Ln.i("MaaRunner: PipelineOverrideAction [$nodeName] allow_next=true，strict 被忽略")
                }
                if (outcome.strippedNextNodes.isNotEmpty()) {
                    Ln.i(
                        "MaaRunner: PipelineOverrideAction [$nodeName] allow_next=false，" +
                            "已从 patch 移除 next：${outcome.strippedNextNodes}",
                    )
                }
                if (outcome.resourceOverride) {
                    Ln.w(
                        "MaaRunner: PipelineOverrideAction [$nodeName] 请求 resource_override=true，" +
                            "但移动端宿主未绑定 MaaResourceOverridePipeline，暂时回退为 context 作用域" +
                            "（补丁不会跨任务持久生效）",
                    )
                }
                try {
                    val overrideJson = JsonTree.toJson(outcome.cleanPatch)
                    if (lib.MaaContextOverridePipeline(context, overrideJson).toInt() == 0) {
                        Ln.e("MaaRunner: PipelineOverrideAction [$nodeName] OverridePipeline 调用失败")
                        0
                    } else {
                        Ln.i(
                            "MaaRunner: PipelineOverrideAction [$nodeName] applied patch " +
                                "nodes=${outcome.cleanPatch.keys}",
                        )
                        1
                    }
                } catch (t: Throwable) {
                    Ln.e("MaaRunner: PipelineOverrideAction error on node=$nodeName", t)
                    0
                }
            }
        }
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

    /**
     * ListComplete / ScrollbarComplete 共用的运行时状态。
     *
     * 判定主逻辑是**画面比对**（见 [handleListCompleteRecognition] 与 [ListCompleteSupport]），
     * 本对象只保存「比过但未到底」的连续次数，作为防死循环的硬上限安全阀。
     * 旧实现把两个识别都写成「第 N 次调用恒真」，导致 IntelArchive 每个页签只滑约 4 屏
     * 就判到底、后面的条目（paper 实测 238 条）永远扫不到。
     */
    private val listCompleteSession = ListCompleteSupport.Session()
    private val listCompleteLock = Any()

    /** 临时 TemplateMatch 节点名（ListComplete/ScrollbarComplete 共用；override 每次写全可变键）。 */
    private val listCompleteTemplateMatchNode = "__ListCompleteTemplateMatch"

    private val listCompleteRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
        handleListCompleteRecognition(
            context, nodeName, customRecognitionParam, image, roi, outBox, "ListCompleteRecognition",
        )
    }

    private val scrollbarCompleteRecognitionCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, image, roi, _, outBox, _ ->
        handleListCompleteRecognition(
            context, nodeName, customRecognitionParam, image, roi, outBox, "ScrollbarCompleteRecognition",
        )
    }

    /**
     * 「列表/滚动条是否到底」的共享实现，对齐上游 `listcomplete/recognition.go:53-184`：
     *
     *  1. 读调用节点 `attach.ready`；
     *  2. 未就绪 → 从当前帧按节点 roi 裁出模板，`OverrideImage` 写进运行时模板、
     *     把 `attach.ready` 置真，**返回未命中**（首轮只建模板）；
     *  3. 已就绪 → 对同一 roi 跑 `TemplateMatch`，相似度 `>= threshold` 判到底返回命中；
     *     否则重截模板返回未命中（画面还在变，继续滑）。
     *
     * 与上游的差异：`ScrollbarCompleteRecognition` 上游比滑块 top/bottom 位置，这里同样用
     * 「roi 画面是否变化」判定（滚动条不动 == 列表到底），共用同一套模板比对逻辑；
     * 另加 [ListCompleteSupport.DEFAULT_MAX_ATTEMPTS] 硬上限兜底，防 roi 内有动画时死循环。
     */
    private fun handleListCompleteRecognition(
        context: Pointer?,
        nodeName: String?,
        customRecognitionParam: String?,
        image: Pointer?,
        roiPtr: Pointer?,
        outBox: Pointer?,
        logTag: String,
    ): Byte {
        val lib = MaaFrameworkLoader.library ?: return 0.toByte()
        if (context == null || image == null || nodeName.isNullOrBlank()) {
            Ln.w("MaaRunner: $logTag 缺少 context/image/nodeName")
            return 0.toByte()
        }
        return try {
            val node = nodeName.trim()
            val params = ListCompleteSupport.parseParams(customRecognitionParam)
            if (params.thresholdRejected || params.maxAttemptsRejected) {
                Ln.w(
                    "MaaRunner: $logTag 参数非法已回落 " +
                        "(node=$node threshold=${params.threshold} maxAttempts=${params.maxAttempts} raw=$customRecognitionParam)",
                )
            }

            // 读节点定义取 attach.ready；读不到按未就绪处理（与上游 loadReady 失败即 false 一致）
            val nodeJson = nodeDefinitionJson(lib, context, node)
            val ready = ListCompleteSupport.isReady(nodeJson)

            val callbackRoi = getBoxRect(lib, roiPtr)
            val matchRoi = if (callbackRoi.w > 0 && callbackRoi.h > 0) {
                intArrayOf(callbackRoi.x, callbackRoi.y, callbackRoi.w, callbackRoi.h)
            } else {
                null
            }

            // 只有 ready（模板已存在）才比模板；否则直接进入建模板分支
            val score = if (ready) {
                runListCompleteTemplateMatch(lib, context, image, node, params.threshold, matchRoi)
            } else {
                null
            }

            val action = synchronized(listCompleteLock) {
                listCompleteSession.decide(node, ready, score, params.threshold, params.maxAttempts)
            }

            when (action) {
                ListCompleteSupport.Action.COMPLETE -> {
                    Ln.i(
                        "MaaRunner: $logTag 判定到底 node=$node score=$score " +
                            "threshold=${params.threshold}",
                    )
                    if (outBox != null && matchRoi != null) {
                        lib.MaaRectSet(outBox, matchRoi[0], matchRoi[1], matchRoi[2], matchRoi[3])
                    }
                    1.toByte()
                }

                ListCompleteSupport.Action.CAPTURE_TEMPLATE,
                ListCompleteSupport.Action.RECAPTURE_TEMPLATE -> {
                    val firstCapture = action == ListCompleteSupport.Action.CAPTURE_TEMPLATE
                    val ok = captureListCompleteTemplate(
                        lib, context, image, node,
                        intArrayOf(callbackRoi.x, callbackRoi.y, callbackRoi.w, callbackRoi.h),
                    )
                    if (ok && firstCapture) {
                        saveListCompleteReady(lib, context, node, nodeJson, true)
                    }
                    Ln.i(
                        "MaaRunner: $logTag ${if (firstCapture) "首轮建模板" else "重截模板"} " +
                            "node=$node ok=$ok score=$score threshold=${params.threshold}",
                    )
                    0.toByte()
                }
            }
        } catch (t: Throwable) {
            Ln.e("MaaRunner: $logTag 异常 node=$nodeName", t)
            0.toByte()
        }
    }

    /** 对运行时模板跑一次 `TemplateMatch`，返回 `best.score`；识别失败/无模板返回 null。 */
    private fun runListCompleteTemplateMatch(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        node: String,
        threshold: Double,
        roi: IntArray?,
    ): Double? {
        val templateName = ListCompleteSupport.templateName(node)
        val nodeOverride = ListCompleteSupport.buildTemplateMatchOverride(
            nodeName = listCompleteTemplateMatchNode,
            templateName = templateName,
            threshold = threshold,
            roi = roi,
        )
        val res = runRecognitionOnce(lib, context, image, listCompleteTemplateMatchNode, nodeOverride)
            ?: return null
        return ListCompleteSupport.bestTemplateScore(res.detailJson)
    }

    /**
     * 按 ROI 从当前帧裁一块、写进运行时模板（`MaaContextOverrideImage`）。
     *
     * 链路与 [debugOverrideProbe] 同源：编码 PNG 读帧 → Bitmap 裁剪 → ARGB→BGR →
     * `MaaImageBufferSetRawData(CV_8UC3)` → `MaaContextOverrideImage`。不落盘。
     */
    private fun captureListCompleteTemplate(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        node: String,
        roi: IntArray,
    ): Boolean {
        val bytes = readEncodedImage(lib, image) ?: run {
            Ln.w("MaaRunner: ListComplete 读帧失败 node=$node")
            return false
        }
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
            Ln.w("MaaRunner: ListComplete 解码帧失败 node=$node")
            return false
        }
        val buf = lib.MaaImageBufferCreate() ?: run {
            bitmap.recycle()
            return false
        }
        try {
            val norm = ListCompleteSupport.normalizeRoi(roi, bitmap.width, bitmap.height) ?: run {
                Ln.w("MaaRunner: ListComplete roi 非法 node=$node roi=${roi.toList()}")
                return false
            }
            val (x, y, w, h) = norm
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, x, y, w, h)
            val bgr = YoloPreprocess.argbToBgr(argb)
            val mem = Memory(bgr.size.toLong())
            mem.write(0, bgr, 0, bgr.size)
            if (lib.MaaImageBufferSetRawData(buf, mem, w, h, MaaImageType.CV_8UC3).toInt() == 0) {
                Ln.w("MaaRunner: ListComplete SetRawData 失败 node=$node ${w}x$h")
                return false
            }
            val templateName = ListCompleteSupport.templateName(node)
            val ok = lib.MaaContextOverrideImage(context, templateName, buf).toInt() != 0
            if (!ok) {
                Ln.w("MaaRunner: ListComplete OverrideImage 失败 node=$node template=$templateName")
            }
            return ok
        } finally {
            lib.MaaImageBufferDestroy(buf)
            bitmap.recycle()
        }
    }

    /** 把 `attach.ready` 写回调用节点（合并保留其它 attach 键）。 */
    private fun saveListCompleteReady(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        node: String,
        existingNodeJson: String?,
        ready: Boolean,
    ): Boolean {
        val overrideJson = ListCompleteSupport.buildReadyOverride(node, existingNodeJson, ready)
        val ok = lib.MaaContextOverridePipeline(context, overrideJson).toInt() != 0
        if (!ok) {
            Ln.w("MaaRunner: ListComplete 写 attach.ready 失败 node=$node ready=$ready")
        }
        return ok
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
        pipelineOverride: String = "{}",
    ): RecoResult? {
        val recoId = lib.MaaContextRunRecognition(context, nodeName, pipelineOverride, image)
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
                    recognitionNumber(lib, context, image, name).toLong()
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

    /**
     * 库存为空/未初始化时只提醒一次：IMS 记账（AddItemData/SyncItemData）仍是 noop，
     * 所有 `{item}` 按 0 参与求值，门控只会放行采集、不会跳过——别误以为门控生效。
     */
    private val itemQuantityEmptyInventoryLogged = AtomicBoolean(false)

    /**
     * 对齐上游 `ims.ItemQuantitySatisfied`：对当前 IMS 库存求值布尔表达式（R1）。
     *
     * 本项目的库存容器 [Ims] 因记账动作未移植而恒为空：缺失物品 = 0 = 未达标，于是
     * `{item} < {limit}` / `{limit} == 0` 这类门控命中 → 放行去采集，`SubSkipped` 的
     * `!({limit}==0 || {item}<{limit})` 不命中 → **不会跳过**。这与「把未知当已达标」
     * 的旧恒真行为正好相反。
     *
     * `notify_ui` / `report_only` 上游走 maafocus 浮层；本项目没有该通道，降级为日志。
     */
    private val itemQuantitySatisfiedCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { _, _, nodeName, _, customRecognitionParam, _, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        try {
            val param = ItemQuantitySupport.parseParams(customRecognitionParam)
            val snapshot = Ims.cache.snapshot()
            if ((!snapshot.hasData || snapshot.items.isEmpty()) &&
                itemQuantityEmptyInventoryLogged.compareAndSet(false, true)
            ) {
                Ln.w(
                    "MaaRunner: ItemQuantitySatisfied 库存为空/未初始化" +
                        "（IMS 记账 AddItemData/SyncItemData 仍为 noop，hasData=${snapshot.hasData}）——" +
                        "所有 {item} 按 0=未达标 参与求值，门控只会放行采集、不会跳过；此日志只打一次",
                )
            }
            val evaluation = ItemQuantitySupport.evaluate(param) { name -> Ims.cache.quantity(name) }
            Ln.i(
                "MaaRunner: ItemQuantitySatisfied [$nodeName] expr='${param.expression}' " +
                    "resolved='${evaluation.resolvedExpression}' values=${evaluation.values} " +
                    "matched=${evaluation.matched}",
            )
            if (param.reportOnly) {
                Ln.i(
                    "MaaRunner: ItemQuantitySatisfied [$nodeName] report_only " +
                        "item='${evaluation.reportItemId}' quantity=${evaluation.reportQuantity}",
                )
            } else if (param.notifyUi) {
                Ln.i("MaaRunner: ItemQuantitySatisfied [$nodeName] notify_ui resolved='${evaluation.resolvedExpression}'")
            }
            if (!evaluation.matched) return@MaaCustomRecognitionCallback 0
            if (outBox != null) {
                val r = getBoxRect(lib, roi)
                lib.MaaRectSet(outBox, r.x, r.y, r.w, r.h)
            }
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: ItemQuantitySatisfied error on node=$nodeName", t)
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
            val names = regionItemMap[region]
            if (names == null) {
                // 扫描回调本应把本区物资写进 regionItemMap；连该区的 key 都没有，说明**扫描没生效**
                // （页签没切过去、识别失败等）。若按上游 `return true` 跳过，会在「一件都没卖」的
                // 情况下让整任务显示成功——正是用户报「没卖出去却成功」的隐患。判失败让问题可见。
                Ln.w(
                    "MaaRunner: AutoSellItemExecuteItemTaskAction [$nodeName] region='$region' " +
                        "regionItemMap 里没有该区（扫描未生效），动作判失败",
                )
                return@MaaCustomActionCallback 0
            }
            if (names.isEmpty()) {
                // 扫描过、本区确实没有可处理的物资：与上游语义一致——跳过，不算失败。
                Ln.i(
                    "MaaRunner: AutoSellItemExecuteItemTaskAction [$nodeName] region='$region' " +
                        "扫描过但无可处理物资，跳过（不算失败）",
                )
                return@MaaCustomActionCallback 1
            }
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
                // 对齐上游 autosell.go:172-181：RunTask 后不仅要拿到 detail，还必须
                // detail.Status.Success()，否则 hasError → return false。此前只查 subId<=0，
                // 子任务内部失败（识别没命中 / 价格不达标 / 点击没生效）全被吞掉，外层照样
                // 显示成功——这就是「卖不出去任务却成功」的直接原因。用 subTaskSucceeded
                // 查 tasker 上该子任务的状态（与 failurecollector 同一套判定）。
                if (!subTaskSucceeded(lib, context, subId)) {
                    Ln.e(
                        "MaaRunner: AutoSellItemExecuteItemTaskAction [$nodeName] prepare 子任务未成功 " +
                            "item='$name' target='$targetName' subId=$subId",
                    )
                    return@MaaCustomActionCallback 0
                }
            }
            1
        } catch (t: Throwable) {
                Ln.e("MaaRunner: AutoSellItemExecuteItemTaskAction error on node=$nodeName", t)
            0
        }
    }

    /**
     * 据点交易货品会话（对齐上游 `OutpostTradingPrioritySession` / 选品）。
     *
     * 已尝试 / 待确认 / 当前物品 / 缺货 / 用户优先顺序都在这里；任务开始时由
     * [resetOutpostTransientState] 清空。此前移动端用裸 [ConcurrentHashMap] 记
     * 「已尝试」且从不清，导致同进程第二次跑任务把上次残留当已尝试、提前 exhausted。
     */
    private val outpostPrioritySession = OutpostPrioritySupport.Session()

    /** 据点交易的独立保留规则会话（对齐上游 `OutpostTradingReserveSession`）。 */
    private val outpostReserveSession = OutpostReserveSupport.Session()

    /**
     * 失败收集表（对齐上游 `failurecollector`）。采集/环境监测每个子路线由
     * `FailureCollectorRunTask` 执行，失败记录进这里，最后由 `FailureCollectorFinish`
     * 汇总并据此判定任务失败。表按 key 隔离，Finish 时删除对应 key。
     */
    private val failureCollectorSession = FailureCollectorSupport.Session()

    /**
     * 抢占送货任务「扫描目标」路径的进程内状态（对齐上游 `scan_target.go:L27-30` 的包级
     * `scannedJobItems` / `currentIndex`）。默认路径（价格筛选）不用它；指定送达点时才缓存整列
     * 达标委托，由 `SeizeDeliveryJobsScanTargetAction` 逐项点开查看，用尽/匹配后由
     * `SeizeDeliveryJobsResetScanStateAction` 清空。
     */
    private val seizeDeliveryScanSession = SeizeDeliverySupport.ScanSession()

    /**
     * `OutpostTradingCurrentGoods` 识别到的当前货品名，按 location 暂存，
     * 供同节点的 `adopt` 动作读取（动作回调拿不到商品名，只有识别回调能 OCR）。
     */
    private val outpostCurrentGoods = ConcurrentHashMap<String, String>()

    /**
     * 任务开始时清据点交易瞬态：保留规则、已尝试/待确认/当前/缺货、当前货品交接槽。
     *
     * 上游由 `OutpostTradingInitializeReserveSession` 的 reset 连带清空；移动端此前
     * 「已尝试」表从不清，同进程第二次跑任务会把上次残留当已尝试、提前 exhausted。
     */
    private fun resetOutpostTransientState() {
        outpostReserveSession.reset()
        outpostPrioritySession.resetAll()
        outpostCurrentGoods.clear()
    }

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

    /**
     * 选品排除集合（对齐上游 selection.go:80-91）：
     * 已尝试 / 任务级缺货 / 永不售卖 / 保留已满足，四类一律不得再入选。
     */
    private fun outpostExcludedNames(location: String): Set<String> =
        outpostPrioritySession.attemptedNames(location) +
            outpostPrioritySession.outOfStockNames() +
            outpostReserveSession.blacklistedItems() +
            outpostReserveSession.satisfiedItems()

    /**
     * 候选基础顺序：`bias`（低买价升序优先表）优先，其次 strategy=price 走跨据点套利排序，
     * 否则保持据点原始顺序。bias 表外的货物按原顺序接在后面，保证仍可被选品过滤看到。
     */
    private fun outpostBaseOrder(names: List<String>, location: String, bias: String): List<String> {
        val biasOrder = bias.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (biasOrder.isNotEmpty()) {
            val known = biasOrder.filter { it in names }
            return known + names.filter { it !in known }
        }
        return if (outpostPrioritySession.strategy == "price") outpostArbitrageOrder(names, location) else names
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

    /**
     * 对齐上游 OutpostTradingPriorityItem（recognition.go）：
     * 按当前选品规则 + 排除集合选下一个可售货品；`select` 记为 pending 等 commit 确认，
     * `exhausted` 需连续两帧同一「仅剩已尝试/不可选」集合才确认。
     */
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
            val r = getBoxRect(lib, roi)
            // 货卡网格：一个 ROI 里多个货名+价格，只有完整检测才能分出多条（onlyRec=false）
            val items = ocrProbe(lib, context, image, intArrayOf(r.x, r.y, r.w, r.h), onlyRec = false)

            if (result == "exhausted") {
                // pending 表示上次点击尚未确认，不能改选其他物品或误判耗尽（上游 recognition.go:85-93）
                if (outpostPrioritySession.pendingName(location).isNotEmpty()) {
                    return@MaaCustomRecognitionCallback 0
                }
                val saved = outpostPrioritySession.goodsSelectionExhausted(location)
                val observed = saved.ifEmpty { OutpostPrioritySupport.visibleStandardNames(items, names) }
                if (outpostPrioritySession.observeExhaustion(location, observed)) {
                    Ln.i("MaaRunner: OutpostTradingPriorityItem [$nodeName] location=$location exhausted (observed=$observed)")
                    return@MaaCustomRecognitionCallback 1
                }
                return@MaaCustomRecognitionCallback 0
            }

            outpostPrioritySession.beginGoodsSelection(location)
            val pending = outpostPrioritySession.pendingName(location)
            if (pending.isNotEmpty()) {
                // 已选待确认：只允许在仍可见时重报同一项，绝不改选
                val m = items.firstOrNull { o ->
                    o.box != null && (o.text.contains(pending) || pending.contains(o.text))
                }
                if (m?.box != null) {
                    if (outBox != null) lib.MaaRectSet(outBox, m.box[0], m.box[1], m.box[2], m.box[3])
                    return@MaaCustomRecognitionCallback 1
                }
                return@MaaCustomRecognitionCallback 0
            }

            val policy = outpostPrioritySession.policy()
            val excluded = outpostExcludedNames(location)
            val order = OutpostPrioritySupport.selectableNames(
                baseOrder = outpostBaseOrder(names, location, bias),
                preferred = policy.preferred,
                onlyPreferred = policy.onlyPreferred,
                excluded = excluded,
            )
            val match = OutpostPrioritySupport.firstVisible(items, order)
            if (match == null) {
                // 本帧没有可选货物：保存识别集合，交给下一个 exhausted 节点做稳定确认
                outpostPrioritySession.setGoodsSelectionExhausted(
                    location,
                    OutpostPrioritySupport.visibleStandardNames(items, names),
                )
                return@MaaCustomRecognitionCallback 0
            }
            val (standard, ocr) = match
            outpostPrioritySession.setPending(location, standard)
            if (outBox != null && ocr.box != null) {
                lib.MaaRectSet(outBox, ocr.box[0], ocr.box[1], ocr.box[2], ocr.box[3])
            }
            Ln.i(
                "MaaRunner: OutpostTradingPriorityItem [$nodeName] location=$location select='$standard' " +
                    "order=${order.size} excluded=${excluded.size}",
            )
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingPriorityItem error on node=$nodeName", t)
            0
        }
    }

    /**
     * OutpostTradingCurrentGoods：识别当前选中的货品图标。只有它仍在可选集合内
     * （未被尝试/缺货/永不售卖/保留已满足排除，且未违反 only_preferred）才命中，
     * 否则返回 0，让 Pipeline 落到更换货品流程——否则会把永不出售的物品沿用下来。
     * 命中时把商品名暂存 [outpostCurrentGoods]，供同节点 adopt 动作绑定当前物品。
     */
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
            }
            if (names.isNullOrEmpty()) return@MaaCustomRecognitionCallback 0
            val r = getBoxRect(lib, roi)
            // 货卡网格：一个 ROI 里多个货名+价格，只有完整检测才能分出多条（onlyRec=false）
            val items = ocrProbe(lib, context, image, intArrayOf(r.x, r.y, r.w, r.h), onlyRec = false)
            val m = GoodsSupport.findFirstMatch(items, names)
                ?: return@MaaCustomRecognitionCallback 0
            val standard = GoodsSupport.standardName(m.text, names)
                ?: return@MaaCustomRecognitionCallback 0
            val policy = outpostPrioritySession.policy()
            val excluded = if (location.isNotBlank()) {
                outpostExcludedNames(location)
            } else {
                outpostReserveSession.blacklistedItems() + outpostReserveSession.satisfiedItems()
            }
            if (!OutpostPrioritySupport.isSelectable(standard, policy.preferred, policy.onlyPreferred, excluded)) {
                Ln.i("MaaRunner: OutpostTradingCurrentGoods [$nodeName] '$standard' not selectable, fall through to change goods")
                return@MaaCustomRecognitionCallback 0
            }
            if (location.isNotBlank()) outpostCurrentGoods[location] = standard
            if (outBox != null && m.box != null) {
                lib.MaaRectSet(outBox, m.box[0], m.box[1], m.box[2], m.box[3])
            }
            Ln.i("MaaRunner: OutpostTradingCurrentGoods [$nodeName] '$standard'")
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingCurrentGoods error on node=$nodeName", t)
            0
        }
    }

    /**
     * OutpostTradingPrioritySession：对齐上游据点交易会话 action（session.go）。
     *
     * 覆盖全部 operation：configure / configure_strategy / reset_preferred /
     * reset_goods_selection / register / commit / adopt / out_of_stock。
     * 仍不接受空 strategy：上游 `sellstrategy.New("")` 会失败，不再静默接受。
     */
    private val outpostPrioritySessionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        try {
            val p = OutpostPrioritySupport.parseParam(MaaJsonTree.parse(customActionParam))
            if (p == null) {
                Ln.w("MaaRunner: OutpostTradingPrioritySession [$nodeName] 参数不合法: $customActionParam")
                return@MaaCustomActionCallback 0
            }
            when (p.operation) {
                OutpostPrioritySupport.OPERATION_CONFIGURE -> {
                    outpostPrioritySession.configure(p.enabled, p.onlyPreferred)
                    Ln.i(
                        "MaaRunner: OutpostTradingPrioritySession [$nodeName] configure " +
                            "enabled=${p.enabled} only_preferred=${p.onlyPreferred}",
                    )
                }

                OutpostPrioritySupport.OPERATION_CONFIGURE_STRATEGY -> {
                    outpostPrioritySession.configureStrategy(p.strategy, p.minimumPrice)
                    Ln.i(
                        "MaaRunner: OutpostTradingPrioritySession [$nodeName] configure_strategy=" +
                            "${p.strategy} min_unit_price=${p.minimumPrice}",
                    )
                }

                OutpostPrioritySupport.OPERATION_RESET_PREFERRED -> {
                    outpostPrioritySession.resetPreferred(p.enabled)
                    Ln.i("MaaRunner: OutpostTradingPrioritySession [$nodeName] reset_preferred enabled=${p.enabled}")
                }

                OutpostPrioritySupport.OPERATION_RESET_GOODS_SELECTION -> {
                    outpostPrioritySession.resetGoodsSelection()
                    Ln.i("MaaRunner: OutpostTradingPrioritySession [$nodeName] reset_goods_selection")
                }

                OutpostPrioritySupport.OPERATION_REGISTER -> {
                    if (p.itemId.isEmpty()) {
                        Ln.d("MaaRunner: OutpostTradingPrioritySession [$nodeName] unconfigured priority slot skipped")
                    } else {
                        val name = OutpostData.nameOfItem(p.itemId)
                        if (name == null) {
                            Ln.e("MaaRunner: OutpostTradingPrioritySession [$nodeName] unknown item_id='${p.itemId}'")
                            return@MaaCustomActionCallback 0
                        }
                        val registered = outpostPrioritySession.registerPreferred(name)
                        Ln.i(
                            "MaaRunner: OutpostTradingPrioritySession [$nodeName] register " +
                                "item_id=${p.itemId} name='$name' registered=$registered",
                        )
                    }
                }

                OutpostPrioritySupport.OPERATION_COMMIT -> {
                    val name = outpostPrioritySession.commit(p.location)
                    if (name == null) {
                        Ln.e("MaaRunner: OutpostTradingPrioritySession [$nodeName] commit 没有待确认物品 location=${p.location}")
                        return@MaaCustomActionCallback 0
                    }
                    outpostReserveSession.setSelected(name)
                    Ln.i("MaaRunner: OutpostTradingPrioritySession [$nodeName] commit location=${p.location} item='$name'")
                }

                OutpostPrioritySupport.OPERATION_ADOPT -> {
                    val name = outpostCurrentGoods.remove(p.location)
                    if (name.isNullOrEmpty()) {
                        Ln.e("MaaRunner: OutpostTradingPrioritySession [$nodeName] adopt 没有识别到的当前货品 location=${p.location}")
                        return@MaaCustomActionCallback 0
                    }
                    outpostPrioritySession.adopt(p.location, name)
                    outpostReserveSession.setSelected(name)
                    Ln.i("MaaRunner: OutpostTradingPrioritySession [$nodeName] adopt location=${p.location} item='$name'")
                }

                OutpostPrioritySupport.OPERATION_OUT_OF_STOCK -> {
                    val outcome = outpostPrioritySession.markOutOfStock(p.location)
                    if (!outcome.ok) {
                        Ln.e("MaaRunner: OutpostTradingPrioritySession [$nodeName] out_of_stock 没有已提交物品 location=${p.location}")
                        return@MaaCustomActionCallback 0
                    }
                    Ln.i(
                        "MaaRunner: OutpostTradingPrioritySession [$nodeName] out_of_stock " +
                            "location=${p.location} item='${outcome.name}' marked=${outcome.marked}",
                    )
                }
            }
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingPrioritySession error on node=$nodeName", t)
            0
        }
    }

    /**
     * 售卖界面右上角据点调度券数量的 OCR 节点（上游 OutpostShared.json:78）。
     * 活动物品按「额度 ÷ 单价」换算本批可卖量时按需触发。
     */
    private val outpostStockBillsQuantityNode = "OutpostTradingStockBillsQuantity"

    /**
     * OutpostTradingReserveSession：对齐上游 reserve.go 的完整保留规则状态机。
     *
     * 此前移动端整个 action 是 noop-success：用户配置的「保留 N 件 / 永不出售」被静默丢弃，
     * 活动物品缺额度换算还可能卖到超额度触发整任务 Stop。这里：
     *  - reset：清保留规则 + 优先会话（上游 reserve.go:273）
     *  - register：读 custom_action_param.item_id，缺失时回退节点 attach.item_id，
     *    映射成商品名注册；未知 item_id 判失败（不再静默丢规则）
     *  - select/satisfy：维护当前物品 / 已满足集合
     *  - apply：OCR 调度券额度（活动物品）后 OverridePipeline 改写 BetterSliding 与 next
     */
    private val outpostReserveSessionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) {
            Ln.e("MaaRunner: OutpostTradingReserveSession [$nodeName] context is nil")
            return@MaaCustomActionCallback 0
        }
        try {
            val p = OutpostReserveSupport.parseParam(MaaJsonTree.parse(customActionParam))
            if (p == null) {
                Ln.e("MaaRunner: OutpostTradingReserveSession [$nodeName] 参数不合法: $customActionParam")
                return@MaaCustomActionCallback 0
            }
            when (p.operation) {
                OutpostReserveSupport.OPERATION_RESET -> {
                    outpostReserveSession.reset()
                    // 上游 reserve.go:279 reset 会连带清空优先选品会话
                    outpostPrioritySession.resetAll()
                    outpostCurrentGoods.clear()
                    Ln.i("MaaRunner: OutpostTradingReserveSession [$nodeName] reset")
                }

                OutpostReserveSupport.OPERATION_REGISTER -> {
                    var itemId = p.itemId
                    if (itemId.isEmpty()) {
                        itemId = OutpostReserveSupport
                            .parseAttachItemId(nodeDefinitionJson(lib, context, nodeName.orEmpty()))
                            .orEmpty()
                    }
                    if (itemId.isEmpty()) {
                        Ln.i("MaaRunner: OutpostTradingReserveSession [$nodeName] unconfigured reserve slot skipped")
                    } else {
                        val name = OutpostData.nameOfItem(itemId)
                        if (name == null) {
                            Ln.e("MaaRunner: OutpostTradingReserveSession [$nodeName] unknown item_id='$itemId'")
                            return@MaaCustomActionCallback 0
                        }
                        val replaced = outpostReserveSession.registerRule(name, p.quantity)
                        if (replaced) {
                            Ln.w(
                                "MaaRunner: OutpostTradingReserveSession [$nodeName] reserve rule replaced " +
                                    "item_id=$itemId name='$name' quantity=${p.quantity}",
                            )
                        } else {
                            Ln.i(
                                "MaaRunner: OutpostTradingReserveSession [$nodeName] reserve rule registered " +
                                    "item_id=$itemId name='$name' quantity=${p.quantity}",
                            )
                        }
                    }
                }

                OutpostReserveSupport.OPERATION_SELECT -> {
                    val name = OutpostData.nameOfItem(p.itemId)
                    if (name == null) {
                        Ln.e("MaaRunner: OutpostTradingReserveSession [$nodeName] select unknown item_id='${p.itemId}'")
                        return@MaaCustomActionCallback 0
                    }
                    outpostReserveSession.setSelected(name)
                    Ln.d("MaaRunner: OutpostTradingReserveSession [$nodeName] select item='$name'")
                }

                OutpostReserveSupport.OPERATION_APPLY -> {
                    val selected = outpostReserveSession.selectedRule()
                    if (selected.quantity == OutpostReserveSupport.BLACKLIST_QUANTITY) {
                        Ln.e(
                            "MaaRunner: OutpostTradingReserveSession [$nodeName] blacklisted item reached apply " +
                                "item='${selected.name}'",
                        )
                        return@MaaCustomActionCallback 0
                    }
                    val name = selected.name
                    if (name.isEmpty()) {
                        Ln.e("MaaRunner: OutpostTradingReserveSession [$nodeName] apply 没有已选中物品")
                        return@MaaCustomActionCallback 0
                    }
                    val unitPrice = OutpostData.unitPriceByLocation[p.location]?.get(name)
                    if (unitPrice == null || unitPrice <= 0) {
                        Ln.e(
                            "MaaRunner: OutpostTradingReserveSession [$nodeName] 取不到单价 " +
                                "location=${p.location} item='$name'",
                        )
                        return@MaaCustomActionCallback 0
                    }
                    val activity = OutpostData.isActivityItemName(name)
                    var stockBills = 0
                    if (activity) {
                        val read = readStockBillsQuantity(lib, context)
                        if (read == null) {
                            Ln.e("MaaRunner: OutpostTradingReserveSession [$nodeName] 读不到调度券额度，无法换算活动物品可卖量")
                            return@MaaCustomActionCallback 0
                        }
                        stockBills = read
                    }
                    when (
                        val plan = OutpostReserveSupport.planApply(
                            nodeName = nodeName.orEmpty(),
                            slidingNode = p.slidingNode,
                            quantity = selected.quantity,
                            configured = selected.configured,
                            unitPrice = unitPrice,
                            activity = activity,
                            stockBills = stockBills,
                        )
                    ) {
                        is OutpostReserveSupport.ApplyPlan.Failed -> {
                            Ln.e("MaaRunner: OutpostTradingReserveSession [$nodeName] apply 失败: ${plan.reason}")
                            return@MaaCustomActionCallback 0
                        }

                        is OutpostReserveSupport.ApplyPlan.Override -> {
                            if (lib.MaaContextOverridePipeline(context, plan.overrideJson).toInt() == 0) {
                                Ln.e(
                                    "MaaRunner: OutpostTradingReserveSession [$nodeName] OverridePipeline 失败 " +
                                        "sliding=${p.slidingNode}",
                                )
                                return@MaaCustomActionCallback 0
                            }
                            Ln.i(
                                "MaaRunner: OutpostTradingReserveSession [$nodeName] apply item='$name' " +
                                    "quantity=${selected.quantity} configured=${selected.configured} " +
                                    "activity=$activity unit_price=$unitPrice stock_bills=$stockBills next=${plan.next}",
                            )
                        }
                    }
                }

                OutpostReserveSupport.OPERATION_SATISFY -> {
                    if (!satisfyOutpostReserve(nodeName.orEmpty())) {
                        return@MaaCustomActionCallback 0
                    }
                }
            }
            1
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingReserveSession error on node=$nodeName", t)
            0
        }
    }

    /**
     * `OutpostTradingReserveSession` 的 `satisfy`：把当前选中物品标记为本次任务已满足。
     *
     * 真实调用点有两个，必须复用同一份逻辑，否则「手工 satisfy」与「节点 satisfy」会漂移：
     *  - pipeline 动作 `OutpostTradingReserveSession`（[outpostReserveSessionCallback]）；
     *  - BetterSliding 越界时由 [MaaBetterSlidingHost.satisfyReserveOutcome] 手工触发，
     *    等价于点亮结果节点 `OutpostTradingReserveAlreadySatisfied`（绕过框架会崩的 enabled 覆盖）。
     *
     * 当前物品未选中 / 没有有效保留规则时返回 false 并打错误日志（不静默跳过）。
     */
    private fun satisfyOutpostReserve(label: String): Boolean {
        val outcome = outpostReserveSession.markSatisfied()
        if (!outcome.ok) {
            Ln.e(
                "MaaRunner: OutpostTradingReserveSession [$label] satisfy 无有效保留规则 " +
                    "item='${outcome.name}' quantity=${outcome.quantity}",
            )
            return false
        }
        Ln.i(
            "MaaRunner: OutpostTradingReserveSession [$label] satisfy item='${outcome.name}' " +
                "quantity=${outcome.quantity} marked=${outcome.marked}",
        )
        return true
    }

    /**
     * 截当前画面并跑 `OutpostTradingStockBillsQuantity` OCR，返回调度券数量。
     * 动作回调拿不到框架帧，只能经控制器缓存取当前帧（与 [readOwnedQuantity] 同法）。
     */
    private fun readStockBillsQuantity(lib: MaaFrameworkLibrary, context: Pointer): Int? {
        val ctrl = controller ?: run {
            Ln.w("MaaRunner: OutpostTradingReserveSession controller 为空，无法读取调度券额度")
            return null
        }
        val capId = lib.MaaControllerPostScreencap(ctrl)
        if (capId > 0) lib.MaaControllerWait(ctrl, capId)
        val imgBuf = lib.MaaImageBufferCreate() ?: return null
        return try {
            if (lib.MaaControllerCachedImage(ctrl, imgBuf).toInt() == 0) {
                Ln.w("MaaRunner: OutpostTradingReserveSession 取当前画面失败")
                return null
            }
            if (lib.MaaImageBufferIsEmpty(imgBuf).toInt() != 0) {
                Ln.w("MaaRunner: OutpostTradingReserveSession 当前画面为空")
                return null
            }
            val recoId = lib.MaaContextRunRecognition(context, outpostStockBillsQuantityNode, "{}", imgBuf)
            if (recoId <= 0L) {
                Ln.w("MaaRunner: OutpostTradingReserveSession 调度券额度识别未命中")
                return null
            }
            val detailText = recognitionDetailJson(lib, context, recoId) ?: return null
            val text = RecoDetail.collectOcrTexts(detailText)
                .firstOrNull { it.any(Char::isDigit) }
                ?: return null
            OcrNum.parse(text).takeIf { it >= 0 }
        } catch (t: Throwable) {
            Ln.w("MaaRunner: OutpostTradingReserveSession 调度券额度解析失败: ${t.message}")
            null
        } finally {
            lib.MaaImageBufferDestroy(imgBuf)
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
            // 可观测性（不改选品行为）：把「价格没绑上」（price==null）的候选显式打出来。
            // 选品把 null 价当最贵排最后；若本页价格全没绑上，minWith 就退化成按 tier/位置挑，
            // 真机表现为「没买最便宜的、选了中间价」。这里说清是哪一步、哪些候选、为什么 null：
            // 本帧到底有没有可解析的价格文本——没有就是 OCR 没读到价，有则是几何没绑上。
            val unbound = candidates.filter { it.price == null }
            if (unbound.isNotEmpty()) {
                val priceTexts = items.mapNotNull { it.text.takeIf { t -> AutoStockpileSupport.parsePrice(t) != null } }
                Ln.w(
                    "MaaRunner: AutoStockpile.Recognition [$nodeName] stage=scan 价格未绑定 price==null " +
                        "region=$region 候选=${candidates.size} 未绑定=${unbound.size} " +
                        "names=${unbound.map { it.name }} 本帧可解析价格文本=$priceTexts 原因=" +
                        if (priceTexts.isEmpty()) {
                            "本帧无价格文本（OCR 未读到价）"
                        } else {
                            "有价格文本但未按 右上方向/距离阈值 绑到货组名上"
                        },
                )
            }
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

    // ────────────────────── 运行中调试探针：借第二个 tasker 并行 ──────────────────────
    //
    // 结论（读框架源码 v5.14.x）：
    //  - Tasker 内部只有一个 `AsyncRunner`（单线程串行队列，`Tasker.cpp:32`）；post_task /
    //    post_recognition / post_action 都进这同一队列（`Tasker.cpp:97-133, 289-315`）。所以运行中
    //    往主 tasker 再 post 只会排在当前任务之后，`MaaTaskerWait` 会一直阻塞到任务结束。
    //  - 第二个 tasker 是独立队列，可真正并行；但**不能给它绑同一个 controller**：框架在每个 task
    //    结束时会调用 `controller_->auto_release_pressed()`（`Tasker::run_task`，`Tasker.cpp:355-357`），
    //    会把运行中任务的按压（Android 原生 controller 用 `UseMouseDownAndUpInsteadOfClick`，点击/滑动
    //    都靠 auto_up 按压）提前放掉；而且它绕开 controller 的动作队列直接调 native，是明确的状态错乱面。
    //
    // 因此运行中的探针只绑 resource、**不绑 controller**，走 `MaaTaskerPostRecognition`：识别算法直接吃
    // 传入的图（内存图或主 controller 的缓存帧），完全不碰输入状态。真正需要 controller 的 `debugRunOnce`
    // 则改为「排到任务结束后、独占主 tasker 执行」。

    /** 运行中提交的 `run` 节点名，任务结束、独占主 tasker 时按序补跑。 */
    private val deferredDebugRuns = ConcurrentLinkedQueue<String>()

    /** 最近一次「排队执行」的调试节点结果，供 CLI `probe-result` 读回。 */
    @Volatile
    private var lastDebugProbeOutcome: DebugProbeOutcome? = null

    /**
     * 提交一次「只识别」探针并等待完成。返回 null 表示探针已跑完（结果写在各探针的 @Volatile 字段里），
     * 否则返回给用户看的原因。
     *
     * - 任务空闲：沿用主 tasker（`PostTask` + 临时探针节点），与既有行为一致。
     * - 任务运行中：新建一个只绑 resource 的临时 tasker，走 `MaaTaskerPostRecognition`；队列独立、
     *   返回快，且绝不触碰 controller。用完即销毁。
     *
     * [requireFrame] 为 true 时需要一张「当前帧」（OCR 探针）；取不到直接失败。为 false 时优先用缓存帧，
     * 没有就塞一张 1×1 占位图（override / yolo / coarse 探针自己造图，不读这张）。
     */
    private fun postProbeAndWait(
        lib: MaaFrameworkLibrary,
        probeNode: String,
        probeReco: String,
        probeParam: JsonObject?,
        requireFrame: Boolean,
    ): String? {
        if (!isRunning()) {
            val tasker = synchronized(lifecycleLock) { tasker }
                ?: return "tasker 未初始化（先跑一次任务）"
            if (lib.MaaTaskerInited(tasker).toInt() == 0) return "tasker 未就绪"
            // MaaTaskerPostTask 的 pipeline_override 是**对象数组**（按序合并），不是单个对象
            val node = buildJsonObject {
                put(
                    probeNode,
                    buildJsonObject {
                        put(
                            "recognition",
                            buildJsonObject {
                                put("type", "Custom")
                                put(
                                    "param",
                                    buildJsonObject {
                                        put("custom_recognition", probeReco)
                                        if (probeParam != null) put("custom_recognition_param", probeParam)
                                    },
                                )
                            },
                        )
                        // 只认不按：临时节点不触发任何动作
                        put("action", buildJsonObject { put("type", "DoNothing") })
                    },
                )
            }
            val id = lib.MaaTaskerPostTask(tasker, probeNode, JsonArray(listOf(node)).toString())
            if (id == INVALID_ID) return "PostTask 被拒绝"
            lib.MaaTaskerWait(tasker, id)
            return null
        }

        val res = synchronized(lifecycleLock) { resource } ?: return "resource 未初始化（先跑一次任务）"
        val image = currentFrameOrPlaceholder(lib, requireFrame) ?: return "取当前缓存帧失败（当前无帧）"
        val probe = lib.MaaTaskerCreate() ?: run {
            lib.MaaImageBufferDestroy(image)
            return "MaaTaskerCreate 失败"
        }
        return try {
            if (lib.MaaTaskerBindResource(probe, res).toInt() == 0 ||
                lib.MaaTaskerInited(probe).toInt() == 0
            ) {
                return "调试 tasker 绑定失败"
            }
            val recoParam = buildJsonObject {
                put("custom_recognition", probeReco)
                if (probeParam != null) put("custom_recognition_param", probeParam)
            }.toString()
            val id = lib.MaaTaskerPostRecognition(probe, "Custom", recoParam, image)
            if (id == INVALID_ID) return "PostRecognition 被拒绝"
            lib.MaaTaskerWait(probe, id)
            null
        } finally {
            lib.MaaTaskerDestroy(probe)
            lib.MaaImageBufferDestroy(image)
        }
    }

    /**
     * 取主 controller 的缓存帧。没有缓存帧时，[requireFrame] 为 true 返回 null，否则退化成一张 1×1 BGR
     * 占位图（`PostRecognition` 需要非空图；override / yolo / coarse 探针不读这张图）。
     */
    private fun currentFrameOrPlaceholder(lib: MaaFrameworkLibrary, requireFrame: Boolean): Pointer? {
        val ctrl = synchronized(lifecycleLock) { controller }
        if (ctrl != null) {
            val buf = lib.MaaImageBufferCreate()
            if (buf != null) {
                if (lib.MaaControllerCachedImage(ctrl, buf).toInt() != 0 &&
                    lib.MaaImageBufferIsEmpty(buf).toInt() == 0
                ) {
                    return buf
                }
                lib.MaaImageBufferDestroy(buf)
            }
        }
        if (requireFrame) return null
        val tiny = lib.MaaImageBufferCreate() ?: return null
        val one = Memory(3)
        return if (lib.MaaImageBufferSetRawData(tiny, one, 1, 1, MaaImageType.CV_8UC3).toInt() != 0) {
            tiny
        } else {
            lib.MaaImageBufferDestroy(tiny)
            null
        }
    }

    /** CLI `probe-result`：读回最近一次「排队执行」的调试节点结果。 */
    fun debugLastProbeResult(): List<String> {
        val outcome = lastDebugProbeOutcome
            ?: return listOf("还没有排队的调试探针结果（运行中执行 run 会排到任务结束后并写回）")
        return listOf("command: ${outcome.command}") + outcome.lines
    }

    /**
     * 对当前帧跑一次识别节点，返回 best 文本。
     *
     * MaaFramework 的 `MaaContextRunRecognition` 需要 task context，而 context 只在任务回调里存在；
     * 外部拿不到。于是注册一个探针识别 `DebugCliOcr`，借一个只含该探针的临时节点回调拿到 context，
     * 探针回调里再用现成的 [runRecognitionOnce] 跑目标节点。任务运行中见 [postProbeAndWait]。
     */
    fun debugOcrOnce(nodeName: String): DebugRecoOutcome {
        val lib = MaaFrameworkLoader.library ?: return DebugRecoOutcome(false, null, "MaaFramework 未加载")
        synchronized(debugOcrLock) {
            debugOcrResult = null
            val param = buildJsonObject { put("node", nodeName) }
            val error = postProbeAndWait(lib, DEBUG_OCR_NODE, DEBUG_OCR_RECO, param, requireFrame = true)
            if (error != null) return DebugRecoOutcome(false, null, error)
            return debugOcrResult ?: DebugRecoOutcome(false, null, "识别未返回结果")
        }
    }

    /** 跑一次节点 [nodeName]（截断其 next，避免顺链跑下去），返回结果描述。 */
    fun debugRunOnce(nodeName: String): String {
        val lib = MaaFrameworkLoader.library ?: return "MaaFramework 未加载"
        // 运行中必须独占 controller（节点可能带动作），不能并行：排到任务结束后再跑，立刻返回避免卡 CLI。
        if (isRunning()) {
            deferredDebugRuns.add(nodeName)
            Ln.i("MaaRunner: debug run '$nodeName' 已排队，任务结束后执行")
            return "已排队（任务结束后自动执行）；用 probe-result 读回结果"
        }
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

    /**
     * 任务结束、[isRunning] 已置 false 且主 tasker 空闲时，把运行中排队的调试节点按序补跑。
     *
     * 只在 [runPlan] 的收尾里调用：此时 worker 独占主 tasker，不会有任何任务并发，安全。
     */
    private fun drainDeferredDebugRuns() {
        val lib = MaaFrameworkLoader.library ?: return
        while (true) {
            val node = deferredDebugRuns.poll() ?: break
            val tasker = synchronized(lifecycleLock) { tasker } ?: break
            val patch = buildJsonObject {
                put(node, buildJsonObject { put("next", JsonArray(emptyList())) })
            }
            val id = lib.MaaTaskerPostTask(tasker, node, JsonArray(listOf(patch)).toString())
            val status = if (id == INVALID_ID) {
                "PostTask 被拒绝（节点不存在？）"
            } else {
                statusText(lib.MaaTaskerWait(tasker, id))
            }
            lastDebugProbeOutcome = DebugProbeOutcome("run $node", listOf("node: $node", "status: $status"))
            RunDiagnostics.note("debugcli", "排队的调试节点 run $node -> $status")
            Ln.i("MaaRunner: deferred debug run '$node' -> $status")
        }
    }

    /**
     * 路线 (b+) 前提验证（仅 debug CLI 调用）：用合成图走完
     * `MaaImageBufferSetRawData` → `MaaContextOverrideImage` → `MaaContextRunRecognition(TemplateMatch)`
     * → 校验返回框。不依赖游戏/补充包，也不需要 controller 的实时帧（整图与模板都在内存里合成）。
     *
     * 与 [debugOcrOnce] 同样的理由：外部拿不到 `MaaContext`，只能借一个临时 Custom 识别节点的
     * 回调拿 context，再在回调里跑真正的 TemplateMatch（见 [debugCliOverrideCallback]）。
     */
    fun debugOverrideProbe(): List<String> {
        // 硬门控：release 下不注册探针、也不执行任何链路（方法体直接短路）
        if (!BuildConfig.DEBUG) return listOf("error: overrideprobe 仅在 debug 构建可用")
        val lib = MaaFrameworkLoader.library ?: return listOf("error: MaaFramework 未加载")
        synchronized(debugOverrideLock) {
            debugOverrideResult = null
            val error = postProbeAndWait(lib, DEBUG_OVERRIDE_NODE, DEBUG_OVERRIDE_RECO, null, requireFrame = false)
            if (error != null) return listOf("error: $error")
            return debugOverrideResult ?: listOf("error: 探针未返回结果")
        }
    }

    private val debugOverrideLock = Any()

    @Volatile
    private var debugOverrideResult: List<String>? = null

    /**
     * 前提验证探针识别：在回调里合成图 → SetRawData → OverrideImage → 跑 TemplateMatch。
     *
     * 这是真机唯一能证明「覆盖进运行时的模板能被随后的识别读到」的地方，链路：
     *  1. `MaaImageBufferSetRawData` 把合成整图与 patch 写进各自 buffer（BGR/CV_8UC3）；
     *  2. `MaaContextOverrideImage(ctx, templateName, patchBuf)`；
     *  3. `MaaContextRunRecognition(ctx, probeNode, overrideJson, fullBuf)`；
     *  4. 读回 reco box，与已知裁剪点比对（纯逻辑 [MapLocatorProbeSupport.isBoxNearExpected]）。
     *
     * 任何一步失败都如实写进 [debugOverrideResult] 与 RunDiagnostics，绝不伪造成功。
     */
    private val debugCliOverrideCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, _, _, _, _, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) {
            debugOverrideResult = listOf("error: 探针回调缺少 context")
            return@MaaCustomRecognitionCallback 0
        }
        val plan = MapLocatorProbeSupport.syntheticPlan()
        if (plan == null) {
            debugOverrideResult = listOf("error: 合成探针图失败（裁剪区域非法）")
            return@MaaCustomRecognitionCallback 0
        }
        var fullBuf: Pointer? = null
        var patchBuf: Pointer? = null
        try {
            val fullMem = Memory(plan.fullBgr.size.toLong())
            fullMem.write(0, plan.fullBgr, 0, plan.fullBgr.size)
            fullBuf = lib.MaaImageBufferCreate()
            if (fullBuf == null ||
                lib.MaaImageBufferSetRawData(
                    fullBuf, fullMem, plan.fullWidth, plan.fullHeight, MaaImageType.CV_8UC3,
                ).toInt() == 0
            ) {
                debugOverrideResult = listOf("error: MaaImageBufferSetRawData(full) 失败")
                return@MaaCustomRecognitionCallback 0
            }

            val patchMem = Memory(plan.patchBgr.size.toLong())
            patchMem.write(0, plan.patchBgr, 0, plan.patchBgr.size)
            patchBuf = lib.MaaImageBufferCreate()
            if (patchBuf == null ||
                lib.MaaImageBufferSetRawData(
                    patchBuf, patchMem, plan.patchW, plan.patchH, MaaImageType.CV_8UC3,
                ).toInt() == 0
            ) {
                debugOverrideResult = listOf("error: MaaImageBufferSetRawData(patch) 失败")
                return@MaaCustomRecognitionCallback 0
            }

            val overrideOk = lib.MaaContextOverrideImage(context, plan.templateName, patchBuf).toInt() != 0
            val nodeOverride = MapLocatorProbeSupport.buildTemplateMatchOverride(plan.probeNode, plan.templateName)
            val res = runRecognitionOnce(lib, context, fullBuf, plan.probeNode, nodeOverride)
            val actual = res?.box
            val hit = res?.hit == true
            val near = hit && MapLocatorProbeSupport.isBoxNearExpected(actual, plan.expectedBox)
            val summary = MapLocatorProbeSupport.describe(plan.expectedBox, actual, MapLocatorProbeSupport.DEFAULT_TOLERANCE)

            RunDiagnostics.note(
                "maplocator",
                "override 探针｜${if (near) "通过" else "未通过"} template=${plan.templateName} " +
                    "overrideOk=$overrideOk hit=$hit $summary",
                mapOf(
                    "stage" to "override_probe",
                    "result" to if (near) "pass" else "fail",
                    "override_ok" to overrideOk,
                    "hit" to hit,
                    "expected" to plan.expectedBox.toList(),
                    "actual" to actual?.toList(),
                    "template" to plan.templateName,
                ),
            )
            Ln.i("MaaRunner: override 探针 overrideOk=$overrideOk hit=$hit $summary")
            debugOverrideResult = listOf(
                "override: ${if (overrideOk) "ok" else "FAILED"}",
                "hit: $hit",
                "expected: [${plan.expectedBox.joinToString(",")}]",
                "actual: ${actual?.let { "[" + it.joinToString(",") + "]" } ?: "null"}",
                "near: $near",
                "result: ${if (near) "PASS" else "FAIL"}",
            )
            if (near && outBox != null && actual != null && actual.size >= 4) {
                lib.MaaRectSet(outBox, actual[0], actual[1], actual[2], actual[3])
            }
            if (near) 1 else 0
        } catch (t: Throwable) {
            debugOverrideResult = listOf("error: ${t.javaClass.simpleName}: ${t.message}")
            Ln.e("MaaRunner: override 探针异常", t)
            0
        } finally {
            if (fullBuf != null) lib.MaaImageBufferDestroy(fullBuf)
            if (patchBuf != null) lib.MaaImageBufferDestroy(patchBuf)
        }
    }

    // ────────────────────── YOLO 分区分类探针（yoloprobe，仅 debug） ──────────────────────

    private val debugYoloLock = Any()

    @Volatile
    private var debugYoloResult: List<String>? = null

    /** 预处理后的 128×128 BGR 图；回调里写进 MaaImageBuffer 再喂给 NeuralNetworkClassify。 */
    @Volatile
    private var debugYoloPreprocessed: ByteArray? = null

    /** sidecar `cls.json` 的解析结果（类名表 + region_mapping）。 */
    @Volatile
    private var debugYoloConfig: YoloConfig = YoloConfig()

    /** sidecar `tile_mapping.json` 的解析结果。 */
    @Volatile
    private var debugYoloMapping: YoloMapping = YoloMapping()

    /**
     * YOLO 分区分类探针：读 [imagePath] 的小地图图 → [YoloPreprocess] 预处理 →
     * `NeuralNetworkClassify(cls.onnx)` → 输出 cls_index / 类名 / zone_id / tile ROI。
     *
     * 与 [debugOverrideProbe] 同样的理由：外部拿不到 `MaaContext`，只能借一个临时 Custom
     * 识别节点的回调拿到 context，再在回调里跑真正的 `NeuralNetworkClassify`
     * （见 [debugCliYoloCallback]）。
     */
    fun debugYoloProbe(imagePath: String): List<String> {
        if (!BuildConfig.DEBUG) return listOf("error: yoloprobe 仅在 debug 构建可用")
        val lib = MaaFrameworkLoader.library ?: return listOf("error: MaaFramework 未加载")
        val res = synchronized(lifecycleLock) { resource }
            ?: return listOf("error: resource 未初始化（先跑一次任务）")
        val root = projectRoot?.let { File(it).parentFile }
            ?: return listOf("error: PI 根未就绪")

        val supplementDir = File(root, "supplements/map-locate/map")
        val config = runCatching {
            YoloConfigParser.parseConfig(File(supplementDir, "cls.json").takeIf { it.isFile }?.readText())
        }.getOrDefault(YoloConfig())
        val tileRegions = runCatching {
            YoloConfigParser.parseTileMapping(File(supplementDir, "tile_mapping.json").takeIf { it.isFile }?.readText())
        }.getOrDefault(emptyMap())
        val mapping = YoloMapping(config.regionMapping, tileRegions)

        val preprocessed = runCatching { preprocessYoloImageFile(imagePath) }.getOrElse {
            return listOf("error: 预处理异常：${it.javaClass.simpleName}: ${it.message}")
        } ?: return listOf("error: 图片解码失败或尺寸非法：$imagePath")

        ensureYoloClassifyBundle(lib, res, root, supplementDir, allowResourceWrite = !isRunning())?.let {
            return listOf("error: $it")
        }

        synchronized(debugYoloLock) {
            debugYoloResult = null
            debugYoloPreprocessed = preprocessed
            debugYoloConfig = config
            debugYoloMapping = mapping

            val error = postProbeAndWait(lib, DEBUG_YOLO_NODE, DEBUG_YOLO_RECO, null, requireFrame = false)
            if (error != null) return listOf("error: $error")
            return debugYoloResult ?: listOf("error: 探针未返回结果")
        }
    }

    /** 读图（Android 解码）→ ARGB → 纯逻辑预处理成 128×128 BGR。 */
    private fun preprocessYoloImageFile(path: String): ByteArray? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            YoloPreprocess.preprocessArgb(pixels, w, h)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 已把 `debug/yolo-classify` bundle 追加进哪个 resource；重复调用直接短路。
     *
     * 关键在于**避免任务运行中再 `MaaResourcePostBundle`**：框架的 Pipeline/ONNX res manager
     * 没有锁，边跑边改 resource 会与运行中任务的数据读取竞争。于是改由 [prepare] 在空闲时预加载，
     * 探针侧只读。
     */
    @Volatile
    private var ensuredYoloBundleFor: Pointer? = null

    /**
     * 让框架能读到 `cls.onnx`。补充包把它放在 `<root>/supplements/map-locate/map/cls.onnx`，
     * 而框架只从 bundle 的 `model/classify/` 下按名懒加载（`ONNXResMgr::classifier`）。
     *
     * 于是把模型硬链（同盘，省 23 MiB 拷贝；失败退回复制）到一个最小 bundle
     * `<root>/debug/yolo-classify/model/classify/cls.onnx`，再 `MaaResourcePostBundle` 追加到
     * **已加载的 resource**——`lazy_load_classifier` 只登记 root，真正加载在首次识别，
     * 所以追加即可生效，无需重建 resource。
     *
     * @param allowResourceWrite 是否允许真的去 `MaaResourcePostBundle` 改 resource。任务运行中必须为
     *   false：框架 res manager 无锁，边跑边改会与运行中任务竞争。空闲（含 [prepare] 预加载）为 true。
     * @return null 表示就绪；否则返回给用户看的原因。
     */
    private fun ensureYoloClassifyBundle(
        lib: MaaFrameworkLibrary,
        res: Pointer,
        root: File,
        supplementDir: File,
        allowResourceWrite: Boolean,
    ): String? {
        if (ensuredYoloBundleFor == res) return null
        if (!allowResourceWrite) {
            return "任务运行中且分类模型尚未加载：请先空闲时跑一次 yoloprobe/coarselocate（或重启任务）"
        }
        val src = File(supplementDir, "cls.onnx")
        if (!src.isFile) {
            return "缺少分类模型：请先在设置里安装补充包 map-locate（${src.absolutePath} 不存在）"
        }
        val bundleDir = File(root, "debug/yolo-classify")
        val modelDir = File(bundleDir, "model/classify")
        modelDir.mkdirs()
        val target = File(modelDir, "cls.onnx")
        if (!target.isFile || target.length() != src.length()) {
            val linked = runCatching {
                target.delete()
                Files.createLink(target.toPath(), src.toPath())
            }.isSuccess
            if (!linked) {
                runCatching { src.copyTo(target, overwrite = true) }
                    .getOrElse { return "准备 cls.onnx 失败：${it.message}" }
            }
        }
        val id = lib.MaaResourcePostBundle(res, bundleDir.absolutePath)
        if (id == INVALID_ID) return "MaaResourcePostBundle 被拒绝"
        if (lib.MaaResourceWait(res, id) != MaaStatus.SUCCEEDED) return "分类模型 bundle 加载失败"
        ensuredYoloBundleFor = res
        return null
    }

    /**
     * 空闲时预加载 YOLO 分类模型（仅 debug）：补充包存在才做，失败只记日志。
     *
     * 这样 `yoloprobe` / `coarselocate` 在任务运行中也不会去改 resource。
     */
    private fun preloadYoloClassifyBundle(lib: MaaFrameworkLibrary) {
        val res = synchronized(lifecycleLock) { resource } ?: return
        val base = projectRoot?.let { File(it).parentFile } ?: return
        val supplementDir = File(base, "supplements/map-locate/map")
        if (!File(supplementDir, "cls.onnx").isFile) return
        ensureYoloClassifyBundle(lib, res, base, supplementDir, allowResourceWrite = true)?.let {
            Ln.w("MaaRunner: 预加载 cls.onnx 跳过：$it")
        }
    }

    private val debugCliYoloCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, _, _, _, _, _, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) {
            debugYoloResult = listOf("error: 探针回调缺少 context")
            return@MaaCustomRecognitionCallback 0
        }
        val bgr = debugYoloPreprocessed
        if (bgr == null) {
            debugYoloResult = listOf("error: 探针缺少预处理图")
            return@MaaCustomRecognitionCallback 0
        }
        var buf: Pointer? = null
        try {
            val mem = Memory(bgr.size.toLong())
            mem.write(0, bgr, 0, bgr.size)
            buf = lib.MaaImageBufferCreate()
            if (buf == null ||
                lib.MaaImageBufferSetRawData(
                    buf, mem, YoloPreprocess.OUTPUT_SIZE, YoloPreprocess.OUTPUT_SIZE, MaaImageType.CV_8UC3,
                ).toInt() == 0
            ) {
                debugYoloResult = listOf("error: MaaImageBufferSetRawData(128x128) 失败")
                return@MaaCustomRecognitionCallback 0
            }

            val nodeOverride = YoloClassifySupport.buildClassifyOverride(
                nodeName = YoloClassifySupport.DEFAULT_PROBE_NODE,
                model = YoloClassifySupport.DEFAULT_MODEL,
                labels = debugYoloConfig.classes,
            )
            val res = runRecognitionOnce(lib, context, buf, YoloClassifySupport.DEFAULT_PROBE_NODE, nodeOverride)
            if (res == null) {
                debugYoloResult = listOf("error: NeuralNetworkClassify 调用失败（cls.onnx 未加载？）")
                return@MaaCustomRecognitionCallback 0
            }
            val parsed = YoloClassifySupport.parseClassifyDetail(res.detailJson)
            if (parsed == null) {
                val detail = res.detailJson?.take(400) ?: "null"
                debugYoloResult = listOf("error: 无法解析识别详情：$detail")
                return@MaaCustomRecognitionCallback 0
            }
            val outcome = YoloClassifySupport.resolveClassify(parsed, debugYoloConfig, debugYoloMapping)
            debugYoloResult = listOf("hit: ${res.hit}") +
                YoloClassifySupport.describeOutcome(parsed.clsIndex, parsed.label, outcome)

            RunDiagnostics.note(
                "maplocator",
                "yoloprobe｜index=${parsed.clsIndex} class=${outcome.rawClass} zone=${outcome.zoneId} valid=${outcome.valid}",
                mapOf(
                    "stage" to "yolo_probe",
                    "cls_index" to parsed.clsIndex,
                    "class" to outcome.rawClass,
                    "zone_id" to outcome.zoneId,
                    "valid" to outcome.valid,
                    "is_none" to outcome.isNone,
                    "has_roi" to outcome.hasRoi,
                ),
            )
            Ln.i("MaaRunner: yoloprobe index=${parsed.clsIndex} class=${outcome.rawClass} zone=${outcome.zoneId}")
            if (res.hit) 1 else 0
        } catch (t: Throwable) {
            debugYoloResult = listOf("error: ${t.javaClass.simpleName}: ${t.message}")
            Ln.e("MaaRunner: yoloprobe 异常", t)
            0
        } finally {
            if (buf != null) lib.MaaImageBufferDestroy(buf)
        }
    }

    // ───────────────────── 端到端粗定位探针（coarselocate，仅 debug）─────────────────────
    //
    // 上游 `startGlobalSearch`（MapLocator.cpp:1268-1332）的等价物第一步：
    // 全帧 → TryExtractMinimap → YOLO 分类得 zone+tile → buildSearchConstraint 算 ROI →
    // 在地图资产图上以「小地图裁剪」为运行时模板跑 TemplateMatch → 读回框。
    // 不做追踪状态机/亚像素精修/朝向（后续分片）。

    private val debugCoarseLock = Any()

    @Volatile
    private var debugCoarseResult: List<String>? = null

    /**
     * 本帧粗定位得到的地图观测（绝对坐标 + 分数），供 `tracklocate` 喂追踪状态机。
     * 回调里在算完热图/灰度结果后写入；[debugCoarseLocate] 入口先清空。
     */
    @Volatile
    private var debugCoarseObservation: MapPosition? = null

    /**
     * `tracklocate` 的运行期追踪状态机：跨命令调用保留状态（单帧累加）。
     * 纯逻辑 [MapLocatorTracking]，不依赖 Android。
     */
    private val debugTrackingState = MapLocatorTracking()

    /** 128×128 BGR（YOLO 输入）。 */
    @Volatile
    private var debugCoarseYoloBgr: ByteArray? = null

    /** 小地图 ROI 的 BGR 原图（TemplateMatch 的运行时模板）。 */
    @Volatile
    private var debugCoarseTemplateBgr: ByteArray? = null
    @Volatile
    private var debugCoarseTemplateW = 0
    @Volatile
    private var debugCoarseTemplateH = 0

    /** 可选 expected zone selector（上游 `options.expected_zone_id`）。 */
    @Volatile
    private var debugCoarseZoneSelector: String = ""

    @Volatile
    private var debugCoarseConfig: YoloConfig = YoloConfig()

    @Volatile
    private var debugCoarseMapping: YoloMapping = YoloMapping()

    /** zoneId → 地图资产 PNG。 */
    @Volatile
    private var debugCoarseMapIndex: Map<String, File> = emptyMap()

    /**
     * PathHeatmap 并行路的**搜索热图两槽缓存**（对齐上游 `getGlobalSearchFeature`
     * `MapLocator.cpp:926-948` 的 `cacheSlotIndex` 语义）：key = `zoneId+kind+roi+generation`。
     */
    private val heatmapSearchCache = MapLocatorHeatmapPipeline.SearchFeatureCache()

    private class ArgbImage(val pixels: IntArray, val width: Int, val height: Int)

    private class BgrImage(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * 第一次端到端粗定位（仅 debug CLI 调用）。真机验证方式：
     * `screenshot` 存一张全帧 → `coarselocate <path> [zone]`。
     *
     * 与 [debugYoloProbe] 同样的理由：外部拿不到 `MaaContext`，借一个临时 Custom 识别节点的
     * 回调拿到 context，再在回调里跑 YOLO 分类 + `OverrideImage` + `TemplateMatch`
     * （见 [debugCliCoarseCallback]）。
     */
    fun debugCoarseLocate(imagePath: String, zone: String?): List<String> {
        if (!BuildConfig.DEBUG) return listOf("error: coarselocate 仅在 debug 构建可用")
        val lib = MaaFrameworkLoader.library ?: return listOf("error: MaaFramework 未加载")
        val res = synchronized(lifecycleLock) { resource }
            ?: return listOf("error: resource 未初始化（先跑一次任务）")
        val root = projectRoot ?: return listOf("error: PI 根未就绪")

        val mapRoot = File(root, "resource/image/MapLocator")
        if (!mapRoot.isDirectory) {
            return listOf("error: 地图资产目录不存在：${mapRoot.absolutePath}")
        }
        val mapIndex = buildMapZoneIndex(mapRoot)
        if (mapIndex.isEmpty()) {
            return listOf("error: 地图资产目录为空：${mapRoot.absolutePath}")
        }

        val frame = decodeArgb(imagePath) ?: return listOf("error: 截图解码失败：$imagePath")
        // 末影控制器是 Android 原生（非 adb/playcover），走默认 ROI、不缩放。
        val plan = MapLocatorCoarsePure.minimapExtractPlan(frame.width, frame.height, useAdbRoi = false)
            ?: return listOf("error: 截帧 ${frame.width}x${frame.height} 无法裁出小地图 ROI（越界？）")
        val minimapArgb = MapLocatorCoarsePure.extractMinimapArgb(
            frame.pixels, frame.width, frame.height, useAdbRoi = false,
        ) ?: return listOf("error: 小地图裁剪失败")
        val yoloBgr = YoloPreprocess.preprocessArgb(minimapArgb, plan.roi.width, plan.roi.height)
            ?: return listOf("error: YOLO 预处理失败")
        val templateBgr = YoloPreprocess.argbToBgr(minimapArgb)

        val base = File(root).parentFile
            ?: return listOf("error: PI 根没有父目录，无法定位补充包目录")
        val supplementDir = File(base, "supplements/map-locate/map")
        val config = runCatching {
            YoloConfigParser.parseConfig(File(supplementDir, "cls.json").takeIf { it.isFile }?.readText())
        }.getOrDefault(YoloConfig())
        val tileRegions = runCatching {
            YoloConfigParser.parseTileMapping(
                File(supplementDir, "tile_mapping.json").takeIf { it.isFile }?.readText(),
            )
        }.getOrDefault(emptyMap())
        val mapping = YoloMapping(config.regionMapping, tileRegions)

        ensureYoloClassifyBundle(lib, res, base, supplementDir, allowResourceWrite = !isRunning())?.let {
            return listOf("error: $it")
        }

        synchronized(debugCoarseLock) {
            debugCoarseResult = null
            debugCoarseObservation = null
            debugCoarseYoloBgr = yoloBgr
            debugCoarseTemplateBgr = templateBgr
            debugCoarseTemplateW = plan.roi.width
            debugCoarseTemplateH = plan.roi.height
            debugCoarseZoneSelector = zone.orEmpty().trim()
            debugCoarseConfig = config
            debugCoarseMapping = mapping
            debugCoarseMapIndex = mapIndex

            val error = postProbeAndWait(lib, DEBUG_COARSE_NODE, DEBUG_COARSE_RECO, null, requireFrame = false)
            if (error != null) return listOf("error: $error")
            return debugCoarseResult ?: listOf("error: 探针未返回结果")
        }
    }

    /**
     * 追踪状态机单帧累加（仅 debug CLI `tracklocate` 调用）。
     *
     * 复用 [debugCoarseLocate] 的完整地图观测（灰度路 + 热图路均不破坏），把本帧位置/分数
     * 与单调时间戳喂进运行期保留的 [debugTrackingState]，打印 accept / reject / hold /
     * relocate 裁决与累计状态（zone / 丢失计数 / 冷启动帧数）。
     *
     * [imagePath] 为 null 表示 `tracklocate reset`：清空状态机。
     */
    fun debugTrackLocate(imagePath: String?, zone: String?): List<String> {
        if (!BuildConfig.DEBUG) return listOf("error: tracklocate 仅在 debug 构建可用")
        if (imagePath == null) {
            debugTrackingState.reset()
            return listOf("track: state reset") + debugTrackingState.describe()
        }

        val coarseLines = debugCoarseLocate(imagePath, zone)
        val observation = debugCoarseObservation
            ?: return coarseLines + listOf("track: error: 本帧没有可用观测（粗定位未产出位置）")

        val nowSeconds = System.nanoTime() / 1_000_000_000.0
        val decision = debugTrackingState.feed(observation, nowSeconds)
        val posText = decision.position
            ?.let { String.format(java.util.Locale.US, "(%.2f,%.2f,score=%.3f)", it.x, it.y, it.score) }
            ?: "-"
        val trackLines = listOf(
            "track: action=${decision.action} reason=${decision.reason} pos=$posText",
        ) + debugTrackingState.describe()

        Ln.i(
            "MaaRunner: tracklocate zone=${observation.zoneId} score=${observation.score} " +
                "action=${decision.action} lost=${decision.lostCount}",
        )
        return coarseLines + trackLines
    }

    // ───────────────────── MapFind 调试探针（mapfind，仅 debug）─────────────────────
    //
    // 与 [mapFindRun] 同一条求解链（capture → SolveViewport → toScreen → ConfirmSpot），
    // 但**只读**：不触发 ZoomOut、不拖动、不交回 next。用途：真机在「全屏大地图」上单点
    // 验证 WorldMapFindPure / WorldMapSolverPure / WorldMapImagePure 的移植是否成立
    // （输出 viewport / 目标屏幕框 / 是否命中 / 耗时）。

    private val debugMapFindLock = Any()

    @Volatile
    private var debugMapFindResult: List<String>? = null

    @Volatile
    private var debugMapFindZone: String? = null

    @Volatile
    private var debugMapFindAt: DoubleArray? = null

    @Volatile
    private var debugMapFindIcon: String? = null

    /**
     * 在**当前全屏大地图**上做一次 MapFind 求解探针（仅 debug CLI `mapfind` 调用）。
     *
     * 借用临时 Custom 识别节点的回调拿到 `MaaContext`（外部拿不到），在回调里跑
     * `SolveViewport`（多尺度 TemplateMatch）→ 目标投屏 → `ConfirmSpot`（给了 icon 时）。
     * 只读，任何一步解不出来都如实报告，不伪造坐标。
     */
    fun debugMapFind(zone: String, atX: Double, atY: Double, icon: String?): List<String> {
        if (!BuildConfig.DEBUG) return listOf("error: mapfind 仅在 debug 构建可用")
        if (zone.isBlank()) return listOf("error: mapfind 的 zone 不能为空")
        val lib = MaaFrameworkLoader.library ?: return listOf("error: MaaFramework 未加载")
        val res = synchronized(lifecycleLock) { resource }
            ?: return listOf("error: resource 未初始化（先跑一次任务）")
        if (!ensureWorldMapAssets(res)) {
            return listOf("error: 世界地图资产未加载（resource/image/SceneManager/MapIcons.json）")
        }
        loadWorldMapBase(zone)
            ?: return listOf("error: zone=$zone 找不到 Base 底图（resource/image/MapLocator/$zone/*base*.png）")
        val iconName = icon?.takeIf { it.isNotBlank() }
        if (iconName != null && worldMapIconTable[iconName] == null) {
            val sample = worldMapIconTable.keys.take(8).joinToString(", ")
            return listOf("error: 图标表无此项 $iconName（例如：$sample）")
        }

        val startedAt = System.nanoTime()
        synchronized(debugMapFindLock) {
            debugMapFindResult = null
            debugMapFindZone = zone
            debugMapFindAt = doubleArrayOf(atX, atY)
            debugMapFindIcon = iconName
            val error = postProbeAndWait(lib, DEBUG_MAPFIND_NODE, DEBUG_MAPFIND_RECO, null, requireFrame = false)
            if (error != null) return listOf("error: $error")
            val result = debugMapFindResult ?: return listOf("error: 探针未返回结果")
            return result + "cost: ${(System.nanoTime() - startedAt) / 1_000_000}ms"
        }
    }

    /** 取探针可用的整帧 ARGB：优先框架传入的 [image]，为空再主动截一帧。 */
    private fun probeScreenArgb(lib: MaaFrameworkLibrary, image: Pointer?): ArgbImage? {
        if (image != null && lib.MaaImageBufferIsEmpty(image).toInt() == 0) {
            decodeArgbFromBuffer(lib, image)?.let { if (it.width > 0 && it.height > 0) return it }
        }
        return captureArgbFrame(lib)
    }

    /**
     * `mapfind` 探针识别：在回调里对当前帧解 viewport、投屏、按图标确认。
     * 只读，绝不拖动或改 pipeline；解不出/认不出如实写进 [debugMapFindResult]。
     */
    private val debugCliMapFindCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback {
            context, _, _, _, _, image, _, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) {
            debugMapFindResult = listOf("error: 探针回调缺少 context")
            return@MaaCustomRecognitionCallback 0
        }
        val zone = debugMapFindZone
        val at = debugMapFindAt
        val base = if (zone != null) loadWorldMapBase(zone) else null
        if (zone == null || at == null || base == null) {
            debugMapFindResult = listOf("error: 探针缺少 zone/at/底图")
            return@MaaCustomRecognitionCallback 0
        }
        try {
            val frame = probeScreenArgb(lib, image) ?: run {
                debugMapFindResult = listOf("error: 取当前帧失败（先让游戏停在全屏大地图页）")
                return@MaaCustomRecognitionCallback 0
            }
            val bgr = YoloPreprocess.argbToBgr(frame.pixels)
            val gray = MapLocatorRefinePure.bgrToGray(bgr, frame.width, frame.height) ?: run {
                debugMapFindResult = listOf("error: 整帧转灰度失败")
                return@MaaCustomRecognitionCallback 0
            }
            val screen = WorldMapGrayImage(gray, frame.width, frame.height)
            val viewport = solveWorldMapViewport(lib, context, screen, base, WorldMapViewportConfig())
            val iconName = debugMapFindIcon
            val spec = iconName?.let { worldMapIconTable[it] }
            var hit: WorldMapSpotHit? = null
            if (viewport != null && spec != null) {
                val expected = viewport.toScreen(at[0], at[1])
                hit = confirmWorldMapSpot(lib, context, spec, screen, expected[0], expected[1], viewport.scale)
            }
            val outcome = WorldMapFindPure.ProbeOutcome(
                zone = zone,
                baseWidth = base.fullWidth,
                baseHeight = base.fullHeight,
                atX = at[0],
                atY = at[1],
                viewport = viewport,
                icon = iconName,
                hit = hit,
                wantUnlocked = true,
            )
            debugMapFindResult = listOf("frame: ${frame.width}x${frame.height}") +
                WorldMapFindPure.describeProbe(outcome)
            if (hit != null && outBox != null) {
                val box = WorldMapTypes.spotBox(hit)
                lib.MaaRectSet(outBox, box.x, box.y, box.width, box.height)
            }
            RunDiagnostics.note(
                "worldmap",
                "mapfind 探针｜zone=$zone viewport=${viewport?.scale ?: "FAILED"} " +
                    "icon=${iconName ?: "-"} hit=${hit != null}",
                mapOf(
                    "stage" to "mapfind_probe",
                    "zone" to zone,
                    "base_size" to listOf(base.fullWidth, base.fullHeight),
                    "at" to listOf(at[0], at[1]),
                    "viewport_scale" to viewport?.scale,
                    "viewport_score" to viewport?.score,
                    "viewport_delta" to viewport?.delta,
                    "icon" to iconName,
                    "hit" to (hit != null),
                    "hit_score" to hit?.score,
                ),
            )
            Ln.i(
                "MaaRunner: mapfind 探针 zone=$zone viewport=${viewport?.scale ?: "FAILED"} " +
                    "icon=${iconName ?: "-"} hit=${hit != null}",
            )
            if (hit != null) 1 else 0
        } catch (t: Throwable) {
            debugMapFindResult = listOf("error: ${t.javaClass.simpleName}: ${t.message}")
            Ln.e("MaaRunner: mapfind 探针异常", t)
            0
        }
    }

    // ───────────────────── MapLocateAssertLocation 真实实现 ─────────────────────
    //
    // 上游 `MapLocateAction.cpp:404-471`：`resetTrackingState` → 最多 60 帧、每帧 250ms
    // 轮询 `locate(force_global_search=true, expected_zone_id=zone_id)` → 定位成功且落在
    // `target` 矩形内即 matched，命中时把 `target` 当 box 交回框架。
    //
    // 这里复用 debug 探针已打通的同一条原语链：小地图裁剪（MapLocatorCoarsePure）→
    // YOLO 分区（NeuralNetworkClassify）→ 约束 ROI → 灰度 TemplateMatch + PathHeatmap
    // 精排（runHeatmapCoarsePath，内部走 MatchValidation 裁决）→ 追踪状态机
    // （MapLocatorTracking.feed）稳定化 → 与 target 比较。裁决纯逻辑在 [MapLocateAssertPure]。

    // 回退开关 / 临时节点与模板名见 companion 的 MAP_LOCATE_ASSERT_*。

    /** 真实实现惰性加载的地图资产/YOLO sidecar，按 resource 生命周期缓存。 */
    private class MapLocateAssets(
        val mapIndex: Map<String, File>,
        val config: YoloConfig,
        val mapping: YoloMapping,
        val supplementDir: File,
    )

    private val mapLocateAssetsLock = Any()

    @Volatile
    private var mapLocateAssetsFor: Pointer? = null

    @Volatile
    private var mapLocateAssets: MapLocateAssets? = null

    /** 真实实现的搜索热图两槽缓存（与 debug 探针分开，避免并发串味）。 */
    private val mapLocateAssertHeatmapCache = MapLocatorHeatmapPipeline.SearchFeatureCache()

    /** 真实实现的追踪状态机；单次 assert 操作开始时 reset（对齐上游）。 */
    private val mapLocateAssertTracking = MapLocatorTracking()

    /** 单次 assert 操作独占状态机与缓存。 */
    private val mapLocateAssertLock = Any()

    /**
     * 惰性加载真实实现所需的资源（地图资产索引 + YOLO sidecar + cls.onnx bundle）。
     *
     * 与 [debugCoarseLocate] 的加载逻辑同源，但按 resource 指针缓存，避免每次识别都重扫盘。
     * 任务运行中不允许 `MaaResourcePostBundle`（框架 res manager 无锁），故 cls.onnx 必须由
     * [prepare] 的空闲预加载登记好；未就绪时返回 null，让识别如实失败。
     */
    private fun ensureMapLocateAssets(lib: MaaFrameworkLibrary, res: Pointer): MapLocateAssets? {
        mapLocateAssets?.let { if (mapLocateAssetsFor == res) return it }
        synchronized(mapLocateAssetsLock) {
            mapLocateAssets?.let { if (mapLocateAssetsFor == res) return it }
            val root = projectRoot ?: return null
            val mapRoot = File(root, "resource/image/MapLocator")
            if (!mapRoot.isDirectory) return null
            val mapIndex = buildMapZoneIndex(mapRoot)
            if (mapIndex.isEmpty()) return null
            val base = File(root).parentFile ?: return null
            val supplementDir = File(base, "supplements/map-locate/map")
            val config = runCatching {
                YoloConfigParser.parseConfig(File(supplementDir, "cls.json").takeIf { it.isFile }?.readText())
            }.getOrDefault(YoloConfig())
            val tileRegions = runCatching {
                YoloConfigParser.parseTileMapping(
                    File(supplementDir, "tile_mapping.json").takeIf { it.isFile }?.readText(),
                )
            }.getOrDefault(emptyMap())
            val mapping = YoloMapping(config.regionMapping, tileRegions)
            ensureYoloClassifyBundle(lib, res, base, supplementDir, allowResourceWrite = !isRunning())?.let {
                Ln.w("MaaRunner: MapLocateAssertLocation 资源未就绪：$it")
                return null
            }
            val assets = MapLocateAssets(mapIndex, config, mapping, supplementDir)
            mapLocateAssets = assets
            mapLocateAssetsFor = res
            return assets
        }
    }

    /** 单帧定位观测：与上游 `LocateResult` 同形（status + 可选位置 + 调试串）。 */
    private data class MinimapLocateFrame(
        val status: LocateStatus,
        val observation: MapPosition?,
        val debugMessage: String,
    )

    /**
     * 对一整帧做一次「小地图 → YOLO → 粗定位 + 热图精排」，返回本帧的全局观测。
     *
     * 只做**单帧**；追踪状态机与多帧轮询在调用方（[mapLocateAssertLocationCallback]）。
     * 任一环节失败都如实返回非 Success，不伪造位置。
     */
    private fun locateMinimapFrame(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        frame: ArgbImage,
        options: LocateOptions,
        assets: MapLocateAssets,
        heatmapCache: MapLocatorHeatmapPipeline.SearchFeatureCache,
    ): MinimapLocateFrame {
        // 1) 小地图裁剪：末影控制器是 Android 原生（非 adb/playcover），走默认 ROI、不缩放。
        val plan = MapLocatorCoarsePure.minimapExtractPlan(frame.width, frame.height, useAdbRoi = false)
            ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "小地图 ROI 越界")
        val minimapArgb = MapLocatorCoarsePure.extractMinimapArgb(
            frame.pixels, frame.width, frame.height, useAdbRoi = false,
        ) ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "小地图裁剪失败")
        val yoloBgr = YoloPreprocess.preprocessArgb(minimapArgb, plan.roi.width, plan.roi.height)
            ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "YOLO 预处理失败")
        val templateBgr = YoloPreprocess.argbToBgr(minimapArgb)

        var yoloBuf: Pointer? = null
        var mapBuf: Pointer? = null
        var templateBuf: Pointer? = null
        val keepAlive = mutableListOf<Memory>()
        try {
            // 2) YOLO 分区分类
            yoloBuf = lib.MaaImageBufferCreate()
            if (yoloBuf == null ||
                !setRawBgr(lib, yoloBuf, yoloBgr, YoloPreprocess.OUTPUT_SIZE, YoloPreprocess.OUTPUT_SIZE, keepAlive)
            ) {
                return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "MaaImageBufferSetRawData(128x128) 失败")
            }
            val classifyOverride = YoloClassifySupport.buildClassifyOverride(
                nodeName = YoloClassifySupport.DEFAULT_PROBE_NODE,
                model = YoloClassifySupport.DEFAULT_MODEL,
                labels = assets.config.classes,
            )
            val yoloRes = runRecognitionOnce(lib, context, yoloBuf, YoloClassifySupport.DEFAULT_PROBE_NODE, classifyOverride)
                ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "NeuralNetworkClassify 调用失败")
            val parsed = YoloClassifySupport.parseClassifyDetail(yoloRes.detailJson)
                ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "无法解析分类详情")
            val coarse = YoloClassifySupport.resolveClassify(parsed, assets.config, assets.mapping)

            val selector = MapLocatorPure.normalizeExpectedZoneId(options.expectedZoneId) {
                assets.mapping.convertYoloNameToZoneId(it)
            }
            val targetZoneId = selector.ifEmpty { coarse.zoneId }
            if (!coarse.valid) {
                return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "YOLO 分类无效（cls_index 越界）")
            }
            if (coarse.isNone || targetZoneId.isEmpty() || targetZoneId == "None") {
                return MinimapLocateFrame(LocateStatus.TRACKING_LOST, null, "本帧没有可定位区域")
            }
            val mapFile = assets.mapIndex[targetZoneId]
                ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "地图资产缺 zone=$targetZoneId")
            val map = decodeBgr(mapFile)
                ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "地图资产解码失败")

            // 3) 约束 ROI（buildSearchConstraint）
            val constraint = buildSearchConstraint(
                expectedZoneSelector = options.expectedZoneId,
                targetZoneId = targetZoneId,
                coarse = coarse,
                zones = mapOf(targetZoneId to MapDimensions(map.width, map.height)),
            )
            val searchRoi = MapLocatorCoarsePure.constrainedSearchRoi(
                constraint, map.width, map.height, plan.roi.width, plan.roi.height,
            ) ?: return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "搜索 ROI 裁到边界后为空")
            if (!constraint.yoloValidated) {
                return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "YOLO 约束未通过（zone 不匹配 selector）")
            }

            // 4) 灰度路：地图资产作 image、小地图裁剪作运行时模板
            templateBuf = lib.MaaImageBufferCreate()
            if (templateBuf == null ||
                !setRawBgr(lib, templateBuf, templateBgr, plan.roi.width, plan.roi.height, keepAlive)
            ) {
                return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "MaaImageBufferSetRawData(template) 失败")
            }
            lib.MaaContextOverrideImage(context, MAP_LOCATE_ASSERT_TEMPLATE, templateBuf)
            mapBuf = lib.MaaImageBufferCreate()
            if (mapBuf == null || !setRawBgr(lib, mapBuf, map.bytes, map.width, map.height, keepAlive)) {
                return MinimapLocateFrame(LocateStatus.YOLO_FAILED, null, "MaaImageBufferSetRawData(map) 失败")
            }
            val grayNode = MapLocatorProbeSupport.buildTemplateMatchOverride(
                nodeName = MAP_LOCATE_ASSERT_TM_NODE,
                templateName = MAP_LOCATE_ASSERT_TEMPLATE,
                method = MapLocatorProbeSupport.DEFAULT_METHOD,
                greenMask = false,
                threshold = COARSE_MATCH_THRESHOLD,
                roi = intArrayOf(searchRoi.x, searchRoi.y, searchRoi.width, searchRoi.height),
            )
            val tmRes = runRecognitionOnce(lib, context, mapBuf, MAP_LOCATE_ASSERT_TM_NODE, grayNode)
            val grayHit = tmRes?.hit == true
            val grayBox = tmRes?.box
            val grayScore = MapLocatorCoarsePure.bestMatchScore(tmRes?.detailJson)

            // 5) PathHeatmap 并行路（精排为准，失败回退灰度路）
            val generation = MapLocatorHeatmapPipeline.assetGeneration(mapFile.length(), mapFile.lastModified())
            val heatmap = runCatching {
                runHeatmapCoarsePath(
                    lib, context, map, templateBgr,
                    plan.roi.width, plan.roi.height,
                    targetZoneId, searchRoi, generation, grayBox, keepAlive,
                    heatmapCache = heatmapCache,
                    tmNode = MAP_LOCATE_ASSERT_HEATMAP_TM_NODE,
                    tmTemplate = MAP_LOCATE_ASSERT_HEATMAP_TEMPLATE,
                )
            }.getOrElse { heatmapFailure("${it.javaClass.simpleName}: ${it.message}") }

            // 观测：热图路优先，其次灰度路命中框；box 是地图坐标下模板左上角，MapPosition 取中心。
            // 上游 `locate` 在全局搜失败但裸峰 > kSeamFallbackMinPeakScore(0.0) 时照样放行，
            // 低分由追踪状态机的冷启动共识 / 远跳拒绝兜底，这里保持一致。
            val observation = run {
                val hmBox = heatmap.box
                val hmScore = heatmap.score
                if (hmScore != null && hmBox != null && hmBox.size >= 4 && hmScore > SEAM_FALLBACK_MIN_PEAK_SCORE) {
                    MapPosition(
                        zoneId = targetZoneId,
                        x = hmBox[0] + hmBox[2] / 2.0,
                        y = hmBox[1] + hmBox[3] / 2.0,
                        score = hmScore,
                    )
                } else if (grayHit && grayBox != null && grayBox.size >= 4 &&
                    grayScore != null && grayScore > SEAM_FALLBACK_MIN_PEAK_SCORE
                ) {
                    MapPosition(
                        zoneId = targetZoneId,
                        x = grayBox[0] + grayBox[2] / 2.0,
                        y = grayBox[1] + grayBox[3] / 2.0,
                        score = grayScore,
                    )
                } else {
                    null
                }
            }
            return if (observation != null) {
                MinimapLocateFrame(LocateStatus.SUCCESS, observation, "Global Search Success")
            } else {
                MinimapLocateFrame(LocateStatus.TRACKING_LOST, null, "全局搜索无有效峰")
            }
        } finally {
            if (yoloBuf != null) lib.MaaImageBufferDestroy(yoloBuf)
            if (mapBuf != null) lib.MaaImageBufferDestroy(mapBuf)
            if (templateBuf != null) lib.MaaImageBufferDestroy(templateBuf)
        }
    }

    /** 从 image buffer 的 PNG 编码字节解出 ARGB（框架回调给的 `image`）。 */
    private fun decodeArgbFromBuffer(lib: MaaFrameworkLibrary, image: Pointer): ArgbImage? {
        val bytes = readEncodedImage(lib, image) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            ArgbImage(pixels, w, h)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 画面指纹：对整帧像素做一次便宜的 64 位滚动哈希。
     *
     * 用于 [MapLocateAssertPure.isScreenStatic] 的「画面是否还在变」判定，以及
     * 「与上一帧逐像素一致 → 复用上一帧定位结果」的短路。这里**逐像素**累加、不采样：
     * 逐帧约 2M 次乘加在真机上是毫秒级，远低于本帧 YOLO + 模板匹配 + 热图的 ~0.9s；
     * 换来的是把「哈希碰撞导致把不同画面误判成静止」的概率压到可忽略，避免误判。
     */
    private fun frameFingerprint(frame: ArgbImage): Long {
        var h = 1125899906842597L
        for (pixel in frame.pixels) {
            h = h * 31 + pixel
        }
        return h
    }

    /** 主动截一帧（assert 轮询的非首帧）。 */
    private fun captureArgbFrame(lib: MaaFrameworkLibrary): ArgbImage? {
        val ctrl = currentController ?: return null
        val capId = lib.MaaControllerPostScreencap(ctrl)
        if (capId <= 0) return null
        lib.MaaControllerWait(ctrl, capId)
        val buf = lib.MaaImageBufferCreate() ?: return null
        return try {
            if (lib.MaaControllerCachedImage(ctrl, buf).toInt() == 0) return null
            if (lib.MaaImageBufferIsEmpty(buf).toInt() != 0) return null
            decodeArgbFromBuffer(lib, buf)
        } finally {
            lib.MaaImageBufferDestroy(buf)
        }
    }

    /**
     * `MapLocateAssertLocation` 的真实识别回调（上游 `MapLocateAction.cpp:404-471`）。
     *
     * 首帧用框架给的 `image`，其后自截；每帧定位后喂追踪状态机，accepted 位置落在 target
     * 矩形内即命中并提前停止。命中时 box = target 矩形（上游 `*out_box = target_rect`）。
     * detail 用 [MapLocateActionPure.assertLocationDetailJson] 回写 `out_detail`。
     */
    private val mapLocateAssertLocationCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, param, image, _, _, outBox, outDetail ->
        if (!MAP_LOCATE_ASSERT_REAL_ENABLED) return@MaaCustomRecognitionCallback 0
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) return@MaaCustomRecognitionCallback 0
        val assertParam = MapLocateActionPure.parseAssertLocationParam(param)
        val targetRect = MapLocateAssertPure.tryBuildAssertRect(assertParam)
        if (targetRect == null) {
            Ln.w("MaaRunner: MapLocateAssertLocation[$nodeName] 参数非法 zone=${assertParam.zoneId} target=${assertParam.target}")
            return@MaaCustomRecognitionCallback 0
        }
        val res = synchronized(lifecycleLock) { resource } ?: return@MaaCustomRecognitionCallback 0
        val assets = ensureMapLocateAssets(lib, res) ?: return@MaaCustomRecognitionCallback 0
        val options = MapLocateAssertPure.buildAssertOptions(assertParam)

        synchronized(mapLocateAssertLock) {
            mapLocateAssertTracking.reset()
            val frames = ArrayList<MapLocateAssertPure.AssertFrame>(MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES)
            // 逐帧画面指纹：既用于「画面静止 → 提前失败」，也用于「与上一帧逐像素一致 → 复用定位结果」
            val fingerprints = ArrayList<Long?>(MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES)
            var prevFingerprint: Long? = null
            var prevLocated: MinimapLocateFrame? = null
            val firstFrame = if (image != null && lib.MaaImageBufferIsEmpty(image).toInt() == 0) {
                decodeArgbFromBuffer(lib, image)
            } else {
                null
            }

            for (index in 0 until MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES) {
                val frame = if (index == 0) firstFrame else captureArgbFrame(lib)
                if (frame == null) {
                    // 取帧失败：指纹记 null（不参与静止判定、且打断连续计数），也不复用上一帧定位结果
                    fingerprints += null
                    prevFingerprint = null
                    prevLocated = null
                    frames += MapLocateAssertPure.AssertFrame(LocateStatus.TRACKING_LOST, null, "取帧失败")
                } else {
                    val fingerprint = frameFingerprint(frame)
                    fingerprints += fingerprint
                    // 画面与上一帧逐像素一致 → 定位输入完全相同、输出确定，直接复用上一帧结果，
                    // 省掉这一帧的 YOLO + 模板匹配 + 热图（~0.9s）；喂给追踪状态机的观测与上游逐帧重跑等价。
                    val reuse = prevLocated != null && prevFingerprint != null && prevFingerprint == fingerprint
                    val located = if (reuse) {
                        Ln.i("MaaRunner: MapLocateAssertLocation 画面未变，复用上一帧定位结果 (frame=$index)")
                        prevLocated
                    } else {
                        runCatching {
                            locateMinimapFrame(lib, context, frame, options, assets, mapLocateAssertHeatmapCache)
                        }.getOrElse {
                            MinimapLocateFrame(LocateStatus.TRACKING_LOST, null, "${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                    prevFingerprint = fingerprint
                    prevLocated = located
                    val decision = located.observation?.let {
                        mapLocateAssertTracking.feed(it, System.nanoTime() / 1_000_000_000.0)
                    }
                    if (decision != null && decision.action == TrackingAction.ACCEPT && decision.position != null) {
                        frames += MapLocateAssertPure.AssertFrame(LocateStatus.SUCCESS, decision.position, located.debugMessage)
                    } else {
                        frames += MapLocateAssertPure.AssertFrame(LocateStatus.TRACKING_LOST, null, located.debugMessage)
                    }
                }

                val last = frames.last()
                if (last.located && last.position != null &&
                    MapLocateActionPure.isPositionInsideRect(last.position, targetRect)
                ) {
                    break
                }
                // 画面已连续 N 帧完全不变：再等也不会有地图，立即结束并判未命中
                if (MapLocateAssertPure.isScreenStatic(fingerprints)) {
                    Ln.i(
                        "MaaRunner: MapLocateAssertLocation 画面连续 " +
                            "${MapLocateAssertPure.ASSERT_LOCATE_STATIC_FRAMES_TO_FAIL} 帧未变，提前结束轮询",
                    )
                    break
                }
                if (index + 1 < MapLocateAssertPure.ASSERT_LOCATE_MAX_FRAMES) {
                    runCatching { Thread.sleep(MapLocateAssertPure.ASSERT_LOCATE_POLL_DELAY_MS) }
                }
            }

            val outcome = MapLocateAssertPure.evaluateAssertFrames(frames, targetRect)
            val finalFrame = outcome.finalFrame
            val finalResult = LocateResult(
                status = finalFrame.status,
                position = finalFrame.position,
                debugMessage = finalFrame.debugMessage,
            )
            if (outDetail != null) {
                runCatching {
                    lib.MaaStringBufferSet(
                        outDetail,
                        MapLocateActionPure.assertLocationDetailJson(
                            MapLocateActionPure.buildAssertLocationOutput(finalResult, assertParam, outcome.matched),
                        ),
                    )
                }.onFailure { Ln.w("MaaRunner: MapLocateAssertLocation 写 detail 失败：${it.message}") }
            }

            val posText = finalFrame.position
                ?.let { String.format(java.util.Locale.US, "(%.2f,%.2f,score=%.3f)", it.x, it.y, it.score) }
                ?: "-"
            RunDiagnostics.note(
                "maplocator",
                "assertlocation｜" + if (outcome.matched) "命中" else "未命中" +
                    " zone=${assertParam.zoneId} frame=${outcome.matchedFrame}/${outcome.framesPolled}",
                mapOf(
                    "stage" to "assert_location",
                    "node" to nodeName,
                    "zone_id" to assertParam.zoneId,
                    "matched" to outcome.matched,
                    "matched_frame" to outcome.matchedFrame,
                    "frames_polled" to outcome.framesPolled,
                    "target" to listOf(targetRect.x, targetRect.y, targetRect.width, targetRect.height),
                    "position" to finalFrame.position?.let { listOf(it.x, it.y, it.score) },
                    "message" to finalFrame.debugMessage,
                ),
            )
            Ln.i(
                "MaaRunner: MapLocateAssertLocation[$nodeName] zone=${assertParam.zoneId} " +
                    "matched=${outcome.matched} frame=${outcome.matchedFrame}/${outcome.framesPolled} " +
                    "target=[${targetRect.x},${targetRect.y},${targetRect.width},${targetRect.height}] " +
                    "pos=$posText msg=${finalFrame.debugMessage}",
            )

            if (!outcome.matched) return@MaaCustomRecognitionCallback 0
            if (outBox != null) {
                lib.MaaRectSet(outBox, targetRect.x, targetRect.y, targetRect.width, targetRect.height)
            }
            1
        }
    }

    // ───────────────────── MapFind 真实实现（世界地图找图标） ─────────────────────
    //
    // 上游 `WorldMap/WorldMapFind.cpp` + `WorldMapSolver.cpp`。核心：大地图铺满全屏时，
    // 屏幕内容与区域底图之间是一个平移+缩放的相似变换（无旋转）。先在屏幕上解出这个
    // viewport（粗解在降采样底图上扫全尺度带 → 细解回原尺度在粗解邻域定尺度），再把目标
    // 底图坐标投到屏幕；到期望位置附近按图标模板确认（窗口 + 判定圈），命中才把**图标本体**
    // 的点框交回框架 Click。任何一步解不出来都如实返回 0：宁可失败，绝不交一个算出来的
    // 空位置去点（那比恒假更危险）。
    //
    // 复用的既有部件：框架 `TemplateMatch`（单尺度，逐档调用即多尺度）+ 运行时模板
    // `MaaContextOverrideImage`（不落盘，见 MapLocateAssert 同款链路）。纯逻辑全部下沉到
    // [WorldMapTypes] / [WorldMapFindPure] / [WorldMapSolverPure] / [WorldMapImagePure] 并可本机回归。
    //
    // 与上游的**已知边界**（本轮报告有述）：
    //  - `vote_grid` 分块投票（整窗被迷雾污染时的回退）未实现；
    //  - `gold_ratio` 解锁判定未实现：图标一律按 unlocked=true 处理（只影响 Core / RecycleBin
    //    这类带 gold_ratio 的图标，且当前管线从未用 `state` 请求锁定态）；
    //  - 玩家标记（白三角）遮蔽回退未实现：人站在图标上时认不出就如实失败，不会误点；
    //  - 拖动兑现率自补偿 / 顶边界多拍判定未实现：只影响重试效率，不影响正确性；
    //  - 图标 alpha 掩膜未参与匹配（框架 `TemplateMatch` 不吃 mask），热点取模板中心。

    /** 屏幕灰度帧；大地图全屏，灰度足以做相似变换求解。 */
    private class WorldMapGrayImage(val gray: ByteArray, val width: Int, val height: Int)

    /** 区域底图：全分辨率灰度 + 按 `coarseDownscale` 降采样的灰度。 */
    private class WorldMapBaseAssets(
        val fullGray: ByteArray,
        val fullWidth: Int,
        val fullHeight: Int,
        val smallGray: ByteArray,
        val smallWidth: Int,
        val smallHeight: Int,
    )

    /** 一次单尺度匹配的峰：分数 + 框（[x, y, w, h]，模板左上角）。 */
    private class WorldMapMatch(val score: Double, val box: IntArray)

    private var worldMapAssetsFor: Pointer? = null
    private var worldMapIconTable: Map<String, WorldMapIconSpec> = emptyMap()
    private var worldMapTemplateDir: File? = null
    private val worldMapBaseCache = HashMap<String, WorldMapBaseAssets?>()
    private val worldMapTemplateCache = HashMap<String, WorldMapGrayImage?>()

    /** 惰性加载图标表与模板目录；按 resource 指针缓存，换了 resource 必须重来。 */
    private fun ensureWorldMapAssets(res: Pointer): Boolean {
        if (worldMapAssetsFor == res && worldMapTemplateDir != null) return true
        synchronized(mapLocateAssetsLock) {
            if (worldMapAssetsFor == res && worldMapTemplateDir != null) return true
            val root = projectRoot ?: return false
            val imageRoot = File(root, "resource/image")
            val tableFile = File(imageRoot, "SceneManager/MapIcons.json")
            if (!tableFile.isFile) {
                Ln.w("MaaRunner: MapFind 缺图标表 ${tableFile.path}")
                return false
            }
            val table = runCatching { WorldMapFindPure.parseIconTable(tableFile.readText()) }
                .getOrElse {
                    Ln.w("MaaRunner: MapFind 图标表读取失败：${it.message}")
                    emptyMap()
                }
            if (table.isEmpty()) {
                Ln.w("MaaRunner: MapFind 图标表为空")
                return false
            }
            worldMapIconTable = table
            worldMapTemplateDir = File(imageRoot, "SceneManager")
            worldMapBaseCache.clear()
            worldMapTemplateCache.clear()
            worldMapAssetsFor = res
            Ln.i("MaaRunner: MapFind 图标表已加载 icons=${table.keys}")
            return true
        }
    }

    /** 读某 zone 的区域底图（全灰度 + 降采样灰度）。上游 `LoadZoneBase` + `SolveViewport` 降采样。 */
    private fun loadWorldMapBase(zone: String): WorldMapBaseAssets? {
        worldMapBaseCache[zone]?.let { return it }
        synchronized(mapLocateAssetsLock) {
            worldMapBaseCache[zone]?.let { return it }
            val root = projectRoot ?: return null
            val zoneDir = File(File(root, "resource/image/MapLocator"), zone)
            if (!zoneDir.isDirectory) {
                worldMapBaseCache[zone] = null
                return null
            }
            val names = zoneDir.listFiles()?.map { it.name } ?: emptyList()
            val baseName = WorldMapFindPure.findZoneBaseFile(names)
            if (baseName == null) {
                Ln.w("MaaRunner: MapFind zone=$zone 找不到 Base 底图")
                worldMapBaseCache[zone] = null
                return null
            }
            val bgr = decodeBgr(File(zoneDir, baseName))
            if (bgr == null) {
                worldMapBaseCache[zone] = null
                return null
            }
            val gray = MapLocatorRefinePure.bgrToGray(bgr.bytes, bgr.width, bgr.height)
            if (gray == null) {
                worldMapBaseCache[zone] = null
                return null
            }
            val down = WorldMapViewportConfig().coarseDownscale.coerceAtLeast(1)
            val (sw, sh) = WorldMapImagePure.downscaledSize(bgr.width, bgr.height, down)
            val small = WorldMapImagePure.downscaleGrayArea(gray, bgr.width, bgr.height, down) ?: gray
            val assets = WorldMapBaseAssets(gray, bgr.width, bgr.height, small, sw, sh)
            worldMapBaseCache[zone] = assets
            Ln.i("MaaRunner: MapFind zone=$zone 底图 ${bgr.width}x${bgr.height} → 粗解 ${sw}x$sh")
            return assets
        }
    }

    /** 读一张图标模板（灰度）。名称可为相对 `SceneManager` 的路径（如 `../AutoDelivery/...`）。 */
    private fun loadWorldMapTemplate(name: String): WorldMapGrayImage? {
        worldMapTemplateCache[name]?.let { return it }
        synchronized(mapLocateAssetsLock) {
            worldMapTemplateCache[name]?.let { return it }
            val dir = worldMapTemplateDir ?: return null
            val file = runCatching { File(dir, name).canonicalFile }.getOrNull() ?: return null
            if (!file.isFile) {
                Ln.w("MaaRunner: MapFind 图标模板不存在 $name")
                worldMapTemplateCache[name] = null
                return null
            }
            val bgr = decodeBgr(file)
            if (bgr == null) {
                worldMapTemplateCache[name] = null
                return null
            }
            val gray = MapLocatorRefinePure.bgrToGray(bgr.bytes, bgr.width, bgr.height)
            if (gray == null) {
                worldMapTemplateCache[name] = null
                return null
            }
            val img = WorldMapGrayImage(gray, bgr.width, bgr.height)
            worldMapTemplateCache[name] = img
            return img
        }
    }

    /** 主动截一帧并转灰度；失败返回 null。 */
    private fun captureWorldMapScreen(lib: MaaFrameworkLibrary): WorldMapGrayImage? {
        val frame = captureArgbFrame(lib) ?: return null
        val bgr = YoloPreprocess.argbToBgr(frame.pixels)
        val gray = MapLocatorRefinePure.bgrToGray(bgr, frame.width, frame.height) ?: return null
        return WorldMapGrayImage(gray, frame.width, frame.height)
    }

    /**
     * 在 [searchBuf]（一张 BGR 图）上用运行时模板 [patchGray] 跑一次单尺度 `TemplateMatch`。
     *
     * [threshold] 传 0.0 时框架总会给出最高分（供跨档比较）；[patchGray] 复制成 BGR 再喂框架，
     * 三通道同值等价于灰度。模板 buffer 每次调用即销毁；搜索图 buffer 由调用方持有。
     */
    private fun runWorldMapMatch(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        searchBuf: Pointer,
        patchGray: ByteArray,
        patchWidth: Int,
        patchHeight: Int,
        keepAlive: MutableList<Memory>,
        threshold: Double,
    ): WorldMapMatch? {
        if (patchWidth <= 0 || patchHeight <= 0) return null
        val templBuf = lib.MaaImageBufferCreate() ?: return null
        return try {
            if (!setRawBgr(lib, templBuf, WorldMapImagePure.grayToBgr(patchGray), patchWidth, patchHeight, keepAlive)) {
                return null
            }
            if (lib.MaaContextOverrideImage(context, MAP_FIND_TEMPLATE, templBuf).toInt() == 0) return null
            val nodeOverride = MapLocatorProbeSupport.buildTemplateMatchOverride(
                nodeName = MAP_FIND_TM_NODE,
                templateName = MAP_FIND_TEMPLATE,
                method = MapLocatorProbeSupport.DEFAULT_METHOD,
                greenMask = false,
                threshold = threshold,
                roi = null,
            )
            val res = runRecognitionOnce(lib, context, searchBuf, MAP_FIND_TM_NODE, nodeOverride) ?: return null
            val score = MapLocatorCoarsePure.bestMatchScore(res.detailJson) ?: return null
            val box = res.box ?: return null
            if (box.size < 4 || box[2] <= 0 || box[3] <= 0) return null
            WorldMapMatch(score, box)
        } finally {
            lib.MaaImageBufferDestroy(templBuf)
        }
    }

    /**
     * 解一帧的 viewport（上游 `SolveViewport`）：粗解在降采样底图上扫尺度带取最优档，
     * 细解在粗解落点邻域定尺度，置信不足返回 null。
     */
    private fun solveWorldMapViewport(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        screen: WorldMapGrayImage,
        base: WorldMapBaseAssets,
        cfg: WorldMapViewportConfig,
    ): WorldMapViewport? {
        val roiRect = WorldMapTypes.safeArea(screen.width, screen.height, cfg.roi, 0)
        if (roiRect.width < WorldMapTypes.MIN_TEMPLATE_SIDE || roiRect.height < WorldMapTypes.MIN_TEMPLATE_SIDE) return null
        val roiGray = WorldMapImagePure.cropGray(
            screen.gray, screen.width, screen.height, roiRect.x, roiRect.y, roiRect.width, roiRect.height,
        ) ?: return null
        if (base.smallWidth < WorldMapTypes.MIN_TEMPLATE_SIDE || base.smallHeight < WorldMapTypes.MIN_TEMPLATE_SIDE) return null

        val down = cfg.coarseDownscale.coerceAtLeast(1)
        val (roiSmallW, roiSmallH) = WorldMapImagePure.downscaledSize(roiRect.width, roiRect.height, down)
        val roiSmall = WorldMapImagePure.downscaleGrayArea(roiGray, roiRect.width, roiRect.height, down) ?: return null

        val keepAlive = mutableListOf<Memory>()
        var baseSmallBuf: Pointer? = null
        var windowBuf: Pointer? = null
        try {
            baseSmallBuf = lib.MaaImageBufferCreate() ?: return null
            if (!setRawBgr(lib, baseSmallBuf, WorldMapImagePure.grayToBgr(base.smallGray), base.smallWidth, base.smallHeight, keepAlive)) {
                return null
            }

            val coarseScales = WorldMapSolverPure.coarseScalesFiltered(cfg, roiSmallW, roiSmallH, base.smallWidth, base.smallHeight)
            val probes = ArrayList<WorldMapSolverPure.CoarseProbe>(coarseScales.size)
            for (scale in coarseScales) {
                val tw = WorldMapTypes.lround(roiSmallW * scale)
                val th = WorldMapTypes.lround(roiSmallH * scale)
                if (tw < WorldMapTypes.MIN_TEMPLATE_SIDE || th < WorldMapTypes.MIN_TEMPLATE_SIDE) {
                    probes += WorldMapSolverPure.CoarseProbe(scale, null)
                    continue
                }
                val patch = WorldMapImagePure.resizeGrayBilinear(roiSmall, roiSmallW, roiSmallH, tw, th)
                val match = patch?.let { runWorldMapMatch(lib, context, baseSmallBuf, it, tw, th, keepAlive, 0.0) }
                val peak = match?.let {
                    WorldMapPeak(it.score, it.box[0].toDouble(), it.box[1].toDouble(), it.box[2], it.box[3])
                }
                probes += WorldMapSolverPure.CoarseProbe(scale, peak)
            }
            val coarse = WorldMapSolverPure.bestCoarse(probes) ?: return null
            val rungs = WorldMapSolverPure.coarseRungs(probes)
            val plan = WorldMapSolverPure.finePlan(
                cfg, base.fullWidth, base.fullHeight, roiRect.width, roiRect.height, coarse,
            ) ?: return null
            val windowGray = WorldMapImagePure.cropGray(
                base.fullGray, base.fullWidth, base.fullHeight,
                plan.window.x, plan.window.y, plan.window.width, plan.window.height,
            ) ?: return null
            windowBuf = lib.MaaImageBufferCreate() ?: return null
            if (!setRawBgr(lib, windowBuf, WorldMapImagePure.grayToBgr(windowGray), plan.window.width, plan.window.height, keepAlive)) {
                return null
            }

            var bestScale = 0.0
            var bestPeak: WorldMapPeak? = null
            for (scale in plan.scales) {
                val tw = WorldMapTypes.lround(roiRect.width * scale)
                val th = WorldMapTypes.lround(roiRect.height * scale)
                if (tw < WorldMapTypes.MIN_TEMPLATE_SIDE || th < WorldMapTypes.MIN_TEMPLATE_SIDE) continue
                val patch = WorldMapImagePure.resizeGrayBilinear(roiGray, roiRect.width, roiRect.height, tw, th) ?: continue
                val match = runWorldMapMatch(lib, context, windowBuf, patch, tw, th, keepAlive, 0.0) ?: continue
                val peak = WorldMapPeak(match.score, match.box[0].toDouble(), match.box[1].toDouble(), match.box[2], match.box[3])
                if (bestPeak == null || peak.score > bestPeak!!.score) {
                    bestPeak = peak
                    bestScale = scale
                }
            }
            val finePeak = bestPeak ?: return null
            return WorldMapSolverPure.buildViewport(cfg, roiRect, plan.window, bestScale, finePeak, rungs, cfg.voteGrid)
        } finally {
            baseSmallBuf?.let { lib.MaaImageBufferDestroy(it) }
            windowBuf?.let { lib.MaaImageBufferDestroy(it) }
        }
    }

    /**
     * 在期望位置附近扫图标（上游 `ConfirmSpot` 的 `scan`）：窗口内逐模板 × 逐尺度匹配，
     * 取最高一档，低于 `minScore` 判无。返回 null 表示窗口为空或没认出来。
     */
    private fun scanWorldMapSpot(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        spec: WorldMapSpotConfig,
        screen: WorldMapGrayImage,
        expectedX: Double,
        expectedY: Double,
        radius: Int,
        viewportScale: Double,
    ): WorldMapSpotHit? {
        val centerIx = WorldMapTypes.lround(expectedX)
        val centerIy = WorldMapTypes.lround(expectedY)
        val window = MapRect(centerIx - radius, centerIy - radius, radius * 2, radius * 2)
            .intersect(MapRect(0, 0, screen.width, screen.height))
        if (window.isEmpty) return null
        val patch = WorldMapImagePure.cropGray(
            screen.gray, screen.width, screen.height, window.x, window.y, window.width, window.height,
        ) ?: return null

        val ladder = WorldMapTypes.spotScaleLadder(spec)
        val keepAlive = mutableListOf<Memory>()
        val searchBuf = lib.MaaImageBufferCreate() ?: return null
        try {
            if (!setRawBgr(lib, searchBuf, WorldMapImagePure.grayToBgr(patch), window.width, window.height, keepAlive)) return null
            var bestTemplate = ""
            var best: WorldMapMatch? = null
            var bestScale = 0.0
            for (name in spec.templates) {
                val templ = loadWorldMapTemplate(name) ?: continue
                for (scale in ladder) {
                    val tw = WorldMapTypes.lround(templ.width * scale)
                    val th = WorldMapTypes.lround(templ.height * scale)
                    if (tw < WorldMapTypes.MIN_ANCHOR_SIDE || th < WorldMapTypes.MIN_ANCHOR_SIDE) continue
                    if (tw > window.width || th > window.height) continue
                    val resized = WorldMapImagePure.resizeGrayBilinear(templ.gray, templ.width, templ.height, tw, th) ?: continue
                    val match = runWorldMapMatch(lib, context, searchBuf, resized, tw, th, keepAlive, 0.0) ?: continue
                    if (best == null || match.score > best!!.score) {
                        best = match
                        bestTemplate = name
                        bestScale = scale
                    }
                }
            }
            val hit = best ?: return null
            if (hit.score < spec.minScore) return null
            val centerX = window.x + hit.box[0] + hit.box[2] / 2.0
            val centerY = window.y + hit.box[1] + hit.box[3] / 2.0
            val hotspot = WorldMapTypes.hotspot(centerX, centerY, 0.0, 0.0, bestScale)
            return WorldMapSpotHit(
                templateName = bestTemplate,
                centerX = centerX,
                centerY = centerY,
                hotspotX = hotspot[0],
                hotspotY = hotspot[1],
                sizeWidth = hit.box[2],
                sizeHeight = hit.box[3],
                score = hit.score,
                matchScale = bestScale,
                offsetBase = WorldMapTypes.offsetBase(centerX, centerY, expectedX, expectedY, viewportScale),
                goldRatio = 0.0,
                unlocked = true,
            )
        } finally {
            lib.MaaImageBufferDestroy(searchBuf)
        }
    }

    /**
     * 上游 `ConfirmSpot`（`WorldMapSolver.cpp:581-701`）：浮动/定点窗口 + 判定圈。
     * 定点点位偏出判定圈时，先在更小的窗口里再看一次近处（对齐上游 669-678）。
     */
    private fun confirmWorldMapSpot(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        spec: WorldMapIconSpec,
        screen: WorldMapGrayImage,
        expectedX: Double,
        expectedY: Double,
        viewportScale: Double,
    ): WorldMapSpotHit? {
        val floating = spec.spot.radiusBase > 0.0
        val radius = WorldMapTypes.confirmRadius(spec.spot, viewportScale)
        var found = scanWorldMapSpot(lib, context, spec.spot, screen, expectedX, expectedY, radius, viewportScale) ?: return null
        if (!floating && found.offsetBase > spec.spot.gateBase) {
            val tight = maxOf(
                WorldMapTypes.lround(spec.spot.gateBase / viewportScale),
                WorldMapTypes.MIN_TEMPLATE_SIDE,
            )
            if (tight < radius) {
                val closer = scanWorldMapSpot(lib, context, spec.spot, screen, expectedX, expectedY, tight, viewportScale)
                if (closer != null && closer.offsetBase < found.offsetBase) found = closer
            }
        }
        if (WorldMapTypes.gateReject(found.offsetBase, spec.spot.gateBase, floating)) return null
        return found
    }

    /** 上游 `DragMap` 的拖动部分（`WorldMapFind.cpp:236-261`）：随机落位 + 定额时长 + 等停稳。 */
    private fun dragWorldMap(lib: MaaFrameworkLibrary, safe: MapRect, dx: Double, dy: Double): DoubleArray {
        val ctrl = currentController ?: return doubleArrayOf(0.0, 0.0)
        val issued = WorldMapTypes.dragClamp(safe, dx, dy)
        if (kotlin.math.hypot(issued[0], issued[1]) < 1.0) return doubleArrayOf(0.0, 0.0)
        val halfX = kotlin.math.abs(issued[0]) / 2.0
        val halfY = kotlin.math.abs(issued[1]) / 2.0
        val centerX = randomBetween(safe.x + halfX, safe.x + safe.width - halfX)
        val centerY = randomBetween(safe.y + halfY, safe.y + safe.height - halfY)
        val fromX = WorldMapTypes.lround(centerX - issued[0] / 2.0)
        val fromY = WorldMapTypes.lround(centerY - issued[1] / 2.0)
        val toX = WorldMapTypes.lround(centerX + issued[0] / 2.0)
        val toY = WorldMapTypes.lround(centerY + issued[1] / 2.0)
        val duration = WorldMapTypes.swipeDuration(issued[0], issued[1])
        Ln.i("MaaRunner: MapFind 拖动 ($fromX,$fromY)→($toX,$toY) dur=$duration")
        val id = lib.MaaControllerPostSwipe(ctrl, fromX, fromY, toX, toY, duration)
        lib.MaaControllerWait(ctrl, id)
        runCatching { Thread.sleep(WorldMapTypes.SETTLE_MILLIS.toLong()) }
        return issued
    }

    private fun randomBetween(lo: Double, hi: Double): Double {
        if (!(hi > lo)) return (lo + hi) / 2.0
        return lo + kotlin.random.Random.nextDouble() * (hi - lo)
    }

    /** 候选归它的 next 节点管开关：读不出来一律当开着（上游 `CandidateEnabled`）。 */
    private fun nodeEnabled(lib: MaaFrameworkLibrary, context: Pointer, name: String): Boolean {
        val buf = lib.MaaStringBufferCreate() ?: return true
        return try {
            if (lib.MaaContextGetNodeData(context, name, buf).toInt() == 0) return true
            val text = lib.MaaStringBufferGet(buf) ?: return true
            val data = MaaJsonTree.parse(text) as? Map<*, *> ?: return true
            (data["enabled"] as? Boolean) ?: true
        } catch (_: Throwable) {
            true
        } finally {
            lib.MaaStringBufferDestroy(buf)
        }
    }

    /** 命中候选时把它的 `next` 交回框架（上游 `MaaContextOverrideNext`；本项目改用覆盖 `next` 字段）。 */
    private fun handBackNext(lib: MaaFrameworkLibrary, context: Pointer, nodeName: String?, next: String): Boolean {
        if (nodeName == null || next.isEmpty()) return true
        val override = buildJsonObject {
            put(nodeName, buildJsonObject { put("next", JsonArray(listOf(JsonPrimitive(next)))) })
        }.toString()
        return lib.MaaContextOverridePipeline(context, override).toInt() != 0
    }

    private fun rectContains(rect: MapRect, x: Double, y: Double): Boolean {
        val ix = WorldMapTypes.lround(x)
        val iy = WorldMapTypes.lround(y)
        return ix >= rect.x && ix < rect.x + rect.width && iy >= rect.y && iy < rect.y + rect.height
    }

    private fun writeWorldMapDetail(
        lib: MaaFrameworkLibrary,
        outDetail: Pointer?,
        param: WorldMapFindPure.FindParam,
        index: Int,
        target: WorldMapFindPure.Target,
        expected: DoubleArray,
        viewport: WorldMapViewport,
        icon: String?,
        hit: WorldMapSpotHit?,
        next: String?,
        playerMarker: Boolean,
    ) {
        if (outDetail == null) return
        val json = WorldMapFindPure.findDetailJson(
            zone = param.zone,
            index = index,
            atX = target.at[0],
            atY = target.at[1],
            screenX = expected[0],
            screenY = expected[1],
            viewportScale = viewport.scale,
            viewportVote = viewport.voteGrid,
            next = next,
            icon = icon,
            templateName = hit?.templateName,
            score = hit?.score,
            unlocked = hit?.unlocked,
            clickX = hit?.hotspotX,
            clickY = hit?.hotspotY,
            playerMarker = playerMarker,
        )
        runCatching { lib.MaaStringBufferSet(outDetail, json) }
            .onFailure { Ln.w("MaaRunner: MapFind 写 detail 失败：${it.message}") }
    }

    /**
     * `MapFind` 的主流程（上游 `MapFindRun`）：解析参数 → 触发 ZoomOut → 逐候选解视口/平移/
     * 认图标 → 命中交回点框与 next，否则 0。所有分支都不伪造坐标。
     */
    private fun mapFindRun(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        nodeName: String?,
        rawParam: String?,
        outBox: Pointer?,
        outDetail: Pointer?,
    ): Int {
        val parse = WorldMapFindPure.parseFindParam(rawParam)
        val param = parse.param
        if (param == null) {
            Ln.w("MaaRunner: MapFind 参数非法：${parse.error}")
            return 0
        }
        val spec = if (param.icon.isNotEmpty()) {
            val resolved = worldMapIconTable[param.icon]
            if (resolved == null) {
                Ln.w("MaaRunner: MapFind 图标表无此项 ${param.icon}")
                return 0
            }
            resolved
        } else {
            null
        }
        if (param.state.isNotEmpty() && (spec == null || spec.spot.minGoldRatio <= 0.0)) {
            Ln.w("MaaRunner: MapFind 图标 ${param.icon} 无解锁阈值，state 判不了")
            return 0
        }
        val base = loadWorldMapBase(param.zone) ?: return 0
        val config = WorldMapViewportConfig(voteGrid = param.voteGrid)
        val targets = WorldMapFindPure.buildTargets(param)
        if (targets.isEmpty()) return 0

        runCatching { lib.MaaContextRunTask(context, MAP_FIND_ZOOM_OUT_NODE, "{}") }
            .onFailure { Ln.w("MaaRunner: MapFind 触发 $MAP_FIND_ZOOM_OUT_NODE 失败：${it.message}") }

        var screen: WorldMapGrayImage? = null
        var viewport: WorldMapViewport? = null
        var previousScale: Double? = null
        var issued = doubleArrayOf(0.0, 0.0)
        val wantUnlocked = WorldMapFindPure.wantsUnlocked(param.state)

        for ((index, target) in targets.withIndex()) {
            if (target.next.isNotEmpty() && !nodeEnabled(lib, context, target.next)) {
                Ln.i("MaaRunner: MapFind 候选被关，跳过 index=$index next=${target.next}")
                continue
            }
            var attempt = 0
            var pans = 0
            var nudges = 0
            while (attempt < param.maxAttempts) {
                if (screen == null) {
                    screen = captureWorldMapScreen(lib)
                    viewport = null
                    if (screen == null) {
                        attempt++
                        runCatching { Thread.sleep(WorldMapTypes.RETRY_MILLIS.toLong()) }
                        continue
                    }
                }
                val frame = screen!!
                val safe = WorldMapTypes.safeArea(
                    frame.width, frame.height, WorldMapTypes.ICON_AREA, WorldMapTypes.ICON_MARGIN,
                )
                if (safe.isEmpty) {
                    Ln.e("MaaRunner: MapFind 安全区退化 ${frame.width}x${frame.height}")
                    return 0
                }

                if (viewport == null) {
                    val pinned = previousScale?.let { WorldMapSolverPure.pinnedConfig(config, it) }
                    if (pinned != null) viewport = solveWorldMapViewport(lib, context, frame, base, pinned)
                    if (viewport == null) viewport = solveWorldMapViewport(lib, context, frame, base, config)
                    if (viewport == null) {
                        previousScale = null
                        screen = null
                        if (nudges >= WorldMapTypes.MAX_NUDGES) {
                            attempt++
                            runCatching { Thread.sleep(WorldMapTypes.RETRY_MILLIS.toLong()) }
                            continue
                        }
                        val nudge = WorldMapTypes.nudgeDelta(safe, issued[0], issued[1], nudges)
                        nudges++
                        issued = dragWorldMap(lib, safe, nudge[0], nudge[1])
                        if (kotlin.math.hypot(issued[0], issued[1]) < 1.0) attempt++
                        continue
                    }
                    previousScale = viewport!!.scale
                    issued = doubleArrayOf(0.0, 0.0)
                }

                val vp = viewport!!
                val expected = vp.toScreen(target.at[0], target.at[1])
                val need = WorldMapTypes.panDelta(safe, expected[0], expected[1])
                if (kotlin.math.hypot(need[0], need[1]) >= 1.0) {
                    if (pans >= WorldMapTypes.MAX_PANS) {
                        val usable = WorldMapTypes.safeArea(frame.width, frame.height, WorldMapTypes.ICON_AREA, 0)
                        if (!rectContains(usable, expected[0], expected[1])) {
                            Ln.w("MaaRunner: MapFind 目标挪不进可点区 zone=${param.zone} index=$index")
                            break
                        }
                    } else {
                        pans++
                        issued = dragWorldMap(lib, safe, need[0], need[1])
                        screen = null
                        viewport = null
                        if (kotlin.math.hypot(issued[0], issued[1]) < 1.0) {
                            Ln.w("MaaRunner: MapFind 目标出界但拖不动 index=$index")
                            break
                        }
                        continue
                    }
                }

                attempt++

                // 不给图标名就只解坐标：交期望位置的点框（上游 WorldMapFind.cpp:536-543）
                if (spec == null) {
                    writeWorldMapDetail(
                        lib, outDetail, param, index, target, expected, vp,
                        icon = null, hit = null, next = null, playerMarker = false,
                    )
                    if (outBox != null) {
                        val box = WorldMapTypes.pointBox(expected[0], expected[1])
                        lib.MaaRectSet(outBox, box.x, box.y, box.width, box.height)
                    }
                    Ln.i("MaaRunner: MapFind 定位 zone=${param.zone} at=${target.at} screen=(${expected[0]},${expected[1]})")
                    return 1
                }

                val hit = confirmWorldMapSpot(lib, context, spec, frame, expected[0], expected[1], vp.scale)
                if (hit != null && hit.unlocked != wantUnlocked) {
                    Ln.w(
                        "MaaRunner: MapFind 图标在但解锁状态不符 zone=${param.zone} icon=${param.icon} " +
                            "unlocked=${hit.unlocked} want=$wantUnlocked",
                    )
                    break
                }
                if (hit != null) {
                    val next = target.next.ifEmpty { null }
                    writeWorldMapDetail(
                        lib, outDetail, param, index, target, expected, vp,
                        icon = param.icon, hit = hit, next = next, playerMarker = false,
                    )
                    if (!handBackNext(lib, context, nodeName, target.next)) {
                        Ln.e("MaaRunner: MapFind 命中但 next 交不回框架 next=${target.next}")
                        return 0
                    }
                    if (outBox != null) {
                        val box = WorldMapTypes.spotBox(hit)
                        lib.MaaRectSet(outBox, box.x, box.y, box.width, box.height)
                    }
                    Ln.i(
                        "MaaRunner: MapFind 命中 zone=${param.zone} icon=${param.icon} " +
                            "screen=(${expected[0]},${expected[1]}) score=${hit.score} scale=${vp.scale}",
                    )
                    return 1
                }

                Ln.w("MaaRunner: MapFind 期望位置认不出图标 attempt=$attempt index=$index")
                screen = null
                viewport = null
                runCatching { Thread.sleep(WorldMapTypes.RETRY_MILLIS.toLong()) }
            }
        }

        Ln.w("MaaRunner: MapFind 放弃 zone=${param.zone} targets=${targets.size} icon=${param.icon}")
        return 0
    }

    /** `MapFind` 的识别回调。任何解不出来/置信不足一律返回 0，绝不交错误坐标。 */
    private val mapFindCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, customRecognitionParam, _, _, _, outBox, outDetail ->
        if (!MAP_FIND_REAL_ENABLED) return@MaaCustomRecognitionCallback 0
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) return@MaaCustomRecognitionCallback 0
        val res = synchronized(lifecycleLock) { resource } ?: return@MaaCustomRecognitionCallback 0
        if (!ensureWorldMapAssets(res)) return@MaaCustomRecognitionCallback 0
        val startedAt = System.nanoTime()
        val result = runCatching {
            mapFindRun(lib, context, nodeName, customRecognitionParam, outBox, outDetail)
        }.getOrElse {
            Ln.e("MaaRunner: MapFind 异常", it)
            0
        }
        RunDiagnostics.note(
            "worldmap",
            "mapfind｜" + if (result == 1) "命中" else "未命中" +
                " cost=${(System.nanoTime() - startedAt) / 1_000_000}ms",
            mapOf("node" to nodeName, "hit" to (result == 1), "param" to customRecognitionParam),
        )
        result.toByte()
    }

    /** 扫描地图资产目录，按上游 key 规则建 zoneId → 文件 索引。 */
    private fun buildMapZoneIndex(mapRoot: File): Map<String, File> {
        val files = mapRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in MAP_IMAGE_EXTENSIONS }
            .toList()
        if (files.isEmpty()) return emptyMap()
        val entries = files.map { (it.parentFile?.name ?: "") to it.name }
        val keys = MapLocatorCoarsePure.zoneIndex(entries)
        val byLocation = files.associateBy { (it.parentFile?.name ?: "") to it.name }
        return keys.mapValues { (_, location) -> byLocation.getValue(location) }
    }

    private fun decodeArgb(path: String): ArgbImage? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            ArgbImage(pixels, w, h)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBgr(file: File): BgrImage? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            BgrImage(YoloPreprocess.argbToBgr(pixels), w, h)
        } finally {
            bitmap.recycle()
        }
    }

    /** 把 BGR 裸像素写进 image buffer；[keepAlive] 用于撑住 Memory 引用到调用结束。 */
    private fun setRawBgr(
        lib: MaaFrameworkLibrary,
        buffer: Pointer,
        bgr: ByteArray,
        width: Int,
        height: Int,
        keepAlive: MutableList<Memory>,
    ): Boolean {
        val memory = Memory(bgr.size.toLong())
        memory.write(0, bgr, 0, bgr.size)
        keepAlive += memory
        return lib.MaaImageBufferSetRawData(buffer, memory, width, height, MaaImageType.CV_8UC3).toInt() != 0
    }

    // ───────────────────── PathHeatmap 并行路（粗排 a + 精排 b） ─────────────────────

    /**
     * PathHeatmap 粗定位结果。
     *
     * [coarseScore]/[coarseBox] 是框架 `TemplateMatch`（掩膜外均值填充模板，选项 a）的粗排；
     * [score]/[box] 是最终结果（精排成功用真掩膜 ZNCC（选项 b），否则回退粗排）。box 均为**地图坐标**。
     */
    private data class HeatmapCoarsePathResult(
        val ok: Boolean,
        val overrideOk: Boolean,
        val coarseHit: Boolean,
        val coarseScore: Double?,
        val coarseBox: IntArray?,
        val score: Double?,
        val box: IntArray?,
        val usedRefine: Boolean,
        val trackingValid: Boolean?,
        val globalAccepted: Double?,
        val error: String?,
    )

    private fun heatmapFailure(reason: String): HeatmapCoarsePathResult = HeatmapCoarsePathResult(
        ok = false, overrideOk = false, coarseHit = false, coarseScore = null, coarseBox = null,
        score = null, box = null, usedRefine = false,
        trackingValid = null, globalAccepted = null, error = reason,
    )

    /**
     * PathHeatmap 并行路（不替换灰度路，仅并列对照）：
     *
     *  1. 地图搜索 ROI 与 小地图 各自 `extractPathHeatmap`；模板侧 `extractTemplatePathFeature`
     *     取掩膜。搜索热图按 `(zoneId, kind, roi, generation)` 走 [heatmapSearchCache] 两槽缓存。
     *  2. 模板按掩膜外接框裁剪（上游 `MapLocator.cpp:413-419`）。
     *  3. 框架粗排：搜索热图（单通道 → 复制成 BGR）经 `MaaImageBufferSetRawData` 作帧图、
     *     掩膜外**均值**填充的模板热图经 `MaaContextOverrideImage` 作运行时模板，跑
     *     `TemplateMatch(method=5, green_mask=false)`——框架吃不了 mask，故用选项 (a) 近似。
     *  4. Kotlin 精排：粗框邻域窗口内用真掩膜 ZNCC（选项 b）定最终分/框；无解则回退粗排。
     *  5. 裁决走既有 [MatchValidation] 的 [PathHeatmapMatchStrategy]（`validateGlobalSearch` /
     *     `validateTracking`），本文件不重造。
     */
    private fun runHeatmapCoarsePath(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        map: BgrImage,
        minimapBgr: ByteArray,
        minimapW: Int,
        minimapH: Int,
        targetZoneId: String,
        searchRoi: MapRect,
        generation: Long,
        grayBox: IntArray?,
        keepAlive: MutableList<Memory>,
        heatmapCache: MapLocatorHeatmapPipeline.SearchFeatureCache,
        tmNode: String,
        tmTemplate: String,
    ): HeatmapCoarsePathResult {
        val isBase = targetZoneId.contains("Base")
        val kind = if (isBase) TemplateFeatureKind.PATH_HEATMAP_BASE else TemplateFeatureKind.PATH_HEATMAP_TIER
        val cfg = if (isBase) {
            MapLocatorPathHeatmap.ImageProcessingConfig.Base
        } else {
            MapLocatorPathHeatmap.ImageProcessingConfig.Tier
        }
        val sw = searchRoi.width
        val sh = searchRoi.height
        if (sw <= 0 || sh <= 0) return heatmapFailure("搜索 ROI 为空")

        // 1) 搜索热图（缓存 key = zoneId+kind+roi+generation，对齐 getGlobalSearchFeature）
        val roiBgr = MapLocatorProbeSupport.cropBgr(
            map.bytes, map.width, map.height, searchRoi.x, searchRoi.y, sw, sh,
        ) ?: return heatmapFailure("搜索 ROI 裁剪失败")
        val key = MapLocatorHeatmapPipeline.SearchFeatureKey(targetZoneId, kind, searchRoi, generation)
        val searchHeat = heatmapCache.getOrCompute(key) {
            MapLocatorPathHeatmap.extractPathHeatmap(roiBgr, sw, sh)
        } ?: return heatmapFailure("搜索热图构建失败")

        // 2) 模板热图 + 掩膜，并按掩膜外接框裁剪（MapLocator.cpp:413-419）
        val tmpl = MapLocatorPathHeatmap.extractTemplatePathFeature(minimapBgr, minimapW, minimapH, cfg)
            ?: return heatmapFailure("模板热图/掩膜提取失败")
        val bbox = MapLocatorHeatmapPipeline.maskBoundingBox(tmpl.mask, minimapW, minimapH)
        if (bbox.isEmpty) return heatmapFailure("模板掩膜为空")
        val croppedFeature = MapLocatorHeatmapPipeline.cropGray(tmpl.feature, minimapW, minimapH, bbox)
            ?: return heatmapFailure("模板热图裁剪失败")
        val croppedMask = MapLocatorHeatmapPipeline.cropMask(tmpl.mask, minimapW, minimapH, bbox)
            ?: return heatmapFailure("模板掩膜裁剪失败")
        val tw = bbox.width
        val th = bbox.height
        if (tw > sw || th > sh) return heatmapFailure("模板(${tw}x${th})大于搜索窗(${sw}x${sh})")

        // 3) 框架粗排（选项 a）
        var overrideOk = false
        var coarseHit = false
        var coarseScore: Double? = null
        var coarseBox: IntArray? = null
        val searchBuf = lib.MaaImageBufferCreate()
        val templBuf = lib.MaaImageBufferCreate()
        if (searchBuf == null || templBuf == null) {
            searchBuf?.let { lib.MaaImageBufferDestroy(it) }
            templBuf?.let { lib.MaaImageBufferDestroy(it) }
            return heatmapFailure("MaaImageBufferCreate 失败")
        }
        try {
            val searchBgr = MapLocatorHeatmapPipeline.replicateToBgr(searchHeat)
            val coarseTempl = MapLocatorHeatmapPipeline.replicateToBgr(
                MapLocatorHeatmapPipeline.fillOutsideMask(croppedFeature, croppedMask),
            )
            if (!setRawBgr(lib, searchBuf, searchBgr, sw, sh, keepAlive) ||
                !setRawBgr(lib, templBuf, coarseTempl, tw, th, keepAlive)
            ) {
                return heatmapFailure("MaaImageBufferSetRawData(热图) 失败")
            }
            overrideOk = lib.MaaContextOverrideImage(context, tmTemplate, templBuf).toInt() != 0
            val nodeOverride = MapLocatorProbeSupport.buildTemplateMatchOverride(
                nodeName = tmNode,
                templateName = tmTemplate,
                method = MapLocatorProbeSupport.DEFAULT_METHOD,
                greenMask = false,
                threshold = COARSE_MATCH_THRESHOLD,
                roi = null,
            )
            val res = runRecognitionOnce(lib, context, searchBuf, DEBUG_COARSE_HEATMAP_TM_NODE, nodeOverride)
            coarseHit = res?.hit == true
            coarseScore = MapLocatorCoarsePure.bestMatchScore(res?.detailJson)
            // 未命中时不采信域内默认框，留空让精排用灰度路框 / 窗中心
            coarseBox = if (coarseHit) res?.box?.copyOf() else null
        } finally {
            lib.MaaImageBufferDestroy(searchBuf)
            lib.MaaImageBufferDestroy(templBuf)
        }

        // 4) 精排（选项 b）：粗框邻域窗口内真掩膜 ZNCC
        val cb = coarseBox
        val centerX = when {
            cb != null -> cb[0]
            grayBox != null && grayBox.size >= 4 -> grayBox[0] - searchRoi.x
            else -> (sw - tw) / 2
        }
        val centerY = when {
            cb != null -> cb[1]
            grayBox != null && grayBox.size >= 4 -> grayBox[1] - searchRoi.y
            else -> (sh - th) / 2
        }
        val refined = MapLocatorHeatmapPipeline.refineInWindow(
            searchHeat, sw, sh, croppedFeature, tw, th, croppedMask, centerX, centerY,
        )
        val finalScore = refined?.score ?: coarseScore
        val finalRoiBox = refined?.let { intArrayOf(it.x, it.y, tw, th) } ?: coarseBox
        val finalMapBox = finalRoiBox?.let {
            intArrayOf(it[0] + searchRoi.x, it[1] + searchRoi.y, it[2], it[3])
        }

        // 5) 裁决走既有 MatchValidation（PathHeatmap.validateGlobalSearch / validateTracking）
        val strategy = MatchStrategyFactory.create(targetZoneId, mode = MatchMode.FORCE_PATH_HEATMAP)
        val raw = MatchResultRaw(
            score = finalScore ?: -1.0,
            locX = (finalRoiBox?.get(0) ?: 0).toDouble(),
            locY = (finalRoiBox?.get(1) ?: 0).toDouble(),
            secondScore = refined?.secondScore ?: -1.0,
            delta = refined?.delta ?: 0.0,
            psr = refined?.psr ?: 0.0,
        )
        val globalAccepted = strategy.validateGlobalSearch(raw)
        val tracking = strategy.validateTracking(
            raw,
            dtSeconds = 0.0,
            lastPos = null,
            searchRect = MapRect(0, 0, sw, sh),
            templCols = tw,
            templRows = th,
        )
        return HeatmapCoarsePathResult(
            ok = finalScore != null,
            overrideOk = overrideOk,
            coarseHit = coarseHit,
            coarseScore = coarseScore,
            coarseBox = coarseBox,
            score = finalScore,
            box = finalMapBox,
            usedRefine = refined != null,
            trackingValid = tracking.isValid,
            globalAccepted = globalAccepted,
            error = if (finalScore == null) "热图路无有效分数" else null,
        )
    }

    /**
     * 粗定位探针识别：在回调里走完「YOLO → 约束 ROI → 地图资产 TemplateMatch」。
     *
     * 任一步失败都如实写进 [debugCoarseResult]，绝不伪造成功。
     */
    private val debugCliCoarseCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, _, _, _, _, _, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null) {
            debugCoarseResult = listOf("error: 探针回调缺少 context")
            return@MaaCustomRecognitionCallback 0
        }
        val yoloBgr = debugCoarseYoloBgr
        val templateBgr = debugCoarseTemplateBgr
        if (yoloBgr == null || templateBgr == null) {
            debugCoarseResult = listOf("error: 探针缺少预处理图/模板图")
            return@MaaCustomRecognitionCallback 0
        }
        var yoloBuf: Pointer? = null
        var mapBuf: Pointer? = null
        var templateBuf: Pointer? = null
        val keepAlive = mutableListOf<Memory>()
        try {
            // 1) YOLO 分类
            yoloBuf = lib.MaaImageBufferCreate()
            if (yoloBuf == null || !setRawBgr(
                    lib, yoloBuf, yoloBgr, YoloPreprocess.OUTPUT_SIZE, YoloPreprocess.OUTPUT_SIZE, keepAlive,
                )
            ) {
                debugCoarseResult = listOf("error: MaaImageBufferSetRawData(128x128) 失败")
                return@MaaCustomRecognitionCallback 0
            }
            val classifyOverride = YoloClassifySupport.buildClassifyOverride(
                nodeName = YoloClassifySupport.DEFAULT_PROBE_NODE,
                model = YoloClassifySupport.DEFAULT_MODEL,
                labels = debugCoarseConfig.classes,
            )
            val yoloRes = runRecognitionOnce(
                lib, context, yoloBuf, YoloClassifySupport.DEFAULT_PROBE_NODE, classifyOverride,
            ) ?: run {
                debugCoarseResult = listOf("error: NeuralNetworkClassify 调用失败（cls.onnx 未加载？）")
                return@MaaCustomRecognitionCallback 0
            }
            val parsed = YoloClassifySupport.parseClassifyDetail(yoloRes.detailJson) ?: run {
                val detail = yoloRes.detailJson?.take(400) ?: "null"
                debugCoarseResult = listOf("error: 无法解析识别详情：$detail")
                return@MaaCustomRecognitionCallback 0
            }
            val coarse = YoloClassifySupport.resolveClassify(parsed, debugCoarseConfig, debugCoarseMapping)

            val selector = debugCoarseZoneSelector
            val expectedZoneId = MapLocatorPure.normalizeExpectedZoneId(selector) {
                debugCoarseMapping.convertYoloNameToZoneId(it)
            }
            val targetZoneId = expectedZoneId.ifEmpty { coarse.zoneId }
            if (!coarse.valid) {
                debugCoarseResult = listOf(
                    "yolo: index=${parsed.clsIndex} class=${coarse.rawClass} valid=false",
                    "error: YOLO 分类无效（cls_index 越界）",
                )
                return@MaaCustomRecognitionCallback 0
            }
            if (coarse.isNone || targetZoneId.isEmpty() || targetZoneId == "None") {
                debugCoarseResult = listOf(
                    "yolo: index=${parsed.clsIndex} class=${coarse.rawClass} zone=${coarse.zoneId} conf=${coarse.confidence}",
                    "result: NONE（本帧没有可定位区域）",
                )
                return@MaaCustomRecognitionCallback 0
            }
            val mapFile = debugCoarseMapIndex[targetZoneId]
            if (mapFile == null) {
                val sample = debugCoarseMapIndex.keys.take(8).joinToString(", ")
                debugCoarseResult = listOf(
                    "zone: $targetZoneId",
                    "error: 地图资产里没有这个 zone（已加载 ${debugCoarseMapIndex.size} 个；例如 $sample）",
                )
                return@MaaCustomRecognitionCallback 0
            }
            val map = decodeBgr(mapFile) ?: run {
                debugCoarseResult = listOf("error: 地图资产解码失败：${mapFile.absolutePath}")
                return@MaaCustomRecognitionCallback 0
            }

            // 2) 约束 ROI（buildSearchConstraint）→ startGlobalSearch 的最终搜索矩形
            val constraint = buildSearchConstraint(
                expectedZoneSelector = selector,
                targetZoneId = targetZoneId,
                coarse = coarse,
                zones = mapOf(targetZoneId to MapDimensions(map.width, map.height)),
            )
            val searchRoi = MapLocatorCoarsePure.constrainedSearchRoi(
                constraint, map.width, map.height, debugCoarseTemplateW, debugCoarseTemplateH,
            ) ?: run {
                debugCoarseResult = listOf(
                    "zone: $targetZoneId",
                    "constraint: mode=${constraint.mode} yolo_validated=${constraint.yoloValidated} roi=${constraint.roi}",
                    "error: 搜索 ROI 裁到地图边界后为空",
                )
                return@MaaCustomRecognitionCallback 0
            }
            if (!constraint.yoloValidated) {
                debugCoarseResult = listOf(
                    "zone: $targetZoneId",
                    "yolo: class=${coarse.rawClass} zone=${coarse.zoneId} conf=${coarse.confidence}",
                    "error: YOLO 约束未通过（zone 不匹配 selector，或 ROI 缺失）",
                )
                return@MaaCustomRecognitionCallback 0
            }

            // 3) 地图资产作 image、小地图裁剪作运行时模板、ROI 约束搜索窗
            templateBuf = lib.MaaImageBufferCreate()
            if (templateBuf == null || !setRawBgr(
                    lib, templateBuf, templateBgr, debugCoarseTemplateW, debugCoarseTemplateH, keepAlive,
                )
            ) {
                debugCoarseResult = listOf("error: MaaImageBufferSetRawData(template) 失败")
                return@MaaCustomRecognitionCallback 0
            }
            val overrideOk = lib.MaaContextOverrideImage(context, DEBUG_COARSE_TEMPLATE, templateBuf).toInt() != 0

            mapBuf = lib.MaaImageBufferCreate()
            if (mapBuf == null || !setRawBgr(lib, mapBuf, map.bytes, map.width, map.height, keepAlive)) {
                debugCoarseResult = listOf("error: MaaImageBufferSetRawData(map) 失败")
                return@MaaCustomRecognitionCallback 0
            }

            val nodeOverride = MapLocatorProbeSupport.buildTemplateMatchOverride(
                nodeName = DEBUG_COARSE_TM_NODE,
                templateName = DEBUG_COARSE_TEMPLATE,
                method = MapLocatorProbeSupport.DEFAULT_METHOD,
                greenMask = false,
                threshold = COARSE_MATCH_THRESHOLD,
                roi = intArrayOf(searchRoi.x, searchRoi.y, searchRoi.width, searchRoi.height),
            )
            val tmRes = runRecognitionOnce(lib, context, mapBuf, DEBUG_COARSE_TM_NODE, nodeOverride)
            val box = tmRes?.box
            val hit = tmRes?.hit == true
            val score = MapLocatorCoarsePure.bestMatchScore(tmRes?.detailJson)
            val outcome = MapLocatorCoarsePure.evaluateCoarseOutcome(hit, box, map.width, map.height, searchRoi)
            val scale = zoneTemplateScale(targetZoneId)
            val boxText = box?.let { "[${it.joinToString(",")}]" } ?: "null"

            // PathHeatmap 并行路（与灰度路并列，不替换灰度路——灰度路留作回退）。
            // 裁决内部走既有 MatchValidation 的 PathHeatmapMatchStrategy。
            val generation = MapLocatorHeatmapPipeline.assetGeneration(mapFile.length(), mapFile.lastModified())
            val heatmap = runCatching {
                runHeatmapCoarsePath(
                    lib, context, map, templateBgr,
                    debugCoarseTemplateW, debugCoarseTemplateH,
                    targetZoneId, searchRoi, generation, box, keepAlive,
                    heatmapCache = heatmapSearchCache,
                    tmNode = DEBUG_COARSE_HEATMAP_TM_NODE,
                    tmTemplate = DEBUG_COARSE_HEATMAP_TEMPLATE,
                )
            }.getOrElse { heatmapFailure("${it.javaClass.simpleName}: ${it.message}") }
            val heatmapLine = if (heatmap.ok || heatmap.coarseScore != null) {
                val cBox = heatmap.coarseBox?.let { "[${it.joinToString(",")}]" } ?: "null"
                val fBox = heatmap.box?.let { "[${it.joinToString(",")}]" } ?: "null"
                "heatmap: override=${if (heatmap.overrideOk) "ok" else "FAILED"} " +
                    "coarse_hit=${heatmap.coarseHit} coarse_score=${heatmap.coarseScore ?: "-"} coarse_box=$cBox " +
                    "refine=${if (heatmap.usedRefine) "(b)" else "fallback(a)"} " +
                    "score=${heatmap.score ?: "-"} box=$fBox " +
                    "tracking_valid=${heatmap.trackingValid ?: "-"} global_accepted=${heatmap.globalAccepted ?: "-"}"
            } else {
                "heatmap: error: ${heatmap.error}"
            }

            // 供 tracklocate 喂追踪状态机的本帧观测：优先热图路（精排分/框），否则灰度路命中框。
            // box 是地图坐标下模板左上角 [x,y,w,h]，MapPosition 取模板中心。
            debugCoarseObservation = run {
                val hmBox = heatmap.box
                val hmScore = heatmap.score
                if (hmScore != null && hmBox != null && hmBox.size >= 4) {
                    MapPosition(
                        zoneId = targetZoneId,
                        x = hmBox[0] + hmBox[2] / 2.0,
                        y = hmBox[1] + hmBox[3] / 2.0,
                        score = hmScore,
                    )
                } else if (hit && box != null && box.size >= 4 && score != null) {
                    MapPosition(
                        zoneId = targetZoneId,
                        x = box[0] + box[2] / 2.0,
                        y = box[1] + box[3] / 2.0,
                        score = score,
                    )
                } else {
                    null
                }
            }

            // 两条路（灰度模板 / 路径热图）任一被接受即算定位成功；输出里标明是哪条过的，
            // 免得只看 `result:` 的人以为整体失败（灰度路对这类雷达小地图天然不命中）。
            val heatmapPass = heatmap.ok && heatmap.globalAccepted != null
            val overallPass = outcome.hit || heatmapPass
            debugCoarseResult = listOf(
                "zone selector: ${selector.ifBlank { "-" }}",
                "yolo: index=${parsed.clsIndex} class=${coarse.rawClass} zone=${coarse.zoneId} " +
                    "valid=${coarse.valid} is_none=${coarse.isNone} conf=${coarse.confidence}",
                "target zone: $targetZoneId (map ${map.width}x${map.height}, template_scale=$scale)",
                "tile roi: " + if (coarse.hasRoi) {
                    "[${coarse.roiX},${coarse.roiY},${coarse.roiW},${coarse.roiH}] infer_margin=${coarse.inferMargin}"
                } else {
                    "- (无 tile ROI，走全图细搜)"
                },
                "constraint: mode=${constraint.mode} yolo_validated=${constraint.yoloValidated} roi=${constraint.roi}",
                "search roi: [${searchRoi.x},${searchRoi.y},${searchRoi.width},${searchRoi.height}]",
                "template override: ${if (overrideOk) "ok" else "FAILED"}",
                "hit: $hit",
                "score: ${score ?: "-"}",
                "box: $boxText",
                "in_map: ${outcome.inMap}",
                "in_roi: ${outcome.inRoi}",
                "result: " + when {
                    outcome.hit -> "PASS（灰度路）"
                    heatmapPass -> "PASS（热图路 score=${heatmap.score ?: "-"}）"
                    else -> "FAIL"
                },
                heatmapLine,
            )

            RunDiagnostics.note(
                "maplocator",
                "coarselocate｜" + when {
                    outcome.hit -> "命中（灰度路）"
                    heatmapPass -> "命中（热图路 ${heatmap.score ?: "-"}）"
                    else -> "未命中"
                } + " zone=$targetZoneId class=${coarse.rawClass}",
                mapOf(
                    "stage" to "coarse_locate",
                    "zone_id" to targetZoneId,
                    "class" to coarse.rawClass,
                    "target_zone_selector" to selector,
                    "cls_index" to parsed.clsIndex,
                    "confidence" to coarse.confidence,
                    "has_roi" to coarse.hasRoi,
                    "constraint_mode" to constraint.mode.name,
                    "yolo_validated" to constraint.yoloValidated,
                    "search_roi" to listOf(searchRoi.x, searchRoi.y, searchRoi.width, searchRoi.height),
                    "map_size" to listOf(map.width, map.height),
                    "template_scale" to scale,
                    "override_ok" to overrideOk,
                    "hit" to hit,
                    "score" to score,
                    "box" to box?.toList(),
                    "in_map" to outcome.inMap,
                    "in_roi" to outcome.inRoi,
                    "heatmap_ok" to heatmap.ok,
                    "heatmap_override_ok" to heatmap.overrideOk,
                    "heatmap_coarse_hit" to heatmap.coarseHit,
                    "heatmap_coarse_score" to heatmap.coarseScore,
                    "heatmap_coarse_box" to heatmap.coarseBox?.toList(),
                    "heatmap_used_refine" to heatmap.usedRefine,
                    "heatmap_score" to heatmap.score,
                    "heatmap_box" to heatmap.box?.toList(),
                    "heatmap_tracking_valid" to heatmap.trackingValid,
                    "heatmap_global_accepted" to heatmap.globalAccepted,
                    "heatmap_error" to heatmap.error,
                ),
            )
            Ln.i(
                "MaaRunner: coarselocate zone=$targetZoneId class=${coarse.rawClass} hit=$hit " +
                    "box=$boxText in_map=${outcome.inMap} in_roi=${outcome.inRoi} | $heatmapLine",
            )
            if (outcome.hit) 1 else 0
        } catch (t: Throwable) {
            debugCoarseResult = listOf("error: ${t.javaClass.simpleName}: ${t.message}")
            Ln.e("MaaRunner: coarselocate 异常", t)
            0
        } finally {
            if (yoloBuf != null) lib.MaaImageBufferDestroy(yoloBuf)
            if (mapBuf != null) lib.MaaImageBufferDestroy(mapBuf)
            if (templateBuf != null) lib.MaaImageBufferDestroy(templateBuf)
        }
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
                // 任务级瞬态：据点交易的「已尝试/缺货/保留」不能跨任务残留
                resetOutpostTransientState()
                // 失败收集表也是任务级：上一次若在 Finish 前中断，残留会污染本次汇总
                failureCollectorSession.resetAll()
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
                    resetOutpostTransientState()
                    failureCollectorSession.resetAll()
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
            // 运行中排队的调试节点：此刻主 tasker 已空闲，独占执行不会与任何任务并发
            runCatching { drainDeferredDebugRuns() }
                .onFailure { Ln.w("MaaRunner: drain deferred debug runs failed: ${it.message}") }
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
        // YOLO 分类模型（cls.onnx）趁任务还没跑、resource 空闲时先登记好：debug 探针在运行中
        // 会用，真实 MapLocateAssertLocation 也在运行中（回调里）用；运行中再 PostBundle 会改
        // resource（框架 res manager 无锁）与任务竞争。release 也要预加载，否则真实现拿不到模型。
        // 未装 map-locate 补充包时 preloadYoloClassifyBundle 内部直接跳过（只记日志）。
        preloadYoloClassifyBundle(lib)
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

    // ───────────────────────── failurecollector（采集/环境监测路线执行器）─────────────────────────
    // 此前三个名字都在 noop 名单里。`FailureCollectorRunTask` 是采集（AutoCollect，82 处）
    // 与环境监测全部 Job 节点的**路线执行器**：noop 之后子任务根本没跑、失败也不记录，
    // 而外层看到的是成功——采集「跑通了实际没动」的根因。三个动作全部对齐上游
    // `failurecollector/action.go` 的真实语义。

    /**
     * `MaaContextRunTask` 是同步执行，用 tasker 查任务状态即可判定子任务成败。
     * 上游要求 `err == nil && detail != nil && detail.Status.Success()`：拿不到 tasker
     * 就无从确认成功，一律按未成功处理（宁可不静默成功）。
     */
    private fun subTaskSucceeded(lib: MaaFrameworkLibrary, context: Pointer, taskId: Long): Boolean {
        if (taskId <= 0L) return false
        val tasker = lib.MaaContextGetTasker(context) ?: return false
        return lib.MaaTaskerStatus(tasker, taskId) == MaaStatus.SUCCEEDED
    }

    /** `FailureCollectorReset`：清空该 key 的失败表（上游 ResetAction）。 */
    private val failureCollectorResetCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, customActionParam, _, _, _ ->
        val param = FailureCollectorSupport.parseParam(MaaJsonTree.parse(customActionParam)) ?: run {
            Ln.w("MaaRunner: FailureCollectorReset [$nodeName] 参数不合法: $customActionParam")
            return@MaaCustomActionCallback 0
        }
        failureCollectorSession.reset(param.key)
        Ln.i("MaaRunner: FailureCollectorReset [$nodeName] key='${param.key}' cleared")
        1
    }

    /**
     * `FailureCollectorRunTask`：跑目标子任务；失败则记录 `failure_task`，可选跑
     * `recovery_task`。**动作本身始终返回成功**——失败留给 Finish 汇总，外层流水线
     * 必须能继续走完（上游 RunTaskAction 语义）。
     */
    private val failureCollectorRunTaskCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        val param = FailureCollectorSupport.parseParam(MaaJsonTree.parse(customActionParam)) ?: run {
            Ln.w("MaaRunner: FailureCollectorRunTask [$nodeName] 参数不合法: $customActionParam")
            return@MaaCustomActionCallback 0
        }
        if (param.task.isEmpty() || param.failureTask.isEmpty()) {
            Ln.w("MaaRunner: FailureCollectorRunTask [$nodeName] 缺 task/failure_task: $customActionParam")
            return@MaaCustomActionCallback 0
        }
        // 目标节点显式 enabled=false 时跳过（上游 GetNode 的禁用判定）
        if (FailureCollectorSupport.isNodeDisabled(nodeDefinitionJson(lib, context, param.task))) {
            Ln.i("MaaRunner: FailureCollectorRunTask [$nodeName] 目标节点已被禁用，跳过 task='${param.task}'")
            return@MaaCustomActionCallback 1
        }
        val taskId = lib.MaaContextRunTask(context, param.task, "{}")
        if (subTaskSucceeded(lib, context, taskId)) {
            Ln.i("MaaRunner: FailureCollectorRunTask [$nodeName] task='${param.task}' 成功")
            return@MaaCustomActionCallback 1
        }
        failureCollectorSession.record(param.key, param.failureTask)
        Ln.e(
            "MaaRunner: FailureCollectorRunTask [$nodeName] task='${param.task}' 失败，" +
                "已记录 failure_task='${param.failureTask}' (key='${param.key}')",
        )
        if (param.recoveryTask.isNotEmpty()) {
            val recoveryId = lib.MaaContextRunTask(context, param.recoveryTask, "{}")
            if (!subTaskSucceeded(lib, context, recoveryId)) {
                Ln.e("MaaRunner: FailureCollectorRunTask [$nodeName] recovery_task='${param.recoveryTask}' 未成功")
            }
        }
        1
    }

    /**
     * `FailureCollectorFinish`：取出并删除失败表，逐个跑失败播报任务（播报自身失败只告警），
     * 返回「是否有失败」——**有失败即失败**，不能恒成功。
     */
    private val failureCollectorFinishCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, customActionParam, _, _, _ ->
        val param = FailureCollectorSupport.parseParam(MaaJsonTree.parse(customActionParam)) ?: run {
            Ln.w("MaaRunner: FailureCollectorFinish [$nodeName] 参数不合法: $customActionParam")
            return@MaaCustomActionCallback 0
        }
        val failures = failureCollectorSession.finish(param.key)
        val lib = MaaFrameworkLoader.library
        if (lib != null && context != null) {
            for (failureTask in failures) {
                val taskId = lib.MaaContextRunTask(context, failureTask, "{}")
                if (!subTaskSucceeded(lib, context, taskId)) {
                    Ln.e("MaaRunner: FailureCollectorFinish [$nodeName] 失败播报任务 '$failureTask' 未成功")
                }
            }
        } else if (failures.isNotEmpty()) {
            Ln.w("MaaRunner: FailureCollectorFinish [$nodeName] 无法执行失败播报（lib/context 为空）")
        }
        Ln.i(
            "MaaRunner: FailureCollectorFinish [$nodeName] key='${param.key}' " +
                "failures=${failures.size} failures=$failures",
        )
        if (FailureCollectorSupport.finishSucceeds(failures)) 1 else 0
    }

    // ───────────────── SeizeDeliveryJobs（抢占送货任务）─────────────────
    // 两个识别原先在 falseRecognitions 里恒假、两个动作在 otherActions 里 noop-success，
    // 整条链（默认价格筛选路径 + 指定送达点扫描路径）在移动端走不通。这里对齐上游
    // `agent/go-service/seizedeliveryjobs/{find_target,scan_target}.go` 换成真实实现；
    // 纯逻辑（奖励解析/链式偏移/候选过滤去重排序/扫描会话）在 [SeizeDeliverySupport]（已单测）。

    /** 从 `__SeizeDeliveryJobsMinReward` 节点定义读价格下限（expected 由 tasks 覆写）。 */
    private fun readSeizeMinReward(lib: MaaFrameworkLibrary, context: Pointer): Double? {
        val nodeJson = nodeDefinitionJson(lib, context, SeizeDeliverySupport.MIN_REWARD_NODE) ?: return null
        return SeizeDeliverySupport.parseMinReward(nodeJson)
    }

    /** 在指定 ROI 跑一个 OCR 子节点并取 `filtered[0]`（上游 `ocrFirst`，沿用当前截图）。 */
    private fun runSeizeOcrFirst(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        node: String,
        roi: SeizeDeliverySupport.Box,
    ): SeizeDeliverySupport.FilteredHit? {
        val res = runRecognitionOnce(lib, context, image, node, SeizeDeliverySupport.roiOverrideJson(node, roi)) ?: return null
        if (!res.hit) return null
        return SeizeDeliverySupport.parseFilteredFirst(MaaJsonTree.parse(res.detailJson))
    }

    /**
     * 上游 `scanJobs`：以调度券锚点（单节点多模板、多 box）为起点，链式 OCR
     * 价格/出发地/接取/查看位置，再过滤/去重/排序出达标委托。
     */
    private fun scanSeizeJobs(
        lib: MaaFrameworkLibrary,
        context: Pointer,
        image: Pointer,
        minReward: Double,
    ): List<SeizeDeliverySupport.JobItem> {
        val token = runRecognitionOnce(lib, context, image, SeizeDeliverySupport.COMMISSION_TOKEN_NODE)
        if (token == null || !token.hit) return emptyList()
        val anchors = SeizeDeliverySupport.parseFilteredBoxes(MaaJsonTree.parse(token.detailJson))
        if (anchors.isEmpty()) return emptyList()

        val rows = ArrayList<SeizeDeliverySupport.RowObservation>(anchors.size)
        for (anchor in anchors) {
            val rewardRoi = SeizeDeliverySupport.offsetBox(anchor, SeizeDeliverySupport.OFFSET_TOKEN_TO_REWARD) ?: continue
            val reward = runSeizeOcrFirst(lib, context, image, SeizeDeliverySupport.REWARD_NODE, rewardRoi) ?: continue
            val rewardBox = reward.box ?: continue
            val originRoi = SeizeDeliverySupport.offsetBox(rewardBox, SeizeDeliverySupport.OFFSET_REWARD_TO_ORIGIN) ?: continue
            val acceptRoi = SeizeDeliverySupport.offsetBox(rewardBox, SeizeDeliverySupport.OFFSET_REWARD_TO_ACCEPT) ?: continue
            val viewRoi = SeizeDeliverySupport.offsetBox(rewardBox, SeizeDeliverySupport.OFFSET_REWARD_TO_VIEW) ?: continue
            val origin = runSeizeOcrFirst(lib, context, image, SeizeDeliverySupport.ORIGIN_NODE, originRoi)
            val accept = runSeizeOcrFirst(lib, context, image, SeizeDeliverySupport.ACCEPT_NODE, acceptRoi)
            val view = runSeizeOcrFirst(lib, context, image, SeizeDeliverySupport.VIEW_LOCATION_NODE, viewRoi)
            rows += SeizeDeliverySupport.RowObservation(
                rewardText = reward.text,
                rewardBox = rewardBox,
                originText = origin?.text,
                originBox = origin?.box,
                acceptBox = accept?.box,
                viewLocationBox = view?.box,
            )
        }
        return SeizeDeliverySupport.assembleJobs(rows, minReward)
    }

    /** `SeizeDeliveryJobsFindTargetRecognition`：默认路径，返回列表最上达标委托的接取按钮框。 */
    private val seizeDeliveryFindTargetCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, _, image, _, _, outBox, outDetail ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            val minReward = readSeizeMinReward(lib, context) ?: run {
                Ln.e("MaaRunner: SeizeDeliveryJobsFindTarget [$nodeName] 读不到价格下限")
                return@MaaCustomRecognitionCallback 0
            }
            val items = scanSeizeJobs(lib, context, image, minReward)
            val box = SeizeDeliverySupport.findTargetBox(items) ?: run {
                Ln.i("MaaRunner: SeizeDeliveryJobsFindTarget [$nodeName] 无达标委托 (min=$minReward)")
                return@MaaCustomRecognitionCallback 0
            }
            if (outBox != null) lib.MaaRectSet(outBox, box.x, box.y, box.w, box.h)
            // 上游返回 Detail 标记；现 MaaStringBufferSet 已绑定，照写（管线不消费，仅可观测）。
            if (outDetail != null) {
                runCatching { lib.MaaStringBufferSet(outDetail, SeizeDeliverySupport.FIND_TARGET_DETAIL) }
                    .onFailure { Ln.w("MaaRunner: SeizeDeliveryJobsFindTarget 写 detail 失败：${it.message}") }
            }
            Ln.i("MaaRunner: SeizeDeliveryJobsFindTarget [$nodeName] -> (${box.x},${box.y},${box.w},${box.h}) matched=${items.size}")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: SeizeDeliveryJobsFindTarget error on node=$nodeName", t)
            0
        }
    }

    /** `SeizeDeliveryJobsScanTargetRecognition`：指定终点路径，首次扫描缓存整列，后续直接命中。 */
    private val seizeDeliveryScanTargetCallback = MaaFrameworkLibrary.MaaCustomRecognitionCallback { context, _, nodeName, _, _, image, roi, _, outBox, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomRecognitionCallback 0
        if (context == null || image == null) return@MaaCustomRecognitionCallback 0
        try {
            if (!seizeDeliveryScanSession.isScanned) {
                val minReward = readSeizeMinReward(lib, context) ?: run {
                    Ln.e("MaaRunner: SeizeDeliveryJobsScanTarget [$nodeName] 读不到价格下限")
                    return@MaaCustomRecognitionCallback 0
                }
                val items = scanSeizeJobs(lib, context, image, minReward)
                if (items.isEmpty()) {
                    Ln.i("MaaRunner: SeizeDeliveryJobsScanTarget [$nodeName] 无达标委托 (min=$minReward)")
                    return@MaaCustomRecognitionCallback 0
                }
                seizeDeliveryScanSession.store(items)
            }
            // 命中框 = 本节点 roi（上游 `return Box: arg.Roi`；真正点击目标由 action 覆写）。
            if (outBox != null && roi != null) {
                val r = getBoxRect(lib, roi)
                lib.MaaRectSet(outBox, r.x, r.y, r.w, r.h)
            }
            Ln.i("MaaRunner: SeizeDeliveryJobsScanTarget [$nodeName] scanned=${seizeDeliveryScanSession.size} idx=${seizeDeliveryScanSession.index}")
            1
        } catch (t: Throwable) {
            Ln.e("MaaRunner: SeizeDeliveryJobsScanTarget error on node=$nodeName", t)
            0
        }
    }

    /** `SeizeDeliveryJobsScanTargetAction`：覆写当前项查看位置/接取点击目标并前进；用尽返回 false。 */
    private val seizeDeliveryScanTargetActionCallback = MaaFrameworkLibrary.MaaCustomActionCallback { context, _, nodeName, _, _, _, _, _ ->
        val lib = MaaFrameworkLoader.library ?: return@MaaCustomActionCallback 0
        if (context == null) return@MaaCustomActionCallback 0
        val item = seizeDeliveryScanSession.current() ?: run {
            Ln.i("MaaRunner: SeizeDeliveryJobsScanTargetAction [$nodeName] 已用尽，转 ScanExhausted")
            return@MaaCustomActionCallback 0
        }
        val targets = SeizeDeliverySupport.scanTargets(item) ?: run {
            Ln.e("MaaRunner: SeizeDeliveryJobsScanTargetAction [$nodeName] 点击目标框退化")
            return@MaaCustomActionCallback 0
        }
        val json = SeizeDeliverySupport.buildScanOverrideJson(targets)
        if (lib.MaaContextOverridePipeline(context, json).toInt() == 0) {
            Ln.e("MaaRunner: SeizeDeliveryJobsScanTargetAction [$nodeName] 覆写 target 失败")
            return@MaaCustomActionCallback 0
        }
        seizeDeliveryScanSession.advance()
        Ln.i("MaaRunner: SeizeDeliveryJobsScanTargetAction [$nodeName] idx=${seizeDeliveryScanSession.index - 1}/${seizeDeliveryScanSession.size}")
        1
    }

    /** `SeizeDeliveryJobsResetScanStateAction`：清空扫描状态。 */
    private val seizeDeliveryResetScanStateCallback = MaaFrameworkLibrary.MaaCustomActionCallback { _, _, nodeName, _, _, _, _, _ ->
        seizeDeliveryScanSession.reset()
        Ln.i("MaaRunner: SeizeDeliveryJobsResetScanState [$nodeName] 扫描状态已清空")
        1
    }

    private fun registerCustomActions(lib: MaaFrameworkLibrary, res: Pointer) {
        registeredCustomActions.clear()
        registeredCustomRecognitions.clear()
        listCompleteSession.reset()
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
        // DeliveryJobsResolveOngoingDepotAction 原先在 noop 名单里：残留送货分支的 next 从不被设置，
        // 节点空转还报成功 = 静默死路。这里换成真实实现，并已从下面的 otherActions 名单删除。
        // 必须在 noop 循环之前注册，否则会被 noop-success 覆盖。
        regAction(OngoingDeliverySupport.ACTION_NAME, resolveOngoingDepotCallback)
        regAction("CharacterControllerYawDeltaAction", yawDeltaCallback)
        regAction("CharacterControllerPitchDeltaAction", pitchDeltaCallback)
        regAction("CharacterControllerForwardAxisAction", forwardAxisCallback)
        regAction("CharacterControllerRelativeMoveAction", relativeMoveCallback)
        regAction("CharacterMoveToTargetAction", moveToTargetCallback)
        regAction("CharacterMoveToTargetNotFoundAction", moveToTargetNotFoundCallback)
        regAction("CharacterSearchAction", characterSearchCallback)
        // CameraScanAction：环境监测「扫描拍照视角」的真实实现，缺它整任务硬失败；
        // 不在下面任何 noop 名单里，注册必须在 noop 循环之前。
        regAction("CameraScanAction", cameraScanCallback)
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

        // failurecollector：采集与环境监测的路线执行器，必须在 noop 循环之前注册，
        // 且不能再出现在 otherActions 名单里（否则会被 noop-success 覆盖）。
        regAction("FailureCollectorReset", failureCollectorResetCallback)
        regAction("FailureCollectorRunTask", failureCollectorRunTaskCallback)
        regAction("FailureCollectorFinish", failureCollectorFinishCallback)

        // SeizeDeliveryJobs：两个识别原先恒假、两个动作原先 noop-success，整条链走不通。
        // 换成真实实现，并已从下面的 otherActions 与 falseRecognitions 名单删除（否则会被 noop 覆盖）。
        regReco("SeizeDeliveryJobsFindTargetRecognition", seizeDeliveryFindTargetCallback)
        regReco("SeizeDeliveryJobsScanTargetRecognition", seizeDeliveryScanTargetCallback)
        regAction("SeizeDeliveryJobsScanTargetAction", seizeDeliveryScanTargetActionCallback)
        regAction("SeizeDeliveryJobsResetScanStateAction", seizeDeliveryResetScanStateCallback)

        val otherActions = listOf(
            "CreditShoppingScanItemAction",
            "AddItemData",
            "SyncItemData",
            "UpdateItemQuantity",
            // DeliveryJobsResolveOngoingDepotAction 不在此列：已注册为真实实现
            // AutoDeliveryResolveDepot/Destination 不在此列：已注册为真实实现
            "CaptureUid",
            "CloseGameAction",
            "ImageCheckSetResultAction",
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
            // FailureCollector* 不在此列：已注册为真实实现
            "ImportBluePrintsEnterCodeAction",
            "ImportBluePrintsFinishAction",
            "ImportBluePrintsInitTextAction",

            "OutpostTradingLocationPlan",
            // OutpostTradingPrioritySession / OutpostTradingReserveSession 不在此列：
            // 已显式注册为 outpostPrioritySessionCallback / outpostReserveSessionCallback
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
        // 路线 (b+) 前提验证探针：合成图 + OverrideImage + TemplateMatch（见 debugOverrideProbe）。
        // 仅 debug 注册，release 下不引入这个自定义识别（与 debug CLI 同样的硬门控）。
        if (BuildConfig.DEBUG) {
            regReco(DEBUG_OVERRIDE_RECO, debugCliOverrideCallback)
            // YOLO 分区分类探针（yoloprobe）：预处理图 → NeuralNetworkClassify(cls.onnx)
            regReco(DEBUG_YOLO_RECO, debugCliYoloCallback)
            // 端到端粗定位探针（coarselocate）：YOLO → 约束 ROI → 地图资产 TemplateMatch
            regReco(DEBUG_COARSE_RECO, debugCliCoarseCallback)
            // 世界地图找图标探针（mapfind）：全屏大地图上 SolveViewport → 投屏 → ConfirmSpot
            regReco(DEBUG_MAPFIND_RECO, debugCliMapFindCallback)
        }
        // ItemQuantitySatisfied 不再是恒真：恒真会让采集「目标库存模式」的所有 SubSkipped
        // 直接命中，任务成功结束却什么都没采（见 ItemQuantitySupport 注释）。
        regReco("ItemQuantitySatisfied", itemQuantitySatisfiedCallback)
        regReco("ItemDataReady", noopTrueRecognitionCallback)
        regReco("AutoSellScanItemRecognition", autoSellScanItemRecognitionCallback)
        regReco("ExpressionRecognition", expressionRecognitionCallback)
        regReco("OutpostTradingPriorityItem", outpostTradingPriorityItemCallback)
        regReco("OutpostTradingCurrentGoods", outpostTradingCurrentGoodsCallback)
        regAction("OutpostTradingPrioritySession", outpostPrioritySessionCallback)
        regAction("OutpostTradingReserveSession", outpostReserveSessionCallback)
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
        // MapLocateAssertLocation 不再是恒假：它是「采集（AutoCollect）」整条链的定位门控
        // （71 个管线文件引用，上游语义是断言「我在地图上的某个位置」）。真实现走已打通的
        // 小地图定位原语 + 追踪状态机；未装 map-locate 补充包 / 定位失败时如实返回 0。
        regReco("MapLocateAssertLocation", mapLocateAssertLocationCallback)

        // MapFind（世界地图找图标，上游 WorldMap/WorldMapFind.cpp）已是真实实现：
        // 多尺度 TemplateMatch 解屏幕↔底图相似变换 → 目标底图坐标投屏 → 图标模板确认 →
        // 交回屏幕点框。解不出/置信不足一律如实返回 0（见 mapFindRun 的失败护栏）。
        regReco("MapFind", mapFindCallback)

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
            "PuzzleRecognition",
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
            listCompleteSession.reset()
            callbackKeepAlive.clear()
            lib.MaaResourceDestroy(res)
        }
        resource = null
        loadedResourcePaths = emptyList()
        // resource 换了，YOLO 分类 bundle 的登记与 MapLocate 资产缓存都要重来
        ensuredYoloBundleFor = null
        mapLocateAssetsFor = null
        mapLocateAssets = null
        mapLocateAssertHeatmapCache.clear()
        // resource 换了，世界地图的底图/图标表缓存也要重来
        worldMapAssetsFor = null
        worldMapIconTable = emptyMap()
        worldMapTemplateDir = null
        worldMapBaseCache.clear()
        worldMapTemplateCache.clear()
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

        /** 路线 (b+) 前提验证探针：临时节点名与注册的识别名 */
        const val DEBUG_OVERRIDE_NODE = "__DebugCliOverride__"
        const val DEBUG_OVERRIDE_RECO = "DebugCliOverride"

        /** YOLO 分区分类探针：临时 Custom 包装节点名与注册的识别名 */
        const val DEBUG_YOLO_NODE = "__DebugCliYolo__"
        const val DEBUG_YOLO_RECO = "DebugCliYolo"

        /** 端到端粗定位探针：外层 Custom 包装节点 / 内层 TemplateMatch 节点 / 注册识别名 */
        const val DEBUG_COARSE_NODE = "__DebugCliCoarse__"
        const val DEBUG_COARSE_RECO = "DebugCliCoarse"
        const val DEBUG_COARSE_TM_NODE = "__DebugCliCoarseTemplateMatch"
        const val DEBUG_COARSE_TEMPLATE = "__DebugCliCoarse/minimap.png"

        /** 世界地图找图标探针（mapfind）：临时 Custom 包装节点名与注册识别名 */
        const val DEBUG_MAPFIND_NODE = "__DebugCliMapFind__"
        const val DEBUG_MAPFIND_RECO = "DebugCliMapFind"

        /**
         * PathHeatmap 并行路的临时 TemplateMatch 节点与运行时模板名（与灰度路分开，
         * 避免覆盖灰度路正在用的模板）。
         */
        const val DEBUG_COARSE_HEATMAP_TM_NODE = "__DebugCliCoarseHeatmapTemplateMatch"
        const val DEBUG_COARSE_HEATMAP_TEMPLATE = "__DebugCliCoarse/heatmap.png"

        /** 粗定位 TemplateMatch 的阈值；对齐上游 loc_threshold 默认 0.55。 */
        const val COARSE_MATCH_THRESHOLD = 0.55

        /**
         * `MapLocateAssertLocation` 真实实现的回退开关。
         *
         * `false` 时识别退化为恒假（与改造前一致），用于真机出现回归时一键回退。
         * 默认开；真机失败时会如实返回 0，不会伪造命中。
         */
        const val MAP_LOCATE_ASSERT_REAL_ENABLED = true

        /** 真实实现用的临时 TemplateMatch 节点/运行时模板名（与 debug 探针分开，避免覆盖表串味）。 */
        const val MAP_LOCATE_ASSERT_TM_NODE = "__MapLocateAssertTemplateMatch"
        const val MAP_LOCATE_ASSERT_TEMPLATE = "__MapLocateAssert/minimap.png"
        const val MAP_LOCATE_ASSERT_HEATMAP_TM_NODE = "__MapLocateAssertHeatmapTemplateMatch"
        const val MAP_LOCATE_ASSERT_HEATMAP_TEMPLATE = "__MapLocateAssert/heatmap.png"

        /**
         * `MapFind` 真实实现的回退开关。
         *
         * `false` 时识别退化为恒假（与改造前一致），真机出现回归时一键回退。
         * 默认开；解不出来时如实返回 0，不会伪造坐标。
         */
        const val MAP_FIND_REAL_ENABLED = true

        /** `MapFind` 用的临时 `TemplateMatch` 节点 / 运行时模板名（与 MapLocate 分开，避免覆盖表串味）。 */
        const val MAP_FIND_TM_NODE = "__MapFindTemplateMatch"
        const val MAP_FIND_TEMPLATE = "__MapFind/patch.png"

        /** 进大地图先缩放到底：交给 pipeline 的子任务（上游 `kZoomOutNode`）。 */
        const val MAP_FIND_ZOOM_OUT_NODE = "SceneMapZoomOutWithoutReco"

        /** 地图资产扩展名（框架 `imread` 可读的常见几种；上游资产只有 png）。 */
        val MAP_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")


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

/**
 * 「排队执行」的调试探针结果（运行中提交的 `run`，任务结束后补跑）。
 *
 * 供 CLI `probe-result` 读回：`command` 是命令描述，`lines` 是给用户看的多行结果。
 */
data class DebugProbeOutcome(
    val command: String,
    val lines: List<String>,
)
