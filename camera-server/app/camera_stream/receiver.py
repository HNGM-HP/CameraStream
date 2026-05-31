from __future__ import annotations

import asyncio
import queue
import threading
import time
from collections import defaultdict, deque
from collections.abc import AsyncIterator, Callable

from .types import StreamClient, StreamClientState

NAL_START_CODE = b"\x00\x00\x00\x01"
MAX_QUEUE_SIZE = 120
RING_BUFFER_SIZE = 90  # ~3 s at 30 fps for snapshots and IDR hunting
H264_SPS = 7
H264_PPS = 8
H264_IDR = 5
H264_NON_IDR = 1  # Coded slice of a non-IDR picture


class CameraStreamReceiver:
  """Per-camera H.264 frame buffer.

  Dual-path delivery:
    - asyncio.Queue for async consumers (WebSocket handler, MJPEG preview)
    - threading.Queue for sync consumers (RecorderManager ffmpeg feeder)
    - Ring buffer for snapshot/peek without consuming
  """

  def __init__(self) -> None:
    self._clients: dict[str, StreamClient] = {}
    self._aliases: dict[str, str] = {}  # device_id -> db_camera_id
    self._generations: dict[str, int] = {}  # camera_id → generation counter for race-free disconnect
    self._queues: dict[str, asyncio.Queue[bytes]] = defaultdict(
      lambda: asyncio.Queue(maxsize=MAX_QUEUE_SIZE)
    )
    self._sync_queues: dict[str, queue.Queue[bytes]] = defaultdict(
      lambda: queue.Queue(maxsize=MAX_QUEUE_SIZE)
    )
    self._sync_consumers: dict[str, list[queue.Queue[bytes]]] = defaultdict(list)
    self._ring_buffers: dict[str, deque[bytes]] = defaultdict(
      lambda: deque(maxlen=RING_BUFFER_SIZE)
    )
    self._codec_headers: dict[str, bytes] = {}
    self._listeners: list[Callable[[str, bytes], None]] = []

  def register_alias(self, device_id: str, db_camera_id: str) -> None:
    """Map a device_id to DB camera_id so recorder can find frames by DB id."""
    self._aliases[device_id] = db_camera_id

  def next_generation(self, camera_id: str) -> int:
    """Bump and return the connection generation for race-free disconnect handling."""
    gen = self._generations.get(camera_id, 0) + 1
    self._generations[camera_id] = gen
    return gen

  def generation(self, camera_id: str) -> int:
    return self._generations.get(camera_id, 0)

  @property
  def clients(self) -> dict[str, StreamClient]:
    return dict(self._clients)

  def active_clients(self) -> list[StreamClient]:
    return [
      c for c in self._clients.values()
      if c.state in (StreamClientState.CONNECTED, StreamClientState.STREAMING)
    ]

  def register(
    self,
    camera_id: str,
    resolution: str = "1920x1080",
    fps: int = 30,
    device_name: str = "",
    ip_address: str = "",
    codec: str = "h264",
    capabilities: dict[str, object] | None = None,
    current_settings: dict[str, object] | None = None,
  ) -> StreamClient:
    client = StreamClient(
      camera_id=camera_id,
      device_name=device_name,
      ip_address=ip_address,
      resolution=resolution,
      fps=fps,
      codec=codec,
      capabilities=capabilities or {},
      current_settings=current_settings or {},
      state=StreamClientState.CONNECTED,
    )
    self._clients[camera_id] = client
    return client

  def update_current_settings(
    self,
    camera_id: str,
    settings: dict[str, object],
    *,
    resolution: str | None = None,
    fps: int | None = None,
    codec: str | None = None,
  ) -> None:
    client = self._clients.get(camera_id)
    if client is None:
      return
    client.current_settings.update(settings)
    if resolution:
      client.resolution = resolution
    if fps:
      client.fps = fps
    if codec:
      client.codec = codec

  def unregister(self, camera_id: str) -> None:
    self._clients.pop(camera_id, None)
    self._queues.pop(camera_id, None)
    self._sync_queues.pop(camera_id, None)
    self._sync_consumers.pop(camera_id, None)
    self._ring_buffers.pop(camera_id, None)
    self._codec_headers.pop(camera_id, None)

  def mark_disconnected(self, camera_id: str, generation: int | None = None) -> None:
    """Mark a client as disconnected without removing its state record.

    Clears the codec header so a reconnecting client starts with fresh SPS/PPS.
    If generation is provided, only clears state when it matches — prevents a
    race where the old WebSocket's finally block overwrites a reconnected client.
    """
    if generation is not None and self._generations.get(camera_id, 0) != generation:
      return  # Newer connection already active; skip cleanup

    client = self._clients.get(camera_id)
    if client:
      client.state = StreamClientState.DISCONNECTED
    self._codec_headers.pop(camera_id, None)
    alias = self._aliases.get(camera_id)
    if alias:
      self._codec_headers.pop(alias, None)

  async def feed_frame(self, camera_id: str, data: bytes) -> None:
    """Push a raw NAL unit (no start-code) into all buffers.

    If the camera_id is a device_id with an alias (DB camera id), the frame
    is also placed into the alias queues so RecorderManager can consume it.
    """
    client = self._clients.get(camera_id)
    if client is None:
      return
    now = time.time()
    if not client.first_frame_at:
      client.first_frame_at = now
    client.last_frame_at = now
    client.frame_count += 1
    if client.state == StreamClientState.CONNECTED:
      client.state = StreamClientState.STREAMING

    annex_b = _normalize_annex_b(data)
    queues_to_fill = [camera_id]

    alias = self._aliases.get(camera_id)
    if alias:
      queues_to_fill.append(alias)

    for qid in queues_to_fill:
      if _contains_h264_parameter_set(annex_b):
        # Accumulate SPS+PPS — they may arrive as separate NAL units from
        # Android MediaCodec. Append instead of overwriting so ffmpeg gets
        # a complete parameter set for decoder initialisation.
        old = self._codec_headers.get(qid)
        self._codec_headers[qid] = (old + annex_b) if old else annex_b

      self._put_async(self._queues[qid], annex_b)
      self._put_sync(self._sync_queues[qid], annex_b)

      for consumer in list(self._sync_consumers[qid]):
        self._put_sync(consumer, annex_b)

      # Ring buffer for snapshots / peek
      self._ring_buffers[qid].append(annex_b)

    # Notify listeners only once with the original camera_id
    for listener in self._listeners:
      try:
        listener(camera_id, annex_b)
      except Exception:
        pass

  async def read_frames(self, camera_id: str) -> AsyncIterator[bytes]:
    """Async generator yielding Annex B frames."""
    aq = self._queues[camera_id]
    while camera_id in self._clients:
      try:
        data = await asyncio.wait_for(aq.get(), timeout=5.0)
        yield data
      except asyncio.TimeoutError:
        if camera_id not in self._clients:
          break

  def get_frame_sync(self, camera_id: str, timeout: float = 5.0) -> bytes:
    """Blocking read for RecorderManager feeder thread."""
    return self._sync_queues[camera_id].get(timeout=timeout)

  def create_sync_consumer(self, camera_id: str, include_header: bool = True) -> queue.Queue[bytes]:
    consumer: queue.Queue[bytes] = queue.Queue(maxsize=MAX_QUEUE_SIZE)
    if include_header:
      header = self.peek_codec_header(camera_id)
      if header:
        self._put_sync(consumer, header)
    self._sync_consumers[camera_id].append(consumer)
    return consumer

  def remove_sync_consumer(self, camera_id: str, consumer: queue.Queue[bytes]) -> None:
    consumers = self._sync_consumers.get(camera_id)
    if not consumers:
      return
    try:
      consumers.remove(consumer)
    except ValueError:
      pass

  def peek_recent_frame(self, camera_id: str) -> bytes | None:
    """Return the most recent frame without consuming it (for snapshots)."""
    rb = self._ring_buffers.get(camera_id)
    if rb:
      return rb[-1]
    return None

  def peek_codec_header(self, camera_id: str) -> bytes | None:
    header = self._codec_headers.get(camera_id)
    if header:
      return header
    # Also check aliases (mirrors wait_for_codec_header)
    for alias_src, alias_dst in list(self._aliases.items()):
      if alias_dst == camera_id:
        header = self._codec_headers.get(alias_src)
        if header:
          return header
    return None

  def peek_idr_frame(self, camera_id: str) -> bytes | None:
    """Return the most recent IDR frame from the ring buffer."""
    rb = self._ring_buffers.get(camera_id)
    if not rb:
      return None
    for frame in reversed(list(rb)):
      if _contains_idr(frame):
        return frame
    return None

  def wait_for_idr(self, camera_id: str, timeout: float = 6.0) -> bytes | None:
    """Block until an IDR frame arrives or timeout.

    Used by mobile preview/recording to guarantee ffmpeg has a keyframe
    after the SPS/PPS header for decoder initialisation.
    """
    deadline = time.time() + timeout
    # First check ring buffer for a recent IDR (fast path)
    while time.time() < deadline:
      idr = self.peek_idr_frame(camera_id)
      if idr:
        return idr
      # Also check alias
      for alias_src, alias_dst in list(self._aliases.items()):
        if alias_dst == camera_id:
          idr = self.peek_idr_frame(alias_src)
          if idr:
            return idr
      time.sleep(0.15)
    return None

  def wait_for_codec_header(self, camera_id: str, timeout: float = 3.0) -> bytes | None:
    """Block until a codec header (SPS/PPS) arrives, or timeout.

    Used by mobile preview/recording to avoid starting ffmpeg without
    valid parameter sets.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
      header = self._codec_headers.get(camera_id)
      if header:
        return header
      # Also check alias
      for alias_src, alias_dst in list(self._aliases.items()):
        if alias_dst == camera_id:
          header = self._codec_headers.get(alias_src)
          if header:
            return header
      time.sleep(0.1)
    return None

  def on_frame(self, callback: Callable[[str, bytes], None]) -> None:
    self._listeners.append(callback)

  def _put_async(self, aq: asyncio.Queue[bytes], data: bytes) -> None:
    if aq.full():
      try:
        aq.get_nowait()
      except asyncio.QueueEmpty:
        pass
    aq.put_nowait(data)

  def _put_sync(self, sq: queue.Queue[bytes], data: bytes) -> None:
    if sq.full():
      try:
        sq.get_nowait()
      except queue.Empty:
        pass
    sq.put(data)


def _normalize_annex_b(data: bytes) -> bytes:
  if _has_start_code(data):
    return data

  converted = _try_avcc_to_annex_b(data)
  if converted:
    return converted

  return NAL_START_CODE + data


def _has_start_code(data: bytes) -> bool:
  return data.startswith(b"\x00\x00\x00\x01") or data.startswith(b"\x00\x00\x01")


def _try_avcc_to_annex_b(data: bytes) -> bytes | None:
  offset = 0
  output = bytearray()
  size = len(data)
  while offset + 4 <= size:
    nal_size = int.from_bytes(data[offset:offset + 4], "big")
    offset += 4
    if nal_size <= 0 or offset + nal_size > size:
      return None
    output.extend(NAL_START_CODE)
    output.extend(data[offset:offset + nal_size])
    offset += nal_size
  if offset != size or not output:
    return None
  return bytes(output)


def _contains_h264_parameter_set(data: bytes) -> bool:
  for nal in _iter_annex_b_nals(data):
    if nal:
      nal_type = nal[0] & 0x1F
      if nal_type in (H264_SPS, H264_PPS):
        return True
  return False


def _contains_idr(data: bytes) -> bool:
  for nal in _iter_annex_b_nals(data):
    if nal:
      nal_type = nal[0] & 0x1F
      if nal_type == H264_IDR:
        return True
  return False


def _iter_annex_b_nals(data: bytes) -> list[bytes]:
  starts: list[tuple[int, int]] = []
  index = 0
  while index < len(data) - 3:
    if data[index:index + 4] == NAL_START_CODE:
      starts.append((index, 4))
      index += 4
    elif data[index:index + 3] == b"\x00\x00\x01":
      starts.append((index, 3))
      index += 3
    else:
      index += 1

  if not starts:
    return [data]

  nals: list[bytes] = []
  for pos, (start, prefix_len) in enumerate(starts):
    nal_start = start + prefix_len
    nal_end = starts[pos + 1][0] if pos + 1 < len(starts) else len(data)
    if nal_start < nal_end:
      nals.append(data[nal_start:nal_end])
  return nals
