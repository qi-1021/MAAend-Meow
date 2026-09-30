# 调试 CLI 与远程调试：开发记录与复用指南

> 面向对象：**AALC-meow**（以及任何基于 MaaFramework 的 Android 端移植项目）。
> 本文只讲「怎么做的、为什么这么做、踩过什么坑」，给的是可照搬的结构与判断依据，不是逐行 API 手册。
>
> 本文描述的实现位于 `MAAend-Meow` 仓库：`app/src/main/java/com/aliothmoon/maafw/cli/`、
> `app/src/main/java/com/aliothmoon/maafw/remote/RemoteServiceImpl.kt`、
> `scripts/debug_cli_bridge.py`、`scripts/relay_client.sh`。相关提交：`4a3268c`(初版) → `0fa9aa1`(任务运行中可用) →
> `1ef04b8`(开机即启 + 远程调试 + start/stop) → `98012bd`(PI 就绪补触发)。

---

## 0. TL;DR

| 能力 | 一句话 |
|---|---|
| 本地调试 CLI | 手机上开一个**只读/可控**的行协议命令行（端口 7777），`adb forward` 后你在电脑上就能观察/操作真机 |
| 开机即启 | debug 构建 + 应用内「调试模式」打开时，**App 一起来 CLI 就在监听**，不必先跑一次任务 |
| 任务运行中可用 | 截图/OCR/探针在**任务跑着的时候**也能用（只读探针走独立的 resource-only tasker），`run` 排队、结果另取 |
| 远程调试 | 可选开关 + **强安全警告** + 令牌鉴权；远端经 Cloudflare Tunnel → 本机 HTTP 桥 → 手机 CLI |
| 反向控制 | `start [task...]` / `stop`：CLI 通过反向 IPC 请求 App 进程按 UI 同一条路径起停任务 |

---

## 1. 为什么需要它（问题定义）

Android 端跑 MaaFramework 的痛点：**你没法像 PC 端那样随手开个终端看状态**。出错时只有：

- 一堆日志文件（要先 adb pull），
- 一个需要「重跑一遍」才能复现的现场，
- 以及「任务正在跑，我想看看现在屏幕上到底是什么」这种最朴素的需求——原来完全做不到。

所以这个 CLI 的目标不是替代日志，而是把**「观察」与「干预」**变成可脚本化的原语：

- 观察：`status` / `report` / `logtail` / `screenshot` / `ocr <node>`
- 探针：`overrideprobe` / `yoloprobe` / `coarselocate`（各自验证一条关键链路）
- 运行：`start` / `stop` / `run <node>`
- 拿回结果：`probe-result`

---

## 2. 总体架构

### 2.1 进程模型（这是全篇最关键的一点）

```
┌─────────────────────────────┐        ┌──────────────────────────────────────┐
│ App 进程 (com.…maaend)      │        │ 特权进程 (Shizuku/Sui 拉起, shell/root)│
│                             │        │  RemoteServiceImpl                    │
│  UI / Compose               │        │   ├─ MaaRunner（框架 + 控制器）        │
│  SessionViewModel           │        │   ├─ DebugCliServer ← 监听 7777        │
│  RunLauncher / RunnerPort   │◄──────►│   └─ RunDiagnostics / 日志             │
│  DebugCliCoordinator（新）  │  binder │                                       │
└─────────────────────────────┘        └──────────────────────────────────────┘
                                                    ▲
                                adb forward tcp:7777 │ 或 局域网/隧道
                                                    │
                                            你的电脑（nc / 小脚本 / HTTP 桥）
```

要记住的三件事：

1. **CLI 服务跑在特权进程**，不在 App 进程。因为它要用 `MaaRunner`（框架实例、控制器、任务器）。
2. **`RunPlan` 只在 App 进程能构建**（依赖 `ProjectDefinition` + `UserConfiguration`）。特权进程只收到最终 JSON。
   → 所以 `start/stop` 必须**反向 IPC**（下面 5.4）。
3. **App 进程与特权进程之间只有 binder**，没有共享内存。所有跨进程能力都要走 AIDL。

### 2.2 协议（刻意做得极简）

