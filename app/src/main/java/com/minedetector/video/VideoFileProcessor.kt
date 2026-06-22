package com.minedetector.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoFileProcessor(
    private val context: Context
) {

    companion object {
        private const val TAG = "VideoFileProcessor"
    }

    suspend fun processVideo(
        videoUri: Uri,
        onFrameProcessed: (Bitmap, Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, videoUri)

            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f

            val totalFrames = ((duration / 1000.0) * frameRate).toInt()
            val frameInterval = 1000000L / frameRate.toLong()

            Log.d(TAG, "Video duration: $duration ms, Frame rate: $frameRate fps, Total frames: $totalFrames")

            for (frameNumber in 0 until totalFrames) {
                val timeUs = frameNumber * frameInterval
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

                if (bitmap != null) {
                    onFrameProcessed(bitmap, frameNumber, totalFrames)
                } else {
                    Log.w(TAG, "Failed to extract frame $frameNumber")
                }
            }

            Log.d(TAG, "Video processing completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing video", e)
            throw e
        } finally {
            retriever.release()
        }
    }
}
