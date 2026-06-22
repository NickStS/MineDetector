package com.minedetector.dji

import android.graphics.Bitmap
import android.util.Log
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.media.DownloadListener
import dji.sdk.media.FetchMediaTask
import dji.sdk.media.FetchMediaTaskContent
import dji.sdk.media.FetchMediaTaskScheduler
import dji.sdk.media.MediaFile
import dji.sdk.media.MediaManager
import dji.sdk.sdkmanager.DJISDKManager
import java.io.File

/**
 * DJI MSDK v4.x compatible (CompletionCallback<T> with onResult(T)).
 * Success is usually delivered as error == null.
 */
class DJIMediaManager {

    companion object {
        private const val TAG = "DJIMediaManager"
    }

    private var mediaManager: MediaManager? = null
    private var scheduler: FetchMediaTaskScheduler? = null
    private var isInMediaMode: Boolean = false

    data class DroneMediaFile(
        val fileName: String,
        val fileSize: Long,
        val dateCreated: String,   // in your SDK it's String
        val timeCreated: Long,     // epoch millis
        val mediaType: MediaFile.MediaType,
        val durationMs: Long,
        val thumbnail: Bitmap? = null,
        val mediaFile: MediaFile
    )

    interface MediaListCallback {
        fun onSuccess(mediaFiles: List<DroneMediaFile>)
        fun onError(error: String)
    }

    interface BitmapCallback {
        fun onSuccess(bitmap: Bitmap)
        fun onError(error: String)
    }

    // -------------------- Init --------------------

    fun initialize(): Boolean {
        return try {
            val product = DJISDKManager.getInstance().product
            val camera = product?.camera
            if (camera == null) {
                Log.e(TAG, "Camera not available")
                false
            } else {
                val mm = camera.mediaManager
                if (mm == null) {
                    Log.e(TAG, "MediaManager not available")
                    false
                } else {
                    mediaManager = mm
                    scheduler = mm.scheduler
                    Log.d(TAG, "MediaManager initialized")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaManager", e)
            false
        }
    }

    fun isAvailable(): Boolean {
        return try {
            DJISDKManager.getInstance().product?.camera?.mediaManager != null
        } catch (_: Exception) {
            false
        }
    }

    // -------------------- Mode switching --------------------

    fun enterMediaMode(callback: (ok: Boolean, error: String?) -> Unit) {
        val camera = DJISDKManager.getInstance().product?.camera
        if (camera == null) {
            callback(false, "Camera not available")
            return
        }

        camera.setMode(
            SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD,
            object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) {
                    if (error != null) {
                        val msg = error.description ?: error.toString()
                        Log.e(TAG, "Failed to enter media mode: $msg")
                        callback(false, msg)
                    } else {
                        isInMediaMode = true
                        Log.d(TAG, "Entered MEDIA_DOWNLOAD mode")
                        callback(true, null)
                    }
                }
            }
        )
    }

