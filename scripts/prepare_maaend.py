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

# ============================================================================
# 上游同步修复（submodule 停在 fcdc53a7，缺以下 4 个与本轮真机 bug 直接相关的
# 提交；构建期在补丁层逐条落值，不动 submodule 指针、不在子模块里提交）。
#
# 证据：gh api repos/MaaEnd/MaaEnd/commits/<sha> --jq '.files[] | {filename, patch}'
# ============================================================================

# 489ff2fe (#6076 偶现基建任务点击使用助力失效)：同类确认对话框改用图标 box_index。
# 1 -> 0（Manufacturing 是补上原本缺失的 box_index）。
UPSTREAM_6076_BOX_INDEX_FIX = [
    ("resource/pipeline/DijiangRewards/GrowthChamber.json", "GrowthChamberGrowAgainConfirm"),
    ("resource/pipeline/DijiangRewards/Manufacturing.json", "MFGCabinAssistConfirm"),
    ("resource/pipeline/DijiangRewards/RecoveryEmotion.json", "RecoveryEmotionConfirm"),
    ("resource/pipeline/GiftOperator/GiftOperatorGiftFlow.json", "GiftOperatorConfirmDialog"),
]
UPSTREAM_6076_BOX_INDEX = 0

# d4ea8745 (#6100 修复 ADB 端基建奖励任务失败)：base resource 侧。
# 注：任务简报把宽 120->180 记在 GrowthChamberCheckSeedNotEmpty 名下，但上游 diff 的
#     实际归属是 ClueItem（第 238 行 roi 的第 3 个数）；这里按真实 diff 落值。
UPSTREAM_6100_CLUE_ITEM_ROI_WIDTH = 180  # 原 120
UPSTREAM_6100_CLUE_ITEM_ROI = [126, 157, 180, 555]
UPSTREAM_6100_CLUE_COUNT_COLOR_OFFSET = [103, -15, 45, 36]  # 原 [103, -24, 46, 36]
UPSTREAM_6100_ADB_CLUE_COUNT_COLOR_OFFSET = [103, -15, 80, 36]
UPSTREAM_6100_RECEPTION_BG_ROI_HEIGHT = 188  # 原 236
UPSTREAM_6100_ADB_TEXT_TEMPLATE_FILE = (
    ASSETS_ROOT / "resource_adb" / "pipeline" / "DijiangRewards" / "Template" / "TextTemplate.json"
)

# 6af0f43c (#6054 AutoSell 走到物资调度终端后补按交互键)。
UPSTREAM_6054_PRESS_NODE = "AutoSellPressStockRedistribution"
UPSTREAM_6054_PRESS_DEF = {
    "desc": "寻路偶现走到目的地了不按，因此检查一下",
    "recognition": "OCR",
    "roi": [755, 330, 297, 312],
    "expected": ["物资调度终端", "物資調度終端"],
    "pre_delay": 0,
    "action": "ClickKey",
    "key": 70,
    "post_delay": 0,
    "rate_limit": 0,
    "next": ["AutoSellEnterStockRedistributionSuccess"],
}
UPSTREAM_6054_ENTRY_NODE = "AutoSellShipEnterStockRedistribution"
UPSTREAM_6054_NEXT_NODE = "AutoSellEnterStockRedistributionSuccess"

# 27507ad4 (#6085 修复折扣识别区域过大)：折扣数字 ROI 两处。
UPSTREAM_6085_DISCOUNT_ROI_OFFSET = [41, -178, 52, 34]  # 原 [39, -212, 25, 63]
UPSTREAM_6085_DISCOUNT_NODES = (
    "AutoStockInStapleItemDiscountsWuling",
    "AutoStockInStapleItemDiscountsValleyIV",
)

# AutoSell 页签按坐标点击复用 AutoStockpile「弹性需求物资」页签 ROI（1280×720 真机截图）。
ELASTIC_TAB_ROI = [445, 80, 350, 66]

# 培养舱「提取获得」结算弹窗关闭识别（像素级诊断 + 真机框架日志，2026-09-30）。
#
# 失败现场帧：fail_frame.png（1280×720）。诊断结论（非猜测）：
#   1. 标题「提取获得」bbox x∈[597,682]、y∈[108,134]，而 ExtractSeedCloseText 的
#      OCR roi [537,21,209,149] 完整覆盖它 —— roi 正确、expected 已含「提取获得」，
#      真机 OCR 实测 0.999510（maafw.bak.2026.09.30-08.22 日志）。OCR 不是问题。
#   2. 关闭键 bbox x∈[620,659]、y∈[588,627]（≈40×40）。ADB 覆盖图
#      resource_adb/image/Common/Button/CloseRewardButton.png（44×45）与它 NCC=0.9998，
#      而基础图 resource/image/.../CloseRewardButton.png（33×31）只有 0.546。
#      MaaFramework 会把「同名模板」在两个 bundle 下的图都装进同一 vector 一起匹配
#      （真机 reco_details 同一次识别里同时出现 44×45 与 33×31 两个候选）：ADB 图 0.9998
#      命中，基础旧图是 0.4~0.8 的低分噪声候选。
#   3. 真正致命的是时序：「确认提取」点击后约 0.3s 就评估关闭节点，而弹窗约 1.5s 后才
#      渲染完成 → 首次识别模板只有 <0.4（真机三处均为 0.219/0.396），OCR 也随之为空；
#      上一轮加的 on_error 会把这次「瞬时落空」立刻转成 GrowBack（返回键被弹窗遮挡）→
#      坐标兜底。真机日志里首次落空后约 0.8~1.5s 再识别即 0.9998 命中（旧链路靠 next
#      轮询自愈，on_error 抢掉了这个重试）。
#
# 修法（都在补丁层、按文件路径定位、缺了 raise）：
#   ① 用 ADB 图覆盖基础图：消除 33×31 旧尺度候选，保证任一层解析到的都是同一物；
#   ② 给 GrowthChamberSeedExtractClose 加 pre_delay，让首次识别发生在弹窗渲染之后。
GROWTH_CHAMBER_CLOSE_TEMPLATE = "Common/Button/CloseRewardButton.png"
GROWTH_CHAMBER_CLOSE_BASE_IMAGE = (
    ASSETS_ROOT / "resource" / "image" / "Common" / "Button" / "CloseRewardButton.png"
)
GROWTH_CHAMBER_CLOSE_ADB_IMAGE = (
    ASSETS_ROOT / "resource_adb" / "image" / "Common" / "Button" / "CloseRewardButton.png"
)
GROWTH_CHAMBER_CLOSE_BUTTON_FILE = (
    ASSETS_ROOT / "resource" / "pipeline" / "Common" / "Button" / "CloseRewardsButton.json"
)
# 真机实测「确认提取」→弹窗可识别约 1.4~1.6s，取 1.5s 等待，让首次识别就落在弹窗就绪后。
GROWTH_CHAMBER_CLOSE_PRE_DELAY = 1500

# 培养舱「提取基核」返回（GrowBack）时序修复（真机框架日志 + 像素级诊断，2026-09-30）。
#
# 现场（08:22 真机日志，submodule fcdc53a7 构建）：
#   SeedExtractClose 于 08:20:51.224 点掉「提取获得」弹窗底部 ✓[618,586,44,45]，
#   约 0.2~0.3s 后（08:20:51.4xx）就直接评估 GrowthChamberGrowBack 的识别——
#   此时关闭动画未结束、返回键 ROI 仍被半透明遮罩盖着，TemplateMatch 只在遮罩上拿到
#   一簇贴阈值的弱候选：all_results_ 分数 0.53~0.75（阈值 0.7），
#   best=[1228,19,31,31] score=0.706312。点这个"假返回键"是空击 → GrowViewIn 不出现
#   → RepeatUntilFoundAction 耗尽 → on_error → 坐标兜底 SeedExtractCloseByCoord 接住。
#
# 像素证据（本地 fail_frame.png 1280×720，与设备 on_error 现场同源）：
#   ROI [1146,0,134,112] 为纯灰渐变（mean≈120、std≈14），
#   被识别成返回键的 31×31 区域 std≈1.9 —— 返回键被弹窗遮住，根本不在框里。
#   兜底 ROI [540,538,209,182] 里的底部 ✓ 才是真正能关弹窗的控件。
#
# 结论：`method=10001` **不是**根因。它是框架支持的 `SQDIFF_NORMED_Inverted`
#   （maa-framework-go v4.0.0-beta.18 recognition.go:138；官方注释见 Interface/Scene.json
#   "默认5时，当弹出右侧面板，此图片依然能匹配上"）。模板 BackButton.png 为 31×31、
#   远小于 ROI 134×112，也无问题。根因是**识别时机撞上弹窗关闭动画**。
#
# 修法（补丁层、按名定位、缺了 raise）：
#   ① GrowthChamberSeedExtractClose 加 post_wait_freezes：点掉弹窗后，等返回键区域
#      [1146,0,134,112] 画面稳定，再让 next 去识别 GrowBack，避免在动画遮罩上做匹配。
#   ② GrowBack 的 RepeatUntilFoundAction 显式 repeat_count/interval_ms：命中即提前返回，
#      正常路径无额外开销；只把「失败」路径的单次等待窗口说清楚、留足目标页渲染时间。
GROWTH_CHAMBER_BACK_REGION = [1146, 0, 134, 112]
GROWTH_CHAMBER_CLOSE_POST_FREEZE = {
    "time": 200,
    "timeout": 1500,
    "target": list(GROWTH_CHAMBER_BACK_REGION),
}
GROWTH_CHAMBER_GROWBACK_REPEAT_COUNT = 4
GROWTH_CHAMBER_GROWBACK_INTERVAL_MS = 3000

