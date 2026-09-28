# 开发日志 (DEVLOG)

> **用途**：按时间倒序记录每一次开发的**内容、动机、验证方式与教训**。
> 与 [`DEVELOPMENT_EXPERIENCE.md`](./DEVELOPMENT_EXPERIENCE.md) 的分工：
> 那份讲**可复用的移植经验与方法论**，这份讲**这一次具体改了什么、为什么、怎么验的**。
>
> 追加格式：新条目放在最上面，标题写 `## YYYY-MM-DD`，正文用「做了什么 / 为什么 / 怎么验的 / 教训 / 未做」几段。

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
  其 BNAV 寻路数据**不在本 checkout**（来自 MAAend-AI 子模块）。已产出分期方案，
  P1（参数与动作编排，不含定位）约 1.2k–1.8k 行即可交付
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
`.tmp/verify/verify.sh` 在本机真实编译 + 反射执行 JUnit 测试：

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

`.tmp/verify/verify.sh`（gitignored，在 `.tmp/` 下所以 `git status` 干净）

```
.tmp/verify/verify.sh all        # 编译 + 类型检查 + 反射执行全部测试
.tmp/verify/verify.sh main       # 只编译主源码层
.tmp/verify/verify.sh typecheck  # 用 JUnit 桩类型检查测试文件
.tmp/verify/verify.sh run        # 只跑测试
```

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
