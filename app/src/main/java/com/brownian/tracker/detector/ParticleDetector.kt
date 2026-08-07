package com.brownian.tracker.detector

import java.nio.ByteBuffer

data class DetectedParticle(
    val x: Float,
    val y: Float,
    val area: Int,
    val radius: Float
)

class ParticleDetector {
    var threshold: Int = 128
    var invert: Boolean = true
    var minArea: Int = 4
    var maxArea: Int = 8000
    var blurRadius: Int = 1

    fun detectParticles(yBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int): List<DetectedParticle> {
        val totalPixels = width * height
        val grayBuffer = FloatArray(totalPixels)

        var minLum = 255f
        var maxLum = 0f

        yBuffer.rewind()

        // 1. Direct Y-plane extraction with rowStride handling
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            val dstOffset = y * width
            for (x in 0 until width) {
                val lum = (yBuffer.get(rowOffset + x).toInt() and 0xFF).toFloat()
                grayBuffer[dstOffset + x] = lum
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }

        // 2. Adaptive Threshold Calculation (Handles camera auto-exposure changes)
        val range = Math.max(10f, maxLum - minLum)
        val ratio = threshold.toFloat() / 255f
        val effectiveThresh = minLum + ratio * range

        // 3. Binary Thresholding
        val binaryMap = ByteArray(totalPixels)
        for (i in 0 until totalPixels) {
            val valLum = grayBuffer[i]
            if (invert) {
                binaryMap[i] = if (valLum < effectiveThresh) 1 else 0
            } else {
                binaryMap[i] = if (valLum > effectiveThresh) 1 else 0
            }
        }

        // 4. Fast BFS Centroid Search
        val visited = ByteArray(totalPixels)
        val queueX = IntArray(totalPixels)
        val queueY = IntArray(totalPixels)
        val particles = ArrayList<DetectedParticle>()

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                if (binaryMap[idx].toInt() == 1 && visited[idx].toInt() == 0) {
                    var head = 0
                    var tail = 0

                    queueX[tail] = x
                    queueY[tail] = y
                    tail++
                    visited[idx] = 1

                    var sumX = 0L
                    var sumY = 0L
                    var pixelCount = 0

                    while (head < tail) {
                        val cx = queueX[head]
                        val cy = queueY[head]
                        head++

                        sumX += cx
                        sumY += cy
                        pixelCount++

                        val neighbors = arrayOf(
                            intArrayOf(cx + 1, cy), intArrayOf(cx - 1, cy),
                            intArrayOf(cx, cy + 1), intArrayOf(cx, cy - 1)
                        )

                        for (n in 0 until 4) {
                            val nx = neighbors[n][0]
                            val ny = neighbors[n][1]
                            if (nx in 0 until width && ny in 0 until height) {
                                val nIdx = ny * width + nx
                                if (binaryMap[nIdx].toInt() == 1 && visited[nIdx].toInt() == 0) {
                                    visited[nIdx] = 1
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                        }
                    }

                    if (pixelCount in minArea..maxArea) {
                        particles.add(
                            DetectedParticle(
                                x = sumX.toFloat() / pixelCount,
                                y = sumY.toFloat() / pixelCount,
                                area = pixelCount,
                                radius = Math.sqrt(pixelCount.toDouble() / Math.PI).toFloat()
                            )
                        )
                    }
                }
            }
        }

        return particles
    }
}
