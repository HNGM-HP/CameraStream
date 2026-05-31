# CameraStream — 质鉴诚监控

[![Platform](https://img.shields.io/badge/Android%207+-3DDC84?logo=android&logoColor=white)]()
[![Kotlin](https://img.shields.io/badge/Kotlin%201.9.20-7F52FF?logo=kotlin&logoColor=white)]()
[![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)]()
[![License](https://img.shields.io/badge/License-GPL%203.0-blue)](LICENSE)

**CameraStream** 是一款 Android 无线摄像头流媒体应用，通过 **LAN 局域网** 将手机摄像头实时画面以 **WebSocket** 协议推流到 PC 端，支持多摄像头切换、硬件编码、手动曝光控制等专业级功能。

> 📖 [English README](README.en.md)

---

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [架构概览](#架构概览)
- [配置参数](#配置参数)
- [License](#license)

---

## 功能特性

### 核心功能

| 功能 | 说明 |
|------|------|
| **实时推流** | Camera2 捕获预览帧 → MediaCodec 硬件编码 → WebSocket 二进制帧传输 |
| **多摄像头** | 支持物理多摄（广角 / 长焦 / 微距）及逻辑摄像头 |
| **硬件编码** | H.264 / H.265 编码，VBR 动态码率，由设备硬件加速 |
| **LAN 自动发现** | UDP 广播（端口 8057）自动发现 PC 接收端，无需手动输入 IP |
| **自动重连** | WebSocket 断开后自动恢复连接 |
| **熄屏推流** | 关闭屏幕继续推流，降低功耗 |

### 相机控制

| 控制项 | 说明 |
|--------|------|
| 🔦 **手电筒** | 前后摄像头 Torch / 闪光灯控制 |
| 🪞 **镜像翻转** | 预览画面水平镜像 |
| ✨ **美颜模式** | 内置美颜滤镜 |
| 🔍 **对焦** | 触摸对焦 / 手动对焦（MF）/ 连续自动对焦 |
| 🔄 **变焦** | 平滑缩放（数码 / 光学） |
| 🌡️ **白平衡** | 自动 / 白炽灯 / 日光 / 荧光灯 / 阴天 |
| ⏱ **快门速度** | 手动设置快门时间 |
| 💡 **ISO** | 感光度手动调节 |
| 📐 **曝光补偿** | EV 值精细调节 |
| 🎯 **测光区域** | 指定区域测光（AE / AF / AWB） |

### 其他

- ✅ 双指缩放
- ✅ 自动 / 手动连接切换
- ✅ 动态选择分辨率、帧率、编码器
- ✅ 横竖屏自适应（AutoFitTextureView 自动比例与旋转）

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **语言** | Kotlin 1.9.20 |
| **最低 / 目标 SDK** | API 24 (Android 7.0) / API 34 (Android 14) |
| **构建** | Android Gradle Plugin 8.2.0, JDK 17 |
| **相机** | Camera2 API（多摄、物理子摄像头） |
| **编码** | MediaCodec（H.264 / H.265，硬件加速） |
| **传输** | OkHttp WebSocket（二进制帧） |
| **发现** | UDP Socket（端口 8057） |
| **异步** | Kotlin Coroutines |
| **UI** | Material Components, AndroidX Preference |

---

## 快速开始

### 前置条件

- Android Studio Hedgehog (2023.1.1+) 或更新版本
- JDK 17
- Android SDK 34

### 构建

```bash
git clone https://github.com/HNGM-HP/CameraStream.git
cd CameraStream
./gradlew assembleDebug
```

APK 生成路径：`app/build/outputs/apk/debug/`

### 运行

1. **PC 端**：运行配套接收服务（监听 WebSocket 连接、解码显示画面）
2. **手机端**：手机与 PC 处于同一局域网 → 打开应用 → 自动发现 PC → 点击连接
3. **手动连接**：可在设置页面直接输入 PC IP 地址

> 配套 PC 接收端代码请见相关仓库。

---

## 项目结构

```
CameraStream/
├── app/
│   └── src/main/java/com/hngm/camerastream/
│       ├── MainActivity.kt           # 主界面 — 预览 / 控制 / 推流 / 发现
│       ├── CameraCapture.kt          # Camera2 封装 — 会话 / 对焦 / 测光 / 多摄
│       ├── VideoEncoder.kt           # MediaCodec 硬件编码器
│       ├── WebSocketClient.kt        # OkHttp WebSocket 客户端
│       ├── DiscoveryClient.kt        # UDP LAN 服务发现
│       ├── ClientBeacon.kt           # 周期性 UDP 宣告（5 秒间隔）
│       ├── SettingsActivity.kt       # 动态设置界面
│       └── AutoFitTextureView.kt     # 自适应 TextureView 变换管线
├── build.gradle.kts                  # 根构建脚本
├── settings.gradle.kts               # Gradle 设置
└── gradle/                           # Gradle Wrapper
```

---

## 架构概览

### 数据流

```
Camera2 Sensor
    ↓
ImageReader / CaptureRequest
    ↓
MediaCodec (H.264 / H.265)
    ↓ (编码帧)
WebSocket 二进制帧
    ↓
─── LAN ───→
    ↓
PC 接收端 (解码 & 显示)
```

### 模块关系

```
MainActivity
├── AutoFitTextureView    ← 预览渲染 / 镜像变换
├── CameraCapture         ← Camera2 会话 / 对焦 / 测光
├── VideoEncoder          ← MediaCodec 编码管线
├── WebSocketClient       ← OkHttp 推流
├── DiscoveryClient       ← UDP 服务发现
├── ClientBeacon          ← UDP 周期性宣告
└── SettingsActivity      ← 参数配置
```

### 关键设计点

- **逐帧推送**而非基于文件的流式传输，最大限度降低延迟
- **Coroutine 异步**无阻塞网络 I/O
- **动态能力枚举**通过 CameraManager 适配设备硬件
- **AutoFitTextureView**在 GPU 上处理旋转、镜像和宽高比自适应

---

## 配置参数

| 参数 | 可选值 | 说明 |
|------|--------|------|
| 分辨率 | Auto / 4K / 1080p / 720p / 480p | 采集分辨率 |
| 帧率 | 15 / 24 / 30 / 60 fps | 编码帧率 |
| 编码器 | H.264 / H.265 | 硬件编码器选择 |
| 码率 | 1–50 Mbps | 视频码率（VBR） |
| 发现端口 | 8057（默认） | UDP 广播端口 |

---

## License

[GNU General Public License v3.0](LICENSE)

```
CameraStream — 质鉴诚监控
Copyright (C) 2024 HNGM-HP

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.
```
