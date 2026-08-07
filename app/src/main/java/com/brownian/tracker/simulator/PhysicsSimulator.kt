package com.brownian.tracker.simulator

import android.graphics.*
import com.brownian.tracker.physics.BOLTZMANN_REF
import kotlin.math.*

data class SimulatedParticle(
    var x: Float,
    var y: Float,
    val radiusMicrons: Double
)

class PhysicsSimulator(var width: Int = 1280, var height: Int = 720) {
    var particleRadiusMicrons: Double = 1.0
    var viscosityMpaSec: Double = 1.0
    var tempCelsius: Double = 20.0
    var driftMicronsPerSec: Double = 0.5
    var numParticles: Int = 40
    // HD 1280x720 scale for 6mm x 3.375mm optical FOV: 6000 μm / 1280 px = 4.6875 μm/px
    var scaleMicronsPerPixel: Float = 4.6875f
    var isPolydisperse: Boolean = true

    val particles = mutableListOf<SimulatedParticle>()
    private val random = java.util.Random()

    init {
        initParticles()
    }

    fun initParticles() {
        particles.clear()
        for (i in 0 until numParticles) {
            particles.add(createRandomParticle())
        }
    }

    private fun createRandomParticle(): SimulatedParticle {
        var rMicrons = particleRadiusMicrons

        if (isPolydisperse) {
            val u1 = Math.max(1e-6, random.nextDouble())
            val u2 = Math.max(1e-6, random.nextDouble())
            val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
            // Fat globules: 0.5 μm to 5.0 μm
            rMicrons = Math.max(0.5, Math.min(5.0, exp(ln(1.5) + 0.5 * z)))
        }

        return SimulatedParticle(
            x = (random.nextFloat() * (width - 60) + 30),
            y = (random.nextFloat() * (height - 60) + 30),
            radiusMicrons = rMicrons
        )
    }

    fun update(dtSeconds: Double) {
        val T_kelvin = tempCelsius + 273.15
        val eta_pascal_sec = viscosityMpaSec * 1e-3

        val driftPxPerSec = driftMicronsPerSec / scaleMicronsPerPixel
        val driftDx = (driftPxPerSec * 0.7071 * dtSeconds).toFloat()
        val driftDy = (driftPxPerSec * 0.7071 * dtSeconds).toFloat()

        for (i in particles.indices) {
            val p = particles[i]
            val radius_meters = p.radiusMicrons * 1e-6
            val D_m2_s = (BOLTZMANN_REF * T_kelvin) / (6.0 * PI * eta_pascal_sec * radius_meters)
            val D_microns_sq_s = D_m2_s * 1e12
            val D_px_sq_s = D_microns_sq_s / (scaleMicronsPerPixel * scaleMicronsPerPixel)
            val sigma = sqrt(2.0 * D_px_sq_s * dtSeconds).toFloat()

            val u1 = Math.max(1e-6, random.nextDouble())
            val u2 = Math.max(1e-6, random.nextDouble())
            val z0 = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
            val z1 = (sqrt(-2.0 * ln(u1)) * sin(2.0 * PI * u2)).toFloat()

            p.x += driftDx + sigma * z0
            p.y += driftDy + sigma * z1

            // Respawns new particle when leaving viewport
            if (p.x < 10f || p.x > width - 10f || p.y < 10f || p.y > height - 10f) {
                particles[i] = createRandomParticle()
            }
        }
    }

    fun renderToBitmap(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)

        // Bright white-slate background
        val bgPaint = Paint().apply {
            color = Color.parseColor("#f1f5f9")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val haloPaint = Paint().apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val corePaint = Paint().apply {
            color = Color.parseColor("#020617")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for (p in particles) {
            val rPx = Math.max(5.0f, (p.radiusMicrons / scaleMicronsPerPixel).toFloat() * 6.0f)

            // Outer diffraction halo
            canvas.drawCircle(p.x, p.y, rPx * 1.5f, haloPaint)

            // Crisp dark particle core
            canvas.drawCircle(p.x, p.y, rPx, corePaint)
        }
    }
}
