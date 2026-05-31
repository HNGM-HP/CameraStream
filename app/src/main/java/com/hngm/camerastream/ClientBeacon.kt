package com.hngm.camerastream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class ClientBeacon(
    private val deviceId: String,
    private val deviceName: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        val address = InetAddress.getByName("255.255.255.255")
                        while (isActive) {
                            val data = JSONObject()
                                .put("type", "client_hello")
                                .put("device_id", deviceId)
                                .put("device_name", deviceName)
                                .toString()
                                .toByteArray()
                            socket.send(DatagramPacket(data, data.size, address, DiscoveryClient.DISCOVERY_PORT))
                            delay(5000)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(5000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}
