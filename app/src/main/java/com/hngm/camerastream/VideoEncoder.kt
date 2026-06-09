package com.hngm.camerastream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

class VideoEncoder(
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
    private val bitrate: Int = 2_000_000,
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    private val latencyMode: String = "balanced"
) {
    private var mediaCodec: MediaCodec? = null
    private var _inputSurface: Surface? = null
    private var isReleased = false

    var onNalUnit: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val inputSurface: Surface
        get() = _inputSurface ?: throw IllegalStateException("Codec not configured")

    fun configure() {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            applyLatencyMode(this)
        }

        mediaCodec = MediaCodec.createEncoderByType(mimeType)
        mediaCodec?.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // Not used — input comes from Surface
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
            ) {
                if (isReleased) return
                try {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null) {
                            onNalUnit?.invoke(buf, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    // Ignore errors during release
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onError?.invoke("Encoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // Format changed, ignore
            }
        })
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun applyLatencyMode(format: MediaFormat) {
        when (latencyMode) {
            "speed" -> {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    format.setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    format.setInteger(MediaFormat.KEY_OUTPUT_REORDER_DEPTH, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                }
            }
            "balanced" -> {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    format.setInteger(MediaFormat.KEY_LATENCY, 1)
                }
            }
        }
    }

    fun start() {
        val codec = mediaCodec ?: return
        _inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun requestKeyFrame() {
        try {
            mediaCodec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
            // Encoder may already be stopped during stream restart.
        }
    }

    fun stop() {
        mediaCodec?.stop()
    }

    fun release() {
        isReleased = true
        _inputSurface = null
        mediaCodec?.release()
        mediaCodec = null
    }
}
