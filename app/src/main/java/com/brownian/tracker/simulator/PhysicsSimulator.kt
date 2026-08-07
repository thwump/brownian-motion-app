package com.brownian.tracker.simulator

import android.graphics.*
import com.brownian.tracker.physics.BOLTZMANN_REF
import kotlin.math.*

data class SimulatedParticle(
    var x: Double,
    var y: Double,
    val radiusMicrons: Double
)

class PhysicsSimulator(var width: Int = 1280, var height: Int = 720) {
    var particleRadiusMicrons: Double = 1.0
    var viscosityMpaSec: Double = 1.0
    var tempCelsius: Double = 20.0
    var driftMicronsPerSec: Double = 0.5
    var numParticles: Int = 30
    
    // Pixel 9 + 200x Lens + 10x Digital Zoom scale:
    // 400 μm FOV across 1280 px canvas => 0.3125 μm/px scale!
    var scaleMicronsPerPixel: Float = 0.3125f
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
            // Fat globules: 0.5 μm to 4.5 μm
            rMicrons = Math.max(0.5, Math.min(4.5, exp(ln(1.5) + 0.5 * z)))
        }

        return SimulatedParticle(
            x = (random.nextDouble() * (width - 120) + 60.0),
            y = (random.nextDouble() * (height - 120) + 60.0),
            radiusMicrons = rMicrons
        )
    }

    fun update(dtSeconds: Double) {
        val T_kelvin = tempCelsius + 273.15
        val eta_pascal_sec = viscosityMpaSec * 1e-3

        val driftPxPerSec = driftMicronsPerSec / scaleMicronsPerPixel
        val driftDx = driftPxPerSec * 0.7071 * dtSeconds
        val driftDy = driftPxPerSec * 0.7071 * dtSeconds

        // 1. Apply Thermal Brownian Motion & Bulk Fluid Drift
        for (i in particles.indices) {
            val p = particles[i]
            val radius_meters = p.radiusMicrons * 1e-6
            val D_m2_s = (BOLTZMANN_REF * T_kelvin) / (6.0 * PI * eta_pascal_sec * radius_meters)
            val D_microns_sq_s = D_m2_s * 1e12
            val D_px_sq_s = D_microns_sq_s / (scaleMicronsPerPixel * scaleMicronsPerPixel)
            
            val sigma = sqrt(2.0 * D_px_sq_s * dtSeconds)

            val u1 = Math.max(1e-6, random.nextDouble())
            val u2 = Math.max(1e-6, random.nextDouble())
            val z0 = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
            val z1 = sqrt(-2.0 * ln(u1)) * sin(2.0 * PI * u2)

            p.x += driftDx + sigma * z0
            p.y += driftDy + sigma * z1
        }

        // 2. Hard-Sphere Elastic Repulsion (Prevents Particle Clumping & Overlap Jumps)
        for (i in 0 until particles.size) {
            for (j in i + 1 until particles.size) {
                val p1 = particles[i]
                val p2 = particles[j]

                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val dist = hypot(dx, dy)

                val minDistPx = Math.max(12.0, ((p1.radiusMicrons + p2.radiusMicrons) / scaleMicronsPerPixel))

                if (dist < minDistPx && dist > 0.001) {
                    val overlap = minDistPx - dist
                    val nx = dx / dist
                    val ny = dy / dist

                    p1.x -= nx * overlap * 0.5
                    p1.y -= ny * overlap * 0.5
                    p2.x += nx * overlap * 0.5
                    p2.y += ny * overlap * 0.5
                }
            }
        }

        // 3. Boundary Respawn Handling
        for (i in particles.indices) {
            val p = particles[i]
            if (p.x < 10.0 || p.x > width - 10.0 || p.y < 10.0 || p.y > height - 10.0) {
                particles[i] = createRandomParticle()
            }
        }
    }

    fun renderToBitmap(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)

        // Bright slate background
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
            // Realistic optical PSF diffraction Airy spot size
            val rPx = Math.max(5.0f, (p.radiusMicrons / scaleMicronsPerPixel).toFloat() * 1.5f)
            val px = p.x.toFloat()
            val py = p.y.toFloat()

            // Outer diffraction halo
            canvas.drawCircle(px, py, rPx * 1.4f, haloPaint)

            // Crisp dark particle core
            canvas.drawCircle(px, py, rPx, corePaint)
        }
    }
}
