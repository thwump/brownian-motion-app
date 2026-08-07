package com.brownian.tracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.brownian.tracker.detector.DetectedParticle
import com.brownian.tracker.tracker.ParticleTrack
import com.brownian.tracker.tracker.TrackPoint
import kotlin.math.hypot

data class RenderTrackSnapshot(
    val id: Int,
    val color: Int,
    val points: List<TrackPoint>
)

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @Volatile
    private var particlesSnapshot: List<DetectedParticle> = emptyList()
    @Volatile
    private var tracksSnapshot: List<RenderTrackSnapshot> = emptyList()
    private var scaleX: Float = 1.0f
    private var scaleY: Float = 1.0f

    var isOverlayEnabled: Boolean = true
    var isCalibrationMode: Boolean = false
    var onCalibrationComplete: ((distPx: Float) -> Unit)? = null

    // Base calibration scale at 1.0x uncropped zoom (4.0mm FOV = 3.125 μm / px)
    var baseScaleMicronsPerPixel: Float = 3.125f
    // Current digital zoom ratio (1.0x to 10.0x)
    var currentZoomRatio: Float = 10.0f

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

    private val scaleBarPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val scaleBarBgPaint = Paint().apply {
        color = Color.parseColor("#b30f172a")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val scaleBarTextPaint = Paint().apply {
        color = Color.parseColor("#00f2fe")
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    fun updateData(newParticles: List<DetectedParticle>, newTracks: List<ParticleTrack>, procWidth: Int, procHeight: Int) {
        // Defensive thread-safe deep copy snapshot
        val particlesCopy = ArrayList(newParticles)
        val tracksCopy = newTracks.map { track ->
            val ptsCopy = synchronized(track) {
                ArrayList(track.points)
            }
            RenderTrackSnapshot(track.id, track.color, ptsCopy)
        }

        this.particlesSnapshot = particlesCopy
        this.tracksSnapshot = tracksCopy

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

        // Draw Overlays if enabled
        if (isOverlayEnabled) {
            // Draw Multi-Colored Particle Trajectories
            val tracksToDraw = tracksSnapshot
            for (track in tracksToDraw) {
                val pts = track.points
                if (pts.size < 2) continue

                trackPaint.color = track.color

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
            val particlesToDraw = particlesSnapshot
            for (particle in particlesToDraw) {
                val cx = particle.x * scaleX
                val cy = particle.y * scaleY
                val r = Math.max(12f, particle.radius * ((scaleX + scaleY) / 2f))

                canvas.drawCircle(cx, cy, r, detectionPaint)
                canvas.drawCircle(cx, cy, 5f, centerPaint)
            }
        }

        // Draw Calibration Drag Line
        val p1 = calibStartPoint
        val p2 = calibEndPoint
        if (p1 != null && p2 != null) {
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, calibPaint)
            canvas.drawCircle(p1.x, p1.y, 10f, centerPaint)
            canvas.drawCircle(p2.x, p2.y, 10f, centerPaint)

            val distPx = hypot(p2.x - p1.x, p2.y - p1.y)
            canvas.drawText(String.format("%.1f px", distPx), (p1.x + p2.x) / 2f + 20f, (p1.y + p2.y) / 2f - 20f, calibTextPaint)
        }

        // Draw Dynamic Zoom-Aware Physical Scale Bar Widget (Bottom-Right)
        drawDynamicScaleBar(canvas)
    }

    private fun drawDynamicScaleBar(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        // Effective scale accounts for digital zoom ratio (μm / px on canvas)
        val effectiveScaleMicronsPerPx = (baseScaleMicronsPerPixel / Math.max(1.0f, currentZoomRatio)) * scaleX
        if (effectiveScaleMicronsPerPx <= 0) return

        // Target scale bar width on screen ~ 140 pixels
        val targetMicrons = 140.0 * effectiveScaleMicronsPerPx

        // Pick round physical length (1mm, 500μm, 100μm, 50μm, 10μm, 1μm)
        val (barMicrons, labelStr) = when {
            targetMicrons >= 800.0 -> Pair(1000.0, "1 mm")
            targetMicrons >= 350.0 -> Pair(500.0, "500 μm")
            targetMicrons >= 75.0  -> Pair(100.0, "100 μm")
            targetMicrons >= 35.0  -> Pair(50.0, "50 μm")
            targetMicrons >= 7.5   -> Pair(10.0, "10 μm")
            else                   -> Pair(1.0, "1 μm")
        }

        val barPx = (barMicrons / effectiveScaleMicronsPerPx).toFloat()
        if (barPx <= 5f || barPx > width * 0.8f) return

        val margin = 30f
        val barY = height - margin - 20f
        val barStartX = width - margin - barPx - 20f
        val barEndX = barStartX + barPx

        // Background pill
        val bgRectF = android.graphics.RectF(
            barStartX - 20f,
            barY - 45f,
            barEndX + 20f,
            height - margin + 10f
        )
        canvas.drawRoundRect(bgRectF, 12f, 12f, scaleBarBgPaint)

        // Main horizontal bar
        canvas.drawLine(barStartX, barY, barEndX, barY, scaleBarPaint)
        // End caps
        canvas.drawLine(barStartX, barY - 10f, barStartX, barY + 10f, scaleBarPaint)
        canvas.drawLine(barEndX, barY - 10f, barEndX, barY + 10f, scaleBarPaint)

        // Label text centered above bar
        val textWidth = scaleBarTextPaint.measureText(labelStr)
        val textX = barStartX + (barPx - textWidth) / 2f
        canvas.drawText(labelStr, textX, barY - 12f, scaleBarTextPaint)
    }
}