    fun exitMediaMode(callback: (ok: Boolean, error: String?) -> Unit) {
        val camera = DJISDKManager.getInstance().product?.camera
        if (camera == null) {
            callback(false, "Camera not available")
            return
        }

        camera.setMode(
            SettingsDefinitions.CameraMode.SHOOT_PHOTO,
            object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) {
                    isInMediaMode = false
                    if (error != null) {
                        val msg = error.description ?: error.toString()
                        callback(false, msg)
                    } else {
                        callback(true, null)
                    }
                }
            }
        )
    }

    // -------------------- File list --------------------

    fun refreshMediaList(callback: MediaListCallback) {
        val manager = mediaManager
        if (manager == null) {
            callback.onError("MediaManager not initialized")
            return
        }
        if (!isInMediaMode) {
            callback.onError("Not in media mode. Call enterMediaMode() first")
            return
        }

        manager.refreshFileListOfStorageLocation(
            SettingsDefinitions.StorageLocation.SDCARD,
            object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(error: DJIError?) {
                    if (error != null) {
                        val msg = error.description ?: error.toString()
                        Log.e(TAG, "Failed to refresh file list: $msg")
                        callback.onError(msg)
                        return
                    }

                    val sdCardFiles = manager.sdCardFileListSnapshot
                    if (sdCardFiles == null || sdCardFiles.isEmpty()) {
                        Log.d(TAG, "No files found on SD card")
                        callback.onSuccess(emptyList())
                        return
                    }

                    val droneFiles = sdCardFiles.map { mf ->
                        DroneMediaFile(
                            fileName = mf.fileName,
                            fileSize = mf.fileSize,
                            dateCreated = mf.dateCreated, // String
                            timeCreated = mf.timeCreated, // Long
                            mediaType = mf.mediaType,
                            durationMs = (mf.durationInSeconds * 1000f).toLong(), // float -> ms
                            thumbnail = null,
                            mediaFile = mf
                        )
                    }.sortedByDescending { it.timeCreated }

                    callback.onSuccess(droneFiles)
                }
            }
        )
    }

    // -------------------- Scheduler --------------------

    fun startScheduler() {
        val sched = scheduler ?: return
        sched.resume(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    val msg = error.description ?: error.toString()
                    Log.e(TAG, "Failed to resume scheduler: $msg")
                } else {
                    Log.d(TAG, "Scheduler resumed")
                }
            }
        })
    }

    fun stopScheduler() {
        val sched = scheduler ?: return
        sched.suspend(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error != null) {
                    val msg = error.description ?: error.toString()
                    Log.e(TAG, "Failed to suspend scheduler: $msg")
                } else {
                    Log.d(TAG, "Scheduler suspended")
                }
            }
        })
    }

    // -------------------- Thumbnail / Preview --------------------

    fun fetchThumbnail(droneFile: DroneMediaFile, callback: BitmapCallback) {
        val sched = scheduler
        if (sched == null) {
            callback.onError("Scheduler not initialized")
            return
        }

        val task = FetchMediaTask(
            droneFile.mediaFile,
            FetchMediaTaskContent.THUMBNAIL
        ) { file, _, error ->
            if (error != null) {
                val msg = error.description ?: error.toString()
                callback.onError(msg)
            } else {
                val bmp = file.thumbnail
                if (bmp != null) callback.onSuccess(bmp) else callback.onError("Thumbnail is null")
            }
        }

        sched.moveTaskToEnd(task)
    }

    fun fetchPreview(droneFile: DroneMediaFile, callback: BitmapCallback) {
        val sched = scheduler
        if (sched == null) {
            callback.onError("Scheduler not initialized")
            return
        }

        val task = FetchMediaTask(
            droneFile.mediaFile,
            FetchMediaTaskContent.PREVIEW
        ) { file, _, error ->
            if (error != null) {
                val msg = error.description ?: error.toString()
                callback.onError(msg)
            } else {
                val bmp = file.preview
                if (bmp != null) callback.onSuccess(bmp) else callback.onError("Preview is null")
            }
        }

        sched.moveTaskToEnd(task)
    }

    // -------------------- Download --------------------

    /**
     * @param destPath full path including filename (e.g. /storage/.../DJI_0001.JPG)
     */
    fun downloadFile(
        droneFile: DroneMediaFile,
        destPath: String,
        progressCallback: (percent: Int) -> Unit,
        completionCallback: (ok: Boolean, error: String?) -> Unit
    ) {
        val mediaFile = droneFile.mediaFile

        val outFile = File(destPath)
        val destDir = outFile.parentFile
        if (destDir == null) {
            completionCallback(false, "Invalid destination path")
            return
        }
        if (!destDir.exists()) destDir.mkdirs()

        val nameWithoutExt: String? = outFile.nameWithoutExtension.ifBlank { null }

        mediaFile.fetchFileData(
            destDir,
            nameWithoutExt,
            object : DownloadListener<String> {

                override fun onStart() {}

                override fun onRateUpdate(total: Long, current: Long, persize: Long) {}

                override fun onRealtimeDataUpdate(data: ByteArray?, position: Long, isLastPack: Boolean) {}

                override fun onProgress(total: Long, current: Long) {
                    val percent = if (total > 0) ((current * 100) / total).toInt() else 0
                    progressCallback(percent)
                }

                override fun onSuccess(data: String?) {
                    completionCallback(true, null)
                }

                override fun onFailure(error: DJIError?) {
                    val msg = error?.description ?: error?.toString() ?: "Download failed"
                    completionCallback(false, msg)
                }
            }
        )
    }

    // -------------------- Cleanup --------------------

    fun cleanup() {
        try {
            stopScheduler()
        } catch (_: Exception) {}

        if (isInMediaMode) {
            exitMediaMode { _, _ -> }
        }

        mediaManager = null
        scheduler = null
        isInMediaMode = false
    }
}
