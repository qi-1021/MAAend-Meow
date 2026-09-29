# 开发日志 (DEVLOG)

> **用途**：按时间倒序记录每一次开发的**内容、动机、验证方式与教训**。
> 与 [`DEVELOPMENT_EXPERIENCE.md`](./DEVELOPMENT_EXPERIENCE.md) 的分工：
> 那份讲**可复用的移植经验与方法论**，这份讲**这一次具体改了什么、为什么、怎么验的**。
>
> 追加格式：新条目放在最上面，标题写 `## YYYY-MM-DD`，正文用「做了什么 / 为什么 / 怎么验的 / 教训 / 未做」几段。

---

## 2026-09-29 · MapLocator 移植：结构测绘 + 分片方案（含一个必须说清的阻碍）

### 一、测绘结论（关键事实，均带行号）

- `MapLocateAssertLocation` 注册的是 **Custom Recognition**（不是 action），
  实现在 `MapLocateAction.cpp:404-471`：**轮询最多 60 帧、每帧 250ms**，
  第 0 帧用回调给的图、之后自己截图；定位成功且落在目标矩形内就返回。
- 主入口 `Impl::locate`（`MapLocator.cpp:1801-2138`，**338 行**）主干：
  **追踪优先**（`tryTrackingLocate` 144 行 → `tryTracking` 199 行）→ 否则**全局搜索**
  （起批任务 → 峰位精修 → 阴值判定），中间夹着遮挡守卫、冷启动共识、
  远跳保护、双策略互证、运动仲裁。**`force_global_search=true` 时完全跳过追踪**
  （这正是 AssertLocation 的用法）。
- 模型：
  - `map/cls.onnx`：**256 类**分类器，输入 **1×3×128×128 float32 RGB [0,1]**
    （原图**不缩放**、居中裁剪/贴入 128×128、叠直径 106 圆形 mask），
    **直接 argmax**（无阈值比较——`yoloConfThreshold` 实际是惰性参数）；
    输出经 `cls.json` 的 `classes`/`region_mapping` 映射成 zone / tile。
  - `map/cameraorientation/{preprocess,polar_with_ref}.onnx`：**必须成对**才能用
    （只给一个 → 整个预测器禁用）；两级串联（前一后输出 7 通道 NHWC 再喂后一个），
    结果只是**附带的朝向输出**，不参与定位判断。
- 资产：`tile_mapping.json`（328 个 tile 的 ROI + `infer_margin` 全为 64）、
  `cls.json`；底图 `resource/image/MapLocator/<Zone>/`。
- 依赖：**OpenCV**（`matchTemplate` 带 mask、`remap`、`Canny`、
  `distanceTransform`、轮廓处理、`resize`）、ONNX Runtime、`boost::regex`、meojson；
  并发模型是 **11 线程优先级池 + 异步 YOLO + std::async 角度推理**。
- **纯算法（不碰 `cv::Mat`）与图像依赖有清晰分界**（函数级）：
  `MotionTracker.cpp` 全部、几何/映射/状态/置信度裁决、`convertYoloNameToZoneId`、
  `decodePmf`、策略的 validate 函数等——这部分可**原样移植且本机可单测**。

### 二、真正的阻碍（必须说清）

**图像处理核心需要 OpenCV 级原语**：带掩膜的多尺度 `matchTemplate`、`remap` 亚像素重采样、
`Canny`/`distanceTransform`（Chamfer 补偿）、轮廓几何。
**Kotlin 没有等价能力，手写性能也不够**（地图是 1600×1600 量级、模板 118×120、
多尺度 + 逐帧追踪）。这是本项移植**唯一实质性的阻碍**，其余都是工作量问题。

### 三、三条路线（评估）

| 路线 | 内容 | 代价 | 精度 |
|---|---|---|---|
| (a) 打包 OpenCV Android | 真·移植 | APK +100MB 级 + 与 JNA/构建集成 | 与上游一致 |
| **(b+) 框架 TemplateMatch（粗搜）+ Kotlin（精修/决策）** | 见下 | **零新原生依赖** | 需真机校准 |
| (c) Kotlin 金字塔 ZNCC | 全自研 | 性能风险（粗搜可能秒级） | 可接受但需校准 |

#### (b+) 为什么可行（三个关键事实凑出来的）

1. **框架 TemplateMatch 没有多尺度**（schema 里 `scales` 0 处），但——
2. **上游的地图缩放本来就是分区的固定值**：`ZoneTemplateScale` 只有
   `ValleyIV_Base` 是 15/16，其余全 1.0（`MapTypes.h:214-217`）。
   也就是说**不需要连续多尺度**，按 zone 选对模板缩放即可 ✓。
3. **搜索不是全图盲扫**：先由 YOLO 分类给出 zone 与 tile，
   搜索被约束到该 tile 的 ROI（`infer_margin=64` 外扩）——
   这也正好是框架 TemplateMatch 的 `roi` 能表达的形状 ✓。

**一个必须绕过的形状问题**：上游是 `matchTemplate(map图, 小地图模板)`，
即**搜索图是地图资产、模板是小地图**；而框架 TemplateMatch 的"被搜图"是**截图**。
但我们的 `MaaContextRunRecognition` **可以传任意图像**（现有 OCR 探针就是这么用的）——
所以把**地图**作为 image 传进去即可 ✓。
模板则需是资源里的**文件**：把运行时裁下来的小地图写成 PNG
（圆形遮罩按 MAA 的 `green_mask` 语义编码成绿色），再用 `template` 指向它 ✓。
亚像素精修（上游用抛物线/`remap` 连续精修）在 Kotlin 里对
Maa 返回位置附近的小邻域做局部 ZNCC 即可（代价极低）✓。

> 该路线的前提是"**能用任意图像跑 TemplateMatch 且模板可动态写入**"——
> 这正是第 1 步真机验证要确认的东西。

### 四、分片与里程碑（每步都能真机验证）

1. **前提验证**：装上 `map-locate` 包 → 确认资产落点 + 框架能跑 `cls.onnx`
   （最小 NN 节点试跑）。**这一步决定 (a)/(b)/(c) 哪些成立**，必须先做。
2. **纯逻辑层**（与路线无关、本机可单测）：MotionTracker + 几何/映射/决策。
3. **粗搜**：按第 1 步结论选路线。
4. **追踪状态机**：`tryTrackingLocate` 的仲裁/守护逻辑。
5. **朝向**：先用 Kotlin 近似（角速度/预测），两级 ONNX 放到最后。

**注意**：这是**多阶段工程**，不是一次会话能完成的量。按上面的顺序做，
每一步都留下可真机验证的产物，避免"写了几千行才发现模型跑不起来"。

---

## 2026-09-29 · 据点交易首次成功跑完：绕过框架崩溃（`dd4e756`）

**这是本项工作第一个「两个核心功能都在真机跑通」的里程碑**：

```
Fatal signal: 0                                  ← 此前跑到收尾必崩
Tasker.Task.Succeeded  OutpostTradingSchedule    ← 据点交易成功
（另一条链路：Tasker.Task.Succeeded  AutoStockpileMain ← 囤货也成功）
```

### 怎么绕开的

框架在 `MaaContextOverridePipeline` 上对 `{"<结果节点>":{"enabled":…}}` 这种调用
**间歇性 SIGSEGV**（同一条调用前三次成功、第四次崩；上游尚未修复，报告已交维护者）。
关键是我们**并不需要这条调用**——那两个结果节点的定义只是：

```json
{"enabled":false,"recognition":"DirectHit",
 "action":"Custom","custom_action":"OutpostTradingReserveSession",
 "custom_action_param":{"operation":"satisfy"},"next":["OutpostTradingSellLoop"]}
```

即"跑一次 `satisfy`，然后去 `OutpostTradingSellLoop`"。于是改成在**编排层手工完成**：

- **越界分支**：不再 enable，改为 ① 复用同一份 satisfy 逻辑手工记账
  ② 用 `overrideCheckQuantityBranch` 把调用方 next 指到 `OutpostTradingSellLoop`
  → **行为等价**（原来就是"点亮节点让流水线自己跑一遍"）。
