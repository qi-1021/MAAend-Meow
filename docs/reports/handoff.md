# 交接：给下一个 harness 的快速上手与应做事项

> 目标读者：**接手的另一个 AI harness / 开发者**。本文只写"上手必需 + 该做什么 + 会踩什么坑"，
> 细节按需去 `docs/DEVLOG.md`（按时间倒序的完整记录）与 `docs/DEVELOPMENT_EXPERIENCE.md`（方法论）里找。
>
> 快照时间：2026-10-01 凌晨。当前主干 **`1bc9097`**，工作区干净，本地=远程，纯逻辑闸门 **1373/1373**，CI 全绿。

---

## 0. 这个项目是什么

**MAAend-Meow**：《明日方舟：终末地》的 **Android 端 MAA 移植**（MaaFwApp 架构 + MaaFramework v5.14.x）。
上游是 `MaaEnd/MaaEnd`（PC 端 MaaPiCli + cpp-algo/go-service）；本仓库把它落到 Android：App 进程 + Shizuku/Sui 特权进程，
原语用 Kotlin 移植，游戏 pipeline 来自上游 assets 并在构建期打补丁。

- 仓库：`/Volumes/mac第三磁盘/codes/Projects/MAAend-Meow`（**在移动硬盘上**，系统盘只剩 ~76GB）
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`
- 发布：`v0.1.7-beta.N` 标签 → 推送即触发 GitHub Release（Pre-release）

---

## 1. 开工前必须知道的五件事

1. **一切都在移动硬盘**：仓库、SDK、JDK、Gradle 缓存、模拟器 AVD、抢救的游戏数据。**严禁往系统盘写大文件**。
2. **构建必须走脚本**：`scripts/build_local.sh debug`。它先跑 `scripts/prepare_maaend.py`（把移动端补丁打进上游 assets）
   再跑 `setup_maa_framework.py`（下载 13 个 native `.so`）——**漏掉后者会得到"能装能开 UI 但框架加载失败、任务立刻 NOT_RUN"的包**。
3. **纯逻辑闸门**：`scripts/verify_pure_logic.sh all`（当前 1373）。新增纯逻辑文件/测试必须登记进**四个数组**
   （`MAIN_FILES` / `TEST_FILES` / `TEST_CLASSES` / `runner()` 的 classes），否则测试不会被执行。
   另有 `python3 scripts/check_i18n_strings.py`（中英必须一致）——两者都在 CI 里卡着。
4. **上游子模块规则**：`upstream/maaend` 可读、可以跑构建期补丁改其工作树，但**永远不要提交子模块指针**；
   所有对上游 pipeline 的修改都写进 `scripts/prepare_maaend.py` 的补丁层（按节点名定位、缺了要 `raise`、最好配构建期断言）。
5. **DEVLOG 是活文档**：每次改动后往 `docs/DEVLOG.md` **最上面**加一条（做了什么 / 为什么 / 怎么验的 / 教训 / 未做）。

---

## 2. 命令速查

```bash
# ── 构建 / 装机 ─────────────────────────────────────────
scripts/build_local.sh debug                 # 本地完整构建（移动硬盘工具链）
adb -s b8459a87 install -r app/build/outputs/apk/debug/app-debug.apk   # 真机覆盖安装（同签名）
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk  # 模拟器

# ── 闸门 ───────────────────────────────────────────────
scripts/verify_pure_logic.sh all             # 纯逻辑（本机可跑）
python3 scripts/check_i18n_strings.py        # 中英一致性

# ── 设备：真机是 b8459a87，模拟器是 emulator-5554（两者可能同时在线，务必 -s 指定）──
adb devices -l

