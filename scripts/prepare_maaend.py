#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Prepare MAAend assets, OCR models, and configuration for Android packaging.
Ensures zero pollution of system /tmp (all caches/temps in project root).
"""

import json
import os
import re
import shutil
import sys
import urllib.request
from pathlib import Path


def strip_json_comments(text: str) -> str:
    """Safely strip // and /* */ comments from JSONC without affecting strings."""
    def replacer(match):
        s = match.group(0)
        return " " if s.startswith("/") else s

    pattern = re.compile(
        r'//.*?$|/\*.*?\*/|\'(?:\\.|[^\\\'])*\'|"(?:\\.|[^\\"])*"',
        re.DOTALL | re.MULTILINE
    )
    return re.sub(pattern, replacer, text)

PROJECT_ROOT = Path(__file__).resolve().parents[1]
TMP_DIR = PROJECT_ROOT / ".tmp"
MAAEND_ROOT = PROJECT_ROOT / "upstream" / "maaend"
ASSETS_ROOT = MAAEND_ROOT / "assets"
OCR_TARGET_DIR = ASSETS_ROOT / "resource" / "model" / "ocr"

# Ensure temp directory stays strictly inside project
TMP_DIR.mkdir(parents=True, exist_ok=True)
os.environ["TMPDIR"] = str(TMP_DIR)
os.environ["TEMP"] = str(TMP_DIR)
os.environ["TMP"] = str(TMP_DIR)

OCR_BASE_URL = "https://raw.githubusercontent.com/MaaXYZ/MaaCommonAssets/main/OCR/ppocr_v6/small"
OCR_FILES = ["det.onnx", "rec.onnx", "keys.txt"]

# ADB 端培养舱「持有量判定」ROI 覆盖的修复值（上游 #6100 / d4ea8745，2026-09-29）。
# 见 patch_adb_growth_chamber_status_rois()：只覆盖 ADB 覆盖层，base resource 不动。
ADB_GROWTH_CHAMBER_ROI_FIX = {
    "GrowthChamberCheckSeedNotEmpty": [40, 52, 0, 0],
    "GrowthChamberCheckPlantNotEmpty": [-85, 57, 29, 0],
}
ADB_GROWTH_CHAMBER_STATUS_FILE = (
    ASSETS_ROOT / "resource_adb" / "pipeline" / "DijiangRewards" / "Template" / "Status.json"
)


def log(msg: str):
    print(f"[MAAend-Prep] {msg}", flush=True)


def ensure_maaend_submodule():
    if not (ASSETS_ROOT / "interface.json").is_file():
        log("MAAend submodule not detected, initializing...")
        import subprocess
        subprocess.run(
            ["git", "submodule", "update", "--init", "--recursive", "upstream/maaend"],
            cwd=PROJECT_ROOT,
            check=True,
        )
    log(f"MAAend found at {MAAEND_ROOT}")


def ensure_ocr_models():
    OCR_TARGET_DIR.mkdir(parents=True, exist_ok=True)
    all_exist = all((OCR_TARGET_DIR / f).is_file() for f in OCR_FILES)
    if all_exist:
        log("OCR models already present.")
        return

    # Try local submodule first
    local_ocr = MAAEND_ROOT / "MaaCommonAssets" / "OCR" / "ppocr_v6" / "small"
    if local_ocr.exists() and all((local_ocr / f).is_file() for f in OCR_FILES):
        log(f"Copying OCR models from local submodule: {local_ocr}")
        for f in OCR_FILES:
            shutil.copy2(local_ocr / f, OCR_TARGET_DIR / f)
        log("OCR models copied successfully.")
        return

    # Download from MaaCommonAssets raw
    log("Downloading OCR models from MaaCommonAssets...")
    for f in OCR_FILES:
        dest = OCR_TARGET_DIR / f
        if dest.is_file() and dest.stat().st_size > 0:
            log(f"  {f} already downloaded.")
            continue
        url = f"{OCR_BASE_URL}/{f}"
        log(f"  Downloading {f} from {url}...")
        req = urllib.request.Request(url, headers={"User-Agent": "MAAend-Android-Prep"})
        with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
            shutil.copyfileobj(resp, out)
        log(f"  Saved {f} ({dest.stat().st_size / (1024 * 1024):.2f} MB)")
    log("All OCR models prepared.")


def ensure_icon():
    dest_png = PROJECT_ROOT / "logo.png"
    src_png = ASSETS_ROOT / "locales" / "MaaEnd-Tiny.png"
    if src_png.is_file():
        shutil.copy2(src_png, dest_png)
        log(f"Copied app icon from {src_png.relative_to(PROJECT_ROOT)} to logo.png")
    elif dest_png.is_file():
        log("App icon logo.png already exists.")
    else:
        log("Warning: logo.png not found, default app icon will be used.")