- **可达分支**：**绝不**把调用方 next 直接指到 SellLoop——那个结果节点静态挂在
  `OutpostTradingSellCheckThenLoop.next`（交易**之后**），改 next 会**跳过卖出**。
  所以这里只是**不点亮**，交易照常由 `OutpostTradingSellThenLoop` 完成，
  并打一条写明取舍的 WARN。

`satisfy` 只有一份实现（`MaaRunner.satisfyOutpostReserve`），pipeline 回调与
新增的 host 方法都调它，语义不会漂移。

### 教训

**当框架的某个 API 会崩、而你其实并不需要它时，"不用它"比"绕开它的坏路径"更彻底。**
这次的关键不是给崩溃打补丁，而是先问「这条调用到底在完成什么语义」——
问清之后发现它只是"跑一次 satisfy 再去 SellLoop"，于是整条调用都可以删掉。

**另一个教训：改动前必须追清引用方式。** 两个结构几乎一样的节点，
一个只由我们自己的代码动态引用（可安全替换），另一个静态挂在交易**之后**的节点链上
（动它就会跳过卖出）——只看定义会以为两者可以同样处理。

### 残留（已明确记录，非静默）

可达分支下，交易后的记账节点不再点亮 → 该物品不会在交易后立即记入 `satisfiedItems`，
流程会再走一轮 `[Anchor]OutpostTradingBetterSliding`（对同一物品重算保留滑条，
真机上表现为多几次"微调第 N 次"）。**卖出行为未变**；该轮通常得到越界 → 走新的
satisfy+路由，最终仍会标记 satisfied；选品侧另有 `attempted` 集合兜底。
待上游修复后恢复原语义。

另注：`NODE_GET_AVAILABLE_QUANTITY` / `NODE_FIND_SWIPE_FOR_RESET` 上仍有
`enabled` 覆盖（BetterSliding 自己的内部节点、每会话一次），不在本次崩溃路径上，
但**同一 API 的残余暴露仍在**——已记录。

---

## 2026-09-29 · 真机联调第二/三轮：武陵根因 + PC 鼠标语义 + 框架崩溃

**提交**：`a6ca528`、`4b66f49`、`5c9a1db`、`dd53759`

### 一、武陵乱码的真正根因：`only_rec` 跨调用泄漏（`a6ca528`）

上一次真机把武陵从"稳定乱码 `2そ22`"变成了"读空"——**这个变化本身就是线索**：
说明乱码来自我们自抓的帧；改用框架帧后，暴露出更深的问题。这轮拿到了完整证据链：

1. **探针帧与框架帧逐像素完全相同**（0 / 921600 差异，`probe_dump` 与 `on_error` 对比）
   → 帧源没问题，问题在 OCR 参数
2. 框架日志给出真凶：
   ```
   __GoodsOcrProbe [all_results_=[{"box":[63,162,1177,553],...text:""}]] [param_.only_rec=true]
   ```
   框 = 整个 ROI、text 空 —— 正是 `only_rec=true` 的特征；而**我们发的 override 里根本没写它**：
   `{"recognition":"OCR","roi":[63,162,1177,553],"color_filter":"AutoStockpileGoodsFilter"}`
3. **根因**：`__GoodsOcrProbe` 是**所有 OCR 探针共用的同一个节点名**，而
   `MaaContextOverridePipeline` 会**保留上次写入的节点定义**——**本次没写的键会继承上一次的值**。
   本轮时序：谷地货卡探针（`only_rec` 尚为默认 false）→ 读对 6 个候选 →
   买货读单价走 `onlyRec=true` → **把共享节点写成 `only_rec=true`** →
   武陵货卡的 override 没写 → 继承 true → 整个 ROI 退化成一行 → 读空。

**修法**：三个可变字段（`roi` / `only_rec` / `color_filter`）**一律显式写出**，
不给继承留余地（roi 无效时写全屏 `[0,0,0,0]`；过滤为空写 `""`）。

**教训**：**共享节点 + 持久化覆盖 = 跨调用状态泄漏**。凡是"逐次可变的参数"，
都要显式写出来，别依赖"没写就是默认值"。这个 bug 只会在真机上以
"换了张图就突然读不到"的形式暴露——单测与编译都发现不了。

### 二、TouchMove：PC 鼠标语义在 Android 上必然失败（`4b66f49`）

`BetterSlidingMoveMouse` 失败，框架日志：
```
action="TouchMove", point=[1258,696], contact=0, pressure=0 → completed=false
```
该节点的 desc 是「**避免遮挡 Increase/Decrease Button**」——PC 上鼠标会停在 +/- 按钮上
挡住点击，所以先把它移开；**Android 的点击不留下光标**，这个动作纯属 PC 语义。
但它的失败会**打断整条链**（据点交易卡在"精确设置数量"）。

修法：构建期把全上游 5 个文件 6 处 `TouchMove` 中和成 `DoNothing`（保留 `next` 与其它字段）。
验证：改后 `BetterSlidingMoveMouse` 不再失败，流程推进到 `BetterSlidingDone` 之后。

**教训**：**移植上游流水线时要专门审"平台动作"**——`TouchMove`（鼠标悬停）、
`TouchDown/Up`、`ClickKey/KeyDown/KeyUp` 这类在 Android 上没有对应概念的动作，
要么中和、要么换成平台等价的实现。

### 三、框架侧间歇性 SIGSEGV（`5c9a1db`、`dd53759`）

**现象**：据点交易收尾时特权进程 SIGSEGV，tombstone 的 pc 落在
`libMaaFramework.so (MaaContextOverridePipeline+592)`，崩溃前最后一条日志是我们发的
`{"OutpostTradingReserveAlreadySatisfied":{"enabled":false}}`。

**决定性证据**：**同一条 override 前三次成功、第四次崩溃**
（14:41:47 / 14:42:01 / 15:44:57 正常，15:45:11 崩）→ 与内容无关，是框架侧的状态/竞态。
寄存器里残留的是日志行与节点名字符串的碎片（故障地址 `0x505b5d…` 解码为 `…[DBG][P`）。

**已做的缓解（`5c9a1db` → `dd53759`）**：
- 第一版想用 `MaaContextGetNodeData` 先读节点当前 `enabled` 再决定跳过——
  **结果那一步自己就崩**（真机日志：崩溃前最后一条正是
  `MaaContextGetNodeData(node_name=OutpostTradingReserveAlreadySatisfied) | enter`）。
- 第二版**完全不查询框架**：只在会话内记住"被我们开启过"的节点，
  只有它们才需要下发关闭覆盖；没开过就保持默认（这两个节点在上游 `SellCore.json` 里
  **默认就是 `enabled:false`**），那条 `{"节点":{"enabled":false}}` 本来就是空操作。
- **真机验证**：跳过日志出现（`…未由本会话开启过，保持默认关闭`），
  流程比以往跑得都深（微调迭代到第 3 次）。

**残留**：`{"<结果节点>":{"enabled":true}}` 这条路**仍会崩**（16:55:27、17:41:24 两次实测）。
属框架侧竞态。

**已识别出干净的绕开路径（待实现）**：这两个"结果节点"的定义就是
```json
{"desc":"本次交易已达到保留数量…","enabled":false,"recognition":"DirectHit",
 "action":"Custom","custom_action":"OutpostTradingReserveSession",
 "custom_action_param":{"operation":"satisfy"},"next":["OutpostTradingSellLoop"]}
```
即它们的**全部作用**是「跑一次 satisfy，然后去 `OutpostTradingSellLoop`」。
所以完全不需要用 `enabled` 覆盖把它们"点亮"，只要：
1. **直接执行一次 satisfy**（复用 `OutpostReserveSupport` 的状态机），
2. 用 `overrideCheckQuantityBranch`（本项目已多次验证可用的 `next` 覆盖）把调用方 next 指到 `OutpostTradingSellLoop`。

`next` 覆盖这条路径我们在主线里一直在用、从未出过问题；而 `enabled` 覆盖是唯一会崩的调用。
另一条可选动作是把 tombstone + 调用栈 + 触发 JSON 整理给 MaaFramework 上游。

