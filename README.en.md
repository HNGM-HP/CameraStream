# CameraStream

[![Platform](https://img.shields.io/badge/Android%207+-3DDC84?logo=android&logoColor=white)]()
[![Kotlin](https://img.shields.io/badge/Kotlin%201.9.20-7F52FF?logo=kotlin&logoColor=white)]()
[![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)]()
[![License](https://img.shields.io/badge/License-GPL%203.0-blue)](LICENSE)

**CameraStream** is an Android wireless IP camera application that streams real-time camera preview from your phone to a PC over **LAN** via **WebSocket**. It features multi-camera switching, hardware-accelerated encoding, and manual camera controls.

> 📖 [中文 README](README.md)

---

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Architecture](#architecture)
- [Configuration](#configuration)
- [License](#license)

---

## Features

### Core

| Feature | Description |
|---------|-------------|
| **Real-time Streaming** | Camera2 preview → MediaCodec hardware encoder → WebSocket binary frames |
| **Multi-Camera** | Supports physical sub-cameras (wide / telephoto / macro) and logical cameras |
| **Hardware Encoding** | H.264 / H.265 encoding with VBR, hardware-accelerated |
| **LAN Auto-Discovery** | UDP broadcast (port 8057) discovers the PC receiver automatically |
| **Auto-Reconnect** | Automatically restores WebSocket connection on disconnect |
| **Screen-Off Mode** | Continues streaming with screen off to save power |

### Camera Controls

| Control | Description |
|---------|-------------|
| 🔦 **Torch** | Front / rear flashlight toggle |
| 🪞 **Mirror** | Horizontal preview mirroring |
| ✨ **Beauty Mode** | Built-in beauty filter |
| 🔍 **Focus** | Tap-to-focus / manual focus (MF) / continuous auto-focus |
| 🔄 **Zoom** | Smooth pinch-to-zoom (digital / optical) |
| 🌡️ **White Balance** | Auto / incandescent / daylight / fluorescent / cloudy |
| ⏱ **Shutter Speed** | Manual shutter duration control |
| 💡 **ISO** | Manual sensitivity adjustment |
| 📐 **EV Compensation** | Fine-grained exposure value tuning |
| 🎯 **Metering Regions** | Per-region AE / AF / AWB metering |

### Others

- ✅ Pinch-to-zoom gesture
- ✅ Auto / manual connection modes
- ✅ Dynamic resolution, frame rate, and encoder selection
- ✅ Portrait / landscape auto-adaptation (AutoFitTextureView)

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Kotlin 1.9.20 |
| **Min / Target SDK** | API 24 (Android 7.0) / API 34 (Android 14) |
| **Build** | Android Gradle Plugin 8.2.0, JDK 17 |
| **Camera** | Camera2 API (multi-camera, physical sub-cameras) |
| **Encoding** | MediaCodec (H.264 / H.265, hardware accelerated) |
| **Transport** | OkHttp WebSocket (binary frames) |
| **Discovery** | UDP Socket (port 8057) |
| **Async** | Kotlin Coroutines |
| **UI** | Material Components, AndroidX Preference |

---

## Quick Start

### Prerequisites

- Android Studio Hedgehog (2023.1.1+) or later
- JDK 17
- Android SDK 34

### Build

```bash
git clone https://github.com/HNGM-HP/CameraStream.git
cd CameraStream
./gradlew assembleDebug
```

The debug APK will be generated at `app/build/outputs/apk/debug/`.

### Run

1. **PC side**: Run the companion receiver application (listens for WebSocket connections, decodes and displays the stream)
2. **Phone side**: Ensure your phone and PC are on the same LAN → open the app → auto-discovery finds the PC → tap to connect
3. **Manual connection**: Enter the PC's IP address directly in the settings screen

> The companion PC receiver code is in a separate repository.

---

## Project Structure

```
CameraStream/
├── app/
│   └── src/main/java/com/hngm/camerastream/
│       ├── MainActivity.kt           # Main screen — preview, controls, streaming, discovery
│       ├── CameraCapture.kt          # Camera2 wrapper — session, focus, metering, multi-camera
│       ├── VideoEncoder.kt           # MediaCodec hardware encoder
│       ├── WebSocketClient.kt        # OkHttp WebSocket client
│       ├── DiscoveryClient.kt        # UDP LAN service discovery
│       ├── ClientBeacon.kt           # Periodic UDP beacon (5s interval)
│       ├── SettingsActivity.kt       # Dynamic settings screen
│       └── AutoFitTextureView.kt     # Adaptive TextureView with transform pipeline
├── build.gradle.kts                  # Root build script
├── settings.gradle.kts               # Gradle settings
└── gradle/                           # Gradle wrapper
```

---

## Architecture

### Data Flow

```
Camera2 Sensor
    ↓
ImageReader / CaptureRequest
    ↓
MediaCodec (H.264 / H.265)
    ↓ (encoded frames)
WebSocket Binary Frame
    ↓
─── LAN ───→
    ↓
PC Receiver (decode & display)
```

### Module Relationships

```
MainActivity
├── AutoFitTextureView    ← Preview rendering / mirror transform
├── CameraCapture         ← Camera2 session / focus / metering
├── VideoEncoder          ← MediaCodec encode pipeline
├── WebSocketClient       ← OkHttp streaming
├── DiscoveryClient       ← UDP service discovery
├── ClientBeacon          ← Periodic UDP presence announcment
└── SettingsActivity      ← Parameter configuration
```

### Design Highlights

- **Frame-level push** rather than file-based streaming for minimal latency
- **Coroutine-based async** for non-blocking network I/O
- **Dynamic capability enumeration** via CameraManager to adapt to device hardware
- **AutoFitTextureView** handles rotation, mirroring, and aspect-ratio adjustment on the GPU

---

## Configuration

| Parameter | Options | Description |
|-----------|---------|-------------|
| Resolution | Auto / 4K / 1080p / 720p / 480p | Capture resolution |
| Frame Rate | 15 / 24 / 30 / 60 fps | Encoding frame rate |
| Encoder | H.264 / H.265 | Hardware codec selection |
| Bitrate | 1–50 Mbps | Video bitrate (VBR) |
| Discovery Port | 8057 (default) | UDP broadcast port |

---

## License

[GNU General Public License v3.0](LICENSE)

```
CameraStream
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
