package com.aliothmoon.maafw.remote

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `FailureCollector*` 三个动作的纯逻辑层（对齐上游
 * `agent/go-service/common/failurecollector/`）。
 *
 * 上游语义（action.go / collector.go）：
 *  - `Reset(key)`：把 key 的失败表清空。
 *  - `RunTask(key, task, recovery_task, failure_task)`：跑 `task` 子任务；成功返回成功，
 *    失败则把 `failure_task` **记录进失败表**（注意：动作本身仍返回成功，让外层流水线
 *    继续走完），若有 `recovery_task` 则接着跑一次恢复，恢复失败只告警。
 *  - `Finish(key)`：取出并**删除**该 key 的失败表，逐个跑失败播报任务（播报任务自身失败
 *    只告警），返回「是否没有任何失败」——有失败就是失败。
 *
 * 这里只放不依赖 JNA / Android 的部分：参数解析、节点禁用判定、失败表与 Finish 判定。
 * 真正调 `MaaContextRunTask` / 查任务状态留给 MaaRunner。
 *
 * 为什么必须实现：此前三个名字都在 noop 名单里，`FailureCollectorRunTask` 是采集
 * （AutoCollect，82 处）与环境监测全部 Job 节点的**路线执行器**，noop 之后子任务根本
 * 没跑、失败也不记录，而外层看到的是成功——典型的「跑通了实际没动」。
 */
object FailureCollectorSupport {

    /** 上游 `actionParam`。 */
    data class ActionParam(
        val key: String,
        val task: String = "",
        val recoveryTask: String = "",
        val failureTask: String = "",
    )

    /**
     * 上游 `parseParam`：必须能反序列化且 `key` 非空，否则调用方判动作失败。
     * `task` / `recovery_task` / `failure_task` 缺省为空串。
     */
    fun parseParam(tree: Any?): ActionParam? {
        val map = tree as? Map<*, *> ?: return null
        val key = (map["key"] as? String)?.trim().orEmpty()
        if (key.isEmpty()) return null
        return ActionParam(
            key = key,
            task = (map["task"] as? String)?.trim().orEmpty(),
            recoveryTask = (map["recovery_task"] as? String)?.trim().orEmpty(),
            failureTask = (map["failure_task"] as? String)?.trim().orEmpty(),
        )
    }

    /**
     * 上游 `RunTaskAction.Run` 在跑子任务前会 `ctx.GetNode(task)` 并检查
     * `Enabled != nil && !*node.Enabled`：显式禁用的目标节点直接跳过（动作算成功）。
     *
     * 移动端拿不到结构化节点，只能读节点定义 JSON。只有显式 `enabled=false` 才算禁用；
     * 缺省（框架默认 true）不算。
     */
    fun isNodeDisabled(nodeJson: String?): Boolean {
        if (nodeJson.isNullOrBlank()) return false
        val map = MaaJsonTree.parse(nodeJson) as? Map<*, *> ?: return false
        return map["enabled"] as? Boolean == false
    }

    /**
     * 上游 `FinishAction.Run` 的返回值语义：`len(failures) == 0`。
     * 抽出来是为了让「有失败 → 失败」这条判定可单测，避免又退化成恒成功。
     */
    fun finishSucceeds(failures: List<String>): Boolean = failures.isEmpty()

    /**
     * 任务级失败表（对齐上游 collector.go 的 `states.byKey`）。
     *
     * key 由流水线参数给出（采集用 `AutoCollect`、环境监测用 `EnvironmentMonitoring`），
     * 值是待播报的 `failure_task` 节点名，同名可重复记录。
     */
    class Session {
        private val lock = ReentrantLock()
        private val failuresByKey = LinkedHashMap<String, MutableList<String>>()

        /** 上游 `Reset`：清空该 key（含从未出现过的 key）。 */
        fun reset(key: String) = lock.withLock {
            failuresByKey[key] = mutableListOf()
        }

        /** 上游 `Record`：同一个失败任务被触发多次会记录多次。 */
        fun record(key: String, failureTask: String) = lock.withLock {
            failuresByKey.getOrPut(key) { mutableListOf() } += failureTask
        }

        /** 上游 `Finish`：返回该 key 的失败任务副本并**删除**该 key。 */
        fun finish(key: String): List<String> = lock.withLock {
            failuresByKey.remove(key)?.toList() ?: emptyList()
        }

        /** 任务开始时清掉整张表：上一次运行若在 Finish 前中断，残留会污染本次汇总。 */
        fun resetAll() = lock.withLock {
            failuresByKey.clear()
        }

        /** 诊断/测试用：某 key 当前的失败数量。 */
        fun failureCount(key: String): Int = lock.withLock {
            failuresByKey[key]?.size ?: 0
        }
    }
}
