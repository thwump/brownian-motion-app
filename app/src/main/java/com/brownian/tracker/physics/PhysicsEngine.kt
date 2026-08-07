package com.brownian.tracker.physics

import com.brownian.tracker.tracker.ParticleTrack
import com.brownian.tracker.tracker.Vector2D
import kotlin.math.abs
import kotlin.math.hypot

const val BOLTZMANN_REF = 1.380649e-23 // J/K

data class CumulativePhysicsResult(
    val D_converged: Double,
    val T_converged_C: Double,
    val totalSteps: Long,
    val stdErrPercent: Double
)

class PhysicsEngine {
    var scaleMicronsPerPixel: Float = 0.1f // μm/px
    var frameRate: Int = 60

    private var cumulativeSumSqDisplacement = 0.0 // μm²
    private var cumulativeSumTimeSeconds = 0.0    // seconds
    private var cumulativeStepCount = 0L

    fun resetAccumulators() {
        cumulativeSumSqDisplacement = 0.0
        cumulativeSumTimeSeconds = 0.0
        cumulativeStepCount = 0L
    }

    /**
     * Joint Maximum Likelihood Solver:
     * Accumulates per-step displacements and solves for Temperature T in °C.
     */
    fun accumulateSteps(
        tracks: List<ParticleTrack>,
        bulkDriftPxPerSec: Vector2D = Vector2D(0f, 0f),
        referenceRadiusMicrons: Double = 1.0
    ): CumulativePhysicsResult {
        val etaPascalSec = 1.002e-3 // Water viscosity at 20°C (1.002 mPa·s)

        for (track in tracks) {
            val pts = track.points
            if (pts.size < 2) continue

            val lastTime = track.lastAccumulatedTime

            for (i in 1 until pts.size) {
                if (pts[i].t > lastTime) {
                    val dt = Math.max(0.005, Math.min(0.1, pts[i].t - pts[i - 1].t))

                    val rawDx = (pts[i].x - pts[i - 1].x) * scaleMicronsPerPixel
                    val rawDy = (pts[i].y - pts[i - 1].y) * scaleMicronsPerPixel

                    val driftDx = bulkDriftPxPerSec.vx * scaleMicronsPerPixel * dt.toFloat()
                    val driftDy = bulkDriftPxPerSec.vy * scaleMicronsPerPixel * dt.toFloat()

                    val pureDx = rawDx - driftDx
                    val pureDy = rawDy - driftDy
                    val pureDist = hypot(pureDx.toDouble(), pureDy.toDouble())

                    // Ignore teleports or boundary jumps
                    if (pureDist > 0.0001 && pureDist < 6.0) {
                        val sqDist = pureDx * pureDx + pureDy * pureDy
                        cumulativeSumSqDisplacement += sqDist
                        cumulativeSumTimeSeconds += dt
                        cumulativeStepCount++
                    }

                    track.lastAccumulatedTime = pts[i].t
                }
            }
        }

        if (cumulativeStepCount < 10 || cumulativeSumTimeSeconds <= 0) {
            return CumulativePhysicsResult(0.0, 20.0, 0, 100.0)
        }

        // Ensemble 2D Thermal Diffusion Coefficient D = <Δr_pure²> / (4 * <Δt>)
        val D_converged = Math.max(0.001, cumulativeSumSqDisplacement / (4.0 * cumulativeSumTimeSeconds))

        // Maximum Likelihood Fit for Temperature in °C
        val radiusMeters = referenceRadiusMicrons * 1e-6
        val D_m2_s = D_converged * 1e-12

        val T_kelvin = (6.0 * Math.PI * etaPascalSec * radiusMeters * D_m2_s) / BOLTZMANN_REF
        val T_converged_C = T_kelvin - 273.15

        val stdErrPercent = Math.max(0.1, 100.0 / Math.sqrt(cumulativeStepCount * 0.4))

        return CumulativePhysicsResult(
            D_converged = D_converged,
            T_converged_C = T_converged_C,
            totalSteps = cumulativeStepCount,
            stdErrPercent = stdErrPercent
        )
    }
}