# ============================================================================
# 采集（AutoCollect）进世界/传送链 on_error 兜底（真机诊断 + 框架源码，2026-09-30）
#
# 真机现场（new_maafw.log，submodule fcdc53a7 构建）：
#   AutoCollectRoute1Start 的 SubTask(SceneEnterWorldWulingWulingCity5) 进入世界后，
#   锚点节点 __ScenePrivateMapWulingWulingCityEnterWorldAnchorWithPick 识别是好的，
#   但它的 next 里真正干活的 __ScenePrivateMapWulingWulingCityEnterWorldWulingWulingCity5
#   （recognition=Custom/MapFind，SceneTeleportWuling.json:929-962）在本 Android 端口是
#   **恒假 stub**（MaaRunner.kt 的 falseRecognitions 名单，WorldMap 未移植）。
#   → WithPick.next 整轮都不命中；MaaFramework PipelineTask::run_next 按 reco_timeout
#     默认 20000ms 反复重扫（每次 cost=0ms ret=false 的 MapFind 连击 21 次）→
#     PipelineNode.Failed → save_on_error 落一张 on_error 帧 → 全屏地图一直开着 →
#     下游 AutoCollectRouteNAssertLocation 的 MapLocateAssertLocation 拿不到「可定位区域」
#     （真机 53.7s 后 matched=false）→ 整条路线失败。
#   Route4 用的是 ValleyIV 旧模板（SwipeToStep + Try1/2/3，阈值 0.9，真机只有 0.383），
#   同一形态：PowerPlateau 进世界锚点的 next 耗尽 → 同样风暴 + 硬失败。
#
# 修法（补丁层，只改产物 JSON，不动子模块指针、不动 Kotlin）：
#   给采集进世界链上每个锚点节点的 next **末位**追加既有退出节点
#   __ScenePrivateAnyExit（ADB 覆盖层点 BACK 键 key=4；基础层 ESC key=27），
#   并同时把它的 on_error 也指向同一节点作为二级兜底：
#     - next 末位兜底：首轮 recognize_list 扫描到最后一个候选时 DirectHit 立刻命中，
#       OnError 分支根本不会进入 → 既没有 20s 重扫风暴，也不会抛 PipelineNode.Failed /
#       写 on_error 帧。真实候选（`__ScenePrivateMapTeleportSuccess`、MapFind 锚点等）
#       排在前面，能命中时优先命中，所以对「传送真的成功」的场景无副作用。
#     - on_error：MaaFramework 的 on_error 只有在本节点 next 整轮失败后才触发（且触发瞬间
#       框架必先写一帧 on_error 并抛 PipelineNode.Failed）；作为二级兜底保留，
#       万一 __ScenePrivateAnyExit 被某个覆盖层禁用也能收敛到安全收尾。
#   安全收尾后角色回到大世界；采集路线本来「人已在目标 zone」时，随后的
#   AutoCollectRouteNAssertLocation 直接命中，路线继续往下跑；不在目标 zone 时
#   也只是正常判失败，不再空转刷屏。
#
# 覆盖范围（已独立扫描 SceneManager 下全部 `*EnterWorldAnchor*` 节点定义，共 18 个）：
#   A 类 WithPick（MapFind 恒假 stub；next 末位是 [JumpBack][Anchor]TeleportPickAnchor）：
#     武陵 9 个（WulingCity / JingyuValley / MarkerStone / TestArea /
#     NorthWulingExclusionZone / SnowyForest / QingboStockade / SwordVaultDale /
#     YinglungPass）+ 谷地枢纽 TheHub 1 个 = 10 个。
#   B 类旧模板（SwipeToStep + Try1/2/3，真机模板 0.383/0.9 失配；next 末位是
#     [JumpBack][Anchor]SwipeToStep4Anchor）：武陵 4 个（JingyuValley / WulingCity /
#     MarkerStone / TestArea）+ 谷地 3 个（OriginiumSciencePark / PowerPlateau /
#     OriginLodespring）+ 帝江 1 个（Dijiang）= 8 个。
#   10 + 8 = 18。两类失败都会让锚点 next 整轮不命中（A 类 MapFind 恒假、B 类模板失配），
#   末位兜底让首轮扫描即命中，真实候选仍排前面优先。
AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE = "__ScenePrivateAnyExit"
AUTOCOLLECT_ENTERWORLD_FALLBACK_FILE = (
    ASSETS_ROOT / "resource" / "pipeline" / "SceneManager" / "SceneCommon.json"
)
# 节点名里用于「独立枚举」的标记：verify 会据此重扫产物，任何新增/改名都会被抓住。
AUTOCOLLECT_ENTERWORLD_ANCHOR_MARKER = "EnterWorldAnchor"
# 兜底应覆盖的锚点总数（与下面枚举逐项对应；verify 会独立复算并断言相等，防止以后再漏）。
AUTOCOLLECT_ENTERWORLD_EXPECTED_COUNT = 20
AUTOCOLLECT_ENTERWORLD_ANCHORS = {
    "resource/pipeline/SceneManager/SceneWuling.json": [
        # A 类 WithPick（MapFind 恒假）—— 其中 6 个上轮已补，其余 3 个本轮补。
        "__ScenePrivateMapWulingWulingCityEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingJingyuValleyEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingMarkerStoneEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingTestAreaEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingNorthWulingExclusionZoneEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingSnowyForestEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingQingboStockadeEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingSwordVaultDaleEnterWorldAnchorWithPick",
        "__ScenePrivateMapWulingYinglungPassEnterWorldAnchorWithPick",
        # B 类旧模板（SwipeToStep 模板失配）—— 本轮补。
        "__ScenePrivateMapWulingJingyuValleyEnterWorldAnchor",
        "__ScenePrivateMapWulingWulingCityEnterWorldAnchor",
        "__ScenePrivateMapWulingMarkerStoneEnterWorldAnchor",
        "__ScenePrivateMapWulingTestAreaEnterWorldAnchor",
    ],
    "resource/pipeline/SceneManager/SceneValleyIV.json": [
        # A 类 WithPick —— TheHub、AburreyQuarry、ValleyPass。
        "__ScenePrivateMapValleyIVTheHubEnterWorldAnchorWithPick",
        "__ScenePrivateMapValleyIVAburreyQuarryEnterWorldAnchorWithPick",
        "__ScenePrivateMapValleyIVValleyPassEnterWorldAnchorWithPick",
        # B 类旧模板 —— PowerPlateau/OriginLodespring 上轮已补，OriginiumSciencePark 本轮补。
        "__ScenePrivateMapValleyIVPowerPlateauEnterWorldAnchor",
        "__ScenePrivateMapValleyIVOriginLodespringEnterWorldAnchor",
        "__ScenePrivateMapValleyIVOriginiumScienceParkEnterWorldAnchor",
    ],
    "resource/pipeline/SceneManager/SceneDijiang.json": [
        # B 类旧模板 —— 本轮补。
        "__ScenePrivateMapDijiangEnterWorldAnchor",
    ],
}


