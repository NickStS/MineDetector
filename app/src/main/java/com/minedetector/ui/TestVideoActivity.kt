package com.minedetector.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.exifinterface.media.ExifInterface
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.minedetector.R
import com.minedetector.utils.ImageHelper
import com.minedetector.data.local.AppDatabase
import com.minedetector.data.models.MediaItem as MediaItemModel
import com.minedetector.data.models.MediaType
import com.minedetector.ml.Detection
import com.minedetector.ml.TFLiteYoloDetector
import com.minedetector.ml.SimpleByteTracker
import com.minedetector.ui.components.VideoOverlayView
import com.minedetector.ui.fragments.MediaAdapter
import com.minedetector.viewmodels.DetectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@UnstableApi
class TestVideoActivity : BaseActivity() {

    companion object {
        private const val TAG = "TestVideoActivity"
        private const val SKIP_FRAMES = 5 // Process every 5th frame for better performance
        const val TAB_SELECT = 0
        const val TAB_DETECT = 1
    }

    private lateinit var viewModel: DetectionViewModel
    private lateinit var yoloDetector: TFLiteYoloDetector
    private lateinit var byteTracker: SimpleByteTracker

    // Main UI
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var btnBack: ImageButton
    private lateinit var mediaSourceSpinner: Spinner
    private var btnDownloadHeader: ImageButton? = null

    // DETECT tab UI
    private lateinit var detectContainer: View
    private lateinit var photoView: PhotoView
    private lateinit var videoContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var overlayView: VideoOverlayView // For photos
    private lateinit var videoOverlay: VideoOverlayView // For videos
    private lateinit var emptyState: LinearLayout

    private lateinit var tvStatus: TextView
    private var tvDetectionResultsLabel: TextView? = null
    private lateinit var tvDetectionStats: TextView
    private var tvMediaInfo: TextView? = null
    private var tvProcessingLabel: TextView? = null
    private lateinit var tvProcessingInfo: TextView
    private var processingInfoSection: View? = null
    private lateinit var progressBar: ProgressBar

    private var exoPlayer: ExoPlayer? = null
    private var detectionJob: Job? = null
    private var isDetecting = false
    private var currentMediaUri: Uri? = null
    private var currentMediaName: String? = null
    private var isPhoto = false
    private var frameCounter = 0
    private var currentBitmap: Bitmap? = null

