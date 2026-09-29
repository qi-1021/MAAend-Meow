# 上游报告（待提交）：MaaFramework v5.14.0 —— MaaContextOverridePipeline 间歇性 SIGSEGV

> **状态**：材料已就绪，**尚未提交**——我们的 GitHub PAT 缺少第三方仓库的 issue 创建权限
> （报错 ）。
> 把本文件正文贴到 https://github.com/MaaXYZ/MaaFramework/issues/new 即可；
> 或给 token 开 issue 写权限后告诉我，我来提。

> **上游核查结论（2026-09-29）**：上游**没有**同签名的 issue/PR/discussion；
> 最新 v5.14.2 的 release notes 也无相关修复。
> 可对照的同形态历史问题（均已修）：#1442（Android 自定义动作反向调用，EINTR→SIGABRT，v5.13.0 修）、
> MaaEnd#5623（自定义动作反复反向调用导致 ZMQ 互锁，v5.14.1 修）。

---

## 环境

- **MaaFramework: v5.14.0**（我们 pin 的版本；`libMaaFramework.so` BuildId `27ca712c3ed8f926be81279f82adeeabf73e01dd`）
- 平台：Android arm64-v8a（Xiaomi 24129PN74C / Android 17），宿主通过 **JNA** 调用 C API
- 注册方式：进程内 `MaaResourceRegisterCustomAction` / `MaaResourceRegisterCustomRecognition`（非 Agent）
- 调用方式：**自定义动作回调内部同步调用**，未跨线程、未保存 context 句柄到回调之外

## 现象

间歇性原生崩溃，崩溃点固定在 `MaaContextOverridePipeline`：

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x505b5d4742445b6d
  tid 9876 (Thread-10), pid <宿主特权进程>

6 total frames
  #00 pc .../libMaaFramework.so (MaaContextOverridePipeline+592)
  #01 pc .../libjnidispatch.so
  #02 pc .../libjnidispatch.so
  #03 pc .../libjnidispatch.so
  #04 pc .../libjnidispatch.so (Java_com_sun_jna_Native_invokeInt+32)
  #05 pc .../base.odex
```

## 复现规律（关键：与内容无关）

**同一条调用前三次成功、第四次崩**：

```
14:41:47  MaaContextOverridePipeline(ctx, {"OutpostTradingReserveAlreadySatisfied":{"enabled":false}})  ✓
14:42:01  同一条                                                                                          ✓
15:44:57  同一条                                                                                          ✓
15:45:11  同一条                                                                                          → SIGSEGV
```

另一次不同内容、同一形态也崩：

```
17:41:24  MaaContextOverridePipeline(ctx, {"OutpostTradingReserveQuantityReached":{"enabled":true}})  → SIGSEGV
```

且**纯查询** `MaaContextGetNodeData(ctx, "OutpostTradingReserveAlreadySatisfied", buf)`
在同一个节点名上**也崩**（崩溃前最后一条框架日志正是它的 `| enter`）：

```
[...][MaaContext.cpp][L303][MaaBool MaaContextGetNodeData(MaaContext *, const char *, MaaStringBuffer *)]
  [context=true] [node_name=OutpostTradingReserveAlreadySatisfied] | enter
→ SIGSEGV
```

即：**同一个 Context、同一个节点名，`OverridePipeline` 与 `GetNodeData` 都会间歇性崩**。

## 已排除

- **不是 JSON 内容问题**：同一条 JSON 连续三次成功，第四次才崩
- **不是空指针/参数非法**：参数与前面成功的调用逐字相同
- **不是 OOM / 系统杀进程**：是 SIGSEGV，tombstone 完整
- **不是我们跨线程使用**：in-process 回调在任务线程上同步执行，我们在回调栈内同步调用后立即返回

## 线索：疑似堆损坏

故障地址与寄存器内容像是**日志文本被当成了指针**：

```
fault addr 0x505b5d4742445b6d → 6d 5b 44 42 47 5d 5b 50 → "m[DBG][P"
x9  0x505b5d4742445b5d                                    → "][DBG][P"
x12 "lreadySa"   x13 "tisfied\0"   x14 "osTtradi"   x15 "ngReseRv"
        ↑ 残留的正是被覆盖掉的节点名字符串（...ReserveAlreadySatisfied）片段
```

看起来像**堆上的缓冲区被日志/字符串覆盖、随后被当指针解引用**，
而不是简单的空指针解引用。

## 源码观察（供参考，未确认）

`source/MaaFramework/Task/Context.h` 中 `pipeline_override_` 是裸 `std::unordered_map`，
`Context` 类没有任何 mutex；`source/Common/MaaContext.cpp` 的 `MaaContextOverridePipeline`
从 JSON 解析到写 map 全程无锁。

## 想请教

1. 这是否是**已知问题**？有没有推荐的规避用法？
2. `MaaContextOverridePipeline` / `MaaContextGetNodeData` 是否支持在**自定义回调内**调用？
   有没有必须遵守的时序/生命周期约束（例如任务结束后 Context 失效、或回调内不宜反向调用）？
3. 如果需要，我可以提供**完整 tombstone**（含更多寄存器与内存映射）与最小复现工程。

> 补充：我们已排查过 v5.14.1 / v5.14.2 的 release notes，未见到与该崩溃相关的修复；
> 也搜索过 issues/discussions，未见相同签名的报告，因此在这里报一条。

---

## 补充（同日更新）：影响面与我们已验证的绕开方式

**影响**：这个崩溃会让宿主**特权进程整个被杀**，正在跑的任务被强制中断——
所以在它被修好之前，我们的「据点交易」功能**每次都在收尾阶段失败**。
这不是理论问题，是线上可用性问题。

**我们已经找到并验证了绕开方式**（供参考，也说明这个 API 并非不可替代）：
触发崩溃的那条调用是 `{"<结果节点>":{"enabled":true}}`，用于"点亮"一个结果节点；
但读上游定义后发现该节点的**全部作用**只是
「跑一次 `Custom(OutpostTradingReserveSession, operation=satisfy)`，然后 `next` 到
`OutpostTradingSellLoop`」——于是我们**不再下发这条 `enabled` 覆盖**，
改在编排层手工完成等价动作（手工 `satisfy` + 把调用方 `next` 指到同一目的地）。

**真机验证结果**：绕开后同一功能
`Fatal signal: 0`、`Tasker.Task.Succeeded`。

也就是说：**这条 `enabled`-only 覆盖路径对我们并非不可替代**，
如果上游一时难以定位根因，至少可以确认"绕开它"是可行解。

**我们仍希望上游能修**，因为：
1. `Context::pipeline_override_`（裸 `unordered_map`、`Context` 无 mutex）在
   `MaaContextOverridePipeline` 与 `MaaContextGetNodeData` 上都会崩，
   任何下游都可能踩到；
2. 我们为了绕开它，**偏离了上游的原始语义**（放弃了一次交易后的记账），
   这属于技术债，修好后我们会恢复。

**可提供的进一步材料**：完整 tombstone（含内存映射与更多寄存器）、
最小复现工程、以及我们侧的全部调用日志（`maa.log` 片段）。