def log(msg: str):
    print(f"[MAAend-Prep] {msg}", flush=True)


def load_jsonc(path: Path) -> dict:
    """读取并去除注释后的 JSON（沿用本文件既有的 strip_json_comments 风格）。"""
    return json.loads(strip_json_comments(path.read_text(encoding="utf-8")))


def write_json(path: Path, data) -> None:
    """以项目统一的格式写回 JSON（4 空格缩进、保留中文）。"""
    path.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")


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

    # 4. 在 AndroidOpenGame.json 的 ClientVersion 中添加云·终末地 (Cloud) 支持
    android_opengame_file = ASSETS_ROOT / "tasks" / "AndroidOpenGame.json"
    if android_opengame_file.is_file():
        try:
            content = android_opengame_file.read_text(encoding="utf-8")
            data = json.loads(strip_json_comments(content))
            client_version = data.get("option", {}).get("ClientVersion", {})
            cases = client_version.get("cases", [])
            if cases and not any(c.get("name") == "Cloud" for c in cases):
                cases.append({
                    "name": "Cloud",
                    "label": "云·终末地 (Cloud)",
                    "pipeline_override": {
                        "StartUpGame": {
                            "action": {
                                "type": "StartApp",
                                "param": {
                                    "package": "com.hypergryph.cloud.endfield/com.hypergryph.cloud.endfield.splash.SplashActivity"
                                }
                            }
                        },
                        "CloseGame": {
                            "action": {
                                "type": "StopApp",
                                "param": {
                                    "package": "com.hypergryph.cloud.endfield"
                                }
                            }
                        }
                    }
                })
                android_opengame_file.write_text(json.dumps(data, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
                log("Added Cloud Endfield option to ClientVersion in AndroidOpenGame.json.")
        except Exception as e:
            log(f"Warning: failed to add Cloud case to AndroidOpenGame.json: {e}")


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
        # ---- 移动端暂未完全实现或依赖桌面端组件的任务 ----
        # 基质筛选：上游 essencefilter 的 9 个组件全未移植 → 选中后空转。
        "EssenceFilter": "🔒基质筛选锁定【暂不可用·移动端未实现基质筛选】",
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

    同一套坐标点击也复用到 AutoSell 的扫描页签：`AutoSellScan*SwitchTab` /
    `...SwitchTabSuccess` 是刚性 TemplateMatch（ElasticGoodsButton.png /
    TabSwitchElasticGoodsActive.png）。720p 真机模板失配时，SwitchTab 点不到、
    SwitchTabSuccess 落空，直接把下一个候选 `AutoSellScanRegionNull` 命中成
    「当前区域物资为空」，于是静默 0 物资不卖。页签是固定 UI，改成坐标点击。
    """
    # 「弹性需求物资」页签的范围（1280×720，取自真机截图）。
    # 左边那个「稳定需求物资」是白底约 x∈[75,435]，弹性页签深色底 x∈[440,800]。
    elastic_tab_roi = list(ELASTIC_TAB_ROI)

    # ---- AutoStockpile：进入购买页后切到「弹性需求物资」页签 ----
    entry = ASSETS_ROOT / "resource" / "pipeline" / "AutoStockpile" / "Entry.json"
    if entry.is_file():
        try:
            data = load_jsonc(entry)
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
                write_json(entry, data)
                log("Overrode AutoStockpile elastic-tab nodes with a positional click.")
        except RuntimeError:
            raise
        except Exception as e:
            log(f"Warning: failed to override rigid template nodes: {e}")

    # ---- AutoSell：扫描前切到「弹性需求物资」页签（同一固定 UI，复用坐标）----
    scan = ASSETS_ROOT / "resource" / "pipeline" / "AutoSell" / "ScanItem.json"
    if not scan.is_file():
        return
    data = load_jsonc(scan)
    # 点击节点：坐标点击页签；校验节点：坐标命中即视为切换成功，
    # 不再用模板二次校验（真正的校验是 Save 节点的物资识别）。
    for node_name in ("AutoSellScanValleyIVSwitchTab", "AutoSellScanWulingSwitchTab"):
        node = data.get(node_name)
        if not isinstance(node, dict):
            raise RuntimeError(
                f"AutoSell/ScanItem.json 缺少节点 {node_name}（上游可能已改名/移除）；"
                f"页签按坐标点击无法应用，请同步更新 scripts/prepare_maaend.py"
            )
        node["recognition"] = "DirectHit"
        node["roi"] = list(elastic_tab_roi)
        node["action"] = {"type": "Click", "param": {}}
        node.pop("template", None)
        node.pop("threshold", None)
    for node_name in ("AutoSellScanValleyIVSwitchTabSuccess", "AutoSellScanWulingSwitchTabSuccess"):
        node = data.get(node_name)
        if not isinstance(node, dict):
            raise RuntimeError(
                f"AutoSell/ScanItem.json 缺少节点 {node_name}（上游可能已改名/移除）；"
                f"页签按坐标点击无法应用，请同步更新 scripts/prepare_maaend.py"
            )
        node["recognition"] = "DirectHit"
        node["roi"] = list(elastic_tab_roi)
        node["action"] = {"type": "DoNothing", "param": {}}
        node.pop("template", None)
        node.pop("threshold", None)
    write_json(scan, data)
    log("Overrode AutoSell scan-tab nodes with a positional click.")


def verify_rigid_template_overrides():
    """
    构建期断言：Upstream/AutoStockpile/AutoSell 的页签节点必须是坐标点击。

    理由同 verify_adb_growth_chamber_status_rois：模板节点在真机（720p）会失配，
    而我们改成了按坐标点击。若上游同步把这些节点改回 TemplateMatch，或后续步骤
    覆盖回旧值，真机就会重新走 ScanRegionNull / 认不出货，且**编得过、出得了包**。
    这里读回最终产物核对，不一致就让构建失败。
    """
    problems = []

    entry = ASSETS_ROOT / "resource" / "pipeline" / "AutoStockpile" / "Entry.json"
    if entry.is_file():
        data = load_jsonc(entry)
        for node_name in ("AutoStockpileGotoElasticGoods", "AutoStockpileEnaureElasticClicked"):
            actual = (data.get(node_name) or {}).get("roi")
            if actual != list(ELASTIC_TAB_ROI):
                problems.append(f"AutoStockpile/{node_name}.roi: 期望 {ELASTIC_TAB_ROI}，实际 {actual}")

    scan = ASSETS_ROOT / "resource" / "pipeline" / "AutoSell" / "ScanItem.json"
    if scan.is_file():
        data = load_jsonc(scan)
        for node_name in (
            "AutoSellScanValleyIVSwitchTab",
            "AutoSellScanWulingSwitchTab",
            "AutoSellScanValleyIVSwitchTabSuccess",
            "AutoSellScanWulingSwitchTabSuccess",
        ):
            node = data.get(node_name) or {}
            if node.get("recognition") != "DirectHit" or node.get("roi") != list(ELASTIC_TAB_ROI):
                problems.append(
                    f"AutoSell/{node_name}: 期望 DirectHit@{ELASTIC_TAB_ROI}，"
                    f"实际 {node.get('recognition')}@{node.get('roi')}"
                )
            if "template" in node or "threshold" in node:
                problems.append(f"AutoSell/{node_name}: 仍残留 template/threshold 刚性模板字段")

    if problems:
        raise RuntimeError(
            "构建期断言失败：页签节点不是按坐标点击（上游同步可能改回模板匹配）：\n  "
            + "\n  ".join(problems)
        )
    log("Asserted AutoStockpile/AutoSell tab nodes are positional clicks.")


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
      3. GrowthChamberSeedExtractClose 加 on_error（它原先没有）：真机失败帧证明
         「提取获得」弹窗已经出现（道具 + 底部 ✓），但关闭按钮识别不到 → 链断 → 整任务红。
      4. ExtractSeedCloseText.expected 扩通用「获得」类文案（参考 CloseRewardsButtonText）：
         提取结算标题与普通奖励结算共用同一标题区，文案微调也能认出关闭按钮。
      5. GrowthChamberNoMaterials.expected 扩原料不足类文案。

    此外，GrowBack 本身依赖右上角返回键模板。若弹窗盖住返回键 / 返回键点不掉，GrowBack
    仍会失败；所以给 GrowBack 再挂一个「按坐标点底部确认键」的兜底节点
    （GrowthChamberSeedExtractCloseByCoord），确保提取链**任何断法**都不会把任务拖红。
    """
    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    tmpl_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "Template" / "TextTemplate.json"
    for path in (growth_file, tmpl_file):
        if not path.is_file():
            raise RuntimeError(f"培养舱提取链修复失败：缺少 {path}")

    safe_node = "GrowthChamberGrowBack"
    coord_close_node = "GrowthChamberSeedExtractCloseByCoord"

    # 1/2/3) on_error 兜底：提取链任一出路断掉都收敛到 GrowBack
    growth = load_jsonc(growth_file)
    for node_name in (
        "GrowthChamberSeedExtractConfirm",
        "GrowthChamberSeedExtract",
        "GrowthChamberSeedExtractClose",
    ):
        node = growth.get(node_name)
        if not isinstance(node, dict):
            raise RuntimeError(
                f"GrowthChamber.json 缺少节点 {node_name}（上游可能已改名/移除）；"
                f"提取链兜底无法应用，请同步更新 scripts/prepare_maaend.py"
            )
        node["on_error"] = [safe_node]

    # GrowBack 的兜底：GrowBack 需要右上角返回键；识别不到 / 点不掉时，
    # 直接按坐标点底部的确认键（CloseRewardsButton 模板所在区域），再等回培养列表。
    grow_back = growth.get(safe_node)
    if not isinstance(grow_back, dict):
        raise RuntimeError(f"GrowthChamber.json 缺少节点 {safe_node}")
    growth[coord_close_node] = {
        "desc": "[培养舱·基核] 提取获得弹窗在但关闭按钮识别不到时，按坐标点击底部确认键兜底",
        "recognition": "DirectHit",
        "roi": [540, 538, 209, 182],
        "pre_delay": 0,
        "action": {"type": "Click"},
        "post_delay": 0,
        "rate_limit": 0,
        "next": ["GrowthChamberGrowViewIn"],
    }
    grow_back["on_error"] = [coord_close_node]

    # 5) NoMaterials 文案扩展
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
    write_json(growth_file, growth)

    # 4) 提取结算关闭文案扩展
    tmpl = load_jsonc(tmpl_file)
    close_text = tmpl.get("ExtractSeedCloseText")
    if not isinstance(close_text, dict):
        raise RuntimeError("TextTemplate.json 缺少节点 ExtractSeedCloseText")
    _extend_ocr_expected(close_text, [
        "获得",
        "獲得",
        "(?i)Rewards?\\s*Acquired",
        "報酬一覧",
    ])
    write_json(tmpl_file, tmpl)

    log("Patched GrowthChamber extraction chain with on_error fallbacks and wider OCR text.")


def verify_growth_chamber_extract_resilience():
    """
    构建期断言：提取链每一处都必须有 on_error 兜底，且关闭兜底节点存在。

    真机失败模式是「弹窗在、关闭按钮识别不到、链断、整任务红」。这里读回最终产物，
    确认 1) SeedExtract/SeedExtractConfirm/SeedExtractClose 都挂了 GrowBack；
    2) GrowBack 挂了按坐标点确认键的兜底；3) 关闭/原料不足文案已扩容。
    任何一项被上游同步抹掉都会让构建失败，而不是等到真机复现才想起。
    """
    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    tmpl_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "Template" / "TextTemplate.json"
    for path in (growth_file, tmpl_file):
        if not path.is_file():
            raise RuntimeError(f"构建期断言失败：产物缺少 {path}")

    safe_node = "GrowthChamberGrowBack"
    coord_close_node = "GrowthChamberSeedExtractCloseByCoord"
    growth = load_jsonc(growth_file)
    problems = []
    for node_name in (
        "GrowthChamberSeedExtractConfirm",
        "GrowthChamberSeedExtract",
        "GrowthChamberSeedExtractClose",
    ):
        on_error = (growth.get(node_name) or {}).get("on_error")
        if safe_node not in (on_error or []):
            problems.append(f"{node_name}.on_error: 期望含 {safe_node}，实际 {on_error}")
    grow_back_on_error = (growth.get(safe_node) or {}).get("on_error")
    if coord_close_node not in (grow_back_on_error or []):
        problems.append(f"{safe_node}.on_error: 期望含 {coord_close_node}，实际 {grow_back_on_error}")
    coord_node = growth.get(coord_close_node)
    if not isinstance(coord_node, dict) or coord_node.get("recognition") != "DirectHit":
        problems.append(f"缺少按坐标关闭兜底节点 {coord_close_node}")

    close_text = (load_jsonc(tmpl_file).get("ExtractSeedCloseText") or {})
    close_expected = (((close_text.get("recognition") or {}).get("param") or {}).get("expected")) or []
    if "获得" not in close_expected:
        problems.append("ExtractSeedCloseText.expected 缺少「获得」类文案")
    no_materials = growth.get("GrowthChamberNoMaterials") or {}
    nm_expected = (((no_materials.get("recognition") or {}).get("param") or {}).get("expected")) or []
    if "原料不足" not in nm_expected:
        problems.append("GrowthChamberNoMaterials.expected 缺少「原料不足」类文案")

    if problems:
        raise RuntimeError(
            "构建期断言失败：培养舱提取链兜底不完整（上游同步可能抹掉 on_error/文案）：\n  "
            + "\n  ".join(problems)
        )
    log("Asserted GrowthChamber extraction chain has on_error fallbacks.")


def patch_growth_chamber_extract_close_recognition():
    """
    修复 ③（根治）：培养舱「提取获得」结算弹窗关闭按钮的原路识别。

    见文件顶部 GROWTH_CHAMBER_CLOSE_* 常量上的像素级诊断。这里落两件事：

    1. 用 resource_adb 的正确图覆盖 resource 里 33×31 的旧尺度图。
       真机同一模板名下两张图会一起参与匹配：44×45 命中 0.9998、33×31 只是低分候选。
       覆盖后任一层解析到的都是同一物，也不再给匹配引入误导性候选。
    2. 给 GrowthChamberSeedExtractClose 加 pre_delay：真机在「确认提取」点击后约 0.3s
       就评估关闭节点，但弹窗约 1.5s 才渲染完成，首次识别必然落空；补一个等待，让首次
       识别就发生在弹窗就绪之后。

    两条都按文件路径/节点名精确定位，任一缺失直接抛错（不静默跳过）。
    """
    for path in (GROWTH_CHAMBER_CLOSE_BASE_IMAGE, GROWTH_CHAMBER_CLOSE_ADB_IMAGE):
        if not path.is_file():
            raise RuntimeError(
                f"培养舱关闭按钮修复失败：缺少模板图 {path}；无法校正旧尺度模板"
            )

    adb_bytes = GROWTH_CHAMBER_CLOSE_ADB_IMAGE.read_bytes()
    if GROWTH_CHAMBER_CLOSE_BASE_IMAGE.read_bytes() != adb_bytes:
        GROWTH_CHAMBER_CLOSE_BASE_IMAGE.write_bytes(adb_bytes)
        log("Overrode stale base CloseRewardButton.png with the ADB-sized template.")
    else:
        log("Base CloseRewardButton.png already matches the ADB template.")

    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    if not growth_file.is_file():
        raise RuntimeError(f"培养舱关闭按钮修复失败：缺少 {growth_file}")
    growth = load_jsonc(growth_file)
    node = growth.get("GrowthChamberSeedExtractClose")
    if not isinstance(node, dict):
        raise RuntimeError(
            "GrowthChamber.json 缺少节点 GrowthChamberSeedExtractClose（上游可能已改名/移除）；"
            "关闭按钮时序修复无法应用，请同步更新 scripts/prepare_maaend.py"
        )
    node["pre_delay"] = GROWTH_CHAMBER_CLOSE_PRE_DELAY
    write_json(growth_file, growth)
    log(
        "Patched GrowthChamberSeedExtractClose.pre_delay = "
        f"{GROWTH_CHAMBER_CLOSE_PRE_DELAY}ms (wait for reward popup to render)."
    )


def verify_growth_chamber_extract_close_recognition():
    """
    构建期断言：核对关闭按钮原路识别的两处修复确实落在最终产物里。

    若上游同步换回旧尺度图、删除 ADB 图、或后续步骤把 pre_delay 抹掉，真机就会再次
    「弹窗在、关闭键识别不到」，且照样编得过、出得了包。这里读回最终产物核对。
    """
    problems = []
    for path in (GROWTH_CHAMBER_CLOSE_BASE_IMAGE, GROWTH_CHAMBER_CLOSE_ADB_IMAGE):
        if not path.is_file():
            raise RuntimeError(f"构建期断言失败：缺少模板图 {path}")
    if GROWTH_CHAMBER_CLOSE_BASE_IMAGE.read_bytes() != GROWTH_CHAMBER_CLOSE_ADB_IMAGE.read_bytes():
        problems.append(
            "resource 基础 CloseRewardButton.png 与 resource_adb 版本不一致（旧尺度图未校正）"
        )

    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    if not growth_file.is_file():
        raise RuntimeError(f"构建期断言失败：缺少 {growth_file}")
    close_node = load_jsonc(growth_file).get("GrowthChamberSeedExtractClose") or {}
    if close_node.get("pre_delay") != GROWTH_CHAMBER_CLOSE_PRE_DELAY:
        problems.append(
            "GrowthChamberSeedExtractClose.pre_delay: 期望 "
            f"{GROWTH_CHAMBER_CLOSE_PRE_DELAY}，实际 {close_node.get('pre_delay')}"
        )

    if not GROWTH_CHAMBER_CLOSE_BUTTON_FILE.is_file():
        raise RuntimeError(f"构建期断言失败：缺少 {GROWTH_CHAMBER_CLOSE_BUTTON_FILE}")
    stable = (load_jsonc(GROWTH_CHAMBER_CLOSE_BUTTON_FILE).get("__CloseRewardsButtonStable") or {})
    template = (((stable.get("recognition") or {}).get("param") or {}).get("template"))
    templates = template if isinstance(template, list) else [template]
    if GROWTH_CHAMBER_CLOSE_TEMPLATE not in templates:
        problems.append(
            f"__CloseRewardsButtonStable.template 未引用 {GROWTH_CHAMBER_CLOSE_TEMPLATE}：{template}"
        )

    if problems:
        raise RuntimeError(
            "构建期断言失败：培养舱「提取获得」关闭按钮识别修复不完整"
            "（上游同步可能覆盖/改名）：\n  " + "\n  ".join(problems)
        )
    log("Asserted GrowthChamber extract-close template and settle delay.")


def patch_growth_chamber_growback_timing():
    """
    修复 ④：培养舱「提取基核」返回（GrowthChamberGrowBack）在弹窗关闭动画上做识别。

    见文件顶部 GROWTH_CHAMBER_BACK_REGION 上的日志 + 像素级诊断。落两件事：

    1. 给 GrowthChamberSeedExtractClose 加 post_wait_freezes（target=返回键 ROI）：
       点掉「提取获得」弹窗后，等返回键区域画面稳定再评估下一条 GrowBack。
       真机日志显示 SeedExtractClose 点击到 GrowBack 识别只隔 ~0.2~0.3s，而弹窗关闭
       动画更久，于是识别撞在半透明遮罩上拿到一簇贴阈值弱候选 → 空击 → 兜底。
    2. 给 GrowBack 的 RepeatUntilFoundAction 显式 repeat_count/interval_ms：把失败路径
       的单次等待窗口说清楚（命中仍会提前返回），不再依赖 MaaRunner 的隐式默认。

    两条都按节点名精确定位，任一缺失直接抛错（不静默跳过）。
    """
    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    if not growth_file.is_file():
        raise RuntimeError(f"培养舱返回时序修复失败：缺少 {growth_file}")
    growth = load_jsonc(growth_file)

    close_node = growth.get("GrowthChamberSeedExtractClose")
    if not isinstance(close_node, dict):
        raise RuntimeError(
            "GrowthChamber.json 缺少节点 GrowthChamberSeedExtractClose（上游可能已改名/移除）；"
            "返回时序修复无法应用，请同步更新 scripts/prepare_maaend.py"
        )
    close_node["post_wait_freezes"] = json.loads(json.dumps(GROWTH_CHAMBER_CLOSE_POST_FREEZE))

    grow_back = growth.get("GrowthChamberGrowBack")
    if not isinstance(grow_back, dict):
        raise RuntimeError(f"GrowthChamber.json 缺少节点 GrowthChamberGrowBack")
    action = grow_back.get("action")
    if not isinstance(action, dict) or action.get("type") != "Custom":
        raise RuntimeError("GrowthChamberGrowBack.action 不是 Custom；返回时序修复无法应用")
    param = action.get("param")
    if not isinstance(param, dict) or param.get("custom_action") != "RepeatUntilFoundAction":
        raise RuntimeError(
            "GrowthChamberGrowBack 未使用 RepeatUntilFoundAction；返回时序修复无法应用"
        )
    cap = param.get("custom_action_param")
    if not isinstance(cap, dict):
        raise RuntimeError("GrowthChamberGrowBack.custom_action_param 结构异常")
    cap["repeat_count"] = GROWTH_CHAMBER_GROWBACK_REPEAT_COUNT
    cap["interval_ms"] = GROWTH_CHAMBER_GROWBACK_INTERVAL_MS

    write_json(growth_file, growth)
    log(
        "Patched GrowthChamberSeedExtractClose.post_wait_freezes (settle back-button ROI) "
        f"and GrowBack repeat_count={GROWTH_CHAMBER_GROWBACK_REPEAT_COUNT}/"
        f"interval_ms={GROWTH_CHAMBER_GROWBACK_INTERVAL_MS}."
    )


def verify_growth_chamber_growback_timing():
    """
    构建期断言：核对 GrowBack 时序修复确实落在最终产物里。

    上游同步若覆盖/改名，改动会「静默失效」——照样编得过、出得了包，只是真机又回到
    「点掉弹窗后立刻在半透明遮罩上识别返回键」。这里读回最终产物核对。
    """
    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    if not growth_file.is_file():
        raise RuntimeError(f"构建期断言失败：缺少 {growth_file}")
    growth = load_jsonc(growth_file)
    problems = []

    close_node = growth.get("GrowthChamberSeedExtractClose") or {}
    pwf = close_node.get("post_wait_freezes")
    if not isinstance(pwf, dict) or pwf.get("target") != GROWTH_CHAMBER_BACK_REGION:
        problems.append(
            "GrowthChamberSeedExtractClose.post_wait_freezes.target: 期望 "
            f"{GROWTH_CHAMBER_BACK_REGION}，实际 {pwf}"
        )

    grow_back = growth.get("GrowthChamberGrowBack") or {}
    cap = (((grow_back.get("action") or {}).get("param") or {}).get("custom_action_param")) or {}
    if cap.get("repeat_count") != GROWTH_CHAMBER_GROWBACK_REPEAT_COUNT:
        problems.append(
            "GrowthChamberGrowBack.repeat_count: 期望 "
            f"{GROWTH_CHAMBER_GROWBACK_REPEAT_COUNT}，实际 {cap.get('repeat_count')}"
        )
    if cap.get("interval_ms") != GROWTH_CHAMBER_GROWBACK_INTERVAL_MS:
        problems.append(
            "GrowthChamberGrowBack.interval_ms: 期望 "
            f"{GROWTH_CHAMBER_GROWBACK_INTERVAL_MS}，实际 {cap.get('interval_ms')}"
        )

    if problems:
        raise RuntimeError(
            "构建期断言失败：培养舱 GrowBack 时序修复不完整"
            "（上游同步可能覆盖/改名）：\n  " + "\n  ".join(problems)
        )
    log("Asserted GrowthChamber GrowBack settle-wait and action window.")


def patch_upstream_6076_confirm_box_index():
    """
    同步上游 489ff2fe（#6076 偶现基建任务点击使用助力失效）。

    真机「点使用助力」偶发失效：确认对话框用文字识别会误命中，改用图标的 box_index。
        培养舱再次种植 GrowthChamberGrowAgainConfirm            : box_index 1 -> 0
        制造舱助力 MFGCabinAssistConfirm                        : 补上缺失的 box_index 0
        恢复心情 RecoveryEmotionConfirm                         : box_index 1 -> 0
        干员赠礼 GiftOperatorConfirmDialog                      : box_index 1 -> 0
    整提交全部应用；节点缺失（上游改名/移除）直接抛错，不静默跳过。
    """
    for rel, node_name in UPSTREAM_6076_BOX_INDEX_FIX:
        path = ASSETS_ROOT / rel
        if not path.is_file():
            raise RuntimeError(f"489ff2fe 同步失败：缺少 {path}")
        data = load_jsonc(path)
        node = data.get(node_name)
        if not isinstance(node, dict):
            raise RuntimeError(
                f"{rel} 缺少节点 {node_name}（上游可能已改名/移除）；"
                f"489ff2fe 同步无法应用，请同步更新 scripts/prepare_maaend.py"
            )
        recognition = node.get("recognition")
        if not isinstance(recognition, dict) or not isinstance(recognition.get("param"), dict):
            raise RuntimeError(f"{rel} 节点 {node_name} 的 recognition.param 结构异常；489ff2fe 同步无法应用")
        recognition["param"]["box_index"] = UPSTREAM_6076_BOX_INDEX
        write_json(path, data)
        log(f"Patched {node_name}.box_index = {UPSTREAM_6076_BOX_INDEX} (upstream #6076).")


def patch_upstream_6100_dijiang_rewards():
    """
    同步上游 d4ea8745（#6100 修复 ADB 端基建奖励任务失败）的 base resource 侧改动。

    说明：ADB 覆盖层 Status.json 的两个 roi_offset 早已由
    patch_adb_growth_chamber_status_rois() 单独处理，这里**不重复、不冲突**。
    本函数补齐此前只跟了 ADB roi_offset、遗漏的其余部分：
      1. GrowthChamberFindTargetBySeed.desc 去掉 #1313 绕开备注（行为不变，文案对齐）。
      2. ReceptionRoom 快速赠予重复线索：背景色 roi 高 236 -> 188，并去掉冗余的
         pre_delay/post_delay/rate_limit（两个节点）。
      3. base Template/Status.json：ClueItem roi 宽 120 -> 180（任务简报误记为
         GrowthChamberCheckSeedNotEmpty；实际归属见上游 diff 第 238 行，勿按简报写错节点）；
         删除废弃节点 GrowthChamberCheckTargetNotEmpty；给两个检查节点补 desc。
      4. base Template/TextTemplate.json：ClueItemCountColor roi_offset 更新。
      5. ADB Template/TextTemplate.json：新增 ClueItemCountColor 覆盖。
    """
    growth_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "GrowthChamber.json"
    reception_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "ReceptionRoom.json"
    status_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "Template" / "Status.json"
    text_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "Template" / "TextTemplate.json"
    for path in (growth_file, reception_file, status_file, text_file, UPSTREAM_6100_ADB_TEXT_TEMPLATE_FILE):
        if not path.is_file():
            raise RuntimeError(f"d4ea8745 同步失败：缺少 {path}")

    # 1) GrowthChamberFindTargetBySeed.desc
    growth = load_jsonc(growth_file)
    node = growth.get("GrowthChamberFindTargetBySeed")
    if not isinstance(node, dict):
        raise RuntimeError("GrowthChamber.json 缺少节点 GrowthChamberFindTargetBySeed")
    node["desc"] = "[培养舱·培养] 点击有基核的培养对象"
    write_json(growth_file, growth)

    # 2) ReceptionRoom 快速赠予重复线索两节点
    reception = load_jsonc(reception_file)
    bg = reception.get("ReceptionRoomSendCluesQuickGiveDuplicatesBackground")
    if not isinstance(bg, dict):
        raise RuntimeError("ReceptionRoom.json 缺少节点 ReceptionRoomSendCluesQuickGiveDuplicatesBackground")
    bg_roi = ((bg.get("recognition") or {}).get("param") or {}).get("roi")
    if not (isinstance(bg_roi, list) and len(bg_roi) == 4):
        raise RuntimeError("ReceptionRoomSendCluesQuickGiveDuplicatesBackground.roi 结构异常")
    bg_roi[3] = UPSTREAM_6100_RECEPTION_BG_ROI_HEIGHT
    for key in ("pre_delay", "post_delay", "rate_limit"):
        bg.pop(key, None)
    text_node = reception.get("ReceptionRoomSendCluesQuickGiveDuplicatesText")
    if not isinstance(text_node, dict):
        raise RuntimeError("ReceptionRoom.json 缺少节点 ReceptionRoomSendCluesQuickGiveDuplicatesText")
    for key in ("pre_delay", "post_delay", "rate_limit"):
        text_node.pop(key, None)
    write_json(reception_file, reception)

    # 3) base Template/Status.json
    status = load_jsonc(status_file)
    clue = status.get("ClueItem")
    if not isinstance(clue, dict):
        raise RuntimeError("Status.json 缺少节点 ClueItem")
    clue_roi = ((clue.get("recognition") or {}).get("param") or {}).get("roi")
    if not (isinstance(clue_roi, list) and len(clue_roi) == 4):
        raise RuntimeError("ClueItem.roi 结构异常")
    clue_roi[2] = UPSTREAM_6100_CLUE_ITEM_ROI_WIDTH
    status.pop("GrowthChamberCheckTargetNotEmpty", None)  # 上游已废弃删除
    for node_name, desc in (
        ("GrowthChamberCheckSeedNotEmpty", "[培养舱·培养] 识别种子数量不为0"),
        ("GrowthChamberCheckPlantNotEmpty", "[培养舱·培养] 识别培养对象数量不为0"),
    ):
        if not isinstance(status.get(node_name), dict):
            raise RuntimeError(f"Status.json 缺少节点 {node_name}")
        status[node_name]["desc"] = desc
    write_json(status_file, status)

    # 4) base Template/TextTemplate.json ClueItemCountColor
    text = load_jsonc(text_file)
    clue_count = text.get("ClueItemCountColor")
    if not isinstance(clue_count, dict):
        raise RuntimeError("TextTemplate.json 缺少节点 ClueItemCountColor")
    cc_param = (clue_count.get("recognition") or {}).get("param")
    if not isinstance(cc_param, dict):
        raise RuntimeError("ClueItemCountColor.recognition.param 结构异常")
    cc_param["roi_offset"] = list(UPSTREAM_6100_CLUE_COUNT_COLOR_OFFSET)
    write_json(text_file, text)

    # 5) ADB Template/TextTemplate.json 新增 ClueItemCountColor
    adb_text = load_jsonc(UPSTREAM_6100_ADB_TEXT_TEMPLATE_FILE)
    adb_text["ClueItemCountColor"] = {"roi_offset": list(UPSTREAM_6100_ADB_CLUE_COUNT_COLOR_OFFSET)}
    write_json(UPSTREAM_6100_ADB_TEXT_TEMPLATE_FILE, adb_text)

    log("Patched DijiangRewards base + ADB for upstream #6100 (d4ea8745).")


def patch_upstream_6054_autosell_interact():
    """
    同步上游 6af0f43c（#6054 AutoSell 走到物资调度终端后补按交互键）。

    真机问题：MapNavigate 的 INTERACT 偶发「走到目的地了没按」，于是没进物资调度页面，
    后续一直等弹性需求物资页到超时。上游在 `AutoSellShipEnterStockRedistribution.next`
    里 Success 之后补一个 `AutoSellPressStockRedistribution`：OCR 认出「物资调度终端」
    就补按交互键（F / KeymapInteract），再走 Success。

    Android 没有键盘：ADB 覆盖层把该节点的 ClickKey(F) 换成 Custom AutoAltClickAction
    （点击 OCR 命中的文本框），与上游 resource_adb 覆盖一致。
    macos/Keymap 的改动与 Android 无关，且 Keymap.json 已被本脚本从 import 里移除，
    故不落（它们在手机上无意义）。
    """
    base_file = ASSETS_ROOT / "resource" / "pipeline" / "AutoSell" / "Common.json"
    if not base_file.is_file():
        raise RuntimeError(f"6af0f43c 同步失败：缺少 {base_file}")
    data = load_jsonc(base_file)
    entry = data.get(UPSTREAM_6054_ENTRY_NODE)
    if not isinstance(entry, dict):
        raise RuntimeError(f"AutoSell/Common.json 缺少节点 {UPSTREAM_6054_ENTRY_NODE}")
    next_list = entry.get("next")
    if not isinstance(next_list, list):
        raise RuntimeError(f"{UPSTREAM_6054_ENTRY_NODE}.next 不是数组")
    if UPSTREAM_6054_PRESS_NODE not in next_list:
        # 与上游一致：Success 在前，Press 在后（Success 命中即短路，不打扰已到达的场景）
        if UPSTREAM_6054_NEXT_NODE in next_list:
            next_list.insert(next_list.index(UPSTREAM_6054_NEXT_NODE) + 1, UPSTREAM_6054_PRESS_NODE)
        else:
            next_list.append(UPSTREAM_6054_PRESS_NODE)
    data[UPSTREAM_6054_PRESS_NODE] = json.loads(json.dumps(UPSTREAM_6054_PRESS_DEF))
    write_json(base_file, data)

    adb_file = ASSETS_ROOT / "resource_adb" / "pipeline" / "AutoSell" / "Common.json"
    adb = load_jsonc(adb_file) if adb_file.is_file() else {}
    adb[UPSTREAM_6054_PRESS_NODE] = {
        "action": {"type": "Custom", "param": {"custom_action": "AutoAltClickAction"}}
    }
    adb_file.parent.mkdir(parents=True, exist_ok=True)
    write_json(adb_file, adb)
    log("Patched AutoSell stock-redistribution interact key for upstream #6054.")


def patch_upstream_6085_staple_discount_roi():
    """
    同步上游 27507ad4（#6085 修复折扣识别区域过大）。

    购买稳定物资时折扣数字 OCR 的 roi_offset 过大，框住了非数字区域导致识别错乱；
    两个地区的节点都用同一修复值：
        AutoStockInStapleItemDiscountsWuling / ...ValleyIV : [39,-212,25,63] -> [41,-178,52,34]
    """
    item_file = ASSETS_ROOT / "resource" / "pipeline" / "AutoStockStaple" / "General" / "Item.json"
    if not item_file.is_file():
        raise RuntimeError(f"27507ad4 同步失败：缺少 {item_file}")
    data = load_jsonc(item_file)
    for node_name in UPSTREAM_6085_DISCOUNT_NODES:
        node = data.get(node_name)
        if not isinstance(node, dict):
            raise RuntimeError(
                f"AutoStockStaple/General/Item.json 缺少节点 {node_name}（上游可能已改名/移除）；"
                f"27507ad4 同步无法应用，请同步更新 scripts/prepare_maaend.py"
            )
        param = (node.get("recognition") or {}).get("param")
        if not isinstance(param, dict):
            raise RuntimeError(f"{node_name}.recognition.param 结构异常")
        param["roi_offset"] = list(UPSTREAM_6085_DISCOUNT_ROI_OFFSET)
    write_json(item_file, data)
    log("Patched AutoStockStaple discount number ROIs for upstream #6085.")


def verify_upstream_sync_patches():
    """
    构建期断言：核对 4 个上游修复的关键值确实落在最终产物里。

    每个补丁都按节点名精确落值；若上游同步改名/移除，或后续步骤覆盖回旧值，补丁会
    「静默失效」——照样编得过、出得了包，只是真机行为悄悄变回去。这里读回最终产物核对。
    """
    problems = []

    # 489ff2fe：四个确认框 box_index == 0
    for rel, node_name in UPSTREAM_6076_BOX_INDEX_FIX:
        path = ASSETS_ROOT / rel
        actual = None
        if path.is_file():
            actual = (((load_jsonc(path).get(node_name) or {}).get("recognition") or {}).get("param") or {}).get("box_index")
        if actual != UPSTREAM_6076_BOX_INDEX:
            problems.append(f"{rel}::{node_name}.box_index: 期望 {UPSTREAM_6076_BOX_INDEX}，实际 {actual}")

    # d4ea8745：ClueItem 宽 180、废弃节点已删、文案已补、ADB ClueItemCountColor 已加
    status_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "Template" / "Status.json"
    status = load_jsonc(status_file) if status_file.is_file() else {}
    actual_clue_roi = (((status.get("ClueItem") or {}).get("recognition") or {}).get("param") or {}).get("roi")
    if actual_clue_roi != UPSTREAM_6100_CLUE_ITEM_ROI:
        problems.append(f"Status.json::ClueItem.roi: 期望 {UPSTREAM_6100_CLUE_ITEM_ROI}，实际 {actual_clue_roi}")
    if "GrowthChamberCheckTargetNotEmpty" in status:
        problems.append("Status.json 仍残留废弃节点 GrowthChamberCheckTargetNotEmpty")
    text_file = ASSETS_ROOT / "resource" / "pipeline" / "DijiangRewards" / "Template" / "TextTemplate.json"
    text = load_jsonc(text_file) if text_file.is_file() else {}
    actual_cc = (((text.get("ClueItemCountColor") or {}).get("recognition") or {}).get("param") or {}).get("roi_offset")
    if actual_cc != UPSTREAM_6100_CLUE_COUNT_COLOR_OFFSET:
        problems.append(f"TextTemplate.json::ClueItemCountColor.roi_offset: 期望 {UPSTREAM_6100_CLUE_COUNT_COLOR_OFFSET}，实际 {actual_cc}")
    adb_text = load_jsonc(UPSTREAM_6100_ADB_TEXT_TEMPLATE_FILE) if UPSTREAM_6100_ADB_TEXT_TEMPLATE_FILE.is_file() else {}
    actual_adb_cc = (adb_text.get("ClueItemCountColor") or {}).get("roi_offset")
    if actual_adb_cc != UPSTREAM_6100_ADB_CLUE_COUNT_COLOR_OFFSET:
        problems.append(f"ADB TextTemplate::ClueItemCountColor.roi_offset: 期望 {UPSTREAM_6100_ADB_CLUE_COUNT_COLOR_OFFSET}，实际 {actual_adb_cc}")

    # 6af0f43c：基础节点存在、next 已挂、ADB 覆盖为 Custom AutoAltClickAction
    base_autosell = ASSETS_ROOT / "resource" / "pipeline" / "AutoSell" / "Common.json"
    base = load_jsonc(base_autosell) if base_autosell.is_file() else {}
    if UPSTREAM_6054_PRESS_NODE not in base:
        problems.append(f"AutoSell/Common.json 缺少基础节点 {UPSTREAM_6054_PRESS_NODE}")
    entry_next = ((base.get(UPSTREAM_6054_ENTRY_NODE) or {}).get("next")) or []
    if UPSTREAM_6054_PRESS_NODE not in entry_next:
        problems.append(f"{UPSTREAM_6054_ENTRY_NODE}.next 未包含 {UPSTREAM_6054_PRESS_NODE}：{entry_next}")
    adb_autosell = ASSETS_ROOT / "resource_adb" / "pipeline" / "AutoSell" / "Common.json"
    adb = load_jsonc(adb_autosell) if adb_autosell.is_file() else {}
    adb_action = ((adb.get(UPSTREAM_6054_PRESS_NODE) or {}).get("action") or {})
    if adb_action.get("type") != "Custom" or (adb_action.get("param") or {}).get("custom_action") != "AutoAltClickAction":
        problems.append(f"ADB AutoSell 覆盖 {UPSTREAM_6054_PRESS_NODE} 不是 Custom/AutoAltClickAction：{adb_action}")

    # 27507ad4：两个折扣节点 roi_offset
    item_file = ASSETS_ROOT / "resource" / "pipeline" / "AutoStockStaple" / "General" / "Item.json"
    item = load_jsonc(item_file) if item_file.is_file() else {}
    for node_name in UPSTREAM_6085_DISCOUNT_NODES:
        actual = (((item.get(node_name) or {}).get("recognition") or {}).get("param") or {}).get("roi_offset")
        if actual != UPSTREAM_6085_DISCOUNT_ROI_OFFSET:
            problems.append(f"AutoStockStaple::Item.json::{node_name}.roi_offset: 期望 {UPSTREAM_6085_DISCOUNT_ROI_OFFSET}，实际 {actual}")

    if problems:
        raise RuntimeError(
            "构建期断言失败：上游同步补丁未落在产物里（上游同步可能覆盖/改名）：\n  "
            + "\n  ".join(problems)
        )
    log("Asserted upstream #6076/#6100/#6054/#6085 sync patches are present.")


def patch_autocollect_enterworld_fallback():
    """
    采集进世界/传送链安全收尾（见文件顶部 AUTOCOLLECT_ENTERWORLD_* 诊断）。

    对每个采集进世界锚点节点：
      1. next 末位追加既有退出节点 __ScenePrivateAnyExit —— 首轮扫描到它即命中，
         MaaFramework 不会再进入 next 整轮失败分支（无 20s 重扫、无 PipelineNode.Failed、
         无 on_error 帧）；真实候选排在它前面，能命中时不影响正常传送。
      2. on_error 也指向同一节点作为二级兜底。
    节点名/结构任一缺失直接抛错（不静默跳过）。
    """
    fallback_file = AUTOCOLLECT_ENTERWORLD_FALLBACK_FILE
    if not fallback_file.is_file():
        raise RuntimeError(f"采集进世界兜底失败：缺少兜底节点所在文件 {fallback_file}")
    fallback_data = load_jsonc(fallback_file)
    if AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE not in fallback_data:
        raise RuntimeError(
            f"采集进世界兜底失败：{fallback_file} 缺少节点 "
            f"{AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE}（上游可能已改名/移除）"
        )

    patched = []
    for rel, node_names in AUTOCOLLECT_ENTERWORLD_ANCHORS.items():
        path = ASSETS_ROOT / rel
        if not path.is_file():
            raise RuntimeError(f"采集进世界兜底失败：缺少 {path}")
        data = load_jsonc(path)
        for name in node_names:
            node = data.get(name)
            if not isinstance(node, dict):
                raise RuntimeError(
                    f"{rel} 缺少采集进世界锚点 {name}（上游可能已改名/移除）；"
                    "兜底无法应用，请同步更新 scripts/prepare_maaend.py"
                )
            nxt = node.get("next")
            if not isinstance(nxt, list):
                raise RuntimeError(f"{rel}::{name}.next 不是列表，无法追加兜底：{nxt}")
            if AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE not in nxt:
                nxt.append(AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE)
            node["on_error"] = [AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE]
            patched.append(f"{rel}::{name}")
        write_json(path, data)

    log(
        f"Patched {len(patched)} AutoCollect enter-world anchors with "
        f"next-tail + on_error fallback -> {AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE}."
    )


def discover_enterworld_anchors() -> dict:
    """
    独立枚举：递归扫描 base resource/pipeline 下所有 JSON 的顶层节点名，收集含
    AUTOCOLLECT_ENTERWORLD_ANCHOR_MARKER 的节点定义，返回 {节点名: 相对路径}。

    这是 verify 的「第二种真相」：不依赖上面那份手写枚举。上游若新增一个进世界锚点、
    或改了锚点所在文件，这里会立刻发现与枚举不一致 → 构建期报错，逼人复核，防止再漏。
    """
    found = {}
    pipeline_root = ASSETS_ROOT / "resource" / "pipeline"
    for path in sorted(pipeline_root.rglob("*.json")):
        data = load_jsonc(path)
        if not isinstance(data, dict):
            continue
        rel = str(path.relative_to(ASSETS_ROOT))
        for name in data.keys():
            if AUTOCOLLECT_ENTERWORLD_ANCHOR_MARKER in name:
                found[name] = rel
    return found


def verify_autocollect_enterworld_fallback():
    """
    构建期断言：核对采集进世界兜底确实落在最终产物里。

    上游同步若改名/覆盖，补丁会「静默失效」——照样编得过、出得了包，只是真机又回到
    「MapFind 21 连击 + on_error 帧 + 整条路线失败」。这里读回最终产物核对。

    保证「不再漏」的两道闸：
      ① 独立重扫产物里的所有 EnterWorldAnchor 节点，断言其**数量 == 期望值**，
         且与手写枚举**双向一一对应**（新增/改名/移文件都会被抓）；
      ② 对枚举里每个锚点逐项断言 next 末位 == 兜底 且 on_error 含兜底。
    """
    problems = []

    fallback_file = AUTOCOLLECT_ENTERWORLD_FALLBACK_FILE
    fallback_data = load_jsonc(fallback_file) if fallback_file.is_file() else {}
    if AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE not in fallback_data:
        problems.append(
            f"{fallback_file} 缺少兜底节点 {AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE}"
        )

    enumerated = {
        name: rel
        for rel, node_names in AUTOCOLLECT_ENTERWORLD_ANCHORS.items()
        for name in node_names
    }
    discovered = discover_enterworld_anchors()

    # ① 数量闸：枚举与独立扫描都必须恰好等于期望值。
    if len(enumerated) != AUTOCOLLECT_ENTERWORLD_EXPECTED_COUNT:
        problems.append(
            f"手写枚举锚点数 {len(enumerated)} != 期望 {AUTOCOLLECT_ENTERWORLD_EXPECTED_COUNT}"
        )
    if len(discovered) != AUTOCOLLECT_ENTERWORLD_EXPECTED_COUNT:
        problems.append(
            f"产物里扫描到的 EnterWorldAnchor 节点数 {len(discovered)} != "
            f"期望 {AUTOCOLLECT_ENTERWORLD_EXPECTED_COUNT}（上游可能新增/改名/移除）"
        )
    unlisted = sorted(set(discovered) - set(enumerated))
    if unlisted:
        problems.append(
            "产物里存在但未纳入兜底枚举的 EnterWorldAnchor 节点（漏补！）："
            + "、".join(f"{n}@{discovered[n]}" for n in unlisted)
        )
    vanished = sorted(set(enumerated) - set(discovered))
    if vanished:
        problems.append(
            "枚举里列出但产物里已找不到的 EnterWorldAnchor 节点（上游改名/移除）："
            + "、".join(f"{n}@{enumerated[n]}" for n in vanished)
        )
    moved = sorted(
        n for n in set(enumerated) & set(discovered) if enumerated[n] != discovered[n]
    )
    if moved:
        problems.append(
            "锚点所在文件与枚举不一致（上游移动了节点）："
            + "、".join(f"{n}: {enumerated[n]} -> {discovered[n]}" for n in moved)
        )

    # ② 逐项闸：next 末位 + on_error。
    for rel, node_names in AUTOCOLLECT_ENTERWORLD_ANCHORS.items():
        path = ASSETS_ROOT / rel
        data = load_jsonc(path) if path.is_file() else {}
        for name in node_names:
            node = data.get(name) or {}
            on_error = node.get("on_error")
            if not isinstance(on_error, list) or AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE not in on_error:
                problems.append(
                    f"{rel}::{name}.on_error 期望含 {AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE}，"
                    f"实际 {on_error}"
                )
            nxt = node.get("next")
            if not isinstance(nxt, list) or AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE not in nxt:
                problems.append(
                    f"{rel}::{name}.next 末位缺兜底 {AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE}，"
                    f"实际 {nxt}"
                )
            elif nxt[-1] != AUTOCOLLECT_ENTERWORLD_FALLBACK_NODE:
                problems.append(
                    f"{rel}::{name}.next 兜底不在末位（会被后续候选抢先）：{nxt}"
                )

    if problems:
        raise RuntimeError(
            "构建期断言失败：采集进世界兜底不完整（上游同步可能覆盖/改名）：\n  "
            + "\n  ".join(problems)
        )
    log(
        f"Asserted {len(discovered)} AutoCollect enter-world anchors carry "
        f"next-tail + on_error fallback."
    )


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
    # 上游 4 个真机修复（submodule 停在 fcdc53a7，构建期在补丁层同步）
    patch_upstream_6076_confirm_box_index()
    patch_upstream_6100_dijiang_rewards()
    patch_upstream_6054_autosell_interact()
    patch_upstream_6085_staple_discount_roi()
    patch_adb_growth_chamber_status_rois()
    patch_growth_chamber_extract_resilience()
    patch_growth_chamber_extract_close_recognition()
    patch_growth_chamber_growback_timing()
    # 采集（AutoCollect）进世界/传送链安全收尾（真机 MapFind 恒假导致的风暴/硬失败）
    patch_autocollect_enterworld_fallback()
    # 断言放最后：读回最终产物，确认没有后续步骤把值覆盖回去
    verify_adb_growth_chamber_status_rois()
    verify_upstream_sync_patches()
    verify_rigid_template_overrides()
    verify_growth_chamber_extract_resilience()
    verify_growth_chamber_extract_close_recognition()
    verify_growth_chamber_growback_timing()
    verify_autocollect_enterworld_fallback()
    log("Preparation complete!")


if __name__ == "__main__":
    main()

