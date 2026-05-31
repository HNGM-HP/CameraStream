"""Mobile camera stream receiver — minimal FastAPI server.

Accepts H.264 video streams from Android Camera Stream app via WebSocket.
Provides UDP LAN discovery (port 8057) to help the app find this server.
"""

from __future__ import annotations

import contextlib
import logging
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .camera_stream import CameraStreamReceiver, CameraStreamServer, DiscoveryService

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger(__name__)

receiver = CameraStreamReceiver()
stream_server = CameraStreamServer(receiver)
discovery = DiscoveryService(ws_port=8055)


@contextlib.asynccontextmanager
async def lifespan(_app: FastAPI):
    await discovery.start()
    logger.info("UDP discovery beacon started on port 8057")
    yield
    discovery.stop()
    logger.info("UDP discovery beacon stopped")


app = FastAPI(title="Camera Stream Receiver", lifespan=lifespan)
app.include_router(stream_server.router)

static_root = Path(__file__).resolve().parent.parent / "static"
app.mount("/static", StaticFiles(directory=static_root), name="static")


@app.get("/")
def root() -> FileResponse:
    return FileResponse(static_root / "dist" / "index.html")


def _client_dict(c) -> dict:
    return {
        "camera_id": c.camera_id,
        "device_name": c.device_name,
        "ip_address": c.ip_address,
        "resolution": c.resolution,
        "fps": c.fps,
        "codec": c.codec,
        "state": c.state.value,
        "frame_count": c.frame_count,
        "last_frame_at": c.last_frame_at,
        "connected_at": c.connected_at,
    }


@app.get("/health")
def health() -> dict:
    clients = receiver.active_clients()
    return {
        "status": "ok",
        "clients": [_client_dict(c) for c in clients],
    }


@app.get("/api/stream/clients")
def stream_clients() -> dict:
    return {"clients": [_client_dict(c) for c in receiver.active_clients()]}
