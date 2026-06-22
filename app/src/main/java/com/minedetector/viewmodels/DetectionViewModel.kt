package com.minedetector.viewmodels

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.minedetector.ml.Detection
import com.minedetector.data.models.DetectionStats

class DetectionViewModel : ViewModel() {

    private val _detections = MutableLiveData<List<Detection>>(emptyList())
    val detections: LiveData<List<Detection>> = _detections

    private val _detectionEnabled = MutableLiveData<Boolean>(false)
    val detectionEnabled: LiveData<Boolean> = _detectionEnabled

    private val _selectedMediaForDetection = MutableLiveData<Uri?>()
    val selectedMediaForDetection: LiveData<Uri?> = _selectedMediaForDetection

    private val _detectionStats = MutableLiveData<DetectionStats>()
    val detectionStats: LiveData<DetectionStats> = _detectionStats

    private val _processingProgress = MutableLiveData<Int>(0)
    val processingProgress: LiveData<Int> = _processingProgress

    fun updateDetections(newDetections: List<Detection>) {
        _detections.value = newDetections
        updateStats(newDetections)
    }

    fun clearDetections() {
        _detections.value = emptyList()
        _detectionStats.value = DetectionStats()
    }

    fun setDetectionEnabled(enabled: Boolean) {
        _detectionEnabled.postValue(enabled)
    }

    fun selectMediaForDetection(uri: Uri) {
        _selectedMediaForDetection.postValue(uri)
    }

    fun updateProgress(progress: Int) {
        _processingProgress.postValue(progress)
    }

    private fun updateStats(detections: List<Detection>) {
        if (detections.isEmpty()) {
            _detectionStats.value = DetectionStats()
            return
        }

        val countsByClass = mutableMapOf<Int, Int>()
        var totalConfidence = 0f

        detections.forEach { detection ->
            countsByClass[detection.classId] = (countsByClass[detection.classId] ?: 0) + 1
            totalConfidence += detection.confidence
        }

        val avgConfidence = (totalConfidence / detections.size) * 100f

        _detectionStats.value = DetectionStats(
            totalDetections = detections.size,
            countsByClass = countsByClass,
            avgConfidence = avgConfidence
        )
    }
}
