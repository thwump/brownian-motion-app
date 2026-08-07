package com.brownian.tracker.detector

import java.nio.ByteBuffer
import kotlin.math.hypot

data class DetectedParticle(
    val x: Float,
    val y: Float,
    val radius: Float,
    val intensity: Float
)

class ParticleDetector {
    var minThreshold: Int = 30
    var minParticleRadius: Int = 3
    var maxParticleRadius: Int = 50
    var invert: Boolean = true

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

        // Strided step dynamically tuned to image width (e.g. step = 4 for 12.5 MP 4080x3072)
        val step = if (width > 2000) 4 else 2
        val labels = IntArray(width * height)
        var currentLabel = 1

        for (y in step until height - step step step) {
            val rowOffset = y * rowStride
            for (x in step until width - step step step) {
                val pixelVal = yData[rowOffset + x].toInt() and 0xFF
                val targetVal = if (invert) (255 - pixelVal) else pixelVal

                if (targetVal > minThreshold) {
                    val idx = y * width + x
                    if (labels[idx] == 0) {
                        // High-speed sub-pixel centroid flood fill
                        var sumX = 0.0
                        var sumY = 0.0
                        var totalIntensity = 0.0
                        var pixelCount = 0
                        var minX = x
                        var maxX = x
                        var minY = y
                        var maxY = y

                        val queueX = IntArray(512)
                        val queueY = IntArray(512)
                        var head = 0
                        var tail = 0

                        queueX[tail] = x
                        queueY[tail] = y
                        tail++
                        labels[idx] = currentLabel

                        while (head < tail && pixelCount < 1000) {
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
                            val bboxRadius = (hypot((maxX - minX).toDouble(), (maxY - minY).toDouble()) / 2.0).toFloat()
                            val radius = Math.max(minParticleRadius.toFloat(), Math.min(maxParticleRadius.toFloat(), bboxRadius))

                            particles.add(DetectedParticle(cx, cy, radius, (totalIntensity / pixelCount).toFloat()))
                        }

                        currentLabel++
                    }
                }
            }
        }

        return particles
    }
}
