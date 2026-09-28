package com.aliothmoon.maafw.remote

/**
 * `MapNavigateAction` 的参数解析（对齐上游 `MapNavigator/navi_param_parser.cpp`）。
 *
 * 上游的路点有两种形态，语义差别不小，而且**解析失败要整节点失败**——
 * 不能「跳过这个点继续」：
 *
 *  - 对象：`{action|actions, target|x,y, zone_id, strict|strict_arrival, required,
 *    angle|heading|yaw, interact_*, find_*}`
 *  - 数组：`[x, y, ...rest]`，`rest` 里可任意顺序出现 `strict` 布尔、动作字符串/数组、`zone_id` 字符串
 *
 * 数组里那条「看起来像动作的字符串不能当 zone」的规则是有意的：
 * `[688, 350, "SPRINT"]` 一旦被当成 zone_id，这个点就静默走错区域了，
 * 所以宁可整条路线失败。
 *
 * 解析是纯逻辑（吃通用 JSON 树），所以可本地单测；执行编排留在 MaaRunner。
 */
object MapNaviParam {

    /** 上游 navi_domain_types.h:20-59 的 14 种动作。大小写敏感。 */
    enum class ActionType {
        RUN, SPRINT, JUMP, FIGHT, INTERACT, TRANSFER, PORTAL, HEADING,
        NAVMESH, ZONE, COLLECT, DIG, FIND, ZIPLINE,
    }

    /** 归一化后的单个路点。 */
    data class Waypoint(
        var x: Double = 0.0,
        var y: Double = 0.0,
        var action: ActionType = ActionType.RUN,
        /** ZONE / 纯角度 HEADING / 无坐标 FIND 为 false。 */
        var hasPosition: Boolean = true,
        var strictArrival: Boolean = false,
        var authoredStrictArrival: Boolean = false,
        var zoneId: String = "",
        var targetTier: String = "",
        var targetDeckY: Double? = null,
        var routeRequired: Boolean = false,
        var headingUsesTarget: Boolean = false,
        var headingAngle: Double = 0.0,
        var interactText: MutableList<String> = mutableListOf(),
        var interactTextNode: String = "",
        var interactScan: String = "",
        var interactRec: Boolean = false,
        var findTarget: String = "",
        var findText: MutableList<String> = mutableListOf(),
        var findTextNode: String = "",
        var findStop: String = "",
        var findArrive: Pair<Double, Double>? = null,
    ) {
        /** 是否是路线边界（P3 的寻路分段要用；P1 只存）。 */
        fun isRouteBoundary(): Boolean = when (action) {
            ActionType.HEADING, ActionType.ZONE, ActionType.COLLECT,
            ActionType.DIG, ActionType.FIND,
            -> true

            else -> false
        }
    }

    /** 归一化后的整条导航参数。 */
    data class NaviParam(
        var mapName: String = "",
        var path: MutableList<Waypoint> = mutableListOf(),
        var navmeshFile: String = "",
        var navmeshSnapRadius: Double = 5.0,
        var arrivalTimeoutMs: Long = 60_000,
        var sprintThreshold: Double = 16.0,
        var enableLocalDriver: Boolean = true,
        var enableBootstrapNavmesh: Boolean = true,
        var ziplineEnabled: Boolean = false,
    )

    sealed interface Outcome {
        /** 空参数或空 path：上游视为「空操作成功」。 */
        object NoOp : Outcome

        /** 任何一处不合法：整节点失败。 */
        data class Invalid(val reason: String) : Outcome

        data class Ok(val param: NaviParam) : Outcome
    }

    /** 路线级 interact / find 默认值。 */
    private data class TextSpec(val texts: MutableList<String>, val node: String)

    private data class InteractDefaults(
        val text: TextSpec?,
        val scan: String,
        val rec: Boolean,
    )

    private data class FindDefaults(
        val target: String,
        val text: TextSpec?,
        val stop: String,
        val arrive: Pair<Double, Double>?,
        /** 路线级同时给了 target 与 text，只在这种路线里真有 FIND 点时才失败。 */
        val conflicting: Boolean,
    )

    // ───────────────────────── 入口 ─────────────────────────

