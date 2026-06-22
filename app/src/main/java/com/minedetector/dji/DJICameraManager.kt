package com.minedetector.dji

import android.util.Log
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera

/**
 * Manages camera operations (photo, video, settings).
 * Video feed/codec is handled by DJIVideoStreamManager — no TextureView here.
 */
class DJICameraManager {
    companion object { private const val TAG = "DJICameraManager" }

    private var camera: Camera? = null

    fun setupCamera(camera: Camera) {
        this.camera = camera
        camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO) { error: DJIError? ->
            if (error == null) Log.d(TAG, "Camera mode set to PHOTO")
            else Log.e(TAG, "Failed to set camera mode: ${error.description}")
        }
    }

    fun capturePhoto(callback: (Boolean, String?) -> Unit) {
        val cam = camera
        if (cam == null) {
            callback(false, "Camera not available")
            return
        }
        cam.startShootPhoto(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                if (error == null) {
                    callback(true, "Photo captured")
                } else {
                    callback(false, error.description)
                }
            }
        })
    }

    fun switchToVideoMode() {
        camera?.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO) { error: DJIError? ->
            if (error == null) Log.d(TAG, "Camera mode set to VIDEO")
            else Log.e(TAG, "Failed to set video mode: ${error.description}")
        }
    }

    fun switchToPhotoMode() {
        camera?.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO) { error: DJIError? ->
            if (error == null) Log.d(TAG, "Camera mode set to PHOTO")
            else Log.e(TAG, "Failed to set photo mode: ${error.description}")
        }
    }

    fun startRecording(callback: (Boolean) -> Unit) {
        camera?.startRecordVideo(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                callback(error == null)
            }
        })
    }

    fun stopRecording(callback: (Boolean) -> Unit) {
        camera?.stopRecordVideo(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(error: DJIError?) {
                callback(error == null)
            }
        })
    }

    fun cleanup() {
        camera = null
    }
}
