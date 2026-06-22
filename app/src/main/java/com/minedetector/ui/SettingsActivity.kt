package com.minedetector.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minedetector.R
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    // Sidebar menu items
    private lateinit var menuDrone: LinearLayout
    private lateinit var menuSignal: LinearLayout
    private lateinit var menuController: LinearLayout
    private lateinit var menuCamera: LinearLayout
    private lateinit var menuMore: LinearLayout

    // Top bar
    private lateinit var tvSettingsTitle: TextView
    private lateinit var btnClose: ImageView

    // Settings content
    private lateinit var settingsContent: LinearLayout

    // Units section
    private lateinit var tvUnitsValue: TextView

    // Camera section
    private lateinit var btnLiveStreaming: LinearLayout

    // Video Cache section
    private lateinit var switchCacheRecording: SwitchMaterial
    private lateinit var switchAudioCache: SwitchMaterial

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private enum class SettingsSection {
        GENERAL, SIGNAL, CONTROLLER, CAMERA, MORE
    }

    private var currentSection = SettingsSection.GENERAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadSettings()
        setupListeners()

        // Show general settings by default
        showSection(SettingsSection.GENERAL)
    }

    private fun initViews() {
        // Sidebar
        menuDrone = findViewById(R.id.menu_drone)
        menuSignal = findViewById(R.id.menu_signal)
        menuController = findViewById(R.id.menu_controller)
        menuCamera = findViewById(R.id.menu_camera)
        menuMore = findViewById(R.id.menu_more)

        // Top bar
        tvSettingsTitle = findViewById(R.id.tv_settings_title)
        btnClose = findViewById(R.id.btn_close)

        // Content
        settingsContent = findViewById(R.id.settings_content)

        // Units
        tvUnitsValue = findViewById(R.id.tv_units_value)

        // Camera
        btnLiveStreaming = findViewById(R.id.btn_live_streaming)

        // Video Cache
        switchCacheRecording = findViewById(R.id.switch_cache_recording)
        switchAudioCache = findViewById(R.id.switch_audio_cache)
    }

    private fun loadSettings() {
        // Load cached settings
        switchCacheRecording.isChecked = prefs.getBoolean("cache_recording", true)
        switchAudioCache.isChecked = prefs.getBoolean("audio_cache", false)

        // Load units setting
        val unitsMetric = prefs.getBoolean("units_metric", true)
        tvUnitsValue.text = if (unitsMetric) "Metric (m/s)" else "Imperial (mph)"
    }

    private fun setupListeners() {
        // Close button
        btnClose.setOnClickListener { finish() }

        // Sidebar menu
        menuDrone.setOnClickListener {
            showSection(SettingsSection.GENERAL)
        }

        menuSignal.setOnClickListener {
            showSection(SettingsSection.SIGNAL)
        }

        menuController.setOnClickListener {
            showSection(SettingsSection.CONTROLLER)
        }

        menuCamera.setOnClickListener {
            showSection(SettingsSection.CAMERA)
        }

        menuMore.setOnClickListener {
            showSection(SettingsSection.MORE)
        }

        // Live streaming
        btnLiveStreaming.setOnClickListener {
            showToast("Live Streaming settings coming soon")
        }

        // Cache switches
        switchCacheRecording.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("cache_recording", isChecked).apply()
            if (isChecked) {
                showToast("Video caching enabled")
            } else {
                showToast("Video caching disabled")
            }
        }

        switchAudioCache.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("audio_cache", isChecked).apply()
            if (isChecked) {
                showToast("Audio caching enabled")
            } else {
                showToast("Audio caching disabled")
            }
        }
    }

    private fun showSection(section: SettingsSection) {
        currentSection = section

        // Update sidebar highlighting
        resetSidebarHighlight()

        when (section) {
            SettingsSection.GENERAL -> {
                tvSettingsTitle.text = "General Settings"
                menuDrone.alpha = 1.0f
                // Settings content is already populated from XML
            }

            SettingsSection.SIGNAL -> {
                tvSettingsTitle.text = "Signal Settings"
                menuSignal.alpha = 1.0f
                showToast("Signal settings coming soon")
            }

            SettingsSection.CONTROLLER -> {
                tvSettingsTitle.text = "Controller Settings"
                menuController.alpha = 1.0f
                showToast("Controller settings coming soon")
            }

            SettingsSection.CAMERA -> {
                tvSettingsTitle.text = "Camera Settings"
                menuCamera.alpha = 1.0f
                showToast("Camera settings coming soon")
            }

            SettingsSection.MORE -> {
                tvSettingsTitle.text = "More Settings"
                menuMore.alpha = 1.0f
                showMoreSettings()
            }
        }
    }

    private fun resetSidebarHighlight() {
        menuDrone.alpha = 0.5f
        menuSignal.alpha = 0.5f
        menuController.alpha = 0.5f
        menuCamera.alpha = 0.5f
        menuMore.alpha = 0.5f
    }

    private fun showMoreSettings() {
        // Show additional options
        AlertDialog.Builder(this)
            .setTitle("More Settings")
            .setItems(arrayOf(
                "Clear Cache",
                "Clear Database",
                "Export Data",
                "About"
            )) { _, which ->
                when (which) {
                    0 -> clearCache()
                    1 -> clearDatabase()
                    2 -> exportData()
                    3 -> showAbout()
                }
            }
            .show()
    }

    private fun clearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear Cache?")
            .setMessage("This will delete temporary files and images")
            .setPositiveButton("Clear") { _, _ ->
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                showToast("Cache cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearDatabase() {
        AlertDialog.Builder(this)
            .setTitle("WARNING")
            .setMessage("This will delete ALL saved data: photos, detections, annotations and flight logs. This action cannot be undone!")
            .setPositiveButton("Delete All") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = com.minedetector.data.local.AppDatabase.getDatabase(applicationContext)
                        db.detectionDao().deleteAllDetections()
                        withContext(Dispatchers.Main) {
                            showToast("Database cleared")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast("Failed to clear database: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportData() {
        showToast("Export feature in development")
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Mine Detector")
            .setMessage("Version 1.0.0\n\nAI-powered landmine detection system for DJI drones.\n\nDeveloped with YOLOv11 and TensorFlow Lite.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}