# ── 调试 CLI（App 内，debug 构建 + 设置里开「调试模式」才监听；端口 7777）──
adb -s b8459a87 forward tcp:7777 tcp:7777
# 协议：**一行一命令**；服务端先发欢迎语+`--END--`，再发响应+`--END--` —— 客户端要读到**第二个** `--END--`。
# 命令：status / report / logtail / screenshot / ocr <node> / run <node> / probe-result /
#        overrideprobe / yoloprobe <img> / coarselocate <frame> [zone] / tracklocate <frame>|reset /
#        mapfind <zone> <at_x> <at_y> [icon] / walk <x> <y> [zone] / start [task...] / stop / copyout / rootcmd
```
小工具：本会话一直在用 `/var/folders/zk/xbxc7nhn36d2cb93g6rlsmg40000gp/T/opencode/cli.py`（已处理双 `--END--`），
临时目录可能被清；丢了就照上面的协议重写一个 30 行的 socket 客户端。

```bash
# ── 远程调试（真·公网，手机出站）──────────────────────
# 1) 本机跑桥/中继（经 Cloudflare Tunnel 暴露为 https://maaendset.qiisme1021.space）
python3 scripts/debug_cli_bridge.py --listen 127.0.0.1:7788 --target 127.0.0.1:7777 --token <App 里显示的令牌>
# 2) 隧道：~/.cloudflared/config.yml 里 maaendset.* → http://127.0.0.1:7788；改完**重启** cloudflared
# 3) App：设置 → 远程调试 → 填地址（默认已填）→「连接远端」→ 显示"已连接（会话 …）"
# 4) 远端（任何网络）：curl -sS -X POST https://maaendset.qiisme1021.space/ -H "X-Token: <令牌>" --data 'status'
```

**设备纪律（用户硬性要求）**：测试间隙必须
`adb -s <S> shell am force-stop com.hypergryph.endfield` + `... am force-stop com.aliothmoon.maafw.maaend`，
并确认残留进程 0——手机/模拟器会烫（实测峰值 44~49°C）。

---

## 3. 当前进度

### 已真机验证可用
- **据点交易**（`OutpostTradingSchedule`）、**自动囤货**（`AutoStockpile`，山谷+武陵选品正确）、**拜访好友**、**打开游戏**。
- **基建任务**（`DijiangRewards`）：修复前必红（提取弹窗关不掉），现整任务绿。
- **调试 CLI 全部命令**，含**任务运行中**可用（只读探针走独立 resource-only tasker，不与任务抢 controller）。
- **远程调试真公网**：蜂窝+VPN 下手机出站连接成功，`curl` 公网拿回完整 status。
- **补充包下载**（三个包）——修了两个真 bug 后正常。
- **MapFind（大地图传送）**：全屏地图上解 viewport（scale≈0.599）→ 投屏 → 图标确认 0.78~0.84 命中；
  AutoCollect 的 18 个进世界锚点全部带安全收尾兜底。
- **定位精度**：`MapLocateAssertLocation` 修掉"位置被夹到搜索 ROI 左上角"后误差 219px→36px，Route3/5 与 CommonRoute6_1 已能命中。

### 关键未完成
- **走路闭环（最高优先）**：朝向源、转向/摇杆纯逻辑、闭环执行器、`walk` 探针都实现了，真机上闭环每拍都在跑，
  但**位置源有 ~170px 伪匹配**且**相机 swipe 未见生效**（yaw 位级不变）→ 判据"真的在走近/到点/采集"未达成。
- **模拟器（现已是唯一设备）**：手机 `b8459a87` 已离开；`emulator-5554` 完全可用，
  且**游戏已能进 3D 世界**（配方：`EMU_GPU=host` + 游戏内画质调低；`swiftshader_indirect` 会全黑）。
  MAAend 已装（`Shizuku (Connected)`、`Service status: Ready`；Shizuku server 以 root 运行——
  `adb root` 后执行 APK 内 `lib/arm64/libshizuku.so` 即可）。
  只剩两步 UI 操作：**开「调试模式」**（CLI 才监听 7777）+ 在「补充包」里**下载 map-locate**（走镜像）。
  启停：`EMU_GPU=host bash /Volumes/mac第三磁盘/AndroidStudio/start_emulator.sh`（见同目录 `EMULATOR.md`）。

  **模拟器启动的已知坑（今晚实测）**：`start_emulator.sh` 自带 60s 的 adb 超时偏短，冷启动常要 2-3 分钟，
  超时报错不等于失败，先 `adb devices` 复查。用 `emu kill` 关掉之后再启动，出现过两次
  **卡在日志最后一行 `Vulkan emulation initialized`**（qemu CPU 0.1%、无 adb、无监听端口）——
  疑似 AVD 脏锁或宿主 GPU 上下文（显示器休眠时 `-gpu host` 可能拿不到 Metal）。处置顺序：
  1) 杀干净 `qemu-system` 进程并删 `maaend_api35.avd/*.lock`；2) 隔一会儿用 `-gpu swiftshader_indirect`
  重试（它虽跑不动游戏，但足够做 App 侧设置）；3) 仍不行再考虑 `-wipe-data` —— **注意那会清掉已导入的
  30GB 游戏数据**（数据本体在 `/Volumes/mac第三磁盘/codes/game-rescue/`，可重导，但要花时间）。
  躺平恢复后 `player.log` 里应能看到 `Vulkan emulation initialized` 之后的 `boot completed`。
- **能用/不能用的渲染配置（已实测）**：`-no-window -gpu host` = 游戏能进 3D 世界 ✓；
  有窗口的 `-gpu host` = 卡在崩溃上报弹窗 ✗；`swiftshader_indirect` = 游戏画面全黑 ✗。
- **模拟器上的 pipeline SIGILL：已定位、已在构建期补掉（还差一次实测确认）**：
  崩溃指令是 `kleidicv::sve2::float_conversion` 里的 `cnth/ptrue`（SVE）。根因是模拟器把宿主 M4 的
  `sve2/sme` 当 HWCAP 透传给 guest，而 MaaDeps 的 OpenCV 4.12.0 静态链了 KleidiCV 0.5.0 HAL，
  每个 API 的静态构造函数据此选中 SVE2 内核。`OPENCV_CPU_DISABLE` 管不到 HAL（4.12.0 的 ARM 名字
  里根本没有 SVE/SME），升级 emulator（canary 37.3.2）同样无效，两条路都已实测排除。
  **解法**：把 `getauxval@plt` 桩改成返回 0——全库 195 处 `getauxval` 调用全部来自 KleidiCV 各 API 的
  静态初始化且共用这一个桩，改后所有探测都认为「没有高级特性」，KleidiCV 回退 NEON 基线（即任何不支持
  SVE2 的真机本来就会走的路径）。实现见 `scripts/setup_maa_framework.py` 的 `patch_kleidicv_getauxval()`，
  铺完 .so 后自动执行、字节不符即报错、幂等。**只需在模拟器上跑一次任务确认 SIGILL 消失**（记得用完彻底关机）。
  失败的那条环境变量注入路线已从代码里撤掉，别再重试。
- **虚拟机纪律（用户要求）**：不用时**彻底关闭**（`adb emu kill` + 确认无 `qemu-system` 残留、
  无 5554/5555 监听、AVD 目录无 `.lock`），不要留挂起实例；并**尽量少用虚拟手机**，它性能开销大。
- **清空重建的完整配方**：删 `userdata-qemu.img.qcow2` → 装 `base.apk` → 首启建目录 →
  `tar xf -` 流式灌 30GB → 解 CE/DE → 用 `pm list packages -U` 的 uid 统一三个目录属主 + `restorecon`
  → 装 App/Shizuku（`libshizuku.so` 起 root server）→ 开调试模式 → 下 map-locate 补充包。
- **GrowBack 修复待复测**：培养舱 3 槽被种满（16~43h 成熟），提取分支当前不可达。
- **root 隐藏模块未装**（Zygisk-Next / Shamiko + 排除列表）——用户明确要求过。
- CreditShopping 等上游 draft PR #6055 合并后再整体更新。

---

## 4. 应做事项（按优先级）

1. **走路闭环标定**（采集/送货的核心）：
   - 前提：角色在**开放区**（Wuling/ValleyIV）。⚠️ 实测在**基地/工业区**场景下 YOLO 对小地图返回 `None`，
     整条定位链不可用——**别在基地里调走路**。
   - 用 `walk <x> <y> [zone]` 直连闭环逐拍看：`action`（WALK/TURN/RELOCATE/HOLD/ARRIVED/FAILED）、
     `distance` 是否单调减小、`yaw` 是否有值、`turn_px` 是否在修正。
   - 已知待查：① `MapNavRuntime.dir8` 的摇杆语义（相机相对 vs 世界轴，走反就改符号）；
     ② 相机 swipe 起点/时序（已按上游 `y` = 720/2−96 修正，但仍需受控 ±30° 验证是否真转视角）；
     ③ PositionProvider 的伪匹配门限（差 ~170px 的簇要被拒）。
2. **模拟器 GPU**：换 `-gpu swiftshader_indirect`（或 `angle_indirect`）/ 换 system image，让游戏进世界；
   随后模拟器就是无手机时的主要调试载体（`adb root` 可用、Shizuku 每次重启需重跑 starter）。
3. **root 隐藏模块**：手机（KernelSU，LKM，超级用户列表 27 项）装 Zygisk-Next + Shamiko 并把
   `com.hypergryph.endfield` 加进排除列表；模拟器侧注意 `ro.debuggable=1` 等检测面。
4. **GrowBack 复测**：作物成熟后再跑基建任务，断言 `SeedExtractClose` 首次即命中、坐标兜底不再触发。
5. `IconRecognition`（送货装箱用，上游 ~8495 行 C++）——只在需要装箱时做窄版。
6. 上游 PR #6055（信用点商店重构）合并后整体同步。

---

## 5. 已知坑（血泪，按"会不会再犯"排序）

1. **CLI 客户端要读到第二个 `--END--`**（欢迎语自带一个）；中继回传只取**第 3 段**（欢迎语/auth/命令）。
2. **`adb shell` 退出会带走后台子进程**——所以远程调试的客户端必须做进 App（已做）。
3. **`File.getUsableSpace()` 对不存在的路径返回 0**——空间检查前必须先 `mkdirs`。
4. **`supplements/` 目录属主**：用 adb 手工推文件建出来的目录属主是 `shell`，App 写不进去（EACCES）——别手推，让 App 自己建。
5. **D8 与 R8 接受面不同**：Kotlin 的局部函数 + 一堆默认参数会让 debug 的 D8 内部崩溃（release 的 R8 没事）；
   用 build-tools 的 `d8` 单独 dex 那个类可秒级复现。
6. **同签名才能覆盖安装**：CI 的 debug 包每次运行都用**新建的临时 debug keystore**，所以换包常常要卸载重装。
7. **本地构建必须复刻 CI 的每个前置步骤**（prepare_maaend + setup_maa_framework）。
8. **图定式模板在 720p 真机易失配**（页签、锚点等）；坐标点击或 ADB 覆盖更稳（`prepare_maaend.py` 里有成例）。
9. **任务运行中探针**：只读探针走独立 tasker；`run` 会驱动点击，被排队到任务结束后。
10. **子代理会阻塞主代理**：派了 lane 主进程就停下等它——要么一次多派几条互不冲突的（按文件所有权切），要么自己做完。
11. **模拟器 `-gpu auto` 会因宿主内存压力降级成纯软件渲染**；要用 `-gpu host`（Metal）显式指定。
12. **网络**：本环境 `raw.githubusercontent.com` 会被重置；`api.github.com`/`cdn.jsdelivr.net`/`ghproxy.net` 可用；
    Mac 上若有透明代理（DNS 解析到 `198.18.x.x`）会把 cloudflared 打死，重启它时要清代理环境变量。

---

## 6. 敏感信息纪律

**不要写进仓库/文档/日志**：设备锁屏 PIN、访问令牌、任何账号凭据。
令牌只从 App 设置页读取；泄露了就点「重置」（会立即作废已鉴权会话）。
`.slim/deepwork/` 下的进度文件是**本地文件**（已在 `.gitignore` 里），可以写敏感上下文，但别复制进 `docs/`。