def ensure_local_properties():
    props_file = PROJECT_ROOT / "local.properties"
    content = [
        "# Auto-generated configuration for MAAend Android packaging",
        "pi.profile=pi-profile.yaml",
        "build.debugAbi=arm64-v8a",
        "build.releaseAbi=arm64-v8a",
        "",
    ]
    if not props_file.is_file():
        props_file.write_text("\n".join(content), encoding="utf-8")
        log("Created local.properties pointing to pi-profile.yaml")
    else:
        existing = props_file.read_text(encoding="utf-8")
        if "pi.profile=" not in existing:
            props_file.write_text(existing.rstrip() + "\npi.profile=pi-profile.yaml\n", encoding="utf-8")
            log("Added pi.profile to local.properties")


def customize_maaend_metadata():
    """
    定制 MAAend 元数据：
    1. 生成 CONTACT 仅保留邮箱 qiisme1021@icloud.com
    2. interface.json 仓库地址换为本项目 qi-1021/MAAend-Meow，描述设置为“这是对于MAAend手机端的一种实现”
    3. 清理不需要或移动端不适用的声明（如 Win32 专属 pretask GameSetting）
    """
    if not ASSETS_ROOT.exists():
        return
    
    # 1. CONTACT 文件
    contact_file = ASSETS_ROOT / "CONTACT"
    contact_content = "| 联系方式 | 地址 |\n| :---: | :---: |\n| 邮箱 | [qiisme1021@icloud.com](mailto:qiisme1021@icloud.com) |\n"
    try:
        contact_file.write_text(contact_content, encoding="utf-8")
        log(f"Customized {contact_file.relative_to(PROJECT_ROOT)}")
    except Exception as e:
        log(f"Warning: could not write CONTACT: {e}")

    # 2. LICENSE 文件兜底
    license_file = ASSETS_ROOT / "LICENSE"
    if not license_file.is_file() and (PROJECT_ROOT / "LICENSE").is_file():
        shutil.copy2(PROJECT_ROOT / "LICENSE", license_file)

    # 3. interface.json 元数据定制
    interface_file = ASSETS_ROOT / "interface.json"
    if interface_file.is_file():
        try:
            content = interface_file.read_text(encoding="utf-8")
            data = json.loads(strip_json_comments(content))

            data["github"] = "https://github.com/qi-1021/MAAend-Meow"
            data["description"] = "这是对于MAAend手机端的一种实现，基于 MaaFramework 与 MaaEnd 开源项目。"
            
            # 确保 contact 指向 CONTACT
            data["contact"] = "CONTACT"

            # 过滤仅适用于 Win32/PC 的特定 import（如 GameSetting.json 会要求前台窗口分辨率）
            if "import" in data and isinstance(data["import"], list):
                data["import"] = [
                    item for item in data["import"]
                    if item not in [
                        "tasks/pretasks/GameSetting.json",
                        "tasks/CloseGamePC.json",
                        # Keymap 是键盘快捷键（hotkey）配置：PiParser 在 Android 端
                        # 明确不支持该类型（会报 error 并丢弃 option），而它的
                        # global_option 引用因此悬空，再报一条 error。手机上本来也没有
                        # 键盘，字段留着只会让加载产生 Error 诊断。
                        "tasks/setting/Keymap.json",
                    ]
                ]

            # 暂时移除 agent 节点声明，因为纯 Pipeline 任务无需外挂 agent 即可在移动端原生执行
            if "agent" in data:
                del data["agent"]

            interface_file.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
            log(f"Customized metadata in {interface_file.relative_to(PROJECT_ROOT)}")
        except Exception as e:
            log(f"Warning: could not customize interface.json: {e}")


def fix_task_controllers():
    """
    为所有任务及选项自动补齐 ADB 控制器声明，消除移动端‘不支持当前控制器’警告。
    """
    tasks_dir = ASSETS_ROOT / "tasks"
    if not tasks_dir.exists():
        return
    count = 0
    for p in tasks_dir.rglob("*.json"):
        if "CloseGamePC" in p.name or "GameSetting" in p.name:
            continue
        try:
            content = p.read_text(encoding="utf-8")
            data = json.loads(strip_json_comments(content))
            modified = False

            # 处理 task 节点
            if "task" in data and isinstance(data["task"], list):
                for t in data["task"]:
                    if isinstance(t, dict) and "controller" in t and isinstance(t["controller"], list):
                        ctrls = t["controller"]
                        if not any("adb" in c.lower() for c in ctrls):
                            ctrls.extend(["ADB", "CloudADB"])
                            modified = True

            # 处理 option 节点
            if "option" in data and isinstance(data["option"], dict):
                for opt_name, opt in data["option"].items():
                    if isinstance(opt, dict) and "controller" in opt and isinstance(opt["controller"], list):
                        ctrls = opt["controller"]
                        if not any("adb" in c.lower() for c in ctrls):
                            ctrls.extend(["ADB", "CloudADB"])
                            modified = True

            if modified:
                p.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
                count += 1
        except Exception as e:
            log(f"Warning: failed to fix controllers in {p.name}: {e}")
    log(f"Fixed ADB controller declarations in {count} task files.")


