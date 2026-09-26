#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Prepare MAAend assets, OCR models, and configuration for Android packaging.
Ensures zero pollution of system /tmp (all caches/temps in project root).
"""

import json
import os
import shutil
import sys
import urllib.request
from pathlib import Path

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
            # 兼容带有 JSONC 注释的读取
            lines = interface_file.read_text(encoding="utf-8").splitlines()
            cleaned_lines = []
            for line in lines:
                stripped = line.strip()
                if stripped.startswith("//"):
                    continue
                cleaned_lines.append(line)
            data = json.loads("\n".join(cleaned_lines))

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


def main():
    log("Starting MAAend Android preparation...")
    ensure_maaend_submodule()
    ensure_ocr_models()
    ensure_icon()
    ensure_local_properties()
    customize_maaend_metadata()
    log("Preparation complete!")


if __name__ == "__main__":
    main()
