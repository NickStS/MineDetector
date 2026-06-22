package com.minedetector.ui

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.google.android.material.button.MaterialButton
import com.minedetector.R
import com.minedetector.viewmodels.DroneViewModel
import com.permissionx.guolindev.PermissionX

@UnstableApi
class MainMenuActivity : AppCompatActivity() {

    private lateinit var droneViewModel: DroneViewModel
    private lateinit var btnSettings: ImageButton
    private lateinit var cardAlbum: CardView
    private lateinit var cardTest: CardView
    private lateinit var btnConnectDrone: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        droneViewModel = ViewModelProvider(this)[DroneViewModel::class.java]

        initViews()
        requestPermissions()
        showSafetyDisclaimer()
        observeViewModel()
    }

    private fun initViews() {
        btnSettings = findViewById(R.id.btn_settings)
        cardAlbum = findViewById(R.id.card_album)
        cardTest = findViewById(R.id.card_test)
        btnConnectDrone = findViewById(R.id.btn_connect_drone)

        // About button
        btnSettings.setOnClickListener {
            showAboutDialog()
        }

        // Album card (Gallery)
        cardAlbum.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        // Test card (Test Video)
        cardTest.setOnClickListener {
            startActivity(Intent(this, TestVideoActivity::class.java))
        }

        // Connect button (Drone Control)
        btnConnectDrone.setOnClickListener {
            startActivity(Intent(this, DroneControlActivity::class.java))
        }
    }

    private fun observeViewModel() {
        droneViewModel.connectionState.observe(this) { isConnected ->
            btnConnectDrone.text = if (isConnected) {
                "Connected"
            } else {
                "Connect"
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // API 33+
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            // API 24-32
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        PermissionX.init(this)
            .permissions(permissions)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    "Permissions required for drone control and camera access",
                    "OK",
                    "Cancel"
                )
            }
            .request { allGranted, _, _ ->
                if (!allGranted) {
                    Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun showSafetyDisclaimer() {
        val prefs = getSharedPreferences("mine_detector_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("disclaimer_shown", false)) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ SAFETY WARNING")
                .setMessage(
                    "ATTENTION!\n\n" +
                            "1. This application is ONLY for detecting potential mines.\n\n" +
                            "2. DO NOT APPROACH detected objects!\n\n" +
                            "3. Immediately contact professional bomb disposal services.\n\n" +
                            "4. The application does not replace the work of specialists.\n\n" +
                            "5. Use ONLY in authorized areas with appropriate permits.\n\n" +
                            "Developers are not responsible for improper use."
                )
                .setPositiveButton("I Understand and Agree") { _, _ ->
                    prefs.edit().putBoolean("disclaimer_shown", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showAboutDialog() {
        val sb = SpannableStringBuilder()

        fun appendLine(text: String) = sb.append(text).append("\n")

        fun appendStyled(
            text: String,
            bold: Boolean = false,
            color: Int? = null,
            sizeMul: Float = 1f
        ) {
            val start = sb.length
            sb.append(text)
            val end = sb.length
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (color != null) sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (sizeMul != 1f) sb.setSpan(RelativeSizeSpan(sizeMul), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Title subtitle
        appendStyled("AI-Based Visual Analysis System\nfor High-Risk Environments", bold = true, sizeMul = 1.1f)
        appendLine("\n")

        // Description
        appendLine("Mine Detector is a mobile solution leveraging deep learning to identify potential threats within visual data streams.")
        appendLine("")
        appendLine("Built on modern object detection models and optimised for on-device inference — no data is sent to external servers.")
        appendLine("")
        appendLine("Developed as part of a research initiative on integrating AI technologies into field analysis scenarios.")
        appendLine("")

        // Detection classes
        appendStyled("Detectable classes:\n", bold = true)
        val classes = listOf(
            "MON-50" to Color.RED,
            "PFM-1"  to Color.rgb(200, 180, 0),
            "PMN-1"  to Color.MAGENTA,
            "PMN-2"  to Color.rgb(255, 140, 0),
            "POM-3"  to Color.CYAN,
            "TM-62"  to Color.GREEN
        )
        for ((name, color) in classes) {
            appendStyled("  ● ", bold = true, color = color)
            appendStyled("$name\n", color = color)
        }
        appendLine("")

        // Warning block
        appendStyled("⚠  DISCLAIMER\n", bold = true, color = Color.rgb(255, 80, 80), sizeMul = 1.05f)
        appendStyled(
            "This software is NOT a certified mine detection tool. " +
            "It must NOT be used in real operational environments. " +
            "Always contact professional EOD services.\n",
            color = Color.rgb(255, 120, 80)
        )
        appendLine("")

        // Version line
        appendStyled("Version 1.5.3 Beta  ·  Developer: danger_videograph",
            color = Color.GRAY, sizeMul = 0.85f)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Mine Detector")
            .setMessage(sb)
            .setPositiveButton("OK", null)
            .create()

        dialog.show()

        // Make the message text selectable and increase padding
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize = 13.5f
            setLineSpacing(4f, 1f)
        }
    }
}