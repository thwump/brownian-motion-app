package com.brownian.tracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.brownian.tracker.detector.DetectedParticle
import com.brownian.tracker.tracker.ParticleTrack
import kotlin.math.hypot

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var particles: List<DetectedParticle> = emptyList()
    private var tracks: List<ParticleTrack> = emptyList()
    private var scaleX: Float = 1.0f
    private var scaleY: Float = 1.0f

    var isCalibrationMode: Boolean = false
    var onCalibrationComplete: ((distPx: Float) -> Unit)? = null

    private var calibStartPoint: PointF? = null
    private var calibEndPoint: PointF? = null

    private val detectionPaint = Paint().apply {
        color = Color.parseColor("#00f2fe")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val centerPaint = Paint().apply {
        color = Color.parseColor("#ff007f")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val trackPaint = Paint().apply {
        color = Color.parseColor("#00f2fe")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val calibPaint = Paint().apply {
        color = Color.parseColor("#00f2fe")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val calibTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
    }

    fun updateData(newParticles: List<DetectedParticle>, newTracks: List<ParticleTrack>, procWidth: Int, procHeight: Int) {
        this.particles = newParticles
        this.tracks = newTracks
        if (procWidth > 0 && procHeight > 0 && width > 0 && height > 0) {
            this.scaleX = width.toFloat() / procWidth.toFloat()
            this.scaleY = height.toFloat() / procHeight.toFloat()
        }
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrationMode) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                calibStartPoint = PointF(event.x, event.y)
                calibEndPoint = PointF(event.x, event.y)
                postInvalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                calibEndPoint = PointF(event.x, event.y)
                postInvalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p1 = calibStartPoint
                val p2 = calibEndPoint
                if (p1 != null && p2 != null) {
                    val distPx = hypot(p2.x - p1.x, p2.y - p1.y)
                    if (distPx > 10f) {
                        onCalibrationComplete?.invoke(distPx)
                    }
                }
                calibStartPoint = null
                calibEndPoint = null
                isCalibrationMode = false
                postInvalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Trajectory Lines
        for (track in tracks) {
            val pts = track.points
            if (pts.size < 2) continue

            for (i in 1 until pts.size) {
                val p1 = pts[i - 1]
                val p2 = pts[i]
                canvas.drawLine(
                    p1.x * scaleX, p1.y * scaleY,
                    p2.x * scaleX, p2.y * scaleY,
                    trackPaint
                )
            }
        }

        // Draw Particle Detection Bounding Rings
        for (particle in particles) {
            val cx = particle.x * scaleX
            val cy = particle.y * scaleY
            val r = Math.max(12f, particle.radius * ((scaleX + scaleY) / 2f))

            canvas.drawCircle(cx, cy, r, detectionPaint)
            canvas.drawCircle(cx, cy, 5f, centerPaint)
        }

        // Draw Calibration Line
        val p1 = calibStartPoint
        val p2 = calibEndPoint
        if (p1 != null && p2 != null) {
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, calibPaint)
            canvas.drawCircle(p1.x, p1.y, 10f, centerPaint)
            canvas.drawCircle(p2.x, p2.y, 10f, centerPaint)

            val distPx = hypot(p2.x - p1.x, p2.y - p1.y)
            canvas.drawText(String.format("%.1f px", distPx), (p1.x + p2.x) / 2f + 20f, (p1.y + p2.y) / 2f - 20f, calibTextPaint)
        }
    }
}