- 裸 TCP，**一行一条命令**；
- 每次响应的**最后一行固定是 `--END--`**；
- 服务端在连接建立时**先发一段欢迎语 + `--END--`**，之后才是命令响应 + `--END--`；
- 命令名大小写不敏感，节点名大小写敏感；多余参数**报错而不是忽略**（避免"以为生效了"）。

> ⚠️ **客户端第一坑**：因为欢迎语自带一个 `--END--`，客户端必须读到**第二个** `--END--` 才算读完响应。
> 我们最初的 `nc` 一次性读、以及第一版 Python 客户端都在这里踩过（表现为「只能看到欢迎语」）。

---

## 3. 命令清单（截至本文）

| 命令 | 作用 | 备注 |
|---|---|---|
| `help` | 列出命令 | |
| `status` | project root / controller / 任务状态 / 报告与日志目录 | CLI 的"仪表盘" |
| `report [n]` | 最新 RunDiagnostics JSONL 的末 n 行 | 结构化报告 |
| `logtail [n]` | 主日志末 n 行 | |
| `screenshot` | 把当前缓存帧存成 png，返回路径 | **运行中也可用** |
| `ocr <node>` | 对当前帧跑该识别节点，返回 best 文本 | |
| `run <node>` | 跑一次该节点（截断 next） | 运行中会**排队到任务结束后**执行 |
| `probe-result` | 读回最近一次排队执行的调试结果 | 与 `run` 配对 |
| `overrideprobe` | 验证 OverrideImage + TemplateMatch 链（合成图） | 不依赖游戏/补充包 |
| `yoloprobe <img>` | 小地图预处理 → NeuralNetworkClassify | 验模型/通道顺序 |
| `coarselocate <frame> [zone]` | 全帧裁小地图 → YOLO → 地图资产上 TemplateMatch | 端到端粗定位 |
| `auth <token>` | 远程连接鉴权 | 回环免鉴权 |
| `start [task...]` | 启动任务（不带参数=当前激活配置；带参数按 taskName 筛选） | 反向 IPC |
| `stop` | 停止当前任务 | 反向 IPC |

---

## 4. 启动时机与生命周期

### 4.1 开机即启（`1ef04b8`）

原来 CLI 只在 `RemoteServiceImpl.setup()` 里启动，而 `setup()` 由「开始一次运行」触发
→ 你**必须先跑一次任务**才能用 CLI，恰恰错过了最想调试的启动阶段。

改法：App 侧 `MaaFwApp.postCreate` 起一个 DI 管理的 `DebugCliCoordinator`；它在
「调试模式 / 远程调试 / 令牌 / 服务连接状态」这些流上 `combine + distinctUntilChanged`，
**一旦满足条件就调用 `RemoteService.configureDebugCli(...)`**。

特权侧那个方法**刻意只做 CLI 需要的两件事**：

```kotlin
// RemoteServiceImpl.configureDebugCli(...) —— 只做这些
runner.setProjectRoot(piRoot)      // 探针/run 需要 PI 根
DebugCliServer.configure(remote, token)
```

**没有**搬到这里的（以及为什么）：
`applyGlobalOptions`（配 native 日志）、`RunDiagnostics.start`（建文件）、
`disablePhantomProcessKiller`（改系统设置）、建目录——它们属于"真的开始跑一次"，
提前做要么有副作用、要么语义错位。

### 4.2 一个必须补的触发（`98012bd`）

冷启动时 PI 常常还没解压完（`installedDir()` 会抛），于是第一次 `configure` 传过去的
`piRoot` 是空的；而原来的触发源里**没有"PI 就绪"**，所以 `project root` 会**永远**显示 `(未设置)`。

修法：把 `PiInstallCoordinator.state` 也接进触发集合，并**映射成 Boolean**
（`it is PiInstallState.Ready`）——否则 `Unpacking` 的进度流会疯狂重触发；
`distinctUntilChanged()` 只在 `false → true` 那一下放行，`apply()` 每次重读 `installedDir()`，
PI 一就绪就把 root 补上。**这是"异步资源就绪"类问题的通用模式。**

### 4.3 release 硬门控

