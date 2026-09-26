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
                    if item not in ["tasks/pretasks/GameSetting.json", "tasks/CloseGamePC.json"]
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
        # 自动采集：依赖 C++ 3D 地图导航 agent，手机端暂无
        "AutoCollect": "🧺自动采集【暂未实现·需3D导航，后续版本实现】",
        # 滑索导入：桌面端浏览器 MITM 抓取，手机端请在电脑导一次后同步数据
        "ZiplineImport": "🚡导入/更新滑索坐标【暂未实现·需桌面端，后续版本实现】",
        # 囤货策略：完整选品/配额策略移植中，当前仅基础流程
        "AutoStockpile": "📦自动囤货【策略完善中，部分物资暂不支持】",
        "AutoStockStaple": "🏪购买稳定物资【策略完善中】",
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
                        t["label"] = marks[name]
                        desc = t.get("description", "")
                        note = "【移动端暂未完全实现，后续版本补齐，敬请期待】"
                        if isinstance(desc, str) and note not in desc:
                            t["description"] = f"{desc} {note}" if desc else note
                        modified = True
            if modified:
                p.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
                count += 1
        except Exception as e:
            log(f"Warning: failed to tag unimplemented task in {p.name}: {e}")
    log(f"Tagged unimplemented tasks in {count} task files.")


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
    log("Preparation complete!")


if __name__ == "__main__":
    main()