**本轮缓解的实测效果**：同一条 BetterSliding 流程里，`结果覆盖跳过` 触发 2 次
（`handleGetSliderMaxQuantity` 与收尾各一次），即两次空操作调用被省掉；
崩溃次数随之下降（同轮只剩"真实开启"那 1 次）。

### 四、仍未做

- 据点交易收尾的 `enabled` 覆盖绕开（改用 `next`）。
- 框架崩溃的上报材料整理。

---

## 2026-09-29 · MapLocator 可行性调研（为下次真机做准备）

MapLocator 是「采集 / 转交委托 / 删除共享滑索」的共同前置（约 4859 行 C++）。
在动手之前先回答**三个会决定工作量的前提问题**——结论比预期好。

### 1. 资产已经就位（无需改动补充包链路）

MapLocator 要的就是这几个文件，而 `map-locate` 补充包里**正好都有**：

| MapLocator 需要 | 补充包内容 |
|---|---|
| `map/cls.onnx`（分区分类） | ✓ |
| `map/cameraorientation/{preprocess,polar_with_ref}.onnx` | ✓ |
| `map/tile_mapping.json`、`map/cls.json` | ✓ |

即：之前做的「大资产按需下载」已经把 MapLocator 的资产侧铺好了，**不需要再动下载逻辑**。

### 2. 不需要单独塞 ONNX Runtime

MaaFramework **内置** `NeuralNetworkClassify` / `NeuralNetworkDetect`（目前仅支持 ONNX 模型），
上游自己的 `AutoFight` 流水线就在用 `NeuralNetworkDetect`
（`assets/resource/pipeline/AutoFight/Recognition.json:289`）。

所以分区分类（`cls.onnx`）可以直接走**框架原生能力**，不必为了跑推理而在 APK 里再塞一个
推理运行时——这本来就是这项移植最大的不确定性之一。

### 3. 但有两块仍然难

- **摄像朝向预测**是**两级网络**：`preprocess.onnx` 承载前处理的唯一实现
  （极坐标几何、参考采样、条带域合成），`polar_with_ref.onnx` 消费 7 通道。
  这不是"一个分类/检测节点"能表达的形状，要么真移植，要么另设计。
- **多尺度模板匹配 + 追踪状态机 + tile 映射**是自研算法，没有现成框架能力可替代。

### 结论与下次真机的第一步

规模从「4859 行 + 推理运行时 + 资产」降为「算法移植 + 复用框架推理」——
**前提风险已经消掉两条，剩下的纯粹是算法工作量**。

**建议下次真机会话的第一件事**：把 `map-locate` 补充包装上，先用一个最小的
`NeuralNetworkClassify` 节点试跑 `map/cls.onnx`。
这一步能**一次性验证「资产可用 + 框架能跑这个模型」两条前提**，
再决定投不投那几千行算法移植——而不是先写几千行再发现模型跑不起来。

---

## 2026-09-29 · 全量流水线审计：129 个自定义组件 → 60 真实 / 64 noop / 5 未注册

**提交**：`4bb1200`、`4031283`、`eeda0a1`

上一轮只审了 `OutpostTrading`。这轮把同样的标准推到**全部流水线**：
提取所有 `custom_action` / `custom_recognition`（129 个唯一名字），逐个对照我们的注册表，
再以 47 个 task entry 为根做可达性遍历，判断"哪些缺口真的会被用户碰到"。

### 一、结果

**60 真实 / 64 noop / 5 未注册**。但真正要紧的只有少数——
审计的价值来自"读上游 + 判断影响"，不是数名字。

### 二、修掉的（按影响排序）

**P0 · 采集（AutoCollect）在两种模式下都"成功结束但什么都没做"**

| 缺口 | 为什么致命 |
|---|---|
| `FailureCollectorRunTask` noop | 它是**采集路线的执行器**（`AutoCollect.json:504` 等 82 处 + 环境监测全部 Job 节点）。noop → 子任务根本没跑、失败也不记录，外层只看到成功 |
| `ItemQuantitySatisfied` **noop-恒真** | 采集「目标库存模式」的 `SubSkipped`（语义是"库存已达标→跳过采集"）**恒真且排在 next 第一位**（`AutoCollect.json:485-495`）→ 所有路线被判达标直接跳过 |

修法：`FailureCollectorSupport`（纯逻辑，11 条测试）+ 用已绑定的 `MaaContextRunTask`
真正跑子任务，成败由 `MaaTaskerStatus` 判定；**RunTask 本身无论子任务成败都返回成功**
（对齐上游——失败留给 `Finish` 汇总，`Finish` 有失败即失败）；失败表随任务开始清。

`ItemQuantitySatisfied`：把上游 `pkg/boolexpr` **完整移植**成 Kotlin（词法 + 递归下降，
不支持的语法一律抛异常而不是静默 false），并对库存求值。
**空库存的正确语义是"不知道/未达标"** → 门控只会**放行采集**、不会跳过，
且空库存时打一条（仅一次）告警——免得以后把"门控生效"当既成事实。

**P1 · 送货与环境监测**

- `DeliveryJobsResolveOngoingDepotAction` noop → **next 从来不被设置 → 静默死路**。
  实现时**顺带修掉一处真实语义偏差**：`AutoDeliverySupport` 原来拿"规范化后的中文区域文本"
  当区域 ID，而上游用的是 `en_us` 区域名去非字母数字（保留大小写）——
  该 ID 直接拼成 `DeliveryJobsOngoingDeliveryFor<ID>`，**用错必然指向不存在的节点**。
- `CameraScanAction` **根本没注册**（比 noop 更糟：引用它的节点会硬失败），
  而「环境监测」在移动端任务列表里可见可选。实现：九宫格 8 步 + 复位 + 三环路径，
  每步移动前后各识别一次，命中即成功；走完未命中/移动失败/停止/超时/参数非法 → **失败 + 日志**。

### 三、核心任务的剩余缺口：全部卡在大件 C++

| 核心任务 | 剩余缺口 | 上游规模 |
|---|---|---|
| 采集 | `MapLocateAssertLocation` | MapLocator **4859 行 C++** + 资产 |
| 送货 | `MapFind`（自动寻图） | WorldMap/MapNavigator **18934 行 C++** |
| 送货 | `IconRecognition`（优先装箱） | IconRecognition **9705 行 C++** + 图标资产 |

这些**必须真机 + 资产**才能做（图像处理，离线盲写只是负债）。已在此记录，
下次有手机时按"资产下载 → 小步移植 → 真机验证"推进。

### 四、任务可见性（不让人选到跑不起来的东西）

审计顺带发现：**移动端任务列表里有根本跑不起来的任务，却没有任何提示**。
按项目既有惯例（自动采集/自动囤货/购买稳定物资已有同类标注）在名字上直接说清：
- `SeizeDeliveryJobs`（抢委托送货）→ 【暂不可用·移动端缺少目标扫描与地图搜索】
- `TrialOfSwordmancy`（选剑演武）→ 【暂不可用·移动端未实现求解】
- `DeliveryJobs`（转交委托）→ 【移动端基础版：送货/装箱/回收站可用；自动寻图与优先装箱待实现】
- `EnvironmentMonitoring`（环境监测）→ 【移动端基础版：相机扫描已实现；路线定位待 MapLocator 移植】

只改 label 显示文本，不影响流水线；已 dry-run 验证 8 个名字全部命中。

### 五、教训

**1. "恒真"比"恒假"更危险。** 恒假只是分支不命中（用户会看到任务失败/卡住）；
恒真会让**"跳过"类分支永远先命中**——而这类分支往往排在 `next` 第一位。
`ItemQuantitySatisfied` 恒真 = "所有路线都判达标"，任务"成功"结束但什么都没做。
**审计 noop 时要专门找值是 `true` 的那些**（`falseRecognitions` 里混着 `noopTrue` 注册）。

**2. 未注册 > noop > 静默 return。** 未注册会硬失败（至少用户知道）；
`noop-success` 假装成功；而**连日志都没有的静默 `return 1`** 最坏——
它连"这里没实现"都不告诉你。三类都要按"必须打日志"的标准清理。

**3. 没实现的组件，要么真做，要么在用户看得见的地方说清楚。** 既不做也不说，
等于让用户替我们试错。

---