def enhance_presets_with_startup():
    """
    为预设（DailyFull, QuickDaily）自动在首位注入启动终末地（AndroidOpenGame），实现一键全自动拉起游戏。
    """
    preset_files = [
        ASSETS_ROOT / "tasks" / "preset" / "DailyFull.json",
        ASSETS_ROOT / "tasks" / "preset" / "QuickDaily.json",
    ]
    for pf in preset_files:
        if not pf.is_file():
            continue
        try:
            data = json.loads(pf.read_text(encoding="utf-8"))
            presets = data.get("preset", [])
            for pr in presets:
                tasks = pr.get("task", [])
                if not tasks or tasks[0].get("name") != "AndroidOpenGame":
                    tasks.insert(0, {
                        "name": "AndroidOpenGame",
                        "option": {
                            "ClientVersion": "CN"
                        }
                    })
                    log(f"Inserted AndroidOpenGame as first step into {pf.name} preset.")
            pf.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
        except Exception as e:
            log(f"Warning: failed to enhance preset {pf.name}: {e}")


def enhance_opengame_pipeline():
    """
    增强 OpenGame pipeline：
    1. 在 OpenGame.next 首位注入 AlreadyInGame（主界面/游戏中识别），若游戏已启动则 0.1s 迅速完成，避免卡死。
    2. 优化 StuckRepairAction，去除对移动端不存在的 SubTask Agent 依赖。
    """
    opengame_file = ASSETS_ROOT / "resource" / "pipeline" / "OpenGame.json"
    if not opengame_file.is_file():
        return
    try:
        content = opengame_file.read_text(encoding="utf-8")
        data = json.loads(strip_json_comments(content))

        # 1. 注入 AlreadyInGame 节点
        data["AlreadyInGame"] = {
            "desc": "游戏已在主界面/游戏中",
            "recognition": {
                "type": "TemplateMatch",
                "param": {
                    "template": [
                        "SceneManager/WorldMenu.png",
                        "SceneManager/ControlNexus.png",
                        "SceneManager/ControlNexusWithTips.png",
                        "SceneManager/Backpack.png",
                        "SceneManager/TaskIcon.png",
                        "SceneManager/TaskIcon2.png",
                        "SceneManager/MapOverviewEnter.png"
                    ]
                }
            }
        }

        # 2. 将 AlreadyInGame 置于 OpenGame 的 next 列表首位
        if "OpenGame" in data and isinstance(data["OpenGame"], dict):
            next_list = data["OpenGame"].get("next", [])
            if "AlreadyInGame" not in next_list:
                next_list.insert(0, "AlreadyInGame")
                data["OpenGame"]["next"] = next_list

        # 3. 修复 StuckRepairAction 去除 SubTask
        if "StuckRepairAction" in data and isinstance(data["StuckRepairAction"], dict):
            data["StuckRepairAction"] = {
                "desc": "尝试修复卡死",
                "next": [
                    "ResetStartUpGame",
                    "StartUpGameFailed"
                ],
                "focus": {
                    "Node.Action.Starting": "$task.OpenGame.focus.stuck_repair"
                }
            }

        opengame_file.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
        log("Enhanced OpenGame pipeline with AlreadyInGame and agent-free repair.")
    except Exception as e:
        log(f"Warning: failed to enhance OpenGame pipeline: {e}")


