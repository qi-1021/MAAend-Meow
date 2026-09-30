#!/usr/bin/env bash
#
# 本地构建（工具链全在移动硬盘上，不依赖 CI / 网络）。
#
# 用法：
#   scripts/build_local.sh            # debug 变体（含调试 CLI，装机测试用这个）
#   scripts/build_local.sh release    # release 变体（仓库自带 signing/release.jks）
#
# 产物：app/build/outputs/apk/<variant>/app-<variant>.apk
#
# ## 为什么需要这个脚本（而不是直接 ./gradlew）
#
# 1. **必须先跑 `prepare_maaend.py`**：上游管线的所有移动端补丁（task 标注、OpenGame、
#    TouchMove 中和、培养舱 ROI/提取链兜底等）都在这个脚本里，按名字打进
#    `upstream/maaend/assets` 的工作树。CI 在 gradle 之前显式跑它
#    （`.github/workflows/build-apk.yml`）；**本地直接 gradle 会拿到没打补丁的管线**——
#    那种包能装、能跑，但行为与发布包不一致，调试结论会假。
# 2. **必须跑 `setup_maa_framework.py`**：MaaFramework 的 13 个 native `.so`
#    （`libMaaFramework.so` 等，约 225 MB）不在 git 里，由这个脚本从 GitHub Release
#    下载并铺进 `app/src/main/jniLibs/<abi>/`。**漏了它打出的包会缺 `libMaaFramework.so`**：
#    能装、能开 UI，但框架加载失败（`MAA_LOAD_FAIL UnsatisfiedLinkError`）、
#    任何任务都以 `NOT_RUN` 立刻收场——昨天就踩了这个坑。
# 3. **固定 JDK 17**：与 CI 对齐（actions/setup-java 用 17）。工具链位置见下。
# 4. **固定 SDK / Gradle 家目录**：都放移动硬盘（系统盘空间紧张）。
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── 工具链位置（移动硬盘）───────────────────────────────────────────────
JDK_HOME="${MAAEND_JDK_HOME:-/Volumes/mac第三磁盘/AndroidStudio/JDK/temurin17/Contents/Home}"
ANDROID_SDK="${MAAEND_ANDROID_SDK:-/Volumes/mac第三磁盘/AndroidStudio/SDK}"

if [ ! -x "$JDK_HOME/bin/java" ]; then
  echo "找不到 JDK：$JDK_HOME（用 MAAEND_JDK_HOME 覆盖）" >&2
  exit 1
fi
if [ ! -d "$ANDROID_SDK/platforms" ]; then
  echo "找不到 Android SDK：$ANDROID_SDK（用 MAAEND_ANDROID_SDK 覆盖）" >&2
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
# Gradle 依赖缓存：~/.gradle 已软链到 /Volumes/mac第三磁盘/codes/.gradle-home
# （如果换机器，把 GRADLE_USER_HOME 指到移动硬盘上的目录即可）

VARIANT="${1:-debug}"
cd "$REPO_ROOT"

echo "== [1/3] 打上游补丁（prepare_maaend.py，与 CI 同一步）=="
python3 scripts/prepare_maaend.py

echo "== [2/3] 铺 MaaFramework native 库（setup_maa_framework.py，与 CI 同一步）=="
# 有 gh 登录就带上 token，避免 API 限流；没有也能跑（脚本会退化为匿名请求）
MAAFW_TOKEN="$(env -u http_proxy -u https_proxy gh auth token 2>/dev/null || true)"
if [ -n "$MAAFW_TOKEN" ]; then
  GITHUB_TOKEN="$MAAFW_TOKEN" python3 scripts/setup_maa_framework.py --abi arm64-v8a
else
  python3 scripts/setup_maa_framework.py --abi arm64-v8a
fi

echo "== [3/3] gradle assemble${VARIANT} =="
case "$VARIANT" in
  debug)   ./gradlew assembleDebug ;;
  release) ./gradlew assembleRelease ;;
  *) echo "未知变体：$VARIANT（只支持 debug / release）" >&2; exit 2 ;;
esac

echo
echo "== 产物 =="
find app/build/outputs/apk -name "*.apk" -newermt "-10 minutes" -print