## 2026-09-28 · 真机联调第一轮：5 个真实 bug + 据点交易静默 stub 审计

**提交**：`7953871`、`088598d`、`64836df`、`7012830`、`1d33cad`、`d2a9fa8`、`ab52e47`、
`4d20066`、`ccb35e0`、`c03c7cb`（CI 全绿）

手机可用后做的第一轮真机验证。**这一轮几乎没加新功能，全在修"离线看不出来"的东西**——
而它们每一个都是"能编译、单测全过、真机上行为错"的类型。这恰好印证了本项目一直的担心：
**编译 + 单测证明不了行为**。

### 一、真机拿到的真实 bug

| # | 症状（真机） | 根因 | 修法 |
|---|---|---|---|
| 1 | 囤货 `seen=0` 失败；干员列表扫描读不出东西 | `ocrProbe` **无条件**开 `only_rec`。它的语义是"只识别、不做文字检测"，把整个 ROI 当成**一行**文字——对货卡网格/干员列表必然只回一个大框乱码（实测 `box:[164,121,700,430] text:"注"`）。上游只在 bettersliding 的**单数值** OCR 上开它 | `only_rec` 改为逐调用点判定（单数值才开），并把"可疑结果"判据纳入 `hit=false` |
| 2 | `BetterSliding 读不到滑条起点框` → 无限重试 | `readHitBox` 想从 `MaaTaskerGetRecognitionDetail` 的 `detail_json` 里取框。但 **`And` 组合识别的 detail_json 根是数组、且没有顶层 box**（box 只在框架回调侧） | 改用**回调第 7 个参数 `box`**（框架本来就给）；detail_json 只作兜底 |
| 3 | `BetterSliding 读不到滑条上限` | 同一底层原因的另一半：解析器只认对象根，`And` 的数组根 `asMap` 直接 null。**凡挂在 And 节点上的读取（FindStart/FindEnd/GetSliderMaxQuantity/CheckQuantity）全都读不到** | 解析器支持数组根（包一层合成根、每项转子节点），OCR 文本优先取 `best`（对齐上游 `RecognitionResults.Best`） |
| 4 | 首次拉起偶尔**静默**不发生（连 onError 都没有） | `connect()` 里 `scope.launch{}` 先返回、`activeLaunch=` 后执行；协程若先跑，会把自己误判成"已被取代"而静默 return | `CoroutineStart.LAZY` + 先登记再 `job.start()` |
| 5 | 武陵货卡稳定读出同一乱码（`[2そ22 ×3]`，跨 3 秒逐字一致），而现场截图画面完全正常 | `autoStockpileRecognitionCallback` 把回调第 6 个参数 **`image`（框架本次识别用的那一帧）解构成了 `_` 丢掉**，改为自己 `PostScreencap`+`CachedImage` 重抓 → "OCR 看到的帧 ≠ 框架看到的帧" | 第一次尝试用**框架帧**，重试/无帧才自抓；只销毁自建 buffer，框架帧绝不 destroy |

### 二、这一轮最大的教训

**1. 框架已经给了的东西，不要自己再造一份。**
bug #2 #3 #5 都是同一个病根：我们试图"自己取"框架已经准备好的输入——
自己解析 detail_json 取 box、自己截屏取帧。框架的**回调参数**里就摆着
`box`（第 7 个）和 `image`（第 6 个），而 Go 绑定里对应字段明明白白
（`CustomActionArg.Box` / `CustomRecognitionArg.Img`）。
**移植时先把回调签名逐个参数核对一遍**，比事后调三个 bug 便宜得多。

**2. "时序 flaky 的测试"可能是真产品 bug，也可能真是测试脆弱——两条都得用桩确定性复现。**
- `spawnFailure_reportsOnceWithoutRetry` 看着像测试脆弱 → 实际是**真产品竞态**（会静默丢弃首次拉起，生产上偶发且难查）
- `processAliveUntilBinder_connects` 看着像产品 bug → 实际是**测试脆弱**（协程体内部回调 `countDown` 时 `isActive` 仍为 true，`awaitConnected()` 后立即读就撞窗口；拉大窗口 5/5 复现、真实 IO 2000 次自然捕到 3 次）

**3. 测试不要赌毫秒。** 改用"等待目标条件本身"（条件轮询 + 足够大的兜底），
而不是"把超时从 300ms 调到 5s 希望够用"。后者只是把概率降低，前者才是确定。

**4. 诊断要能区分"两条不同的失败"。** 探针在取帧失败时会静默 `return@repeat`，
现象（0 候选）与"真读到乱码"完全一样。所以补了取帧返回值日志 + 失败帧导出
（`probe_dump/goods-<region>-<ts>.png`）——**没有可区分的证据，就只能猜**。

### 三、据点交易「静默 stub」全量审计（本轮最大收获）

真机跑完据点交易后做了一次节点覆盖审计，挖出 **4 处 `noop-success` 伪装成功**：

| 缺口 | 后果 |
|---|---|
| `OutpostTradingReserveSession` 整个 noop | 「保留 N 件 / 永不出售」语义全丢；BetterSliding 保持管线默认 `TargetQuantity=999999` → **把库存尽可能全卖掉**；设了「永不出售」的物品**会被选中卖掉**；活动物品缺额度换算 → 可能超额度 → 触发 `AidQuotaExceededStop` → **整个任务被 Stop** |
| `OutpostTradingPrioritySession` 只实现 `configure_strategy`，其余 7 个 operation **静默 `else -> {}` 返回 1** | 用户的「优先售卖规则 / 仅售优先项」配置**无声失效**。**而且它连 noop-success 日志都不打**——比 noop 更隐蔽 |
| `OutpostTradingPriorityItem` 选品不读 黑名单/缺货/已满足 | 优先物品不生效；NeverSell 不被排除 |
| `outpostTried` 任务开始时没人清 | 同进程内二次跑任务可能把上次残留当"已尝试" → 提前 exhausted |

已全部补齐（`c03c7cb`）。**规则（写进项目约定）：任何未实现的 operation 必须打日志。**
"静默 return 成功"比"明确失败"危险得多——前者会让排查方向完全跑偏。

### 四、需要更正的历史记录

本文件早先写过「**`OutpostTradingPrioritySession` 已真实实现覆盖主路径**」——**这句不准确**。
当时的实现只有 `configure_strategy`，其余 7 个 operation 是静默 `return 1`，
既不生效也不报错；它不阻塞主链，但也远谈不上"覆盖主路径"。
已在 `c03c7cb` 补齐，并在此更正——**凡写"已实现"都要能指到具体行号，
否则就是在制造下一个静默 stub**。

### 五、仍未解决 / 待验证

- **武陵乱码的最终确认**：`image` 修复（`ccb35e0`）需要真机再跑一轮。
  判定表：看日志有没有 `使用框架帧 attempt=0`；若仍 0 候选，取
  `probe_dump/goods-Wuling-*.png` 与同轮 `on_error` 截图逐像素比对——
  相同 ⇒ 帧源不是主因（转查 OCR 预处理）；尺寸不同 ⇒ `CachedImage` 缩放差异；
  尺寸同内容不同 ⇒ 坐实异帧。
- `adopt` 依赖 `OutpostTradingCurrentGoods` 命中（安卓侧是 54×54 图标槽的 OCR 近似，
  上游用图标识别），不命中时仍可由 `commit` 路径绑定——已在代码注释标注。
- 完整的上游仓库页扫描（模板定位 + 逐格数量 OCR + 图标识别）仍未移植。

---

## 2026-09-28 · 测试版诊断报告 + autoEcoFarm + 会客室倒计时

**提交**：`e009f66`、`2935fc6`（CI 全绿）。三条 lane 并行，都只做**不依赖真机**的部分。

### 一、测试版诊断报告（`e009f66`）

**动机**：真机调试最缺的是证据。之前只有 MaaFramework 自己的 `maafw.log` 和失败现场图，
没有「这次跑经历了什么、在哪一步决策了什么」的结构化记录。手机现在不可用，但报告本身
是可以先做好的——它是将来真机回归效率的关键。

