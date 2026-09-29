# 最后一次真机验证计划

> 背景：手机明日（2026-09-30）最后一次可用。当天的日常任务已被消耗过一次，明日重置后可重跑。
> 本清单 = 明天要验的全部东西 + 精确命令 + 判据。按优先级从上往下做，做不完就停在断点。

## 0. 装包（有坑：必须卸载重装）

**包**：本地构建的 debug APK

```
app/build/outputs/apk/debug/app-debug.apk
```

**为什么必须卸载**：CI 的 debug 构建每次运行都用**新建的临时 debug keystore**（实测两次运行签名不同：
`101c708d…` vs `35d270d9…`），与设备上现有包（同样是某次 CI 的临时 debug 签名）**必然不匹配**，
`adb install -r` 会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

```
adb uninstall com.aliothmoon.maafw.maaend
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**卸载会丢什么 / 怎么恢复**：

| 丢的东西 | 恢复方式 |
|---|---|
| 任务配置（全套日常等） | 应用内重建（≈1 分钟） |
| 补充包（地图底图 + cls.onnx，约 22 MiB） | 应用内「补充包」页重新下载（源：GitHub `MAAend/MAAend-AI@3178323b`，有 blob 校验） |
| PI 资源（pipeline/模型） | 随 APK 安装自动恢复，无需操作 |

**本地构建环境（本次新建，都在移动硬盘）**：
- Android SDK：`/Volumes/mac第三磁盘/AndroidStudio/SDK`（`~/Library/Android/sdk` 软链到此；platform 37.0/37.2、build-tools 36/37、NDK 27.2.12479018、CMake 3.22.1）
- Gradle 依赖：`~/.gradle` → `/Volumes/mac第三磁盘/codes/.gradle-home`
- 构建命令：`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ./gradlew assembleDebug`

## 1. 先验新能力：探针「任务运行中」可用

之前探针被 `任务运行中` 硬门控挡住，现已改为真并行（只读探针走独立 resource-only tasker + PostRecognition；
`run` 改为排队到任务结束后执行）。**先验它，因为后面每一条都要用它。**

1. 起一次任务（任意），任务运行中依次执行：

| 命令 | 期望 |
|---|---|
| `screenshot` | 返回帧路径，帧内容 = 当前游戏画面 |
| `ocr <节点>` | 返回 best 文本，**不阻塞** |
| `yoloprobe <图>` | 返回 zone/ROI，**不阻塞** |
| `overrideprobe` | 返回命中框，**不阻塞** |
| `coarselocate <帧>` | 返回 zone/tile/ROI/命中框，**不阻塞** |
| `probe-result` | 读回最近一次探针结果 |

2. **关键判据**：探针执行期间，**正在跑的任务不受影响**（不崩、不错位、不提前结束）。
   跑完任务后检查 `report`，任务应正常继续/正常结束。

## 2. P0：用户报告的三个 bug（今日已侦察到根因并修复，待真机复现）

### 2.1 情报「超过一定量后多余情报不送出」→ 真判底修复

- **根因**：`ListCompleteRecognition` 被写成"第 5 次调用恒真"的计数器，而它应该是**画面比对判底**
  → 每个页签滑约 4 屏就提前结束，后面的条目永远扫不到（实测 paper 238 条）。
- **验证**：跑 `IntelArchive`（情报档案库）任务。
  - 日志里搜 `ListCompleteRecognition`：每个页签的**滑动轮数应远超 4**（paper 应有几十轮）；
  - 结束时看导出的导入链接：解锁数应接近全量（catalog 共 503 个可解锁 ID），而不是只扫到前几屏的那点；
  - 注意 `max_attempts=200` 只是安全阀，正常情况下不应触发。

### 2.2 培养舱「所有种子都试一遍」+「种下却报失败」

- **根因①**：ADB 端持有量识别的 ROI 漂了（`resource_adb/.../DijiangRewards/Template/Status.json`），
  0 数量被读成"有" → 挨个点全表。已按上游 #6100（`d4ea8745`）改成 种子 `[40,52,0,0]`、本体 `[-85,57,29,0]`。
- **根因②**：默认 `AutoExtractSeed=Yes` 让提取基核成为必经分支，三条出路覆盖窄，断链后整任务报红。已加
  `on_error` 兜底（回 `GrowthChamberGrowBack`）+ 扩 `expected` 文案。
- **验证**：跑 `DijiangRewards`（基建任务）。
  - ① 持有量为 0 的材料**不再被点**；只有真有基核/本体的候选被选中；
  - ① 反向：确实有库存的候选仍能识别并种下（ROI 收窄没误伤）；
  - ② 种下之后走提取分支：无论提取成功/原料不足/文案变化，**都能回到培养舱主界面，任务不红**；
  - ② 极端兜底：三条出路都不命中时，约 20s 后 `on_error` 拉回。

### 2.3 （顺带）今日 `基建任务` 失败

今日那次 `DijiangRewards` 失败应即 2.2 的②。同一验证覆盖。

## 3. P0：MapLocator 粗定位真机验证（采集/送货的前置）

- 前提：游戏在**世界**里（有 HUD 和小地图）。**不要重跑日常来进世界**——用只开游戏的节点：
  - `run AndroidOpenGame`（或从 UI 点「启动终末地」），它不消耗日常次数。
- 步骤：
  1. 游戏进世界后 `screenshot` → 拿到 1280×720 全帧（虚拟屏帧，含小地图，位于 `(49,51)-(167,171)`）；
  2. `coarselocate <帧路径>`；
  3. **判据**：zone 分类不是 `None`（真实世界帧）；搜索 ROI 合理；模板命中框落在地图资产的对应位置；
     返回的粗坐标与游戏内实际位置一致（可用大世界地图目视核对）。
- 已知限制（当天记录）：adb 小地图 0.8 缩放变体未接（真机走 Android native controller，不缩放）；
  模板尺度未补偿（`ValleyIV_Base` 是 15/16）。
- 附：从世界帧裁 `(49,51)-(167,171)` 喂 `yoloprobe`，检查 zone/tile 与画面一致（验证预处理通道顺序）。

## 4. P1：完整「全套日常」跑到底

今天只跑到第 3/15（拜访好友 ✅、基建任务 ❌、信用点购物刚起就停了）。明天完整跑一遍，覆盖：
`AndroidOpenGame / VisitFriends / DijiangRewards / CreditShoppingN2 / DeliveryJobs / SellProduct /
AutoStockpile / AutoStockStaple / AutoSell / EnvironmentMonitoring / DailyRewards /
SeizeDeliveryJobs / ProtocolSpace / AutoCollect`。

- 中途用第 1 节的探针**边跑边查**（这正是本次新增能力）。
- 任何失败都记下：任务名 + 时间点 + `report`/`logtail` 输出 + 当前帧（`screenshot` 存档）。

## 5. 纪律

- **测试间隙必须 `adb shell am force-stop com.hypergryph.endfield` 和 `com.aliothmoon.maafw.maaend`**（手机很烫）。
- 虚拟屏 displayId 每次重装/重建都会变（见过 2/3/4/5/6/10…21），用 `dumpsys display | grep MaaFwVirtualDisplay` 现查。
- CLI 端口：`adb forward tcp:7777 tcp:7777`；协议：一行一命令，读完 `--END--` 结束。
- 任务运行中探针可用（本次新能力），但 **`run` 是排队到任务结束后执行**，不会即时。
