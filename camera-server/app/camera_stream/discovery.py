"""UDP beacon for bidirectional LAN discovery between server and Android clients.

Server: listens on UDP 8057, responds to "discover" broadcasts, also sends
periodic "server_hello" broadcasts so clients can find the server passively.
"""

from __future__ import annotations

import asyncio
import json
import logging
import socket
import time

logger = logging.getLogger(__name__)

DISCOVERY_PORT = 8057
BEACON_INTERVAL = 5.0


class DiscoveryService:
    def __init__(self, host: str = "", ws_port: int = 8055) -> None:
        self.host = host
        self.ws_port = ws_port
        self._clients: dict[str, dict] = {}
        self._task: asyncio.Task | None = None
        self._transport: asyncio.DatagramTransport | None = None

    @property
    def discovered_clients(self) -> list[dict]:
        return list(self._clients.values())

    def _beacon_json(self) -> bytes:
        return json.dumps({
            "type": "server_hello",
            "ws_port": self.ws_port,
        }, separators=(",", ":")).encode()

    async def start(self) -> None:
        loop = asyncio.get_running_loop()

        class BeaconProto(asyncio.DatagramProtocol):
            def __init__(self, outer: DiscoveryService) -> None:
                self.outer = outer

            def connection_made(self, transport: asyncio.DatagramTransport) -> None:
                self.outer._transport = transport

            def datagram_received(self, data: bytes, addr: tuple) -> None:
                self.outer._on_datagram(data, addr[0])

            def error_received(self, exc: Exception) -> None:
                logger.debug("discovery udp error: %s", exc)

        try:
            transport, _proto = await loop.create_datagram_endpoint(
                lambda: BeaconProto(self),
                local_addr=("0.0.0.0", DISCOVERY_PORT),
                allow_broadcast=True,
            )
            # Enable broadcast for sending
            sock = transport.get_extra_info("socket")
            if sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        except OSError:
            # If broadcast socket fails, try without broadcast
            transport, _proto = await loop.create_datagram_endpoint(
                lambda: BeaconProto(self),
                local_addr=("0.0.0.0", DISCOVERY_PORT),
            )
        self._transport = transport

        self._task = asyncio.create_task(self._beacon_loop())

    async def _beacon_loop(self) -> None:
        data = self._beacon_json()
        while True:
            try:
                if self._transport:
                    self._transport.sendto(data, ("255.255.255.255", DISCOVERY_PORT))
            except Exception:
                logger.debug("beacon send failed", exc_info=True)
            await asyncio.sleep(BEACON_INTERVAL)

    def _on_datagram(self, data: bytes, src_ip: str) -> None:
        try:
            msg = json.loads(data)
        except json.JSONDecodeError:
            return

        msg_type = msg.get("type", "")

        if msg_type == "discover":
            # Client is actively scanning — respond directly
            if self._transport:
                self._transport.sendto(self._beacon_json(), (src_ip, DISCOVERY_PORT))
            logger.debug("discovery: responded to discover from %s", src_ip)

        elif msg_type == "client_hello":
            device_id = msg.get("device_id", "")
            device_name = msg.get("device_name", "")
            self._clients[device_id] = {
                "device_id": device_id,
                "device_name": device_name,
                "ip": src_ip,
                "last_seen": time.time(),
            }
            logger.info("discovery: client_hello from %s (%s) @ %s", device_name, device_id, src_ip)

    def stop(self) -> None:
        if self._task:
            self._task.cancel()
        if self._transport:
            try:
                self._transport.close()
            except Exception:
                pass