def tag_unimplemented_tasks():
    """
    为移动端暂未实现/部分实现的功能打上可见标签，避免用户误用。
    这些需要桌面端 agent（3D 导航/浏览器）或完整策略移植，后续版本实现。
    只改 label/description 显示文本，不影响流水线逻辑。
    """
    marks = {
        # 操控类（采集/送货/协议空间）基础动作已在移动端原生实现（MotionSupport）:
        # 虚拟摇杆/触摸转向/环绕搜索/动作按钮/送货目录解析均已移植；NAVMESH 精确寻路
        # 仍为降级近似，完整地图导航待 MapLocator 移植。
        "AutoCollect": "🧺自动采集【移动端基础版：传送+直走+交互，复杂寻路持续完善】",
        # 滑索导入：桌面端浏览器 MITM 抓取，手机端请在电脑导一次后同步数据
        "ZiplineImport": "🚡导入/更新滑索坐标【暂未实现·需桌面端，后续版本实现】",
        # 囤货策略：认货/绑价/选货/点货/校正/购买链已在移动端跑通（见 AutoStockpileSupport），
        # 但没有上游那套配额与阈值配置，选品规则退化为「本页可见货组里挑最便宜的」
        "AutoStockpile": "📦自动囤货【移动端基础版：OCR 认货选最低价，无配额阈值策略】",
        "AutoStockStaple": "🏪购买稳定物资【策略完善中】",
        # 抢委托送货：上游目标扫描的 4 个自定义组件（seizedeliveryjobs/register.go）
        # 全未移植，且依赖 `MapFind`（C++ WorldMap，未移植）→ 选中后跑不起来。
        # 与其静默空转，不如在名字上直接说清楚。
        "SeizeDeliveryJobs": "🏍️抢委托送货【暂不可用·移动端缺少目标扫描与地图搜索】",
        # 选剑演武：上游 4 个识别（Recognize/RecognizeDeck/RecognizeAband/Decide）
        # 全是求解器实现，移动端未移植 → 任务会空转。
        "TrialOfSwordmancy": "🗡️选剑演武【暂不可用·移动端未实现求解】",
        # 转交委托：送货目录/装箱/回收站链已在移动端跑通；缺的是
        # `MapFind`（自动寻图，C++ WorldMap 未移植）与 `IconRecognition`
        # （优先装箱货物，C++ 图标识别 9705 行未移植）→ 这两项配置暂不生效。
        "DeliveryJobs": "🚚转交委托【移动端基础版：送货/装箱/回收站可用；自动寻图与优先装箱待实现】",
        # 环境监测：相机扫描（CameraScanAction）与失败收集器已实现；路线里仍依赖
        # `MapLocateAssertLocation`（C++ MapLocator 未移植）→ 路线定位是降级近似。
        "EnvironmentMonitoring": "🌿环境监测【移动端基础版：相机扫描已实现；路线定位待 MapLocator 移植】",
        # ---- 以下 5 个在移动端的可见分组里（贵重品库/大世界/理智消耗），但整条链都跑不起来 ----
        # 基质筛选：上游 essencefilter 的 9 个组件全未移植 → 选中后空转。
        "EssenceFilter": "🔒基质筛选锁定【暂不可用·移动端未实现基质筛选】",
        # 基质刷取：依赖基质筛选链（EssenceInventory 由它产出），且自身 2 个 action 未注册
        # （未注册=硬失败，比 noop 更糟）。
        "AutoEssence": "🎱基质刷取【暂不可用·依赖基质筛选链，移动端未实现】",
        # 浮空回收：上游 aerosalvage 的网格检测/拖拽规划组件全为 noop。
        "AeroSalvage": "🎈浮空回收【暂不可用·移动端未实现】",
        # 一键导入蓝图：上游 blueprintimport 的 3 个组件全为 noop。
        "ImportBluePrints": "📐一键导入蓝图【暂不可用·移动端未实现】",
        # 解拼图：上游 puzzle-solver 的识别与求解全为 noop。
        "PuzzleSolver": "🧩解拼图【暂不可用·移动端未实现求解】",
        # 删除共享滑索：删除动作本身可用；自动寻图依赖 `MapFind`（C++ WorldMap 未移植）。
        "SharedZiplineDelete": "✂️删除共享滑索【移动端基础版：删除可用；自动寻图待 MapFind】",
    }
    tasks_dir = ASSETS_ROOT / "tasks"
    if not tasks_dir.exists():
        return
    count = 0
    for p in tasks_dir.rglob("*.json"):
        try:
            content = p.read_text(encoding="utf-8")
            data = json.loads(strip_json_comments(content))
            modified = False
            if "task" in data and isinstance(data["task"], list):
                for t in data["task"]:
                    if not isinstance(t, dict):
                        continue
                    name = t.get("name")
                    if name in marks:
                        # 注意：label 是字面量，description 是 `$task.X.description` 引用。
                        # i18n 引用**整串就是 key**（见 PiText / CurrentProjectI18nTest），
                        # 所以在 description 后面拼中文后缀会让它解析不出来（运行时会退化成
                        # 原文，且每种语言都报缺 key）。移动端说明一律放 label。
                        note = "【移动端暂未完全实现，后续版本补齐，敬请期待】"
                        label = marks[name]
                        if note not in label:
                            label = f"{label}{note}"
                        t["label"] = label
                        modified = True
            if modified:
                p.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
                count += 1
        except Exception as e:
            log(f"Warning: failed to tag unimplemented task in {p.name}: {e}")
    log(f"Tagged unimplemented tasks in {count} task files.")