**设计**（`diagnostics/RunDiagnostics.kt` + `RunDiagnosticsPolicy.kt`）：
- 每次任务落一份 JSONL：`log/report/run-<时间戳>.jsonl`，一行一事件
  （`ts` / `event` / 原始 `detailsJson`），关键决策点另有人话 `note()`。
- **三条硬上限**（用户明确要求「不要浪费储存空间」）：单文件 8 MiB、保留 10 份、
  目录总量 64 MiB；写满落 `{"truncated":true}` 标记而**不是静默截断**，超限按时间从旧删。
- release 下 `start(debug=false)` 后**全部 no-op，不创建任何文件**。
- 写盘走单线程后台、异常吞掉降级——诊断不能把任务搞挂。
- 上限计算与删除选择抽成纯逻辑 `RunDiagnosticsPolicy`（12 条边界用例，含磁盘预检不足时的取舍）。

**已加 note 的点**都是真机调得最久的地方：囤货挑货结果（选中/价格/候选数）、
BetterSliding 节点推进、MapNavigator 参数解析的成功与失败原因。

### 二、autoEcoFarm 整组（`e009f66`）

审计里这两个识别是最像「可以独立补齐」的（包只 622 行），**但真读进去发现不是**：
它们所在的两条识别链都依赖同包里三个当时还是 noop 的动作，其中
`autoEcoFarmOverrideTargetTemplate` 是**前置**——没有它，分支被点亮了也会一直找
「追踪标记」而不是「农田标记」，表现就是**随机转视角找东西**。

这正是本项目吃过亏的「**stub 静默成功**」：看起来修好了，实际永远找不到目标。
所以整组 5 个注册项（2 识别 + 3 动作）一起做了真实实现：

| 文件 | 纯逻辑要点 |
|---|---|
| `AutoEcoFarmSwipe.kt` | 0.9 衰减 / 0.1~1.0 夹取 / Go `int()` **向零截断** |
| `AutoEcoFarmNearest.kt` | 左上角插值 + 中心距离平方；读 `filtered` 而非 `all` |
| `AutoEcoFarmOverride.kt` | 只改 `recognition.param.template` 的 override 构造 |
| `AutoEcoFarmSleep.kt` | 分片 sleep 计划 + `mm:ss` 格式化 |

### 三、会客室两个识别（`2935fc6`）

上游 `dijiangrewards` 整包 328 行，**只有一个 OCR 调用点**，其余全是可单测的纯逻辑，
是这一批里性价比最高的。之前恒假导致帝江号奖励与信用购物 N2 里
「等待交流结束 / 长时间保活」两个分支永不可达。

- 倒计时解析：`mm:ss` 与 `hh:mm:ss`，分/秒 ≥ 60 判失败。
- `best→filtered→all` 的 OCR 文本提取（并兼容 `results` 外层 + 递归兜底，避免猜错路径
  导致「永远不命中」）。
- 两个节流状态机：10s 提示间隔 / 20min 保活间隔 / 2min 会话中断重置。

**一个容易当冗余删掉的函数**：`previousNonSpaceRune`。没有它，`1:0:30` 会被
正则从中间匹配成 `0:30`——已在测试里钉死这个用例。

**已知降级**：上游命中时用 `maafocus` 打浮层倒计时，本项目没移植那条渲染通道，
改成 `Ln.i` 日志。**不影响命中判定**，只少了浮层；要还原得先移植 maafocus/i18n。

### 教训

**校验「真实注册会不会被 noop 循环覆盖」必须用行号边界，不能靠 grep 猜。**
我先后用 `grep '"名字",'` 和「向上找最近的 `val xxx = listOf(`」两种办法检查，
**都误报**了：前者会把 `regReco("名字", ...)` 调用行本身算进去，后者会在名字位于
列表**之后**时错误地归到前一个列表。最终用 Python 算出两个 noop 列表的精确行范围
（`otherActions` 3399–3453、`falseRecognitions` 3500–3521）与注册行号（3492/3496）
比对才确定无覆盖。**这个检查以后要固化成脚本**。

---

## 2026-09-28 · 关掉上游自动构建 + 补充包（大资产按需下载）

**提交**：`5f6415d`、`560a260`、`b9f7293`（CI 全绿）

### 一、停掉上游自动同步/自动构建

`sync-upstream.yml` 原先每天两次自动检查上游、直接提交并触发打包。移除 cron，理由：

1. **上游一改，我们依赖的东西就可能跟着变**——节点名、资产路径、语言文件的 key。
   `prepare_maaend.py` 里的补丁是**按名字**打的，上游改名后它们会**静默失效**：
   照样编得过、照样出包，只是行为悄悄变了。这类失败最难发现。
2. 上游更新应该是有意识的：想要哪个特性，我们自己挑进来。

`workflow_dispatch` 保留（需要跟上游时手动跑）。顺带修掉冗余：手动同步时「推送」
已经会触发 build-apk（它监听 main 的 push），原来的显式触发会白白打两遍包。

### 二、补充包

**先算了笔账**：把那些模型全打进 APK 会从 **275 MiB 涨到约 479 MiB**：

| 项 | 大小 |
|---|---|
| 当前 APK | 275.3 MiB |
| MapNavigator（P2 定位 + P3 寻路） | 157.4 MiB（其中 navmesh 三件套 134.7） |
| 检测模型（自动战斗 + 协议空间） | 46.5 MiB |
| **全塞进去** | **479.2 MiB** |

绝大多数人只用得上一部分，所以改成**按需下载**——想要什么就取什么。
实装后 APK 只多了 16 KB（清单与代码本身）。

三个包（清单 `assets/supplement-packs.json`，pin 到 MaaEnd-AI 的具体 commit）：

| 包 | 大小 | 解锁 | 依赖 |
|---|---|---|---|
| `map-locate` | 22.7 MiB | 传送、落点断言、基础导航 | — |
| `map-navmesh` | 134.7 MiB | 精确寻路 | map-locate |
| `detect` | 46.5 MiB | 自动战斗、协议空间识别 | — |

分层：
- `SupplementPack` / `SupplementPackLocal` —— **纯逻辑**（清单解析、安装状态、依赖、
  git blob 校验和、扫本地目录），本机可测
- `SupplementPackInstaller` —— 流式下载、边写边算校验和、校验通过才 `rename` 到位、
  支持取消 / 磁盘预检 / 依赖检查
- `SupplementPackText` —— 包 id → 文案资源的静态映射
- `SupplementPackSection` + Settings 接线 —— 设置页里的补充包区块

### 三个刻意的决定

1. **校验和用 git blob SHA-1 而不是 SHA-256**：GitHub API 免费给前者，后者要先把
   204 MiB 全下下来才算得出（生成清单时就得多下一遍）。这是**完整性校验**
   （防截断/串包），不是安全边界；传输走 HTTPS 且资源 pin 在固定 commit。
2. **包名文案走静态映射，不用 `getIdentifier`**：release 的资源压缩看不到动态引用
   的字符串会把它裁掉，编译期毫无提示，装到机器上才发现包名显示成了 `map-locate`。
3. **清单 pin 到具体 commit 而不是 `main`**：否则同一份 APK 在不同时间下到不同的
   资产，校验和必然对不上。

### 教训

- **CI 是唯一能验证 Android/Compose 代码的地方**。这一批里 installer 与 UI 本机都
  编不了，CI 一次就报出 6 处错误（`UiState()` 缺默认值、`isActive` 少 import），
  全是本机看不出来的。
- **两个 lane 并行时会各自发明契约**：我在 prompt 里给的字符串名是下划线形式，
  而我自己写资源时用了点号——designer 发现后把资源改成了下划线并加了两段式兜底查找。
  最后我把它换成静态映射（兜底查找同样会被资源压缩裁掉）。
- **用户对"卡住"很敏感**：我在前台闷跑一次 204 MiB 下载（navmesh 单文件 98 MiB、
  每个文件还配了 3 次重试），看起来就像死了。后来换成用 GitHub API 直接取 blob SHA，
  根本不用下载。

---

## 2026-09-28 · 修完 7 个历史失败，单测重新卡住构建

**提交**：`ef425a7`、`99a14a7`、`41eedb6`、`1ef7ef2`（CI 全绿，`continue-on-error` 已摘）

### 做了什么

