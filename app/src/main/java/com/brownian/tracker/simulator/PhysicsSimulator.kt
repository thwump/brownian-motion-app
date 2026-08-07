package com.brownian.tracker.simulator

import android.graphics.*
import com.brownian.tracker.physics.BOLTZMANN_REF
import kotlin.math.*

data class SimulatedParticle(
    var x: Double,
    var y: Double,
    val radiusMicrons: Double,
    val trailX: FloatArray = FloatArray(30),
    val trailY: FloatArray = FloatArray(30),
    var trailHead: Int = 0
)

class PhysicsSimulator(var width: Int = 1280, var height: Int = 720) {
    var particleRadiusMicrons: Double = 1.0
    var viscosityMpaSec: Double = 1.0
    var tempCelsius: Double = 20.0
    var driftMicronsPerSec: Double = 0.5
    // Diffuse Gas Regime: 8 widely-spaced particles to prevent any merging/clustering
    var numParticles: Int = 8
    
    // Scale controls visual optical magnification (0.15 μm/px scale -> High-Mag FOV)
    var scaleMicronsPerPixel: Float = 0.15f
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
            // Fat globules: 1.0 μm to 2.5 μm
            rMicrons = Math.max(1.0, Math.min(2.5, exp(ln(1.5) + 0.3 * z)))
        }

        // Spawns particles widely spaced out (min 100px separation)
        var px = 0.0
        var py = 0.0
        var attempts = 0
        do {
            px = (random.nextDouble() * (width - 160) + 80.0)
            py = (random.nextDouble() * (height - 160) + 80.0)
            attempts++

            var tooClose = false
            for (p in particles) {
                if (hypot(px - p.x, py - p.y) < 120.0) {
                    tooClose = true
                    break
                }
            }
        } while (tooClose && attempts < 100)

        val p = SimulatedParticle(x = px, y = py, radiusMicrons = rMicrons)
        for (k in 0 until 30) {
            p.trailX[k] = px.toFloat()
            p.trailY[k] = py.toFloat()
        }
        return p
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
            
            // Thermal Brownian step size σ_px
            val sigma = sqrt(2.0 * D_px_sq_s * dtSeconds)

            val u1 = Math.max(1e-6, random.nextDouble())
            val u2 = Math.max(1e-6, random.nextDouble())
            val z0 = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
            val z1 = sqrt(-2.0 * ln(u1)) * sin(2.0 * PI * u2)

            p.x += driftDx + sigma * z0
            p.y += driftDy + sigma * z1

            // Record trajectory trail history
            p.trailHead = (p.trailHead + 1) % 30
            p.trailX[p.trailHead] = p.x.toFloat()
            p.trailY[p.trailHead] = p.y.toFloat()
        }

        // 2. Hard-Sphere Elastic Repulsion (Prevents Particle Clumping)
        for (i in 0 until particles.size) {
            for (j in i + 1 until particles.size) {
                val p1 = particles[i]
                val p2 = particles[j]

                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val dist = hypot(dx, dy)

                val minDistPx = Math.max(40.0, ((p1.radiusMicrons + p2.radiusMicrons) / scaleMicronsPerPixel) * 2.0)

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

        // Bright background for perfect high-contrast detector match (detector.invert = true)
        val bgPaint = Paint().apply {
            color = Color.parseColor("#f8fafc")
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

        val trailPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            isAntiAlias = true
        }

        for (p in particles) {
            val px = p.x.toFloat()
            val py = p.y.toFloat()
            val rPx = Math.max(8.0f, (p.radiusMicrons / scaleMicronsPerPixel).toFloat())

            // Render glowing motion trajectory tail directly in simulator
            val head = p.trailHead
            for (k in 1 until 30) {
                val idx1 = (head - k + 30) % 30
                val idx2 = (head - k + 1 + 30) % 30
                val alpha = (255 * (30 - k) / 30)
                trailPaint.color = Color.argb(alpha, 0, 242, 254)
                canvas.drawLine(p.trailX[idx1], p.trailY[idx1], p.trailX[idx2], p.trailY[idx2], trailPaint)
            }

            // Outer diffraction halo
            canvas.drawCircle(px, py, rPx * 1.4f, haloPaint)

            // Crisp dark particle core matching detector.invert = true
            canvas.drawCircle(px, py, rPx, corePaint)
        }
    }
}
