# 质鉴诚监控 (CameraStream)

**质鉴诚监控** 是一款 Android 无线摄像头流媒体应用，通过 LAN 将手机摄像头实时画面传输到 PC 端，支持多摄像头切换、手动曝光控制、硬件编码等一系列专业功能。

---

## 功能特性

- 📷 **实时摄像头流** — 基于 Camera2 API 捕获预览画面，通过 WebSocket 逐帧推流
- 🔄 **多摄像头支持** — 支持物理多摄（广角/长焦/微距）及逻辑摄像头切换
- 🎥 **硬件编码** — 使用 MediaCodec 进行 H.264 / H.265 硬件编码，支持 VBR 动态码率
- 🌐 **LAN 自动发现** — UDP 广播发现（端口 8057），无需手动输入 IP
- 🔦 **手电筒** — 前后摄像头闪光灯/Torch 控制
- 🪞 **镜像翻转** — 预览画面水平镜像
- ✨ **美颜模式** — 内置美颜滤镜
- 📱 **横竖屏自适应** — AutoFitTextureView 自动适配比例与旋转
- 🎛️ **手动相机控制**：
  - ISO / 曝光补偿 / 快门速度
  - 手动对焦（MF）与触摸对焦
  - 变焦（平滑缩放）
  - 白平衡预设
- 🔌 **自动重连** — WebSocket 连接断开后自动恢复
- 🌙 **熄屏模式** — 关闭屏幕后继续推流，节省电量
- ⚙️ **设置页面** — 动态选择摄像头、分辨率、帧率、编码器类型

---

## 项目结构

```
mobile/
├── app/
│   └── src/main/java/com/hngm/camerastream/
│       ├── MainActivity.kt          # 主界面 — 预览、控制、推流、设备发现
│       ├── CameraCapture.kt         # Camera2 封装 — 会话管理、拍照、对焦、测光
│       ├── VideoEncoder.kt          # MediaCodec 硬件编码器
│       ├── WebSocketClient.kt       # OkHttp WebSocket 客户端
│       ├── DiscoveryClient.kt       # LAN UDP 服务发现
│       ├── ClientBeacon.kt          # 周期性 UDP 广播宣告
│       ├── SettingsActivity.kt      # 设置界面
│       └── AutoFitTextureView.kt    # 自适应预览视图
├── ivcam-ui/                        # UI 设计稿与界面截图
├── build.gradle.kts                 # 根构建脚本
├── settings.gradle.kts              # Gradle 设置
└── gradle/                          # Gradle Wrapper
```

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 |
| 最低 Android | 7.0 (API 24) |
| 目标 Android | 14 (API 34) |
| 相机 API | Camera2 |
| 编码 | MediaCodec (H.264 / H.265) |
| 传输 | OkHttp WebSocket (二进制帧) |
| 发现 | UDP Socket (端口 8057) |
| 异步 | Kotlin Coroutines |
| UI | Material Components + AndroidX |

---

## 快速开始

### 构建

```bash
# 克隆仓库
git clone https://github.com/YOUR_USERNAME/CameraStream.git
cd CameraStream

# 使用 Gradle Wrapper 构建
./gradlew assembleDebug
```

### 运行

1. 在 PC 端运行配套接收服务（监听 WebSocket 连接）
2. 手机与 PC 处于同一局域网
3. 打开应用 → 自动发现 PC 接收端 → 点击连接开始推流
4. 或在 PC 端手动填写手机 IP 进行连接

### 前置条件

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

---

## 配套服务

本应用需要 PC 端接收程序配合使用，接收 WebSocket 视频流并进行解码显示。相关接收端代码请参阅对应仓库。

---

## 截图

| 纵向预览 | 横向预览 | 设置 |
|---------|---------|------|
| ![纵向预览](ivcam-ui/手机app纵向预览界面UI.jpg) | ![横向预览](ivcam-ui/手机app横向预览界面UI.jpg) | ![设置](ivcam-ui/设置-高级.jpg) |

---

## 推荐仓库名

| 建议 | 说明 |
|------|------|
| **CameraStream** | 简洁，与 Gradle `rootProject.name` 一致，推荐 |
| `android-camera-stream` | 带前缀，便于搜索发现 |
| `phone-ip-camera` | 功能导向，突出"手机变 IP 摄像头" |

## 推荐描述

> **English:**
> An Android app that turns your phone into a wireless IP camera. Streams real-time camera preview to a PC over LAN via WebSocket, with Camera2 manual controls, hardware H.264/H.265 encoding, multi-camera support, and automatic device discovery.
>
> **中文：**
> 一款将手机变身为无线 IP 摄像头的 Android 应用。通过 LAN 局域网基于 WebSocket 实时推流手机摄像头画面到 PC 端，支持 Camera2 手动控制、H.264/H.265 硬件编码、多摄像头切换和自动设备发现。

---

## 上传到 GitHub 需要什么

要将此项目上传到 GitHub，你需要提供以下信息：

| 需要提供 | 说明 |
|---------|------|
| **① GitHub 用户名或组织名** | 例如 `your-username` 或 `your-org` |
| **② 仓库名称** | 建议用 `CameraStream`（见上方推荐） |
| **③ 可见性** | `public`（公开）或 `private`（私有） |
| **④ GitHub Token 或个人账号** | 用于认证。可选方式：<br>- 在 GitHub 创建 Personal Access Token (classic, 勾选 `repo` 权限)<br>- 或安装 `gh` CLI 并登录 `gh auth login` |
| **⑤ 接收端代码地址（可选）** | 如果有 PC 端配套代码仓库，可作为依赖说明 |

**确认上述信息后，我可以帮你：**

1. 在 GitHub 上创建远程仓库
2. 关联本地仓库并推送代码
3. 如有需要，配置 GitHub Actions 用于 CI 构建

> 注意：首次推送后，`ivcam-ui/` 目录下的 UI 设计稿（JPG）会一并上传。如果这些是敏感设计素材，可以在推送前添加 `.gitignore` 忽略该目录。

---

## License

```
MIT License — 详见 LICENSE 文件（如未提供则无）
```
