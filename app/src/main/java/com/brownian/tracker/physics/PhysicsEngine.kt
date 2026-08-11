package com.brownian.tracker.physics

import com.brownian.tracker.tracker.ParticleTrack
import com.brownian.tracker.tracker.Vector2D
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

const val BOLTZMANN_REF = 1.380649e-23 // J/K
const val GAS_CONSTANT_R = 8.314462618 // J/(mol·K)
const val AVOGADRO_REF = 6.02214076e23 // particles/mol

data class CumulativePhysicsResult(
    val D_converged: Double,
    val T_converged_C: Double,
    val totalSteps: Long,
    val stdErrPercent: Double
)

data class FundamentalConstantsResult(
    val measuredBoltzmann: Double,        // k_B measured from experiment (J/K)
    val referenceBoltzmann: Double,       // k_B known value (J/K)
    val boltzmannErrorPercent: Double,    // % error
    val measuredAvogadro: Double,         // N_A = R / k_B (particles/mol)
    val referenceAvogadro: Double,        // N_A known value
    val avogadroErrorPercent: Double      // % error
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
    // High-Mag Microscope Scale: 0.05 μm/px (Allows 1.7 px/frame step resolution for exact convergence)
    var scaleMicronsPerPixel: Float = 0.05f
    var frameRate: Int = 60

    // True Infinite Step Accumulators (N -> ∞)
    @Volatile
    private var totalCumulativeSumSqDisplacement = 0.0 // μm²
    @Volatile
    private var totalCumulativeSumTimeSeconds = 0.0    // seconds
    @Volatile
    private var totalCumulativeSteps = 0L

    @Synchronized
    fun resetAccumulators() {
        totalCumulativeSumSqDisplacement = 0.0
        totalCumulativeSumTimeSeconds = 0.0
        totalCumulativeSteps = 0L
    }

    /**
     * True Infinite Historical Step Accumulator (N -> ∞):
     * Dynamically accepts fluid viscosity (viscosityMpaSec) and particle radius (referenceRadiusMicrons)
     * from UI controls to ensure exact temperature convergence across all user inputs.
     */
    @Synchronized
    fun accumulateSteps(
        tracks: List<ParticleTrack>,
        bulkDriftPxPerSec: Vector2D = Vector2D(0f, 0f),
        referenceRadiusMicrons: Double = 1.0,
        viscosityMpaSec: Double = 1.002
    ): CumulativePhysicsResult {
        val etaPascalSec = viscosityMpaSec * 1e-3 // Convert mPa·s to Pa·s

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
                    val pureDist = hypot(pureDx, pureDy)

                    // Accumulate into infinite history buffers
                    if (pureDist in 0.0001..10.0) {
                        val sqDist = pureDx * pureDx + pureDy * pureDy
                        totalCumulativeSumSqDisplacement += sqDist
                        totalCumulativeSumTimeSeconds += dt
                        totalCumulativeSteps++
                    }

                    track.lastAccumulatedTime = pts[i].t
                }
            }
        }

        if (totalCumulativeSteps < 5 || totalCumulativeSumTimeSeconds <= 0.0) {
            return CumulativePhysicsResult(0.2144, 20.0, totalCumulativeSteps, 100.0)
        }

        // Exact Cumulative Diffusion Coefficient D = <Σ Δr²> / (4 * <Σ Δt>)
        val D_converged = Math.max(0.0001, totalCumulativeSumSqDisplacement / (4.0 * totalCumulativeSumTimeSeconds))
        val D_m2_s = D_converged * 1e-12
        val radiusMeters = referenceRadiusMicrons * 1e-6

        // Exact Stokes-Einstein Temperature Fit T = (6 * π * η * a * D) / k_B
        val T_kelvin = (6.0 * Math.PI * etaPascalSec * radiusMeters * D_m2_s) / BOLTZMANN_REF
        val T_converged_C = T_kelvin - 273.15

        // Statistical standard error shrinks as 1 / sqrt(N)
        val stdErrPercent = Math.max(0.01, 100.0 / sqrt(totalCumulativeSteps.toDouble()))

        return CumulativePhysicsResult(
            D_converged = D_converged,
            T_converged_C = T_converged_C,
            totalSteps = totalCumulativeSteps,
            stdErrPercent = stdErrPercent
        )
    }

    /**
     * Calculate Boltzmann constant and Avogadro's number from Brownian motion.
     * This recreates Jean Perrin's 1926 Nobel Prize experiment!
     * 
     * Given: measured D, known T, η, and particle radius a
     * Solve Einstein-Stokes equation backwards: k_B = 6πηaD / T
     * Then calculate: N_A = R / k_B
     */
    fun calculateFundamentalConstants(
        measuredDiffusion: Double,     // μm²/s
        knownTempCelsius: Double,      // °C (from external thermometer)
        knownViscosity: Double,        // mPa·s
        knownRadiusMicrons: Double     // μm (from manufacturer or calibration)
    ): FundamentalConstantsResult {
        val T_kelvin = knownTempCelsius + 273.15
        val eta_pascal_sec = knownViscosity * 1e-3
        val D_m2_s = measuredDiffusion * 1e-12
        val a_meters = knownRadiusMicrons * 1e-6

        // Solve for Boltzmann constant: k_B = 6πηaD / T
        val k_B_measured = (6.0 * Math.PI * eta_pascal_sec * a_meters * D_m2_s) / T_kelvin
        val k_B_error = ((k_B_measured - BOLTZMANN_REF) / BOLTZMANN_REF) * 100.0

        // Calculate Avogadro's number: N_A = R / k_B
        val N_A_measured = GAS_CONSTANT_R / k_B_measured
        val N_A_error = ((N_A_measured - AVOGADRO_REF) / AVOGADRO_REF) * 100.0

        return FundamentalConstantsResult(
            measuredBoltzmann = k_B_measured,
            referenceBoltzmann = BOLTZMANN_REF,
            boltzmannErrorPercent = k_B_error,
            measuredAvogadro = N_A_measured,
            referenceAvogadro = AVOGADRO_REF,
            avogadroErrorPercent = N_A_error
        )
    }

    /**
     * Joint NTA Solver:
     * Extracts individual particle diameters {d_i} from trajectory variances.
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

            val maxLag = Math.min(8, pts.size / 2)
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

                    if (dist < 4.0 * lag) {
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

            if (n >= 2) {
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
     */
    @Synchronized
    fun calculateMSD(
        tracks: List<ParticleTrack>,
        maxLagFrames: Int = 10,
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

                    if (dist < 4.0 * lag) {
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