CI 的单测步骤一直挂着 `continue-on-error`，因为一接上就暴露 7 个历史失败。
它们看着像「环境问题」，实际是 **4 个真 bug + 1 个过期期望 + 2 处上游/我们自己的数据缺陷**：

| 失败 | 真实原因 | 修法 |
|---|---|---|
| `GitHubUpdateTest` 429 / 403 | `releases()` 会依次试 4 个镜像端点。429 时只记 `lastOutcome` 再 `continue`，后面三个端点没有响应就抛异常，把 `RATE_LIMITED` **覆盖成 `NETWORK`**——真原因丢了还白打三次请求 | 限流是账号/IP 级的，换镜像照样被限 → 直接返回 |
| `GitHubUpdateTest` resolve | `selectAsset` 在「本机 ABI 无变体、也没有 universal」时 `return apkAssets.firstOrNull()`，**把 ABI 校验整个架空**（会把 x86_64 的包推给 arm64 设备），且与函数自己的文档注释矛盾 | 返回 null，交由上层报 `NO_MATCHING_ASSET` |
| `UiTextBoundaryTest` | `SessionViewModel` 两处直接构造 `UiText.Verbatim` 塞硬编码中文，绕过 i18n | 改用 `uiTextOf(R.string.*)` 并补中英文资源 |
| `CurrentProjectI18nTest` | **是我们自己拼坏的**：`prepare_maaend.py` 在 `$task.X.description` 后面拼中文后缀。i18n 引用**整串就是 key**，所以这个 description 在每种语言下都解析不出来（运行时退化成原文） | 移动端说明改放 `label`（本就是字面量） |
| `CurrentProjectI18nTest`（另一条） | `task.BatchUseDetector.option.Times.input.error` 被 PI 引用，但**上游五种语言都缺**（已在 MaaEnd 仓库确认是上游遗漏） | 打包期按语言补上 |
| `CurrentProjectLoadTest` | `tasks/setting/Keymap.json` 是**键盘快捷键（hotkey）配置**，而 `PiParser` 在 Android 端明确不支持该类型 → 丢 option → `global_option` 引用悬空又报一条 error | 按既有模式排除该 import（手机没有键盘；已确认无他处引用、无 pipeline 依赖） |
| `SettingsViewModelTest` | 期望 2 次检查、实际 3 次。三个来源（切渠道重查 / 填 CDK 静默查 / 手动查）在代码里都有理由，测试与它们**同属初始提交**——从第一天起就没算上「切渠道」那一次 | 更新期望并写明三个来源 |

### 为什么值得做

`continue-on-error` 让 CI 失去了把关能力：单测红了构建照样绿，等于没有单测。
摘掉开关之后，**793 个测试真正卡住构建**。

### 方法上的收获

**默认日志只有「类名 + 行号」，看不到断言消息。** 加了 `ef425a7` 一步把 XML 报告里的
`<failure message=...>` 打出来之后，一轮 CI 就拿到了全部 7 条的真实原因——
否则要为了看一句 message 反复跑 8 分钟一轮的构建。这个诊断步骤保留着。

### 顺带合上另一道闸门

`scripts/check_i18n_strings.py`（校验中英 key 对齐、占位符集合一致、英文里不残留中文、
单文件内无重复 key）早就写好了，脚本自己的文档里也写着「可作 CI gate」——但一直没人接。
这与单测是同一类缺口：**工具在，闸门没合上**。已接进 CI，放在构建之前
（这类静态检查几秒出结果，不该等 5 分钟编译完才发现）。当前实测干净：中英各 613 条。

### 顺带确认

- 上述修复全部有测试或实测支撑：限流/ABI 两条由既有断言直接验证；i18n 与 Keymap
  用 `prepare_maaend.py` 自己的 `strip_json_comments` 实测过解析路径
- `upstream/maaend` 的 submodule 指针在本地被改动过（`9f90c71` → `fcdc53a-dirty`），
  **没有提交**——提交它会误钉一个上游提交

---

## 2026-09-28 · MapNavigator P1：参数解析 + 动作编排

**提交**：`e225871`、`7bce9cc`、`b51eb65`、`966508e`（均已推送，CI 全绿）

### 做了什么

MapNavigator 是 18934 行 C++，还依赖 Navmesh(11757) + MapLocator(4859) + Zipline(1562)，
且 BNAV/ONNX 资产不在本 checkout（可从公开仓库下载，见下文更正）。所以先按已产出的分期方案做 **P1（参数与动作编排，不含定位）**。

**1. `MapNaviParam`：完整参数解析（44 条测试）**

之前只剩一个降级实现——对象形态只读 `action/zone/yaw` 三个字段、数组形态是「位置固定式」误读、
而且**没有任何校验**（动作名写错会被静默当成 zone 或直接吞掉）。现在覆盖：

- 根参数 12 个字段（含 `nav_file` 优先于 `navmesh_file`、`snap_radius` 优先于 `navmesh_snap_radius`）
- 路点对象形态：`action|actions` 合并、`target` 优先于 `x,y`、`zone_id`/`strict`/`angle` 各自的别名组
- 路点数组形态：`[x, y, ...rest]`，`rest` 里布尔是 strict、动作字符串/数组是动作、其余是 zone
- 动作展开：空 → RUN；含非 RUN 时跳过其中的 RUN；全是 RUN 时逐个保留
- 文本三形态：非空字符串 / 非空字符串数组 / `{"node": ...}`

**2. 执行侧换成按归一化路点分发**

- `COLLECT`/`DIG`/`INTERACT` 走**真实子流水线**（对齐 `async_prompt_action.cpp`）：
  截断共用出口、按需注入 expected、`rec` 时改成 DoNothing
- `FIND` 用 `find_stop` 做视觉伺服（48 步 × 30°）；只配 `find_arrive` 的点**明确失败**
- `FIGHT` 新增 `MotionSupport.attack()`（按钮 1030,551，取自上游 adb_input_backend）
- `HEADING` 改开环：维护累计朝向估计，发 `yawDelta(target - assumed)` 再前推 270ms
- `ZIPLINE` 明确失败（P1 无规划数据），不再可能被静默当 RUN 走

### 刻意保留的上游规则（都有用例）

1. **数组里「看起来像动作」的字符串不许当 zone_id**：全大写 / 含下划线 /
   大小写不敏感等于动作名 —— 写错大小写会静默走错区域，宁可整条路线失败
2. **`NAVMESH` 的坐标必须来自 `target`**（`x,y` 不算），且强制严格到达
3. **任一路点失败则整条失败**，不做「跳过这个点继续」

### 为什么先做 P1

没有定位（MapLocator 未移植）时，`NAVMESH`/`TRANSFER`/`PORTAL` 只能降级成「前进近似」，
但 **`COLLECT`/`DIG`/`INTERACT` 本来就不依赖定位**——它们只是「走到点后跑一个子流水线」，
所以这一批能力可以现在就真正可用，而不用等整条定位链。

### 教训

1. **`const val` 不能出现在类里**（只能顶层 / object / companion）。CI 直接指出了行号。
2. **回调参数 `nodeName` 是 `String?`**，传给需要 `String` 的函数前要判空——
   本地不编译 MaaRunner，这类错误只有 CI 能抓。
3. **改测试时不要用全局 `replace`**：我用它改 fixture 时误伤了另一个测试
   （`item("char_a")` → `item("A")`），两条测试一起挂。
4. **JUnit 桩要补齐重载**：`assertEquals(double, double, double)` 与 `@After`/`@Before`
   都不是"顺手就有"的，缺了会报 unresolved。

### 未做（**并更正一处此前的错误判断**）

此前我把 P2/P3 记作「被不在 checkout 的资产阻塞」。**这个说法不准确**——资产是可取的，
只是没有任何构建步骤去下载它们。实际情况（已用匿名 HTTP 206 实测确认可达）：