    fun parseText(raw: String?): Outcome {
        if (raw.isNullOrEmpty()) return Outcome.NoOp
        val tree = MaaJsonTree.parse(raw) ?: return Outcome.Invalid("param 不是合法 JSON")
        return parse(tree)
    }

    fun parse(tree: Any?): Outcome {
        val root = tree as? Map<*, *> ?: return Outcome.Invalid("param 不是对象")

        val param = NaviParam()

        // 根参数：逐字段严格取类型（类型不符即失败，上游同）
        try {
            param.mapName = str(root, "map_name") ?: ""
            param.arrivalTimeoutMs = long(root, "arrival_timeout") ?: 60_000L
            param.sprintThreshold = dbl(root, "sprint_threshold") ?: 16.0
            param.enableLocalDriver = bool(root, "enable_local_driver") ?: true
            param.enableBootstrapNavmesh = bool(root, "enable_bootstrap_navmesh") ?: true
            param.ziplineEnabled = bool(root, "zip") ?: false
            // nav_file 优先级高于 navmesh_file；snap_radius 同
            str(root, "navmesh_file")?.let { param.navmeshFile = it }
            str(root, "nav_file")?.let { param.navmeshFile = it }
            dbl(root, "navmesh_snap_radius")?.let { param.navmeshSnapRadius = it }
            dbl(root, "snap_radius")?.let { param.navmeshSnapRadius = it }
        } catch (t: TypeError) {
            return Outcome.Invalid(t.message ?: "根参数类型不符")
        }

        val routeInteract = try {
            readInteractDefaults(root)
        } catch (t: TypeError) {
            return Outcome.Invalid(t.message ?: "interact 默认值不合法")
        }
        val routeFind = try {
            readFindDefaults(root)
        } catch (t: TypeError) {
            return Outcome.Invalid(t.message ?: "find 默认值不合法")
        }

        var zoneContext = param.mapName
        val pathNode = root["path"]
        if (pathNode != null) {
            if (pathNode !is List<*>) return Outcome.Invalid("path 不是数组")
            for ((index, element) in pathNode.withIndex()) {
                val points = try {
                    parseWaypoint(element, zoneContext)
                } catch (t: TypeError) {
                    return Outcome.Invalid("path[$index] ${t.message}")
                } ?: return Outcome.Invalid("path[$index] 无法解析")
                for (point in points) {
                    param.path += point
                    if (point.action == ActionType.ZONE || (point.hasPosition && point.zoneId.isNotEmpty())) {
                        zoneContext = point.zoneId.ifEmpty { zoneContext }
                    }
                }
            }
        } else if (hasSingleWaypointKeys(root)) {
            val points = try {
                parseWaypoint(root, zoneContext)
            } catch (t: TypeError) {
                return Outcome.Invalid("根级单路点 ${t.message}")
            } ?: return Outcome.Invalid("根级单路点无法解析")
            param.path += points
        }

        if (param.path.isEmpty()) return Outcome.NoOp

        // 路线级默认值只补「没写」的点
        try {
            applyRouteDefaults(param, routeInteract, routeFind)
        } catch (t: TypeError) {
            return Outcome.Invalid(t.message ?: "路线默认值应用失败")
        }
        return Outcome.Ok(param)
    }

    // ───────────────────────── 路点 ─────────────────────────

    /** 返回该元素展开出的路点列表（一个对象可能展开成多个动作点）。 */
    private fun parseWaypoint(element: Any?, zoneContext: String): List<Waypoint>? = when (element) {
        is List<*> -> parseArrayWaypoint(element, zoneContext)
        is Map<*, *> -> parseObjectWaypoint(element, zoneContext)
        else -> null
    }

