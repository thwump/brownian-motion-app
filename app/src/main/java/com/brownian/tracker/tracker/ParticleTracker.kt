package com.brownian.tracker.tracker

import com.brownian.tracker.detector.DetectedParticle
import kotlin.math.hypot

data class TrackPoint(val x: Float, val y: Float, val t: Double)

data class ParticleTrack(
    val id: Int,
    val points: MutableList<TrackPoint> = mutableListOf(),
    var lastAccumulatedTime: Double = 0.0
)

data class Vector2D(val vx: Float, val vy: Float)

class ParticleTracker {
    private var nextId = 1
    private val activeTracks = mutableListOf<ParticleTrack>()
    private val completedTracks = mutableListOf<ParticleTrack>()
    
    var maxDistancePx: Float = 40f
    var maxUnseenFrames: Int = 5
    var maxTrackPoints: Int = 60
    var maxCompletedTracks: Int = 15
    var enableDriftCorrection: Boolean = true

    var bulkDriftVector: Vector2D = Vector2D(0f, 0f)
        private set

    fun update(detections: List<DetectedParticle>, nowSec: Double): List<ParticleTrack> {
        val assignedDetections = BooleanArray(detections.size)
        val assignedTracks = BooleanArray(activeTracks.size)
        var driftSumX = 0f
        var driftSumY = 0f
        var driftCount = 0

        // Greedy Nearest Neighbor Matching
        for (i in activeTracks.indices) {
            val track = activeTracks[i]
            val lastPt = track.points.lastOrNull() ?: continue

            var minDist = Float.MAX_VALUE
            var bestIdx = -1

            for (j in detections.indices) {
                if (assignedDetections[j]) continue
                val det = detections[j]
                val dist = hypot(det.x - lastPt.x, det.y - lastPt.y)

                if (dist < maxDistancePx && dist < minDist) {
                    minDist = dist
                    bestIdx = j
                }
            }

            if (bestIdx != -1) {
                val det = detections[bestIdx]
                val dx = det.x - lastPt.x
                val dy = det.y - lastPt.y

                driftSumX += dx
                driftSumY += dy
                driftCount++

                track.points.add(TrackPoint(det.x, det.y, nowSec))
                if (track.points.size > maxTrackPoints) {
                    track.points.removeAt(0)
                }

                assignedDetections[bestIdx] = true
                assignedTracks[i] = true
            }
        }

        // Calculate Bulk Fluid Drift Vector
        if (driftCount > 0 && enableDriftCorrection) {
            bulkDriftVector = Vector2D(driftSumX / driftCount, driftSumY / driftCount)
        } else {
            bulkDriftVector = Vector2D(0f, 0f)
        }

        // Initialize new tracks for unassigned detections
        for (j in detections.indices) {
            if (!assignedDetections[j]) {
                val det = detections[j]
                val newTrack = ParticleTrack(id = nextId++)
                newTrack.points.add(TrackPoint(det.x, det.y, nowSec))
                activeTracks.add(newTrack)
            }
        }

        // Prune old inactive tracks
        val iterator = activeTracks.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            val lastPt = track.points.lastOrNull()
            if (lastPt != null && (nowSec - lastPt.t) > 0.5) {
                if (track.points.size >= 10) {
                    completedTracks.add(track)
                    if (completedTracks.size > maxCompletedTracks) {
                        completedTracks.removeAt(0)
                    }
                }
                iterator.remove()
            }
        }

        return activeTracks
    }

    fun getAllTracks(): List<ParticleTrack> {
        val result = mutableListOf<ParticleTrack>()
        result.addAll(activeTracks)
        result.addAll(completedTracks)
        return result
    }

    fun reset() {
        activeTracks.clear()
        completedTracks.clear()
        nextId = 1
        bulkDriftVector = Vector2D(0f, 0f)
    }
}
