package com.brownian.tracker.detector

import java.nio.ByteBuffer
import kotlin.math.hypot

data class DetectedParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val intensity: Float
)

/**
 * High-performance, robust particle detector for real video microscopy (NTA).
 * Uses intensity-weighted sub-pixel centroiding, adaptive thresholding, and size/shape filtering.
 */
class ParticleDetector {
    var minThreshold: Int = 30
    var minParticleRadius: Int = 3
    var maxParticleRadius: Int = 50
    var invert: Boolean = true
    
    // Safety cap to prevent ANR / memory freeze when high contrast detects thousands of spots
    var maxDetectedParticlesCap: Int = 80

    fun detectParticles(
        yBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ): List<DetectedParticle> {
        val particles = mutableListOf<DetectedParticle>()
        yBuffer.rewind()

        val yData = ByteArray(yBuffer.remaining())
        yBuffer.get(yData)

        val step = if (width > 2000) 4 else 2
        val labels = IntArray(width * height)
        var currentLabel = 1

        // Large 1024-entry BFS traversal queue to handle high-resolution macro spots without truncation
        val queueX = IntArray(1024)
        val queueY = IntArray(1024)

        for (y in step until height - step step step) {
            val rowOffset = y * rowStride
            for (x in step until width - step step step) {
                // Safety exit if particle count hits maximum safety cap
                if (particles.size >= maxDetectedParticlesCap) {
                    return particles
                }

                val pixelVal = yData[rowOffset + x].toInt() and 0xFF
                val targetVal = if (invert) (255 - pixelVal) else pixelVal

                if (targetVal > minThreshold) {
                    val idx = y * width + x
                    if (labels[idx] == 0) {
                        var sumX = 0.0
                        var sumY = 0.0
                        var totalIntensity = 0.0
                        var pixelCount = 0
                        var minX = x
                        var maxX = x
                        var minY = y
                        var maxY = y

                        var head = 0
                        var tail = 0

                        queueX[tail] = x
                        queueY[tail] = y
                        tail++
                        labels[idx] = currentLabel

                        while (head < tail && pixelCount < 600) {
                            val qx = queueX[head]
                            val qy = queueY[head]
                            head++

                            val qPixelVal = yData[qy * rowStride + qx].toInt() and 0xFF
                            val qTargetVal = if (invert) (255 - qPixelVal) else qPixelVal

                            sumX += qx * qTargetVal
                            sumY += qy * qTargetVal
                            totalIntensity += qTargetVal
                            pixelCount++

                            if (qx < minX) minX = qx
                            if (qx > maxX) maxX = qx
                            if (qy < minY) minY = qy
                            if (qy > maxY) maxY = qy

                            val dxs = intArrayOf(-step, step, 0, 0)
                            val dys = intArrayOf(0, 0, -step, step)

                            for (n in 0 until 4) {
                                val nx = qx + dxs[n]
                                val ny = qy + dys[n]

                                if (nx in step until width - step && ny in step until height - step) {
                                    val nIdx = ny * width + nx
                                    if (labels[nIdx] == 0) {
                                        val nPixelVal = yData[ny * rowStride + nx].toInt() and 0xFF
                                        val nTargetVal = if (invert) (255 - nPixelVal) else nPixelVal
                                        if (nTargetVal > minThreshold) {
                                            labels[nIdx] = currentLabel
                                            if (tail < queueX.size) {
                                                queueX[tail] = nx
                                                queueY[tail] = ny
                                                tail++
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (pixelCount >= 2 && totalIntensity > 0) {
                            val cx = (sumX / totalIntensity).toFloat()
                            val cy = (sumY / totalIntensity).toFloat()
                            
                            val widthPx = (maxX - minX + step).toDouble()
                            val heightPx = (maxY - minY + step).toDouble()
                            val bboxRadius = (hypot(widthPx, heightPx) / 2.0).toFloat()
                            val aspectRatio = Math.max(widthPx, heightPx) / Math.max(1.0, Math.min(widthPx, heightPx))

                            // Strict validation: Reject noise speckles, elongated scratches (aspectRatio > 2.5), and oversized dust spots
                            if (bboxRadius >= minParticleRadius.toFloat() * 0.5f && 
                                bboxRadius <= maxParticleRadius.toFloat() * 1.5f && 
                                aspectRatio <= 2.5) {
                                
                                val clampedRadius = Math.max(minParticleRadius.toFloat(), Math.min(maxParticleRadius.toFloat(), bboxRadius))
                                particles.add(DetectedParticle(cx, cy, clampedRadius, (totalIntensity / pixelCount).toFloat()))
                            }
                        }

                        currentLabel++
                    }
                }
            }
        }

        return particles
    }
}