    /**
     * 上游 navi_param_parser.cpp:467-501。
     *
     * `[x, y, ...rest]`：前两项必须是数字；`rest` 里布尔是 strict、动作字符串/数组是动作、
     * 其它字符串是 zone_id——但「看起来像动作」的字符串一律判失败。
     */
    private fun parseArrayWaypoint(arr: List<*>, zoneContext: String): List<Waypoint>? {
        if (arr.size < 2) return null
        val x = (arr[0] as? Number)?.toDouble() ?: return null
        val y = (arr[1] as? Number)?.toDouble() ?: return null

        var strict = false
        var hasStrict = false
        var zone = ""
        val actions = mutableListOf<ActionType>()

        for (index in 2 until arr.size) {
            when (val element = arr[index]) {
                is Boolean -> {
                    strict = element
                    hasStrict = true
                }

                is String -> {
                    val action = actionOf(element)
                    if (action != null) {
                        actions += action
                    } else if (looksLikeAction(element)) {
                        // 宁可整条失败，也不要把它当 zone 走错区域
                        throw TypeError("第 ${index + 1} 个元素 '$element' 看起来是动作但不是合法动作名")
                    } else {
                        zone = element
                    }
                }

                is List<*> -> {
                    for (item in element) {
                        val action = (item as? String)?.let { actionOf(it) }
                            ?: throw TypeError("第 ${index + 1} 个元素的动作数组里有非法项")
                        actions += action
                    }
                }

                else -> throw TypeError("第 ${index + 1} 个元素类型不支持")
            }
        }

        return expand(
            actions, x, y,
            hasPosition = true, strict = strict, hasStrict = hasStrict,
            zone = zone, zoneContext = zoneContext, extra = null, hasTarget = true,
        )
    }

    /** 上游 navi_param_parser.cpp:426-501 的对象形态。 */
    private fun parseObjectWaypoint(obj: Map<*, *>, zoneContext: String): List<Waypoint>? {
        val actions = mutableListOf<ActionType>()
        for (key in listOf("action", "actions")) {
            val node = obj[key] ?: continue
            collectActions(node, actions)
        }

        val target = obj["target"] as? List<*>
        val x = (obj["x"] as? Number)?.toDouble()
        val y = (obj["y"] as? Number)?.toDouble()
        val hasTarget = target != null && target.size >= 2 &&
            target[0] is Number && target[1] is Number
        val hasXy = x != null && y != null
        val px = if (hasTarget) (target!![0] as Number).toDouble() else x ?: 0.0
        val py = if (hasTarget) (target!![1] as Number).toDouble() else y ?: 0.0

        val angle = firstNumber(obj, "angle", "heading", "yaw")
        val zone = finalString(obj, "zone_id", "zoneId", "zone", "map_name", "mapName").orEmpty()
        val strict = firstBool(obj, "strict", "strict_arrival", "strictArrival") ?: false
        val hasStrict = hasAny(obj, "strict", "strict_arrival", "strictArrival")
        val required = bool(obj, "required") ?: false
        val tier = finalString(obj, "target_tier", "targetTier").orEmpty()
        val deckY = firstNumber(obj, "target_deck_y", "targetDeckY")

        val extra = WaypointExtras(
            required = required,
            tier = tier,
            deckY = deckY,
            angle = angle,
            interact = readInteractPoint(obj),
            find = readFindPoint(obj),
        )

        return expand(
            actions, px, py,
            hasPosition = hasTarget || hasXy,
            strict = strict, hasStrict = hasStrict,
            zone = zone, zoneContext = zoneContext, extra = extra,
            hasTarget = hasTarget,
        )
    }

    private data class WaypointExtras(
        val required: Boolean,
        val tier: String,
        val deckY: Double?,
        val angle: Double?,
        val interact: InteractPoint?,
        val find: FindPoint?,
    )

    private data class InteractPoint(val text: TextSpec?, val scan: String, val rec: Boolean)
    private data class FindPoint(val target: String, val text: TextSpec?, val stop: String, val arrive: Pair<Double, Double>?)

