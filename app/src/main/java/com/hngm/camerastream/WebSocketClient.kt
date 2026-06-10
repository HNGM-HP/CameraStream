package com.hngm.camerastream

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketClient(
    private val serverUrl: String,
    private val cameraId: String,
    private val deviceName: String = "",
    private val resolution: String = "1920x1080",
    private val fps: Int = 30,
    private val codec: String = "h264",
    private val videoFormat: String = "default",
    private val orientation: String = "landscape",
    private val capabilities: JSONObject = JSONObject(),
    private val currentSettings: JSONObject = JSONObject()
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var connected = false

    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSettingsUpdate: ((Map<String, String>) -> Unit)? = null

    fun connect() {
        if (connected) return
        val url = buildWebSocketUrl()
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                onConnected?.invoke()
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("device_name", deviceName)
                    .put("resolution", resolution)
                    .put("fps", fps)
                    .put("codec", codec)
                    .put("video_format", videoFormat)
                    .put("orientation", orientation)
                    .put("capabilities", capabilities)
                    .put("current_settings", currentSettings)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    val type = json.optString("type", "")
                    when (type) {
                        "ready" -> onReady?.invoke()
                        "settings_update" -> {
                            val map = mutableMapOf<String, String>()
                            for (key in json.keys()) {
                                if (key != "type") map[key] = json.get(key).toString()
                            }
                            onSettingsUpdate?.invoke(map)
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                onDisconnected?.invoke("连接失败: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                onDisconnected?.invoke("连接关闭: $reason")
            }
        })
    }

    fun sendStart() {
        ws?.send("""{"type":"start"}""")
    }

    fun sendStop() {
        ws?.send("""{"type":"stop"}""")
    }

    fun sendSettingsStatus(settings: JSONObject) {
        if (!connected) return
        ws?.send(JSONObject().put("type", "settings_status").put("current_settings", settings).toString())
    }

    fun sendFrame(byteBuffer: java.nio.ByteBuffer, info: android.media.MediaCodec.BufferInfo) {
        if (!connected) return
        val bytes = ByteArray(info.size)
        byteBuffer.position(info.offset)
        byteBuffer.get(bytes, 0, info.size)
        ws?.send(bytes.toByteString(0, info.size))
    }

    fun isConnected(): Boolean = connected

    private fun buildWebSocketUrl(): String {
        val base = serverUrl.trim().trimEnd('/')
        val wsBase = when {
            base.startsWith("ws://", ignoreCase = true) -> base
            base.startsWith("wss://", ignoreCase = true) -> base
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substringAfter("http://")
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substringAfter("https://")
            else -> "ws://$base"
        }
        return "$wsBase/api/camera-stream/$cameraId"
    }

    fun disconnect() {
        ws?.close(1000, "user disconnect")
        ws = null
        connected = false
    }
}
