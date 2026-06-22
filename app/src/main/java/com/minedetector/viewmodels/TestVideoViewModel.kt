package com.minedetector.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.minedetector.data.models.DetectionStats
import com.minedetector.ml.Detection

class TestVideoViewModel : ViewModel() {

    private val _detections = MutableLiveData<List<Detection>>(emptyList())
    val detections: LiveData<List<Detection>> = _detections

    private val _detectionStats = MutableLiveData<DetectionStats>(DetectionStats())
    val detectionStats: LiveData<DetectionStats> = _detectionStats

    private val _processingProgress = MutableLiveData<Int>(0)
    val processingProgress: LiveData<Int> = _processingProgress

    private val _statusMessage = MutableLiveData<String>("Select a video for analysis")
    val statusMessage: LiveData<String> = _statusMessage

    fun updateDetections(newDetections: List<Detection>) {
        _detections.postValue(newDetections)
        calculateStats(newDetections)
    }

    fun updateProgress(progress: Int) {
        _processingProgress.postValue(progress)
    }

    fun updateStatus(message: String) {
        _statusMessage.postValue(message)
    }

    fun clearDetections() {
        _detections.postValue(emptyList())
        _detectionStats.postValue(DetectionStats())
    }

    private fun calculateStats(detections: List<Detection>) {
        val countsByClass = mutableMapOf<Int, Int>()
        detections.forEach { detection ->
            countsByClass[detection.classId] = (countsByClass[detection.classId] ?: 0) + 1
        }

        val avgConfidence = if (detections.isNotEmpty()) {
            detections.map { it.confidence }.average().toFloat() * 100
        } else {
            0f
        }

        _detectionStats.postValue(
            DetectionStats(
                totalDetections = detections.size,
                countsByClass = countsByClass,
                avgConfidence = avgConfidence
            )
        )
    }
}
