package com.hngm.camerastream

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class DiscoveryClient(
    private val deviceId: String,
    private val deviceName: String,
    private val onFound: (host: String, port: Int) -> Unit
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val DISCOVERY_PORT = 8057
        const val DISCOVER_MSG = """{"type":"discover"}"""
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    broadcast = true
                }
                // Send discover broadcast
                sendBroadcast(DISCOVER_MSG)
                sendBroadcast(clientHelloMsg())
                // Listen for response
                val buf = ByteArray(256)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    withTimeout(8000) {
                        while (isActive) {
                            socket?.receive(packet)
                            val data = String(packet.data, 0, packet.length)
                            if (data.contains("server_hello")) {
                                val host = packet.address.hostAddress ?: continue
                                val port = extractPort(data)
                                if (port > 0) {
                                    onFound(host, port)
                                    return@withTimeout
                                }
                            }
                        }
                    }
                    // Timeout — send another broadcast
                    sendBroadcast(DISCOVER_MSG)
                    sendBroadcast(clientHelloMsg())
                }
            } catch (e: CancellationException) {
                // Normal stop
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendBroadcast(msg: String) {
        try {
            val data = msg.toByteArray()
            val packet = DatagramPacket(
                data, data.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            socket?.send(packet)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clientHelloMsg(): String {
        return JSONObject()
            .put("type", "client_hello")
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .toString()
    }

    private fun extractPort(json: String): Int {
        val regex = """"ws_port"\s*:\s*(\d+)""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun stop() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