所有入口一律 `BuildConfig.DEBUG` 短路 + `isDebug`（应用内「调试模式」开关）双闸门。
**释放包不监听任何端口、不建任何文件。** 这条每次改 CLI 都要回归。

---

## 5. 任务运行中也能用（`0fa9aa1`）

### 5.1 先读框架，别猜

最初的实现是把探针硬门控在 `isRunning()` 上。用户明确说"这是错的"——他想边跑边查。
读 MaaFramework 源码后确认了三件事（**这些结论直接决定方案**）：

1. tasker 是**单线程串行队列**：`Tasker.cpp` 里一个 `AsyncRunner`，运行中再 post 只会排队，
   `Wait` 会阻塞到当前任务结束。
2. `MaaTaskerPostRecognition/PostAction` **与 task 共用同一队列**，救不了。
3. 同一个 controller **可以**绑第二个 tasker，但**不安全**：框架在每个 task 结束会
   `controller_->auto_release_pressed()`，会把**正在跑的任务按着的手指提前放掉**。

### 5.2 方案

- **四个只读探针**（`ocr`/`overrideprobe`/`yoloprobe`/`coarselocate`）：
  用一个**只绑 resource、不绑 controller** 的临时 tasker + `MaaTaskerPostRecognition`。
  队列独立 → **真并行**；`controller_` 为 null → 框架跳过 `auto_release_pressed`，完全不碰输入状态。
  OCR 用 `MaaControllerCachedImage` 取主 controller 的当前帧当输入。
- **`run`**（会驱动点击，离不开 controller）：**排队**到任务结束后由 run worker 独占执行，
  CLI 立即返回，结果用 `probe-result` 读回——**绝不让 socket 卡几十秒**。
- 模型 bundle 预热：把 YOLO 分类 bundle 的注册挪到空闲的 `prepare()`，并**按 resource 记忆**，
  避免运行中 `MaaResourcePostBundle` 改 resource（框架 res manager 没锁）。

### 5.3 血泪教训

override 一个**共用**节点时，**所有会变的键都必须显式写出**——框架的 override 会**继承上一次的值**。
我们曾在 `__GoodsOcrProbe` 上因为没写 `only_rec` 而继承了上一轮的 `true`，导致 OCR 静默读空。

---

## 6. 远程调试（`1ef04b8`）

### 6.1 安全模型（照这个抄）

