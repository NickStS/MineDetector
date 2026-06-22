package com.minedetector.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.minedetector.MineDetectorApplication
import com.minedetector.R
import com.minedetector.ml.TFLiteYoloDetector
import com.minedetector.BuildConfig
import com.minedetector.ui.components.VideoOverlayView
import com.minedetector.viewmodels.DetectionViewModel
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.ux.widget.FPVWidget
import dji.ux.widget.dashboard.CompassWidget
import java.io.File
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.locationcomponent.LocationComponentPlugin
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.logo.logo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DroneControlActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DroneControl"
        // Mapbox style image IDs for custom map markers (programmatic bitmaps)
        private const val DRONE_ICON_ID = "drone-icon"
        private const val HOME_ICON_ID  = "home-icon"
    }

    private lateinit var detectionViewModel: DetectionViewModel

    // DJI managers
    private var connectionManager: com.minedetector.dji.DJIConnectionManager? = null
    private var connectionMonitorJob: kotlinx.coroutines.Job? = null
    /** true = onResume должен выполнить полный реинит DJI (USB reconnect) */
    private var pendingUsbReinit = false

    /**
     * Страховочный ресивер на случай, если onNewIntent() не отработал
     * (например, Android направил USB_ACCESSORY_ATTACHED к другому приложению).
     * Регистрируется динамически в onResume, снимается в onPause.
     */
    private val usbAttachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
                Log.d(TAG, "USB BroadcastReceiver: accessory attached — scheduling DJI re-init")
                pendingUsbReinit = true
            }
        }
    }
    private var telemetryManager: com.minedetector.dji.DJITelemetryManager? = null
    private var isDjiAvailable = false

    // ── Manual VideoFeeder fallback (for devices where FPVWidget doesn't show video) ──
    private var manualCodecManager: DJICodecManager? = null
    private var manualVideoListener: VideoFeeder.VideoDataListener? = null
    private var videoCheckJob: Job? = null

    // AI detector
    private var tfliteDetector: TFLiteYoloDetector? = null

    // Views
    private lateinit var rootLayout: RelativeLayout
    private lateinit var overlayView: VideoOverlayView
    private lateinit var fpvWidget: FPVWidget
    // fpv_mini_container — прозрачный View поверх мини-FPV, перехватывает клики
    private lateinit var fpvMiniContainer: View
    // fpv_mini_bg — чёрный фон под мини-FPV (показывается когда карта fullscreen)
    private lateinit var fpvMiniBg: View
    // fpv_overlay_widget — DJI overlay: tap-to-focus (yellow ring), gimbal controls (blue circle)
    // Должен быть ВЫШЕ fpvWidget и НИЖЕ UI панелей — иначе элементы не видны/не нажимаются
    private lateinit var fpvOverlayWidget: View
    // Ч/б оверлей при отключении дрона (показывается поверх fpvWidget)
    private lateinit var fpvDisconnectOverlay: ImageView

    // AI controls
    private lateinit var btnAiDetection: FrameLayout
    private lateinit var tvAiLabel: TextView
    private lateinit var tvAiStatus: TextView
    private lateinit var tvDetectionCount: TextView

    // Album button
    private lateinit var btnAlbum: MaterialCardView

    // DJI GO 4-style gimbal pitch indicator (custom Canvas view)
    private lateinit var gimbalPitchView: com.minedetector.ui.components.DjiGimbalPitchIndicatorView

    // Notification
    private lateinit var notificationContainer: FrameLayout
    private lateinit var notificationIcon: ImageView
    private lateinit var notificationText: TextView

    // PreFlight checklist panel
    private lateinit var preflightChecklistPanel: View
    private lateinit var preflightOverlay: View

    // Map/Radar (✅ ПЕРЕДЕЛАНО ПОД MAPBOX)
    private var mapView: MapView? = null
    private lateinit var compassWidget: CompassWidget
    private lateinit var mapContainer: FrameLayout
    private lateinit var mapClickOverlay: View
    private lateinit var btnToggleRadar: ImageView
    private lateinit var btnCenterOnDrone: ImageView
    private lateinit var btnCenterOnMe: ImageView
    private lateinit var btnMapLayers: ImageView
    // Container for map controls (layers + GPS + person), centered vertically on screen
    private lateinit var mapControlsContainer: View
    private var isMapMode = false // false = compass by default (mapbox не всегда инициализируется)
    private var isMapFullscreen = false // true = map fullscreen, FPV mini

    // Map style cycling
    private val mapStyles = listOf(
        Style.SATELLITE_STREETS,
        Style.MAPBOX_STREETS,
        Style.SATELLITE,
        Style.OUTDOORS
    )
    private val mapStyleNames = listOf("Satellite+Roads", "Streets", "Satellite", "Terrain")
    private var currentMapStyleIndex = 0

    // ✅ Drone auto-follow: always follow drone camera
    // Set to false when user presses "Center on Me"; reset to true on mini-map or "Center on Drone"
    private var followDroneMode = true

    // ✅ Актуальные координаты пользователя — обновляются через Mapbox OnIndicatorPositionChangedListener
    // Всегда содержат свежее местоположение (та же точка что синяя dot на карте)
    private var currentUserLocation: Point? = null
    private val userLocationListener = OnIndicatorPositionChangedListener { point ->
        currentUserLocation = point
    }

    // DJI camera panels — hidden in fullscreen map mode, restored in camera mode
    private lateinit var cameraConfigBar: View
    private lateinit var cameraCapturePanel: View
    private lateinit var manualFocusWidget: View
    // Сохранённая видимость ManualFocusWidget — обновляется нашим поллингом режима фокуса.
    // GONE по умолчанию: камера стартует в AF-режиме (DJI default).
    // Поллинг обновляет это поле при каждой смене AF↔MF, корректно сохраняет/восстанавливает при swap.
    private var manualFocusSavedVisibility = View.GONE

    // Job для поллинга режима фокуса DJI камеры (500мс интервал).
    // DJI ManualFocusWidget НЕ обновляет свою видимость при смене AF↔MF после внешней манипуляции.
    // Поллим getFocusMode() и управляем видимостью вручную.
    private var focusModePollingJob: Job? = null

    // ── Capability flags — заполняются probeCameraCapabilities() при каждом подключении ──
    // Позволяет адаптироваться к любому дрону: Pro/Enterprise/Zoom/Thermal/etc.
    private var capFocusMode        = false   // getFocusMode() поддерживается
    private var capPhotoAspectRatio = false   // getPhotoAspectRatio() поддерживается
    private var capSystemStateCallback = false // setSystemStateCallback() поддерживается

    // Mapbox маркеры
    private var droneMarker: com.mapbox.maps.plugin.annotation.generated.PointAnnotation? = null
    private var homeMarker: com.mapbox.maps.plugin.annotation.generated.PointAnnotation? = null
    // ✅ ФИКС: AnnotationManager создаётся ОДИН раз, не пересоздаётся при каждом GPS обновлении
    private var pointAnnotationManager: com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager? = null
    private var lastDroneLatitude: Double = 0.0
    private var lastDroneLongitude: Double = 0.0
    // Last known drone heading (degrees, 0=North clockwise) — used to rotate the drone icon
    // and to restore correct rotation when the drone marker is recreated after a style reload.
    private var lastDroneHeading: Float = 0f
    private var isDroneEverPositioned: Boolean = false
    // Last known satellite count — updated from telemetryFlow even when lat/lon is 0 (no fix).
    // Used to show "GPS: X sat searching..." instead of "Drone position unknown" when
    // the GPS module is active but hasn't computed a position fix yet.
    private var lastKnownSatelliteCount: Int = 0


    // Initial layout sizes (saved for restoration)
    private val MINI_WIDTH_DP = 150
    private val MINI_HEIGHT_DP = 100

    // Текущее соотношение сторон видео (обновляется при смене разрешения/режима камеры)
    // По умолчанию 16:9 (горизонтальное видео дрона)
    private var videoAspectRatio: Float = 16f / 9f
    // Известные соотношения видео DJI камер (НЕ включаем соотношения экранов 21:9, 20:9, 19:9)
    // Используется для защиты от ложных обновлений когда DJI сбрасывает TextureView до экрана
    private val KNOWN_VIDEO_RATIOS = listOf(16f/9f, 4f/3f, 3f/2f, 1f/1f, 17f/9f)
    // Дебаунс для viewTreeObserver: DJI вызывает requestLayout() ~1-2 раза/сек внутренне.
    // Без дебаунса каждый вызов логирует "TextureView reset detected" → спам в логах.
    // 500мс = достаточно быстро реагировать на реальное изменение видео-формата.
    private var lastAspectRatioFixTime = 0L
    // Debounce for probePhotoRatioOnCodecChange() — called from viewTreeObserver (fires 1-2×/sec).
    // 600ms prevents hammering getPhotoAspectRatio() on every layout event.
    private var lastPhotoRatioProbeTime = 0L

    private var sessionDetections = 0
    private var isDetectionEnabled = false
    private var detectionJob: Job? = null

    // true when camera is in SHOOT_PHOTO mode — updated by cameraParamsPolling.
    // Prevents viewTreeObserver from overriding photo-mode aspect ratio (3:2 / 4:3):
    // DJI renders the live preview TextureView at 16:9 even in photo mode,
    // which would reset videoAspectRatio back to 16:9 and undo the 3:2 correction.
    @Volatile private var isPhotoMode = false

    // Only one inference at a time!
    // TFLite Interpreter is NOT thread-safe: concurrent run() → "Detection error: null" + garbage.
    // If previous frame is still being processed — drop the new one (better to skip than corrupt).
    private var inferenceJob: Job? = null

    // Job для поллинга режима камеры (фото/видео) и aspect ratio фото-режима
    private var cameraParamsPollingJob: Job? = null

    // Telemetry collector jobs — must be cancelled and restarted on each reconnect.
    // Without cancellation: every reconnect adds a new parallel collector →
    // duplicate updateDronePositionOnMap() calls, isDroneEverPositioned conflicts.
    private var telemetryJob: Job? = null
    private var gimbalJob: Job? = null
    private var gpsNoFixWarningJob: Job? = null

    // Throttling
    private var lastDetectionTime = 0L
    private val detectionIntervalMs = 150L

    // Показываем детекцию сразу, убираем только после N пустых кадров подряд.
    // Это нужно потому что модель видит мину через кадр (93% → пусто → 82%) — если
    // требовать N кадров ПОДРЯД для показа, пользователь не видит ни одной детекции.
    private var consecutiveDetectionFrames = 0       // сколько кадров подряд есть детекции
    private var noDetectionFrames = 0                // сколько кадров подряд нет детекций
    private val MIN_EMPTY_FRAMES_TO_CLEAR = 3        // ~1500ms тишины → убираем боксы

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drone_control)

        // ✅ CRITICAL: Prevent screen from locking during drone flight!
        // Without this, the screen times out per system settings (15s/30s/1min)
        // which is dangerous during active flight operations.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableImmersiveMode()

        initViewModels()
        initViews()
        initDetector()
        initDji()
        setupListeners()
        observeViewModels()

        // ✅ Применяем коррекцию пропорций через scaleX (не LayoutParams — DJI их сбрасывает)
        rootLayout.post { fixFpvAspectRatio() }
        rootLayout.postDelayed({ if (!isMapFullscreen) fixFpvAspectRatio() }, 1000)
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun initViewModels() {
        detectionViewModel = ViewModelProvider(this)[DetectionViewModel::class.java]
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.root_layout)
        overlayView = findViewById(R.id.overlay_view)
        fpvWidget = findViewById(R.id.fpv_widget)
        fpvDisconnectOverlay = findViewById(R.id.fpv_disconnect_overlay)
        fpvMiniContainer = findViewById(R.id.fpv_mini_container)
        fpvMiniBg = findViewById(R.id.fpv_mini_bg)

        // ✅ Слушатель изменения соотношения сторон видео внутри FPVWidget
        // DJI сам меняет TextureView при смене режима (фото 4:3 / фото 3:2 / видео 16:9 / и т.д.)
        fpvWidget.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val tv = findTextureViewInView(fpvWidget) ?: return@addOnGlobalLayoutListener
                if (tv.width <= 0 || tv.height <= 0) return@addOnGlobalLayoutListener
                val ratio = tv.width.toFloat() / tv.height.toFloat()
                if (!isMapFullscreen && ratio > 0.3f && ratio < 4.0f) {
                    // DEBOUNCE only for videoAspectRatio updates (prevents log spam).
                    // DJI calls requestLayout() ~1-2×/sec internally.
                    // IMPORTANT: do NOT debounce fpvWidget.post { fixFpvAspectRatio() } —
                    // DJI may reset TextureView scaleX=1.0 between our fix and the next layout event,
                    // and we must reapply immediately, not skip it for 500ms.
                    val now = System.currentTimeMillis()
                    val ratioChanged = Math.abs(ratio - videoAspectRatio) > 0.05f
                    if (ratioChanged && now - lastAspectRatioFixTime >= 500L) {
                        lastAspectRatioFixTime = now
                        // Only update videoAspectRatio for known DJI video formats.
                        // When DJI resets TextureView to screen size (e.g. 21:9) after mini→fullscreen
                        // swap, that is NOT a real video ratio change — do not overwrite.
                        val isKnownVideoRatio = KNOWN_VIDEO_RATIOS.any { kotlin.math.abs(it - ratio) < 0.07f }
                        // In photo mode: accept 3:2 and 4:3 updates from the TextureView layout.
                        // Block 16:9 in photo mode — DJI sometimes resets the TextureView to 16:9
                        // as part of its codec reconfiguration even when the photo ratio is 3:2/4:3.
                        val isNonSixteenNine = kotlin.math.abs(ratio - 16f / 9f) > 0.08f
                        if (isKnownVideoRatio && (!isPhotoMode || isNonSixteenNine)) {
                            Log.d(TAG, "${if (isPhotoMode) "📷 Photo" else "🎥 Video"} ratio from TextureView: $videoAspectRatio → $ratio")
                            videoAspectRatio = ratio
                        }
                    }
                    // ALWAYS reapply scaleX — DJI internally resets TextureView.scaleX=1.0
                    // as part of its layout cycle. fixFpvAspectRatio() returns immediately
                    // if the scaleX is already correct (abs(current - target) < 0.001).
                    fpvWidget.post { fixFpvAspectRatio() }

                    // Secondary fallback: query camera.getPhotoAspectRatio() directly.
                    // Catches photo-mode ratio without relying on isPhotoMode flag or
                    // the DJI control-plane (setSystemStateCallback) which can lag seconds.
                    // Internal 600ms debounce prevents excessive API calls.
                    probePhotoRatioOnCodecChange()
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // AI controls
        btnAiDetection = findViewById(R.id.btn_ai_detection)
        tvAiLabel = findViewById(R.id.tv_ai_label)
        tvAiStatus = findViewById(R.id.tv_ai_status)
        tvDetectionCount = findViewById(R.id.tv_detection_count)

        // Album
        btnAlbum = findViewById(R.id.btn_album)

        // Gimbal center button — сброс pitch + yaw → 0°
        findViewById<android.view.View>(R.id.btn_gimbal_center).setOnClickListener {
            centerGimbal()
        }

        // Gimbal pitch indicator (DJI GO 4 style)
        gimbalPitchView = findViewById(R.id.gimbal_pitch_view)

        // Notification
        notificationContainer = findViewById(R.id.notification_container)
        notificationIcon = findViewById(R.id.notification_icon)
        notificationText = findViewById(R.id.notification_text)

        // Back button
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        // PreFlight checklist panel + dismiss-on-touch-outside overlay
        preflightChecklistPanel = findViewById(R.id.preflight_checklist_panel)
        preflightOverlay = findViewById(R.id.preflight_overlay)
        preflightOverlay.setOnClickListener {
            preflightChecklistPanel.visibility = View.GONE
            preflightOverlay.visibility = View.GONE
        }

        // Map/Radar
        mapContainer = findViewById(R.id.map_container)
        compassWidget = findViewById(R.id.compass_widget)
        mapClickOverlay = findViewById(R.id.map_click_overlay)
        btnToggleRadar = findViewById(R.id.btn_toggle_radar)
        btnCenterOnDrone = findViewById(R.id.btn_center_on_drone)
        btnCenterOnMe = findViewById(R.id.btn_center_on_me)
        btnMapLayers = findViewById(R.id.btn_map_layers)
        mapControlsContainer = findViewById(R.id.map_controls_container)

        // DJI camera panels (скрываются в fullscreen карте)
        cameraConfigBar = findViewById(R.id.camera_config_bar)
        cameraCapturePanel = findViewById(R.id.CameraCapturePanel)
        manualFocusWidget = findViewById(R.id.manual_focus_widget)
        // Прячем до подключения дрона — камера по умолчанию в AF-режиме.
        // Поллинг режима фокуса (startFocusModePolling) обновит видимость при подключении.
        manualFocusWidget.visibility = View.GONE
        // ✅ FPV overlay: tap-to-focus, gimbal rotation — должен быть выше fpvWidget
        fpvOverlayWidget = findViewById(R.id.fpv_overlay_widget)

        initMapWidget()
    }

    private fun initMapWidget() {
        try {
            // ✅ MAPBOX: Инициализация Mapbox MapView
            mapView = findViewById(R.id.map_view)

            Log.d(TAG, "Initializing Mapbox...")

            // Показываем компас по умолчанию (надежнее)
            mapView?.visibility = View.GONE
            compassWidget.visibility = View.VISIBLE
            isMapMode = false
            btnToggleRadar.setImageResource(R.drawable.ic_map)

            // Fix compass size
            fixCompassSize()

            // ✅ Отключаем gestures для миникарты (включим только в fullscreen)
            disableMapGestures()

            // ✅ Auto-unfollow: when user starts panning the map manually in fullscreen mode,
            // disable drone follow so the camera stays where they left it.
            // In mini mode gestures are disabled so this listener never fires for mini.
            mapView?.gestures?.addOnMoveListener(object : OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    if (isMapFullscreen && followDroneMode) {
                        followDroneMode = false
                        Log.d(TAG, "User panned map → drone follow disabled")
                    }
                }
                override fun onMove(detector: MoveGestureDetector): Boolean = false
                override fun onMoveEnd(detector: MoveGestureDetector) {}
            })

            // ✅ Настраиваем встроенные UI элементы Mapbox
            // Компас: отключён пока карта не fullscreen (иначе виден на мини-карте/камере)
            mapView?.compass?.enabled = false
            mapView?.scalebar?.enabled = false // Масштаб убираем
            mapView?.attribution?.enabled = false // Надпись "Mapbox" убираем
            mapView?.logo?.enabled = false // Логотип Mapbox убираем

            // ✅ Компас: прямо под top_bar (44dp) у правого края
            // В fullscreen-карте DJI CameraCapturePanel скрыт → можно прижать к краю
            try {
                mapView?.compass?.updateSettings {
                    marginTop = dpToPx(54).toFloat()  // 44 top_bar + 10 зазор
                    marginRight = dpToPx(4).toFloat() // прижат к правому краю
                }
                Log.d(TAG, "✅ Compass margin set: top=54dp, right=4dp")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set compass margin: ${e.message}")
            }

            // ✅ Инициализируем Mapbox с satellite стилем — ОДИН РАЗ!
            mapView?.mapboxMap?.loadStyle(Style.SATELLITE_STREETS) { loadedStyle ->
                Log.d(TAG, "✅ Mapbox style loaded!")

                // ✅ Register custom drone/home bitmaps BEFORE creating annotations.
                // "marker-15" does not exist in Mapbox v11 sprite → invisible markers.
                // Custom programmatic bitmaps are guaranteed to be available.
                // Pass style directly (not via mapboxMap.style) to avoid any timing races.
                addCustomIconsToStyle(loadedStyle)

                // ✅ КРИТИЧНО: AnnotationManager создаётся ОДИН раз здесь!
                pointAnnotationManager = mapView?.annotations?.createPointAnnotationManager()
                Log.d(TAG, "✅ AnnotationManager created once")

                // ✅ ФИКС TIMING: Если телеметрия пришла ДО готовности карты — создаём маркер сейчас
                if (lastDroneLatitude != 0.0 && lastDroneLongitude != 0.0 && !isDroneEverPositioned) {
                    Log.d(TAG, "✅ Retroactively creating drone marker (coords arrived before map was ready)")
                    updateDronePositionOnMap(lastDroneLatitude, lastDroneLongitude, null, null, lastDroneHeading)
                }

                // Устанавливаем дефолтную позицию камеры (чтобы не показывался Дубай)
                // Камера сдвинется к дрону/пользователю при первом появлении
                val defaultCamera = CameraOptions.Builder()
                    .zoom(2.0)
                    .build()
                // ✅ ФИКС: через post чтобы не уходить за верхний край
                mapView?.post {
                    mapView?.mapboxMap?.setCamera(defaultCamera)
                }

                runOnUiThread {
                    showNotification("Map ready", false)
                    enableUserLocationOnMap()
                }
            }

            Log.d(TAG, "✅ Mapbox initialized (Compass shown by default)")
        } catch (e: Exception) {
            Log.e(TAG, "Mapbox init error: ${e.message}", e)
        }
    }

    /**
     * Включает синюю точку местоположения пользователя на Mapbox карте
     * Работает только если есть разрешение ACCESS_FINE_LOCATION
     */
    private fun enableUserLocationOnMap() {
        try {
            val hasLocationPermission = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasLocationPermission) {
                Log.d(TAG, "Location permission not granted, skipping user location on map")
                return
            }

            mapView?.location?.apply {
                enabled = true
                pulsingEnabled = true // Пульсирующая синяя точка
                Log.d(TAG, "✅ User location enabled on map (blue pulsing dot)")
            }

            // ✅ Подписываемся на обновления позиции пользователя через Mapbox
            // Это тот же источник что синяя точка — всегда актуальные координаты
            mapView?.location?.addOnIndicatorPositionChangedListener(userLocationListener)
            Log.d(TAG, "✅ User position listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable user location on map: ${e.message}", e)
        }
    }

    private fun initDetector() {
        try {
            tfliteDetector = TFLiteYoloDetector(this)
            Log.d(TAG, "TFLite detector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init detector: ${e.message}")
            showNotification("AI model load failed", true)
        }
    }

    private fun initDji() {
        if (MineDetectorApplication.TEST_MODE) {
            isDjiAvailable = false
            showNotification("Test mode active", false)
            return
        }

        try {
            connectionManager = com.minedetector.dji.DJIConnectionManager(this)

            connectionManager?.registerApp(
                onSuccess = {
                    isDjiAvailable = true
                    runOnUiThread {
                        showNotification("DJI SDK registered", false)
                        startConnectionMonitoring()
                    }
                },
                onError = { msg ->
                    isDjiAvailable = false
                    runOnUiThread {
                        showNotification("DJI: $msg", true)
                        // Safety net: even on genuine SDK errors, DJI may still fire
                        // onProductConnect if the drone is already physically connected.
                        // Starting the collector ensures we don't miss that event.
                        startConnectionMonitoring()
                    }
                }
            )
        } catch (e: Throwable) {
            isDjiAvailable = false
            Log.e(TAG, "DJI init failed: ${e.message}")
            showNotification("DJI init failed", true)
        }
    }

    private fun startConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = lifecycleScope.launch {
            connectionManager?.isConnected?.collect { connected ->
                isDjiAvailable = connected
                if (connected) {
                    Log.d(TAG, "Drone connected")
                    showNotification("Drone connected", false)

                    // ✅ Убираем frozen-frame (если дрон был отключён до этого)
                    hideFrozenFrame()

                    // ✅ Сбрасываем GPS-состояние при каждом подключении, чтобы карта
                    // правильно центрировалась при первом GPS-фиксе нового сеанса.
                    isDroneEverPositioned = false
                    lastDroneLatitude = 0.0
                    lastDroneLongitude = 0.0
                    lastKnownSatelliteCount = 0

                    // ✅ При подключении дрона применяем коррекцию scaleX
                    rootLayout.postDelayed({ if (!isMapFullscreen) fixFpvAspectRatio() }, 300)

                    // ✅ Перехватываем SurfaceTextureListener для мгновенного детекта
                    // смены размера буфера кодека (1632×1088 → 3:2, 1920×1088 → 16:9).
                    // DJI control API (getMode/setSystemStateCallback) отстаёт на секунды.
                    fpvWidget.post { interceptTextureViewSizeChanges() }

                    // ✅ Используем уже инициализированный telemetryManager из connectionManager
                    telemetryManager = connectionManager?.telemetryManager
                    startTelemetryMonitoring()

                    // ✅ Probe camera capabilities — адаптируемся к любому дрону.
                    // Ждём 800мс чтобы camera успела инициализироваться после connect,
                    // потом тестируем каждый API вызов. Флаги capXxx используются
                    // в startFocusModePolling() и startCameraParamsPolling().
                    probeCameraCapabilities()

                    // ✅ FPV VIDEO FALLBACK: через 3 секунды проверяем, показывает ли
                    // FPVWidget видео. Если нет — вручную подключаем VideoFeeder.
                    // Нужно для Xiaomi/MIUI и других устройств, где FPVWidget молча
                    // не показывает видео (известная проблема DJI SDK v4 + MIUI).
                    scheduleVideoCheck()

                    Log.d(TAG, "Telemetry + camera monitoring started " +
                        "(focus=$capFocusMode ratio=$capPhotoAspectRatio sysState=$capSystemStateCallback)")
                } else {
                    Log.d(TAG, "Drone disconnected")
                    showNotification("Drone disconnected", true)

                    // Захватываем bitmap СЕЙЧАС (до post), пока DJI ещё не очистил TextureView.
                    // fpvWidget.post {} откладывает вызов — к тому моменту tv.bitmap уже null.
                    val lastFrame = findTextureViewInView(fpvWidget)?.bitmap
                    fpvWidget.post { showFrozenFrame(lastFrame) }

                    // Stop polling jobs
                    focusModePollingJob?.cancel()
                    focusModePollingJob = null
                    cameraParamsPollingJob?.cancel()
                    cameraParamsPollingJob = null
                    telemetryJob?.cancel()
                    telemetryJob = null
                    gimbalJob?.cancel()
                    gimbalJob = null
                    gpsNoFixWarningJob?.cancel()
                    gpsNoFixWarningJob = null
                    lastKnownSatelliteCount = 0
                    runOnUiThread { gimbalPitchView.visibility = View.GONE }

                    // Stop AI detection if running
                    if (isDetectionEnabled) {
                        toggleDetection()
                    }
                }
            }
        }
    }

    private fun startTelemetryMonitoring() {
        // Cancel any previous collectors — each reconnect must start fresh.
        // Without this, old coroutines keep running in parallel, causing duplicate
        // updateDronePositionOnMap() calls and isDroneEverPositioned race conditions.
        telemetryJob?.cancel()
        gimbalJob?.cancel()
        gpsNoFixWarningJob?.cancel()

        // Read real pitch limits from the drone's gimbal capabilities.
        // Runs on background thread; updates gimbalPitchView.min/maxDeg on UI thread.
        lifecycleScope.launch(Dispatchers.IO) { readAndApplyPitchRange() }

        // "No GPS fix" notification removed — DJI's own status bar already warns about GPS.
        // gpsNoFixWarningJob stays null (cancelled on disconnect as usual).

        var gpsEverAcquired = false
        var mapUpdateCounter = 0

        if (telemetryManager == null) {
            Log.e(TAG, "❌ telemetryJob: telemetryManager is NULL — map will not update!")
        } else {
            Log.d(TAG, "📡 telemetryJob: starting collect on telemetryManager=$telemetryManager")
        }

        telemetryJob = lifecycleScope.launch {
            telemetryManager?.telemetryFlow?.collect { telemetry ->
                // First valid GPS fix in this session
                if (telemetry.latitude != 0.0 && telemetry.longitude != 0.0) {
                    if (!gpsEverAcquired) {
                        gpsEverAcquired = true
                        gpsNoFixWarningJob?.cancel() // cancel "no fix" warning — we have fix
                        showNotification("GPS: ${telemetry.satelliteCount} satellites", false)
                    }
                    mapUpdateCounter++
                    val homePointData = telemetryManager?.getHomePoint()
                    updateDronePositionOnMap(
                        telemetry.latitude,
                        telemetry.longitude,
                        homePointData?.first,
                        homePointData?.second,
                        telemetry.heading   // rotate drone icon to match flight direction
                    )
                } else {
                    // lat/lon is 0 (NaN from DJI → no GPS position fix yet).
                    // Still update satellite count so centerMapOnDrone() can show useful info.
                    lastKnownSatelliteCount = telemetry.satelliteCount
                }
            }
        }

        // Gimbal pitch: sync DjiGimbalPitchIndicatorView from telemetry (display-only, no drag)
        gimbalJob = lifecycleScope.launch {
            telemetryManager?.gimbalPitchFlow?.collect { pitch ->
                runOnUiThread {
                    gimbalPitchView.pitchDeg = pitch
                    if (!isMapFullscreen) {
                        gimbalPitchView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /**
     * Поллит режим фокуса DJI камеры каждые 500мс и управляет видимостью ManualFocusWidget.
     *
     * Проблема: DJI ManualFocusWidget устанавливает visibility только при первом подключении
     * камеры (GONE для AF, VISIBLE для MF), но НЕ обновляет его при последующих переключениях
     * AF↔MF — особенно после внешнего изменения visibility (map swap).
     *
     * Решение: поллим camera.getFocusMode() каждые 500мс.
     * При изменении режима — обновляем manualFocusSavedVisibility и (если не в fullscreen карте)
     * сразу применяем к виджету.
     */
    /**
     * Тестирует каждый camera API на реальном подключённом дроне.
     * Результаты сохраняются в cap* флаги и используются в polling функциях.
     * Работает с любым дроном — Pro, Enterprise, Zoom, Thermal, Agras и т.д.
     */
    private fun probeCameraCapabilities() {
        lifecycleScope.launch {
            // Даём камере 800мс на инициализацию после connect
            delay(800)

            val camera = (dji.sdk.sdkmanager.DJISDKManager.getInstance().product
                as? dji.sdk.products.Aircraft)?.camera

            if (camera == null) {
                Log.w(TAG, "probeCameraCapabilities: camera=null, all caps=false")
                capFocusMode = false; capPhotoAspectRatio = false; capSystemStateCallback = false
                return@launch
            }

            val model = dji.sdk.sdkmanager.DJISDKManager.getInstance().product?.model
            Log.d(TAG, "probeCameraCapabilities: model=$model")

            // ── 1. Probe getFocusMode ─────────────────────────────────────────
            var focusProbeResult = false
            val focusLatch = java.util.concurrent.CountDownLatch(1)
            try {
                camera.getFocusMode(object : dji.common.util.CommonCallbacks
                    .CompletionCallbackWith<dji.common.camera.SettingsDefinitions.FocusMode> {
                    override fun onSuccess(v: dji.common.camera.SettingsDefinitions.FocusMode) {
                        focusProbeResult = true; focusLatch.countDown()
                    }
                    override fun onFailure(e: dji.common.error.DJIError?) {
                        // onFailure = API есть, просто ошибка — считаем поддерживаемым
                        focusProbeResult = true; focusLatch.countDown()
                    }
                })
            } catch (e: Exception) {
                Log.d(TAG, "probe getFocusMode exception: ${e.javaClass.simpleName}")
                focusLatch.countDown()
            }
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                focusLatch.await(1, java.util.concurrent.TimeUnit.SECONDS)
            }
            capFocusMode = focusProbeResult

            // ── 2. Probe getPhotoAspectRatio ──────────────────────────────────
            var ratioProbeResult = false
            val ratioLatch = java.util.concurrent.CountDownLatch(1)
            try {
                camera.getPhotoAspectRatio(object : dji.common.util.CommonCallbacks
                    .CompletionCallbackWith<dji.common.camera.SettingsDefinitions.PhotoAspectRatio> {
                    override fun onSuccess(v: dji.common.camera.SettingsDefinitions.PhotoAspectRatio) {
                        ratioProbeResult = true; ratioLatch.countDown()
                    }
                    override fun onFailure(e: dji.common.error.DJIError?) {
                        ratioProbeResult = true; ratioLatch.countDown()
                    }
                })
            } catch (e: Exception) {
                Log.d(TAG, "probe getPhotoAspectRatio exception: ${e.javaClass.simpleName}")
                ratioLatch.countDown()
            }
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                ratioLatch.await(1, java.util.concurrent.TimeUnit.SECONDS)
            }
            capPhotoAspectRatio = ratioProbeResult

            // ── 3. Probe setSystemStateCallback ──────────────────────────────
            // ВАЖНО: probe с null недостаточен — Enterprise может принять null (no-op),
            // но бросить UnsupportedOperationException при реальном (non-null) колбеке.
            // Поэтому пробуем с реальным (пустым) колбеком, потом сразу убираем его.
            capSystemStateCallback = try {
                camera.setSystemStateCallback { _ -> /* probe callback — immediately replaced */ }
                camera.setSystemStateCallback(null)   // снять колбек после пробы
                true
            } catch (e: Exception) {
                Log.d(TAG, "probe setSystemStateCallback exception: ${e.javaClass.simpleName}")
                false
            }

            Log.d(TAG, "Camera caps — focus=$capFocusMode ratio=$capPhotoAspectRatio sysState=$capSystemStateCallback")

            // Запускаем polling только для поддерживаемых API
            if (capFocusMode)   startFocusModePolling()
            if (capPhotoAspectRatio || capSystemStateCallback) startCameraParamsPolling()
        }
    }

    private fun startFocusModePolling() {
        focusModePollingJob?.cancel()
        focusModePollingJob = lifecycleScope.launch {
            Log.d(TAG, "Focus mode polling started")
            while (isDjiAvailable) {
                delay(500)
                if (!isDjiAvailable) break
                try {
                    val aircraft = dji.sdk.sdkmanager.DJISDKManager.getInstance().product
                        as? dji.sdk.products.Aircraft ?: continue
                    // CompletionCallbackWith — интерфейс с двумя методами (onSuccess/onFailure),
                    // Kotlin не может SAM-конвертировать его в лямбду → нужен explicit object.
                    aircraft.camera?.getFocusMode(
                        object : dji.common.util.CommonCallbacks.CompletionCallbackWith<dji.common.camera.SettingsDefinitions.FocusMode> {
                            override fun onSuccess(focusMode: dji.common.camera.SettingsDefinitions.FocusMode) {
                                val isMf = focusMode == dji.common.camera.SettingsDefinitions.FocusMode.MANUAL
                                val newVis = if (isMf) View.VISIBLE else View.GONE
                                // Обновляем только при реальном изменении (не спамим)
                                if (manualFocusSavedVisibility != newVis) {
                                    Log.d(TAG, "📷 Focus: $focusMode → ManualFocusWidget ${if (isMf) "VISIBLE" else "GONE"}")
                                    runOnUiThread {
                                        manualFocusSavedVisibility = newVis
                                        // В режиме fullscreen карты — только сохраняем, не показываем
                                        if (!isMapFullscreen) {
                                            manualFocusWidget.visibility = newVis
                                        }
                                    }
                                }
                            }
                            override fun onFailure(error: dji.common.error.DJIError?) {
                                // Игнорируем ошибки (напр. камера временно недоступна)
                            }
                        }
                    )
                } catch (e: Exception) {
                    // Ошибки поллинга молча игнорируем (напр. при отключении дрона)
                }
            }
            Log.d(TAG, "Focus mode polling stopped")
        }
    }

    /**
     * Поллит режим камеры (фото / видео) и aspect ratio фото-режима каждую секунду.
     *
     * Проблема: DJI FPVWidget всегда растягивает превью до размеров TextureView (экран 21:9).
     * fixFpvAspectRatio() корректирует через scaleX = videoAspectRatio / screenRatio.
     * Но videoAspectRatio по умолчанию = 16:9 и никогда не обновляется при смене режима камеры.
     *
     * Результат: в фото-режиме 3:2 картинка отображается как 16:9 (лишнее сжатие).
     *
     * Фикс: при переходе в SHOOT_PHOTO — обновляем videoAspectRatio по PhotoAspectRatio.
     *        при переходе в RECORD_VIDEO — сбрасываем на 16:9.
     */
    /**
     * Запрашивает PhotoAspectRatio у DJI и обновляет videoAspectRatio.
     * При получении 16:9 в фото-режиме (DJI может врать сразу после смены кодека) —
     * schedules повторный запрос через [retryDelay] мс.
     */
    private fun fetchPhotoAspectRatio(camera: dji.sdk.camera.Camera, retryDelay: Long = 0L) {
        if (!capPhotoAspectRatio) return
        try { camera.getPhotoAspectRatio(
            object : dji.common.util.CommonCallbacks.CompletionCallbackWith<dji.common.camera.SettingsDefinitions.PhotoAspectRatio> {
                override fun onSuccess(ratio: dji.common.camera.SettingsDefinitions.PhotoAspectRatio) {
                    Log.d(TAG, "📷 getPhotoAspectRatio → ${ratio.name} (retryDelay=${retryDelay}ms)")
                    val newRatio = when (ratio) {
                        dji.common.camera.SettingsDefinitions.PhotoAspectRatio.RATIO_3_2  -> 3f / 2f
                        dji.common.camera.SettingsDefinitions.PhotoAspectRatio.RATIO_4_3  -> 4f / 3f
                        dji.common.camera.SettingsDefinitions.PhotoAspectRatio.RATIO_16_9 -> 16f / 9f
                        else -> {
                            // RATIO_UNKNOWN → fallback: read TV layout dims then bitmap
                            inferPhotoRatioFromFpvBitmap()
                            return
                        }
                    }
                    // DJI sometimes returns RATIO_16_9 immediately after codec reconfiguration
                    // even when camera was set to 3:2. Schedule retries for up to 800ms.
                    if (newRatio == 16f / 9f && isPhotoMode && retryDelay < 800L) {
                        val nextDelay = if (retryDelay == 0L) 300L else retryDelay + 400L
                        Log.w(TAG, "📷 getPhotoAspectRatio returned 16:9 in photo mode — retrying in ${nextDelay}ms")
                        fpvWidget.postDelayed({ fetchPhotoAspectRatio(camera, nextDelay) }, nextDelay)
                        // Meanwhile, try bitmap inference as best-effort
                        if (retryDelay == 0L) inferPhotoRatioFromFpvBitmap()
                        return
                    }
                    videoAspectRatio = newRatio
                    runOnUiThread { fpvWidget.post { fixFpvAspectRatio() } }
                }
                override fun onFailure(e: dji.common.error.DJIError?) {
                    Log.e(TAG, "getPhotoAspectRatio failed: ${e?.description}")
                    inferPhotoRatioFromFpvBitmap()
                }
            }
        ) } catch (e: Exception) {
            Log.w(TAG, "fetchPhotoAspectRatio exception: ${e.javaClass.simpleName}")
            inferPhotoRatioFromFpvBitmap()
        }
    }

    private fun startCameraParamsPolling() {
        cameraParamsPollingJob?.cancel()
        cameraParamsPollingJob = lifecycleScope.launch {
            Log.d(TAG, "Camera params polling started")

            // ✅ FIX #1: Install DJI camera-mode and photo-ratio callbacks for IMMEDIATE
            // notification (avoid waiting for next 300ms poll cycle).
            // The polling loop below remains as a safety net (callbacks miss some edge cases).
            try {
                val aircraft = dji.sdk.sdkmanager.DJISDKManager.getInstance().product
                    as? dji.sdk.products.Aircraft
                val camera = aircraft?.camera
                if (camera != null && capSystemStateCallback) {
                    // SystemState callback — fires on mode changes SHOOT_PHOTO ↔ RECORD_VIDEO.
                    // Только если поддерживается (capSystemStateCallback=true из probe).
                    camera.setSystemStateCallback { systemState ->
                        when (systemState.mode) {
                            dji.common.camera.SettingsDefinitions.CameraMode.SHOOT_PHOTO -> {
                                val wasPhoto = isPhotoMode
                                isPhotoMode = true
                                if (!wasPhoto) {
                                    Log.d(TAG, "📷 Camera→SHOOT_PHOTO [callback] — resetting debounce")
                                    lastAspectRatioFixTime = 0L  // allow viewTreeObserver to update immediately
                                    fetchPhotoAspectRatio(camera, retryDelay = 0L)
                                }
                            }
                            dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO -> {
                                val wasPhoto = isPhotoMode
                                isPhotoMode = false
                                if (wasPhoto) {
                                    Log.d(TAG, "🎥 Camera→RECORD_VIDEO [callback]")
                                    runOnUiThread { updateVideoAspectRatio(16f / 9f) }
                                }
                            }
                            else -> {}
                        }
                    }
                    // Note: DJI SDK v4 has no setPhotoAspectRatioCallback — ratio changes are
                    // handled by fetchPhotoAspectRatio() called from the polling loop below.
                    Log.d(TAG, "✅ DJI camera system-state callback registered")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not register DJI camera callbacks: ${e.message}")
            }

            while (isDjiAvailable) {
                delay(300)   // 300ms safety-net poll (catches cases callbacks miss)
                if (!isDjiAvailable) break
                try {
                    val aircraft = dji.sdk.sdkmanager.DJISDKManager.getInstance().product
                        as? dji.sdk.products.Aircraft ?: continue
                    val camera = aircraft.camera ?: continue

                    // 1. Получаем текущий режим камеры
                    camera.getMode(
                        object : dji.common.util.CommonCallbacks.CompletionCallbackWith<dji.common.camera.SettingsDefinitions.CameraMode> {
                            override fun onSuccess(mode: dji.common.camera.SettingsDefinitions.CameraMode) {
                                when (mode) {
                                    dji.common.camera.SettingsDefinitions.CameraMode.SHOOT_PHOTO -> {
                                        val wasPhoto = isPhotoMode
                                        isPhotoMode = true
                                        if (!wasPhoto) {
                                            // First poll detecting photo mode → clear debounce
                                            Log.d(TAG, "📷 Camera→SHOOT_PHOTO [poll] — resetting debounce")
                                            lastAspectRatioFixTime = 0L
                                        }
                                        if (capPhotoAspectRatio) fetchPhotoAspectRatio(camera, retryDelay = 0L)
                                    }
                                    dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO -> {
                                        isPhotoMode = false
                                        runOnUiThread { updateVideoAspectRatio(16f / 9f) }
                                    }
                                    else -> {}
                                }
                            }
                            override fun onFailure(e: dji.common.error.DJIError?) {}
                        }
                    )
                } catch (e: Exception) {
                    // Ошибки поллинга молча игнорируем
                }
            }
            Log.d(TAG, "Camera params polling stopped")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────────────────
    // GIMBAL PITCH CONTROL
    // ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Настраивает DjiGimbalPitchIndicatorView.
     * interactive=false → только отображение, без управления жестами.
     * Чтобы снова разрешить управление пальцем — установить interactive=true.
     */
    private fun setupGimbalIndicator() {
        gimbalPitchView.interactive = false
        gimbalPitchView.onPitchChanged = { pitch -> sendGimbalPitchCommand(pitch) }
    }

    /**
     * Считывает реальный диапазон наклона с подключённого дрона через capabilities гимбала.
     *
     * NOTE: чтение через getMin/getMax возвращает диапазон КОНФИГУРАЦИИ ЭНДПОИНТА
     * (например, «до куда можно сдвинуть нижнюю границу гимбала»), а НЕ фактический
     * угловой диапазон гимбала.  На практике это даёт значения ±90°, ±900 и другой
     * мусор, который сбивает шкалу.  Дефолт -90°..+30° покрывает все потребительские
     * дроны DJI, поэтому функция намеренно отключена.
     *
     * TODO: вернуть чтение через DJIKey / реальный API когда будет стабильный способ.
     */
    private fun readAndApplyPitchRange() {
        Log.d(TAG, "readAndApplyPitchRange: using defaults (min=${gimbalPitchView.minDeg}°, max=${gimbalPitchView.maxDeg}°)")
    }

    /**
     * Sends an absolute-angle pitch command to the DJI gimbal.
     * Roll and yaw are left unchanged (NO_ROTATION).
     * time=0 means "execute as fast as the gimbal allows".
     */
    /** Центрирует подвес: pitch = 0°, yaw = 0°, roll = 0°. */
    private fun centerGimbal() {
        try {
            val aircraft = dji.sdk.sdkmanager.DJISDKManager.getInstance().product
                as? dji.sdk.products.Aircraft ?: return
            val gimbal = aircraft.gimbal ?: return

            val rotation = dji.common.gimbal.Rotation.Builder()
                .mode(dji.common.gimbal.RotationMode.ABSOLUTE_ANGLE)
                .pitch(0f)
                .roll(0f)
                .yaw(0f)
                .time(0.5)
                .build()

            gimbal.rotate(rotation) { err ->
                if (err != null) Log.e(TAG, "Gimbal center error: ${err.description}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "centerGimbal error: ${e.message}")
        }
    }

    private fun sendGimbalPitchCommand(pitch: Float) {
        try {
            val aircraft = dji.sdk.sdkmanager.DJISDKManager.getInstance().product
                as? dji.sdk.products.Aircraft ?: return
            val gimbal = aircraft.gimbal ?: return

            val rotation = dji.common.gimbal.Rotation.Builder()
                .mode(dji.common.gimbal.RotationMode.ABSOLUTE_ANGLE)
                .pitch(pitch)
                .roll(dji.common.gimbal.Rotation.NO_ROTATION)
                .yaw(dji.common.gimbal.Rotation.NO_ROTATION)
                .time(0.0)
                .build()

            gimbal.rotate(rotation) { err ->
                if (err != null) {
                    Log.e(TAG, "Gimbal rotate error: ${err.description}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendGimbalPitchCommand error: ${e.message}")
        }
    }

    private fun setupListeners() {
        // Gimbal indicator — wire onPitchChanged callback (not drone-dependent)
        setupGimbalIndicator()

        // AI Detection toggle
        btnAiDetection.setOnClickListener {
            // ✅ ИСПРАВЛЕНИЕ: Проверяем наличие видеопотока перед включением детекции
            if (!isDetectionEnabled) {
                // Проверяем есть ли валидный видеопоток из FPV
                val testBitmap = extractBitmapFromFpvWidget()
                if (testBitmap == null) {
                    showNotification("❌ No video feed! Please ensure drone is connected and camera is streaming.", true)
                    Toast.makeText(this, "No video feed available", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Log.d(TAG, "✅ Video feed detected, starting detection")
            }
            toggleDetection()
        }

        // Album/Gallery
        btnAlbum.setOnClickListener {
            openGallery()
        }

        // 3-dots menu — toggle preflight checklist
        findViewById<ImageView>(R.id.btn_menu).setOnClickListener {
            togglePreflightPanel()
        }

        // Toggle button (small icon on map/radar) - switches between map and radar
        btnToggleRadar.setOnClickListener {
            toggleMapRadar()
        }

        // ✅ Кнопка центрирования на дроне
        btnCenterOnDrone.setOnClickListener {
            centerMapOnDrone()
        }

        // ✅ Кнопка центрирования на пользователе (синяя точка)
        btnCenterOnMe.setOnClickListener {
            centerMapOnMe()
        }

        // ✅ Кнопка смены стиля карты (по кругу)
        btnMapLayers.setOnClickListener {
            switchMapLayer()
        }

        // ✅ Мини-FPV: клик на прозрачном overlay возвращает к fullscreen камере
        fpvMiniContainer.setOnClickListener {
            Log.d(TAG, "Mini FPV clicked: returning to camera fullscreen")
            if (isMapFullscreen) {
                swapMapAndFpv()
            }
        }

        // ========== CORRECT: Click behavior ==========
        // RADAR -> MAP -> MAP fullscreen -> MAP mini
        // RADAR never goes fullscreen!

        // ✅ Обработчик клика на невидимом overlay (перехватывает клики на миникарте)
        mapClickOverlay.setOnClickListener {
            Log.d(TAG, "Map overlay clicked: isMapMode=$isMapMode, isMapFullscreen=$isMapFullscreen")

            if (!isMapMode) {
                // Currently showing RADAR -> switch to MAP (mini)
                Log.d(TAG, "Action: Radar → Map (mini)")
                toggleMapRadar()
            } else if (!isMapFullscreen) {
                // Currently showing MAP (mini) -> expand to fullscreen
                Log.d(TAG, "Action: Map (mini) → Map (fullscreen)")
                swapMapAndFpv()
            } else {
                // Currently showing MAP (fullscreen) -> back to mini
                Log.d(TAG, "Action: Map (fullscreen) → Map (mini)")
                swapMapAndFpv()
            }
        }
    }

    private fun observeViewModels() {
        detectionViewModel.detections.observe(this) { detections ->
            if (detections.isNotEmpty()) {
                noDetectionFrames = 0
                // Считаем событие только при первом появлении (переход 0→1)
                if (consecutiveDetectionFrames == 0) {
                    sessionDetections++
                }
                consecutiveDetectionFrames++
                overlayView.updateDetections(detections)
                val currentCount = detections.size
                tvDetectionCount.text = "Mines: $currentCount (events: $sessionDetections)"
                tvDetectionCount.visibility = View.VISIBLE
            } else {
                consecutiveDetectionFrames = 0
                noDetectionFrames++
                // Убираем боксы только после N пустых кадров подряд (~1500ms тишины)
                if (noDetectionFrames >= MIN_EMPTY_FRAMES_TO_CLEAR) {
                    overlayView.updateDetections(emptyList())
                    tvDetectionCount.visibility = View.GONE
                }
            }
        }
    }

    private fun showNotification(text: String, isError: Boolean = false) {
        notificationIcon.setColorFilter(
            if (isError) ContextCompat.getColor(this, R.color.error)
            else ContextCompat.getColor(this, R.color.success)
        )
        notificationText.text = text
        notificationContainer.visibility = View.VISIBLE

        lifecycleScope.launch {
            delay(5_000)
            notificationContainer.visibility = View.GONE
        }
    }

    // ============ PreFlight Panel Toggle ============

    private fun togglePreflightPanel() {
        if (preflightChecklistPanel.visibility == View.VISIBLE) {
            preflightChecklistPanel.visibility = View.GONE
            preflightOverlay.visibility = View.GONE
        } else {
            preflightOverlay.visibility = View.VISIBLE
            preflightChecklistPanel.visibility = View.VISIBLE
            preflightChecklistPanel.bringToFront()
        }
    }

    // ============ Map/Radar Toggle ============

    private fun toggleMapRadar() {
        isMapMode = !isMapMode
        if (isMapMode) {
            // ✅ MAPBOX: Switch to map view
            mapView?.visibility = View.VISIBLE
            compassWidget.visibility = View.GONE
            btnToggleRadar.setImageResource(R.drawable.ic_radar)

            // ✅ Компас Mapbox: скрываем в мини-режиме карты (виден только в fullscreen)
            mapView?.compass?.enabled = false
            Log.d(TAG, "Switched to Mapbox view (mini, compass hidden)")
        } else {
            // Switch to radar view
            mapView?.visibility = View.GONE
            compassWidget.visibility = View.VISIBLE
            btnToggleRadar.setImageResource(R.drawable.ic_map)

            // ✅ Compass always off in radar mode (mapView hidden anyway)
            mapView?.compass?.enabled = false

            // Fix compass size
            fixCompassSize()
            Log.d(TAG, "Switched to compass/radar view")
        }

        // Ensure container layout is recalculated
        mapContainer.post {
            mapContainer.requestLayout()
            mapContainer.invalidate()
        }
    }

    /**
     * Отключает все жесты для миникарты (нельзя двигать/зумить)
     */
    private fun disableMapGestures() {
        mapView?.gestures?.apply {
            scrollEnabled = false
            rotateEnabled = false
            pitchEnabled = false
            quickZoomEnabled = false
            doubleTapToZoomInEnabled = false
            doubleTouchToZoomOutEnabled = false
            simultaneousRotateAndPinchToZoomEnabled = false
            pinchToZoomEnabled = false
        }
        Log.d(TAG, "Map gestures disabled (mini mode)")
    }

    /**
     * Включает жесты для полноэкранной карты (можно двигать/зумить)
     */
    private fun enableMapGestures() {
        mapView?.gestures?.apply {
            scrollEnabled = true
            rotateEnabled = true
            pitchEnabled = true
            quickZoomEnabled = true
            doubleTapToZoomInEnabled = true
            doubleTouchToZoomOutEnabled = true
            simultaneousRotateAndPinchToZoomEnabled = true
            pinchToZoomEnabled = true
        }
        Log.d(TAG, "Map gestures enabled (fullscreen mode)")
    }

    /**
     * Updates drone and home point position on the map.
     * Does NOT call loadStyle (AnnotationManager is created once in initMapWidget).
     *
     * Auto-follow behavior:
     *  - followDroneMode=true (default): camera always tracks drone position
     *  - followDroneMode=false: camera stays where user moved it (set by "Center on Me")
     *  - On first GPS fix: always zoom in to drone at 16.0, set followDroneMode=true
     *  - On mini map: camera always follows (regardless of mode, gestures are disabled)
     */
    /**
     * Updates drone and home point position on the map.
     * Does NOT call loadStyle (AnnotationManager is created once in initMapWidget).
     *
     * @param heading Drone heading in degrees (0 = North, clockwise). Used to rotate
     *                the drone icon so the arrow always points in the flight direction.
     *
     * Auto-follow behavior:
     *  - followDroneMode=true (default): camera always tracks drone position
     *  - followDroneMode=false: camera stays where user moved it (set by "Center on Me")
     *  - On first GPS fix: always zoom in to drone at 16.0, set followDroneMode=true
     *  - On mini map: camera always follows (regardless of mode, gestures are disabled)
     */
    private fun updateDronePositionOnMap(
        latitude: Double,
        longitude: Double,
        homeLatitude: Double?,
        homeLongitude: Double?,
        heading: Float = 0f
    ) {
        if (latitude == 0.0 || longitude == 0.0 || latitude.isNaN() || longitude.isNaN()) return

        lastDroneLatitude = latitude
        lastDroneLongitude = longitude
        lastDroneHeading = heading

        val manager = pointAnnotationManager ?: run {
            Log.w(TAG, "AnnotationManager not ready yet, skipping marker update")
            return
        }

        runOnUiThread {
            try {
                // Create or update drone marker with custom icon + heading rotation
                if (droneMarker == null) {
                    val opts = PointAnnotationOptions()
                        .withPoint(Point.fromLngLat(longitude, latitude))
                        .withIconImage(DRONE_ICON_ID)   // custom blue-circle-arrow bitmap
                        .withIconSize(1.5)
                        .withIconRotate(heading.toDouble()) // 0=North, clockwise
                    droneMarker = manager.create(opts)
                    Log.d(TAG, "✅ Drone marker created at $latitude, $longitude heading=${heading.toInt()}°")
                } else {
                    droneMarker!!.point = Point.fromLngLat(longitude, latitude)
                    droneMarker!!.iconRotate = heading.toDouble()
                    manager.update(droneMarker!!)
                    // Debug only: comment out after confirming marker is visible
                    Log.v(TAG, "🔄 Drone marker updated: ${"%.5f".format(latitude)}, ${"%.5f".format(longitude)} hdg=${heading.toInt()}°")
                }

                // ✅ Camera follow logic
                if (!isDroneEverPositioned) {
                    // First GPS fix: always zoom in and start following
                    isDroneEverPositioned = true
                    followDroneMode = true
                    val cam = getCameraOptions(longitude, latitude, 16.0)
                    mapView?.post { mapView?.mapboxMap?.setCamera(cam) }
                    Log.d(TAG, "✅ Camera auto-centered on drone (first fix)")
                } else if (followDroneMode || !isMapFullscreen) {
                    // Subsequent updates: follow if followDroneMode OR mini map (always follows)
                    val currentZoom = mapView?.mapboxMap?.cameraState?.zoom ?: 16.0
                    val cam = getCameraOptions(longitude, latitude, currentZoom)
                    mapView?.post { mapView?.mapboxMap?.setCamera(cam) }
                }

                // Home point marker (created once at take-off, not rotated)
                if (homeLatitude != null && homeLongitude != null &&
                    homeLatitude != 0.0 && homeLongitude != 0.0 && homeMarker == null) {
                    val homeOpts = PointAnnotationOptions()
                        .withPoint(Point.fromLngLat(homeLongitude, homeLatitude))
                        .withIconImage(HOME_ICON_ID)    // custom orange-circle-H bitmap
                        .withIconSize(1.2)
                    homeMarker = manager.create(homeOpts)
                    Log.d(TAG, "✅ Home marker created at $homeLatitude, $homeLongitude")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating map markers: ${e.message}", e)
            }
        }
    }

    /**
     * Centers map on pilot location (blue dot).
     * Sets followDroneMode=false so the camera stops tracking the drone.
     * Uses currentUserLocation — live Mapbox indicator position (same source as blue dot).
     * Always zooms to 17 for consistent result regardless of current zoom.
     */
    private fun centerMapOnMe() {
        try {
            // Stop following drone — user wants to look at their own position
            followDroneMode = false

            // ✅ Используем координаты из Mapbox location indicator — это всегда свежая позиция
            val userLoc = currentUserLocation
            if (userLoc != null) {
                val cam = getCameraOptions(userLoc.longitude(), userLoc.latitude(), 17.0)
                mapView?.post { mapView?.mapboxMap?.setCamera(cam) }
                Log.d(TAG, "Map centered on pilot via Mapbox indicator (zoom=17): ${userLoc.latitude()}, ${userLoc.longitude()}")
                return
            }

            // Fallback: если Mapbox ещё не дал позицию (карта только открылась)
            Log.w(TAG, "Mapbox user location not yet available, trying LocationManager fallback")
            val locationManager = getSystemService(android.location.LocationManager::class.java)
            val hasPermission = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val lastLocation = if (hasPermission) {
                locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: locationManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    ?: locationManager?.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
            } else null

            if (lastLocation != null) {
                val cam = getCameraOptions(lastLocation.longitude, lastLocation.latitude, 17.0)
                mapView?.post { mapView?.mapboxMap?.setCamera(cam) }
                Log.d(TAG, "Map centered on pilot via LocationManager fallback (zoom=17): ${lastLocation.latitude}, ${lastLocation.longitude}")
            } else {
                showNotification("Pilot location unknown", true)
                Log.w(TAG, "No location available from any source")
            }
        } catch (e: Exception) {
            Log.e(TAG, "centerMapOnMe error: ${e.message}", e)
            showNotification("Location error", true)
        }
    }

    /**
     * Centers map on drone position and resumes drone auto-follow.
     * Always zooms to DRONE_CENTER_ZOOM for consistent visual result.
     */
    private fun centerMapOnDrone() {
        if (lastDroneLatitude != 0.0 && lastDroneLongitude != 0.0) {
            // Resume drone following, always zoom in to 17
            followDroneMode = true
            val cam = getCameraOptions(lastDroneLongitude, lastDroneLatitude, 17.0)
            mapView?.post { mapView?.mapboxMap?.setCamera(cam) }
            Log.d(TAG, "Map centered on drone (zoom=17): $lastDroneLatitude, $lastDroneLongitude")
        } else {
            // Distinguish between "no connection" and "connected but GPS searching"
            val msg = when {
                telemetryManager == null -> "Drone not connected"
                lastKnownSatelliteCount > 0 ->
                    "GPS fix not yet — ${lastKnownSatelliteCount} satellites visible, wait outdoors"
                else -> "Drone GPS: no signal"
            }
            showNotification(msg, true)
            Log.d(TAG, "centerMapOnDrone: no fix — $msg (sat=$lastKnownSatelliteCount)")
        }
    }

    /**
     * Returns CameraOptions centered on the given coordinates.
     * No EdgeInsets padding — consistent centering at any zoom level.
     */
    private fun getCameraOptions(lng: Double, lat: Double, zoom: Double): CameraOptions {
        return CameraOptions.Builder()
            .center(Point.fromLngLat(lng, lat))
            .zoom(zoom)
            .build()
    }

    /**
     * Cycles map style: Satellite+Roads → Streets → Satellite → Terrain → repeat
     * Recreates AnnotationManager and markers after loadStyle (they are wiped on style change)
     */
    private fun switchMapLayer() {
        currentMapStyleIndex = (currentMapStyleIndex + 1) % mapStyles.size
        val newStyle = mapStyles[currentMapStyleIndex]
        val newName = mapStyleNames[currentMapStyleIndex]

        showNotification("Map: $newName", false)

        // ✅ CRITICAL: explicitly delete all annotations BEFORE style reload.
        // The AnnotationManager stays registered in Mapbox's plugin system even after we null
        // our reference. When the new style loads, it auto-recreates its source layer and
        // re-renders any non-deleted annotations → stale drone/home markers appear as phantoms.
        // deleteAll() empties the manager's internal annotation list before the reload.
        pointAnnotationManager?.deleteAll()
        pointAnnotationManager = null
        droneMarker = null
        homeMarker = null

        mapView?.mapboxMap?.loadStyle(newStyle) { loadedStyle ->
            Log.d(TAG, "✅ Map style changed to $newName")

            // Re-register custom icons — style reload clears all added images
            addCustomIconsToStyle(loadedStyle)

            // ✅ Пересоздаём AnnotationManager после смены стиля
            pointAnnotationManager = mapView?.annotations?.createPointAnnotationManager()

            // ✅ Восстанавливаем маркер дрона если координаты известны
            // isDroneEverPositioned остаётся true → автоцентрирование НЕ сработает
            if (lastDroneLatitude != 0.0 && lastDroneLongitude != 0.0) {
                updateDronePositionOnMap(lastDroneLatitude, lastDroneLongitude, null, null, lastDroneHeading)
            }

            runOnUiThread {
                // Восстанавливаем синюю точку пользователя
                enableUserLocationOnMap()
                // Восстанавливаем компас если fullscreen
                if (isMapFullscreen) {
                    mapView?.compass?.enabled = true
                }
            }
        }
    }

    /**
     * Fix compass to be perfectly square, centered in container
     * Call this after making compass visible
     */
    private fun fixCompassSize() {
        // Compass is now fixed to 100x100dp in XML, just ensure it's centered
        compassWidget.post {
            try {
                compassWidget.requestLayout()
                compassWidget.invalidate()
                Log.d(TAG, "Compass layout refreshed")
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing compass: ${e.message}", e)
            }
        }
    }

    /**
     * Корректирует соотношение сторон видео через scaleX на TextureView.
     *
     * DJI всегда растягивает видео чтобы заполнить весь TextureView (= экран на широком телефоне).
     * Например, на Note20 Ultra (ratio≈2.08) видео 16:9 рендерится растянутым.
     *
     * Решение — scaleX-трансформация (НЕ изменение LayoutParams):
     *   scaleX = videoAspectRatio / screenRatio
     *   Пример: 1.78 / 2.08 = 0.856 → видео визуально сужается до 16:9, чёрные полосы по бокам
     *
     * Почему НЕ LayoutParams.width/height:
     *   DJI переопределяет размер в своём onLayout() при каждом requestLayout() →
     *   бесконечный цикл fix→DJI-сброс→viewTreeObserver→fix...
     *   scaleX = чистый transform, DJI его не трогает → одно применение, стабильно.
     *
     * Не вызывать в мини-режиме (isMapFullscreen=true).
     */
    private fun fixFpvAspectRatio() {
        // Apply scaleX to fpvWidget (parent), NOT to the TextureView (child).
        // Reason: DJI's internal FPVWidget code resets TextureView.scaleX to 1.0 on every
        // layout/codec reconfiguration, but does NOT touch fpvWidget.scaleX — so our
        // correction on fpvWidget persists and is never silently undone by DJI.
        try {
            if (isMapFullscreen) return

            // ✅ FIX #2: Use fpvWidget actual dimensions (not rootLayout) for screenRatio.
            // rootLayout = full window including system insets; fpvWidget = actual video area.
            // In fullscreen camera mode these are usually equal, but semantically fpvWidget is correct.
            val vw = fpvWidget.width
            val vh = fpvWidget.height
            if (vw <= 0 || vh <= 0) {
                fpvWidget.postDelayed({ fixFpvAspectRatio() }, 200)
                return
            }

            val screenRatio = vw.toFloat() / vh.toFloat()

            if (screenRatio <= videoAspectRatio + 0.02f) {
                // Screen not wider than video — no stretching needed
                if (kotlin.math.abs(fpvWidget.scaleX - 1.0f) >= 0.001f) {
                    fpvWidget.scaleX = 1.0f
                    fpvWidget.scaleY = 1.0f
                }
                return
            }

            // Screen is wider than video → DJI stretches video to fill screen horizontally.
            // scaleX = videoRatio/screenRatio compensates this stretch visually.
            // Applied to fpvWidget (not TextureView) so DJI cannot override it.
            val scaleX = videoAspectRatio / screenRatio

            // ✅ FIX #3: In photo mode, always reapply scaleX even if value didn't change.
            // DJI codec reconfigurations (3:2 ↔ 16:9 stream switch) reset fpvWidget's rendering
            // state and the visual correction may need to be re-applied even when our stored
            // scaleX value is already correct. In video mode the early-return is fine.
            if (!isPhotoMode && kotlin.math.abs(fpvWidget.scaleX - scaleX) < 0.001f) return

            fpvWidget.scaleX = scaleX
            fpvWidget.scaleY = 1.0f
        } catch (e: Exception) {
            Log.e(TAG, "fixFpvAspectRatio error: ${e.message}", e)
        }
    }

    /**
     * Fallback when getPhotoAspectRatio() returns UNKNOWN, 16:9-wrong, or fails.
     *
     * Strategy (in order of reliability):
     *  1. TextureView layout dimensions — works if DJI sizes TV to video native (e.g. 1620×1080)
     *  2. TextureView bitmap dimensions — same as above, confirms step 1
     *  3. Letterbox scan — if DJI letterboxes in photo mode, find content edges in the bitmap
     *  4. Falls back silently (keeps previous videoAspectRatio)
     *
     * DJI renders the FPV preview stretched to fill its TextureView. When in photo mode,
     * some DJI cameras/firmware DO set the TextureView native size = video resolution
     * (e.g. 1620×1080 for 3:2) rather than screen size. Checking tv.width/tv.height first
     * catches this case cheaply without reading bitmap pixels.
     *
     * Safe to call from any thread — UI ops done via runOnUiThread.
     */
    private fun inferPhotoRatioFromFpvBitmap() {
        runOnUiThread {
            val tv = findTextureViewInView(fpvWidget) ?: return@runOnUiThread

            // ── Step 1: TextureView layout dimensions ──────────────────────────────
            val tvW = tv.width; val tvH = tv.height
            if (tvW > 0 && tvH > 0) {
                val tvRatio = tvW.toFloat() / tvH.toFloat()
                val r = nearestStandardRatio(tvRatio)
                if (r != null) {
                    videoAspectRatio = r; fpvWidget.post { fixFpvAspectRatio() }; return@runOnUiThread
                }
            }

            // ── Step 2 + 3: Bitmap pixel analysis ─────────────────────────────────
            val bm = tv.bitmap ?: return@runOnUiThread
            val w = bm.width; val h = bm.height.takeIf { it > 0 } ?: run { bm.recycle(); return@runOnUiThread }

            // Step 2: bitmap dimensions (works if TV was sized to video native)
            val bmRatio = w.toFloat() / h.toFloat()
            val r2 = nearestStandardRatio(bmRatio)
            if (r2 != null) {
                bm.recycle(); videoAspectRatio = r2; fpvWidget.post { fixFpvAspectRatio() }; return@runOnUiThread
            }

            // Step 3: Scan center row for black bars (letterbox detection).
            // If DJI letterboxes 3:2 video inside a 2.08-ratio view the side bars are black.
            val BLACK = 30  // pixel brightness threshold
            val row = h / 2
            var leftEdge = 0
            for (x in 0 until w / 2) {
                val px = bm.getPixel(x, row)
                if ((android.graphics.Color.red(px) + android.graphics.Color.green(px) + android.graphics.Color.blue(px)) / 3 > BLACK) {
                    leftEdge = x; break
                }
            }
            var rightEdge = w - 1
            for (x in w - 1 downTo w / 2) {
                val px = bm.getPixel(x, row)
                if ((android.graphics.Color.red(px) + android.graphics.Color.green(px) + android.graphics.Color.blue(px)) / 3 > BLACK) {
                    rightEdge = x; break
                }
            }
            bm.recycle()
            val contentW = rightEdge - leftEdge + 1
            val scanRatio = contentW.toFloat() / h.toFloat()
            val r3 = nearestStandardRatio(scanRatio)
            if (r3 != null && leftEdge > 4) {  // require meaningful bar (>4px) to trust scan
                videoAspectRatio = r3; fpvWidget.post { fixFpvAspectRatio() }; return@runOnUiThread
            }

            Log.w(TAG, "📷 inferPhotoRatio: all methods failed (bmRatio=${"%.3f".format(bmRatio)}, scanRatio=${"%.3f".format(scanRatio)})")
        }
    }

    /** Maps a raw ratio to the nearest standard DJI photo/video aspect ratio, or null if no match. */
    private fun nearestStandardRatio(ratio: Float): Float? = when {
        kotlin.math.abs(ratio - 3f / 2f) < 0.12f -> 3f / 2f
        kotlin.math.abs(ratio - 4f / 3f) < 0.08f -> 4f / 3f
        kotlin.math.abs(ratio - 16f / 9f) < 0.08f -> 16f / 9f
        else -> null
    }

    /**
     * Probes camera mode + photo ratio and corrects videoAspectRatio / isPhotoMode.
     *
     * Called from viewTreeObserver (1-2×/sec) with internal 600ms debounce.
     *
     * ⚠️ KEY INSIGHT: getPhotoAspectRatio() ALWAYS returns the photo-ratio SETTING
     * (e.g. RATIO_3_2) even when the camera is in RECORD_VIDEO mode — it is a camera
     * setting, NOT a current-mode indicator.  Without checking getMode() first we would
     * override the correct 16:9 video ratio with 3:2 every time the user is in video mode.
     *
     * Flow:
     *  getMode() == SHOOT_PHOTO → getPhotoAspectRatio() → apply 3:2 / 4:3 correction
     *  getMode() == RECORD_VIDEO → ensure isPhotoMode=false + videoAspectRatio=16:9
     */
    private fun probePhotoRatioOnCodecChange() {
        val now = System.currentTimeMillis()
        if (now - lastPhotoRatioProbeTime < 600L) return
        lastPhotoRatioProbeTime = now

        val camera = try {
            (dji.sdk.sdkmanager.DJISDKManager.getInstance().product
                as? dji.sdk.products.Aircraft)?.camera
        } catch (e: Exception) { null } ?: return

        // Step 1: check mode first — getPhotoAspectRatio() alone cannot tell us
        // whether the camera is in photo or video mode.
        // Wrapped in try-catch: Enterprise cameras may throw synchronously on getMode().
        try {
            camera.getMode(
                object : dji.common.util.CommonCallbacks.CompletionCallbackWith<dji.common.camera.SettingsDefinitions.CameraMode> {
                    override fun onSuccess(mode: dji.common.camera.SettingsDefinitions.CameraMode) {
                        when (mode) {
                            dji.common.camera.SettingsDefinitions.CameraMode.SHOOT_PHOTO -> {
                                // Step 2: camera IS in photo mode → now get actual ratio
                                try {
                                    camera.getPhotoAspectRatio(
                                        object : dji.common.util.CommonCallbacks.CompletionCallbackWith<dji.common.camera.SettingsDefinitions.PhotoAspectRatio> {
                                            override fun onSuccess(ratio: dji.common.camera.SettingsDefinitions.PhotoAspectRatio) {
                                                val newRatio = when (ratio) {
                                                    dji.common.camera.SettingsDefinitions.PhotoAspectRatio.RATIO_3_2 -> 3f / 2f
                                                    dji.common.camera.SettingsDefinitions.PhotoAspectRatio.RATIO_4_3 -> 4f / 3f
                                                    else -> return  // RATIO_16_9 / UNKNOWN: ignore
                                                }
                                                if (kotlin.math.abs(newRatio - videoAspectRatio) > 0.05f || !isPhotoMode) {
                                                    Log.d(TAG, "📷 probePhotoRatio: PHOTO ${ratio.name}" +
                                                        " → ${"%.2f".format(newRatio)}")
                                                    isPhotoMode = true
                                                    videoAspectRatio = newRatio
                                                    runOnUiThread { fpvWidget.post { fixFpvAspectRatio() } }
                                                }
                                            }
                                            override fun onFailure(e: dji.common.error.DJIError?) {}
                                        }
                                    )
                                } catch (ex: Exception) {
                                    Log.w(TAG, "probePhotoRatio getPhotoAspectRatio exception: ${ex.javaClass.simpleName}")
                                }
                            }
                            dji.common.camera.SettingsDefinitions.CameraMode.RECORD_VIDEO -> {
                                // Camera is in video mode — correct any stale photo-mode state
                                if (isPhotoMode || kotlin.math.abs(videoAspectRatio - 16f / 9f) > 0.05f) {
                                    Log.d(TAG, "🎥 probePhotoRatio: VIDEO → reset to 16:9")
                                    isPhotoMode = false
                                    videoAspectRatio = 16f / 9f
                                    runOnUiThread { fpvWidget.post { fixFpvAspectRatio() } }
                                }
                            }
                            else -> {}
                        }
                    }
                    override fun onFailure(e: dji.common.error.DJIError?) {}
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "probePhotoRatioOnCodecChange getMode exception: ${e.javaClass.simpleName}")
        }
    }

    // ============ Custom Map Icons ============

    /**
     * Creates a 64×64 px drone icon: solid blue circle with a white upward-pointing arrow.
     * The arrow points North (up). Mapbox [PointAnnotation.iconRotate] rotates it clockwise
     * by the drone's heading — so 0° = arrow up = North, 90° = arrow right = East, etc.
     */
    private fun createDroneIconBitmap(): Bitmap {
        val size = 64
        val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bm)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Blue filled circle
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#2196F3")   // Material Blue 500
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)

        // Thin white border
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 2.5f
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)

        // White upward-pointing arrow (chevron / filled triangle with notch)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val cx = size / 2f
        val path = Path()
        path.moveTo(cx,        9f)   // arrow tip (top)
        path.lineTo(cx + 11f, 44f)  // bottom-right wing
        path.lineTo(cx,        36f)  // center notch
        path.lineTo(cx - 11f, 44f)  // bottom-left wing
        path.close()
        canvas.drawPath(path, paint)

        return bm
    }

    /**
     * Creates a 48×48 px home icon: solid orange circle with bold "H" label.
     * Placed once at the take-off point and not rotated.
     */
    private fun createHomeIconBitmap(): Bitmap {
        val size = 48
        val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bm)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Orange filled circle
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FF9800")   // Material Orange 500
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)

        // Thin white border
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 2f
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)

        // "H" label
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = 22f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        val textY = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText("H", size / 2f, textY, paint)

        return bm
    }

    /**
     * Registers the custom drone and home bitmaps in the currently active Mapbox style.
     * Must be called after every [loadStyle] call (style reload clears all added images).
     */
    /**
     * Overload for use inside loadStyle { style -> } callbacks — receives style directly
     * so there is no race between the callback and mapboxMap.style becoming non-null.
     */
    private fun addCustomIconsToStyle(style: com.mapbox.maps.Style) {
        try {
            style.addImage(DRONE_ICON_ID, createDroneIconBitmap())
            style.addImage(HOME_ICON_ID,  createHomeIconBitmap())
            Log.d(TAG, "✅ Custom map icons registered (drone=$DRONE_ICON_ID, home=$HOME_ICON_ID)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add custom icons to style: ${e.message}")
        }
    }

    /** Fallback overload — looks up current style from mapboxMap (for calls outside loadStyle). */
    private fun addCustomIconsToStyle() {
        val style = mapView?.mapboxMap?.style
        if (style == null) {
            Log.w(TAG, "⚠️ addCustomIconsToStyle: style is null — icons not registered")
            return
        }
        addCustomIconsToStyle(style)
    }

    // ============ Frozen Frame (Grayscale on Disconnect) ============

    /**
     * Captures the current FPV frame as a grayscale bitmap and renders it as the
     * foreground of fpvWidget.  Gives a visual "frozen / disconnected" cue while
     * keeping the last seen frame visible.
     *
     * Uses fpvWidget.foreground so the overlay:
     *  • automatically scales with the widget (works in both fullscreen & mini mode)
     *  • is drawn on top of DJI's video content by the Android View framework
     *  • is safely removed by hideFrozenFrame() → fpvWidget.foreground = null
     */
    /**
     * Показывает последний кадр с дрона в чёрно-белом виде поверх FPVWidget.
     * Если bitmap недоступен — показывает просто чёрный экран (фон ImageView).
     */
    private fun showFrozenFrame(sourceBm: Bitmap?) {
        if (sourceBm != null && !sourceBm.isRecycled) {
            try {
                val grayBm = Bitmap.createBitmap(sourceBm.width, sourceBm.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(grayBm)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                canvas.drawBitmap(sourceBm, 0f, 0f, paint)
                fpvDisconnectOverlay.setImageBitmap(grayBm)
                Log.d(TAG, "🖤 Grayscale frame shown (${sourceBm.width}×${sourceBm.height})")
            } catch (e: Exception) {
                Log.w(TAG, "showFrozenFrame bitmap error: ${e.message}")
                fpvDisconnectOverlay.setImageBitmap(null)
            }
        } else {
            fpvDisconnectOverlay.setImageBitmap(null)
            Log.d(TAG, "🖤 No bitmap — black overlay shown")
        }
        // Копируем scaleX/Y с fpvWidget — fixFpvAspectRatio() мог применить коррекцию
        // соотношения сторон (scaleX < 1.0). Без этого bitmap выглядит растянутым.
        fpvDisconnectOverlay.scaleX = fpvWidget.scaleX
        fpvDisconnectOverlay.scaleY = fpvWidget.scaleY
        fpvDisconnectOverlay.visibility = View.VISIBLE
    }

    /** Убирает ч/б оверлей, позволяя живому видео снова быть видимым. */
    private fun hideFrozenFrame() {
        fpvDisconnectOverlay.visibility = View.GONE
        fpvDisconnectOverlay.setImageBitmap(null)
        fpvDisconnectOverlay.scaleX = 1f
        fpvDisconnectOverlay.scaleY = 1f
        Log.d(TAG, "🖤 Disconnect overlay hidden")
    }

    /**
     * Перехватывает SurfaceTextureListener внутри FPVWidget.TextureView через рефлексию.
     *
     * Когда кодек меняет выходной размер буфера (например 1920×1088→16:9, 1632×1088→3:2),
     * Android вызывает onSurfaceTextureSizeChanged(surface, bufferWidth, bufferHeight).
     * Это происходит МГНОВЕННО при смене кодека — до того, как DJI control API
     * (setSystemStateCallback / getMode) обновит режим камеры (может занять секунды).
     *
     * Wrapped listener делегирует все вызовы оригинальному DJI listener-у,
     * добавляя только наш ratio-детект в onSurfaceTextureSizeChanged.
     */
    /**
     * Отслеживает изменения размера TextureView внутри FPVWidget БЕЗ рефлексии.
     *
     * Старый способ (reflection на mListener) ломается на API 34+ (Android 14).
     * Новый способ: OnLayoutChangeListener на TextureView. Когда кодек меняет
     * выходной размер буфера, DJI внутренне перестраивает TextureView →
     * мы получаем onLayoutChange → определяем aspect ratio.
     *
     * Также используем polling (каждые 2 секунды) как fallback, потому что
     * DJI может менять буфер без изменения layout (тот же размер, другой codec).
     */
    private var lastTrackedTvWidth = 0
    private var lastTrackedTvHeight = 0
    private var interceptRetryCount = 0

    private fun interceptTextureViewSizeChanges() {
        val tv = findTextureViewInView(fpvWidget) ?: run {
            Log.w(TAG, "⚠️ interceptTextureView: TextureView not found")
            return
        }
        // Guard: TextureView must be available before we can intercept its listener.
        // On slower devices (Xiaomi/MIUI) the surface may not be ready yet.
        if (!tv.isAvailable && interceptRetryCount < 10) {
            interceptRetryCount++
            Log.d(TAG, "interceptTextureView: surface not ready, retry #$interceptRetryCount in 500ms")
            fpvWidget.postDelayed({ interceptTextureViewSizeChanges() }, 500)
            return
        }
        interceptRetryCount = 0

        // ── Способ 1: OnLayoutChangeListener (не требует рефлексии) ──────────
        // Срабатывает когда DJI кодек меняет размер TextureView.
        tv.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != lastTrackedTvWidth || h != lastTrackedTvHeight)) {
                lastTrackedTvWidth = w
                lastTrackedTvHeight = h
                onTextureViewSizeDetected(w, h)
            }
        }

        // ── Способ 2: SurfaceTexture size polling (fallback каждые 2 секунды) ──
        // Кодек может менять буфер без изменения layout размера TextureView.
        // getSurfaceTexture().defaultBufferSize отражает реальный размер потока.
        lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                delay(2000)
                try {
                    val surfTex = tv.surfaceTexture ?: continue
                    // Получаем текущий bitmap для определения размера кодека
                    val bm = tv.bitmap ?: continue
                    val w = bm.width
                    val h = bm.height
                    if (w > 100 && h > 100 && (w != lastTrackedTvWidth || h != lastTrackedTvHeight)) {
                        lastTrackedTvWidth = w
                        lastTrackedTvHeight = h
                        onTextureViewSizeDetected(w, h)
                    }
                } catch (_: Exception) { }
            }
        }

        Log.d(TAG, "✅ TextureView size tracking via OnLayoutChangeListener + polling (no reflection)")
    }

    /**
     * Вызывается когда определён размер буфера кодека TextureView.
     * Определяет aspect ratio видео и обновляет FPV scaling.
     */
    private fun onTextureViewSizeDetected(w: Int, h: Int) {
        if (h <= 0) return
        val ratio = w.toFloat() / h.toFloat()
        val nearestRatio = nearestStandardRatio(ratio)
        if (nearestRatio != null && kotlin.math.abs(nearestRatio - videoAspectRatio) > 0.05f) {
            videoAspectRatio = nearestRatio
            // 16:9 → video mode; другие → photo mode
            isPhotoMode = kotlin.math.abs(nearestRatio - 16f / 9f) > 0.05f
            Log.d(TAG, "📐 Video ratio detected: ${w}×${h} → ${"%.3f".format(ratio)} → nearest=${"%.3f".format(nearestRatio)} photo=$isPhotoMode")
            runOnUiThread { fpvWidget.post { fixFpvAspectRatio() } }
        }
    }

    /**
     * Обновляет соотношение сторон видео и пересчитывает размер TextureView.
     * Вызывать при смене разрешения камеры (фото/видео режим, 4:3, 16:9 и т.д.)
     */
    private fun updateVideoAspectRatio(ratio: Float) {
        if (videoAspectRatio != ratio) {
            videoAspectRatio = ratio
            Log.d(TAG, "Video aspect ratio updated to $ratio")
            fpvWidget.post { fixFpvAspectRatio() }
        }
    }

    // ============ Map/FPV Swap ============

    private fun swapMapAndFpv() {
        try {
            isMapFullscreen = !isMapFullscreen

            val fpvParams = fpvWidget.layoutParams as? RelativeLayout.LayoutParams
            val mapParams = mapContainer.layoutParams as? RelativeLayout.LayoutParams

            if (fpvParams == null || mapParams == null) {
                Log.e(TAG, "Failed to get layout params for swap")
                isMapFullscreen = !isMapFullscreen // Revert
                return
            }

            // CRITICAL: Clear ALL rules first to prevent conflicts
            clearAllRules(fpvParams)
            clearAllRules(mapParams)

        if (isMapFullscreen) {
            // ===== Map becomes FULLSCREEN =====
            mapParams.width = RelativeLayout.LayoutParams.MATCH_PARENT
            mapParams.height = RelativeLayout.LayoutParams.MATCH_PARENT
            mapParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
            mapParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            mapParams.addRule(RelativeLayout.ALIGN_PARENT_START)
            mapParams.addRule(RelativeLayout.ALIGN_PARENT_END)
            mapParams.setMargins(0, 0, 0, 0)

            // ===== FPV becomes MINI (bottom-left) =====
            fpvParams.width = dpToPx(MINI_WIDTH_DP)
            fpvParams.height = dpToPx(MINI_HEIGHT_DP)
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_START)
            fpvParams.setMargins(0, 0, 0, 0)

            // ✅ Включаем gestures для fullscreen карты
            enableMapGestures()

            // ✅ Скрываем overlay (в fullscreen можно двигать карту)
            mapClickOverlay.visibility = View.GONE

            // ✅ Скрываем кнопку переключения радар/карта в fullscreen
            btnToggleRadar.visibility = View.GONE

            // ✅ Показываем чёрный фон и мини-FPV контейнер (только фон, без иконок)
            fpvMiniBg.visibility = View.VISIBLE
            fpvMiniContainer.visibility = View.VISIBLE

            // ✅ Show map controls container (Layers / GPS / Person) — centered vertically
            mapControlsContainer.visibility = View.VISIBLE

            // ✅ Show Mapbox compass only in fullscreen map
            mapView?.compass?.enabled = true
            Log.d(TAG, "✅ Mapbox compass: ENABLED (fullscreen map)")

            // ✅ Hide DJI camera panels in fullscreen map mode
            cameraConfigBar.visibility = View.GONE
            cameraCapturePanel.visibility = View.GONE
            // Сохраняем текущую видимость виджета фокуса (DJI управляет им: AF=GONE, MF=VISIBLE)
            // потом восстановим оригинальное состояние, не форсируя VISIBLE
            manualFocusSavedVisibility = manualFocusWidget.visibility
            manualFocusWidget.visibility = View.GONE
            btnAlbum.visibility = View.GONE
            gimbalPitchView.visibility = View.GONE

            // Apply params
            mapContainer.layoutParams = mapParams
            fpvWidget.layoutParams = fpvParams

            // Force immediate layout update
            mapContainer.requestLayout()
            fpvWidget.requestLayout()
            rootLayout.requestLayout()

            // ✅ ФИКС мини-камеры: сбрасываем ТОЛЬКО scaleX-коррекцию (НЕ трогаем LayoutParams!)
            // scaleX=1.0 убирает визуальную коррекцию 16:9 — в мини-режиме видео заполняет контейнер.
            // LayoutParams НЕ ТРОГАЕМ: textureView.layoutParams = p вызывает requestLayout()
            // → DJI сбрасывает видеопровод → чёрный квадрат вместо камеры в мини-режиме.
            fpvWidget.post {
                val tv = findTextureViewInView(fpvWidget)
                tv?.let { textureView ->
                    textureView.scaleX = 1.0f
                    textureView.scaleY = 1.0f
                    Log.d(TAG, "✅ Mini mode: scaleX=1.0 (LayoutParams not touched — avoids black screen)")
                }
            }

            // Z-order: map(bottom) → fpv_mini_bg → fpvWidget → overlayView → fpvMiniContainer → controls
            mapContainer.post {
                fpvMiniBg.bringToFront()         // Black background
                fpvWidget.bringToFront()         // Video on top of background
                overlayView.bringToFront()       // AI detection boxes
                fpvMiniContainer.bringToFront()  // Click interceptor (topmost)
                mapControlsContainer.bringToFront()
                bringOverlaysToFront()
            }

        } else {
            // ===== FPV becomes FULLSCREEN =====
            fpvParams.width = RelativeLayout.LayoutParams.MATCH_PARENT
            fpvParams.height = RelativeLayout.LayoutParams.MATCH_PARENT
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_START)
            fpvParams.addRule(RelativeLayout.ALIGN_PARENT_END)
            fpvParams.setMargins(0, 0, 0, 0)

            // ===== Map becomes MINI (bottom-left) =====
            mapParams.width = dpToPx(MINI_WIDTH_DP)
            mapParams.height = dpToPx(MINI_HEIGHT_DP)
            mapParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            mapParams.addRule(RelativeLayout.ALIGN_PARENT_START)
            mapParams.setMargins(0, 0, 0, 0)

            // ✅ Отключаем gestures для mini карты
            disableMapGestures()

            // ✅ Показываем overlay (в mini нельзя двигать карту, только кликнуть)
            mapClickOverlay.visibility = View.VISIBLE

            // ✅ Показываем кнопку переключения обратно в mini режиме
            btnToggleRadar.visibility = View.VISIBLE

            // ✅ Скрываем чёрный фон и мини-FPV контейнер когда FPV fullscreen
            fpvMiniBg.visibility = View.GONE
            fpvMiniContainer.visibility = View.GONE

            // ✅ Hide map controls container when camera is fullscreen
            mapControlsContainer.visibility = View.GONE

            // ✅ Hide Mapbox compass in mini map / camera mode
            mapView?.compass?.enabled = false
            Log.d(TAG, "✅ Mapbox compass: DISABLED (map mini)")

            // ✅ Restore DJI camera panels
            cameraConfigBar.visibility = View.VISIBLE
            cameraCapturePanel.visibility = View.VISIBLE
            // ✅ Восстанавливаем ManualFocusWidget в состояние, сохранённое поллингом фокуса.
            // manualFocusSavedVisibility обновляется startFocusModePolling() при каждой смене
            // AF↔MF (даже пока карта была fullscreen). После возврата получаем точное состояние.
            // VISIBLE = MF режим, GONE = AF режим.
            manualFocusWidget.visibility = manualFocusSavedVisibility
            btnAlbum.visibility = View.VISIBLE
            // Restore gimbal panel only if drone is connected (gimbalJob sets it visible)
            if (telemetryManager != null) gimbalPitchView.visibility = View.VISIBLE

            // ✅ Resume drone auto-follow when going back to mini map
            followDroneMode = true
            Log.d(TAG, "✅ Follow mode: resumed (mini map always tracks drone)")

            // Apply params
            fpvWidget.layoutParams = fpvParams
            mapContainer.layoutParams = mapParams

            // Force immediate layout update
            fpvWidget.requestLayout()
            mapContainer.requestLayout()
            rootLayout.requestLayout()

            // Z-order: fpv behind, map in front
            fpvWidget.post {
                mapContainer.bringToFront()
                bringOverlaysToFront()
            }
        }

            // Force widget visibility recalculation + fix aspect ratio
            fpvWidget.postDelayed({
                try {
                    fpvWidget.requestLayout()
                    fpvWidget.invalidate()

                    // ✅ Пересчитываем пропорции после смены размера FPV
                    if (!isMapFullscreen) {
                        // FPV вернулся в fullscreen — восстанавливаем правильные пропорции
                        fixFpvAspectRatio()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating FPV widget: ${e.message}")
                }
            }, 150)

            if (isMapMode) {
                // ✅ MAPBOX: No need to manually invalidate
                mapView?.invalidate()
            } else {
                // Fix compass to be square when it's visible
                fixCompassSize()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in swapMapAndFpv: ${e.message}", e)
            showNotification("Display error", true)
        }
    }
    
    /**
     * Clear all RelativeLayout rules from LayoutParams
     */
    private fun clearAllRules(params: RelativeLayout.LayoutParams) {
        params.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
        params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        params.removeRule(RelativeLayout.ALIGN_PARENT_START)
        params.removeRule(RelativeLayout.ALIGN_PARENT_END)
        params.removeRule(RelativeLayout.ALIGN_PARENT_LEFT)
        params.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)
        params.removeRule(RelativeLayout.CENTER_IN_PARENT)
        params.removeRule(RelativeLayout.CENTER_HORIZONTAL)
        params.removeRule(RelativeLayout.CENTER_VERTICAL)
    }

    private fun bringOverlaysToFront() {
        // ✅ ФИКС z-порядка FPVOverlayWidget (tap-to-focus, gimbal):
        // После mini-режима fpvWidget.bringToFront() ставит его ВЫШЕ fpvOverlayWidget →
        // элементы DJI overlay скрыты и не нажимаются.
        // Вызываем bringToFront() ПЕРВЫМ — последующие вызовы UI-панелей поднимут их выше,
        // но overlay окажется выше fpvWidget (именно это нам нужно).
        if (!isMapFullscreen) {
            fpvOverlayWidget.bringToFront()
        }

        // Bring all UI overlays to front in correct order
        findViewById<View>(R.id.top_bar).bringToFront()
        findViewById<View>(R.id.remaining_flight_time).bringToFront()
        findViewById<View>(R.id.left_panel).bringToFront()
        findViewById<View>(R.id.tv_ai_status).bringToFront()
        findViewById<View>(R.id.tv_detection_count).bringToFront()
        findViewById<View>(R.id.CameraCapturePanel).bringToFront()
        findViewById<View>(R.id.camera_config_bar).bringToFront()
        // ✅ ФИКС z-порядка: ManualFocusWidget должен быть поверх fpvWidget
        // После mini→fullscreen swap, fpvWidget.bringToFront() ставит его выше ManualFocusWidget (XML pos 10).
        // Явно поднимаем ManualFocusWidget выше — DJI сам решит показывать или скрывать.
        manualFocusWidget.bringToFront()
        gimbalPitchView.bringToFront()
        findViewById<View>(R.id.telemetry_container).bringToFront()
        notificationContainer.bringToFront()
        btnAlbum.bringToFront()

        // Mini-FPV z-order: black bg → video → AI boxes → click interceptor
        if (isMapFullscreen) {
            fpvMiniBg.bringToFront()         // Black background (behind FPV)
            fpvWidget.bringToFront()         // Video on top
        }
        overlayView.bringToFront()           // AI detection boxes — всегда поверх UI в обоих режимах
        fpvMiniContainer.bringToFront()      // Click interceptor (topmost)
        mapControlsContainer.bringToFront()  // Map controls stack (Layers/GPS/Person)

        // Camera settings panels - MUST be on top
        try {
            rootLayout.findViewWithTag<View>("camera_exposure_panel")?.bringToFront()
            rootLayout.findViewWithTag<View>("camera_advanced_panel")?.bringToFront()
        } catch (e: Exception) {
            // Panels might not exist or have tags
        }

        if (preflightChecklistPanel.visibility == View.VISIBLE) {
            preflightOverlay.bringToFront()
            preflightChecklistPanel.bringToFront()
        }

        // В мини-режиме (карта small) — mapContainer поверх FPV
        if (!isMapFullscreen) {
            mapContainer.bringToFront()
        }

        // Force layout invalidation
        rootLayout.postInvalidate()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ============ AI Detection ============

    private fun toggleDetection() {
        isDetectionEnabled = !isDetectionEnabled

        if (isDetectionEnabled) {
            // Фон кнопки — тёмный (активный стиль), текст "AI" белый
            // Только статус-надпись снизу становится зелёной
            btnAiDetection.setBackgroundResource(R.drawable.circle_button_active)
            tvAiStatus.text = "AI: ON"
            tvAiStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            startAiFrameCapture()
            showNotification("AI Detection ON", false)
        } else {
            btnAiDetection.setBackgroundResource(R.drawable.circle_button_dark)
            tvAiStatus.text = "AI: OFF"
            tvAiStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
            consecutiveDetectionFrames = 0
            noDetectionFrames = 0
            stopAiFrameCapture()
            showNotification("AI Detection OFF", false)
        }
    }

    private fun startAiFrameCapture() {
        val detector = tfliteDetector ?: run {
            showNotification("AI model not loaded", true)
            return
        }

        // Cancel any existing job first
        detectionJob?.cancel()

        // Гарантируем что overlayView поверх всех UI-панелей перед стартом детекции
        bringOverlaysToFront()

        // Start periodic frame capture from FPVWidget
        detectionJob = lifecycleScope.launch(Dispatchers.Main) {
            Log.d(TAG, "AI frame capture started")

            var consecutiveErrors = 0
            var consecutiveBlackFrames = 0
            val maxConsecutiveErrors = 10
            val maxConsecutiveBlackFrames = 20 // Останавливаем если 20 черных кадров подряд

            while (isDetectionEnabled) {
                delay(detectionIntervalMs)

                if (!isDetectionEnabled) break

                try {
                    val bitmap = extractBitmapFromFpvWidget()
                    if (bitmap != null && !bitmap.isRecycled) {
                        processFrame(bitmap)
                        consecutiveErrors = 0 // Reset error counter on success
                        consecutiveBlackFrames = 0 // Reset black frame counter
                    } else {
                        consecutiveErrors++
                        consecutiveBlackFrames++
                        Log.w(TAG, "Failed to extract valid bitmap (errors:${consecutiveErrors}/${maxConsecutiveErrors}, black:${consecutiveBlackFrames}/${maxConsecutiveBlackFrames})")

                        if (consecutiveBlackFrames >= maxConsecutiveBlackFrames) {
                            showNotification("⚠️ No valid video feed - stopping detection", true)
                            isDetectionEnabled = false
                            withContext(Dispatchers.Main) {
                                toggleDetection() // Stop detection UI
                            }
                            break
                        }

                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            showNotification("Video feed issue detected", true)
                            consecutiveErrors = 0 // Reset to avoid spam
                        }
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Frame extraction error (${consecutiveErrors}/${maxConsecutiveErrors}): ${e.message}")

                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        showNotification("Detection error: ${e.message}", true)
                        consecutiveErrors = 0
                    }
                }
            }

            Log.d(TAG, "AI frame capture stopped")
        }
    }
    
    /**
     * Extract bitmap from FPVWidget's internal TextureView
     * ✅ ИСПРАВЛЕНИЕ: Добавлена валидация на черные/пустые кадры
     */
    private fun extractBitmapFromFpvWidget(): Bitmap? {
        return try {
            val bitmap = findTextureViewInView(fpvWidget)?.bitmap

            // Проверяем что bitmap валидный и не пустой/черный
            if (bitmap != null && !bitmap.isRecycled) {
                // Проверяем что это не черный кадр (когда дрон отключен)
                if (isBlackOrInvalidFrame(bitmap)) {
                    Log.w(TAG, "Detected black/invalid frame, skipping")
                    return null
                }
                return bitmap
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract bitmap: ${e.message}")
            null
        }
    }

    /**
     * Проверяет, является ли кадр черным, недействительным или тестовым паттерном
     * ✅ УЛУЧШЕНО: добавлена проверка на тестовые паттерны, цветные полосы, глитчи
     */
    private fun isBlackOrInvalidFrame(bitmap: Bitmap): Boolean {
        try {
            val width = bitmap.width
            val height = bitmap.height

            if (width == 0 || height == 0) return true

            // Увеличено количество точек для более точной проверки
            val samplePoints = mutableListOf<Pair<Int, Int>>()

            // Сетка 5x5 = 25 точек по всему изображению
            for (row in 1..5) {
                for (col in 1..5) {
                    val x = (width * col) / 6
                    val y = (height * row) / 6
                    samplePoints.add(Pair(x, y))
                }
            }

            var nonBlackPixels = 0
            val colors = mutableListOf<Int>()
            var totalBrightness = 0

            for ((x, y) in samplePoints) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val brightness = (r + g + b) / 3
                totalBrightness += brightness

                // Считаем не-черные пиксели
                if (r > 10 || g > 10 || b > 10) {
                    nonBlackPixels++
                }

                colors.add(pixel)
            }

            val avgBrightness = totalBrightness / samplePoints.size

            // ПРОВЕРКА 1: Слишком мало не-черных пикселей
            if (nonBlackPixels < 5) {
                Log.w(TAG, "❌ Frame rejected: too dark (${nonBlackPixels}/25 non-black pixels)")
                return true
            }

            // ПРОВЕРКА 2: Слишком яркий или слишком темный (глюк/тест паттерн)
            if (avgBrightness < 10 || avgBrightness > 245) {
                Log.w(TAG, "❌ Frame rejected: extreme brightness ($avgBrightness)")
                return true
            }

            // ПРОВЕРКА 3: Проверка на тестовый паттерн (цветные полосы)
            // Если все пиксели почти одинаковые - это тест паттерн
            val uniqueColors = colors.distinct().size
            if (uniqueColors < 3) {
                Log.w(TAG, "❌ Frame rejected: test pattern detected (only $uniqueColors unique colors)")
                return true
            }

            // ПРОВЕРКА 4: Проверка на вертикальные/горизонтальные полосы
            // Сравниваем первую строку с остальными
            var identicalRows = 0
            for (row in 1..4) {
                var rowMatches = true
                for (col in 1..5) {
                    val idx1 = (row - 1) * 5 + (col - 1)
                    val idx2 = row * 5 + (col - 1)

                    if (idx1 < colors.size && idx2 < colors.size) {
                        val color1 = colors[idx1]
                        val color2 = colors[idx2]

                        // Если цвета слишком похожи (разница < 5)
                        val r1 = (color1 shr 16) and 0xFF
                        val g1 = (color1 shr 8) and 0xFF
                        val b1 = color1 and 0xFF
                        val r2 = (color2 shr 16) and 0xFF
                        val g2 = (color2 shr 8) and 0xFF
                        val b2 = color2 and 0xFF

                        if (Math.abs(r1 - r2) > 5 || Math.abs(g1 - g2) > 5 || Math.abs(b1 - b2) > 5) {
                            rowMatches = false
                            break
                        }
                    }
                }
                if (rowMatches) identicalRows++
            }

            if (identicalRows >= 3) {
                Log.w(TAG, "❌ Frame rejected: horizontal stripes pattern detected")
                return true
            }

            // Кадр валиден
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error checking frame validity: ${e.message}")
            return true // В случае ошибки считаем кадр недействительным
        }
    }

    private fun findTextureViewInView(view: View): TextureView? {
        if (view is TextureView) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val found = findTextureViewInView(child)
                if (found != null) return found
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════
    // FPV VIDEO FALLBACK — for devices where FPVWidget doesn't show video
    // (known issue on Xiaomi/MIUI, some MediaTek devices)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Логирует в файл video_debug.txt — для диагностики на устройствах без ADB.
     * Файл: /sdcard/Android/data/com.minedetector/files/video_debug.txt
     */
    private fun logVideoDebug(msg: String) {
        Log.d(TAG, "[VIDEO_DBG] $msg")
        try {
            val file = File(getExternalFilesDir(null), "video_debug.txt")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("$ts  $msg\n")
        } catch (_: Exception) {}
    }

    /**
     * Планирует проверку FPV видео через 3 секунды после подключения дрона.
     * Если FPVWidget не показывает видео — подключает VideoFeeder вручную.
     */
    private fun scheduleVideoCheck() {
        videoCheckJob?.cancel()
        videoCheckJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(3000) // ждём 3 сек чтобы FPVWidget успел инициализировать кодек
            checkAndFixVideo()
        }
    }

    /**
     * Проверяет, показывает ли FPVWidget видео. Если экран чёрный — вручную
     * создаёт DJICodecManager и подписывается на VideoFeeder.primaryVideoFeed.
     *
     * Проблема: на некоторых устройствах (Xiaomi/MIUI, Snapdragon 855) DJI UX SDK's
     * FPVWidget молча не инициализирует свой внутренний кодек. Телеметрия работает,
     * UI отображается, но TextureView остаётся чёрным.
     */
    private fun checkAndFixVideo() {
        logVideoDebug("checkAndFixVideo() started")

        val tv = findTextureViewInView(fpvWidget)
        if (tv == null) {
            logVideoDebug("FAIL: TextureView not found inside FPVWidget")
            return
        }
        logVideoDebug("TextureView: ${tv.width}x${tv.height}, isAvailable=${tv.isAvailable}")

        if (!tv.isAvailable) {
            logVideoDebug("FAIL: TextureView surface not available")
            return
        }

        // Проверяем, есть ли реальное видео (не чёрный кадр)
        val bm = try { tv.bitmap } catch (_: Exception) { null }
        if (bm != null && !isBlackOrInvalidFrame(bm)) {
            logVideoDebug("OK: FPV video is working, no fix needed")
            return
        }
        logVideoDebug("FPV video is BLACK — applying manual VideoFeeder fallback")

        // Проверяем VideoFeeder
        val feeder = try { VideoFeeder.getInstance() } catch (e: Exception) {
            logVideoDebug("FAIL: VideoFeeder.getInstance() threw: ${e.message}")
            return
        }
        if (feeder == null) {
            logVideoDebug("FAIL: VideoFeeder is null (SDK not initialized?)")
            return
        }

        val primaryFeed = feeder.primaryVideoFeed
        if (primaryFeed == null) {
            logVideoDebug("FAIL: primaryVideoFeed is null (camera not connected?)")
            return
        }
        logVideoDebug("VideoFeeder OK, primaryVideoFeed OK — creating manual codec")

        try {
            // Очищаем предыдущий fallback если был
            cleanupManualVideoFallback()

            // Создаём свой DJICodecManager на TextureView из FPVWidget
            val surface = tv.surfaceTexture
            if (surface == null) {
                logVideoDebug("FAIL: surfaceTexture is null")
                return
            }
            manualCodecManager = DJICodecManager(this, surface, tv.width, tv.height)

            // Подписываемся на видеоданные и передаём в кодек
            val listener = VideoFeeder.VideoDataListener { videoBuffer, size ->
                manualCodecManager?.sendDataToDecoder(videoBuffer, size)
            }
            manualVideoListener = listener
            primaryFeed.addVideoDataListener(listener)

            logVideoDebug("✅ Manual video fallback ACTIVATED — video should appear now")
        } catch (e: Exception) {
            logVideoDebug("FAIL: Manual fallback exception: ${e.message}")
            Log.e(TAG, "Manual video fallback failed", e)
        }
    }

    /**
     * Очищает ресурсы ручного VideoFeeder fallback.
     */
    private fun cleanupManualVideoFallback() {
        try {
            manualVideoListener?.let { listener ->
                try {
                    VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(listener)
                } catch (_: Exception) {}
            }
            manualVideoListener = null
        } catch (_: Exception) {}

        try {
            manualCodecManager?.cleanSurface()
        } catch (_: Exception) {}
        manualCodecManager = null
    }

    private fun stopAiFrameCapture() {
        detectionJob?.cancel()
        detectionJob = null
        inferenceJob?.cancel()
        inferenceJob = null

        detectionViewModel.clearDetections()
        overlayView.clearDetections()
        sessionDetections = 0
        tvDetectionCount.text = ""
        tvDetectionCount.visibility = View.GONE
    }

    /**
     * Запускает инференс для одного кадра.
     *
     * ✅ ФИКС RACE CONDITION: TFLite Interpreter НЕ потокобезопасен.
     * Раньше каждые 150мс запускался новый Dispatchers.Default job, не дожидаясь предыдущего.
     * Инференс занимает 500–800мс → одновременно работало 4–5 потоков на одном interpreter +
     * outputBuffer → "Detection error: null" + мусорные детекции с 100% confidence.
     *
     * Теперь: если inferenceJob ещё активен — кадр пропускаем (drop frame).
     * Лучше пропустить кадр, чем получить неправильный результат или краш.
     */
    private fun processFrame(bitmap: Bitmap) {
        val detector = tfliteDetector ?: return

        // Пропускаем кадр если предыдущий инференс ещё не завершился
        if (inferenceJob?.isActive == true) return

        try {
            overlayView.setImageDimensions(bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set image dimensions: ${e.message}")
            return
        }

        inferenceJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                val detections = detector.detectObjects(bitmap)
                withContext(Dispatchers.Main) {
                    if (isDetectionEnabled) {
                        try {
                            detectionViewModel.updateDetections(detections)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update detections in ViewModel: ${e.message}")
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory during detection: ${e.message}")
                withContext(Dispatchers.Main) { showNotification("Low memory", true) }
            } catch (e: Exception) {
                Log.e(TAG, "Detection error: ${e.message}")
            }
        }
    }

    // ============ Gallery ============

    private fun openGallery() {
        try {
            val intent = android.content.Intent(this, GalleryActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open gallery: ${e.message}")
            Toast.makeText(this, "Failed to open gallery", Toast.LENGTH_SHORT).show()
        }
    }

    // ============ Lifecycle ============

    override fun onStart() {
        super.onStart()
        // ✅ MAPBOX: Lifecycle start
        mapView?.onStart()
    }

    override fun onStop() {
        super.onStop()
        // ✅ MAPBOX: Lifecycle stop
        mapView?.onStop()
    }

    /**
     * Вызывается когда активити уже запущено (launchMode=singleTask) и Android посылает
     * новый Intent — в т.ч. USB_ACCESSORY_ATTACHED при подключении дрона по USB.
     * DJI SDK получает USB permission автоматически через свой BroadcastReceiver,
     * поэтому достаточно переинициализировать соединение если SDK ещё не подключился.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "android.hardware.usb.action.USB_ACCESSORY_ATTACHED") {
            Log.d(TAG, "USB accessory attached via onNewIntent — scheduling DJI re-init on resume")
            // onNewIntent() вызывается пока activity ещё PAUSED (до onResume).
            // Запускать initDji() здесь ненадёжно — ставим флаг, реинит в onResume().
            pendingUsbReinit = true
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        // Регистрируем USB receiver — ловим физический reconnect кабеля
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachedReceiver, filter)
        }
        // USB reconnect: флаг выставлен в onNewIntent() или BroadcastReceiver.
        // Выполняем реинит здесь — activity уже RESUMED, lifecycle стабилен.
        if (pendingUsbReinit) {
            pendingUsbReinit = false
            Log.d(TAG, "USB reconnect: full DJI re-init on resume")
            connectionMonitorJob?.cancel()
            connectionManager?.disconnect()
            connectionManager = null
            initDji()
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(usbAttachedReceiver) } catch (_: Exception) {}

        if (isDetectionEnabled) {
            stopAiFrameCapture()
            isDetectionEnabled = false
            btnAiDetection.setBackgroundResource(R.drawable.circle_button_dark)
            tvAiStatus.text = "AI: OFF"
            tvAiStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAiFrameCapture()
        focusModePollingJob?.cancel()
        cameraParamsPollingJob?.cancel()
        videoCheckJob?.cancel()
        cleanupManualVideoFallback()
        connectionManager?.disconnect()
        tfliteDetector?.close()

        // ✅ MAPBOX: Cleanup
        mapView?.location?.removeOnIndicatorPositionChangedListener(userLocationListener)
        mapView?.onDestroy()
        mapView = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Mapbox не требует сохранения состояния (делает автоматически)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }
}
