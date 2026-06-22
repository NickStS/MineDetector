package com.minedetector

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MineDetectorApplication : Application() {

    companion object {
        private const val TAG = "MineDetectorApp"

        /** Single source of truth for test mode. Set true to test without drone. */
        const val TEST_MODE = false

        @Volatile
        lateinit var instance: MineDetectorApplication
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        MultiDex.install(this)

        if (!TEST_MODE) {
            try {
                Class.forName("com.secneo.sdk.Helper")
                    .getMethod("install", Application::class.java)
                    .invoke(null, this)
                Log.d(TAG, "DJI Helper installed successfully")
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "DJI Helper class not found - running without DJI SDK")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install DJI Helper: ${e.message}")
            }
        } else {
            Log.d(TAG, "Application context attached (DJI Helper disabled for testing)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")
        installCrashLogger()

        try {
            if (BuildConfig.MAPBOX_ACCESS_TOKEN.isNotEmpty()) {
                com.mapbox.common.MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
                Log.d(TAG, "Mapbox initialized with access token")
            } else {
                Log.w(TAG, "Mapbox token not configured in local.properties")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Mapbox: ${e.message}")
        }
    }

    /**
     * Перехватывает все необработанные исключения и сохраняет их в файл.
     *
     * Зачем: на устройствах без ADB (Smart Controller, телефон коллеги)
     * невозможно получить logcat. Этот обработчик пишет краш-репорт в файл
     * на внешнем хранилище устройства.
     *
     * Файл: /sdcard/Android/data/com.minedetector/files/crash_log.txt
     * (или внутренний /data/.../files/crash_log.txt если внешнее недоступно)
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Составляем текст краш-репорта
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val deviceInfo = buildString {
                    appendLine("=== MineDetector CRASH LOG ===")
                    appendLine("Time      : $timestamp")
                    appendLine("Device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Android   : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Thread    : ${thread.name}")
                    appendLine("App ver   : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine()
                    appendLine("--- EXCEPTION ---")
                    appendLine(sw.toString())
                    appendLine("=================")
                }

                // Сохраняем в файл (пробуем внешнее, потом внутреннее)
                val logFile = try {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    File(dir, "crash_log.txt")
                } catch (ex: Exception) {
                    File(filesDir, "crash_log.txt")
                }

                // Добавляем к предыдущим крашам (до 5 записей)
                val existing = if (logFile.exists()) logFile.readText() else ""
                val allLogs = "$deviceInfo\n$existing"
                    .lines()
                    .take(500)           // ограничиваем размер файла
                    .joinToString("\n")
                logFile.writeText(allLogs)

                Log.e(TAG, "💥 CRASH SAVED TO: ${logFile.absolutePath}")
                Log.e(TAG, deviceInfo)
            } catch (ex: Exception) {
                // Если сохранение провалилось — хотя бы логируем
                Log.e(TAG, "💥 CRASH (file save failed): ${throwable.message}")
            }

            // Вызываем оригинальный обработчик (показывает диалог "has stopped")
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "✅ Crash logger installed → ${(getExternalFilesDir(null) ?: filesDir).absolutePath}/crash_log.txt")
    }
}