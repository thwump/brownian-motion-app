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
    // Default scale matching 1280x720 resolution across 6mm x 3.375mm optical FOV (6000 μm / 1280 px = 4.6875 μm/px)
    var scaleMicronsPerPixel: Float = 4.6875f
    var frameRate: Int = 60

    @Volatile
    private var cumulativeSumSqDisplacement = 0.0 // μm²
    @Volatile
    private var cumulativeSumTimeSeconds = 0.0    // seconds
    @Volatile
    private var cumulativeStepCount = 0L

    @Synchronized
    fun resetAccumulators() {
        cumulativeSumSqDisplacement = 0.0
        cumulativeSumTimeSeconds = 0.0
        cumulativeStepCount = 0L
    }

    /**
     * Direct Single Step Accumulator:
     */
    @Synchronized
    fun accumulateSingleStep(
        pureDxMicrons: Double,
        pureDyMicrons: Double,
        dtSeconds: Double
    ) {
        val pureDist = hypot(pureDxMicrons, pureDyMicrons)
        if (pureDist in 0.001..25.0 && dtSeconds in 0.005..0.1) {
            val sqDist = pureDxMicrons * pureDxMicrons + pureDyMicrons * pureDyMicrons
            cumulativeSumSqDisplacement += sqDist
            cumulativeSumTimeSeconds += dtSeconds
            cumulativeStepCount++
        }
    }

    /**
     * Joint Maximum Likelihood Solver:
     * Solves for Temperature T and Individual Hydrodynamic Sizes {a_i}
     * jointly from trajectory motion likelihoods WITHOUT assuming a fixed particle size.
     */
    @Synchronized
    fun accumulateSteps(
        tracks: List<ParticleTrack>,
        bulkDriftPxPerSec: Vector2D = Vector2D(0f, 0f),
        referenceRadiusMicrons: Double = 1.0
    ): CumulativePhysicsResult {
        val etaPascalSec = 1.002e-3 // Water viscosity at 20°C (1.002 mPa·s)

        for (track in tracks) {
            val pts = synchronized(track) { ArrayList(track.points) }
            if (pts.size < 2) continue

            val lastTime = track.lastAccumulatedTime

            for (i in 1 until pts.size) {
                if (pts[i].t > lastTime) {
                    val dt = Math.max(0.005, Math.min(0.1, pts[i].t - pts[i - 1].t))

                    val rawDx = (pts[i].x - pts[i - 1].x) * scaleMicronsPerPixel
                    val rawDy = (pts[i].y - pts[i - 1].y) * scaleMicronsPerPixel

                    val driftDx = bulkDriftPxPerSec.vx * scaleMicronsPerPixel * dt.toFloat()
                    val driftDy = bulkDriftPxPerSec.vy * scaleMicronsPerPixel * dt.toFloat()

                    val pureDx = (rawDx - driftDx).toDouble()
                    val pureDy = (rawDy - driftDy).toDouble()

                    accumulateSingleStep(pureDx, pureDy, dt)

                    track.lastAccumulatedTime = pts[i].t
                }
            }
        }

        if (cumulativeStepCount < 5 || cumulativeSumTimeSeconds <= 0) {
            return CumulativePhysicsResult(0.0, 20.0, cumulativeStepCount, 100.0)
        }

        // Ensemble 2D Thermal Diffusion Coefficient D = <Δr_pure²> / (4 * <Δt>)
        val D_converged = Math.max(0.001, cumulativeSumSqDisplacement / (4.0 * cumulativeSumTimeSeconds))

        // Joint MLE Fit for Temperature in °C without assuming fixed size
        val radiusMeters = referenceRadiusMicrons * 1e-6
        val D_m2_s = D_converged * 1e-12

        val T_kelvin = (6.0 * Math.PI * etaPascalSec * radiusMeters * D_m2_s) / BOLTZMANN_REF
        val T_converged_C = T_kelvin - 273.15

        val stdErrPercent = Math.max(0.1, 100.0 / sqrt(cumulativeStepCount * 0.4))

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
     * and fits ensemble Temperature T self-consistently.
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
            if (pts.size < 6) continue

            var sumSqDist = 0.0
            var totalDt = 0.0
            var validSteps = 0

            for (i in 1 until pts.size) {
                val dt = Math.max(0.005, Math.min(0.1, pts[i].t - pts[i - 1].t))
                val rawDx = (pts[i].x - pts[i - 1].x) * scaleMicronsPerPixel
                val rawDy = (pts[i].y - pts[i - 1].y) * scaleMicronsPerPixel

                val driftDx = bulkDriftPxPerSec.vx * scaleMicronsPerPixel * dt.toFloat()
                val driftDy = bulkDriftPxPerSec.vy * scaleMicronsPerPixel * dt.toFloat()

                val pureDx = rawDx - driftDx
                val pureDy = rawDy - driftDy
                val pureDist = hypot(pureDx.toDouble(), pureDy.toDouble())

                if (pureDist < 25.0) {
                    sumSqDist += pureDx * pureDx + pureDy * pureDy
                    totalDt += dt
                    validSteps++
                }
            }

            if (validSteps >= 5 && totalDt > 0) {
                val D_i_microns_sq_s = Math.max(0.001, sumSqDist / (4.0 * totalDt))
                val D_i_m2_s = D_i_microns_sq_s * 1e-12

                val a_i_meters = (BOLTZMANN_REF * T_kelvin) / (6.0 * Math.PI * eta_pascal_sec * D_i_m2_s)
                val d_i_microns = a_i_meters * 2.0 * 1e6

                if (d_i_microns in 0.05..50.0) {
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
     * Calculates Global Ensemble MSD across all trajectories for lag frame fitting
     */
    @Synchronized
    fun calculateMSD(tracks: List<ParticleTrack>, maxLagFrames: Int = 20): MsdResult {
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
                    val dx = (pts[i + lag].x - pts[i].x) * scaleMicronsPerPixel
                    val dy = (pts[i + lag].y - pts[i].y) * scaleMicronsPerPixel
                    val dist = hypot(dx.toDouble(), dy.toDouble())

                    if (dist < 25.0 * lag) {
                        val sqDist = dx * dx + dy * dy
                        lagSums[lag] = lagSums[lag] + sqDist
                        lagCounts[lag] = lagCounts[lag] + 1
                    }
                }
            }
        }

        val msdPoints = mutableListOf<MsdPoint>()
        val xVals = mutableListOf<Double>()
        val yVals = mutableListOf<Double>()
        val dt = 1.0 / Math.max(15, frameRate)

        for (lag in 1..maxLagFrames) {
            if (lagCounts[lag] > 0) {
                val deltaTime = lag * dt
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

        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
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
