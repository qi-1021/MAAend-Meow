# 开发日志 (DEVLOG)

> **用途**：按时间倒序记录每一次开发的**内容、动机、验证方式与教训**。
> 与 [`DEVELOPMENT_EXPERIENCE.md`](./DEVELOPMENT_EXPERIENCE.md) 的分工：
> 那份讲**可复用的移植经验与方法论**，这份讲**这一次具体改了什么、为什么、怎么验的**。
>
> 追加格式：新条目放在最上面，标题写 `## YYYY-MM-DD`，正文用「做了什么 / 为什么 / 怎么验的 / 教训 / 未做」几段。

---

## 2026-10-01 · 清空重建模拟器（手机替代方案）+ 查出 MaaFW 在模拟器上 SIGILL 的根因

### 做了什么

**① 按"清空模拟器 → 只装终末地 → 重新下载编译"重建**
- 删掉 AVD 的 `userdata-qemu.img.qcow2`（37G）做数据区清空；`-wipe-data` 也试过，崩溃与数据无关。
- 装 `game-rescue/apk/base.apk`（1.6G），首启建目录后用 **流式解包** 灌 30GB 外部数据
  （`adb shell 'cd /sdcard/Android/data && tar xf -' < external_data.tar`，避免设备端再落一份 30GB 中转，
  数据分区只有 64GB 会不够）。内部 CE/DE 解包后 **按 `pm list packages -U` 的权威 uid 统一三个目录的属主**
  （tar 里带的是原机 uid，直接 chown 会拿到错的值），再 `restorecon`。
- 重编 App（`scripts/build_local.sh debug`）、装 Shizuku 并用 **APK 内 `lib/arm64/libshizuku.so` 直接启动 server（root）**、
  开调试模式、下载 map-locate 补充包（23M，`pi/resource/image/MapLocator/` 6 个文件）。

**② 模拟器跑游戏的可用配置（关键）**
- **`-no-window -gpu host`**：能进 3D 世界（完整 HUD + 左上小地图 + 任务进度，0 个 `HG_ALWAYS_ASSERT`）。
- 有窗口的 `-gpu host` 会卡在 Vulkan 初始化后的 **崩溃上报同意弹窗**（`Showing crashdialog to get consent`），
  疑似显示器休眠时拿不到 Metal 上下文；`swiftshader_indirect` 能启动但游戏画面全黑。**别用这两种。**
- `start_emulator.sh` 自带的 60s adb 超时短于冷启动，超时报错≠失败。

**③ MaaFW 在模拟器上跑任务会 SIGILL（根因已定位）**
- 现象：`start` 驱动任务后 `:shizuku_service` 立刻死亡，App 报
  `privileged process died mid-run, forcing abort`，CLI 随即无响应。
- tombstone（已存档 `game-rescue/tombstones/tombstone_00_emu_sigill_opencv.txt`）：
  `signal 4 (SIGILL), code 1 (ILL_ILLOPC)`，栈是
  `libopencv_world4.so (cv::parallel_for_ → cv::Mat::convertTo → cv::matchTemplate)`
  ← `libMaaFramework.so (MaaNS::VisionNS::TemplateMatcher::template_match)`。
- 判断：模拟器 vCPU 把宿主 M4 的 `sve2/sme/i8mm` 等新扩展一并暴露，OpenCV 运行时派发据此选了高级指令路径，
  而该路径在模拟器上触发非法指令。**真机 arm64 不受影响**（这也是 phone 上一切正常的原因）。
- 尝试过 `-qemu -cpu max,sve=off,sme=off`：模拟器直接起不来，日志给出确凿原因 ——
  `can't apply global max-arm-cpu.sve=off: Property '.sve' not found`。说明这个版本（37.1.11）的 QEMU
  在 HVF 下**不建模 CPU**，feature 是宿主直接透传，因此 `-cpu` 这条路在本版本上封死。
- 外部研究（@librarian）确认这是 M4 + Android 模拟器的**已知通用坑**（MediaPipe #6293 做到指令级：
  模拟器广播 `sme2` 但 `rdsvl` 一执行就 trap；dotnet/runtime #127398 标题就是「SME but no SVE」；
  Podman #28312、Parallels 论坛同源）。OpenCV issue #27618 的崩溃栈与本案**逐帧一致**，
  且其初始化日志显示派发了 `NEON_DOTPROD/NEON_FP16`。
- 可行的三条路：① 换 emulator 版本（较新的 QEMU 在 HVF 下主动屏蔽 SME，或已正确支持 SME2）；
  ② 给加载 OpenCV 的进程注入 `OPENCV_CPU_DISABLE=NEON_DOTPROD,NEON_FP16,NEON_BF16,SVE`
  （Shizuku 的 `newProcess` 可以带 env，但 `wrap.*` setprop 因属性名不能含 `:` 覆盖不到 `:shizuku_service`）；
  ③ 真机。

### 怎么验的
- 游戏进世界：observer 读图确认（`Explore` / `Bell of Recollection` `1/4` / 圆形小地图 / 摇杆与动作键）。
- 数据完整性：外部 30GB、CE 20M、DE 128K，uid 10207 全目录一致。
- 纯逻辑闸门不受影响：1374/1374。

### 未做 / 未解
- 模拟器上跑不了 pipeline 任务（SIGILL），所以**走路闭环的调参仍然需要真机**，或在模拟器上先解决 CPU 特性屏蔽。
- 游戏曾报 `Error code: 1103`（取不到版本数据），点 Confirm 重试后正常进世界。

## 2026-10-01 · 触摸驱动多指对齐 + 走路闭环 170px 假匹配物理步长离群抑制

### 做了什么

