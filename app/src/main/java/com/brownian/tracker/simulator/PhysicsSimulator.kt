package com.brownian.tracker.simulator

import android.graphics.*
import com.brownian.tracker.physics.BOLTZMANN_REF
import kotlin.math.*

data class SimulatedParticle(
    var x: Float,
    var y: Float,
    val radiusMicrons: Double
)

class PhysicsSimulator(var width: Int = 640, var height: Int = 480) {
    var particleRadiusMicrons: Double = 1.0
    var viscosityMpaSec: Double = 1.0
    var tempCelsius: Double = 20.0
    var driftMicronsPerSec: Double = 0.5
    var numParticles: Int = 40
    // Field of view: ~6mm x 4mm across 640x480 pixels => ~9.375 μm/px scale
    var scaleMicronsPerPixel: Float = 9.375f
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
            x = (random.nextFloat() * (width - 40) + 20),
            y = (random.nextFloat() * (height - 40) + 20),
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

            // Respawns new particle when leaving viewport (eliminates boundary teleports)
            if (p.x < 5f || p.x > width - 5f || p.y < 5f || p.y > height - 5f) {
                particles[i] = createRandomParticle()
            }
        }
    }

    fun renderToBitmap(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)

        // Light background
        val bgPaint = Paint().apply {
            color = Color.parseColor("#e2e8f0")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val haloPaint = Paint().apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val corePaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Diffraction-limited point spread function (PSF) rendering for 4mm x 6mm FOV
        for (p in particles) {
            // Optical diffraction Airy disk radius (1.5 to 3.5 pixels)
            val diffractionRadiusPx = Math.max(2.0f, (p.radiusMicrons / 0.8f).toFloat())

            // Diffraction halo ring
            canvas.drawCircle(p.x, p.y, diffractionRadiusPx * 1.6f, haloPaint)

            // Dark core spot
            corePaint.color = Color.parseColor("#0f172a")
            canvas.drawCircle(p.x, p.y, diffractionRadiusPx, corePaint)
        }
    }
}