    private var currentMediaInfo: MediaInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_video)

        viewModel = ViewModelProvider(this)[DetectionViewModel::class.java]
        yoloDetector = TFLiteYoloDetector(this)
        byteTracker = SimpleByteTracker(
            trackHighThresh = 0.5f,
            trackLowThresh = 0.1f,
            trackBuffer = 30,
            matchThresh = 0.8f
        )

        initViews()
        setupViewPager()
        setupListeners()
        observeViewModel()
        handleIncomingIntent()
    }

    /**
     * Handles "Send to Detection" from MediaViewerActivity / GalleryActivity.
     * If the intent contains "media_path", load that file directly in the DETECT tab
     * without going through the SELECT tab first.
     */
    private fun handleIncomingIntent() {
        val path = intent.getStringExtra("media_path") ?: return
        val file = java.io.File(path)
        if (!file.exists()) return
        val uri = android.net.Uri.fromFile(file)
        viewModel.selectMediaForDetection(uri)
        viewPager.setCurrentItem(TAB_DETECT, false)
    }

    private fun initViews() {
        tabLayout = findViewById(R.id.tab_layout)
        viewPager = findViewById(R.id.view_pager)
        btnBack = findViewById(R.id.btn_back)
        mediaSourceSpinner = findViewById(R.id.media_source_spinner)
        btnDownloadHeader = findViewById(R.id.btn_download_header)

        // DETECT tab components
        detectContainer = findViewById(R.id.detect_container)
        photoView = findViewById(R.id.photo_view)
        videoContainer = findViewById(R.id.video_container)
        playerView = findViewById(R.id.player_view)
        overlayView = findViewById(R.id.overlay_view) // For photos
        videoOverlay = findViewById(R.id.video_overlay) // For videos
        emptyState = findViewById(R.id.empty_state)

        tvStatus = findViewById(R.id.tv_status)
        tvDetectionResultsLabel = findViewById(R.id.tv_detection_results_label)
        tvDetectionStats = findViewById(R.id.tv_detection_stats)
        tvMediaInfo = findViewById(R.id.tv_media_info)
        tvProcessingLabel = findViewById(R.id.tv_processing_label)
        tvProcessingInfo = findViewById(R.id.tv_processing_info)
        progressBar = findViewById(R.id.progress_bar)
        processingInfoSection = findViewById(R.id.processing_info_section)

        setupMediaSourceSpinner()
    }

    private fun setupMediaSourceSpinner() {
        val sources = arrayOf("All", "Gallery")
        val spinnerAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item,
            sources
        )
        spinnerAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        mediaSourceSpinner.adapter = spinnerAdapter

        mediaSourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val fragment = supportFragmentManager.findFragmentByTag("f$TAB_SELECT") as? SelectFragment
                fragment?.onMediaSourceChanged(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupViewPager() {
        val adapter = TestVideoPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                TAB_SELECT -> "SELECT"
                TAB_DETECT -> "DETECT"
                else -> ""
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateUIForTab(position)
            }
        })
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnDownloadHeader?.setOnClickListener { downloadDetectedMedia() }
    }

    private fun observeViewModel() {
        viewModel.selectedMediaForDetection.observe(this) { uri ->
            uri?.let { loadMedia(it) }
        }

        viewModel.detections.observe(this) { detections ->
            updateDetectionResults(detections)
        }

        viewModel.detectionStats.observe(this) { stats ->
            // Stats update handled in updateDetectionResults
        }

        viewModel.processingProgress.observe(this) { progress ->
            progressBar.progress = progress
        }
    }

    private fun updateDetectionResults(detections: List<Detection>) {
        if (detections.isEmpty()) {
            tvDetectionResultsLabel?.visibility = View.GONE
            tvDetectionStats.visibility = View.GONE
        } else {
            tvDetectionResultsLabel?.visibility = View.VISIBLE
            tvDetectionStats.visibility = View.VISIBLE

            val detectedTypes = mutableMapOf<String, Int>()
            for (detection in detections) {
                val label = detection.label
                val currentCount = detectedTypes[label] ?: 0
                detectedTypes[label] = currentCount + 1
            }

            val statsText = buildString {
                append("Detected: ${detections.size}\n")
                for ((label, count) in detectedTypes) {
                    append("$label: $count\n")
                }
                if (detections.isNotEmpty()) {
                    var totalConf = 0.0
                    for (detection in detections) {
                        totalConf += detection.confidence
                    }
                    val avgConf = (totalConf / detections.size) * 100
                    append("Avg Conf: ${String.format(Locale.US, "%.2f%%", avgConf)}")
                }
            }
            tvDetectionStats.text = statsText.trimEnd()
        }

        // Draw detections on image/video
        if (isPhoto) {
            drawDetectionsOnPhoto(detections)
        } else {
            Log.d(TAG, "Updating video overlay with ${detections.size} detections")
            videoOverlay.updateDetections(detections)
        }
    }

    private fun drawDetectionsOnPhoto(detections: List<Detection>) {
        // Use overlay view for consistency with video
        overlayView.updateDetections(detections)
    }

    private fun drawDetectionOnCanvas(canvas: Canvas, detection: Detection, imgWidth: Int, imgHeight: Int) {
        val color = when (detection.classId) {
            0 -> Color.RED
            1 -> Color.YELLOW
            2 -> Color.MAGENTA
            3 -> Color.rgb(255, 165, 0)
            4 -> Color.CYAN
            5 -> Color.GREEN
            else -> Color.RED
        }

        val boxPaint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            this.color = Color.WHITE
            textSize = 48f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        val textBgPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
        }

        // Draw bounding box
        canvas.drawRect(detection.boundingBox, boxPaint)

        // Draw label
        val confidencePercent = (detection.confidence * 100).toInt()
        val text = "${detection.label} ${confidencePercent}%"

        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)

        val textX = detection.boundingBox.left
        val textY = detection.boundingBox.top - 10f

        val textBackgroundRect = RectF(
            textX,
            textY - textBounds.height() - 20f,
            textX + textBounds.width() + 30f,
            textY - 5f
        )
        canvas.drawRect(textBackgroundRect, textBgPaint)
        canvas.drawText(text, textX + 15f, textY - 10f, textPaint)
    }

    private fun updateUIForTab(position: Int) {
        when (position) {
            TAB_SELECT -> {
                btnDownloadHeader?.visibility = View.GONE
                detectContainer.visibility = View.GONE
            }
            TAB_DETECT -> {
                mediaSourceSpinner.visibility = View.GONE
                btnDownloadHeader?.visibility = if (currentMediaUri != null) View.VISIBLE else View.GONE
                detectContainer.visibility = View.VISIBLE
            }
        }
    }

    fun switchToDetectTab() {
        viewPager.setCurrentItem(TAB_DETECT, true)
    }

    private fun loadMedia(uri: Uri) {
        currentMediaUri = uri

        lifecycleScope.launch {
            try {
                // Get media info
                val mediaInfo = getMediaInfo(uri)
                currentMediaName = mediaInfo.name
                isPhoto = mediaInfo.type == MediaType.PHOTO

                withContext(Dispatchers.Main) {
                    emptyState.visibility = View.GONE
                    overlayView.clearDetections()
                    videoOverlay.clearDetections()
                    viewModel.clearDetections()
                    byteTracker.reset() // Reset tracker for new media

                    if (isPhoto) {
                        loadPhoto(uri, mediaInfo)
                    } else {
                        loadVideo(uri, mediaInfo)
                    }

                    updateMediaInfo(mediaInfo)
                    btnDownloadHeader?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading media", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TestVideoActivity, "Error loading media", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private data class MediaInfo(
        val type: MediaType,
        val name: String,
        val width: Int,
        val height: Int,
        val duration: Long = 0
    )

    private suspend fun getMediaInfo(uri: Uri): MediaInfo {
        return withContext(Dispatchers.IO) {
            // Determine type first
            val mimeType = contentResolver.getType(uri)
                ?: uri.path?.substringAfterLast('.')?.lowercase()?.let { ext ->
                    when (ext) {
                        "jpg", "jpeg", "png", "webp", "bmp", "gif" -> "image/jpeg"
                        "mp4", "avi", "mkv", "mov", "3gp", "wmv" -> "video/mp4"
                        else -> ""
                    }
                } ?: ""
            val type = if (mimeType.startsWith("image")) MediaType.PHOTO else MediaType.VIDEO

            // Get file name
            var name = "Unknown"
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: "Unknown"
                }
            }

            // Get dimensions based on type
            val (width, height, duration) = if (type == MediaType.VIDEO) {
                // Use MediaMetadataRetriever for video
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: -1
                        val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: -1
                        val d = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        retriever.release()
                        Triple(w, h, d)
                    } ?: Triple(-1, -1, 0L)
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting video metadata", e)
                    Triple(-1, -1, 0L)
                }
            } else {
                // Use BitmapFactory for photos
                contentResolver.openInputStream(uri)?.use { input ->
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(input, null, options)
                    Triple(options.outWidth, options.outHeight, 0L)
                } ?: Triple(0, 0, 0L)
            }

            MediaInfo(
                type = type,
                name = name,
                width = width,
                height = height,
                duration = duration
            )
        }
    }
    private fun updateMediaInfo(mediaInfo: MediaInfo) {
        val infoText = buildString {
            append("Type: ${if (mediaInfo.type == MediaType.PHOTO) "Photo" else "Video"}\n")
            append("Name: ${mediaInfo.name}\n")
            append("Resolution: ${mediaInfo.width}x${mediaInfo.height}")
            if (mediaInfo.type == MediaType.VIDEO && mediaInfo.duration > 0) {
                val seconds = mediaInfo.duration / 1000
                append("\nDuration: ${seconds}s")
            }
        }
        tvMediaInfo?.text = infoText

        // Processing section only visible for video
        val isVideo = mediaInfo.type == MediaType.VIDEO
        processingInfoSection?.visibility = if (isVideo) View.VISIBLE else View.GONE
        tvProcessingLabel?.visibility = View.GONE // never show spinning label on load
    }

    private suspend fun loadPhoto(uri: Uri, mediaInfo: MediaInfo) {
        withContext(Dispatchers.IO) {
            // Загружаем сырой bitmap
            val rawBitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }

            // Читаем EXIF поворот через второй поток (первый уже закрыт)
            val rotation = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) {
                Log.w(TAG, "EXIF read error: ${e.message}")
                0f
            }

            // Применяем поворот если нужно
            val bitmap = if (rawBitmap != null && rotation != 0f) {
                Log.d(TAG, "📸 Applying EXIF rotation: ${rotation}°")
                ImageHelper.rotateBitmap(rawBitmap, rotation).also { rawBitmap.recycle() }
            } else {
                rawBitmap
            }

            withContext(Dispatchers.Main) {
                bitmap?.let {
                    // Recycle old bitmap to prevent memory leak when loading multiple photos
                    val oldBm = currentBitmap
                    if (oldBm != null && oldBm != it && !oldBm.isRecycled) {
                        photoView.setImageBitmap(null) // detach before recycle
                        oldBm.recycle()
                    }
                    currentBitmap = it
                    photoView.setImageBitmap(it)
                    photoView.visibility = View.VISIBLE
                    videoContainer.visibility = View.GONE
                    overlayView.visibility = View.VISIBLE // Show photo overlay
                    videoOverlay.visibility = View.GONE // Hide video overlay

                    overlayView.setImageDimensions(it.width, it.height)

                    // Ð¡Ð¸Ð½Ñ…Ñ€Ð¾Ð½Ð¸Ð·Ð¸Ñ€ÑƒÐµÐ¼ overlay Ñ PhotoView (Ð·ÑƒÐ¼, Ð¿ÐµÑ€ÐµÐ¼ÐµÑ‰ÐµÐ½Ð¸Ðµ)
                    setupPhotoZoomSync() // Matrix sync

                    tvStatus.text = "Processing..."

                    // Start detection
                    detectPhoto(it)
                }
            }
        }
    }

    private fun detectPhoto(originalBitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "📸 Starting photo detection: ${originalBitmap.width}x${originalBitmap.height}")

                // Создаем immutable копию для детекции
                val bitmapForDetection = withContext(Dispatchers.Default) {
                    originalBitmap.copy(originalBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                }

                Log.d(TAG, "✅ Created detection copy: ${bitmapForDetection.width}x${bitmapForDetection.height}")

                // Детекция на Default dispatcher
                val detections = withContext(Dispatchers.Default) {
                    yoloDetector.detectObjects(bitmapForDetection)
                }

                Log.d(TAG, "✅ Detection complete: ${detections.size} objects found")

                // Обновляем UI
                withContext(Dispatchers.Main) {
                    viewModel.updateDetections(detections)
                    tvStatus.text = "Detection complete: ${detections.size} objects"
                }

                // Очищаем копию
                bitmapForDetection.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error detecting photo", e)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Detection failed: ${e.message}"
                    Toast.makeText(this@TestVideoActivity, "Detection failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupPhotoZoomSync() {
        // Ð¡Ð¸Ð½Ñ…Ñ€Ð¾Ð½Ð¸Ð·Ð¸Ñ€ÑƒÐµÐ¼ Ð¼Ð°ÑÑˆÑ‚Ð°Ð± Ð¸ Ð¿Ð¾Ð·Ð¸Ñ†Ð¸ÑŽ overlay Ñ PhotoView
        // Синхронизируем Matrix от PhotoView к overlay
        photoView.setOnMatrixChangeListener { // no parameter
            // Получаем Matrix (zoom + translation)
            val matrix = photoView.imageMatrix
            overlayView.setPhotoViewMatrix(matrix)
            overlayView.invalidate()
        }
    }

    private fun loadVideo(uri: Uri, mediaInfo: MediaInfo) {
        photoView.visibility = View.GONE
        overlayView.visibility = View.GONE // Hide photo overlay
        videoContainer.visibility = View.VISIBLE
        videoOverlay.visibility = View.VISIBLE // Show video overlay

        releasePlayer()

        tvStatus.text = "Loading video..."

        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            playerView.player = player

            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true // Auto-play

            // Set overlay dimensions for video
            videoOverlay.setImageDimensions(mediaInfo.width, mediaInfo.height)
            Log.d(TAG, "Video loaded: ${mediaInfo.width}x${mediaInfo.height}, overlay dims set")

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            tvStatus.text = "Processing..."
                            val currentPlayer = exoPlayer
                            if (currentPlayer != null && !isDetecting) {
                                startVideoDetection()
                            }
                        }
                        Player.STATE_ENDED -> {
                            stopVideoDetection()
                            tvStatus.text = "Video ended"
                        }
                        Player.STATE_BUFFERING -> {
                            tvStatus.text = "Buffering..."
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val currentPlayer = exoPlayer
                    if (isPlaying && currentPlayer != null && currentPlayer.playbackState == Player.STATE_READY) {
                        if (!isDetecting) {
                            tvStatus.text = "Processing..."
                            startVideoDetection()
                        }
                    } else {
                        stopVideoDetection()
                    }
                }
            })
        }

        // Setup video zoom/pan gestures
        setupVideoZoom()
    }

    private fun setupVideoZoom() {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val surfaceView = playerView.videoSurfaceView
                if (surfaceView != null) {
                    val scaleFactor = detector.scaleFactor
                    val newScaleX = (surfaceView.scaleX * scaleFactor).coerceIn(1f, 4f)
                    val newScaleY = (surfaceView.scaleY * scaleFactor).coerceIn(1f, 4f)

                    surfaceView.scaleX = newScaleX
                    surfaceView.scaleY = newScaleY

                    // videoOverlay ÃÂ¼ÃÂ°Ã‘ÂÃ‘Ë†Ã‘â€šÃÂ°ÃÂ±ÃÂ¸Ã‘â‚¬Ã‘Æ’ÃÂµÃ‘â€šÃ‘ÂÃ‘Â ÃÂ°ÃÂ²Ã‘â€šÃÂ¾ÃÂ¼ÃÂ°Ã‘â€šÃÂ¸Ã‘â€¡ÃÂµÃ‘ÂÃÂºÃÂ¸ Ã‘â€š.ÃÂº. ÃÂ²ÃÂ½Ã‘Æ’Ã‘â€šÃ‘â‚¬ÃÂ¸ video_container

                    // Constrain translation after scale change
                    constrainVideoTranslation()
                }
                return true
            }
        })

        var lastX = 0f
        var lastY = 0f
        var isDragging = false

        playerView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val surfaceView = playerView.videoSurfaceView
                    if (surfaceView != null && (surfaceView.scaleX > 1f || surfaceView.scaleY > 1f)) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY

                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                        }

                        if (isDragging) {
                            val newTranslationX = surfaceView.translationX + dx
                            val newTranslationY = surfaceView.translationY + dy

                            // Apply with constraints
                            surfaceView.translationX = newTranslationX
                            surfaceView.translationY = newTranslationY
                            constrainVideoTranslation()

                            // videoOverlay ÃÂ¿ÃÂµÃ‘â‚¬ÃÂµÃÂ¼ÃÂµÃ‘â€°ÃÂ°ÃÂµÃ‘â€šÃ‘ÂÃ‘Â ÃÂ°ÃÂ²Ã‘â€šÃÂ¾ÃÂ¼ÃÂ°Ã‘â€šÃÂ¸Ã‘â€¡ÃÂµÃ‘ÂÃÂºÃÂ¸ Ã‘â€š.ÃÂº. ÃÂ²ÃÂ½Ã‘Æ’Ã‘â€šÃ‘â‚¬ÃÂ¸ video_container

                            lastX = event.x
                            lastY = event.y
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Toggle play/pause on tap
                        exoPlayer?.let { player ->
                            if (player.isPlaying) {
                                player.pause()
                            } else {
                                player.play()
                            }
                        }
                    }
                }
            }
            true
        }
    }

    private fun constrainVideoTranslation() {
        val surfaceView = playerView.videoSurfaceView ?: return
        val scale = surfaceView.scaleX

        if (scale <= 1f) {
            surfaceView.translationX = 0f
            surfaceView.translationY = 0f
            return
        }

        // Calculate max translation to keep video within bounds
        val viewWidth = playerView.width.toFloat()
        val viewHeight = playerView.height.toFloat()

        val scaledWidth = viewWidth * scale
        val scaledHeight = viewHeight * scale

        val maxTranslationX = (scaledWidth - viewWidth) / 2f
        val maxTranslationY = (scaledHeight - viewHeight) / 2f

        // Constrain translation
        surfaceView.translationX = surfaceView.translationX.coerceIn(-maxTranslationX, maxTranslationX)
        surfaceView.translationY = surfaceView.translationY.coerceIn(-maxTranslationY, maxTranslationY)

        // videoOverlay ÃÂ¿ÃÂµÃ‘â‚¬ÃÂµÃÂ¼ÃÂµÃ‘â€°ÃÂ°ÃÂµÃ‘â€šÃ‘ÂÃ‘Â ÃÂ°ÃÂ²Ã‘â€šÃÂ¾ÃÂ¼ÃÂ°Ã‘â€šÃÂ¸Ã‘â€¡ÃÂµÃ‘ÂÃÂºÃÂ¸
    }

    /**
     * Crop black letterbox bars from video frame
     */
    private fun cropLetterbox(bitmap: Bitmap, mediaInfo: MediaInfo?): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Check if we have letterbox (black bars top/bottom)
        val videoAspect = mediaInfo?.width?.toFloat()?.div(mediaInfo.height.coerceAtLeast(1))
            ?: (width.toFloat() / height)
        val viewAspect = width.toFloat() / height

        if (abs(videoAspect - viewAspect) < 0.01f) {
            // No letterbox
            return bitmap
        }

        // Calculate content area (skip black bars)
        val contentHeight = (width / videoAspect).toInt()
        val offsetY = (height - contentHeight) / 2

        return if (offsetY > 0 && contentHeight > 0 && contentHeight < height) {
            // Crop to content area
            Bitmap.createBitmap(bitmap, 0, offsetY, width, contentHeight)
        } else {
            bitmap
        }
    }

    private fun startVideoDetection() {
        if (isDetecting) return

        isDetecting = true
        tvStatus.text = "Detecting..."
        frameCounter = 0
        byteTracker.reset()

        var lastFrameTime = System.currentTimeMillis()
        var detectionCount = 0
        var totalConfidence = 0f

        detectionJob = lifecycleScope.launch(Dispatchers.Default) {
            val textureView = playerView.videoSurfaceView as? TextureView

            // Wait for first frame to render
            delay(500)

            while (isActive) {
                val isPlaying = withContext(Dispatchers.Main) {
                    exoPlayer?.isPlaying == true
                }

                if (!isPlaying) break

                frameCounter++

                // Process every Nth frame
                if (frameCounter % SKIP_FRAMES == 0) {
                    try {
                        // Capture and copy frame on Main thread
                        val frameBitmap = withContext(Dispatchers.Main) {
                            textureView?.bitmap?.let { original ->
                                // Check if bitmap is valid (not empty/black)
                                if (original.width > 0 && original.height > 0 && !original.isRecycled) {
                                    // Create immutable copy immediately
                                    val fullCopy = original.copy(
                                        original.config ?: Bitmap.Config.ARGB_8888,
                                        false
                                    )
                                    // DON'T recycle original - TextureView owns it!

                                    // Crop letterbox BEFORE detection
                                    val cropped = cropLetterbox(fullCopy, currentMediaInfo)
                                    if (cropped != fullCopy) {
                                        fullCopy.recycle()
                                    }
                                    cropped
                                } else {
                                    null
                                }
                            }
                        }

                        if (frameBitmap != null && !frameBitmap.isRecycled) {
                            // Detect objects on Default dispatcher
                            val detections = yoloDetector.detectObjects(frameBitmap)

                            // Track objects
                            val trackedDetections = byteTracker.update(detections)

                            // Calculate FPS
                            val currentTime = System.currentTimeMillis()
                            val fps = if (currentTime != lastFrameTime) {
                                1000f / (currentTime - lastFrameTime)
                            } else 0f
                            lastFrameTime = currentTime

                            // Calculate average confidence
                            if (trackedDetections.isNotEmpty()) {
                                detectionCount++
                                totalConfidence += trackedDetections.map { it.confidence }.average().toFloat()
                            }
                            val avgConf = if (detectionCount > 0) totalConfidence / detectionCount else 0f

                            withContext(Dispatchers.Main) {
                                viewModel.updateDetections(trackedDetections)

                                tvProcessingInfo.text = buildString {
                                    append("FPS: %.1f\n".format(fps))
                                    append("Frames: $frameCounter\n")
                                    append("Tracked: ${byteTracker.getTrackCount()}\n")
                                    append("Avg Conf: %.1f%%".format(avgConf * 100))
                                }
                            }

                            // Clean up copy after use
                            frameBitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error detecting frame", e)
                    }
                }

                delay(33) // ~30 FPS
            }
        }
    }

    private fun stopVideoDetection() {
        isDetecting = false
        detectionJob?.cancel()
        detectionJob = null
    }

    private fun downloadDetectedMedia() {
        if (currentMediaUri == null) {
            Toast.makeText(this, "No media loaded", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val detections = viewModel.detections.value ?: emptyList()

                if (isPhoto) {
                    currentBitmap?.let { bitmap ->
                        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val canvas = Canvas(resultBitmap)

                        for (detection in detections) {
                            drawDetectionOnCanvas(canvas, detection, resultBitmap.width, resultBitmap.height)
                        }

                        saveBitmapToGallery(resultBitmap)
                    }
                } else {
                    // For video: capture current frame on Main thread
                    val textureView = playerView.videoSurfaceView as? TextureView
                    val frameBitmap = withContext(Dispatchers.Main) {
                        textureView?.bitmap?.let { original ->
                            // Create mutable copy immediately
                            // DON'T recycle original - TextureView owns it!
                            original.copy(Bitmap.Config.ARGB_8888, true)
                        }
                    }

                    if (frameBitmap != null) {
                        val canvas = Canvas(frameBitmap)

                        for (detection in detections) {
                            drawDetectionOnCanvas(canvas, detection, frameBitmap.width, frameBitmap.height)
                        }

                        saveBitmapToGallery(frameBitmap)
                        frameBitmap.recycle()
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@TestVideoActivity,
                                "Failed to capture video frame",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading media", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TestVideoActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun saveBitmapToGallery(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "detection_${timestamp}.jpg"

        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MineDetector")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(it, contentValues, null, null)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TestVideoActivity, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to gallery", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TestVideoActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } ?: run {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@TestVideoActivity, "Failed to create file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun releasePlayer() {
        stopVideoDetection()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        yoloDetector.close()
    }

    private class TestVideoPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                TAB_SELECT -> SelectFragment()
                TAB_DETECT -> DetectFragment()
                else -> SelectFragment()
            }
        }
    }

    class SelectFragment : Fragment() {
        private lateinit var recyclerView: RecyclerView
        private lateinit var emptyState: LinearLayout
        private lateinit var adapter: MediaAdapter
        private lateinit var viewModel: DetectionViewModel
        private var currentSource = 0

        private val storagePermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.any { it }) {
                loadMedia()
            }
        }

        private fun galleryPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            return inflater.inflate(R.layout.fragment_detect_select, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            recyclerView = view.findViewById(R.id.recycler_media)
            emptyState = view.findViewById(R.id.empty_state)

            viewModel = ViewModelProvider(requireActivity())[DetectionViewModel::class.java]

            setupRecyclerView()
            loadMedia()
        }

        fun onMediaSourceChanged(source: Int) {
            currentSource = source
            loadMedia()
        }

        private fun setupRecyclerView() {
            recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = MediaAdapter { mediaItem ->
                val uri = if (mediaItem.path.startsWith("content://")) {
                    Uri.parse(mediaItem.path)
                } else {
                    Uri.fromFile(File(mediaItem.path))
                }
                viewModel.selectMediaForDetection(uri)
                (activity as? TestVideoActivity)?.switchToDetectTab()
            }
            recyclerView.adapter = adapter
        }

        private fun loadMedia() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (!hasStoragePermission()) {
                    withContext(Dispatchers.Main) {
                        storagePermLauncher.launch(galleryPermissions())
                    }
                    return@launch
                }

                val mediaList = if (currentSource == 0) {
                    loadAllMedia()
                } else {
                    loadGalleryMedia()
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    if (mediaList.isEmpty()) {
                        emptyState.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyState.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        adapter.submitList(mediaList)
                    }
                }
            }
        }

        private fun hasStoragePermission(): Boolean {
            val context = requireContext()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                        PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }

        private fun loadAllMedia(): List<MediaItemModel> {
            val mediaList = mutableListOf<MediaItemModel>()

            // Images
            try {
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED
                )

                requireContext().contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(uri, id)

                        mediaList.add(
                            MediaItemModel(
                                path = contentUri.toString(),
                                type = MediaType.PHOTO,
                                name = cursor.getString(nameCol) ?: "",
                                size = cursor.getLong(sizeCol),
                                dateModified = cursor.getLong(dateCol) * 1000
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("SelectFragment", "Error loading images", e)
            }

            // Videos
            try {
                val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED
                )

                requireContext().contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(uri, id)

                        mediaList.add(
                            MediaItemModel(
                                path = contentUri.toString(),
                                type = MediaType.VIDEO,
                                name = cursor.getString(nameCol) ?: "",
                                size = cursor.getLong(sizeCol),
                                dateModified = cursor.getLong(dateCol) * 1000
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("SelectFragment", "Error loading videos", e)
            }

            return mediaList.sortedByDescending { it.dateModified }
        }

        private suspend fun loadGalleryMedia(): List<MediaItemModel> {
            val mediaList = mutableListOf<MediaItemModel>()
            val database = AppDatabase.getDatabase(requireContext())

            try {
                val detections = database.detectionDao().getAllDetections()
                for (detection in detections) {
                    val file = File(detection.photoPath)
                    if (file.exists()) {
                        val mediaType = when (file.extension.lowercase()) {
                            "jpg", "jpeg", "png", "webp", "bmp" -> MediaType.PHOTO
                            "mp4", "avi", "mkv", "mov", "3gp" -> MediaType.VIDEO
                            else -> MediaType.PHOTO
                        }

                        mediaList.add(
                            MediaItemModel(
                                path = detection.photoPath,
                                type = mediaType,
                                name = file.name,
                                size = file.length(),
                                dateModified = file.lastModified()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return mediaList.sortedByDescending { it.dateModified }
        }

        override fun onResume() {
            super.onResume()
            loadMedia()
        }
    }

    class DetectFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            return View(requireContext())
        }
    }
}