**① 触摸驱动接触点 ID（contact_id）与滑动时序全量对齐上游 C++ 布局**
- 现场排查与对齐上游 `agent/cpp-algo/source/MapNavigator/Backend/Adb/`：
  - `adb_camera_swipe_driver.h:15`：`contact_id = 1`（此前误设为 9，在部分 Android 驱动/虚拟触摸注入器超出 5 点槽位上限被静默丢弃导致视角不转、yaw 位级不变）。
  - `adb_input_backend.h:29-35` `AdbActionButtonLayout`：
    - `interact_button.contact_id = 5`（此前为 10）
    - `sprint_button.contact_id = 2`
    - `jump_button.contact_id = 3`
    - `attack_button.contact_id = 4`
  - 同步上游 **`e2462511` (#6097 ADB 转向丢步与起落对称修复)**：
    - `adb_camera_swipe_driver.h:27,30`：两端停留时间 `touch_down_hold_ms = 100ms`, `end_hold_ms = 100ms`（原 8ms / 30ms）。游戏逐帧采样触点，与按下或抬起同帧的位移会整步丢失；延长两端停留确保覆盖一帧完整采样，消除转视角丢步。
    - `adb_camera_swipe_driver.cpp:94`：横向中心对称起落 `sx = 640 - dx/2, ex = sx + dx`。被当成点击时落点最靠近屏幕中心，杜绝误触屏幕两侧的任务追踪或动作按钮。
  - 修改 `MotionSupport.kt`：`CONTACT_CAMERA = 1`，`CONTACT_ACTION = 5`，`CONTACT_SPRINT = 2`，`CONTACT_JUMP = 3`，`CONTACT_ATTACK = 4`，更新分步滑动时序与中心对称坐标。
  - `tapButton` 动作按钮（交互/冲刺/跳跃/攻击）调用时传入专有 contact_id，避免跨功能触摸 ID 碰撞或被底层驱动丢弃。

**② 走路闭环步长突变抑制（单拍 ~170px 假匹配抑制）**
- 现象：真机闭环走路过程中小地图偶尔出现低置信突发假匹配跳变（~170px），由于之前 `MapNavWalkPlanner.tick` 只要 `fix.usable` 就无条件更新 `lastDistance`，导致到路点距离从 20px 瞬间被污染成 150px/210px，破坏单调性并引发误判。
- 修复：
  - `NavWalkConfig` 增加 `maxPlausibleStepDistance: Double = 50.0`（角色单拍 250ms 最大物理位移约 5~8px，50px 足够包容冲刺同时拦截 >100px 的严重假匹配）。
  - 在 `MapNavWalkPlanner.tick` 中增加物理步长跳变检测：若上一拍已有有效距离，且单拍距离变动 `abs(dist - lastDistance) > config.maxPlausibleStepDistance`，拒绝更新 `lastDistance` 与到达判定，降级为 `HOLD` 并计入丢失计数，保护走路连续性。
- 单元测试：在 `MapNavWalkPureTest.kt` 新增 `突发跳变离群值被拒且不污染距离` 回归测试。

**③ 资产准备层断言与上游同步**
- `scripts/prepare_maaend.py`：上游同步在 `SceneValleyIV.json` 新增了 `__ScenePrivateMapValleyIVAburreyQuarryEnterWorldAnchorWithPick` 与 `__ScenePrivateMapValleyIVValleyPassEnterWorldAnchorWithPick` 两个进世界锚点，构建期断言发现漏补。
- 将枚举与总数更新至 20 个，全部安全补齐 `__ScenePrivateAnyExit` 末位兜底与 `on_error`。

### 怎么验的
- 纯逻辑闸门：`./scripts/verify_pure_logic.sh all` 全部通过（**1374/1374**）。
- 国际化一致性检查：`python3 scripts/check_i18n_strings.py` 全部通过（中英 663 条无缺失）。
- 本地构建与打包：`scripts/build_local.sh debug` 成功生成 `app-debug.apk`。
- 全程未启动模拟器，严格遵守移动硬盘存储与上游子模块指针保护纪律。

---

## 2026-09-30 · 采集/送货打通：MapFind（WorldMap）移植完成并真机验证

### 做了什么

**① `MapFind` 从恒假 stub 变成真实现（找了一整天的最后一块大件）** —— `cf6a83b`
- 上游 `agent/cpp-algo/source/WorldMap/`（约 1830 行 C++）整体移植：`WorldMapTypes/FindPure/SolverPure/ImagePure`
  共 **1114 行纯逻辑 + 60 个测试**；框架 IO（多尺度 `TemplateMatch` + 运行时模板覆盖）留在 `MaaRunner`。
- 关键侦察结论：**资产本来就在包里**（`SceneManager/MapIcons.json`、传送图标、各 zone `Base.png`，
  已从已装 APK 的 `assets/pi.zip` 里逐条核实）；而"已知点 + 固定缩放"的捷径**不成立**
  （scale = 底图像素/屏幕像素，随分辨率+底图尺寸+缩放档变化），所以老老实实做两级 viewport 求解。
- **真机验证命中**：全屏武陵地图上 `scale=0.599 score=0.759` → 投屏 `at[942.6,1781.2] → (1035.2,171.5)`
  → `MapTeleportAnchor` 确认 `0.780`，返回框**肉眼确认落在天井院传送锚点上**；
  真实 `MapFind` 在 AutoCollect 里连续命中 3 个锚点（`viewport_scale 0.59–0.60`）。
- 失败护栏：解不出/置信不足/图标未知/状态不符 → 一律 false，**绝不返回算错的屏幕坐标**；
  `MAP_FIND_REAL_ENABLED` 可一键回退。未移植项（vote_grid 分块、gold_ratio 解锁判定、玩家标记遮挡回退、
  拖动增益补偿、alpha 掩膜/亚像素）**每一项都退化为 miss 而不是误点**。

**② 采集进世界锚点兜底覆盖 9 → 18（用枚举+独立扫描断言防再漏）**
A 类 WithPick（依赖 MapFind）与 B 类旧模板（`SwipeToStep`）**都**追加 `__ScenePrivateAnyExit` 到 `next` 末位 + `on_error`；
验证改为**扫描产物发现锚点**再断言数量与双向一致，漏一个就 fail build（已做负例测试）。
真机：锚点不再 `PipelineNode.Failed`、不再有 MapFind 21 连击、无新增失败帧，流程推进到 `RouteN AssertLocation`。

**③ 顺带修掉两个"修了但没生效"的**
- `MapLocateAssertLocation` 的**逐像素静止快速失败在真机永不触发**（3D 画面每帧都在变）——仍是 60 帧 61.8s/105s。
  改为**语义判据**：区分"还在等地图"（WAITING，继续轮询）与"画面已切过去但定位就是失败"（DETERMINISTIC，
  连续 6 帧同因 → 早停），并加 **20s 墙钟保底**（帧数上限 60×250ms 只是睡眠预算，真机每帧 ~1s，时间才是真闸）。
- 另加 `mapfind <zone> <x> <y> [icon]` 调试命令（只读，不 ZoomOut/不拖/不交回 next），
  正是它让上面的真机验证成为可能。

### 怎么验的

- 纯逻辑：`scripts/verify_pure_logic.sh all` → **1280/1280**。
- 真机（设备 `b8459a87`）：MapFind 命中并肉眼核对；锚点无风暴；`mapfind` 探针输出与真实 MapFind 交叉一致。
- 遗留未验：① `GrowBack` 时序修复（培养舱 3 槽被上午种下的作物占满 16–43h，分支不可达，今天无法验）；
  ② assert 早停（任务 20 分钟内没走到 AutoCollect，实测未观测到）。

### 教训

- **"我修了"和"真机上有效"是两件事**：逐像素静止判据在合成场景下天然成立，在真实 3D 画面里几乎永不触发；
  第一版快速失败因此等于没做。判据必须建立在**业务语义**（这次是"失败原因是否确定"）而不是表象（画面是否在动）。
- 帧数上限 ≠ 时间上限：60×250ms 看起来 15s，实测 105s——**每帧成本**才是主导项，预算要按墙钟设。

### 未做（明确边界）

- `MapFind` 未移植项（见 ①）与性能（单次 25–117s，Wuling 底图 2016×2976 的多尺度搜索很重）。
- 送货/采集的"指定送达点""大地图寻路"等仍依赖上游后续与更多真机标定。
- CreditShopping 等上游 draft PR #6055 合并后再整体更新。
- 其它大件 stub（`EssenceFilter`/`AutoEssence`/IMS/滑索导入等）未动。

## 2026-09-30 · PathHeatmap 真机打通 + 追踪状态机移植（deepwork Phase 1/2）

### 做了什么

**① PathHeatmap 真机打通（关键突破）**
真机世界帧上第一次拿到可用的定位结果：

```
result: PASS（热图路 score=0.8564690018963244）
heatmap: override=ok coarse_hit=true coarse_score=0.555124 coarse_box=[66,124,98,99]
         refine=(b) score=0.8564690018963244 box=[81,123,98,99]
         tracking_valid=true global_accepted=0.8564690018963244
```

- **掩膜落地分工**：框架 `TemplateMatch` 不吃 mask → 粗排用"掩膜内**均值**填充"
  （填均值时 `T-meanT` 在掩膜外恒 0，ZNCC 分子与模板范数退化为掩膜内统计，偏差只剩整窗 `meanI`）；
  精排用 Kotlin `MapLocatorPathHeatmap.matchGlobal` 真掩膜 ZNCC；精排退化时回退粗排分。
- 灰度路（Standard）对这类雷达小地图仍 FAIL，作为回退保留。
- **顺带修了一个会误导人的口径**：`result:` 原实现只看灰度路，于是"热图路 ACCEPT"也会打印 FAIL。
  现在改为合并裁决并标明路径（`PASS（灰度路）` / `PASS（热图路 score=…）` / `FAIL`），RunDiagnostics 同步。

**② 追踪状态机移植（`MapLocatorTracking.kt` 296 行 + 248 行测试，17 场景）**
- 冷启动共识（连续 3 帧紧簇才接受）、高置信直通（≥0.85 立即接受并重锚）、
  远跳拒绝（低分 + 距离 >80）→ 记丢失 → 超上限 relocate（普通区 3 / 路径区 10）、
  换区强制重冷启、歧义/边缘吸附保持、`None` 遮挡占位。
- 新增 debug CLI `tracklocate <frame> [zone]` / `tracklocate reset`，跨命令保留状态，
  输出 `action/reason/pos` + `zone/tracking/lost/cold_start`。

**③ 提取弹窗修复的真机确认（fix-6 复盘）**
- 关闭识别**已修好**：命中分从 0.2–0.4 提升到 **0.998**；任务 `任务完成: 🎁基建任务`（修复前必红）。
- **残留瓦特**：坐标兜底仍被触发 2 次 → 失败点后移到 `GrowBack`（关闭弹窗后返回培养界面超时）。
  兜底把任务救绿（单向 next、不成环），记为已知技术债。

### 怎么验的

- 纯逻辑：`scripts/verify_pure_logic.sh all` → **1127/1127**（基线 1102 + 新增 25）。
- 真机（同一张保存的世界帧）：`coarselocate` 新口径正确；`tracklocate` 连喂 3 帧
  `ACCEPT / zone=OMVBase01 / lost=0-10` 稳定，`reset` 可清空。

### 教训

- **"任意一路通过即成功"必须写进输出**：两条策略并存时，只看其中一条会把成功报成失败，
  把调试者引向错误方向。
- 真机上"任务绿"不等于"修好了"：`GrowBack` 仍在失败、只是被兜底接住。
  兜底是保险，不是修复——要单独记账。

### 未做

- `GrowBack` 超时的根因（返回键模板 / 等待节点）。**已取证到**：
  - GrowBack 的**识别是成功的**（`And(GrowthChamberBackButton)` 通过后才有自定义动作），失败在**动作**
    `RepeatUntilFoundAction {action: Click, wait_nodes: [GrowthChamberGrowViewIn]}`——
    实现是 `repeatCount`（**默认 3**）× 「点一次 + 等 wait node」，3 次都没等到就判失败。
  - `GrowthChamberBackButton` = `TemplateMatch roi=[1146,0,134,112] template=ClaimDijiangRewards/BackButton.png
    green_mask=true **method=10001**` —— **method 是非标准值**（框架常规 1/3/5），需确认框架是否真支持。
  - 但纯坐标兜底 `SeedExtractCloseByCoord` 能过（点固定位置 + 下一节点 GrowViewIn 命中），
    所以更像是**返回键识别框/点击落点**或与弹窗关闭动画的时序问题。
  - 下次现场调试：抓 GrowBack 失败帧（`on_error/` 已有）+ 看实现里那句
    `RepeatUntilFoundAction [node] attempt n/N click (cx, cy)` 的落点。
- PathHeatmap 的 native 加速（当前全图细搜在 Kotlin 侧是 O(W·H·w·h)）。
- AutoSell 的真机复测（当日任务已被消耗）。

---

## 2026-09-30 · PathHeatmap 纯逻辑移植 + 提取弹窗时序根治

### 做了什么

**① MapLocator 第二条策略 PathHeatmap 的纯逻辑层**（`b22e331`）
- 新增 `MapLocatorPathHeatmap.kt`（685 行）+ 测试 462 行：热图构建（路面标准色
  237/233/228 的颜色曼哈顿距离 + 5×5 σ=1.1 高斯模糊，逐位复刻整数公式）、
  `GenerateMinimapMask` 全层（圆盘 / 精确白 / HSV 白 / 彩色图标 + 椭圆核膨胀 /
  中心遮蔽 / 暗部剔除，之前只移植了外接圆盘）、模板特征提取、带掩膜 ZNCC 打分
  （与 `RefinePeakContinuous::evaluate` 同式）+ PSR/delta/second，
  以及 `ImageProcessingConfig.Base/Tier` 常量与两槽搜索特征缓存。
- 之前只移植了 `Standard`（模板匹配）那条线，真机世界帧上它对地图资产的相关性
  天花板恒在 ~0.6（尺度/旋转/裁剪/alpha/掩膜穷举过），拿不出可用峰，所以按上游
  `MatchStrategy.cpp` 把 PathHeatmap（路径网热图匹配）这条也搬过来。
- 真正离不开 OpenCV 吞吐的部分（`matchTemplate` 的 SIMD 实现、Canny 倒角补偿）
  在文件头显式标记并写了 native/framework 侧的调用形状，没有硬造。

**② 「提取获得」弹窗关不掉的时序根因修复**（`294867f`）
- 像素级诊断 + 真机日志双证：`ExtractSeedCloseText` 的 roi 正确、expected 正确，
  ADB 的 44×45 关闭按钮模板在渲染完成后匹配 0.9998——**根因是时序**：「确认提取」
  点击后约 1.5 s 弹窗才渲染完，而关闭节点约 0.3 s 后就被评估，首次识别必然落空；
  上一轮加的 `on_error→GrowBack` 又把这次瞬时落空立刻转走（返回键还被弹窗遮着），
  于是掉进坐标兜底。
- 修法（全在补丁层）：ADB 44×45 图覆盖基础层 33×31 旧尺度图（消掉低分噪声候选）；
  `GrowthChamberSeedExtractClose.pre_delay = 1500` 让首次评估落在渲染完成后；
  保留 on_error→GrowBack→坐标兜底当最后保险。均配了构建期断言。

### 为什么

- ①：粗定位"链路全通但命不中"的结论已经写过；Standard 这条线在数学上就不够，
  继续调参是浪费时间，按上游的第二条线走才是正解。
- ②：上一轮坐标兜底把任务救绿了，但关闭识别本身一次都没命中过——兜底是保险，
  不是修好。不把时序修了，每次提取都靠兜底，早晚还得出事。

### 怎么验的

- `scripts/verify_pure_logic.sh all`：**1088/1088**（基线 1062 + 新增 26），`ALL OK`。
- ① 合成验证：精确裁剪 true=1.0 vs wrong≈-0.09（gap ≈1.09）；全局搜索 best score=1.0，
  second=0.170，delta=0.830，psr=21.08。
- ② `python3 scripts/prepare_maaend.py` 通过（含新增断言）；子模块指针未动（仍 `fcdc53a7`）。

### 教训

- "模板是对的、OCR 是对的，但第一次永远 miss"——先看时间戳再怀疑识别器。
  这次是弹窗渲染 1.5 s vs 评估 0.3 s，证据就在同一份日志里（首次 0.2–0.4，1.5 s 后 0.9998）。
- `on_error` 是双刃剑：它把"瞬时落空"升级成了"整条链改道"。给会自愈的节点加 on_error
  之前，先确认它的失败真的是永久性的。

### 未做

- ① 的 native/framework 接线（文件头已标调用形状）+ 真机验证（需要"小地图裁剪 +
  已知真值位置"的数据对，详见文件内说明）。
- ② 的真机复测：确认 `SeedExtractClose` 首次评估即命中、坐标兜底**不再**被触发。

---

## 2026-09-30 · 真机验证日：CLI 开机即启/远程调试落地、提取链现场复现、MapLocator 粗定位撞到方法学上限

### 做了什么

**① 调试 CLI 三项能力（用户要的）+ 文档**（`1ef04b8`、`98012bd`）
- **开机即启**：App 启动（debug + 调试模式）就拉起 CLI，不必先跑任务。做法：`MaaFwApp.postCreate` 起
  DI 管理的 `DebugCliCoordinator`，服务连上后调 `RemoteService.configureDebugCli(...)`；特权侧**只做
  CLI 需要的两件事**（`setProjectRoot` + `DebugCliServer.configure`），把 `applyGlobalOptions`/
  `RunDiagnostics.start`/`disablePhantomProcessKiller` 留在开跑时（它们配 native 日志/改系统设置/写文件）。
- **PI 就绪补触发**：冷启动时 PI 常没解压完，`installedDir()` 抛异常 → `project root` 会永远是"(未设置)"。
  把 `PiInstallCoordinator.state` 映射成 Boolean 接进触发集合，`false→true` 那一下重跑 configure。
- **远程调试**：设置页开关（默认关）+ ⚠高风险警告（写明"可远程操控/读截图日志/模拟点击"）+「不再提示」+
  令牌（SecureRandom、可查看/复制/重置）+ **非回环必须 `auth <令牌>`** + 常量时间比较 + 5 次失败锁 30s +
  **回环（含 adb forward）免令牌** + fail-closed。
- **`start [task...]` / `stop`**：RunPlan 在 App 进程构建，所以特权侧走**反向 IPC**（`IAppCommandCallback`）
  请 App 侧用与 UI 相同的 `RunLauncher → RunnerPort` 路径起停。
- **文档**：`docs/reports/debug-cli-and-remote-debug.md`（给 AALC-meow 的复用指南，含协议/进程模型/安全模型/踩坑）。
- **远程链路**：CLI 是裸 TCP，Cloudflare 免费隧道只走 HTTP → 本机加一个带令牌的 HTTP 桥
  `scripts/debug_cli_bridge.py`。公网端到端验证通过。

**② 培养舱提取链：真机现场复现 + 修复 + 修复后真机通过**（`c5045c9`）
- 现场：`FindTargetBySeed → GrowConfirm×3 → FindTargetBySeed 20s 超时失败`，**失败帧停在「提取获得」弹窗**
  （道具 + 底部 ✓）——材料已种下、基核已提取，纯误报。
- 上游**未修**这条；我们补齐：`SeedExtractClose` 原本**没有** `on_error` → 补 `GrowBack`；
  给 `GrowBack` 再挂一个**不依赖识别的坐标点击兜底**（防弹窗盖住返回键）；`ExtractSeedCloseText`/
  `NoMaterials` 文案扩容。
- **修复后真机复跑（同一次运行用新 CLI 的 `start AndroidOpenGame DijiangRewards` 直接起）：**
  ```
  SeedExtractConfirm / SeedExtractClose        ← 修复前这两个节点从未触达
  SeedExtractCloseByCoord                      ← 新增的坐标兜底被触发（识别路仍认不出关闭按钮）
  GrowBack                                     ← 被兜底救回，回到培养界面
  任务完成: 🎁基建任务                          ← 修复前必红，现在通过 ✓
  ```
  `on_error/` 没有新增失败帧。**结论：识别路仍需修（关闭按钮认不出），但兜底已经做到"任何断法都不拖红"。**

**③ 同步 4 个上游修复**（`c5045c9`，全部走补丁层，不动子模块指针）
`489ff2fe`(#6076 确认框 box_index)、`d4ea8745`(#6100 —— **注意：宽度 120→180 是 `ClueItem` 不是
`GrowthChamberCheckSeedNotEmpty`**，简报里的归属是错的，按真实 diff 落值)、`6af0f43c`(#6054 AutoSell
交互键 + ADB 覆盖)、`27507ad4`(#6085 折扣 ROI)。另把 AutoSell 页签改成按坐标点击（原来 720p 模板失配
→ 静默 0 物资不卖）。

**④ AutoSell 失败传播**（`c80be82`）
移植时丢了上游的 `!Status.Success() → return false`，导致"没卖出去却成功"。改为查子任务状态、
失败即动作失败；扫描缓存**键不存在**（扫描没生效）判失败、**空列表**（本区确实没有）按上游跳过。

### 怎么验的

- CLI/远程调试：真机逐步验证——开机即启 ✓、`start` ✓、警告弹窗 ✓、令牌 ✓、
  **未鉴权被拒** ✓、`auth` 后正常 ✓、**公网 HTTPS 端到端** ✓。
- 提取链：真机复现 + 修复后重跑（本次）。
- 上游修复：`prepare_maaend.py` 幂等 + 3 组构建期断言读回产物核对；1062 测试全过。

### 教训（重要）

**MapLocator 粗定位撞到方法学上限（负面结论，但很值钱）**：

真机世界帧上，整条链路前半段**全部打通**：裁小地图 ✓ → YOLO 正确识别真实 zone（`OMVBase01`）✓ →
找到地图资产 ✓ → 运行时覆盖模板 ✓ → 全图搜索 ✓。但**匹配不命中**。

本地用真帧 + 真资产做了穷举诊断（numpy FFT 归一化互相关）：

| 变量 | 扫的范围 | 结论 |
|---|---|---|
| 尺度 | 0.3–2.0（步长 0.1） | 无强峰，最好 ~0.63 |
| 旋转 | 0–355°（步长 5°） | 无强峰；最佳角多在 0–20° |
| 裁剪 ROI | 4 种候选（含放大版） | **上游默认 (49,51,118,120) 反而最好**；放大更差 |
| alpha 合成 | 黑/白/浅灰蓝/小地图底色 | 黑底（现状）最好 → **不是 alpha 问题** |
| 掩膜（去白/去暗/中心遮蔽） | 上游 `GenerateMinimapMask` 思路 | 仍 ~0.64 |
| **换资产** | **全部 5 张地图（Dung/IndieDg005/007/OMVBase/ValleyIV）** | **全都 ~0.55–0.67 → 不是分类错** |

→ 结论：**小地图是"随镜头旋转的雷达式示意渲染"，地图资产是仅 11% 像素不透明的稀疏线稿；
两者直接做归一化互相关拿不出有辨识度的峰**。上游有两条策略，我们只移植了 `Standard`（模板匹配）这条；
上游另一条（`PathHeatmap`，按**路径线**与预计算热图匹配）大概率才是能用的那条。

→ 下一步不是调参，而是**移植 PathHeatmap 策略**（或等价的路网匹配）。在那之前，
`coarselocate` 的"能跑通但命不中"要如实标注，别当成"接近可用"。

### 未做 / 待办

- MapLocator：PathHeatmap 策略移植；朝向/尺度补偿等它落地后再谈。
- 信用点购物：上游正在大重构（draft PR #6055），等合并再整体更新，不要拆挑。
- 真机复测清单（提取链 + AutoSell）见本次运行日志。

---

## 2026-09-30 · 三个真机 bug 的根因修复 + 本地构建链路打通（准备最后一次真机验证）

### 做了什么

**① 探针「任务运行中」可用**（`0fa9aa1`）
之前 `ocr`/`run`/`overrideprobe`/`yoloprobe`/`coarselocate` 都被 `isRunning()` 硬门控挡住。
读框架源码确认：tasker 是单线程串行队列（`Tasker.cpp:32` + `AsyncRunner.hpp:82/116/166/193`），
运行中再 post 只会排队、`Wait` 要等几分钟；`MaaTaskerPostRecognition` 与 task 共用同一队列。
最终方案：四个**只读**探针走「只绑 resource、不绑 controller 的第二 tasker + PostRecognition」，
队列独立 → 真并行且不碰输入状态（绑 controller 的第二 tasker 会在 task 结束时
`auto_release_pressed`，把正在跑的任务按着的手指提前放掉——这是不能接受的）。
`run` 会驱动点击，改为排队到任务结束后执行；新增 `probe-result` 读回结果，socket 不阻塞。

**② 情报「超过一定量后多余情报不送出」**（`4d0f2a4`）
根因不是情报代码，而是共用识别 `ListCompleteRecognition` / `ScrollbarCompleteRecognition`
被移植成了**调用计数**（第 5 / 第 4 次恒真）。上游是**画面比对判底**
（`listcomplete/recognition.go:24-184`：截 ROI → OverrideImage → 每轮 TemplateMatch 比，
threshold 默认 0.9、IntelArchive 传 0.98）。计数版导致每个页签只滑约 4 屏就"到底"，
后面条目永远扫不到（paper 实测 238 条）。新增 `ListCompleteSupport.kt`（305 行 + 40 测试）承接
状态机/参数解析，计数器只留作防死循环的硬上限（`max_attempts=200`）。**影响面 10 个管线文件**。

**③ 培养舱两处**（`dda613b`）
- 「所有材料都试一遍」：ADB 端持有量判定的 `roi_offset` 漂了，0 数量被读成"有"。
  按上游 `d4ea8745`（#6100）改为种子 `[40,52,0,0]`、本体 `[-85,57,29,0]`，
  并加**构建期断言**防止将来同步上游时被静默覆盖回去。
- 「种下却报失败」：默认 `AutoExtractSeed=Yes` 让提取基核成为必经分支，三条出路覆盖窄，
  断链后整任务报红。给两个提取节点加 `on_error` 兜底（回 `GrowthChamberGrowBack`）+ 扩 expected 文案。

**④ D8 崩溃（阻断 debug 包，必须修）**
`MapLocatorRefinePure.refineSubpixel` 的字节码让 D8（debug 变体的 dexer，R8 9.2.14）内部崩溃：
`ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 32`（release 的 R8 能过，debug 的 D8 不能）。
用 build-tools 的 `d8` 单独复现出**秒级迭代回路**，把两个可疑字节码形状一起消掉：
局部函数（捕获一圈变量）抽成成员类 `SurfaceEvaluator`；实现体拆成不带默认参数的
`refineSubpixelImpl`（默认参数放转发入口）。行为逐位不变（1018 条测试全过）。

**⑤ 本地构建链路（工具链全在移动硬盘）**
`~/Library/Android/sdk` 原本是**断链符号链接**（指向 `/Volumes/mac第三磁盘/AndroidStudio/SDK`，
但那个目录是空的）——这就是"只能靠 CI"的原因。现已在移动硬盘装齐：
SDK（platform 37.0/37.2 + build-tools 36/37 + NDK r27c + CMake 3.22.1，3.4G）、JDK 17（501M）、
Gradle 依赖（`~/.gradle` 软链到 `/Volumes/mac第三磁盘/codes/.gradle-home`）。
新增 `scripts/build_local.sh`：**先跑 `prepare_maaend.py` 再 gradle**——CI 是显式跑这步的
（`build-apk.yml:91`），本地直接 gradle 会拿到**没打补丁的管线**（能装能跑但行为不一致，结论会假）。

### 怎么验的

- `scripts/verify_pure_logic.sh all`：1018/1018（978 → 1018）。
- `d8` 单测该类：修复前崩、修复后出 `classes.dex`。
- `scripts/build_local.sh debug` 端到端出包（`app/build/outputs/apk/debug/app-debug.apk`，
  versionName `0.1.8-alpha.13`，debug 签名）。
- 管线补丁验证：产物 `pi/resource_adb/.../Status.json` 里两个 `roi_offset` 已是修复值。

### 教训

- **移植一个识别时，"它怎么判"比"它被调用几次"重要得多**。计数器版能跑、能在短列表上"看起来对"，
  但在长列表上静默丢数据——这类 bug 不会报错，只会少干活。
- **D8 与 R8 的接受面不同**：release 过不代表 debug 过。遇到 dexer 崩溃，去 build-tools 里
  直接跑 `d8` 建立秒级回路，比反复跑 gradle 快一个数量级。
- **构建产物的行为 = 源码 + 构建期补丁**。本地构建必须复刻 CI 的补丁步骤，否则本地调试的结论
  不能代表发布包。

### 未做 / 待验

- 真机复测（明天最后一次）：见 `docs/reports/final-device-session-plan.md`（装包要**先卸载**——
  CI 各次运行的 debug 签名不同，覆盖安装必失败；卸载后配置需重建、补充包应用内重下）。
- MapLocator 剩余分片：追踪状态机接线、朝向近似；`coarselocate` 的世界帧验证。

---

## 2026-09-29 · MapLocator 第 4 片：端到端粗定位串联（`coarselocate`）

把两块**已真机验证**的能力串成第一次端到端粗定位：

> 小地图 → YOLO 分类得 zone + tile → 算搜索 ROI → 在**地图资产图**上跑 TemplateMatch → 粗位置

新增 `MapLocatorCoarsePure.kt`（纯逻辑 247 行 + 27 测试）：
- `mapZoneKey`：对齐 `loadAvailableZones`(`MapLocator.cpp:876-924`) 的 key 规则——
  `base.png`（小写全等）→ `<父目录>_Base`；`Lv(\d+)Tier(\d+)\.(png|jpg|webp)$`（icase）
  → `<父目录>_L{去零}_{去零}`；其余取 `stem`。
- `minimapExtractPlan` / `extractMinimapArgb`：对齐 `TryExtractMinimap`(`MapTypes.h:151-172`)；
  adb 变体（先 0.8 缩放 + y−7）的**选择与几何**已实现。
- `constrainedSearchRoi`：对齐 `startGlobalSearch`(`MapLocator.cpp:1268-1332`)——
  `buildSearchConstraint` 的 ROI_FINE 再外扩 `globalSearchRoiPad` 并裁到地图边界。
- 命中判定（`in_map`/`in_roi`）与框架 detail 的 `best.score` 解析。

debug CLI：`coarselocate <全帧截图> [expected-zone-selector]`，
输出 zone/yolo/tile ROI/constraint/search ROI/template override/hit/score/box/in_map/in_roi/result，
并写 `RunDiagnostics.note("maplocator", stage=coarse_locate)`。

**如实记录的缺口**（全部写进报告）：
1. **adb 小地图变体的 0.8 重采样未接**——几何已实现，但 `extractMinimapArgb` 对需缩放路径返回
   null；且末影只走 native controller（非 adb），探针走不到那条分支。
2. **控制器类型无法运行时判定**（`MaaControllerGetInfo` 未绑定），探针硬编码非 adb。
3. **模板尺度未补偿**：框架 `TemplateMatch` 是单尺度，而
   `ZoneTemplateScale("ValleyIV_Base") = 15/16` 不会自动生效——真机粗定位到该 zone
   时需另做缩放（后续分片）。
4. 地图 PNG 的 alpha 被丢弃（BGR 而非上游 BGRA）；不透明区无影响，粗定位可接受。

纯逻辑测试 921 → 954 条。

### 另记一条操作规范（用户明确要求）

**测试间隙要关掉游戏与 App**——"手机会很烫"。做法：

```bash
adb shell am force-stop com.hypergryph.endfield
adb shell am force-stop com.aliothmoon.maafw.maaend   # 连带特权服务/CLI/虚拟屏一起停
```

关完确认：无 `hypergryph`/`maafw` 残留进程、虚拟屏引用归零。
下次要测时重新拉起 App + 点「启动终末地」即可——
注意 **runner 的 `setup()` 仍只在"首次启动任务"时触发**（第 3 片踩过的坑），
所以 CLI 要等到真正开始一次任务之后才会监听。

---

## 2026-09-29 · MapLocator 第 3 片：YOLO 分区分类链路打通（真机，`d30e410`）

真机 `yoloprobe` 结果：

```
hit: true            ← 模型加载成功
cls_index: 254       ← 得到索引
class: None          ← 查我们自己已移植的 classes 表
is_none: true
confidence: 0.0105
```

### 这一轮确认了什么

1. **模型加载路径可用（关键）**：`cls.onnx` 从补充包目录硬链到一个 debug bundle +
   `MaaResourcePostBundle` 追加 `model/classify` root，框架**懒加载时确实能命中**。
   这是第 3 片里**唯一没能离线确认**的框架行为（只能从 `ONNXResMgr.cpp` 的
   `lazy_load_classifier` 源码推断），现在由真机自证 ✓。
2. **预处理 → 推理 → 索引 → 类名映射**整条链路跑通：
   索引 `254` → 我们的 `classes[254] = "None"`，与框架给的 label 一致 ✓。
3. **模型 5 个文件全部按清单的 git blob SHA 校验通过**（`git hash-object` 逐一比对，
   含 `cls.onnx` 23MB / 两个 cameraorientation / 两个 JSON）。

### 准确性验证还差一步（并附一个教训）

**结论**：还差一次"**世界 HUD 帧**"的验证。本轮两次截到的都是游戏**标题/加载页**
（画面是 CADPA 适龄提示 + "正在加载资源…"），根本没有小地图——所以只能验到
"链路通 + 非小地图正确判 `None`"。

**教训**：调试前先确认**输入本身是不是有效的**。我拿加载页当"小地图"跑了两次，
两次都是 `None`，一度差点误判成"预处理/通道顺序错了"——是**把帧拉出来看**
才发现输入根本不对。下次直接在游戏世界里截帧再跑一次
`yoloprobe <小地图裁剪>` 即可定论。

---

## 2026-09-29 · MapLocator 前提验证通过（真机，`3d69ff5`）

分片计划的**第 1 步（前提验证）已在真机上跑通**：

```
override: ok                 ← MaaContextOverrideImage 生效（运行时模板，不落盘）
hit: true                    ← 框架 TemplateMatch 确实用了这个运行时模板
expected: [137,88,48,40]
actual:   [137,88,48,40]     ← 匹配位置与裁剪位置逐像素一致
result: PASS
```

**这一次性确认了路线 (b+) 的全部关键前提**：

1. `MaaContextOverrideImage` 在我们的框架版本上**存在且可用** ✓
2. `MaaImageBufferSetRawData`（此前只能由 Go 绑定佐证、随包头文件里没有的那个符号）
   **存在且可用** ✓ —— 真机自证了这条保留项
3. 框架 `TemplateMatch` **会使用运行时覆盖的模板** ✓ ——
   这解决了最担心的一点：**模板缓存**（`TemplateResMgr` 是缓存的）会不会导致
   "换了模板但框架仍用旧的"。答案是不会，`override_image` 正是绕过缓存的官方入口。
4. 匹配位置**精确**（与裁剪位置完全一致，无偏移）。

**怎么验的**：debug CLI 的 `overrideprobe`（`3d69ff5` 引入）——
合成一张确定性 BGR 噪声图 → 从已知位置 `(137,88,48,40)` 裁 patch →
`MaaImageBufferSetRawData` 写入 → `MaaContextOverrideImage` 设为运行时模板 →
`MaaContextRunRecognition` 拿**整张图**跑 `TemplateMatch`（method=5）→ 比对框。
**自洽闭环：不需要游戏、不需要补充包**（图像全是合成的）。

**过程中的一个坑（值得记）**：CLI 死活不起来。排查路径是——
logcat 里**一条 `MaaRunner:` 都没有** → 说明 `setup()` 根本没被调用 →
而 `setup()` 只在**首次启动任务**时走（`MaaFrameworkRunnerPort` 的准备路径），
**`启动终末地` 不会触发它**。加一个任务并启动后，立刻出现
`DebugCli: listening on 127.0.0.1:7777` ✓。
教训：**"服务就绪"不等于"runner 已 setup"**，两者的触发时机不同。

**下一步（按分片计划继续）**：
3. **粗搜**：YOLO 分类（`cls.onnx`）给出 zone + tile → 定 ROI →
   把**地图资产图**作为 image 传给 `TemplateMatch`（本步已验证可行）→ 得到粗位置；
4. **追踪状态机**：用已移植的纯逻辑层（第 2 片）驱动；
5. **亚像素精修**：Kotlin 局部 ZNCC；
6. **朝向**：先 Kotlin 近似（角速度/预测），两级 ONNX 放最后。

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

#### (b+) 的决定性依据：框架本来就支持「运行时换模板图」

源码核对（`ResourceMgr.h` / `ResourceMgr.cpp`）给出了比预期更好的答案：

```cpp
virtual bool override_image(const std::string& image_name, const cv::Mat& image) override;
// → ResourceMgr.cpp:271  template_res_.set_image(image_name, image);
```

即**运行时模板图可以被直接覆盖、不落盘**；而且**上游自己的文档就在用这个模式**
（`docs/zh_cn/developers/custom.md:338,341,385`：「按节点原生 roi 截取区域，经
`OverrideImage` 写入运行时模板…**不落盘**」）。

所以 (b+) 里"把小地图写成 PNG 再让框架读"这一步**根本不需要**——
框架本来就支持在内存里换模板。这同时消除了两个风险：

1. 往资源目录写文件的权限/时机问题；
2. **模板缓存**导致的"改了文件但框架不重读"——`TemplateResMgr` 确实是被缓存的
   （`ResourceMgr.cpp:691,743` 的 `lazy_load`），而 `override_image` 正是
   **绕过缓存的官方入口**。

**需要补的 JNA 绑定**：`MaaResourceOverrideImage` 与 `MaaTaskerGetResource`
（我们目前只有 `MaaContextOverridePipeline`）——小改动，且有上游文档背书。

**于是 (b+) 的形状确定下来**：
1. 从当前帧裁出小地图（Kotlin 裁剪 ✓ 纯数组操作）；
2. 用 `override_image` 把它设成某个模板名；
3. 把**地图资产图**作为 image 传给 `MaaContextRunRecognition`
   （现有 OCR 探针已验证这条路径可用 ✓），节点用 `TemplateMatch`
   （`method=5` TM_CCOEFF_NORMED、`green_mask=true` 处理圆形遮罩、
   `roi` = YOLO 给出的 tile ROI 外扩 `infer_margin`）；
4. 在返回位置附近用 Kotlin 做局部 ZNCC 亚像素精修（代价极低）；
5. 追踪/仲裁/守护用已移植的纯逻辑层（第 2 片 ✓ 已完成）。

**唯一还需真机确认的**：`override_image` 设的模板能否被随后的
`MaaContextRunRecognition` 立即读到（时序），以及 `TemplateMatch` 在
1600×1600 级图像 + 118×120 模板下的耗时。

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
