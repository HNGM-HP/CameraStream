from __future__ import annotations

import json
import logging
import os
import socket
from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketState

from .receiver import CameraStreamReceiver

logger = logging.getLogger(__name__)

def _auto_detect_lan_ip(port: int) -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if ip and not ip.startswith("127."):
            return f"{ip}:{port}"
    except OSError:
        pass
    return ""

LAN_HOST = os.getenv("LAN_HOST") or _auto_detect_lan_ip(8055)


class CameraStreamServer:
    """Manages WebSocket connections from Android camera clients.

    Protocol:
      - Text frames:  JSON control messages (hello, start, stop)
      - Binary frames: H.264 NAL units (no start code)
    """

    def __init__(
        self,
        receiver: CameraStreamReceiver | None = None,
    ) -> None:
        self.receiver = receiver or CameraStreamReceiver()
        self._on_hello: Callable[[str, dict[str, Any]], bool] | None = None
        self._on_disconnect: Callable[[str], None] | None = None
        self._ws_connections: dict[str, WebSocket] = {}

    def on_client_hello(self, cb: Callable[[str, dict[str, Any]], bool]) -> None:
        self._on_hello = cb

    def on_client_disconnect(self, cb: Callable[[str], None]) -> None:
        self._on_disconnect = cb

    async def send_settings(self, camera_id: str, settings: dict) -> bool:
        """Push settings to a connected mobile client."""
        ws = self._ws_connections.get(camera_id)
        if ws is None:
            return False
        try:
            await ws.send_text(json.dumps({"type": "settings_update", **settings}))
            return True
        except Exception:
            return False

    def register_connection_alias(self, camera_id: str, alias: str) -> None:
        ws = self._ws_connections.get(camera_id)
        if ws is not None:
            self._ws_connections[alias] = ws

    @property
    def router(self) -> APIRouter:
        r = APIRouter()
        r.add_api_websocket_route("/api/camera-stream/{camera_id}", self._ws_handler)
        return r

    async def _ws_handler(self, ws: WebSocket, camera_id: str) -> None:
        await ws.accept()
        client_ip = ws.client.host if ws.client else ""

        # Close any existing connection for this camera_id (reconnect race)
        old_ws = self._ws_connections.get(camera_id)
        if old_ws is not None:
            logger.info("camera-stream replacing existing connection: camera_id=%s", camera_id)
            try:
                await old_ws.close(code=1001, reason="replaced")
            except Exception:
                pass

        # Bump generation so mark_disconnected from an old handler is a no-op
        gen = self.receiver.next_generation(camera_id)

        self._ws_connections[camera_id] = ws
        logger.info("camera-stream connected: camera_id=%s ip=%s gen=%d", camera_id, client_ip, gen)

        try:
            while ws.client_state == WebSocketState.CONNECTED:
                msg = await ws.receive()
                if msg["type"] == "websocket.receive":
                    if "text" in msg:
                        await self._on_control(camera_id, ws, msg["text"], client_ip)
                    elif "bytes" in msg:
                        await self.receiver.feed_frame(camera_id, msg["bytes"])
        except WebSocketDisconnect:
            pass
        except Exception:
            logger.exception("camera-stream error camera_id=%s", camera_id)
        finally:
            for key, conn in list(self._ws_connections.items()):
                if conn is ws:
                    self._ws_connections.pop(key, None)
            self.receiver.mark_disconnected(camera_id, generation=gen)
            if self._on_disconnect:
                try:
                    self._on_disconnect(camera_id)
                except Exception:
                    logger.exception("on_disconnect callback failed")
            logger.info("camera-stream disconnected: camera_id=%s gen=%d", camera_id, gen)

    async def _on_control(self, camera_id: str, ws: WebSocket, raw: str, client_ip: str = "") -> None:
        try:
            msg: dict[str, Any] = json.loads(raw)
        except json.JSONDecodeError:
            return

        msg_type = msg.get("type", "")

        if msg_type == "hello":
            resolution = msg.get("resolution", "1920x1080")
            fps = msg.get("fps", 30)
            device_name = msg.get("device_name", "")
            codec = str(msg.get("codec", "h264")).lower()
            if codec != "h264":
                await ws.send_text(json.dumps({"type": "error", "message": "unsupported codec"}))
                await ws.close()
                return
            raw_capabilities = msg.get("capabilities", {})
            capabilities = _clean_capabilities(raw_capabilities)
            current_settings = _clean_current_settings(msg.get("current_settings"))
            self.receiver.register(camera_id, resolution, fps, device_name, client_ip, codec, capabilities, current_settings)

            if self._on_hello:
                try:
                    accepted = self._on_hello(camera_id, msg)
                except Exception:
                    logger.exception("on_hello callback failed")
                    accepted = False
            else:
                accepted = True

            if not accepted:
                await ws.send_text(json.dumps({"type": "error", "message": "设备未注册，请在管理面板先添加此摄像头"}))
                await ws.close()
                return

            await ws.send_text(json.dumps({"type": "ready"}))

            logger.info(
                "camera-stream hello: camera_id=%s device=%s res=%s fps=%s",
                camera_id, device_name, resolution, fps,
            )

        elif msg_type == "start":
            logger.info("camera-stream start: camera_id=%s", camera_id)

        elif msg_type == "stop":
            logger.info("camera-stream stop: camera_id=%s", camera_id)

        elif msg_type == "settings_status":
            current_settings = _clean_current_settings(msg.get("current_settings"))
            resolution = _current_str(current_settings.get("video_size"))
            fps = _current_int(current_settings.get("video_fps"))
            codec = _current_str(current_settings.get("video_encoder"))
            self.receiver.update_current_settings(
                camera_id,
                current_settings,
                resolution=resolution,
                fps=fps,
                codec=codec,
            )


