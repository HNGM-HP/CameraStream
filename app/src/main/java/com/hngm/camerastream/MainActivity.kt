package com.hngm.camerastream

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private var cameraCapture: CameraCapture? = null
    private var videoEncoder: VideoEncoder? = null
    private var wsClient: WebSocketClient? = null

    private lateinit var surfacePreview: AutoFitTextureView
    private lateinit var searchLayout: View
    private lateinit var streamOverlay: View
    private lateinit var overlayStatus: TextView
    private lateinit var overlayServer: TextView
    private lateinit var overlayFrameRate: TextView
    private lateinit var btnDisconnect: ImageButton
    private lateinit var btnOverlaySettings: ImageButton
    private lateinit var btnBattery: ImageButton
    private lateinit var overlayBatteryLevel: TextView
    private lateinit var btnTorch: ImageButton
    private lateinit var btnBeauty: ImageButton
    private lateinit var btnMirror: ImageButton
    private lateinit var btnScreenOff: ImageButton
    private lateinit var topControlBar: View
    private lateinit var bottomControlBar: View
    private lateinit var controlShutter: TextView
    private lateinit var controlIso: TextView
    private lateinit var controlExposure: TextView
    private lateinit var controlWhiteBalance: TextView
    private lateinit var controlFocus: TextView
    private lateinit var controlZoom: TextView
    private lateinit var previewClosedHint: TextView
    private lateinit var screenOffLayer: View
    private lateinit var focusLockIndicator: View
    private lateinit var rootLayout: View
    private lateinit var textIpAddress: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnAdd: ImageButton
    private lateinit var btnHelp: ImageButton
    private lateinit var circleContainer: View

    private var currentCameraId = "0"
    private var activeCameraId = "0"
    private var currentCameraIndex = 0
    private var availableCameras: List<CameraCapture.Companion.CameraInfo> = emptyList()
    private var serverCameraId = ""
    private var serverHost = ""
    private var isStreaming = false
    private var isConnected = false
    private var isScanning = false
    private var cameraSessionStarted = false
    private var cameraSessionSuspended = false
    private var appInBackground = false
    private var reconnectOnResume = false
    private var discoveryClient: DiscoveryClient? = null
    private var clientBeacon: ClientBeacon? = null
    private var currentFps = 30
    private var currentCodec = "h264"
    private var currentResolution = "1280x720"
    private var activeVideoWidth = 1280
    private var activeVideoHeight = 720
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    
    private var isTorchOn = false
    private var isMirrorOn = false
    private var isBeautyOn = false
    private var screenOffRequested = false
    private var manualIso: Int? = null
    private var manualExposureTimeNs: Long? = null
    private var exposureCompensation = 0
    private var whiteBalanceKelvin: Int? = null
    private var focusDistance: Float? = null
    private var currentVideoFormat = "default"
    private var currentZoom = 1f
    private var suppressStatusPublish = false
    private lateinit var zoomGestureDetector: ScaleGestureDetector
    private lateinit var previewGestureDetector: android.view.GestureDetector
    private var wasMultiTouch = false
    private var activeControlPopup: android.widget.PopupWindow? = null
    private var autoParametersLocked = false
    private val overlayAutoHideRunnable = Runnable { hideOverlay() }

    companion object {
        private const val REQUEST_CAMERA = 1
        private const val TAG = "CameraStream"
    }

    private data class VideoConfig(
        val width: Int, // Target/Output width
        val height: Int, // Target/Output height
        val bufferWidth: Int, // Raw buffer width (from supported list)
        val bufferHeight: Int, // Raw buffer height (from supported list)
        val fps: Int,
        val bitrate: Int,
        val codec: String,
        val mimeType: String
    ) {
        val resolution: String
            get() = "${width}x${height}"
    }

    private data class CameraOrientationConfig(
        val sensorOrientationDegrees: Int,
        val facing: Int,
        val displayRotationDegrees: Int,
        val relativeRotationDegrees: Int,
        val rotateAndCropModes: String
    )

    private data class CameraControlRanges(
        val exposureTimeNs: android.util.Range<Long>?,
        val iso: android.util.Range<Int>?,
        val exposureCompensation: android.util.Range<Int>?,
        val whiteBalanceKelvin: android.util.Range<Int>,
        val focusDistance: android.util.Range<Float>?,
        val zoomRatio: android.util.Range<Float>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Sync orientation with preferences before setting content view
        applyRotationSetting()

        configureEdgeToEdgeWindow()

        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        surfacePreview = findViewById(R.id.surfacePreview)
        streamOverlay = findViewById(R.id.streamOverlay)
        overlayStatus = findViewById(R.id.overlayStatus)
        overlayServer = findViewById(R.id.overlayServer)
        overlayFrameRate = findViewById(R.id.overlayFrameRate)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnOverlaySettings = findViewById(R.id.btnOverlaySettings)
        btnBattery = findViewById(R.id.btnBattery)
        overlayBatteryLevel = findViewById(R.id.overlayBatteryLevel)
        btnTorch = findViewById(R.id.btnTorch)
        btnBeauty = findViewById(R.id.btnBeauty)
        btnMirror = findViewById(R.id.btnMirror)
        btnScreenOff = findViewById(R.id.btnScreenOff)
        topControlBar = findViewById(R.id.topControlBar)
        bottomControlBar = findViewById(R.id.bottomControlBar)
        controlShutter = findViewById(R.id.controlShutter)
        controlIso = findViewById(R.id.controlIso)
        controlExposure = findViewById(R.id.controlExposure)
        controlWhiteBalance = findViewById(R.id.controlWhiteBalance)
        controlFocus = findViewById(R.id.controlFocus)
        controlZoom = findViewById(R.id.controlZoom)
        previewClosedHint = findViewById(R.id.previewClosedHint)
        screenOffLayer = findViewById(R.id.screenOffLayer)
        focusLockIndicator = findViewById(R.id.focusLockIndicator)
        searchLayout = findViewById(R.id.searchLayout)
        textIpAddress = findViewById(R.id.textIpAddress)
        textStatus = findViewById(R.id.textStatus)
        btnSettings = findViewById(R.id.btnSettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnAdd = findViewById(R.id.btnAdd)
        btnHelp = findViewById(R.id.btnHelp)
        circleContainer = findViewById(R.id.circleContainer)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        applyOverlayInsets()

        availableCameras = CameraCapture.enumerateCameras(cameraManager, unsupportedPhysicalCameraIds())
        currentCameraIndex = availableCameras.indexOfFirst { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        if (currentCameraIndex < 0) currentCameraIndex = 0
        if (availableCameras.isNotEmpty()) {
            currentCameraId = availableCameras[currentCameraIndex].id
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        serverCameraId = prefs.getString("last_camera_id", generateDeviceId()) ?: generateDeviceId()
        currentVideoFormat = prefs.getString("video_format", "default") ?: "default"
        restartClientBeacon()

        textIpAddress.text = getLocalIpAddress()

        val breathingAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.breathing)
        circleContainer.startAnimation(breathingAnim)

        // Settings button in search UI
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnRefresh.setOnClickListener {
            textIpAddress.text = getLocalIpAddress()
            if (!isConnected) {
                tryAutoConnect()
            }
        }

        btnAdd.setOnClickListener {
            showConnectDialog()
        }

        btnHelp.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_content)
                .setPositiveButton("知道了", null)
                .show()
        }
        topControlBar.setOnClickListener { resetOverlayAutoHide() }
        bottomControlBar.setOnClickListener { resetOverlayAutoHide() }
        btnBattery.isEnabled = false

        zoomGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextZoom = currentZoom * detector.scaleFactor
                setZoomRatio(nextZoom)
                return true
            }

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                wasMultiTouch = true
                return true
            }
        })

        previewGestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (!wasMultiTouch && isConnected && !screenOffRequested) {
                    handlePreviewAutoAdjust(event.x, event.y)
                    if (streamOverlay.visibility == View.VISIBLE) resetOverlayAutoHide() else showOverlay()
                }
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (!wasMultiTouch && isConnected && !screenOffRequested) {
                    handlePreviewAutoLock(event.x, event.y)
                    resetOverlayAutoHide()
                }
            }
        })

        surfacePreview.setOnTouchListener { _, event ->
            handlePreviewTouch(event)
        }

        // Tap on preview closed hint to show preview+overlay
        previewClosedHint.setOnClickListener {
            if (isConnected) setScreenOffEnabled(false)
        }
        screenOffLayer.setOnClickListener {
            if (isConnected) setScreenOffEnabled(false)
        }

        streamOverlay.setOnTouchListener { _, event ->
            handlePreviewTouch(event)
        }

        // Overlay blank tap only refreshes the auto-hide timer.
        streamOverlay.setOnClickListener {
            resetOverlayAutoHide()
        }

        // Buttons inside overlay — consume click without closing overlay
        val btnSwitchCamera = findViewById<ImageButton>(R.id.btnSwitchCamera)
        btnSwitchCamera.setOnClickListener {
            resetOverlayAutoHide()
            switchCamera()
        }
        
        btnTorch.setOnClickListener {
            resetOverlayAutoHide()
            setTorchEnabled(!isTorchOn)
        }

        btnBeauty.setOnClickListener {
            resetOverlayAutoHide()
            setBeautyEnabled(!isBeautyOn)
        }

        btnMirror.setOnClickListener {
            resetOverlayAutoHide()
            setMirrorEnabled(!isMirrorOn)
        }

        btnScreenOff.setOnClickListener {
            resetOverlayAutoHide()
            setScreenOffEnabled(!screenOffRequested)
        }

        controlShutter.setOnClickListener { resetOverlayAutoHide(); showShutterSlider() }
        controlIso.setOnClickListener { resetOverlayAutoHide(); showIsoSlider() }
        controlExposure.setOnClickListener { resetOverlayAutoHide(); showExposureSlider() }
        controlWhiteBalance.setOnClickListener { resetOverlayAutoHide(); showWhiteBalanceSlider() }
        controlFocus.setOnClickListener { resetOverlayAutoHide(); showFocusSlider() }
        controlZoom.setOnClickListener { resetOverlayAutoHide(); showZoomSlider() }

        btnDisconnect.setOnClickListener {
            hideOverlay()
            stopStreaming()
        }
        btnOverlaySettings.setOnClickListener {
            resetOverlayAutoHide()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        updateOverlayState()

        surfacePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                updatePreviewTransform()
                if (isConnected && !cameraSessionStarted && !screenOffRequested) {
                    startCameraSession()
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                updatePreviewTransform()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                restartCameraSession()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        requestCameraPermission()

        if (prefs.getBoolean("auto_connect", true)) {
            tryAutoConnect()
        } else {
            textStatus.setText(R.string.manual_connect_hint)
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screenOffRequested) {
                    setScreenOffEnabled(false)
                } else if (streamOverlay.visibility == View.VISIBLE) {
                    hideOverlay()
                } else if (isStreaming || isConnected) {
                    stopStreaming()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!::cameraManager.isInitialized || !::surfacePreview.isInitialized) return
        appInBackground = false
        if (reconnectOnResume && serverHost.isNotBlank()) {
            reconnectOnResume = false
            startStreaming(serverHost)
            return
        }
        if (isConnected) {
            if (screenOffRequested) enterScreenOffWindowMode() else enterPreviewWindowMode()
        }
        val orientationChanged = applyRotationSetting()
        if (isConnected && videoEncoder != null && shouldRestartForCurrentSettings()) {
            restartActiveStream()
        } else if (isConnected && cameraSessionSuspended && !screenOffRequested) {
            resumeStreamFromBackground()
        } else if (isConnected && videoEncoder != null && !screenOffRequested && !cameraSessionStarted && surfacePreview.isAvailable) {
            startCameraSession()
        } else if (orientationChanged && isConnected && !screenOffRequested && surfacePreview.isAvailable) {
            restartCameraSession()
            startCameraSession()
        } else {
            updatePreviewTransform()
        }
    }

    override fun onPause() {
        if (::surfacePreview.isInitialized) {
            appInBackground = true
            activeControlPopup?.dismiss()
            if (isConnected && (cameraCapture != null || videoEncoder != null)) {
                cameraSessionSuspended = true
                wsClient?.sendStop()
                restartCameraSession()
                releaseVideoEncoder()
            }
        }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::surfacePreview.isInitialized) return
        surfacePreview.post {
            updatePreviewTransform()
            if (isConnected && !screenOffRequested && surfacePreview.isAvailable) {
                restartCameraSession()
                startCameraSession()
            }
        }
    }

    private fun applyRotationSetting(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val orientation = prefs.getString("video_orientation", "landscape")
        val targetOrientation = if (orientation == "portrait") {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val changed = requestedOrientation != targetOrientation
        requestedOrientation = targetOrientation
        return changed
    }
    private fun showOverlay() {
        if (screenOffRequested) return
        enterPreviewWindowMode()
        overlayStatus.text = "已连接到 $serverHost"
        overlayServer.text = serverHost
        overlayFrameRate.text = "${currentFps}fps | ${currentCodec.uppercase()} | $currentResolution"
        updateOverlayState()
        streamOverlay.visibility = View.VISIBLE
        scheduleOverlayAutoHide()
    }

    private fun hideOverlay() {
        cancelOverlayAutoHide()
        activeControlPopup?.dismiss()
        streamOverlay.visibility = View.GONE
    }

    private fun resetOverlayAutoHide() {
        if (streamOverlay.visibility == View.VISIBLE) {
            scheduleOverlayAutoHide()
        }
    }

    private fun scheduleOverlayAutoHide() {
        if (!::rootLayout.isInitialized) return
        rootLayout.removeCallbacks(overlayAutoHideRunnable)
        rootLayout.postDelayed(overlayAutoHideRunnable, 30_000L)
    }

    private fun cancelOverlayAutoHide() {
        if (!::rootLayout.isInitialized) return
        rootLayout.removeCallbacks(overlayAutoHideRunnable)
    }

    private fun applyOverlayInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val statusInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val cutoutInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val navigationInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            val topMargin = if (isConnected) {
                maxOf(statusInsets.top, cutoutInsets.top)
                    .let { (it + dp(4)).coerceAtLeast(dp(6)) }
                    .coerceAtMost(dp(36))
            } else {
                maxOf(statusInsets.top, cutoutInsets.top).coerceAtMost(dp(8))
            }
            (topControlBar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                params.gravity = android.view.Gravity.TOP
                params.topMargin = topMargin
                topControlBar.layoutParams = params
            }
            val bottomInset = navigationInsets.bottom.coerceAtMost(dp(48))
            (bottomControlBar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { params ->
                params.gravity = android.view.Gravity.BOTTOM
                params.bottomMargin = bottomInset
                bottomControlBar.layoutParams = params
            }
            topControlBar.setPadding(dp(8), 0, dp(8), 0)
            bottomControlBar.setPadding(dp(8), dp(5), dp(8), dp(8))
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(rootLayout)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun updatePreviewTransform() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fillPreview = prefs.getBoolean("fill_preview", true)
        
        val config = resolveVideoConfig()
        val orientationConfig = resolveCameraOrientationConfig()
        activeVideoWidth = config.width
        activeVideoHeight = config.height
        surfacePreview.configurePreviewTransform(
            config.bufferWidth,
            config.bufferHeight,
            orientationConfig.relativeRotationDegrees,
            orientationConfig.displayRotationDegrees,
            targetIsPortrait(),
            fillPreview,
            isMirrorOn
        )
    }

    private fun targetIsPortrait(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString("video_orientation", "landscape") == "portrait"
    }

    private fun resolveCameraOrientationConfig(): CameraOrientationConfig {
        val chars = try {
            cameraManager.getCameraCharacteristics(currentCameraId)
        } catch (_: Exception) {
            return CameraOrientationConfig(0, CameraCharacteristics.LENS_FACING_BACK, 0, 0, "unavailable")
        }
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        val displayRotationDegrees = currentDisplayRotationDegrees()
        val sign = if (facing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1
        val relativeRotationDegrees = (sensorOrientation - displayRotationDegrees * sign + 360) % 360
        return CameraOrientationConfig(
            sensorOrientation,
            facing,
            displayRotationDegrees,
            relativeRotationDegrees,
            availableRotateAndCropModes(chars)
        )
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotationDegrees(): Int {
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun availableRotateAndCropModes(chars: CameraCharacteristics): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "api<31"
        val modes = chars.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
            ?: return "unavailable"
        return modes.joinToString(prefix = "[", postfix = "]") { rotateAndCropModeName(it) }
    }

    private fun rotateAndCropModeName(mode: Int): String {
        return when (mode) {
            CameraMetadata.SCALER_ROTATE_AND_CROP_NONE -> "NONE"
            CameraMetadata.SCALER_ROTATE_AND_CROP_90 -> "90"
            CameraMetadata.SCALER_ROTATE_AND_CROP_180 -> "180"
            CameraMetadata.SCALER_ROTATE_AND_CROP_270 -> "270"
            CameraMetadata.SCALER_ROTATE_AND_CROP_AUTO -> "AUTO"
            else -> mode.toString()
        }
    }

    private fun facingName(facing: Int): String {
        return when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> facing.toString()
        }
    }

    private fun logCameraOrientation(config: VideoConfig, orientationConfig: CameraOrientationConfig) {
        Log.i(
            TAG,
            "cameraOrientation cameraId=$currentCameraId " +
                "facing=${facingName(orientationConfig.facing)} " +
                "sensor=${orientationConfig.sensorOrientationDegrees} " +
                "display=${orientationConfig.displayRotationDegrees} " +
                "relative=${orientationConfig.relativeRotationDegrees} " +
                "target=${if (targetIsPortrait()) "portrait" else "landscape"} " +
                "buffer=${config.bufferWidth}x${config.bufferHeight} " +
                "view=${surfacePreview.width}x${surfacePreview.height} " +
                "rotateAndCropModes=${orientationConfig.rotateAndCropModes}"
        )
    }

    private fun showPreview() {
        screenOffRequested = false
        exitScreenOffWindowMode()
        screenOffLayer.visibility = View.GONE
        streamOverlay.visibility = View.GONE
        previewClosedHint.visibility = View.GONE
        surfacePreview.visibility = View.VISIBLE
        surfacePreview.alpha = 1f
        surfacePreview.isEnabled = true
        if (isConnected && videoEncoder == null) {
            videoEncoder = createVideoEncoder(resolveVideoConfig())
        }
        if (!cameraSessionStarted && surfacePreview.isAvailable) {
            startCameraSession()
        }
    }

    private fun hidePreview() {
        activeControlPopup?.dismiss()
        cancelOverlayAutoHide()
        streamOverlay.visibility = View.GONE
        previewClosedHint.visibility = View.GONE
        surfacePreview.visibility = View.VISIBLE
        surfacePreview.alpha = 1f
        surfacePreview.isEnabled = false
        screenOffLayer.bringToFront()
        screenOffLayer.visibility = View.VISIBLE
        enterScreenOffWindowMode()
    }

    private fun configureEdgeToEdgeWindow() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#f5faff")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
    }

    private fun enterPreviewWindowMode() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#f5faff")
        androidx.core.view.WindowCompat.getInsetsController(window, rootLayout).apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        androidx.core.view.ViewCompat.requestApplyInsets(rootLayout)
    }

    private fun exitPreviewWindowMode() {
        androidx.core.view.WindowCompat.getInsetsController(window, rootLayout).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#f5faff")
        androidx.core.view.ViewCompat.requestApplyInsets(rootLayout)
    }

    private fun enterScreenOffWindowMode() {
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        androidx.core.view.WindowCompat.getInsetsController(window, rootLayout).apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitScreenOffWindowMode() {
        if (isConnected) {
            enterPreviewWindowMode()
        } else {
            exitPreviewWindowMode()
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (isConnected && prefs.getBoolean("keep_screen_on", true)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun resolveVideoConfig(): VideoConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Fix potential ClassCastException from old video_bitrate (String) to new (Int)
        try {
            prefs.getInt("video_bitrate_v2", 0)
        } catch (e: Exception) {
            prefs.edit().remove("video_bitrate_v2").apply()
        }
        val requestedCameraId = prefs.getString("camera_id", currentCameraId) ?: currentCameraId
        val selectedCameraId = if (availableCameras.any { it.id == requestedCameraId }) {
            requestedCameraId
        } else {
            availableCameras.getOrNull(currentCameraIndex)?.id
                ?: availableCameras.firstOrNull()?.id
                ?: "0"
        }
        if (selectedCameraId != requestedCameraId) {
            prefs.edit().putString("camera_id", selectedCameraId).apply()
        }
        if (selectedCameraId != currentCameraId) {
            currentCameraId = selectedCameraId
            currentCameraIndex = availableCameras.indexOfFirst { it.id == currentCameraId }.coerceAtLeast(0)
        }

        val supportedSizes = supportedVideoSizes()
        val requestedSize = prefs.getString("video_size", "1280x720") ?: "1280x720"
        val size = if (supportedSizes.contains(requestedSize)) requestedSize else supportedSizes.firstOrNull() ?: "1280x720"
        if (size != requestedSize) prefs.edit().putString("video_size", size).apply()

        val baseWidth = size.substringBefore("x").toIntOrNull() ?: 1280
        val baseHeight = size.substringAfter("x").toIntOrNull() ?: 720
        val selectedSize = android.util.Size(baseWidth, baseHeight)
        val supportedFps = CameraCapture.supportedFps(cameraManager, currentCameraId, selectedSize)
        val requestedFps = (prefs.getString("video_fps", "30") ?: "30").toIntOrNull() ?: 30
        val fps = if (supportedFps.contains(requestedFps)) requestedFps else supportedFps.firstOrNull() ?: 30
        if (fps != requestedFps) prefs.edit().putString("video_fps", fps.toString()).apply()

        val width = baseWidth
        val height = baseHeight

        val quality = prefs.getString("video_quality", "medium") ?: "medium"
        var bitrate = when (quality) {
            "high" -> 5_000_000
            "low" -> 1_000_000
            else -> 2_500_000
        }
        
        // Custom bitrate
        val customBitrateMbps = try {
            prefs.getInt("video_bitrate_v2", 0).toFloat()
        } catch (e: Exception) {
            0f
        }

        if (customBitrateMbps > 0) {
            bitrate = (customBitrateMbps * 1_000_000).toInt()
        }

        val codec = prefs.getString("video_encoder", "h264") ?: "h264"
        val mimeType = if (codec == "h265") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        return VideoConfig(width, height, baseWidth, baseHeight, fps, bitrate, codec, mimeType)
    }

    private fun supportedVideoSizes(): List<String> {
        return CameraCapture.supportedResolutionOptions(cameraManager, currentCameraId)
            .map { it.value }
            .distinct()
    }

    private fun selectedVideoSize(): android.util.Size {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val supportedSizes = supportedVideoSizes()
        val selected = prefs.getString("video_size", "1280x720") ?: "1280x720"
        val size = if (supportedSizes.contains(selected)) selected else supportedSizes.firstOrNull() ?: "1280x720"
        return sizeFromValue(size) ?: android.util.Size(1280, 720)
    }

    private fun sizeFromValue(size: String): android.util.Size? {
        val width = size.substringBefore("x").toIntOrNull() ?: 1280
        val height = size.substringAfter("x").toIntOrNull() ?: 720
        if (width <= 0 || height <= 0) return null
        return android.util.Size(width, height)
    }

    private fun parseBooleanSetting(value: String): Boolean {
        return value.equals("true", ignoreCase = true) || value == "1" || value.equals("on", ignoreCase = true)
    }

    private fun parseNullableIntSetting(value: String): Int? {
        return if (value.equals("auto", ignoreCase = true) || value.equals("default", ignoreCase = true)) {
            null
        } else {
            value.toIntOrNull()
        }
    }

    private fun parseNullableLongSetting(value: String): Long? {
        return if (value.equals("auto", ignoreCase = true) || value.equals("default", ignoreCase = true)) {
            null
        } else {
            value.toLongOrNull()
        }
    }

    private fun parseNullableFloatSetting(value: String): Float? {
        return if (value.equals("auto", ignoreCase = true) || value.equals("default", ignoreCase = true)) {
            null
        } else {
            value.toFloatOrNull()
        }
    }

    private fun unsupportedPhysicalCameraIds(): Set<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getStringSet(CameraCapture.UNSUPPORTED_PHYSICAL_CAMERA_IDS_KEY, emptySet())
            ?: emptySet()
    }

    private fun handleCameraCaptureError(cameraId: String, message: String) {
        if (!cameraId.contains("@")) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val hidden = unsupportedPhysicalCameraIds().toMutableSet()
        hidden.add(cameraId)
        availableCameras = CameraCapture.enumerateCameras(cameraManager, hidden)
        val fallbackCameraId = cameraId.substringBefore("@").takeIf { logicalId ->
            availableCameras.any { it.id == logicalId }
        } ?: availableCameras.firstOrNull()?.id ?: "0"
        prefs.edit()
            .putStringSet(CameraCapture.UNSUPPORTED_PHYSICAL_CAMERA_IDS_KEY, hidden)
            .putString("camera_id", fallbackCameraId)
            .apply()
        currentCameraId = fallbackCameraId
        currentCameraIndex = availableCameras.indexOfFirst { it.id == currentCameraId }.coerceAtLeast(0)
        restartCameraSession()
        if (isConnected && videoEncoder != null) {
            restartEncoder()
            if (!screenOffRequested && surfacePreview.isAvailable) {
                startCameraSession()
            }
        }
        publishCurrentSettings()
        Toast.makeText(this, "物理镜头不可用，已隐藏并切回逻辑摄像头", Toast.LENGTH_LONG).show()
    }

    private fun buildCapabilitiesJson(): JSONObject {
        val fpsValues = CameraCapture.supportedFps(cameraManager, currentCameraId, selectedVideoSize()).map { it.toString() }
        val encoders = mutableListOf("h264")
        if (isH265Supported()) encoders.add("h265")
        
        return JSONObject()
            .put("video_sizes", JSONArray(supportedVideoSizes()))
            .put("video_fps", JSONArray(fpsValues))
            .put("video_fps_by_size", supportedVideoFpsBySizeJson())
            .put("video_quality", JSONArray(listOf("high", "medium", "low")))
            .put("video_orientation", JSONArray(listOf("landscape", "portrait")))
            .put("video_encoder", JSONArray(encoders))
            .put("battery_percent", currentBatteryPercent() ?: JSONObject.NULL)
            .put("camera_controls", buildCameraControlsJson())
    }

    private fun supportedVideoFpsBySizeJson(): JSONObject {
        val result = JSONObject()
        for (sizeValue in supportedVideoSizes()) {
            val size = sizeFromValue(sizeValue) ?: continue
            val fpsValues = CameraCapture.supportedFps(cameraManager, currentCameraId, size)
                .map { it.toString() }
            result.put(sizeValue, JSONArray(fpsValues))
        }
        return result
    }

    private fun buildCurrentSettingsJson(): JSONObject {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val json = JSONObject()
            .put("video_size", currentResolution)
            .put("video_fps", currentFps)
            .put("video_quality", prefs.getString("video_quality", "medium") ?: "medium")
            .put("video_orientation", prefs.getString("video_orientation", "landscape") ?: "landscape")
            .put("video_encoder", currentCodec)
            .put("video_format", currentVideoFormat)
            .put("camera_id", currentCameraId)
            .put("torch_enabled", isTorchOn)
            .put("beauty_enabled", isBeautyOn)
            .put("mirror_enabled", isMirrorOn)
            .put("screen_off", screenOffRequested)
            .put("auto_parameters_locked", autoParametersLocked)
            .put("zoom_ratio", currentZoom.toDouble())
            .put("iso", manualIso?.toString() ?: "auto")
            .put("shutter_speed_ns", manualExposureTimeNs?.toString() ?: "auto")
            .put("exposure_compensation", exposureCompensation)
            .put("white_balance_kelvin", whiteBalanceKelvin?.toString() ?: "auto")
            .put("focus_distance", focusDistance?.toString() ?: "auto")
        currentBatteryPercent()?.let { json.put("battery_percent", it) }
        return json
    }

    private fun publishCurrentSettings() {
        if (!suppressStatusPublish && isConnected) {
            wsClient?.sendSettingsStatus(buildCurrentSettingsJson())
        }
    }

    private fun buildCameraControlsJson(): JSONObject {
        val chars = try {
            cameraManager.getCameraCharacteristics(currentCameraId)
        } catch (_: Exception) {
            return JSONObject()
        }
        val ranges = cameraControlRanges(chars)
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        return JSONObject()
            .put("camera_ids", JSONArray(availableCameras.map { it.id }))
            .put("camera_options", JSONArray(availableCameras.map { camera ->
                JSONObject()
                    .put("id", camera.id)
                    .put("name", camera.name)
                    .put("logical_id", camera.logicalId)
                    .put("physical_id", camera.physicalId ?: JSONObject.NULL)
            }))
            .put("torch_enabled", hasFlash)
            .put("beauty_enabled", true)
            .put("mirror_enabled", true)
            .put("screen_off", true)
            .put("zoom_ratio", floatRangeToJson(ranges.zoomRatio))
            .put("exposure_compensation", intRangeToJson(ranges.exposureCompensation))
            .put("iso", intRangeToJson(ranges.iso))
            .put("shutter_speed_ns", longRangeToJson(ranges.exposureTimeNs))
            .put("white_balance_kelvin", intRangeToJson(ranges.whiteBalanceKelvin))
            .put("focus_distance", floatRangeToJson(ranges.focusDistance))
    }

    private fun intRangeToJson(range: android.util.Range<Int>?): Any {
        return range?.let {
            JSONObject()
                .put("min", it.lower)
                .put("max", it.upper)
        } ?: JSONObject.NULL
    }

    private fun longRangeToJson(range: android.util.Range<Long>?): Any {
        return range?.let {
            JSONObject()
                .put("min", it.lower)
                .put("max", it.upper)
        } ?: JSONObject.NULL
    }

    private fun floatRangeToJson(range: android.util.Range<Float>?): Any {
        return range?.let {
            JSONObject()
                .put("min", it.lower.toDouble())
                .put("max", it.upper.toDouble())
        } ?: JSONObject.NULL
    }

    private fun cameraControlRanges(): CameraControlRanges {
        val chars = try {
            cameraManager.getCameraCharacteristics(currentCameraId)
        } catch (_: Exception) {
            return fallbackControlRanges()
        }
        return cameraControlRanges(chars)
    }

    private fun cameraControlRanges(chars: CameraCharacteristics): CameraControlRanges {
        val focusMax = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return CameraControlRanges(
            exposureTimeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureCompensation = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            whiteBalanceKelvin = android.util.Range(2500, 7500),
            focusDistance = if (focusMax > 0f) android.util.Range(0f, focusMax) else null,
            zoomRatio = zoomRatioRange(chars)
        )
    }

    private fun fallbackControlRanges(): CameraControlRanges {
        return CameraControlRanges(
            exposureTimeNs = null,
            iso = null,
            exposureCompensation = null,
            whiteBalanceKelvin = android.util.Range(2500, 7500),
            focusDistance = null,
            zoomRatio = android.util.Range(1f, 1f)
        )
    }

    private fun zoomRatioRange(chars: CameraCharacteristics): android.util.Range<Float> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                val lower = range.lower.coerceAtLeast(0.1f)
                val upper = range.upper.coerceAtLeast(lower)
                return android.util.Range(lower, upper)
            }
        }
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return android.util.Range(1f, maxZoom.coerceAtLeast(1f))
    }

    private fun isH265Supported(): Boolean {
        return try {
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                .codecInfos.any { it.isEncoder && it.supportedTypes.contains(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) }
        } catch (e: Exception) {
            false
        }
    }

    private fun restartEncoder() {
        val encoder = videoEncoder ?: return
        val config = resolveVideoConfig()
        activeVideoWidth = config.width
        activeVideoHeight = config.height
        currentResolution = config.resolution
        currentFps = config.fps
        currentCodec = config.codec
        activeCameraId = currentCameraId
        cameraSessionSuspended = false
        updatePreviewTransform()

        encoder.stop()
        encoder.release()

        videoEncoder = createVideoEncoder(config)
    }

    private fun createVideoEncoder(config: VideoConfig): VideoEncoder {
        return VideoEncoder(config.width, config.height, config.fps, config.bitrate, config.mimeType).apply {
            configure()
            onNalUnit = { buf, info -> wsClient?.sendFrame(buf, info) }
            onError = { msg -> runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() } }
            start()
        }
    }

    private fun releaseVideoEncoder() {
        try {
            videoEncoder?.stop()
        } catch (_: Exception) {
        }
        videoEncoder?.release()
        videoEncoder = null
    }

    private fun resumeStreamFromBackground() {
        val config = resolveVideoConfig()
        activeVideoWidth = config.width
        activeVideoHeight = config.height
        currentResolution = config.resolution
        currentFps = config.fps
        currentCodec = config.codec
        activeCameraId = currentCameraId
        cameraSessionSuspended = false
        if (videoEncoder == null) {
            videoEncoder = createVideoEncoder(config)
        }
        updatePreviewTransform()
        if (surfacePreview.isAvailable) {
            startCameraSession()
        }
        wsClient?.sendStart()
    }

    private fun shouldRestartForCurrentSettings(): Boolean {
        val config = resolveVideoConfig()
        return config.resolution != currentResolution ||
            config.fps != currentFps ||
            config.codec != currentCodec ||
            currentCameraId != activeCameraId
    }

    private fun restartActiveStream() {
        if (!isConnected || videoEncoder == null) {
            updatePreviewTransform()
            return
        }
        restartCameraSession()
        restartEncoder()
        if (!screenOffRequested && surfacePreview.isAvailable) {
            startCameraSession()
        }
    }

    private fun restartCameraSession() {
        cameraCapture?.close()
        cameraCapture = null
        cameraSessionStarted = false
        isStreaming = false
    }

    private fun setTorchEnabled(enabled: Boolean) {
        isTorchOn = enabled
        cameraCapture?.setTorch(enabled)
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setBeautyEnabled(enabled: Boolean) {
        isBeautyOn = enabled
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setMirrorEnabled(enabled: Boolean) {
        isMirrorOn = enabled
        updatePreviewTransform()
        if (isConnected && videoEncoder != null) {
            restartCameraSession()
            if (surfacePreview.isAvailable) {
                startCameraSession()
            }
        }
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setScreenOffEnabled(enabled: Boolean) {
        screenOffRequested = enabled
        if (enabled) {
            hidePreview()
        } else {
            showPreview()
        }
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun handlePreviewTouch(event: MotionEvent): Boolean {
        if (streamOverlay.visibility == View.VISIBLE && event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetOverlayAutoHide()
        }
        zoomGestureDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            wasMultiTouch = true
        }
        if (!wasMultiTouch) {
            previewGestureDetector.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> wasMultiTouch = false
        }
        return true
    }

    private fun handlePreviewAutoAdjust(x: Float, y: Float) {
        val point = normalizedPreviewPoint(x, y)
        showFocusLockIndicator(x, y, locked = false)
        autoParametersLocked = false
        manualIso = null
        manualExposureTimeNs = null
        whiteBalanceKelvin = null
        focusDistance = null
        cameraCapture?.setAutoControlsLocked(false)
        cameraCapture?.triggerAutoAdjust(point.first, point.second)
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun handlePreviewAutoLock(x: Float, y: Float) {
        handlePreviewAutoAdjust(x, y)
        surfacePreview.postDelayed({
            if (isConnected && !screenOffRequested) {
                setAutoParameterLock(true)
                showFocusLockIndicator(x, y, locked = true)
            }
        }, 600L)
    }

    private fun showFocusLockIndicator(x: Float, y: Float, locked: Boolean) {
        if (!::focusLockIndicator.isInitialized || !::rootLayout.isInitialized) return
        val size = dp(if (locked) 78 else 72)
        val rootWidth = rootLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rootHeight = rootLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val params = (focusLockIndicator.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(size, size)
        params.width = size
        params.height = size
        params.leftMargin = (x.toInt() - size / 2).coerceIn(0, (rootWidth - size).coerceAtLeast(0))
        params.topMargin = (y.toInt() - size / 2).coerceIn(0, (rootHeight - size).coerceAtLeast(0))
        focusLockIndicator.layoutParams = params
        focusLockIndicator.bringToFront()
        if (::screenOffLayer.isInitialized && screenOffLayer.visibility == View.VISIBLE) {
            screenOffLayer.bringToFront()
        }
        focusLockIndicator.animate().cancel()
        focusLockIndicator.visibility = View.VISIBLE
        focusLockIndicator.alpha = 1f
        focusLockIndicator.scaleX = if (locked) 1.45f else 1.3f
        focusLockIndicator.scaleY = if (locked) 1.45f else 1.3f
        focusLockIndicator.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(0f)
            .setDuration(if (locked) 1000L else 750L)
            .withEndAction {
                focusLockIndicator.visibility = View.GONE
            }
            .start()
    }

    private fun normalizedPreviewPoint(x: Float, y: Float): Pair<Float, Float> {
        val point = surfacePreview.mapViewPointToTextureNormalized(x, y)
        return if (targetIsPortrait()) {
            Pair(point.second, point.first)
        } else {
            point
        }
    }

    private fun setAutoParameterLock(locked: Boolean, publish: Boolean = true) {
        autoParametersLocked = locked
        cameraCapture?.setAutoControlsLocked(locked)
        updateOverlayState()
        if (publish) {
            publishCurrentSettings()
        }
    }

    private fun setZoomRatio(ratio: Float) {
        val zoomRange = cameraControlRanges().zoomRatio
        val nextZoom = ratio.coerceIn(zoomRange.lower, zoomRange.upper)
        cameraCapture?.setZoom(nextZoom)
        currentZoom = cameraCapture?.getZoom() ?: nextZoom
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setIso(value: Int?) {
        setAutoParameterLock(false, publish = false)
        val range = cameraControlRanges().iso
        manualIso = if (range != null) value?.coerceIn(range.lower, range.upper) else value
        cameraCapture?.setIso(manualIso)
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setShutterSpeedNs(value: Long?) {
        setAutoParameterLock(false, publish = false)
        val range = cameraControlRanges().exposureTimeNs
        manualExposureTimeNs = if (range != null) value?.coerceIn(range.lower, range.upper) else value
        cameraCapture?.setShutterSpeedNs(manualExposureTimeNs)
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setExposureCompensation(value: Int) {
        setAutoParameterLock(false, publish = false)
        val range = cameraControlRanges().exposureCompensation
        exposureCompensation = if (range != null) value.coerceIn(range.lower, range.upper) else 0
        cameraCapture?.setExposureCompensation(exposureCompensation)
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setWhiteBalanceKelvin(value: Int?) {
        setAutoParameterLock(false, publish = false)
        val range = cameraControlRanges().whiteBalanceKelvin
        whiteBalanceKelvin = value?.coerceIn(range.lower, range.upper)
        cameraCapture?.setWhiteBalanceKelvin(whiteBalanceKelvin)
        updateOverlayState()
        publishCurrentSettings()
    }

    private fun setFocusDistance(value: Float?) {
        setAutoParameterLock(false, publish = false)
        val range = cameraControlRanges().focusDistance
        focusDistance = if (range != null) value?.coerceIn(range.lower, range.upper) else null
        cameraCapture?.setFocusDistance(focusDistance)
        updateOverlayState()
        publishCurrentSettings()
    }

    // ── Floating sliders for bottom controls ───────────────────────────────

    private fun showControlSlider(
        anchor: View,
        title: String,
        currentRawValue: Float?, // null = AUTO
        min: Float,
        max: Float,
        steps: Int,
        hasAuto: Boolean,
        formatValue: (Float) -> String,
        onValueChanged: (Float?) -> Unit
    ) {
        activeControlPopup?.dismiss()
        val isAuto = currentRawValue == null
        val currentValue = currentRawValue ?: ((min + max) / 2f)
        val valueSpan = (max - min).coerceAtLeast(0.0001f)

        val currentLabel = android.widget.TextView(this).apply {
            text = if (isAuto) "AUTO" else formatValue(currentValue)
            textSize = 16f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 6)
        }

        val seekBar = android.widget.SeekBar(this).apply {
            this.max = steps
            progress = if (isAuto) steps / 2
            else ((currentValue - min) / valueSpan * steps).toInt().coerceIn(0, steps)
        }

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetOverlayAutoHide()
                    val value = min + valueSpan * progress / steps
                    currentLabel.text = formatValue(value)
                    onValueChanged(value)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                resetOverlayAutoHide()
            }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                resetOverlayAutoHide()
            }
        })

        val valueRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            if (hasAuto) {
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = "AUTO"
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), 0xFFFFFFFF.toInt())
                        setColor(0x33000000)
                    }
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                    setOnClickListener {
                        resetOverlayAutoHide()
                        currentLabel.text = "AUTO"
                        seekBar.progress = steps / 2
                        onValueChanged(null)
                    }
                }, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) })
            }
            addView(currentLabel, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xCC000000.toInt())
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(android.widget.TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFBBBBBB.toInt())
                setPadding(0, 0, 0, dp(4))
            })
            addView(valueRow)
            addView(seekBar, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val rootWidth = if (rootLayout.width > 0) rootLayout.width else resources.displayMetrics.widthPixels
        val popupWidth = (rootWidth - dp(24)).coerceIn(dp(260), dp(420))
        val popup = android.widget.PopupWindow(
            container,
            popupWidth,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dp(6).toFloat()
            }
        }

        container.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val anchorLocation = IntArray(2)
        val rootLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        rootLayout.getLocationOnScreen(rootLocation)
        val popupHeight = container.measuredHeight
        val leftBound = rootLocation[0] + dp(8)
        val rightBound = rootLocation[0] + rootWidth - popupWidth - dp(8)
        val x = (anchorLocation[0] + anchor.width / 2 - popupWidth / 2)
            .coerceIn(leftBound, rightBound.coerceAtLeast(leftBound))
        val y = (anchorLocation[1] - popupHeight - dp(8))
            .coerceAtLeast(rootLocation[1] + dp(8))
        popup.setOnDismissListener {
            if (activeControlPopup === popup) activeControlPopup = null
        }
        activeControlPopup = popup
        popup.showAtLocation(rootLayout, android.view.Gravity.NO_GRAVITY, x, y)
    }

    private fun showShutterSlider() {
        val range = cameraControlRanges().exposureTimeNs
        if (range == null || range.lower <= 0L) {
            Toast.makeText(this, "相机不支持手动快门", Toast.LENGTH_SHORT).show()
            return
        }
        // Log scale: seekbar maps to ln(min)..ln(max)
        val logMin = kotlin.math.ln(range.lower.toDouble())
        val logMax = kotlin.math.ln(range.upper.toDouble())
        val steps = 120 // fine granularity
        showControlSlider(
            anchor = controlShutter,
            title = "快门速度",
            currentRawValue = manualExposureTimeNs?.let { kotlin.math.ln(it.toDouble()).toFloat() },
            min = logMin.toFloat(),
            max = logMax.toFloat(),
            steps = steps,
            hasAuto = true,
            formatValue = { logVal ->
                val ns = kotlin.math.exp(logVal.toDouble()).toLong()
                formatShutterSpeed(ns)
            },
            onValueChanged = { logVal ->
                if (logVal == null) {
                    setShutterSpeedNs(null)
                } else {
                    setShutterSpeedNs(kotlin.math.exp(logVal.toDouble()).toLong())
                }
            }
        )
    }

    private fun showIsoSlider() {
        val range = cameraControlRanges().iso
        if (range == null) {
            Toast.makeText(this, "相机不支持手动ISO", Toast.LENGTH_SHORT).show()
            return
        }
        showControlSlider(
            anchor = controlIso,
            title = "ISO 感光度",
            currentRawValue = manualIso?.toFloat(),
            min = range.lower.toFloat(),
            max = range.upper.toFloat(),
            steps = (range.upper - range.lower).coerceIn(1, 200),
            hasAuto = true,
            formatValue = { "%.0f".format(it) },
            onValueChanged = { setIso(it?.toInt()) }
        )
    }

    private fun showExposureSlider() {
        val range = cameraControlRanges().exposureCompensation
        if (range == null) {
            Toast.makeText(this, "相机不支持曝光补偿", Toast.LENGTH_SHORT).show()
            return
        }
        showControlSlider(
            anchor = controlExposure,
            title = "亮度 / 曝光补偿",
            currentRawValue = exposureCompensation.toFloat(),
            min = range.lower.toFloat(),
            max = range.upper.toFloat(),
            steps = range.upper - range.lower,
            hasAuto = false,
            formatValue = { "%.0f EV".format(it) },
            onValueChanged = { setExposureCompensation(it!!.toInt()) }
        )
    }

    private fun showWhiteBalanceSlider() {
        val range = cameraControlRanges().whiteBalanceKelvin
        showControlSlider(
            anchor = controlWhiteBalance,
            title = "色温",
            currentRawValue = whiteBalanceKelvin?.toFloat(),
            min = range.lower.toFloat(),
            max = range.upper.toFloat(),
            steps = ((range.upper - range.lower) / 100).coerceAtLeast(1),
            hasAuto = true,
            formatValue = { "%.0f K".format(it) },
            onValueChanged = { setWhiteBalanceKelvin(it?.toInt()) }
        )
    }

    private fun showFocusSlider() {
        val range = cameraControlRanges().focusDistance
        if (range == null) {
            Toast.makeText(this, "相机不支持手动对焦", Toast.LENGTH_SHORT).show()
            return
        }
        showControlSlider(
            anchor = controlFocus,
            title = "焦距",
            currentRawValue = focusDistance,
            min = range.lower,
            max = range.upper,
            steps = 100,
            hasAuto = true,
            formatValue = { formatFocusDistance(it) },
            onValueChanged = { setFocusDistance(it) }
        )
    }

    private fun showZoomSlider() {
        val range = cameraControlRanges().zoomRatio
        showControlSlider(
            anchor = controlZoom,
            title = "变焦倍率",
            currentRawValue = currentZoom,
            min = range.lower,
            max = range.upper,
            steps = ((range.upper - range.lower) * 10f).toInt().coerceAtLeast(1),
            hasAuto = false,
            formatValue = { "%.1fx".format(it) },
            onValueChanged = { setZoomRatio(it!!) }
        )
    }

    // ── End floating sliders ─────────────────────────────────────────────

    private fun applyRealtimeControls(capture: CameraCapture) {
        capture.setTorch(isTorchOn)
        capture.setZoom(currentZoom)
        capture.setExposureCompensation(exposureCompensation)
        capture.setIso(manualIso)
        capture.setShutterSpeedNs(manualExposureTimeNs)
        capture.setWhiteBalanceKelvin(whiteBalanceKelvin)
        capture.setFocusDistance(focusDistance)
        capture.setAutoControlsLocked(autoParametersLocked)
        capture.setEncodedMirror(isMirrorOn)
    }

    private fun updateOverlayState() {
        if (!::overlayBatteryLevel.isInitialized) return
        updateBatteryIndicator()
        btnTorch.alpha = if (isTorchOn) 1f else 0.65f
        btnBeauty.alpha = if (isBeautyOn) 1f else 0.65f
        btnMirror.alpha = if (isMirrorOn) 1f else 0.65f
        btnScreenOff.alpha = if (screenOffRequested) 1f else 0.65f
        val autoLabel = if (autoParametersLocked) "锁定" else "AUTO"
        controlShutter.text = "快门\n${manualExposureTimeNs?.let { formatShutterSpeed(it) } ?: autoLabel}"
        controlIso.text = "ISO\n${manualIso?.toString() ?: autoLabel}"
        controlExposure.text = "亮度\n$exposureCompensation"
        controlWhiteBalance.text = "色温\n${whiteBalanceKelvin?.let { "${it}K" } ?: autoLabel}"
        controlFocus.text = "焦距\n${focusDistance?.let { formatFocusDistance(it) } ?: autoLabel}"
        controlZoom.text = "倍率\n${String.format("%.1fx", currentZoom)}"
    }

    private fun updateBatteryIndicator() {
        val percent = currentBatteryPercent()
        overlayBatteryLevel.text = if (percent != null) {
            "$percent%"
        } else {
            "--%"
        }
    }

    private fun currentBatteryPercent(): Int? {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private fun formatShutterSpeed(value: Long?): String {
        if (value == null) return "AUTO"
        val denominator = (1_000_000_000L / value).coerceAtLeast(1L)
        return "1/${denominator}S"
    }

    private fun formatFocusDistance(value: Float?): String {
        if (value == null) return "AUTO"
        if (value == 0f) return "INF"
        return String.format("%.1f", value)
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: ""
                        if (sAddr.indexOf(':') < 0) return sAddr
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, true)) model
        else "$manufacturer $model"
    }

    private fun generateDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") "m-${androidId.takeLast(6)}"
        else "m-${java.util.UUID.randomUUID().toString().take(6)}"
    }

    private fun restartClientBeacon() {
        clientBeacon?.stop()
        clientBeacon = ClientBeacon(serverCameraId, getDeviceName()).also { it.start() }
    }

    private fun tryAutoConnect() {
        if (isScanning || isConnected) return
        isScanning = true
        textStatus.text = "正在扫描局域网服务端..."

        discoveryClient = DiscoveryClient(serverCameraId, getDeviceName()) { host, port ->
            runOnUiThread {
                if (!isConnected && isScanning) {
                    isScanning = false
                    discoveryClient?.stop()
                    startStreaming("$host:$port")
                }
            }
        }.also { it.start() }

        circleContainer.postDelayed({
            if (isScanning) {
                discoveryClient?.stop()
                isScanning = false
                textStatus.setText(R.string.searching)
            }
        }, 15000)
    }

    private fun showConnectDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val hostInput = android.widget.EditText(this).apply {
            hint = "192.168.1.100:8055"
            setText(prefs.getString("last_host", ""))
        }
        container.addView(android.widget.TextView(this).apply {
            text = "服务器地址 (IP:端口)"
            textSize = 13f
        })
        container.addView(hostInput)

        val camIdInput = android.widget.EditText(this).apply {
            hint = generateDeviceId()
            setText(prefs.getString("last_camera_id", generateDeviceId()))
        }
        container.addView(android.widget.TextView(this).apply {
            text = "摄像头 ID（每个手机用不同ID）"
            textSize = 13f
        })
        container.addView(camIdInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.connect_to_pc)
            .setView(container)
            .setPositiveButton(R.string.connect) { _, _ ->
                var host = hostInput.text.toString().trim()
                if (host.isNotEmpty()) {
                    if (!host.contains(":")) {
                        host += ":8055"
                    }
                    val cameraId = camIdInput.text.toString().trim().ifEmpty { generateDeviceId() }
                    prefs.edit()
                        .putString("last_host", host)
                        .putString("last_camera_id", cameraId)
                        .apply()
                    serverCameraId = cameraId
                    restartClientBeacon()
                    startStreaming(host)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
        }
    }

    private fun startStreaming(host: String) {
        if (isConnected) return
        serverHost = host
        reconnectAttempts = 0

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("keep_screen_on", true)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        val config = resolveVideoConfig()
        activeVideoWidth = config.width
        activeVideoHeight = config.height
        currentResolution = config.resolution
        currentFps = config.fps
        currentCodec = config.codec
        activeCameraId = currentCameraId
        updatePreviewTransform()

        val deviceName = getDeviceName()

        val orientation = prefs.getString("video_orientation", "landscape") ?: "landscape"

        wsClient = WebSocketClient(
            host,
            serverCameraId,
            deviceName,
            currentResolution,
            currentFps,
            currentCodec,
            currentVideoFormat,
            orientation,
            buildCapabilitiesJson(),
            buildCurrentSettingsJson()
        ).apply {
            onConnected = {
                runOnUiThread {
                    isConnected = true
                    reconnectAttempts = 0
                    enterPreviewWindowMode()
                    searchLayout.visibility = View.GONE
                    surfacePreview.visibility = View.VISIBLE
                    surfacePreview.alpha = 1f
                    surfacePreview.isEnabled = true
                    previewClosedHint.visibility = View.GONE
                    textStatus.text = "已连接到 $host"

                    videoEncoder = createVideoEncoder(config)

                    if (surfacePreview.isAvailable) {
                        startCameraSession()
                    }
                    wsClient?.sendStart()
                }
            }
            onDisconnected = { reason ->
                runOnUiThread {
                    if (appInBackground || cameraSessionSuspended) {
                        reconnectOnResume = true
                        isConnected = false
                        isStreaming = false
                        wsClient = null
                        releaseVideoEncoder()
                        textStatus.text = "后台暂停，返回后重连..."
                        return@runOnUiThread
                    }
                    Toast.makeText(this@MainActivity, reason, Toast.LENGTH_SHORT).show()
                    if (reconnectAttempts < maxReconnectAttempts) {
                        reconnectAttempts++
                        textStatus.text = "断开，${2 * reconnectAttempts}s 后重连 ($reconnectAttempts/$maxReconnectAttempts)..."
                        circleContainer.postDelayed({
                            if (!isConnected) {
                                startStreaming(host)
                            }
                        }, (2000L * reconnectAttempts).coerceAtMost(10000L))
                    } else {
                        stopStreaming()
                    }
                }
            }
            onSettingsUpdate = { settings ->
                runOnUiThread {
                    if (settings["request_keyframe"]?.equals("true", ignoreCase = true) == true) {
                        videoEncoder?.requestKeyFrame()
                        if (settings.keys.all { it == "request_keyframe" }) {
                            return@runOnUiThread
                        }
                    }

                    val editor = prefs.edit()
                    val pendingSize = settings["video_size"]?.takeIf { supportedVideoSizes().contains(it) }
                    settings["camera_id"]?.let {
                        if (availableCameras.any { camera -> camera.id == it }) {
                            editor.putString("camera_id", it)
                        }
                    }
                    settings["video_encoder"]?.let {
                        if (it == "h264" || (it == "h265" && isH265Supported())) {
                            editor.putString("video_encoder", it)
                        }
                    }
                    pendingSize?.let {
                        editor.putString("video_size", it)
                    }
                    settings["video_fps"]?.let {
                        val fps = it.toIntOrNull() ?: currentFps
                        val fpsSize = pendingSize?.let { size -> sizeFromValue(size) } ?: selectedVideoSize()
                        if (CameraCapture.supportedFps(cameraManager, currentCameraId, fpsSize).contains(fps)) {
                            editor.putString("video_fps", it)
                        }
                    }
                    settings["video_quality"]?.let { editor.putString("video_quality", it) }
                    settings["video_orientation"]?.let { editor.putString("video_orientation", it) }
                    editor.apply()

                    suppressStatusPublish = true
                    settings["torch_enabled"]?.let { setTorchEnabled(parseBooleanSetting(it)) }
                    settings["beauty_enabled"]?.let { setBeautyEnabled(parseBooleanSetting(it)) }
                    settings["mirror_enabled"]?.let { setMirrorEnabled(parseBooleanSetting(it)) }
                    settings["screen_off"]?.let { setScreenOffEnabled(parseBooleanSetting(it)) }
                    settings["auto_parameters_locked"]?.let { setAutoParameterLock(parseBooleanSetting(it)) }
                    settings["zoom_ratio"]?.toFloatOrNull()?.let { setZoomRatio(it) }
                    settings["iso"]?.let { setIso(parseNullableIntSetting(it)) }
                    settings["shutter_speed_ns"]?.let { setShutterSpeedNs(parseNullableLongSetting(it)) }
                    settings["exposure_compensation"]?.toIntOrNull()?.let { setExposureCompensation(it) }
                    settings["white_balance_kelvin"]?.let { setWhiteBalanceKelvin(parseNullableIntSetting(it)) }
                    settings["focus_distance"]?.let { setFocusDistance(parseNullableFloatSetting(it)) }

                    if (settings.any { it.key in listOf("camera_id", "video_encoder", "video_size", "video_fps", "video_quality", "video_orientation", "video_bitrate_v2") }) {
                        applyRotationSetting()
                        restartActiveStream()
                    }
                    suppressStatusPublish = false
                    publishCurrentSettings()
                    Toast.makeText(this@MainActivity, "设置已从服务器同步", Toast.LENGTH_SHORT).show()
                }
            }
            connect()
        }
    }

    private fun startCameraSession() {
        if (cameraSessionStarted) return
        val st = surfacePreview.surfaceTexture ?: return
        val videoEnc = videoEncoder ?: return
        
        val config = resolveVideoConfig()
        val orientationConfig = resolveCameraOrientationConfig()
        st.setDefaultBufferSize(config.bufferWidth, config.bufferHeight)
        val fillPreview = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("fill_preview", true)
        surfacePreview.configurePreviewTransform(
            config.bufferWidth,
            config.bufferHeight,
            orientationConfig.relativeRotationDegrees,
            orientationConfig.displayRotationDegrees,
            targetIsPortrait(),
            fillPreview,
            isMirrorOn
        )
        logCameraOrientation(config, orientationConfig)
        val previewSurface = Surface(st)
        cameraSessionStarted = true

        val sessionCameraId = currentCameraId
        cameraCapture = CameraCapture(cameraManager, sessionCameraId, config.width, config.height, config.fps).apply {
            this.previewSurface = previewSurface
            this.encoderSurface = videoEnc.inputSurface
            onReady = { runOnUiThread { isStreaming = true } }
            onError = { msg -> runOnUiThread { handleCameraCaptureError(sessionCameraId, msg) } }
            applyRealtimeControls(this)
            open()
        }
    }

    private fun switchCamera() {
        if (availableCameras.size < 2) {
            Toast.makeText(this, "只有一个可用摄像头", Toast.LENGTH_SHORT).show()
            return
        }
        currentCameraIndex = (currentCameraIndex + 1) % availableCameras.size
        currentCameraId = availableCameras[currentCameraIndex].id
        
        // Update preference so it stays on this camera
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString("camera_id", currentCameraId).apply()

        Toast.makeText(this, "切换到: ${availableCameras[currentCameraIndex].name}", Toast.LENGTH_SHORT).show()
        cameraCapture?.close()
        cameraCapture = null
        cameraSessionStarted = false
        if (isConnected) {
            restartEncoder()
            if (!screenOffRequested && surfacePreview.isAvailable) startCameraSession()
            publishCurrentSettings()
        }
    }

    private fun stopStreaming() {
        runOnUiThread {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            cameraSessionSuspended = false
            activeControlPopup?.dismiss()
            cancelOverlayAutoHide()
            isScanning = false
            discoveryClient?.stop()
            discoveryClient = null
            wsClient?.sendStop()
            wsClient?.disconnect()
            wsClient = null
            cameraCapture?.close()
            cameraCapture = null
            videoEncoder?.release()
            videoEncoder = null
            isStreaming = false
            isConnected = false
            cameraSessionStarted = false
            screenOffRequested = false
            exitScreenOffWindowMode()
            screenOffLayer.visibility = View.GONE
            streamOverlay.visibility = View.GONE
            previewClosedHint.visibility = View.GONE
            searchLayout.visibility = View.VISIBLE
            surfacePreview.alpha = 1f
            surfacePreview.isEnabled = true
            surfacePreview.visibility = View.GONE
            textStatus.setText(R.string.disconnected)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        clientBeacon?.stop()
        clientBeacon = null
    }
}
