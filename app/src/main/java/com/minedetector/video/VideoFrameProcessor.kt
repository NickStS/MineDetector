package com.minedetector.video

import android.graphics.Bitmap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class VideoFrameProcessor {

    // Limit buffer to 4 frames (~32MB max at 1080p) instead of 64 to prevent OOM
    private val frameChannel = Channel<Bitmap>(4)

    fun processFrameStream(): Flow<Bitmap> = flow {
        for (frame in frameChannel) {
            emit(frame)
        }
    }

    suspend fun addFrame(bitmap: Bitmap) {
        frameChannel.send(bitmap)
    }

    fun close() {
        frameChannel.close()
    }
}