| 资产 | 大小 | 用途 |
|---|---|---|
| `MaaEnd-AI/map/navmesh/base.nav.gz` | **98 MB** | P3 寻路（BNAV） |
| `.../base.fields.nav.gz` + `base.occluder.gz` | 22 + 20 MB | P3 清洗网格 / 遮挡 |
| `MaaEnd-AI/map/cls.onnx` | **23 MB** | P2 区域分类 |
| `.../map/cameraorientation/preprocess.onnx` + `polar_with_ref.onnx` | 254 KB + 390 KB | P2 镜头朝向 |
| `.../map/tile_mapping.json` | 55 KB | P2 底图切片 |
| `MaaEnd-AI/detect/AutoFight/autofightv12.onnx` | **38 MB** | 自动战斗 |
| `MaaEnd-AI/detect/ProtocolSpace/best.onnx` | **10 MB** | 协议空间 |

仓库：`MaaEnd/MaaEnd-AI`（公开，默认分支 main，约 1.6 GB）。
本 checkout 的 `assets/resource/model/` 下**只有 OCR 模型**（`det.onnx` / `rec.onnx`），
其余一个都没有——而 `prepare_maaend.py` 已经从 `MaaCommonAssets` 下载 OCR 模型，
所以「构建期下载」这个模式是现成的，照做即可。

引用出处：`upstream/maaend/tools/pipeline-generate/data/scripts/navzone_utils.py:36-41`
（`NAV_REMOTE_TEMPLATE` / `NAV_SUBMODULE_API`）。

**所以 P2/P3 不是"能不能写"的问题，是"要不要把这些资产打进去"的问题**：
P2+P3 合计约 163 MB，再加两个检测模型约 48 MB。这会把 APK 体积推高数倍，
是需要产品决策的取舍，不是纯技术问题——所以没有擅自加进构建。

| 项 | 状态 |
|---|---|
| MapNavigator P2（定位） | **可做**：需下载 cls.onnx + cameraorientation（≈23.6 MB）+ 底图资产 |
| MapNavigator P3（真寻路） | **可做**：需下载 base.nav.gz 三件套（≈140 MB） |
| 自动战斗 / 协议空间模型 | **可做**：需下载 autofightv12.onnx / best.onnx（≈48 MB） |
| `CaptureUid` | ~420 行 Go；纯遥测，无消费者、无功能影响 |

---

## 2026-09-28 · 据点交易干员子系统完成 + MapNavigate 数组路点修复

**提交**：`20f9b90`、`de39848`、`e225871`（均已推送，CI 全绿）

### 做了什么

**1. 干员选择子系统全部八层落地**

| Kotlin | 移植自 | 说明 |
|---|---|---|
| `OperatorOcrMatch` | `internal/ocrmatch` (171) | 两层严格相等匹配 |
| `OperatorDataset` | `selectiondata` (220) + `operator/data.go` (248) | 数据加载/校验/候选派生 |
| `OperatorSelection` | `operator/selection.go` (543) | 分档/完美候选/偏好/DFS 全局分配 |
| `OperatorCache` | `operator/cache.go` (479) | 快照持久化、分级容错、原子写 |
| `OperatorMatching` | `operator/matching.go` (119) | 三种匹配 + 前缀噪声回退 |
| `OperatorSession` | `operator/session.go` (413) | 任务级会话、7 种 operation |
| `OperatorScan` | `operator/scan.go` (393) | 跨帧扫描状态机、OCR 交接槽 |
| `OperatorRecognitions` | `recognition.go` (404) + `conflict.go` (174) | 六个识别 + 冲突识别 |
| `OperatorRuntime` | — | 运行时聚合（数据/缓存/会话/扫描），不依赖 Android，可本地测 |

MaaRunner 侧只做适配：七个识别 + 一个 action 从 noop 换成真实实现。

**2. 修掉 MapNavigateAction 的一个静默 bug**

上游路点有两种形态（对象与数组 `[x, y, "ACTION"?, strict?, "zone_id"?]`），
而实现只处理 `JsonObject`，数组路点被 `continue` 整段跳过。
数组是最高频写法——实测 376 处 COLLECT、322 处 NAVMESH、264 处 ZONE 都用它，
**一个都没执行**，日志却照样打印「path done (N steps)」。

### 为什么

这七个识别是恒假、会话 action 是 noop。实际后果不只是「少个功能」：
据点内的干员列表扫描（next 里含 `OperatorListBottom`）**永远到不了底、会一直滑**，
而且 `OutpostTradingLocationPlan` 缺 operator 字段。

### 怎么验的

- 纯逻辑八层 → 本机反射执行 JUnit（新增 112 条，累计 277 条）
- MaaRunner 接线 → CI 编译（`build-apk.yml` + `gradlew test`，两次推送两次绿）

### 教训

1. **`Ln` 依赖 `android.util.Log`**，所以引用它的类无法本地编译。
   把日志改成可注入的 `var logger: (String) -> Unit = {}` 之后，
   `OperatorRuntime` 这一层就保持可测——分层时「谁能碰 Android」要提前定死。
2. **`?: fail(...)` 会推断出公共父类型**，导致 `val x = f() ?: fail(...)` 里
   `x` 变成 `Any`。改成显式 `if (x == null) return fail(...)`。
3. **我用 `replace` 全局改测试 fixture 时误伤了另一个测试**
   （`host.currentItems = listOf(item("char_a"))` 被连带改成 `item("A")`）。
   批量改测试要用更精确的锚点，或者改完逐条看差异。
4. **JUnit 桩要补齐注解**：只桩 `@Test` 会在用到 `@After` 时报「unresolved reference」。

### 未做

| 项 | 规模 | 原因 |
|---|---|---|
| MapNavigator P2–P6 | 18934 行 C++ + Navmesh 11757 + MapLocator 4859 + Zipline 1562 | P1 已交付。P2/P3 的资产可从公开仓库 `MaaEnd/MaaEnd-AI` 下载（见本日 P1 条目的更正说明），但合计约 163 MB，需先定 APK 体积取舍 |
| `CaptureUid` | ~420 行 Go | 纯遥测 + 供 cpp-algo 读账号标识；无消费者、无功能影响，且需真机验证 |

---

## 2026-09-28 · 据点交易「干员智能选择」（operator 子系统）纯逻辑层

**状态**：纯逻辑四层完成，胶水层与剩余三层进行中。

### 做了什么

上游 `outposttrading/operator` 是 2967 行 Go + 391 行依赖包，负责「据点派驻哪个干员最优」。
按既有分层模式（纯逻辑可本地单测 / JNA 只做胶水）拆成 Kotlin：

| Kotlin | 移植自 | 行数 |
|---|---|---|
| `OperatorOcrMatch.kt` | `internal/ocrmatch/match.go` (171) | 110 |
| `OperatorDataset.kt` | `internal/selectiondata/data.go` (220) + `operator/data.go` (248) | 290 |
| `OperatorSelection.kt` | `operator/selection.go` (543) | 450 |
| `OperatorCache.kt` | `operator/cache.go` (479) | 380 |

测试 209 条（本批新增 53 条），本机与 CI 双通道验证。

### 为什么

当前 Android 侧这七个识别器/动作全是 stub（`MaaRunner.kt:2738-2744` 恒 false、
`OutpostTradingOperatorSession` noop），实际后果不是「少个功能」而是**据点内的干员列表扫描
永远到不了底、会一直滑**。同时 `OutpostTradingLocationPlan` noop 使据点计划缺 operator 字段。

### 三处最容易写错、且错了只表现为「选人变了」的地方

1. **DFS 的平局方向**：`buildRestoreAssignmentPlan` 每个据点的分支是
   {分配任一候选} ∪ {跳过}，跳过分支必须排在候选循环**之后**，才能保证平局时
   「新地区先锁定」。测试用一个「两据点抢同一人」的用例把它钉住。
2. **字典序比较**：`isBetterRestorePlan` 是 覆盖数 → 沿用数 → 可复用数（大者优），
   然后**只有据点集合相同时**才比总成本（小者优）；集合不同直接判否。
   所以「平局保留先到者」是在比较函数里实现的，不是入口前置校验。
3. **`OutpostProsperityMaxBonusTier` 的口径**：它不是「比较时取 max」，而是
   「据点发展值已满、发展值词条失效后」的档位；用哪个由据点是否在
   `outpostProsperityMaxLocations` 里决定，方向始终越小越好。

### 另一个必须保留的语义：缓存里 nil 与空数组不是一回事