    /**
     * 上游 navi_param_parser.cpp:766-801 + 1024-1114 的展开与校验。
     *
     * 动作列表为空 → 1 个 RUN；含任一非 RUN → 跳过其中的 RUN；坐标缺失但有角度 → 变成 HEADING 点。
     */
    private fun expand(
        actionsIn: List<ActionType>,
        x: Double,
        y: Double,
        hasPosition: Boolean,
        strict: Boolean,
        hasStrict: Boolean,
        zone: String,
        zoneContext: String,
        extra: WaypointExtras?,
        hasTarget: Boolean = true,
    ): List<Waypoint> {
        val resolvedZone = zone.ifEmpty { zoneContext }
        val angle = extra?.angle

        val actions = when {
            actionsIn.isEmpty() -> if (!hasPosition && angle != null) listOf(ActionType.HEADING) else listOf(ActionType.RUN)
            actionsIn.any { it != ActionType.RUN } -> actionsIn.filter { it != ActionType.RUN }
            else -> actionsIn
        }

        return actions.map { action ->
            val wp = baseWaypoint(x, y, hasPosition = true, strict, hasStrict, resolvedZone, extra)
            wp.action = action
            when (action) {
                ActionType.HEADING -> {
                    // HEADING 用 target 或角度二选一
                    if (angle != null) {
                        wp.headingUsesTarget = false
                        wp.headingAngle = angle
                        wp.hasPosition = false
                    } else if (hasPosition) {
                        wp.headingUsesTarget = true
                    } else {
                        throw TypeError("HEADING 既没有 target 也没有角度")
                    }
                }

                ActionType.ZONE -> {
                    if (resolvedZone.isEmpty()) throw TypeError("ZONE 缺少 zone_id")
                    wp.hasPosition = false
                }

                ActionType.NAVMESH -> {
                    // 上游强制严格到达；且坐标必须来自 target（x/y 不算）
                    if (!hasTarget) throw TypeError("NAVMESH 必须用 target 指定坐标")
                    wp.strictArrival = true
                }

                else -> {
                    // 其余动作都需要坐标；无坐标但有角度时按上游退化成 HEADING
                    if (!hasPosition) {
                        if (angle == null) throw TypeError("路点既没有坐标也没有角度")
                        wp.action = ActionType.HEADING
                        wp.headingUsesTarget = false
                        wp.headingAngle = angle
                        wp.hasPosition = false
                    }
                }
            }
            wp
        }
    }

    private fun baseWaypoint(
        x: Double,
        y: Double,
        hasPosition: Boolean,
        strict: Boolean,
        hasStrict: Boolean,
        zone: String,
        extra: WaypointExtras?,
    ) = Waypoint(
        x = x,
        y = y,
        hasPosition = hasPosition,
        strictArrival = strict,
        authoredStrictArrival = hasStrict && strict,
        zoneId = zone,
        targetTier = extra?.tier.orEmpty(),
        targetDeckY = extra?.deckY,
        routeRequired = extra?.required ?: false,
        interactText = extra?.interact?.text?.texts?.toMutableList() ?: mutableListOf(),
        interactTextNode = extra?.interact?.text?.node.orEmpty(),
        interactScan = extra?.interact?.scan.orEmpty(),
        interactRec = extra?.interact?.rec ?: false,
        findTarget = extra?.find?.target.orEmpty(),
        findText = extra?.find?.text?.texts?.toMutableList() ?: mutableListOf(),
        findTextNode = extra?.find?.text?.node.orEmpty(),
        findStop = extra?.find?.stop.orEmpty(),
        findArrive = extra?.find?.arrive,
    )

    // ───────────────────────── 路线级默认值 ─────────────────────────

    private fun applyRouteDefaults(param: NaviParam, interact: InteractDefaults, find: FindDefaults) {
        for (wp in param.path) {
            // interact：只补没写的点
            if (wp.action == ActionType.INTERACT && wp.interactText.isEmpty() && wp.interactTextNode.isEmpty()) {
                interact.text?.let {
                    wp.interactText = it.texts.toMutableList()
                    wp.interactTextNode = it.node
                }
            }
            if (wp.action == ActionType.INTERACT) {
                if (wp.interactScan.isEmpty()) wp.interactScan = interact.scan
                // rec 只能开不能关
                if (interact.rec) wp.interactRec = true
            }

            if (wp.action == ActionType.FIND) {
                if (wp.findTarget.isEmpty() && wp.findText.isEmpty() && wp.findStop.isEmpty() && wp.findArrive == null) {
                    wp.findTarget = find.target
                    find.text?.let {
                        wp.findText = it.texts.toMutableList()
                        wp.findTextNode = it.node
                    }
                    wp.findStop = find.stop
                    wp.findArrive = find.arrive
                } else {
                    if (wp.findTarget.isEmpty() && wp.findText.isEmpty() && wp.findTextNode.isEmpty()) {
                        wp.findTarget = find.target
                        find.text?.let {
                            wp.findText = it.texts.toMutableList()
                            wp.findTextNode = it.node
                        }
                    }
                    if (wp.findStop.isEmpty() && wp.findArrive == null) {
                        wp.findStop = find.stop
                        wp.findArrive = find.arrive
                    }
                }
            }
        }

        // FIND 的目标来源二选一、完成条件至少一个
        for ((index, wp) in param.path.withIndex()) {
            if (wp.action != ActionType.FIND) continue
            if (wp.findTarget.isNotEmpty() && (wp.findText.isNotEmpty() || wp.findTextNode.isNotEmpty())) {
                throw TypeError("path[$index] FIND 同时给了 find_target 与 find_text")
            }
            if (wp.findTarget.isEmpty() && wp.findText.isEmpty() && !wp.findTextNode.isNotEmpty()) {
                throw TypeError("path[$index] FIND 缺少目标来源")
            }
            if (wp.findStop.isEmpty() && wp.findArrive == null) {
                throw TypeError("path[$index] FIND 缺少完成条件（find_stop 或 find_arrive）")
            }
        }

        // 路线级冲突：只有路线里真有 FIND 点时才失败
        val hasFind = param.path.any { it.action == ActionType.FIND }
        if (find.conflicting && hasFind) {
            throw TypeError("路线级 FIND 同时给了 find_target 与 find_text")
        }
    }

