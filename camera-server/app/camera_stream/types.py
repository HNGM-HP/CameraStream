from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum


class StreamClientState(Enum):
  CONNECTED = "connected"
  STREAMING = "streaming"
  DISCONNECTED = "disconnected"


@dataclass
class StreamClient:
  camera_id: str
  state: StreamClientState = StreamClientState.CONNECTED
  device_name: str = ""
  ip_address: str = ""
  resolution: str = "1920x1080"
  fps: int = 30
  codec: str = "h264"
  capabilities: dict[str, object] = field(default_factory=dict)
  current_settings: dict[str, object] = field(default_factory=dict)
  connected_at: float = field(default_factory=time.time)
  first_frame_at: float = 0.0
  last_frame_at: float = 0.0
  frame_count: int = 0