def override_rigid_template_nodes():
    """
    把脆弱小模板的页签切换换成按位置点击（移动端分辨率下模板易失配）。

    为什么不用 OCR 找「弹性需求物资」这五个字：真机实测这一行 OCR 是
    `艳定需求物资提性需深情物 03`——稳→艳、弹→提、求→深，还把两个页签并成一句、
    重复三份。720p 下这个字号这套字体认不准，模糊匹配也救不回来。
    页签位置是固定 UI，直接按坐标点更可靠。
    """
    # 「弹性需求物资」页签的范围（1280×720，取自真机截图）。
    # 左边那个「稳定需求物资」是白底约 x∈[75,435]，弹性页签深色底 x∈[440,800]。
    elastic_tab_roi = [445, 80, 350, 66]
    entry = ASSETS_ROOT / "resource" / "pipeline" / "AutoStockpile" / "Entry.json"
    if not entry.is_file():
        return
    try:
        data = json.loads(strip_json_comments(entry.read_text(encoding="utf-8")))
        modified = False
        # 切到弹性页签：DirectHit 直接命中页签矩形，自带的 Click 点它的中心。
        # 进购买页默认是稳定物资，这一步是必须的。
        node = data.get("AutoStockpileGotoElasticGoods")
        if isinstance(node, dict):
            node["recognition"] = "DirectHit"
            node["roi"] = elastic_tab_roi
            node["action"] = {"type": "Click", "param": {}}
            modified = True
        # 这里不再校验页签：真正的校验是决策节点的货物识别。
        # 货组名（…货组）只出现在弹性页签上，点歪了就在决策里认不出货、
        # 按 Skip 收尾，不会拿着稳定物资当弹性货去买。
        node = data.get("AutoStockpileEnaureElasticClicked")
        if isinstance(node, dict):
            node["recognition"] = "DirectHit"
            node["roi"] = elastic_tab_roi
            node["action"] = {"type": "DoNothing", "param": {}}
            modified = True
        if modified:
            entry.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
            log("Overrode AutoStockpile elastic-tab nodes with a positional click.")
    except Exception as e:
        log(f"Warning: failed to override rigid template nodes: {e}")


def apply_mobile_resilience_patches():
    """
    移动端鲁棒性补丁：
    1. AutoDelivery 任务缺失节点：从 FalseAction 失败改为 DoNothing 正常收尾。
       任务列表被剧情任务（如钟鸣人归）占位时属正常游戏状态，不该整任务失败。
    2. ProtocolSpace 索引页签切换：加 on_error 单次重试（转场/坏帧导致 OCR 落空时兜底）。
    """
    common = ASSETS_ROOT / "resource" / "pipeline" / "AutoDelivery" / "Common.json"
    if common.is_file():
        try:
            data = json.loads(strip_json_comments(common.read_text(encoding="utf-8")))
            node = data.get("AutoDeliveryDeliveryMissionNotFound")
            if isinstance(node, dict):
                node["desc"] = "任务列表已经滑到底部但仍未找到送货任务：视为本轮无可送任务，正常收尾而非整任务失败"
                node["action"] = "DoNothing"
                node.pop("custom_action", None)
                node["focus"] = {
                    "Node.Recognition.Succeeded": "未找到可接取的送货任务（可能被剧情任务占用），跳过"
                }
                common.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
                log("Patched AutoDeliveryDeliveryMissionNotFound to graceful skip.")
        except Exception as e:
            log(f"Warning: failed to patch AutoDelivery mission-not-found: {e}")

    manual = ASSETS_ROOT / "resource" / "pipeline" / "ProtocolSpace" / "OperationalManual.json"
    if manual.is_file():
        try:
            data = json.loads(strip_json_comments(manual.read_text(encoding="utf-8")))
            node = data.get("ProtocolSpaceOperationalManualSwitchIndexTab")
            retry_name = "ProtocolSpaceOperationalManualSwitchIndexTabRetry"
            if isinstance(node, dict) and retry_name not in data:
                node["on_error"] = [retry_name]
                data[retry_name] = {
                    "desc": "索引页签切换失败后的单次重试（多为转场坏帧/遮挡；max_hit 防死循环）",
                    "recognition": "DirectHit",
                    "max_hit": 1,
                    "pre_delay": 0,
                    "action": "DoNothing",
                    "post_delay": 1500,
                    "rate_limit": 0,
                    "next": ["ProtocolSpaceOperationalManualSwitchIndexTab"],
                }
                manual.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
                log("Patched ProtocolSpace index-tab switch with on_error retry.")
        except Exception as e:
            log(f"Warning: failed to patch ProtocolSpace index-tab retry: {e}")


