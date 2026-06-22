package com.minedetector.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoDecoder {

    companion object {
        private const val TAG = "VideoDecoder"
        private const val TIMEOUT_US = 10000L
    }

    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var isRunning = false

    fun init(videoPath: String, surface: Surface?): Boolean {
        return try {
            extractor = MediaExtractor().apply {
                setDataSource(videoPath)
            }

            val ext = extractor ?: return false
            var videoTrackIndex = -1
            for (i in 0 until ext.trackCount) {
                val format = ext.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    ext.selectTrack(i)

                    decoder = MediaCodec.createDecoderByType(mime).apply {
                        configure(format, surface, null, 0)
                        start()
                    }
                    break
                }
            }

            videoTrackIndex >= 0
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing decoder", e)
            false
        }
    }

    fun decodeFrame(): Bitmap? {
        if (decoder == null || extractor == null) return null

        try {
            val inputBufferIndex = decoder!!.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder!!.getInputBuffer(inputBufferIndex)
                val sampleSize = extractor!!.readSampleData(inputBuffer!!, 0)

                if (sampleSize < 0) {
                    decoder!!.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                } else {
                    decoder!!.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        sampleSize,
                        extractor!!.sampleTime,
                        0
                    )
                    extractor!!.advance()
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            if (outputBufferIndex >= 0) {
                decoder!!.releaseOutputBuffer(outputBufferIndex, true)
                // Здесь можно извлечь bitmap из Surface если нужно
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding frame", e)
        }

        return null
    }

    fun release() {
        try {
            decoder?.stop()
            decoder?.release()
            extractor?.release()
            decoder = null
            extractor = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        }
    }
}