`operators == null` = 从未扫描 / 已被判失效；`ids == []` = 扫过且该账号确实没有相关干员。
把空数组当成「没缓存」，会让每轮任务都全量滚动列表且**永远选不出人**。
测试同时覆盖了读取侧的分级容错（顶层坏 → 整份当不存在；单账号坏 → 只丢该账号）
与写入侧的反向策略（先规范化再整份校验，任一账号非法则整份不写、旧文件保持原样）。

### 数据来源（一条容易踩的坑）

`selection_data.json` 经打包链路进的是 `assets/pi.zip`，App 解包到 `<externalFilesDir>/pi/`，
所以运行时路径是 `projectRoot/data/OutpostTrading/selection_data.json`。
**不要**照抄 `MaaRunner.loadDeliveryCatalogFromApk()` 的 `assets/data/...`——
当前打包方式下 APK 根 assets 里没有散装 data 目录。

### 已验证的规模判断

- operator 子系统：2967 行 Go，可移植（四层已完成）
- MapNavigator：18934 行 C++，且还依赖 Navmesh(11757) + MapLocator(4859) + Zipline(1562)；
  其 BNAV 与 ONNX 资产不在本 checkout，但可从公开仓库 `MaaEnd/MaaEnd-AI` 下载
  （详见本日 P1 条目的更正说明）。已产出分期方案，P1（参数与动作编排，不含定位）已交付
- 顺带发现一个独立的真 bug：`mapNavigateCallback` 只处理 `JsonObject`，
  **数组路点被整段跳过**——而 `[x,y,"COLLECT"]` 是最高频形态（376 处）

---

## 2026-09-28 · 补齐三条功能链的共同阻塞点（BetterSliding / 稳定物资 / 执行周期）

**提交**：`8f4d050`、`a97bffc`、`e174b9c`（均已推送，CI 三次全绿）

### 做了什么

| 提交 | 内容 | 规模 |
|---|---|---|
| `8f4d050` | `BetterSliding` 全量移植（不再 noop） | 8 个主文件 + 7 个测试，~2900 行 |
| `a97bffc` | `AutoStockStapleQuantityControlAction` 实现 | 1 主 + 1 测，~430 行 |
| `e174b9c` | `ScheduleRecognition` 周期门控实现 | 1 主 + 1 测，~230 行 |

### 为什么

**核心问题**：`BetterSliding` 在移动端一直注册成 **noop 成功**。看起来只是"少个功能"，
实际后果是据点交易 6 个据点、囤货、稳定物资购买**全部按滑条默认值买错/卖错数量**，
而日志上完全看不出来（动作上报成功）。它不是三个功能之一，是三条链的共同阻塞点。

**怎么发现的**：不是读代码读出来的。先怀疑导航链坏了，结果用代码搜索确认导航链
（`SubTask` → `Scene*` 资产）本来就是好的、33 个模板全在；再把三条任务的
`custom_action` 逐个与 `MaaRunner` 的注册表对照，才定位到它。

### 怎么验的

没有真机。所有可测逻辑都抽成**不依赖 MaaFramework 的纯逻辑层**，用
`scripts/verify_pure_logic.sh` 在本机真实编译 + 反射执行 JUnit 测试：

- 5 个纯逻辑层：`Support`（normalize/types）、`Params`、`Overrides`、`Ocr`、`Decision`（handlers 的判定部分）
- 1 个编排层：`Session`（host 接口后面，可本地跑）
- 测试 130 条，本机与 CI 双通过；CI 里 **Run unit tests 是 success**，不是被 `continue-on-error` 掩盖

真机行为**仍未回归**——本轮的保证范围是「编译正确 + 编排正确 + 纯逻辑正确」，不是「游戏里数量对了」。

### 教训（都是真实抓到的，不是假想）

1. **JUnit 桩写成空实现，会让"全绿"变成自欺欺人。**
   第一版桩里 `assertEquals` 是空方法、`assertThrows` 不执行 lambda，于是"57 个全过"毫无意义。
   换成真断言后**立刻**暴露出一个失败用例。教训：验证工具本身必须先被验证。

2. **测试全绿只证明自洽，不证明对。**
   `BetterSlidingOcr` 第一版按 `results.best.best_template_box` 这种我**猜的** JSON 路径写，
   测试也全绿。后来去读仓库既有代码（`GoodsSupport.collectOcrItems`）才发现：真实做法是
   **递归遍历、完全不认路径**。已改成路径无关实现，并显式标注唯一剩余假设（节点名字段是 `name`）。

3. **移植陷阱：Go 的 `math.Round` 是半数远离零，Java 的 `Math.round` 是负半数向零靠拢。**
   精确点击的位移在反向滑条上是负的，照搬会点歪一像素。已按 Go 语义实现并用真实 -2.5 位移锁住。

4. **Kotlin 的块注释会嵌套**（Java 不会）。KDoc 里写一句 `BetterSliding/*` 会开一个嵌套注释，
   把后面整个文件吞掉，报错是"Unclosed comment"。

5. **上游有些"怪癖"是行为的一部分，别顺手修**：`SliderQuantity` 的 presence 只看键在不在
   （显式 null 也算提供）、Percentage 的夹取顺序使 `availableQuantity=0` 时结果为 0、
   `resetState` 故意不清目标数量。全部写了用例钉住。

6. **一次只能用一条路径做同一件事。** `MaaRunner` 没有绑定 `MaaContextOverrideNext`，
   与其新增 native 绑定，不如用等价的「覆盖 `next` 字段」，假设更少。

### 未做（明确记录，避免下次重新调研）

| 项 | 规模 | 为什么没做 |
|---|---|---|
| `CaptureUid` | ~420 行 Go | 纯遥测 + 供 cpp-algo 读账号标识；本仓库 cpp-algo 未移植、无消费者。需导航到操作手册界面 + 加盐哈希，无功能影响且必须真机验证 |
| OutpostTrading 干员选择子系统 | **2967 行 Go**（11 文件） | 缓存快照/全量扫描/冲突检测/最优档选择。不阻塞据点交易主链（`OutpostTradingPrioritySession` 已真实实现覆盖主路径），缺失仅表现为"选不到最优干员" |
| MapNavigator | **18934 行 C++**（60+ 文件） | navmesh 寻路/滑索规划/灵敏度检测/状态机。是「自动采集」的前置，属独立大工程 |

---

## 基础设施：本地纯逻辑验证

`scripts/verify_pure_logic.sh`（**已进仓库**；早先只在 `.tmp/` 下、是 gitignored 的，
等于文档写着让人用一个不存在的工具）

```
scripts/verify_pure_logic.sh all        # 编译 + 类型检查 + 反射执行全部测试
scripts/verify_pure_logic.sh main       # 只编译主源码层
scripts/verify_pure_logic.sh typecheck  # 用 JUnit 桩类型检查测试文件
scripts/verify_pure_logic.sh run        # 只跑测试
```

中间产物落在 `.tmp/verify/`（gitignored），脚本本身可复现。

- 不需要 Android SDK：用 `~/.gradle/caches` 里已有的
  `kotlin-compiler-embeddable` / `kotlin-stdlib` / `kotlin-reflect` /
  `kotlinx-coroutines` / `kotlinx-serialization` / `annotations` + 本机 JDK
- JUnit 桩由脚本自己生成，**断言是真实实现**（见上文教训 1）
- runner 用反射执行 `@Test` 方法，所以跑的是**真实的测试体**，不是另写一份 harness
- 放在 `.tmp/` 而非 `/tmp`：/tmp 会被系统清理，之前放 /tmp 的副本丢过一次

**能力边界**：只覆盖不依赖 Android/JNA 的纯逻辑。`MaaRunner` 的编译与真机行为
只能靠 CI（`build-apk.yml` 会编译 APK 并跑 `./gradlew test`）。

---

## 相关 skill

`~/.config/opencode/skills/maafw-virtual-display/SKILL.md`

真机调试方法论。核心是两点：**分清 App 原生 UI（用 `uiautomator dump`）与游戏 canvas
（只能截图算坐标）**，以及用 **`adb shell input -d <displayId>` 直接驱动虚拟屏**，
不要按 PIP 换算坐标。