    // ───────────────────────── 文本字段三形态 ─────────────────────────

    /**
     * 上游 `NaviTextListInput`（navi_param_parser.cpp:101-144）：
     * 非空字符串 / 非空且每项非空的字符串数组 / `{"node": 非空字符串}`；其余一律失败。
     */
    private fun readTextSpec(node: Any?, field: String): TextSpec? {
        when (node) {
            null -> return null

            is String -> {
                if (node.isEmpty()) throw TypeError("$field 是空字符串")
                return TextSpec(mutableListOf(node), "")
            }

            is List<*> -> {
                if (node.isEmpty()) throw TypeError("$field 是空数组")
                val texts = mutableListOf<String>()
                for (item in node) {
                    val s = item as? String ?: throw TypeError("$field 数组里含非字符串")
                    if (s.isEmpty()) throw TypeError("$field 数组里含空字符串")
                    texts += s
                }
                return TextSpec(texts, "")
            }

            is Map<*, *> -> {
                val only = node.keys.singleOrNull() as? String
                if (only != "node") throw TypeError("$field 的对象形态只接受 {\"node\": ...}")
                val s = node["node"] as? String ?: throw TypeError("$field.node 不是字符串")
                if (s.isEmpty()) throw TypeError("$field.node 是空字符串")
                return TextSpec(mutableListOf(), s)
            }

            else -> throw TypeError("$field 类型不支持")
        }
    }

    private fun readInteractDefaults(root: Map<*, *>): InteractDefaults {
        val text = readTextSpec(finalValue(root, "interact_text", "interactText"), "interact_text")
        val scan = finalString(root, "interact_scan", "interactScan").orEmpty()
        val rec = firstBool(root, "interact_rec", "interactRec") ?: false
        return InteractDefaults(text, scan, rec)
    }

    private fun readFindDefaults(root: Map<*, *>): FindDefaults {
        val target = finalString(root, "find_target", "findTarget").orEmpty()
        val text = readTextSpec(finalValue(root, "find_text", "findText"), "find_text")
        val stop = finalString(root, "find_stop", "findStop").orEmpty()
        val arrive = readPair(root, "find_arrive", "findArrive")
        return FindDefaults(target, text, stop, arrive, conflicting = target.isNotEmpty() && text != null)
    }

    private fun readInteractPoint(obj: Map<*, *>): InteractPoint? {
        val hasAnyField = hasAny(obj, "interact_text", "interactText") ||
            hasAny(obj, "interact_scan", "interactScan") ||
            hasAny(obj, "interact_rec", "interactRec")
        if (!hasAnyField) return null
        return InteractPoint(
            text = readTextSpec(finalValue(obj, "interact_text", "interactText"), "interact_text"),
            scan = finalString(obj, "interact_scan", "interactScan").orEmpty(),
            rec = firstBool(obj, "interact_rec", "interactRec") ?: false,
        )
    }

