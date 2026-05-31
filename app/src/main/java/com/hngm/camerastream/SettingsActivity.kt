package com.hngm.camerastream

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRotationSetting()
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "设置"
    }

    override fun onResume() {
        super.onResume()
        applyRotationSetting()
    }

    fun applyRotationSetting(orientationValue: String? = null) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val orientation = orientationValue ?: prefs.getString("video_orientation", "landscape")
        requestedOrientation = if (orientation == "portrait") {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Migrate video_bitrate from String to Int if necessary before loading preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            try {
                prefs.getInt("video_bitrate_v2", 0)
            } catch (e: Exception) {
                val oldVal = try { prefs.getString("video_bitrate_v2", "0")?.toIntOrNull() ?: 0 } catch (e2: Exception) { 0 }
                prefs.edit().putInt("video_bitrate_v2", oldVal).apply()
            }

            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            
            // Populate Camera List
            val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraInfos = CameraCapture.enumerateCameras(
                manager,
                prefs.getStringSet(CameraCapture.UNSUPPORTED_PHYSICAL_CAMERA_IDS_KEY, emptySet())
                    ?: emptySet()
            )
            val cameraPref = findPreference<ListPreference>("camera_id")
            if (cameraPref != null && cameraInfos.isNotEmpty()) {
                cameraPref.entries = cameraInfos.map { it.name }.toTypedArray()
                cameraPref.entryValues = cameraInfos.map { it.id }.toTypedArray()
                if (cameraPref.value == null || !cameraInfos.any { it.id == cameraPref.value }) {
                    cameraPref.value = cameraInfos.first().id
                }
                
                cameraPref.setOnPreferenceChangeListener { _, newValue ->
                    bindRealCameraOptions(newValue as String)
                    true
                }
            }

            bindRealCameraOptions(cameraPref?.value ?: "0")

            findPreference<ListPreference>("video_orientation")?.setOnPreferenceChangeListener { _, newValue ->
                (activity as? SettingsActivity)?.applyRotationSetting(newValue as String)
                true
            }
            
            val bitratePref = findPreference<androidx.preference.SeekBarPreference>("video_bitrate_v2")
            bitratePref?.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.SeekBarPreference> { preference ->
                if (preference.value == 0) "自动（基于质量设置）" else "${preference.value} Mbps"
            }

            findPreference<Preference>("advanced_settings")?.isVisible = false

            findPreference<Preference>("advanced_settings")?.setOnPreferenceClickListener {
                parentFragmentManager
                    .beginTransaction()
                    .replace(R.id.settings, AdvancedSettingsFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }

            findPreference<Preference>("help")?.setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.help_title)
                    .setMessage(R.string.help_content)
                    .setPositiveButton("知道了", null)
                    .show()
                true
            }
        }

        private fun bindRealCameraOptions(cameraId: String) {
            val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            
            // Resolution
            val filteredSizes = CameraCapture.supportedResolutionOptions(manager, cameraId)
            
            val sizeEntries = filteredSizes.map { it.label }
            val sizeValues = filteredSizes.map { it.value }
            
            val sizePref = findPreference<ListPreference>("video_size")
            if (sizePref != null) {
                sizePref.entries = sizeEntries.toTypedArray()
                sizePref.entryValues = sizeValues.toTypedArray()
                if (sizePref.value == null || !sizeValues.contains(sizePref.value)) {
                    sizePref.value = sizeValues.find { it == "1280x720" } ?: sizeValues.firstOrNull()
                }
                
                sizePref.setOnPreferenceChangeListener { _, newValue ->
                    val selectedSize = parseSize(newValue as String)
                    updateFpsOptions(cameraId, selectedSize)
                    true
                }
                
                // Initial FPS update
                updateFpsOptions(cameraId, parseSize(sizePref.value))
            }

            // Encoder
            val encoders = mutableListOf("h264")
            val encoderLabels = mutableMapOf("h264" to "H.264")
            if (isH265Supported()) {
                encoders.add("h265")
                encoderLabels["h265"] = "H.265 (HEVC)"
            }
            bindList("video_encoder", encoders, encoderLabels)
        }

        private fun updateFpsOptions(cameraId: String, size: android.util.Size?) {
            val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val fpsPref = findPreference<ListPreference>("video_fps") ?: return
            val availableFps = CameraCapture.supportedFps(manager, cameraId, size)
                .map { it.toString() }
            
            fpsPref.entries = availableFps.toTypedArray()
            fpsPref.entryValues = availableFps.toTypedArray()
            
            if (fpsPref.value == null || !availableFps.contains(fpsPref.value)) {
                // Default to 30 if available, otherwise the highest possible
                fpsPref.value = availableFps.find { it == "30" } ?: availableFps.firstOrNull()
            }
        }

        private fun parseSize(sizeStr: String?): android.util.Size? {
            if (sizeStr == null) return null
            val parts = sizeStr.split("x")
            return if (parts.size == 2) {
                android.util.Size(parts[0].toInt(), parts[1].toInt())
            } else null
        }

        private fun isH265Supported(): Boolean {
            return try {
                android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                    .codecInfos.any { it.isEncoder && it.supportedTypes.contains(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC) }
            } catch (e: Exception) {
                false
            }
        }

        private fun bindList(key: String, values: List<String>, labels: Map<String, String> = emptyMap()) {
            val pref = findPreference<ListPreference>(key) ?: return
            if (values.isEmpty()) return
            pref.entries = values.map { labels[it] ?: it }.toTypedArray()
            pref.entryValues = values.toTypedArray()
            
            // Reset to first available if current value is invalid
            if (pref.value == null || !values.contains(pref.value)) {
                // Try to find a sensible default if it's size
                if (key == "video_size") {
                    pref.value = values.find { it == "1280x720" } ?: values.first()
                } else if (key == "video_fps") {
                    pref.value = values.find { it == "30" } ?: values.first()
                } else {
                    pref.value = values.first()
                }
            }
        }
    }

    class AdvancedSettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.advanced_preferences, rootKey)
        }
    }
}
