package com.brownian.tracker.physics

import com.brownian.tracker.tracker.ParticleTrack
import com.brownian.tracker.tracker.Vector2D
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

const val BOLTZMANN_REF = 1.380649e-23 // J/K

data class CumulativePhysicsResult(
    val D_converged: Double,
    val T_converged_C: Double,
    val totalSteps: Long,
    val stdErrPercent: Double
)

data class MsdPoint(val dt: Double, val msd: Double)
data class MsdResult(
    val msdPoints: List<MsdPoint>,
    val slope: Double,
    val rSquared: Double,
    val D: Double
)

data class ParticleSizeResult(
    val trackId: Int,
    val D: Double,
    val diameter: Double,
    val pointCount: Int
)

data class SizeHistogram(
    val bins: List<String>,
    val counts: List<Int>,
    val totalCount: Int
)

data class PolydisperseSizingResult(
    val individualResults: List<ParticleSizeResult>,
    val meanDiameter: Double,
    val medianDiameter: Double,
    val stdDev: Double,
    val pdi: Double,
    val sizeHistogram: SizeHistogram
)

class PhysicsEngine {
    // Pixel 9 + 200x Lens + 10x Digital Zoom scale: 0.3125 μm/px (400 μm FOV across 1280 px canvas)
    var scaleMicronsPerPixel: Float = 0.3125f
    var frameRate: Int = 60

    @Volatile
    private var cumulativeStepCount = 0L

    @Synchronized
    fun resetAccumulators() {
        cumulativeStepCount = 0L
    }

    /**
     * Extended Lag-Time Step Evaluation (up to 30 lag frames = 0.50 seconds):
     * Resolves long-time thermal motion (2.1 pixels of displacement) well above sub-pixel spatial detection noise!
     */
    @Synchronized
    fun accumulateSteps(
        tracks: List<ParticleTrack>,
        bulkDriftPxPerSec: Vector2D = Vector2D(0f, 0f),
        referenceRadiusMicrons: Double = 1.0
    ): CumulativePhysicsResult {
        val etaPascalSec = 1.002e-3 // Water viscosity at 20°C (1.002 mPa·s)

        // Evaluate lag steps up to 30 frames (0.50 seconds)
        val msdResult = calculateMSD(tracks, maxLagFrames = 30, bulkDriftPxPerSec = bulkDriftPxPerSec)
        cumulativeStepCount += tracks.sumOf { synchronized(it) { it.points.size } }

        if (msdResult.msdPoints.size < 3 || msdResult.D <= 0.0) {
            return CumulativePhysicsResult(0.0, 20.0, cumulativeStepCount, 100.0)
        }

        val D_converged = msdResult.D
        val D_m2_s = D_converged * 1e-12
        val radiusMeters = referenceRadiusMicrons * 1e-6

        // Stokes-Einstein Temperature Fit T = (6 * π * η * a * D) / k_B
        val T_kelvin = (6.0 * Math.PI * etaPascalSec * radiusMeters * D_m2_s) / BOLTZMANN_REF
        val T_converged_C = T_kelvin - 273.15

        val stdErrPercent = Math.max(0.1, 100.0 / sqrt(Math.max(1L, cumulativeStepCount) * 0.4))

        return CumulativePhysicsResult(
            D_converged = D_converged,
            T_converged_C = T_converged_C,
            totalSteps = cumulativeStepCount,
            stdErrPercent = stdErrPercent
        )
    }

