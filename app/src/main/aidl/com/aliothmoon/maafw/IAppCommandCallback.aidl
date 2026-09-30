package com.aliothmoon.maafw;

/**
 * 调试 CLI 的反向命令桥（特权进程 → app 进程）。
 *
 * 为什么需要它：运行计划（RunPlan）在 **app 进程**用 ProjectDefinition + UserConfiguration 构建，
 * 特权进程只拿得到最终的 payload，没有能力自己拼一份。所以 CLI 的 start/stop 只能回落给 app
 * 侧代执行——这里走的就是 app 进程既有 Ui 相同的 RunLauncher → RunnerPort 路径。
 *
 * 全部 oneway：CLI 客户端线程不能被 app 侧阻塞。方法只表示「已请求」，
 * 结果由 app 侧照常走 RunnerState / 回调推进，CLI 用 `status` / `logtail` 观察。
 * transaction id 只增不改，与 RemoteService 同一约定。
 */
oneway interface IAppCommandCallback {

    /**
     * 请求启动一轮任务。
     *
     * [taskNamesJson] 是 JSON 字符串数组：空数组表示当前激活配置的全部任务，非空按 taskName 筛选。
     * 用 JSON 而不是分隔符拼接：taskName 里出现分隔符时不会解错。
     */
    void onStartTasks(String taskNamesJson) = 1;

    /** 请求停止当前任务；幂等，没在跑时也应安全。 */
    void onStopRun() = 2;
}
