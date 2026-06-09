package com.hngm.camerastream

import android.graphics.SurfaceTexture
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs
import kotlin.math.min
import java.util.concurrent.Executors

class CameraCapture(
    private val cameraManager: CameraManager,
    private val cameraId: String = "0",
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 30
) {
    private data class CameraIdSpec(val logicalId: String, val physicalId: String?)

    private val cameraSpec = parseCameraId(cameraId)
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread = HandlerThread("Camera").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    var previewSurface: Surface? = null
    var encoderSurface: Surface? = null
    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var zoomRatio = 1f
    private var torchEnabled = false
    private var exposureCompensation = 0
    private var manualIso: Int? = null
    private var manualExposureTimeNs: Long? = null
    private var whiteBalanceKelvin: Int? = null
    private var focusDistance: Float? = null
    private var meteringPoint: Pair<Float, Float>? = null
    private var autoControlsLocked = false
    private var tapFocusActive = false
    private var encodedMirrorEnabled = false
    private var isRepeating = false

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (!tapFocusActive) return
            val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
            when (afState) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                    tapFocusActive = false
                    updateRepeatingRequest()
                }
            }
        }
    }

    val characteristics: CameraCharacteristics
        get() = cameraCharacteristicsFor(cameraManager, cameraId)

    val sensorOrientation: Int
        get() = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    val facing: Int
        get() = characteristics.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK

    val supportedResolutions: List<Size>
        get() {
            val configs = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: return emptyList()
            return configs.getOutputSizes(SurfaceTexture::class.java)
                .sortedByDescending { it.width * it.height }
        }

    fun open() {
        cameraManager.openCamera(cameraSpec.logicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                startCaptureSession()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                cameraDevice = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                cameraDevice = null
                onError?.invoke("Camera error: $error")
            }
        }, backgroundHandler)
    }

    private fun startCaptureSession() {
        val device = cameraDevice ?: return
        val surfaces = listOfNotNull(previewSurface, encoderSurface).toMutableList()
        if (surfaces.isEmpty()) {
            onError?.invoke("No output surfaces configured")
            return
        }

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                startRepeatingRequest(session)
                onReady?.invoke()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError?.invoke("Failed to configure capture session")
            }
        }

        if (shouldUseHighSpeedSession()) {
            device.createConstrainedHighSpeedCaptureSession(surfaces, sessionCallback, backgroundHandler)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = createOutputConfigurations(surfaces)
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                Executors.newSingleThreadExecutor(),
                sessionCallback
            )
            device.createCaptureSession(config)
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, sessionCallback, backgroundHandler)
        }
    }

    private fun createOutputConfigurations(surfaces: List<Surface>): List<OutputConfiguration> {
        return surfaces.map { surface ->
            OutputConfiguration(surface).apply {
                cameraSpec.physicalId?.let { setPhysicalCameraId(it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val mirrorMode = if (surface == encoderSurface && encodedMirrorEnabled) {
                        OutputConfiguration.MIRROR_MODE_H
                    } else {
                        OutputConfiguration.MIRROR_MODE_NONE
                    }
                    setMirrorMode(mirrorMode)
                }
            }
        }
    }

    private fun startRepeatingRequest(session: CameraCaptureSession) {
        val device = cameraDevice ?: return
        try {
            val template = if (session is CameraConstrainedHighSpeedCaptureSession) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }
            val builder = device.createCaptureRequest(template)
            previewSurface?.let { builder.addTarget(it) }
            encoderSurface?.let { builder.addTarget(it) }

            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectFpsRange())
            
            applySessionSettings(builder)

            val request = builder.build()
            if (session is CameraConstrainedHighSpeedCaptureSession) {
                val requests = session.createHighSpeedRequestList(request)
                session.setRepeatingBurst(requests, captureCallback, backgroundHandler)
            } else {
                session.setRepeatingRequest(request, captureCallback, backgroundHandler)
            }
            isRepeating = true
        } catch (e: Exception) {
            onError?.invoke("Failed to start preview: ${e.message}")
        }
    }

    private fun shouldUseHighSpeedSession(): Boolean {
        if (fps <= 60) return false
        if (cameraSpec.physicalId != null) return false
        val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        val size = Size(width, height)
        return try {
            configs.highSpeedVideoSizes.any { it == size } &&
                configs.getHighSpeedVideoFpsRangesFor(size).any { fps >= it.lower && fps <= it.upper }
        } catch (_: Exception) {
            false
        }
    }

    private fun applySessionSettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        applyMeteringRegions(builder)

        val manualExposure = manualIso != null || manualExposureTimeNs != null
        if (manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, resolvedIso())
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, resolvedExposureTimeNs())
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, autoControlsLocked)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
        }

        whiteBalanceKelvin?.let {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, selectWhiteBalanceMode(it))
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        } ?: run {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, autoControlsLocked)
        }

        focusDistance?.let {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        } ?: builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            if (autoControlsLocked || tapFocusActive) selectTriggeredAutoFocusMode() else selectAutoFocusMode()
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (rect != null) {
                val deltaX = (rect.width() / (2 * zoomRatio)).toInt()
                val deltaY = (rect.height() / (2 * zoomRatio)).toInt()
                val cropRect = android.graphics.Rect(rect.centerX() - deltaX, rect.centerY() - deltaY, rect.centerX() + deltaX, rect.centerY() + deltaY)
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
        }
    }

    fun setTorch(enabled: Boolean) {
        torchEnabled = enabled
        updateRepeatingRequest()
    }

    fun setExposureCompensation(value: Int) {
        val range = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        exposureCompensation = if (range != null) {
            value.coerceIn(range.lower, range.upper)
        } else {
            0
        }
        updateRepeatingRequest()
    }

    fun setIso(value: Int?) {
        manualIso = value?.let {
            val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (range != null) it.coerceIn(range.lower, range.upper) else it.coerceAtLeast(1)
        }
        updateRepeatingRequest()
    }

    fun setShutterSpeedNs(value: Long?) {
        manualExposureTimeNs = value?.let {
            val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (range != null) it.coerceIn(range.lower, range.upper) else it.coerceAtLeast(1L)
        }
        updateRepeatingRequest()
    }

    fun setWhiteBalanceKelvin(value: Int?) {
        whiteBalanceKelvin = value?.coerceIn(2500, 7500)
        updateRepeatingRequest()
    }

    fun setFocusDistance(value: Float?) {
        val maxDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        tapFocusActive = false
        focusDistance = value?.coerceIn(0f, maxDistance)
        updateRepeatingRequest()
    }

    fun setEncodedMirror(enabled: Boolean) {
        encodedMirrorEnabled = enabled
    }

    fun triggerAutoAdjust(normalizedX: Float, normalizedY: Float) {
        manualIso = null
        manualExposureTimeNs = null
        whiteBalanceKelvin = null
        focusDistance = null
        autoControlsLocked = false
        tapFocusActive = true
        meteringPoint = Pair(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
        updateRepeatingRequest()
        submitAutoTrigger()
    }

    fun setAutoControlsLocked(locked: Boolean) {
        autoControlsLocked = locked
        if (!locked) {
            tapFocusActive = false
        }
        updateRepeatingRequest()
    }

    fun setZoom(ratio: Float) {
        val zoomRange = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        val minZoomRatio = zoomRange?.lower ?: 1f
        val maxZoomRatio = zoomRange?.upper
            ?: characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: 10f
        zoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio.coerceAtLeast(minZoomRatio))
        updateRepeatingRequest()
    }

    fun getZoom(): Float = zoomRatio

    private fun resolvedIso(): Int {
        val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val fallback = 400
        return manualIso ?: if (range != null) fallback.coerceIn(range.lower, range.upper) else fallback
    }

    private fun resolvedExposureTimeNs(): Long {
        val range = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val fallback = (1_000_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
        return manualExposureTimeNs ?: if (range != null) fallback.coerceIn(range.lower, range.upper) else fallback
    }

    private fun selectWhiteBalanceMode(kelvin: Int): Int {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet()
            ?: return CaptureRequest.CONTROL_AWB_MODE_AUTO
        val preferred = when {
            kelvin < 3500 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            kelvin < 4500 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            kelvin < 6500 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            else -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        }
        return if (modes.contains(preferred)) preferred else CaptureRequest.CONTROL_AWB_MODE_AUTO
    }

    private fun selectAutoFocusMode(): Int {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet()
            ?: return CaptureRequest.CONTROL_AF_MODE_AUTO
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    private fun selectTriggeredAutoFocusMode(): Int {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet()
            ?: return CaptureRequest.CONTROL_AF_MODE_AUTO
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) -> CaptureRequest.CONTROL_AF_MODE_MACRO
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
    }

    private fun applyMeteringRegions(builder: CaptureRequest.Builder) {
        val region = meteringRectangle() ?: return
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        }
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
        if ((characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0) > 0) {
            builder.set(CaptureRequest.CONTROL_AWB_REGIONS, arrayOf(region))
        }
    }

    private fun meteringRectangle(): MeteringRectangle? {
        val point = meteringPoint ?: return null
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val meteringBounds = currentMeteringBounds(activeArray)
        val side = (min(activeArray.width(), activeArray.height()) * 0.12f).toInt().coerceAtLeast(1)
        val centerX = meteringBounds.left + (point.first * meteringBounds.width()).toInt()
        val centerY = meteringBounds.top + (point.second * meteringBounds.height()).toInt()
        val left = (centerX - side / 2).coerceIn(activeArray.left, activeArray.right - side)
        val top = (centerY - side / 2).coerceIn(activeArray.top, activeArray.bottom - side)
        return MeteringRectangle(
            Rect(left, top, left + side, top + side),
            MeteringRectangle.METERING_WEIGHT_MAX
        )
    }

    private fun currentMeteringBounds(activeArray: Rect): Rect {
        if (zoomRatio <= 1f) return activeArray
        val width = (activeArray.width() / zoomRatio).toInt().coerceIn(1, activeArray.width())
        val height = (activeArray.height() / zoomRatio).toInt().coerceIn(1, activeArray.height())
        val left = activeArray.centerX() - width / 2
        val top = activeArray.centerY() - height / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun submitAutoTrigger() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        try {
            val template = if (session is CameraConstrainedHighSpeedCaptureSession) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }

            val afMode = selectTriggeredAutoFocusMode()
            if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
                val cancelBuilder = createAutoTriggerRequestBuilder(device, template)
                cancelBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
                cancelBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                submitSingleCapture(session, cancelBuilder.build())
            }

            val builder = createAutoTriggerRequestBuilder(device, template)
            if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            submitSingleCapture(session, builder.build())
        } catch (e: Exception) {
            onError?.invoke("Failed to trigger auto controls: ${e.message}")
        }
    }

    private fun createAutoTriggerRequestBuilder(
        device: CameraDevice,
        template: Int
    ): CaptureRequest.Builder {
        val builder = device.createCaptureRequest(template)
        previewSurface?.let { builder.addTarget(it) }
        encoderSurface?.let { builder.addTarget(it) }
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectFpsRange())
        applySessionSettings(builder)
        return builder
    }

    private fun submitSingleCapture(session: CameraCaptureSession, request: CaptureRequest) {
        if (session is CameraConstrainedHighSpeedCaptureSession) {
            session.captureBurst(session.createHighSpeedRequestList(request), null, backgroundHandler)
        } else {
            session.capture(request, null, backgroundHandler)
        }
    }

    private fun updateRepeatingRequest() {
        val session = captureSession ?: return
        if (isRepeating) {
            startRepeatingRequest(session)
        }
    }

    private fun selectFpsRange(): Range<Int> {
        if (fps > 60) {
            val configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = Size(width, height)
            val highSpeedRange = try {
                configs?.getHighSpeedVideoFpsRangesFor(size)
                    ?.filter { fps >= it.lower && fps <= it.upper }
                    ?.minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { abs(it.upper - fps) })
            } catch (_: Exception) {
                null
            }
            if (highSpeedRange != null) return highSpeedRange
        }

        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(fps, fps)
        
        // Find ranges that include our target FPS
        val matches = ranges.filter { fps >= it.lower && fps <= it.upper }
        
        return if (matches.isNotEmpty()) {
            // Prefer fixed rate if available (e.g. [30, 30])
            matches.find { it.lower == it.upper } 
                ?: matches.maxByOrNull { it.upper } // Else highest upper bound
                ?: matches[0]
        } else {
            ranges.minByOrNull { Math.abs(it.upper - fps) } ?: Range(fps, fps)
        }
    }

    fun close() {
        try {
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        isRepeating = false
        backgroundThread.quitSafely()
    }

    companion object {
        data class CameraInfo(
            val id: String,
            val facing: Int,
            val name: String,
            val logicalId: String = id,
            val physicalId: String? = null
        )

        data class ResolutionOption(
            val label: String,
            val value: String,
            val size: Size
        )

        fun supportedResolutions(cameraManager: CameraManager, cameraId: String): List<Size> {
            return try {
                val chars = cameraCharacteristicsFor(cameraManager, cameraId)
                val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return emptyList()
                configs.getOutputSizes(SurfaceTexture::class.java)
                    .distinctBy { "${it.width}x${it.height}" }
                    .sortedByDescending { it.width * it.height }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun supportedResolutionOptions(cameraManager: CameraManager, cameraId: String): List<ResolutionOption> {
            val sizes = supportedResolutions(cameraManager, cameraId)
            val result = mutableListOf<ResolutionOption>()
            val standards = listOf(
                Triple("8K (4320P)", 7680, 4320),
                Triple("4K (2160P)", 3840, 2160),
                Triple("2K (1440P)", 2560, 1440),
                Triple("1080P", 1920, 1080),
                Triple("720P", 1280, 720),
                Triple("480P", 720, 480),
                Triple("360P", 640, 360)
            )

            for (std in standards) {
                val match = sizes.find { it.width == std.second && it.height == std.third }
                    ?: sizes.find { size ->
                        val aspect = size.width.toFloat() / size.height
                        val targetAspect = std.second.toFloat() / std.third
                        abs(aspect - targetAspect) < 0.1 &&
                            size.height >= std.third * 0.9 &&
                            size.height <= std.third * 1.1
                    }
                if (match != null) {
                    val value = "${match.width}x${match.height}"
                    if (result.none { it.value == value }) {
                        result.add(ResolutionOption("${std.first} ($value)", value, match))
                    }
                }
            }

            if (result.isEmpty()) {
                sizes.take(8).forEach { size ->
                    val value = "${size.width}x${size.height}"
                    result.add(ResolutionOption(value, value, size))
                }
            }
            return result
        }

        fun supportedFps(cameraManager: CameraManager, cameraId: String, size: Size? = null): List<Int> {
            val candidates = listOf(30, 50, 60, 120, 240)
            return try {
                val chars = cameraCharacteristicsFor(cameraManager, cameraId)
                val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?: return listOf(30)
                val maxRegularFps = size?.let {
                    val duration = configs?.getOutputMinFrameDuration(SurfaceTexture::class.java, it) ?: 0L
                    if (duration > 0L) (1_000_000_000L / duration).toInt() else null
                }
                val highSpeedFps = if (size != null) {
                    try {
                        configs?.getHighSpeedVideoFpsRangesFor(size)
                            ?.flatMap { range -> candidates.filter { it >= range.lower && it <= range.upper } }
                            ?.toSet()
                            ?: emptySet()
                    } catch (_: Exception) {
                        emptySet()
                    }
                } else {
                    emptySet()
                }
                val regularFps = candidates.filter { fps ->
                    fps <= 60 &&
                        (maxRegularFps == null || fps <= maxRegularFps) &&
                        ranges.any { fps >= it.lower && fps <= it.upper }
                }.toSet()
                candidates.filter { regularFps.contains(it) || highSpeedFps.contains(it) }
                    .ifEmpty { listOf(30) }
            } catch (_: Exception) {
                listOf(30)
            }
        }

        const val UNSUPPORTED_PHYSICAL_CAMERA_IDS_KEY = "unsupported_physical_camera_ids"

        fun enumerateCameras(
            cameraManager: CameraManager,
            hiddenCameraIds: Set<String> = emptySet()
        ): List<CameraInfo> {
            val result = mutableListOf<CameraInfo>()
            val seenIds = mutableSetOf<String>()
            cameraManager.cameraIdList.forEach { id ->
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK
                    if (seenIds.add(id)) {
                        result.add(CameraInfo(id, facing, cameraName(facing, id), id, null))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLogicalMultiCamera(chars)) {
                        val physicalCameras = chars.physicalCameraIds
                            .mapNotNull { physicalId ->
                                val physicalChars = try {
                                    cameraManager.getCameraCharacteristics(physicalId)
                                } catch (_: Exception) {
                                    null
                                }
                                physicalChars
                                    ?.takeIf { supportsSurfaceTextureOutput(it) }
                                    ?.let { Pair(physicalId, it) }
                            }
                            .sortedBy { minFocalLength(it.second) ?: Float.MAX_VALUE }
                        if (physicalCameras.size < 2) {
                            return@forEach
                        }
                        physicalCameras.forEachIndexed { index, pair ->
                            val physicalId = pair.first
                            val physicalChars = pair.second
                            val specId = "$id@$physicalId"
                            if (hiddenCameraIds.contains(specId)) return@forEachIndexed
                            if (!seenIds.add(specId)) return@forEachIndexed
                            val physicalFacing = physicalChars.get(CameraCharacteristics.LENS_FACING) ?: facing
                            val role = if (physicalCameras.size > 1 && index == 0) {
                                "\u5e7f\u89d2\u5019\u9009"
                            } else {
                                "\u7269\u7406\u955c\u5934"
                            }
                            result.add(
                                CameraInfo(
                                    specId,
                                    physicalFacing,
                                    "${cameraName(physicalFacing, physicalId)} ($role)",
                                    id,
                                    physicalId
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return result
        }

        private fun isLogicalMultiCamera(chars: CameraCharacteristics): Boolean {
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: return false
            return capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )
        }

        private fun supportsSurfaceTextureOutput(chars: CameraCharacteristics): Boolean {
            val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return false
            return configs.getOutputSizes(SurfaceTexture::class.java)?.isNotEmpty() == true
        }

        private fun parseCameraId(cameraId: String): CameraIdSpec {
            val parts = cameraId.split("@", limit = 2)
            return CameraIdSpec(parts[0], parts.getOrNull(1))
        }

        private fun cameraCharacteristicsFor(
            cameraManager: CameraManager,
            cameraId: String
        ): CameraCharacteristics {
            val spec = parseCameraId(cameraId)
            spec.physicalId?.let {
                try {
                    return cameraManager.getCameraCharacteristics(it)
                } catch (_: Exception) {
                }
            }
            return cameraManager.getCameraCharacteristics(spec.logicalId)
        }

        private fun minFocalLength(chars: CameraCharacteristics): Float? {
            return chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
        }

        private fun cameraName(facing: Int, id: String): String {
            return when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "\u540e\u7f6e $id"
                CameraCharacteristics.LENS_FACING_FRONT -> "\u524d\u7f6e $id"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "\u5916\u90e8 $id"
                else -> "\u6444\u50cf\u5934 $id"
            }
        }
    }
}
