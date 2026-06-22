package com.minedetector.dji

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import java.util.concurrent.atomic.AtomicBoolean

class DJIVideoStreamManager(
    private val textureView: TextureView
) {
    companion object { private const val TAG = "DJIVideoStream" }

    private var codecManager: DJICodecManager? = null
    @Volatile
    private var frameCallback: ((Bitmap) -> Unit)? = null
    private val isStarted = AtomicBoolean(false)

    private var videoDataListener: VideoFeeder.VideoDataListener? = null

    @Volatile
    private var reusableBitmap: Bitmap? = null

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "Surface available: ${width}x${height}")
                codecManager = DJICodecManager(textureView.context, surface, width, height)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                codecManager?.onSurfaceSizeChanged(width, height, 0)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stop()
                codecManager?.cleanSurface()
                codecManager = null
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                val cb = frameCallback ?: return
                val bitmap = reusableBitmap?.let {  textureView.getBitmap(it) }
                    ?: textureView.getBitmap()
                if (bitmap != null) {
                    reusableBitmap = bitmap
                    // Make a copy for the consumer so it won't be overwritten next frame
                    val copy = bitmap.copy(bitmap.config, false)
                    if (copy != null) {
                        cb(copy)
                    }
                }
            }
        }
    }

    fun start() {
        if (!isStarted.compareAndSet(false, true)) return

        val feeder = VideoFeeder.getInstance()
        if (feeder == null) {
            Log.w(TAG, "VideoFeeder is null (no product?)")
            isStarted.set(false)
            return
        }

        val primaryFeed = feeder.primaryVideoFeed
        if (primaryFeed == null) {
            Log.w(TAG, "primaryVideoFeed is null — video stream unavailable for this drone")
            isStarted.set(false)
            return
        }

        val listener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }
        videoDataListener = listener
        primaryFeed.addVideoDataListener(listener)

        Log.d(TAG, "Video stream started")
    }

    fun stop() {
        if (!isStarted.compareAndSet(true, false)) return

        try {
            val feeder = VideoFeeder.getInstance()
            val feed = feeder?.primaryVideoFeed
            val listener = videoDataListener
            if (feed != null && listener != null) {
                feed.removeVideoDataListener(listener)
            }
        } catch (_: Throwable) { }
        videoDataListener = null

        Log.d(TAG, "Video stream stopped")
    }

    fun setFrameCallback(callback: ((Bitmap) -> Unit)?) {
        frameCallback = callback
    }

    fun cleanup() {
        stop()
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
