# Camera Stream Receiver

Android Camera Stream App 配套接收端 — 接收手机端 H.264 视频流。

## 启动

```bash
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8055
```

## 手机端连接

| 协议 | 地址 | 说明 |
|------|------|------|
| WebSocket | `ws://&lt;服务器IP&gt;:8055/api/camera-stream/&lt;设备ID&gt;` | H.264 视频推流 |
| UDP 发现 | `:8057` | 广播 `discover` 包，服务器返回 `server_hello` |

## 页面

| 路径 | 内容 |
|------|------|
| `http://服务器IP:8055` | 实时推流状态页面 |
| `http://服务器IP:8055/health` | JSON 健康检查（含客户端列表） |
| `http://服务器IP:8055/docs` | OpenAPI 文档 |

## WebSocket 协议

**客户端 → 服务端：**

```json
{"type": "hello", "device_name": "Redmi Note 12", "resolution": "1920x1080", "fps": 30, "codec": "h264", "capabilities": {...}, "current_settings": {...}}
```

服务端返回 `{"type": "ready"}` 后，客户端以二进制帧发送 H.264 NAL 单元（无 start code 的纯 NAL，或 Annex B 格式）。

**服务端 → 客户端：**

```json
{"type": "ready"}
{"type": "settings_update", ...}
{"type": "error", "message": "..."}
```

## 项目结构

```
├── app/
│   ├── main.py                   # FastAPI 入口
│   └── camera_stream/
│       ├── server.py             # WebSocket 服务端
│       ├── receiver.py           # H.264 帧环形缓冲
│       ├── discovery.py          # UDP LAN 发现
│       └── types.py              # StreamClient 数据结构
├── static/dist/index.html        # 状态页面
└── requirements.txt
```
