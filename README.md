# MAAend-Meow (终末地小助手 Android 端)

<div align="center">

<img src="logo.png" alt="MAAend-Meow Logo" width="128" height="128">

# MAAend-Meow

**基于 [MaaFramework](https://github.com/MaaXYZ/MaaFramework) 与 [MaaEnd](https://github.com/MaaEnd/MaaEnd) 的《明日方舟：终末地》原生 Android 移动端自动化工具**

[![License](https://img.shields.io/badge/license-AGPL%203.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![MaaFramework](https://img.shields.io/badge/MaaFramework-v5.14.0-orange.svg)](https://github.com/MaaXYZ/MaaFramework)
[![MaaEnd](https://img.shields.io/badge/MaaEnd-v2-brightgreen.svg)](https://github.com/MaaEnd/MaaEnd)

</div>

---

> [!NOTE]
> **致敬上游与项目定位说明**：
> 
> 上游 [MaaEnd/MaaEnd](https://github.com/MaaEnd/MaaEnd) 在近期版本（v2.31.0+）中已经正式推出了官方的 MAA end 手机版（Android APK）实现，我们由衷向他们表示祝贺！🎉
> 
> **我们是上游仓库紧密协作的战友**。MAAend-Meow 作为基于 MaaFwApp 架构的衍生项目，致力于探索与实践更多移动端前沿特性——包括基于 Shizuku 的独立 **VirtualDisplay 后台静默运行**（前台不抢占屏幕）、大地图 **PathHeatmap 12~15x 算法加速**、全流程**远程调试与自动化运维工具链**，以及对**云·终末地**等场景的轻量化适配与性能优化。我们探索沉淀的成熟经验与修复，亦将积极交流并反哺上游生态。
> 
> ⚠️ **温馨提示**：本项目主要由个人业余维护，测试覆盖与资源有限，在兼容性、极端场景下可能存在比官方版本更多的问题或 Bug，敬请大家理解与包容。如遇问题欢迎在 Issues 交流反馈，或直接使用上游官方版。

---

## 🌟 项目特性

- **📱 原生 Android 纯净运行**：无需电脑投屏或模拟器，手机直接执行终末地全套自动化日常。
- **⚡ 超低资源消耗**：底层完全基于 C++ 实现的 MaaFramework 图像识别流水线，告别厚重容器，内存极省、响应迅速。
- **🎯 16 KB 内存分页对齐**：全量 Native 库（MaaFramework、MaaUtils 等）严格完成 16 KB 页面对齐，完美适配 Android 15+ 现代内核。
- **🛡️ 零 Root 提权（Shizuku 优先）**：优先推荐使用 [Shizuku](https://shizuku.rikka.app/) 免 Root 授权，通过系统底层虚拟显示器与注入通道稳定控制；Root 模式经过兼容性防护加固。
- **🌐 多镜像智能竞速更新**：内置国内多个 GitHub 镜像源测速竞速逻辑，自动选用延迟最低的镜像站拉取版本信息与下载安装包，解决网络受阻问题。
- **🔄 失败智能重试机制**：支持在常规流程结束后自动重试此前偶发失败的任务，大幅提升挂机稳定性。
- **🎮 自动化启动与多渠道服兼容**：支持官方服、Bilibili服、国际服等多版本终末地客户端的自动启动与对齐。
- **☁️ 纯云端 CI 编译**：配套完备的 GitHub Actions 流水线，构建全在云端完成，**无需在本地配置数 GB 的 Android SDK / NDK 编译工具**。

---

## 📱 运行要求

| 项目 | 要求 | 说明 |
| :--- | :--- | :--- |
| **操作系统** | Android 9.0（API 28）及以上 | 推荐 Android 11+，后台虚拟空间兼容性更佳 |
| **提权方案** | [Shizuku](https://shizuku.rikka.app/) 或 Root 权限 | 推荐使用 Shizuku，免 Root 即可直接调用系统底层 Native 控制器 |
| **设备架构** | `arm64-v8a`（主流真机） / `x86_64`（PC 模拟器） | 默认提供单架构轻量化包，安装包体积紧凑 |
| **目标游戏** | 《明日方舟：终末地》客户端 | 手机需安装与所选配置对应的游戏渠道服客户端 |

---

## 📌 上游组件锚定与基线

本项目核心组件版本与 Commit 同步记录在项目根目录的 [`UPSTREAM_VERSIONS.json`](UPSTREAM_VERSIONS.json) 中：

| 组件名称 | 来源仓库 | 当前锚定版本 / Commit | 作用说明 |
| :--- | :--- | :--- | :--- |
| **MaaEnd** | [MaaEnd/MaaEnd](https://github.com/MaaEnd/MaaEnd) | Commit [`9f90c71`](https://github.com/MaaEnd/MaaEnd/commit/9f90c719237d0bb1988197292296671851d54e8f)<br>(Branch: `v2`) | 业务资源仓库，提供 `interface.json`、Pipeline 流水线、图片模板、据点/基建等核心任务 |
| **MaaFramework** | [MaaXYZ/MaaFramework](https://github.com/MaaXYZ/MaaFramework) | Tag `v5.14.0`<br>(向前兼容 `v5.13.1`+) | 核心自动化框架动态库（`libMaaFramework.so`、`libMaaUtils.so`、`libMaaAndroidNativeControlUnit.so`） |
| **MaaCommonAssets (OCR)** | [MaaXYZ/MaaCommonAssets](https://github.com/MaaXYZ/MaaCommonAssets) | `OCR/ppocr_v6/small`<br>(ONNX 格式) | 轻量级 PP-OCR v6 模型文件（`det.onnx`, `rec.onnx`, `keys.txt`） |
| **Android 宿主工程** | [Aliothmoon/MaaFwApp](https://github.com/Aliothmoon/MaaFwApp) | 基于 MAA-Meow 通用架构分支 | 提供 Jetpack Compose 界面、Shizuku 进程代理、虚拟显示屏与多点触控控制器 |

---

## 🔄 自动化持续集成与更新

### 自动定时巡检
仓库内置了 [`.github/workflows/sync-upstream.yml`](.github/workflows/sync-upstream.yml) 巡检流水线，每天北京时间 **10:00** 与 **22:00** 自动检查上游是否有新提交。若检测到更新，会自动拉取最新资源并重新编译打包 APK。

### 手动打包
可在 GitHub Actions 页面直接运行 **Build MAAend-Meow APK** 流水线，选择分支即可自动构建出包。

---

## 📄 开源许可证与致谢

- 本项目基于 [AGPL-3.0 License](LICENSE) 开源。
- 感谢 [MaaEnd/MaaEnd](https://github.com/MaaEnd/MaaEnd) 提供的优质终末地自动化流水线。
- 感谢 [MaaXYZ/MaaFramework](https://github.com/MaaXYZ/MaaFramework) 与 [Aliothmoon/MaaFwApp](https://github.com/Aliothmoon/MaaFwApp) 提供的跨平台自动化框架与 Android 运行架构支持。

---

## 📬 联系方式

- 问题与建议：欢迎提交 [Issues](https://github.com/qi-1021/MAAend-Meow/issues)
- 开发者邮箱：[qiisme1021@icloud.com](mailto:qiisme1021@icloud.com)