    /**
     * Joint NTA Solver:
     * Extracts individual particle diameters {d_i} from trajectory variances
     * across extended lag times (up to 15 frames = 0.25 seconds).
     */
    @Synchronized
    fun calculatePolydisperseSizing(
        tracks: List<ParticleTrack>,
        tempCelsius: Double = 20.0,
        viscosityMpaSec: Double = 1.002,
        bulkDriftPxPerSec: Vector2D = Vector2D(0f, 0f)
    ): PolydisperseSizingResult {
        if (tracks.isEmpty()) {
            return PolydisperseSizingResult(emptyList(), 0.0, 0.0, 0.0, 0.0, SizeHistogram(emptyList(), emptyList(), 0))
        }

        val T_kelvin = tempCelsius + 273.15
        val eta_pascal_sec = viscosityMpaSec * 1e-3

        val individualResults = mutableListOf<ParticleSizeResult>()
        val diameters = mutableListOf<Double>()

        for (track in tracks) {
            val pts = synchronized(track) { ArrayList(track.points) }
            if (pts.size < 12) continue

            val maxLag = Math.min(15, pts.size / 2)
            val lagSums = DoubleArray(maxLag + 1)
            val lagCounts = IntArray(maxLag + 1)

            for (lag in 1..maxLag) {
                for (i in 0 until (pts.size - lag)) {
                    val dt = Math.max(0.005, pts[i + lag].t - pts[i].t)
                    val rawDx = (pts[i + lag].x - pts[i].x) * scaleMicronsPerPixel
                    val rawDy = (pts[i + lag].y - pts[i].y) * scaleMicronsPerPixel

                    val driftDx = bulkDriftPxPerSec.vx * scaleMicronsPerPixel * dt.toFloat()
                    val driftDy = bulkDriftPxPerSec.vy * scaleMicronsPerPixel * dt.toFloat()

                    val pureDx = rawDx - driftDx
                    val pureDy = rawDy - driftDy
                    val dist = hypot(pureDx.toDouble(), pureDy.toDouble())

                    if (dist < 3.0 * lag) {
                        lagSums[lag] = lagSums[lag] + (pureDx * pureDx + pureDy * pureDy)
                        lagCounts[lag] = lagCounts[lag] + 1
                    }
                }
            }

            var sumXY = 0.0
            var sumX2 = 0.0
            var sumY = 0.0
            var sumX = 0.0
            var n = 0
            val dtUnit = 1.0 / Math.max(15, frameRate)

            for (lag in 1..maxLag) {
                if (lagCounts[lag] > 0) {
                    val tVal = lag * dtUnit
                    val msdVal = lagSums[lag] / lagCounts[lag]
                    sumXY += tVal * msdVal
                    sumX2 += tVal * tVal
                    sumX += tVal
                    sumY += msdVal
                    n++
                }
            }

            if (n >= 3) {
                val denom = n * sumX2 - sumX * sumX
                val slope = if (Math.abs(denom) > 1e-6) (n * sumXY - sumX * sumY) / denom else 0.0
                val D_i_microns_sq_s = Math.max(0.001, slope / 4.0)
                val D_i_m2_s = D_i_microns_sq_s * 1e-12

                val a_i_meters = (BOLTZMANN_REF * T_kelvin) / (6.0 * Math.PI * eta_pascal_sec * D_i_m2_s)
                val d_i_microns = a_i_meters * 2.0 * 1e6

                if (d_i_microns in 0.1..40.0) {
                    individualResults.add(ParticleSizeResult(track.id, D_i_microns_sq_s, d_i_microns, pts.size))
                    diameters.add(d_i_microns)
                }
            }
        }

        if (diameters.isEmpty()) {
            return PolydisperseSizingResult(emptyList(), 0.0, 0.0, 0.0, 0.0, SizeHistogram(emptyList(), emptyList(), 0))
        }

        diameters.sort()
        val meanD = diameters.average()
        val medianD = diameters[diameters.size / 2]

        val variance = diameters.map { (it - meanD).pow(2) }.average()
        val stdDev = sqrt(variance)
        val pdi = (stdDev / meanD).pow(2)

        val minBin = 0.2
        val maxBin = Math.max(6.0, Math.min(25.0, Math.ceil(diameters.last())))
        val numBins = 15
        val binWidth = (maxBin - minBin) / numBins
        val bins = mutableListOf<String>()
        val counts = IntArray(numBins)

        for (b in 0 until numBins) {
            bins.add(String.format("%.2f", (b + 0.5) * binWidth + minBin))
        }

        for (d in diameters) {
            val idx = Math.min(numBins - 1, Math.max(0, ((d - minBin) / binWidth).toInt()))
            counts[idx] = counts[idx] + 1
        }

        return PolydisperseSizingResult(
            individualResults = individualResults,
            meanDiameter = meanD,
            medianDiameter = medianD,
            stdDev = stdDev,
            pdi = pdi,
            sizeHistogram = SizeHistogram(bins, counts.toList(), diameters.size)
        )
    }

