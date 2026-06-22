package com.minedetector.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TFLite YOLO детектор мин.
 *
 * Формат модели (yolov8s, onnx2tf FP32):
 *   input  = [1, 640, 640, 3] float32 NHWC (R,G,B interleaved, 0..1)
 *   output = [1, 10, 8400] channels-first
 *     output[0][0..3][i] = cx, cy, w, h НОРМАЛИЗОВАННЫЕ (0..1) — onnx2tf нормализует!
 *                          Умножать на INPUT_SIZE чтобы получить пиксели (авто-детект)
 *     output[0][4..9][i] = вероятности 6 классов (sigmoid ВСТРОЕН — не применять снова!)
 *
 * Препроцессинг: простой resize (bitmap → 640×640, аспект НЕ сохраняется)
 *   Модель обучена на простом resize — letterbox (серые полосы) конфузит модель
 *   и даёт maxConf < 3% на всех кадрах (confirmed в логах: Diag maxConf=0.024).
 *
 * Оптимизации:
 *   - Все буферы pre-allocated (inputBuffer, outputBuffer, pixels, scaleBitmap)
 *   - Canvas-based resize без создания промежуточного Bitmap (нет GC на hot path)
 *   - Диагностика каждые 30 кадров (max confidence, сколько выше порога)
 */
class TFLiteYoloDetector(context: Context) {

    private lateinit var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private val labels: List<String>

    // ─── Output tensor layout (detected from tensor shape in init) ──────────
    /** true = [1, channels, anchors] (channels-first); false = [1, anchors, channels] */
    private var outputChannelsFirst = true
    private var outputChannels = 10
    private var outputAnchors = 8400

    companion object {
        private const val TAG = "TFLiteDetector"
        private const val MODEL_NAME = "yolo_mine_detector.tflite"
        private const val LABELS_FILE = "labels.txt"
        private const val INPUT_SIZE = 640

        // ─── Пороги детекции ─────────────────────────────────────────────────
        // Порог 0.45: повышен с 0.30 — 0.30 слишком низок для модели, обученной на
        // 129 изображениях, что приводит к большому количеству ложных срабатываний.
        // .pt модель детектирует отлично, значит TFLite конверсия корректна,
        // просто нужен более агрессивный порог отсечения шумовых детекций.
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.45f

        // IoU > 0.45 → считаем дублем и давим NMS (было 0.30, повышено чтобы
        // не убивать соседние реальные мины, стоящие рядом)
        private const val IOU_THRESHOLD = 0.45f

        // Максимум боксов на кадр (после NMS)
        private const val MAX_DETECTIONS = 10

        // ─── Фильтры размера бокса ───────────────────────────────────────────
        // Нормализованные (0–1) значения: (w_px/640)*(h_px/640)
        // Мина может занимать до 30% площади кадра (дрон низко → объект крупный).
        // Минимум 0.03% — очень мелкий объект с высоты.
        private const val MIN_BOX_AREA_NORM = 0.0005f
        private const val MAX_BOX_AREA_NORM = 0.15f

        // ─── Фильтр пропорций ────────────────────────────────────────────────
        // Мины примерно круглые/квадратные. Небольшое расширение до 4:1
        // для MON-50 (прямоугольный корпус при боковом ракурсе).
        private const val MAX_ASPECT_RATIO = 3.0f

        // ─── Отступ от края кадра ────────────────────────────────────────────
        // Центр бокса должен быть не ближе 4% от края (было 8% — слишком агрессивно).
        // Мины у краёв кадра реальны, особенно при телеметрических разворотах.
        private const val EDGE_MARGIN_FRAC = 0.06f

        // ─── Letterbox ───────────────────────────────────────────────────────
        // Цвет паддинга = серый 114 (стандарт Ultralytics YOLO)
        private const val LB_FILL = 114

        // ─── Фильтр тёмных зон ───────────────────────────────────────────────
        // Если средняя яркость пикселей внутри bbox < порога → чёрная зона
        // (нет картинки с дрона, темнота, ночь). Шаг выборки = 4px (быстро).
        private const val MIN_BOX_BRIGHTNESS = 25f   // 0..255; <25 = почти чёрный

        // ─── Диагностика ─────────────────────────────────────────────────────
        // Логируем статистику каждые N кадров (помогает настраивать порог)
        private const val DIAG_INTERVAL = 30
    }

