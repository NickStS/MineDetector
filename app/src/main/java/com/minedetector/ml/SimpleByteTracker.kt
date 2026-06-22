package com.minedetector.ml

import android.util.Log
import com.minedetector.ml.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * Простой ByteTrack tracker для отслеживания объектов между кадрами
 * Основан на алгоритме ByteTrack: https://github.com/ifzhang/ByteTrack
 */
class SimpleByteTracker(
    private val trackHighThresh: Float = 0.5f,
    private val trackLowThresh: Float = 0.1f,
    private val trackBuffer: Int = 30,
    private val matchThresh: Float = 0.8f
) {
    companion object {
        private const val TAG = "SimpleByteTracker"
    }

    private val tracks = java.util.Collections.synchronizedList(mutableListOf<Track>())
    private var nextId = 1

    data class Track(
        val id: Int,
        var detection: Detection,
        var age: Int = 0,
        var hits: Int = 1,
        var timeSinceUpdate: Int = 0
    ) {
        val isActive: Boolean
            get() = timeSinceUpdate == 0
    }


    fun update(detections: List<Detection>): List<Detection> {
        tracks.forEach {
            it.age++
            it.timeSinceUpdate++
        }

        val highDetections = detections.filter { it.confidence >= trackHighThresh }
        val lowDetections = detections.filter {
            it.confidence >= trackLowThresh && it.confidence < trackHighThresh
        }

        val activeTracks = tracks.filter { it.isActive }
        val (matchedTracks, unmatchedTracks, unmatchedHighDets) =
            matchDetectionsToTracks(highDetections, activeTracks)

        matchedTracks.forEach { (track, detection) ->
            track.detection = detection.copy()
            track.hits++
            track.timeSinceUpdate = 0
        }

        val lostTracks = tracks.filter {
            !it.isActive && it.timeSinceUpdate < trackBuffer
        }
        val remainingDetections = unmatchedHighDets + lowDetections
        val (matchedLost, unmatchedLostTracks, unmatchedRemainDets) =
            matchDetectionsToTracks(remainingDetections, lostTracks)

        matchedLost.forEach { (track, detection) ->
            track.detection = detection.copy()
            track.hits++
            track.timeSinceUpdate = 0
        }

        val matchedInSecondPass = matchedLost.map { it.second }.toSet()
        unmatchedHighDets
            .filter { it !in matchedInSecondPass && it.confidence >= trackHighThresh }
            .forEach { detection ->
                tracks.add(Track(nextId++, detection.copy()))
            }

        tracks.removeAll { it.timeSinceUpdate >= trackBuffer }

        return tracks
            .filter { it.isActive && it.hits >= 1 }
            .map { track ->
                track.detection.copy(trackId = track.id)
            }
    }

    /**
     * Match detections to tracks using IoU
     */
    private fun matchDetectionsToTracks(
        detections: List<Detection>,
        tracks: List<Track>
    ): Triple<List<Pair<Track, Detection>>, List<Track>, List<Detection>> {
        if (detections.isEmpty() || tracks.isEmpty()) {
            return Triple(emptyList(), tracks, detections)
        }

        // Calculate IoU matrix
        val iouMatrix = Array(detections.size) { d ->
            FloatArray(tracks.size) { t ->
                calculateIoU(detections[d], tracks[t].detection)
            }
        }

        // Simple greedy matching (in production use Hungarian algorithm)
        val matched = mutableListOf<Pair<Track, Detection>>()
        val matchedDetIndices = mutableSetOf<Int>()
        val matchedTrackIndices = mutableSetOf<Int>()

        // Sort by IoU descending and match greedily
        val pairs = mutableListOf<Triple<Int, Int, Float>>()
        for (d in detections.indices) {
            for (t in tracks.indices) {
                if (iouMatrix[d][t] >= matchThresh) {
                    pairs.add(Triple(d, t, iouMatrix[d][t]))
                }
            }
        }
        pairs.sortByDescending { it.third }

        for ((detIdx, trackIdx, iou) in pairs) {
            if (detIdx !in matchedDetIndices && trackIdx !in matchedTrackIndices) {
                matched.add(Pair(tracks[trackIdx], detections[detIdx]))
                matchedDetIndices.add(detIdx)
                matchedTrackIndices.add(trackIdx)
            }
        }

        val unmatchedTracks = tracks.filterIndexed { idx, _ -> idx !in matchedTrackIndices }
        val unmatchedDets = detections.filterIndexed { idx, _ -> idx !in matchedDetIndices }

        return Triple(matched, unmatchedTracks, unmatchedDets)
    }

    /**
     * Calculate Intersection over Union
     */
    private fun calculateIoU(det1: Detection, det2: Detection): Float {
        val x1 = max(det1.box.left, det2.box.left)
        val y1 = max(det1.box.top, det2.box.top)
        val x2 = min(det1.box.right, det2.box.right)
        val y2 = min(det1.box.bottom, det2.box.bottom)

        if (x2 <= x1 || y2 <= y1) return 0f

        val intersection = (x2 - x1) * (y2 - y1)
        val area1 = det1.box.width() * det1.box.height()
        val area2 = det2.box.width() * det2.box.height()
        val union = area1 + area2 - intersection

        return intersection / union
    }

    /**
     * Reset tracker
     */
    fun reset() {
        tracks.clear()
        nextId = 1
        Log.d(TAG, "Tracker reset")
    }

    /**
     * Get current track count
     */
    fun getTrackCount(): Int = tracks.filter { it.isActive }.size
}