    /**
     * Calculates Global Ensemble MSD across all trajectories with Directional Drift Subtraction
     * across extended lag times (up to maxLagFrames = 30)
     */
    @Synchronized
    fun calculateMSD(
        tracks: List<ParticleTrack>,
        maxLagFrames: Int = 30,
        bulkDriftPxPerSec: Vector2D = Vector2D(0f, 0f)
    ): MsdResult {
        if (tracks.isEmpty()) {
            return MsdResult(emptyList(), 0.0, 0.0, 0.0)
        }

        val lagSums = DoubleArray(maxLagFrames + 1)
        val lagCounts = IntArray(maxLagFrames + 1)

        for (track in tracks) {
            val pts = synchronized(track) { ArrayList(track.points) }
            val len = pts.size
            if (len < 2) continue

            for (lag in 1..Math.min(maxLagFrames, len - 1)) {
                for (i in 0 until (len - lag)) {
                    val dt = Math.max(0.005, pts[i + lag].t - pts[i].t)
                    val rawDx = (pts[i + lag].x - pts[i].x) * scaleMicronsPerPixel
                    val rawDy = (pts[i + lag].y - pts[i].y) * scaleMicronsPerPixel

                    val driftDx = bulkDriftPxPerSec.vx * scaleMicronsPerPixel * dt.toFloat()
                    val driftDy = bulkDriftPxPerSec.vy * scaleMicronsPerPixel * dt.toFloat()

                    val pureDx = rawDx - driftDx
                    val pureDy = rawDy - driftDy
                    val dist = hypot(pureDx.toDouble(), pureDy.toDouble())

                    if (dist < 3.0 * lag) {
                        val sqDist = pureDx * pureDx + pureDy * pureDy
                        lagSums[lag] = lagSums[lag] + sqDist
                        lagCounts[lag] = lagCounts[lag] + 1
                    }
                }
            }
        }

        val msdPoints = mutableListOf<MsdPoint>()
        val xVals = mutableListOf<Double>()
        val yVals = mutableListOf<Double>()
        val dtUnit = 1.0 / Math.max(15, frameRate)

        for (lag in 1..maxLagFrames) {
            if (lagCounts[lag] > 0) {
                val deltaTime = lag * dtUnit
                val msdVal = lagSums[lag] / lagCounts[lag]

                msdPoints.add(MsdPoint(deltaTime, msdVal))
                xVals.add(deltaTime)
                yVals.add(msdVal)
            }
        }

        if (xVals.size < 2) {
            return MsdResult(emptyList(), 0.0, 0.0, 0.0)
        }

        var sumXY = 0.0
        var sumX2 = 0.0
        var sumY = 0.0
        var sumX = 0.0
        val n = xVals.size

        for (i in 0 until n) {
            sumXY += xVals[i] * yVals[i]
            sumX2 += xVals[i] * xVals[i]
            sumX += xVals[i]
            sumY += yVals[i]
        }

        val denom = n * sumX2 - sumX * sumX
        val slope = if (Math.abs(denom) > 1e-6) (n * sumXY - sumX * sumY) / denom else 0.0
        val yMean = sumY / n
        var ssTot = 0.0
        var ssRes = 0.0
        for (i in 0 until n) {
            val yPred = slope * xVals[i]
            ssRes += (yVals[i] - yPred).pow(2)
            ssTot += (yVals[i] - yMean).pow(2)
        }
        val rSquared = if (ssTot > 0) Math.max(0.0, 1.0 - (ssRes / ssTot)) else 0.0
        val D = Math.max(0.0, slope / 4.0)

        return MsdResult(msdPoints, slope, rSquared, D)
    }
}