def neutralize_touch_move_nodes():
    """
    把 `TouchMove`（鼠标移动）节点中和成 `DoNothing`——Android 没有「悬停光标」。

    真机实测（2026-09-29）：据点交易走到 `BetterSlidingMoveMouse` 时失败，
    框架日志给出的是
        action="TouchMove", point=[1258,696], contact=0, pressure=0 → completed=false
    而该节点的 desc 是「避免遮挡 Increase/Decrease Button」：
    **PC 上鼠标会停在 +/- 按钮上挡住点击，所以先把光标移开**。
    Android 的点击不留下光标，这个动作纯属 PC 语义——但它的失败会**打断整条链**
    （据点交易因此卡在"精确设置数量"这一步）。

    做法：`action: TouchMove` → `DoNothing`，去掉 `target`（对 DoNothing 无意义），
    `next` 与其它字段原样保留。全上游共 5~6 处（BetterSliding / Interface /
    IMS / AutoDelivery / AutoEcoFarm），一次全中和。
    """
    pipeline_root = ASSETS_ROOT / "resource" / "pipeline"
    if not pipeline_root.is_dir():
        return
    patched_files = 0
    for p in pipeline_root.rglob("*.json"):
        try:
            content = p.read_text(encoding="utf-8")
            if "TouchMove" not in content:
                continue
            data = json.loads(strip_json_comments(content))
            if not isinstance(data, dict):
                continue
            changed = False
            for name, node in data.items():
                if not isinstance(node, dict):
                    continue
                act = node.get("action")
                is_touch_move = act == "TouchMove" or (
                    isinstance(act, dict) and act.get("type") == "TouchMove"
                )
                if not is_touch_move:
                    continue
                node["action"] = "DoNothing"
                node.pop("target", None)
                node["desc"] = (node.get("desc") or "") + "｜移动端中和：Android 无悬停光标，TouchMove 必然失败且会打断流水线"
                changed = True
                log(f"Neutralized TouchMove node '{name}' in {p.name}")
            if changed:
                p.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
                patched_files += 1
        except Exception as e:
            log(f"Warning: failed to neutralize TouchMove in {p.name}: {e}")
    log(f"Neutralized TouchMove nodes in {patched_files} pipeline files.")


