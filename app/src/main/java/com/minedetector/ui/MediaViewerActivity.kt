package com.minedetector.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.button.MaterialButton
import com.minedetector.R
import com.minedetector.data.models.MediaType
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@UnstableApi
class MediaViewerActivity : AppCompatActivity() {

    private lateinit var photoView: PhotoView
    private lateinit var videoView: PlayerView
    private lateinit var btnBack: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var tvFilename: TextView
    private lateinit var speedControls: LinearLayout
    private lateinit var btnSendToDetection: MaterialButton

    // Speed buttons
    private lateinit var btnSpeed05x: MaterialButton
    private lateinit var btnSpeed1x: MaterialButton
    private lateinit var btnSpeed15x: MaterialButton
    private lateinit var btnSpeed2x: MaterialButton

    private var player: ExoPlayer? = null
    private var currentMediaItem: com.minedetector.data.models.MediaItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_viewer)

        // Handle deprecated getParcelableExtra for API 33+
        currentMediaItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("media_item", com.minedetector.data.models.MediaItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("media_item")
        }

        initViews()
        loadMedia()
    }

    private fun initViews() {
        photoView = findViewById(R.id.photo_view)
        videoView = findViewById(R.id.video_view)
        btnBack = findViewById(R.id.btn_back)
        btnMore = findViewById(R.id.btn_more)
        tvFilename = findViewById(R.id.tv_filename)
        speedControls = findViewById(R.id.speed_controls)
        btnSendToDetection = findViewById(R.id.btn_send_to_detection)

        btnSpeed05x = findViewById(R.id.btn_speed_0_5x)
        btnSpeed1x = findViewById(R.id.btn_speed_1x)
        btnSpeed15x = findViewById(R.id.btn_speed_1_5x)
        btnSpeed2x = findViewById(R.id.btn_speed_2x)

        btnBack.setOnClickListener { finish() }
        btnMore.setOnClickListener { showMediaInfo() }
        btnSendToDetection.setOnClickListener { sendToDetection() }

        setupSpeedControls()
    }

    private fun loadMedia() {
        currentMediaItem?.let { media ->
            val file = File(media.path)
            tvFilename.text = file.name

            when (media.type) {
                MediaType.PHOTO -> {
                    photoView.visibility = View.VISIBLE
                    videoView.visibility = View.GONE
                    speedControls.visibility = View.GONE

                    if (file.exists()) {
                        photoView.load(file) {
                            crossfade(true)
                        }
                    }
                }
                MediaType.VIDEO -> {
                    photoView.visibility = View.GONE
                    videoView.visibility = View.VISIBLE
                    speedControls.visibility = View.VISIBLE

                    if (file.exists()) {
                        initializePlayer(file)
                    }
                }
            }
        }
    }

    private fun initializePlayer(file: File) {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            videoView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    private fun setupSpeedControls() {
        btnSpeed05x.setOnClickListener {
            setPlaybackSpeed(0.5f)
            updateSpeedButtonStates(btnSpeed05x)
        }
        btnSpeed1x.setOnClickListener {
            setPlaybackSpeed(1.0f)
            updateSpeedButtonStates(btnSpeed1x)
        }
        btnSpeed15x.setOnClickListener {
            setPlaybackSpeed(1.5f)
            updateSpeedButtonStates(btnSpeed15x)
        }
        btnSpeed2x.setOnClickListener {
            setPlaybackSpeed(2.0f)
            updateSpeedButtonStates(btnSpeed2x)
        }

        // Set 1x as default
        updateSpeedButtonStates(btnSpeed1x)
    }

    private fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
    }

    private fun updateSpeedButtonStates(activeButton: MaterialButton) {
        val buttons = listOf(btnSpeed05x, btnSpeed1x, btnSpeed15x, btnSpeed2x)
        buttons.forEach { button ->
            button.setTextColor(
                if (button == activeButton)
                    ContextCompat.getColor(this, R.color.dji_white)  // ✅ Fixed: using ContextCompat
                else
                    ContextCompat.getColor(this, R.color.dji_silver_grey)  // ✅ Fixed: using ContextCompat
            )
        }
    }

    private fun showMediaInfo() {
        currentMediaItem?.let { media ->
            val file = File(media.path)

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
            val date = dateFormat.format(Date(media.dateModified))

            val sizeKB = media.size / 1024
            val sizeMB = sizeKB / 1024
            val sizeStr = if (sizeMB > 0) "$sizeMB MB" else "$sizeKB KB"

            val info = buildString {
                append("Filename: ${media.name}\n\n")
                append("Size: $sizeStr\n\n")
                append("Type: ${media.type.name}\n\n")
                append("Date Modified: $date\n\n")
                append("Path: ${file.absolutePath}")
            }

            AlertDialog.Builder(this)
                .setTitle("Media Information")
                .setMessage(info)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun sendToDetection() {
        currentMediaItem?.let { media ->
            val intent = Intent(this, TestVideoActivity::class.java)
            intent.putExtra("media_path", media.path)
            intent.putExtra("media_type", media.type.name)
            startActivity(intent)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}