def _clean_capabilities(raw: Any) -> dict[str, object]:
    if not isinstance(raw, dict):
        return {}
    result: dict[str, object] = {}
    for key in ("video_sizes", "video_fps", "video_quality", "video_orientation", "video_encoder"):
        cleaned = _clean_string_list(raw.get(key))
        if cleaned:
            result[key] = cleaned
    fps_by_size = _clean_string_list_map(raw.get("video_fps_by_size"))
    if fps_by_size:
        result["video_fps_by_size"] = fps_by_size
    camera_controls = _clean_camera_controls(raw.get("camera_controls"))
    if camera_controls:
        result["camera_controls"] = camera_controls
    battery_percent = raw.get("battery_percent")
    if isinstance(battery_percent, (int, float)):
        result["battery_percent"] = max(0.0, min(100.0, float(battery_percent)))
    return result


def _clean_string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value if str(item).strip()]


def _clean_string_list_map(value: object) -> dict[str, list[str]]:
    if not isinstance(value, dict):
        return {}
    result: dict[str, list[str]] = {}
    for key, items in value.items():
        text_key = str(key).strip()
        cleaned = _clean_string_list(items)
        if text_key and cleaned:
            result[text_key] = cleaned
    return result


def _clean_camera_controls(raw: object) -> dict[str, object]:
    if not isinstance(raw, dict):
        return {}

    result: dict[str, object] = {}
    camera_ids = _clean_string_list(raw.get("camera_ids"))
    if camera_ids:
        result["camera_ids"] = camera_ids

    for key in ("torch_enabled", "beauty_enabled", "mirror_enabled", "screen_off"):
        value = raw.get(key)
        if isinstance(value, bool):
            result[key] = value

    for key in ("zoom_ratio", "exposure_compensation", "iso", "shutter_speed_ns", "white_balance_kelvin", "focus_distance"):
        value = _clean_numeric_range(raw.get(key))
        if value:
            result[key] = value

    return result


def _clean_current_settings(raw: object) -> dict[str, object]:
    if not isinstance(raw, dict):
        return {}

    result: dict[str, object] = {}
    string_keys = {
        "video_size", "video_quality", "video_orientation", "video_encoder",
        "video_format", "camera_id", "iso", "shutter_speed_ns",
        "white_balance_kelvin", "focus_distance",
    }
    bool_keys = {"torch_enabled", "beauty_enabled", "mirror_enabled", "screen_off"}
    numeric_keys = {"video_fps", "zoom_ratio", "exposure_compensation", "battery_percent"}

    for key in string_keys:
        value = raw.get(key)
        if isinstance(value, (str, int, float)):
            text = str(value).strip()
            if text:
                result[key] = text

    for key in bool_keys:
        value = raw.get(key)
        if isinstance(value, bool):
            result[key] = value

    for key in numeric_keys:
        value = raw.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            number = float(value)
            result[key] = max(0.0, min(100.0, number)) if key == "battery_percent" else number

    return result


def _current_str(value: object) -> str | None:
    if isinstance(value, str) and value.strip():
        return value.strip()
    return None


def _current_int(value: object) -> int | None:
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return None
    return None


def _clean_numeric_range(raw: object) -> dict[str, float]:
    if not isinstance(raw, dict):
        return {}
    min_value = raw.get("min")
    max_value = raw.get("max")
    if not isinstance(min_value, (int, float)) or not isinstance(max_value, (int, float)):
        return {}
    if float(max_value) < float(min_value):
        return {}
    return {"min": float(min_value), "max": float(max_value)}