def apply_outpost_trading_arbitrage():
    """
    低买高卖（自动套利）：给据点交易加 Arbitrage 选品策略档。
    买侧由 OutpostTradingConfigureSelectionStrategy(strategy=price) 驱动，
    Android 侧 MaaRunner 回调按各据点基础单价升序重排候选（见 OutpostData.unitPriceByLocation）。
    """
    tasks_file = ASSETS_ROOT / "tasks" / "OutpostTrading.json"
    if not tasks_file.is_file():
        return
    try:
        data = json.loads(strip_json_comments(tasks_file.read_text(encoding="utf-8")))
        modified = False
        strategy_cases = data.get("option", {}).get("SellProductSelectionStrategy", {}).get("cases")
        if isinstance(strategy_cases, list) and all(c.get("name") != "Arbitrage" for c in strategy_cases if isinstance(c, dict)):
            strategy_cases.append({
                "name": "Arbitrage",
                "label": "$task.OutpostTrading.SelectionStrategyArbitrage",
                "description": "$task.OutpostTrading.SelectionStrategyArbitrageDescription",
                "pipeline_override": {
                    "OutpostTradingConfigureSelectionStrategy": {
                        "custom_action_param": {
                            "operation": "configure_strategy",
                            "strategy": "price"
                        }
                    }
                },
            })
            modified = True
        if modified:
            tasks_file.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
            log("Added OutpostTrading Arbitrage selection strategy.")
    except Exception as e:
        log(f"Warning: failed to add arbitrage strategy: {e}")

    # i18n 标签（缺哪个语言补哪个，不覆盖上游已有词条）
    labels = {
        "zh_cn": ("低买高卖（自动套利）", "买相对价格最低的货，卖相对价格最高的货：按各据点基础单价排序选品，低买高卖赚跨据点价差"),
        "zh_tw": ("低買高賣（自動套利）", "買相對價格最低的貨，賣相對價格最高的貨：按各據點基礎單價排序選品，低買高賣賺跨據點價差"),
        "en_us": ("Buy Low, Sell High (Auto Arbitrage)", "Buy the relatively cheapest goods, sell the relatively priciest: ranks candidates by base unit price per outpost to profit from cross-outpost spreads"),
        "ja_jp": ("安く買って高く売る（自動アービトラージ）", "相対的に最も安い品を買い、最も高い品を売る：拠点ごとの基本単価で候補を並べ、拠点間の価格差で利益を得る"),
        "ko_kr": ("싸게 사서 비싸게 팔기 (자동 아비트라지)", "상대적으로 가장 싼 물건을 사서 가장 비싼 물건을 팝니다: 거점별 기본 단가로 후보를 정렬해 거점 간 가격 차이로 수익을 냅니다"),
    }
    for lang, (label, desc) in labels.items():
        loc = ASSETS_ROOT / "locales" / "interface" / f"{lang}.json"
        if not loc.is_file():
            continue
        try:
            ldata = json.loads(loc.read_text(encoding="utf-8"))
            changed = False
            for key, value in (
                ("task.OutpostTrading.SelectionStrategyArbitrage", label),
                ("task.OutpostTrading.SelectionStrategyArbitrageDescription", desc),
            ):
                if key not in ldata:
                    ldata[key] = value
                    changed = True
            if changed:
                loc.write_text(json.dumps(ldata, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
        except Exception as e:
            log(f"Warning: failed to add arbitrage labels for {lang}: {e}")


def patch_missing_upstream_i18n_keys():
    """
    补上游漏声明的 i18n key。

    PI 引用了 `$task.BatchUseDetector.option.Times.input.error`（Times 输入框的校验
    提示），但上游五种语言文件里都没有它——运行时会把 key 原文显示给用户。
    已在 MaaEnd 仓库确认这是**上游遗漏**，不是我们同步滞后。

    在这里补而不是改 submodule 里的语言文件：那些文件随上游同步整体覆盖，直接改会被冲掉。
    """
    missing = {
        "task.BatchUseDetector.option.Times.input.error": {
            "zh_cn": "请输入有效数字",
            "zh_tw": "請輸入有效數字",
            "en_us": "Enter a valid number",
            "ja_jp": "有効な数値を入力してください",
            "ko_kr": "유효한 숫자를 입력하세요",
        },
    }
    locales_dir = ASSETS_ROOT / "locales" / "interface"
    if not locales_dir.is_dir():
        return
    patched = 0
    for lang in ("zh_cn", "zh_tw", "en_us", "ja_jp", "ko_kr"):
        path = locales_dir / f"{lang}.json"
        if not path.is_file():
            continue
        try:
            data = json.loads(strip_json_comments(path.read_text(encoding="utf-8")))
        except Exception as e:
            log(f"Warning: failed to read {path.name}: {e}")
            continue
        modified = False
        for key, by_lang in missing.items():
            text = by_lang.get(lang)
            if not text or key in data:
                continue
            data[key] = text
            modified = True
        if modified:
            path.write_text(
                json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8"
            )
            patched += 1
    if patched:
        log(f"Patched {patched} locale file(s) with upstream-missing i18n keys.")


def patch_adb_growth_chamber_status_rois():
    """
    修复 ①：ADB 端培养舱「持有量判定」的 ROI 覆盖写错。

    上游 #6100（d4ea8745，2026-09-29）修正了
    `resource_adb/.../DijiangRewards/Template/Status.json` 里两个检查节点的 roi_offset：
        种子 GrowthChamberCheckSeedNotEmpty : [43, 54, 9, 1]    -> [40, 52, 0, 0]
        本体 GrowthChamberCheckPlantNotEmpty: [-84, 57, 31, 0]  -> [-85, 57, 29, 0]
    框偏了会把「0 数量」判成「有」，于是挨个点遍候选、还把列表滚 30 次扫全表——
    真机表现就是「培养稀有植物把所有材料试一遍」。本项目 submodule 停在修复前，
    构建期按上游修复值覆盖（**只覆盖 ADB 覆盖层，base resource 的 Status.json 不动**）。

    节点缺失（上游改名/移除）时直接抛错，而不是静默跳过：否则补丁会失效而无人发现。
    """
    if not ADB_GROWTH_CHAMBER_STATUS_FILE.is_file():
        raise RuntimeError(
            f"ADB Status.json 缺失：{ADB_GROWTH_CHAMBER_STATUS_FILE}；无法应用培养舱 ROI 修复"
        )
    data = json.loads(strip_json_comments(ADB_GROWTH_CHAMBER_STATUS_FILE.read_text(encoding="utf-8")))
    for node_name, roi in ADB_GROWTH_CHAMBER_ROI_FIX.items():
        node = data.get(node_name)
        if not isinstance(node, dict):
            raise RuntimeError(
                f"ADB Status.json 缺少节点 {node_name}（上游可能已改名/移除）；"
                f"培养舱 ROI 修复无法应用，请同步更新 scripts/prepare_maaend.py"
            )
        node["roi_offset"] = list(roi)
    ADB_GROWTH_CHAMBER_STATUS_FILE.write_text(
        json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    log("Patched ADB GrowthChamber check ROIs with upstream #6100 values.")


def verify_adb_growth_chamber_status_rois():
    """
    构建期断言：产物里 ADB 培养舱持有量 ROI 必须等于 #6100 修复值。

    为什么单独断言：补丁按节点名写入，若将来同步上游时节点被改名/移除，
    或后续步骤把文件覆盖回旧值，补丁会「静默失效」——照样编得过、照样出包，
    只是行为悄悄变回去。这里读回**最终产物**核对，不一致就让构建失败。
    """
    if not ADB_GROWTH_CHAMBER_STATUS_FILE.is_file():
        raise RuntimeError(f"构建期断言失败：产物缺少 {ADB_GROWTH_CHAMBER_STATUS_FILE}")
    data = json.loads(strip_json_comments(ADB_GROWTH_CHAMBER_STATUS_FILE.read_text(encoding="utf-8")))
    problems = []
    for node_name, expected in ADB_GROWTH_CHAMBER_ROI_FIX.items():
        actual = (data.get(node_name) or {}).get("roi_offset")
        if actual != expected:
            problems.append(f"{node_name}: 期望 {expected}，实际 {actual}")
    if problems:
        raise RuntimeError(
            "构建期断言失败：ADB 培养舱持有量 ROI 不等于上游 #6100 修复值；"
            "上游同步可能覆盖/改名，请检查 scripts/prepare_maaend.py：\n  "
            + "\n  ".join(problems)
        )
    for node_name, expected in ADB_GROWTH_CHAMBER_ROI_FIX.items():
        log(f"Asserted ADB {node_name} roi_offset == {expected} (upstream #6100).")


def _extend_ocr_expected(node: dict, extra: list) -> bool:
    """向节点的 OCR expected 追加不重复的候选文案；返回是否改动。"""
    param = (node.get("recognition") or {}).get("param") or {}
    expected = param.get("expected")
    if not isinstance(expected, list):
        return False
    changed = False
    for item in extra:
        if item not in expected:
            expected.append(item)
            changed = True
    return changed


def patch_growth_chamber_extract_resilience():
    """
    修复 ②：培养舱「提取基核」链任一分支断掉，整任务就报红。

    默认 AutoExtractSeed=Yes 时，「点培养确认 -> 前往提取基核 -> 确认提取 -> 关闭提取页」
    是必经分支，而提取页三条出路覆盖不全；文案/时序一变就断链，断链后外层一路失败
    -> 整任务红，即使种子其实已经种下去了。

    保守做法（不砍功能），全部收敛到既有的安全节点 GrowthChamberGrowBack
    （识别返回键并反复点，直到回到培养选择界面）：
      1. GrowthChamberSeedExtractConfirm 加 on_error：确认已点、但结果页/关闭按钮没出现时兜底。
      2. GrowthChamberSeedExtract 加 on_error：确认提取/原料不足/返回三条出路都没命中时兜底。
      3. ExtractSeedCloseText.expected 扩通用「获得」类文案（参考 CloseRewardsButtonText）：
         提取结算标题与普通奖励结算共用同一标题区，文案微调也能认出关闭按钮。
      4. GrowthChamberNoMaterials.expected 扩原料不足类文案。
    """
    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    tmpl_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "Template" / "TextTemplate.json"
    for path in (growth_file, tmpl_file):
        if not path.is_file():
            raise RuntimeError(f"培养舱提取链修复失败：缺少 {path}")

    safe_node = "GrowthChamberGrowBack"

    # 1/2) on_error 兜底
    growth = json.loads(strip_json_comments(growth_file.read_text(encoding="utf-8")))
    for node_name in ("GrowthChamberSeedExtractConfirm", "GrowthChamberSeedExtract"):
        node = growth.get(node_name)
        if not isinstance(node, dict):
            raise RuntimeError(
                f"GrowthChamber.json 缺少节点 {node_name}（上游可能已改名/移除）；"
                f"提取链兜底无法应用，请同步更新 scripts/prepare_maaend.py"
            )
        node["on_error"] = [safe_node]

    # 4) NoMaterials 文案扩展
    no_materials = growth.get("GrowthChamberNoMaterials")
    if not isinstance(no_materials, dict):
        raise RuntimeError("GrowthChamber.json 缺少节点 GrowthChamberNoMaterials")
    _extend_ocr_expected(no_materials, [
        "原料不足",
        "材料不足",
        "素材不足",
        "缺少材料",
        "無法獲取",
        "无法提取",
        "無法取得",
        "(?i)Not\\s*enough",
        "(?i)Insufficient",
    ])
    growth_file.write_text(json.dumps(growth, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")

    # 3) 提取结算关闭文案扩展
    tmpl = json.loads(strip_json_comments(tmpl_file.read_text(encoding="utf-8")))
    close_text = tmpl.get("ExtractSeedCloseText")
    if not isinstance(close_text, dict):
        raise RuntimeError("TextTemplate.json 缺少节点 ExtractSeedCloseText")
    _extend_ocr_expected(close_text, [
        "获得",
        "獲得",
        "(?i)Rewards?\\s*Acquired",
        "報酬一覧",
    ])
    tmpl_file.write_text(json.dumps(tmpl, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")

    log("Patched GrowthChamber extraction chain with on_error fallbacks and wider OCR text.")


def main():
    log("Starting MAAend Android preparation...")
    ensure_maaend_submodule()
    ensure_ocr_models()
    ensure_icon()
    ensure_local_properties()
    customize_maaend_metadata()
    fix_task_controllers()
    enhance_presets_with_startup()
    enhance_opengame_pipeline()
    tag_unimplemented_tasks()
    patch_missing_upstream_i18n_keys()
    override_rigid_template_nodes()
    apply_mobile_resilience_patches()
    neutralize_touch_move_nodes()
    apply_outpost_trading_arbitrage()
    patch_adb_growth_chamber_status_rois()
    patch_growth_chamber_extract_resilience()
    # 断言放最后：读回最终产物，确认没有后续步骤把 ROI 覆盖回去
    verify_adb_growth_chamber_status_rois()
    log("Preparation complete!")


if __name__ == "__main__":
    main()