    private fun readFindPoint(obj: Map<*, *>): FindPoint? {
        val hasAnyField = hasAny(obj, "find_target", "findTarget") ||
            hasAny(obj, "find_text", "findText") ||
            hasAny(obj, "find_stop", "findStop") ||
            hasAny(obj, "find_arrive", "findArrive")
        if (!hasAnyField) return null
        return FindPoint(
            target = finalString(obj, "find_target", "findTarget").orEmpty(),
            text = readTextSpec(finalValue(obj, "find_text", "findText"), "find_text"),
            stop = finalString(obj, "find_stop", "findStop").orEmpty(),
            arrive = readPair(obj, "find_arrive", "findArrive"),
        )
    }

    /** `find_arrive` 必须恰好两个数字。 */
    private fun readPair(obj: Map<*, *>, vararg keys: String): Pair<Double, Double>? {
        val node = finalValue(obj, *keys) ?: return null
        val list = node as? List<*> ?: throw TypeError("${keys[0]} 不是数组")
        if (list.size != 2) throw TypeError("${keys[0]} 必须是恰好两个数字")
        val a = (list[0] as? Number)?.toDouble() ?: throw TypeError("${keys[0]} 第一项不是数字")
        val b = (list[1] as? Number)?.toDouble() ?: throw TypeError("${keys[0]} 第二项不是数字")
        return a to b
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private class TypeError(message: String) : RuntimeException(message)

    private fun hasSingleWaypointKeys(root: Map<*, *>): Boolean =
        hasAny(root, "action", "actions", "x", "y", "angle", "heading", "yaw", "target")

    private fun collectActions(node: Any?, out: MutableList<ActionType>) {
        when (node) {
            is String -> out += actionOf(node) ?: throw TypeError("非法动作名 '$node'")
            is List<*> -> for (item in node) {
                val s = item as? String ?: throw TypeError("动作数组里含非字符串")
                out += actionOf(s) ?: throw TypeError("非法动作名 '$s'")
            }

            else -> throw TypeError("action 类型不支持")
        }
    }

    /** 大小写敏感（上游同）。 */
    private fun actionOf(name: String): ActionType? =
        ActionType.entries.firstOrNull { it.name == name }

    /**
     * 「看起来像动作」：全大写或含下划线，或者大小写不敏感地等于某个动作名。
     * 这类字符串不许被当成 zone_id——否则写错大小写会静默走错区域。
     */
    private fun looksLikeAction(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.any { it == '_' } || text.all { !it.isLetter() || it.isUpperCase() }) return true
        return ActionType.entries.any { it.name.equals(text, ignoreCase = true) }
    }

    private fun hasAny(map: Map<*, *>, vararg keys: String): Boolean = keys.any { map.containsKey(it) }

    private fun finalValue(map: Map<*, *>, vararg keys: String): Any? {
        for (key in keys) {
            val v = map[key] ?: continue
            return v
        }
        return null
    }

    private fun str(map: Map<*, *>, key: String): String? {
        val v = map[key] ?: return null
        return v as? String ?: throw TypeError("$key 不是字符串")
    }

    private fun finalString(map: Map<*, *>, vararg keys: String): String? {
        for (key in keys) {
            val v = map[key] ?: continue
            return v as? String ?: throw TypeError("$key 不是字符串")
        }
        return null
    }

    private fun dbl(map: Map<*, *>, key: String): Double? {
        val v = map[key] ?: return null
        return (v as? Number)?.toDouble() ?: throw TypeError("$key 不是数字")
    }

    private fun firstNumber(map: Map<*, *>, vararg keys: String): Double? {
        for (key in keys) {
            val v = map[key] ?: continue
            return (v as? Number)?.toDouble() ?: throw TypeError("$key 不是数字")
        }
        return null
    }

    private fun long(map: Map<*, *>, key: String): Long? {
        val v = map[key] ?: return null
        return (v as? Number)?.toLong() ?: throw TypeError("$key 不是数字")
    }

    private fun bool(map: Map<*, *>, key: String): Boolean? {
        val v = map[key] ?: return null
        return v as? Boolean ?: throw TypeError("$key 不是布尔")
    }

    private fun firstBool(map: Map<*, *>, vararg keys: String): Boolean? {
        for (key in keys) {
            val v = map[key] ?: continue
            return v as? Boolean ?: throw TypeError("$key 不是布尔")
        }
        return null
    }
}