    // ─── Pre-allocated буферы — ни одной аллокации на горячем пути ──────────

    /** Float32 RGB [0,1], interleaved: RGBRGBRGB... (NHWC = [1,640,640,3]) */
    private val inputBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .apply { order(ByteOrder.nativeOrder()) }

    /** Output buffer: shape determined from actual tensor after init */
    private lateinit var outputBuffer: Array<Array<FloatArray>>

    /** 640×640 пикселей как Int (ARGB) — 1.6 МБ, выделяется один раз */
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    /** Canvas target для resize — 640×640 ARGB_8888, переиспользуется (нет GC на hot path) */
    private var scaleBitmap: Bitmap? = null
    private val scaleCanvas = Canvas()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)  // билинейная фильтрация

    /**
     * Масштабные коэффициенты последнего resize: scaleX = 640/origW, scaleY = 640/origH
     * Undo в postprocess: cx_orig = cx_640 / scaleX = cx_640 * origW / 640
     */
    private var resizeScaleX = 1f
    private var resizeScaleY = 1f

    /** Счётчик кадров для диагностических логов */
    private var diagFrameCount = 0

    /**
     * Авто-определяемый формат координат модели:
     *   false = пиксели (0..640)  — используем как есть
     *   true  = нормализованные (0..1) — умножаем на INPUT_SIZE перед фильтрами
     * Определяется в первом кадре по максимальному значению cx/cy/w/h.
     */
    private var coordIsNormalized = false

    /**
     * true если модель выдаёт сырые логиты (не sigmoid).
     * Определяется автоматически в первом кадре по диапазону class scores.
     * Если true — применяем sigmoid(x) = 1/(1+exp(-x)) к class scores.
     */
    private var needSigmoid = false

    /** Fixed confidence threshold. Per-class floors in classMinThreshold override this downward. */
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD

    /**
     * Per-class minimum confidence thresholds.
     * A candidate must satisfy BOTH: conf >= confidenceThreshold AND conf >= classMinThreshold[classId].
     * PMN-1 (id=2) defaults to 0.55 because its round shape causes false positives
     * on circular UI elements (compass dials, buttons, etc.).
     * Set a class entry to 0f to disable per-class floor for that class.
     */
    private val classMinThreshold: FloatArray = FloatArray(10) { 0f }.also { arr ->
        // Покласные пороги: для каждого класса устанавливаем минимальную уверенность,
        // ниже которой детекция считается ложной. Базовый порог (0.45) применяется ко всем,
        // здесь можно ПОВЫСИТЬ порог для проблемных классов.
        //
        // ДАННЫЕ С ПОЛЁТА (28 скринов, апрель 2026):
        //   MON-50 — ГЛАВНАЯ ПРОБЛЕМА: модель массово детектит траву как MON-50
        //   с confidence до 68%! На чистой траве без мин — 4-7 боксов MON-50.
        //   Причина: всего ~20 train-зразків, модель вивчила текстуру трави як фічу.
        //   PMN-1/PMN-2 — встречаются реже, но тоже есть ложняки на 30-40%.
        //   PFM-1 — при наличии реального объекта даёт ~40-55%, отсечь ниже 0.50.
        //   TM-62 — большие bbox на пустой траве, ложняки до 43%.
        //   POM-3 — ложняки на тёмных зонах ~26%.
        arr[0] = 0.72f   // MON-50: АГРЕССИВНЫЙ порог — ложняки до 68% на траве!
        arr[1] = 0.52f   // PFM-1:  малый размер → шум, но реальные ~55%
        arr[2] = 0.55f   // PMN-1:  круглая форма, ложняки на камнях/кнопках
        arr[3] = 0.50f   // PMN-2:  аналогично PMN-1
        arr[4] = 0.50f   // POM-3:  ложняки на тёмных зонах
        arr[5] = 0.55f   // TM-62:  большие bbox-ложняки до 43%, реальные должны быть выше
    }

    /**
     * Максимум детекций одного класса в одном кадре.
     * Если класс X появляется > лимита раз — скорее всего все X ложные (grid-артефакт).
     * MON-50 особенно страдает: на траве может быть 4-7 "MON-50" за кадр.
     */
    private val maxPerClassPerFrame = intArrayOf(
        2,   // MON-50 (id=0): >2 за кадр = 100% ложняк
        3,   // PFM-1 (id=1): мелкие, могут быть рядом (кассетные)
        3,   // PMN-1 (id=2)
        3,   // PMN-2 (id=3)
        2,   // POM-3 (id=4)
        2,   // TM-62 (id=5): большая, не бывает >2 рядом
    )

    // ─────────────────────────────────────────────────────────────────────────

    init {
        try {
            labels = context.assets.open(LABELS_FILE).bufferedReader().readLines()
            val modelBuffer = loadModelFile(context)

            val (interp, backend) = createInterpreter(modelBuffer)
            interpreter = interp

            val inp = interpreter.getInputTensor(0)
            val out = interpreter.getOutputTensor(0)
            configureOutputLayout(out.shape())
            Log.d(TAG, "✅ Model loaded [$backend] | classes=${labels.size} " +
                "in=${inp.shape().contentToString()} dtype=${inp.dataType()} " +
                "out=${out.shape().contentToString()} dtype=${out.dataType()} | " +
                "layout=${if (outputChannelsFirst) "C×N[1,$outputChannels,$outputAnchors]" else "N×C[1,$outputAnchors,$outputChannels]"} | " +
                "threshold=${confidenceThreshold}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model init failed", e)
            throw RuntimeException("TFLiteYoloDetector init failed: ${e.message}", e)
        }
    }

    /**
     * Tries to build an Interpreter with GPU delegate. Falls back to CPU×4 on any error.
     *
     * We intentionally skip CompatibilityList: on Samsung Exynos 990 (SM-N985F) its
     * static initializer throws NoClassDefFoundError when the native GPU .so is absent,
     * and even a catch(Throwable) cannot reliably intercept class-loading failures that
     * happen inside <clinit>. Instead we directly construct GpuDelegate() — if the GPU
     * is truly unsupported it throws a catchable exception at this point.
     */
    private fun createInterpreter(modelBuffer: java.nio.MappedByteBuffer): Pair<Interpreter, String> {
        try {
            val delegate = GpuDelegate()
            val opts = Interpreter.Options().apply { addDelegate(delegate) }
            val interp = Interpreter(modelBuffer, opts)
            gpuDelegate = delegate
            return Pair(interp, "GPU")
        } catch (t: Throwable) {
            gpuDelegate = null
            Log.w(TAG, "⚠️ GPU delegate unavailable, falling back to CPU: " +
                "${t.javaClass.simpleName} — ${t.message}")
        }
        val opts = Interpreter.Options().apply { setNumThreads(4) }
        return Pair(Interpreter(modelBuffer, opts), "CPU×4 (GPU unavailable)")
    }

    /**
     * Reads output tensor shape and configures channel/anchor layout + output buffer.
     * YOLOv8 TFLite typically exports as [1, 10, 8400] (channels-first),
     * but some exporters produce [1, 8400, 10] (channels-last).
     * Getting this wrong causes coordinates to be read as class scores → wrong bbox position.
     */
    private fun configureOutputLayout(shape: IntArray) {
        require(shape.size == 3 && shape[0] == 1) {
            "Unsupported output tensor shape: ${shape.contentToString()}"
        }
        val d1 = shape[1]
        val d2 = shape[2]
        // Heuristic: the smaller dimension is channels (4 coords + N classes)
        outputChannelsFirst = d1 <= 64 && d2 > d1
        if (outputChannelsFirst) {
            outputChannels = d1
            outputAnchors  = d2
        } else {
            outputAnchors  = d1
            outputChannels = d2
        }
        outputBuffer = if (outputChannelsFirst) {
            Array(1) { Array(outputChannels) { FloatArray(outputAnchors) } }
        } else {
            Array(1) { Array(outputAnchors) { FloatArray(outputChannels) } }
        }
    }

    /** Access output value regardless of channels-first vs channels-last layout. */
    private fun rawAt(output: Array<Array<FloatArray>>, channel: Int, anchor: Int): Float =
        if (outputChannelsFirst) output[0][channel][anchor] else output[0][anchor][channel]

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_NAME)
        return fd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)
            }
        }
    }

    // ─── Публичный API ────────────────────────────────────────────────────────

    fun detectObjects(bitmap: Bitmap): List<Detection> {
        if (bitmap.isRecycled) return emptyList()

        return try {
            val t0 = System.currentTimeMillis()

            preprocessSimpleResize(bitmap)
            interpreter.run(inputBuffer, outputBuffer)
            val result = postprocess(outputBuffer, bitmap.width, bitmap.height)

            if (result.isNotEmpty()) {
                val dt = System.currentTimeMillis() - t0
                Log.d(TAG, "✅ ${result.size} detection(s) | ${dt}ms | " +
                    result.joinToString { d ->
                        val b = d.boundingBox
                        "${d.label} ${"%.0f".format(d.confidence * 100)}% " +
                        "bbox=[${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}] " +
                        "(img ${bitmap.width}x${bitmap.height})"
                    })
            }

            result
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ OOM during detection — skipping frame")
            System.gc()
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Detection error: ${e.message}")
            emptyList()
        }
    }

    // ─── Препроцессинг: простой resize ────────────────────────────────────

    /**
     * Масштабируем bitmap → 640×640 без сохранения пропорций (простой resize).
     *
     * Модель обучена на distorted-квадрате (Ultralytics imgsz=640, rect=False или
     * кастомный пайплайн). Letterbox (серые полосы) модель не видела при обучении
     * → maxConf < 3% на всех кадрах, нет детекций.
     *
     * Сохраняем resizeScaleX/Y для обратного преобразования в postprocess:
     *   cx_orig = cx_640 / resizeScaleX  = cx_640 * origW / 640
     *   cy_orig = cy_640 / resizeScaleY  = cy_640 * origH / 640
     *
     * Нет аллокаций на hot path: scaleBitmap pre-allocated, scaleCanvas переиспользуется.
     */
    private fun preprocessSimpleResize(bitmap: Bitmap) {
        resizeScaleX = INPUT_SIZE / bitmap.width.toFloat()   // напр. 640/2249 = 0.2845
        resizeScaleY = INPUT_SIZE / bitmap.height.toFloat()  // напр. 640/1080 = 0.5926

        // Pre-allocated 640×640 target (создаётся один раз)
        val sb = scaleBitmap ?: Bitmap
            .createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            .also { scaleBitmap = it }

        scaleCanvas.setBitmap(sb)

        // Рисуем источник прямо в 640×640 (без промежуточного Bitmap)
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = RectF(0f, 0f, INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat())
        scaleCanvas.drawBitmap(bitmap, srcRect, dstRect, scalePaint)

        // ARGB → float32 RGB [0,1]
        sb.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        inputBuffer.rewind()
        for (px in pixels) {
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)  // R
            inputBuffer.putFloat(((px shr 8)  and 0xFF) / 255f)  // G
            inputBuffer.putFloat((px           and 0xFF) / 255f)  // B
        }
    }

    // ─── Постпроцессинг ────────────────────────────────────────────────────

    private fun postprocess(
        output: Array<Array<FloatArray>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<Detection> {
        val numAnchors = outputAnchors         // e.g. 8400
        val numClasses = outputChannels - 4   // e.g. 10 - 4 = 6

        val imgW = imageWidth.toFloat()
        val imgH = imageHeight.toFloat()

        // ── Авто-определение формата координат (ОДИН РАЗ, первый кадр) ────────
        // Ultralytics TFLite может выдавать пиксели (0..640) ИЛИ нормализованные (0..1)
        // в зависимости от версии/флагов экспорта.
        // Эвристика: если max(cx,cy,w,h) по первым 200 якорям ≤ 2.0 → normalized.
        if (diagFrameCount == 0) {
            var maxRaw = 0f
            for (i in 0 until minOf(200, numAnchors)) {
                for (c in 0..3) {
                    val v = rawAt(output, c, i)
                    if (v > maxRaw) maxRaw = v
                }
            }
            coordIsNormalized = maxRaw <= 2.0f
            Log.d(TAG, "🔍 Coord format: ${if (coordIsNormalized) "NORMALIZED (0..1) → ×640" else "PIXELS (0..640)"} " +
                "(maxRaw sample=${"%.4f".format(maxRaw)})")

            // ── Sigmoid check: проверяем диапазон class scores ──
            // Если sigmoid встроен → значения в [0,1]. Если нет → могут быть <0 или >1.
            var minClassScore = Float.MAX_VALUE
            var maxClassScore = Float.MIN_VALUE
            for (i in 0 until minOf(500, numAnchors)) {
                for (c in 4 until 4 + numClasses) {
                    val v = rawAt(output, c, i)
                    if (v < minClassScore) minClassScore = v
                    if (v > maxClassScore) maxClassScore = v
                }
            }
            Log.d(TAG, "🔍 Class score range: [${"%.4f".format(minClassScore)}, ${"%.4f".format(maxClassScore)}] " +
                "— ${if (minClassScore >= -0.01f && maxClassScore <= 1.01f) "✅ sigmoid OK (values in [0,1])" else "⚠️ RAW LOGITS detected! Sigmoid NOT built-in!"}")

            // Если обнаружены сырые логиты — автоматически применяем sigmoid
            if (minClassScore < -0.5f || maxClassScore > 1.5f) {
                Log.w(TAG, "⚠️ Model outputs raw logits — will apply sigmoid manually")
                // Установим флаг для sigmoid
                needSigmoid = true
            }
        }
        // Масштаб: если нормализованные → умножаем на 640 чтобы получить пиксели
        val coordScale = if (coordIsNormalized) INPUT_SIZE.toFloat() else 1.0f

        val candidates = mutableListOf<Detection>()

        // Диагностика
        val DIAG_LOG_THRESHOLD = 0.20f
        var maxConfAny = 0f
        var aboveDiag  = 0
        // Счётчики: какой именно фильтр убивает кандидатов
        var filtArea = 0; var filtAspect = 0; var filtBounds = 0
        var filtFrac = 0; var filtSize   = 0; var filtDark = 0
        // Лучший якорь (для логирования сырых координат)
        var bestIdx = -1; var bestConf = 0f

        for (i in 0 until numAnchors) {

            var maxConf  = 0f
            var maxClass = -1
            for (c in 0 until numClasses) {
                var conf = rawAt(output, 4 + c, i)
                if (needSigmoid) conf = (1.0f / (1.0f + kotlin.math.exp(-conf)))
                if (conf > maxConf) { maxConf = conf; maxClass = c }
            }

            if (maxConf > maxConfAny) maxConfAny = maxConf
            if (maxConf > bestConf)   { bestConf = maxConf; bestIdx = i }
            if (maxConf > DIAG_LOG_THRESHOLD) aboveDiag++
            val classFloor = if (maxClass in classMinThreshold.indices) classMinThreshold[maxClass] else 0f
            if (maxConf < confidenceThreshold || maxConf < classFloor) continue

            val c0 = rawAt(output, 0, i) * coordScale
            val c1 = rawAt(output, 1, i) * coordScale
            val c2 = rawAt(output, 2, i) * coordScale
            val c3 = rawAt(output, 3, i) * coordScale

            val rect = decodeXywhRect(c0, c1, c2, c3, imgW, imgH)
            if (rect == null) { filtBounds++; continue }

            val rectW = rect.width()
            val rectH = rect.height()
            if (rectW < 4f || rectH < 4f) { filtSize++; continue }

            val wNorm    = rectW / imgW
            val hNorm    = rectH / imgH
            val areaNorm = wNorm * hNorm
            if (areaNorm < MIN_BOX_AREA_NORM || areaNorm > MAX_BOX_AREA_NORM) { filtArea++; continue }

            val aspect = rectW / rectH
            if (aspect > MAX_ASPECT_RATIO || aspect < 1f / MAX_ASPECT_RATIO) { filtAspect++; continue }

            val cx = rect.centerX()
            val cy = rect.centerY()
            val edgeMarginX = imgW * EDGE_MARGIN_FRAC
            val edgeMarginY = imgH * EDGE_MARGIN_FRAC
            if (cx < edgeMarginX || cy < edgeMarginY ||
                cx > imgW - edgeMarginX || cy > imgH - edgeMarginY) { filtBounds++; continue }

            if (rectW / imgW > 0.55f || rectH / imgH > 0.60f) { filtFrac++; continue }

            val bx0 = (rect.left  * resizeScaleX).toInt().coerceIn(0, INPUT_SIZE - 1)
            val by0 = (rect.top   * resizeScaleY).toInt().coerceIn(0, INPUT_SIZE - 1)
            val bx1 = (rect.right * resizeScaleX).toInt().coerceIn(0, INPUT_SIZE - 1)
            val by1 = (rect.bottom * resizeScaleY).toInt().coerceIn(0, INPUT_SIZE - 1)
            var brightSum = 0L; var brightCount = 0
            var sy = by0; while (sy <= by1) {
                var sx = bx0; while (sx <= bx1) {
                    val px = pixels[sy * INPUT_SIZE + sx]
                    brightSum += ((px shr 16) and 0xFF) + ((px shr 8) and 0xFF) + (px and 0xFF)
                    brightCount++
                    sx += 4
                }
                sy += 4
            }
            val meanBright = if (brightCount > 0) brightSum / brightCount / 3f else 0f
            if (meanBright < MIN_BOX_BRIGHTNESS) { filtDark++; continue }

            candidates.add(
                Detection(
                    classId     = maxClass,
                    label       = labels.getOrNull(maxClass) ?: "Unknown",
                    confidence  = maxConf,
                    boundingBox = rect
                )
            )
        }

        diagFrameCount++
        if (diagFrameCount % DIAG_INTERVAL == 0) {
            val rawCoords = if (bestIdx >= 0 && bestConf > 0.1f)
                "best#${bestIdx}(conf=${"%.3f".format(bestConf)}" +
                " c0=${"%.4f".format(rawAt(output, 0, bestIdx))}" +
                " c1=${"%.4f".format(rawAt(output, 1, bestIdx))}" +
                " c2=${"%.4f".format(rawAt(output, 2, bestIdx))}" +
                " c3=${"%.4f".format(rawAt(output, 3, bestIdx))})"
            else "noGoodAnchor"

            Log.d(TAG, "📊 Diag[${diagFrameCount}]: " +
                "maxConf=${"%.3f".format(maxConfAny)} " +
                "above20%=$aboveDiag survived=${candidates.size} " +
                "fmt=${if (coordIsNormalized) "NORM" else "PX"} " +
                "killed(area=$filtArea asp=$filtAspect bnd=$filtBounds frac=$filtFrac sz=$filtSize dark=$filtDark) " +
                rawCoords)
        }

        if (candidates.isEmpty()) return emptyList()

        // ── NMS (per-class) ──────────────────────────────────────────────────
        val afterNms = nonMaxSuppression(candidates)

        // ── Защита: grid-pattern = мусорные детекции (≥5 равномерных боксов) ─
        // Снижено с 9 до 5: на однородных текстурах (трава, грунт) модель часто
        // генерирует 5-8 "фантомных" детекций в регулярной сетке.
        if (afterNms.size >= 5 && isGridPattern(afterNms)) {
            Log.w(TAG, "⚠️ Grid-pattern suppressed (${afterNms.size} evenly-spaced boxes)")
            return emptyList()
        }

        // ── Per-class flood filter ──────────────────────────────────────────
        // Если одного класса слишком много в кадре → убираем ВСЕ этого класса.
        // Пример: 4× MON-50 на чистой траве = все ложные (реально >2 MON-50 рядом не бывает).
        val classCount = IntArray(numClasses + 4)
        for (d in afterNms) classCount[d.classId]++
        val afterFlood = afterNms.filter { d ->
            val limit = maxPerClassPerFrame.getOrElse(d.classId) { 3 }
            val count = classCount[d.classId]
            if (count > limit) {
                Log.w(TAG, "⚠️ Class flood: ${d.label} × $count > limit $limit — dropping all ${d.label}")
                false
            } else true
        }

        return afterFlood
            .sortedByDescending { it.confidence }
            .take(MAX_DETECTIONS)
    }

    // ─── Декодирование bbox ───────────────────────────────────────────────────

    /**
     * Декодирует [cx, cy, w, h] в пикселях 640-пространства → RectF в оригинальных пикселях.
     * YOLOv8 всегда выдаёт xywh (cx,cy,w,h). Возвращает null если размер нулевой.
     */
    private fun decodeXywhRect(c0: Float, c1: Float, c2: Float, c3: Float, imgW: Float, imgH: Float): RectF? {
        if (c2 <= 0f || c3 <= 0f) return null
        val cx = c0 / resizeScaleX
        val cy = c1 / resizeScaleY
        val w  = c2 / resizeScaleX
        val h  = c3 / resizeScaleY
        if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite()) return null
        return RectF(
            (cx - w / 2f).coerceAtLeast(0f),
            (cy - h / 2f).coerceAtLeast(0f),
            (cx + w / 2f).coerceAtMost(imgW),
            (cy + h / 2f).coerceAtMost(imgH)
        )
    }

    // ─── NMS ──────────────────────────────────────────────────────────────────

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val result = mutableListOf<Detection>()
        for ((_, group) in detections.groupBy { it.classId }) {
            val sorted = group.sortedByDescending { it.confidence }
            val kept   = mutableListOf<Detection>()
            for (det in sorted) {
                if (kept.none { iou(det.boundingBox, it.boundingBox) > IOU_THRESHOLD }) {
                    kept.add(det)
                    if (kept.size >= MAX_DETECTIONS) break
                }
            }
            result.addAll(kept)
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val il = max(a.left, b.left);  val it = max(a.top, b.top)
        val ir = min(a.right, b.right); val ib = min(a.bottom, b.bottom)
        if (ir <= il || ib <= it) return 0f
        val inter = (ir - il) * (ib - it)
        val union = (a.right - a.left) * (a.bottom - a.top) +
                    (b.right - b.left) * (b.bottom - b.top) - inter
        return if (union > 0f) inter / union else 0f
    }

    // ─── Grid-pattern фильтр ──────────────────────────────────────────────────

    /**
     * Если 9+ боксов расположены почти равномерно (CV расстояний < 0.2) →
     * это артефакт сетки якорей, а не реальные объекты.
     */
    private fun isGridPattern(detections: List<Detection>): Boolean {
        if (detections.size < 5) return false
        val centers = detections.map {
            Pair(
                (it.boundingBox.left + it.boundingBox.right)  / 2f,
                (it.boundingBox.top  + it.boundingBox.bottom) / 2f
            )
        }
        val dists = mutableListOf<Float>()
        for (i in centers.indices) {
            for (j in i + 1 until centers.size) {
                val dx = centers[i].first  - centers[j].first
                val dy = centers[i].second - centers[j].second
                dists.add(sqrt(dx * dx + dy * dy))
            }
        }
        val avg  = dists.average().toFloat()
        val std  = sqrt(dists.map { (it - avg) * (it - avg) }.average()).toFloat()
        val cv   = if (avg > 0f) std / avg else 0f
        return cv < 0.2f
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun close() {
        try {
            interpreter.close()
            gpuDelegate?.close()
            gpuDelegate = null
            scaleBitmap?.recycle()
            scaleBitmap = null
            Log.d(TAG, "Detector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing detector: ${e.message}")
        }
    }
}