| 机制 | 做法 |
|---|---|
| 默认关 | 开关默认 false；release 下不存在 |
| 强提醒 | 开启时弹**高可见度**警告（⚠ 标题 + 明确写清"可远程操控、读截图与日志、模拟点击"）+ 必须显式确认 + 可勾「不再提示」（持久化） |
| 令牌 | App 用 `SecureRandom` 生成、设置页可查看/复制/**重置**（重置即作废已鉴权会话） |
| 鉴权 | **非回环**连接必须先 `auth <token>`，其余命令一律拒绝；常量时间比较；**5 次失败锁 30 秒** |
| 回环豁免 | `adb forward` 走的回环**免令牌**——现有脚本/工作流不能坏 |
| fail-closed | 判断"是否回环"失败时按"需要鉴权"处理 |
| 绑定 | 远程开启才绑 `0.0.0.0`，否则只绑 `127.0.0.1` |

### 6.2 公网接入链路（Cloudflare Tunnel + HTTP 桥）

**为什么需要桥**：CLI 是**裸 TCP 行协议**，而 Cloudflare Tunnel 免费套餐只暴露 **HTTP(S)**。
两条路：

- (A) 远端装 `cloudflared access tcp`（要配 Cloudflare Access）——适合技术型远端；
- (B) **本机跑一个薄 HTTP 桥**（推荐，见 `scripts/debug_cli_bridge.py`）：HTTP 进 → 裸 TCP 出，
  并把令牌校验放在桥上（同时透传给 App，**两道闸门**）。

拓扑：

```
远端  ──HTTPS(POST + X-Token)──▶  Cloudflare  ──▶  本机 cloudflared
                                                    │ ingress: maaendset.* → http://127.0.0.1:7788
                                                    ▼
                                      scripts/debug_cli_bridge.py（只听回环）
                                                    │ 裸 TCP：先 auth <token> 再发命令
                                                    ▼
                                      手机 App CLI（远程模式下监听 0.0.0.0:7777，局域网来源必须鉴权）
```

本机配置（`~/.cloudflared/config.yml`）：

```yaml
ingress:
  - hostname: maaendset.qiisme1021.space
    service: http://127.0.0.1:7788      # 注意是 127.0.0.1 而不是 localhost
  - service: http_status:404
```

```bash
cloudflared tunnel route dns <tunnel> maaendset.qiisme1021.space   # 建 CNAME
# 改完 config 必须重启 tunnel 进程（它只在启动时读 ingress）
```

远端用法：

```bash
curl -s -X POST https://maaendset.qiisme1021.space/ \
     -H 'X-Token: <App 里显示的访问令牌>' --data 'status'
```

> **坑**：tunnel 的 `service: http://localhost:PORT` 在有些机器上会解析到 `::1`，
> 而 `adb forward` 只监听 IPv4 `127.0.0.1` → 502。**写 `127.0.0.1` 最稳。**

### 6.3 自建调试服务器（开源套件与设置方法）

上面 6.2 那套「桥主动回拨手机 CLI」在**手机与桥不同网段 / 蜂窝网络**下不成立：手机在
蜂窝 / CGNAT / 异网下**无法被入站连接**（拿不到公网可达的入站地址，NAT 也不放行），
`adb forward` 与局域网直连自然全部失效——用户实测「一开 VPN 整条链就断」正是这个原因。

正确架构是**手机出站**：手机主动连公网桥建立会话，握手成功后远端即可经桥发命令。
本仓库自带的开源调试服务器套件就是为此准备的，**不需要自己再写**：

| 文件 | 角色 |
|---|---|
| `scripts/debug_cli_bridge.py` | 服务端：HTTP 桥 + 中继（Cloudflare Tunnel 只暴露 HTTP(S)，它把 HTTP 翻译成 CLI；中继模式下把命令排队给手机出站会话） |
| `scripts/relay_client.sh` | shell 版中继客户端（**无 App / 手工验证**场景用；App 内已有等价的 Kotlin 客户端，见 §6.4） |

#### 协议（桥暴露的四个接口，都要 `X-Token`）

```
POST /attach                        → 200，响应体一行纯文本 sid（建立会话）
GET  /pull?sid=..&timeout=25        → 200：第一行 rid、其余是命令；204：暂无命令（手机长轮询取命令）
POST /result?sid=..&rid=..  body=输出 → 200（手机回传命令输出）
POST /                     body=命令  → 200：命令输出（远端调试者入口；桥按最早会话排队并等结果）
```

#### 令牌从哪来

令牌就是 App 设置页「远程调试」里的**访问令牌**（`SecureRandom` 生成的 32 位串，可复制/重置）。
桥的 `--token`、`X-Token`、以及 App 内客户端用的都是同一个值：它既是桥的准入密钥，
也是手机 CLI 的鉴权令牌（两道闸门）。

#### 设置步骤

```bash
# 1) 在 App 里开「调试模式」→ 开「远程调试」（过强安全警告）→ 复制访问令牌
# 2) 本机起桥（只监听回环；中继模式手机出站也能连）
python3 scripts/debug_cli_bridge.py \
    --listen 127.0.0.1:7788 --token '<App 里复制的令牌>' --mode relay
# 3) Cloudflare Tunnel 把 maaendset.qiisme1021.space 指到 7788
```

`~/.cloudflared/config.yml`：

```yaml
ingress:
  - hostname: maaendset.qiisme1021.space
    service: http://127.0.0.1:7788     # 必须写 127.0.0.1，别写 localhost（可能解析到 ::1）
  - service: http_status:404
```

```bash
cloudflared tunnel route dns <tunnel> maaendset.qiisme1021.space   # 建 CNAME
# 改完 config 必须重启 tunnel 进程（只在启动时读 ingress）
```

```bash
# 4) 手机端：App 设置里「中继地址」默认就是 https://maaendset.qiisme1021.space
#    （默认值可修改），点「连接远端」→ 状态变「已连接（会话 <sid>）」
# 5) 远端任意机器发命令，拿到的就是完整 status：
curl -sS -m 150 -X POST https://maaendset.qiisme1021.space/ \
     -H 'X-Token: <App 中的访问令牌>' --data 'status'
```

无 App（或想手工验证桥）时用 shell 客户端：
`sh scripts/relay_client.sh https://maaendset.qiisme1021.space <令牌>`（手机上有 `curl`/`nc` 时可直接跑）。

#### 已知坑（照单全收）

1. **Cloudflare 免费档单请求有 ~100s 上限**，而手机侧的长轮询不能太长：桥把 `timeout`
   夹到 `1..60`，手机客户端取 `25s` 留足隧道与排队余量。别把 `timeout` 开到上百秒。
2. **本地 CLI 的裸 socket 响应分三段、每段以 `--END--` 结尾**（①欢迎语 ②`auth` 回执 ③命令结果）。
   shell 客户端（`relay_client.sh`）必须**只回传第 3 段**；漏了就会把欢迎语当成命令输出回给远端。
   App 内客户端不绕这条 socket，而是直接在特权进程里复用解析+分发（见 §6.4），天然没有三段问题。
3. **`adb shell` 起的后台进程会随会话退出被带走**，所以手机端客户端必须跑在**App 进程内**、
   由用户开关驱动，而不是靠 adb 起一个 shell 常驻。App 内客户端正是为此。
4. **手机在蜂窝 / CGNAT / 异网下只有出站可靠**；VPN 会改路由，直连/回拨方案一开 VPN 就断，
   出站中继不受影响——这也是"必须有中继模式"的根本原因。
5. **`X-Token` 同时是桥与 CLI 的密钥**：令牌一换，桥必须重启，App 内客户端需重连。

### 6.4 App 内中继客户端（Kotlin）

位置：`app/src/main/java/com/aliothmoon/maafw/cli/DebugCliRelayClient.kt`（I/O 与线程）、
`DebugCliRelay.kt`（纯逻辑：地址规格化 / 桥响应解析 / 状态码 / 退避，纳入本机纯逻辑验证）。
它在**特权进程**（与 `DebugCliServer` 同进程）里跑，这样命令能直接复用既有的
`DebugCliSupport.parse` + `DebugCliHost` 执行路径，不另起一套：

```
runLoop（可停止，断线指数退避重连）
  ├─ POST /attach                      → sid；失败记 detail 并退避重试
  ├─ GET  /pull?sid=..&timeout=25       → 200 命令 / 204 无命令（长轮询）
  ├─ DebugCliServer.executeRelayCommand(host, 命令)  ← 复用 parse + dispatch
  └─ POST /result?sid=..&rid=..         → 回传输出
```

状态机对外经 binder 暴露：`IDLE → CONNECTING → CONNECTED(sid) / FAILED(原因)`，另有已处理条数。
App 侧（`SessionViewModel`）只轮询这四个值写进 `SessionUiState`；设置页据此显示状态行与「断开」。
生命周期完全由用户驱动：**开「远程调试」并点「连接远端」才跑，关设置或点「断开」即停**。

---

## 7. 踩过的坑（清单，按"会不会再犯"排序）

1. **客户端要读到第二个 `--END--`**（欢迎语+响应两段）。→ 写客户端时先跑一次 `nc` 看清报文形状。
2. **tasker 是串行队列**，别指望"post 一下就能在运行中并行"（除非不绑 controller）。
3. **第二 tasker 不要绑 controller**：`auto_release_pressed` 会破坏正在运行的任务。
4. **override 共用节点必须写全可变键**（继承上一轮的坑）。
5. **`run` 在运行中不能即时执行**（要驱动点击）：排队 + 另取结果，别阻塞 socket。
6. **CLI 起得来 ≠ 能用**：`project root` 依赖 PI 解压完成，要有"就绪后补触发"。
7. **签名不匹配**：CI 的 debug 包每次运行都用**新建的临时 debug keystore**，两次构建签名就不同
   → 换包要么卸载重装（丢应用数据），要么本地构建保证同签名。本地构建完直接 `adb install -r` 最省事。
8. **本地构建必须复刻 CI 的构建期步骤**：我们的项目里是 `prepare_maaend.py`（打上游管线补丁）+
   `setup_maa_framework.py`（下载 13 个 native `.so`）。
   **漏了后者会得到"能装能开 UI、但框架加载失败（`UnsatisfiedLinkError`）、任何任务立刻 `NOT_RUN` 结束"的包**——
   这个坑我们真踩了。已固化进 `scripts/build_local.sh`。
9. **D8 与 R8 接受面不同**：release 能编不代表 debug 能编。我们遇到 D8 对某个方法内部崩
   （`ArrayIndexOutOfBoundsException`，R8 同代码没事）。定位手段：用 build-tools 里的 `d8`
   **单独 dex 那个类**，秒级复现/验证，比反复跑 gradle 快一个数量级。
   触发形状：Kotlin 的**局部函数**（捕获一圈局部变量）+ 方法上**一堆默认参数**。
   解法：局部函数抽成成员类型、实现体拆成不带默认参数的私有方法。

---

## 8. 给 AALC-meow 的复用建议

可以照搬的部分：

- **协议形状**（一行一命令 + 结束标记 + 欢迎语）：极简、任何语言都能写客户端。
- **进程划分**：CLI 放特权进程、`RunPlan` 留 App 进程、`start/stop` 走反向 IPC。
- **探针方案**：resource-only tasker + `PostRecognition` 做"运行中只读观察"；会驱动输入的操作一律排队。
- **安全模型**：默认关 + 强提醒 + 令牌 + 常量时间 + 限速 + 回环豁免 + fail-closed。
- **远程链路**：Cloudflare Tunnel + 本机 HTTP 桥（`scripts/debug_cli_bridge.py` 可直接拿走改）。
- **构建脚本**：把 CI 的每一步（补丁、下载 native、gradle）固化成一个本地脚本。

需要按自己项目改的：

- AIDL 接口名/方法号（我们用了 `configureDebugCli=80` / `setAppCommandCallback=81`，留足增量空间）。
- "调试模式"开关的落点（我们的在设置页，且是 CLI 的总闸门之一）。
- 探针具体验证什么——按你的识别/动作链路设计（我们的是 OverrideImage、YOLO 分类、地图粗定位）。
- 反向 IPC 的载荷（我们传 `RunTrigger.Manual` + taskFilter；你们可能还要传配置覆盖）。

---

## 9. 验证清单（可照跑）

```bash
# ── 本机（纯逻辑闸门）──────────────────────────────
scripts/verify_pure_logic.sh all          # 含 CLI 解析/鉴权/限速的纯逻辑测试
python3 scripts/check_i18n_strings.py     # 中英一致性

# ── 真机 ──────────────────────────────────────────
adb forward tcp:7777 tcp:7777
printf 'status\n' | nc 127.0.0.1 7777     # 应看到欢迎语 + 响应（两个 --END--）

# 开机即启：装好包、开调试模式，冷启动 App，不跑任务直接 status → project root 应为 PI 路径
# 运行中可用：跑一个任务，然后 screenshot / coarselocate <刚存的帧路径>，应不阻塞、任务不受影响

# ── 远程 ──────────────────────────────────────────
# 1) App 设置里开「远程调试」（确认强警告）→ 记下令牌
# 2) 起桥：
python3 scripts/debug_cli_bridge.py --listen 127.0.0.1:7788 --target <手机局域网IP>:7777 --token <令牌>
# 3) Cloudflare ingress 指向 http://127.0.0.1:7788 并重启 tunnel
# 4) 本机自测：
curl -s -X POST http://127.0.0.1:7788/ -H 'X-Token: <令牌>' --data 'status'
# 5) 公网：
curl -s -X POST https://maaendset.qiisme1021.space/ -H 'X-Token: <令牌>' --data 'status'
# 6) 负例：不带/带错令牌应被拒（桥 401；直连手机则 "远程连接未鉴权：请先执行 auth <令牌>"）
```
