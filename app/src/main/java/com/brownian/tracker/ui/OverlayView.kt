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
    private var offsetX: Float = 0.0f
    private var offsetY: Float = 0.0f

    var isOverlayEnabled: Boolean = true
    var isCalibrationMode: Boolean = false
        set(value) {
            field = value
            if (value && (calibStartPoint == null || calibEndPoint == null)) {
                // Set initial default fine-tuning handles in center of screen
                val cx = width / 2f
                val cy = height / 2f
                calibStartPoint = PointF(cx - 150f, cy)
                calibEndPoint = PointF(cx + 150f, cy)
            }
            postInvalidate()
        }

    var onCalibrationLineChanged: ((distPx: Float) -> Unit)? = null

    // Base calibration scale at 1.0x uncropped zoom (4.0mm FOV = 3.125 μm / px)
    var baseScaleMicronsPerPixel: Float = 3.125f
    // Current digital zoom ratio (1.0x to 10.0x)
    var currentZoomRatio: Float = 1.0f

    var calibStartPoint: PointF? = null
        private set
    var calibEndPoint: PointF? = null
        private set

    private var activeDraggingHandle: Int = 0 // 0 = none, 1 = start, 2 = end

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

    private val calibLinePaint = Paint().apply {
        color = Color.parseColor("#00f2fe")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val handleOuterPaint = Paint().apply {
        color = Color.parseColor("#8000f2fe")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val handleInnerPaint = Paint().apply {
        color = Color.parseColor("#ff007f")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val calibTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
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
            val scaleWidth = width.toFloat() / procWidth.toFloat()
            val scaleHeight = height.toFloat() / procHeight.toFloat()
            val scale = Math.max(scaleWidth, scaleHeight)
            
            this.scaleX = scale
            this.scaleY = scale
            
            val scaledProcWidth = procWidth * scale
            val scaledProcHeight = procHeight * scale
            this.offsetX = (width - scaledProcWidth) / 2f
            this.offsetY = (height - scaledProcHeight) / 2f
        }
        postInvalidate()
    }

    fun getCalibrationDistancePx(): Float {
        val p1 = calibStartPoint ?: return 0f
        val p2 = calibEndPoint ?: return 0f
        return hypot(p2.x - p1.x, p2.y - p1.y)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrationMode) return super.onTouchEvent(event)

        // Lock out parent ScrollView from scrolling during calibration handle dragging
        parent?.requestDisallowInterceptTouchEvent(true)

        val touchX = event.x
        val touchY = event.y

        val p1 = calibStartPoint ?: PointF(width / 2f - 150f, height / 2f)
        val p2 = calibEndPoint ?: PointF(width / 2f + 150f, height / 2f)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val distToP1 = hypot(touchX - p1.x, touchY - p1.y)
                val distToP2 = hypot(touchX - p2.x, touchY - p2.y)
                val touchRadius = 90f // 90px touch target radius for comfortable handle grabbing

                activeDraggingHandle = when {
                    distToP1 < touchRadius && distToP1 <= distToP2 -> 1
                    distToP2 < touchRadius -> 2
                    else -> {
                        // If tap is outside handles, set new line start and end at touch position
                        calibStartPoint = PointF(touchX, touchY)
                        calibEndPoint = PointF(touchX + 100f, touchY)
                        2
                    }
                }
                notifyLineChanged()
                postInvalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeDraggingHandle == 1) {
                    calibStartPoint = PointF(touchX, touchY)
                } else if (activeDraggingHandle == 2) {
                    calibEndPoint = PointF(touchX, touchY)
                }
                notifyLineChanged()
                postInvalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDraggingHandle = 0
                notifyLineChanged()
                postInvalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun notifyLineChanged() {
        val distPx = getCalibrationDistancePx()
        onCalibrationLineChanged?.invoke(distPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Overlays if enabled
        if (isOverlayEnabled && !isCalibrationMode) {
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
                        p1.x * scaleX + offsetX, p1.y * scaleY + offsetY,
                        p2.x * scaleX + offsetX, p2.y * scaleY + offsetY,
                        trackPaint
                    )
                }
            }

            // Draw Particle Detection Bounding Rings
            val particlesToDraw = particlesSnapshot
            for (particle in particlesToDraw) {
                val cx = particle.x * scaleX + offsetX
                val cy = particle.y * scaleY + offsetY
                val r = Math.max(12f, particle.radius * scaleX)

                canvas.drawCircle(cx, cy, r, detectionPaint)
                canvas.drawCircle(cx, cy, 5f, centerPaint)
            }
        }

        // Draw Fine-Tuning Calibration Drag Handles & Line
        if (isCalibrationMode) {
            val p1 = calibStartPoint ?: PointF(width / 2f - 150f, height / 2f)
            val p2 = calibEndPoint ?: PointF(width / 2f + 150f, height / 2f)

            // Connecting scale line
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, calibLinePaint)

            // Large Touch Handles for Start (P1) and End (P2)
            canvas.drawCircle(p1.x, p1.y, 40f, handleOuterPaint)
            canvas.drawCircle(p1.x, p1.y, 14f, handleInnerPaint)

            canvas.drawCircle(p2.x, p2.y, 40f, handleOuterPaint)
            canvas.drawCircle(p2.x, p2.y, 14f, handleInnerPaint)

            // Length readout text overlay above midpoint
            val distPx = hypot(p2.x - p1.x, p2.y - p1.y)
            val textStr = String.format("%.1f px", distPx)
            val midX = (p1.x + p2.x) / 2f
            val midY = (p1.y + p2.y) / 2f - 30f
            canvas.drawText(textStr, midX - 50f, midY, calibTextPaint)
        }

        // Draw Dynamic Zoom-Aware Physical Scale Bar Widget (Bottom-Right)
        if (!isCalibrationMode) {
            drawDynamicScaleBar(canvas)
        }
    }

    private fun drawDynamicScaleBar(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        val effectiveScaleMicronsPerPx = (baseScaleMicronsPerPixel / Math.max(1.0f, currentZoomRatio)) * scaleX
        if (effectiveScaleMicronsPerPx <= 0) return

        val targetMicrons = 140.0 * effectiveScaleMicronsPerPx

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

        val bgRectF = android.graphics.RectF(
            barStartX - 20f,
            barY - 45f,
            barEndX + 20f,
            height - margin + 10f
        )
        canvas.drawRoundRect(bgRectF, 12f, 12f, scaleBarBgPaint)

        canvas.drawLine(barStartX, barY, barEndX, barY, scaleBarPaint)
        canvas.drawLine(barStartX, barY - 10f, barStartX, barY + 10f, scaleBarPaint)
        canvas.drawLine(barEndX, barY - 10f, barEndX, barY + 10f, scaleBarPaint)

        val textWidth = scaleBarTextPaint.measureText(labelStr)
        val textX = barStartX + (barPx - textWidth) / 2f
        canvas.drawText(labelStr, textX, barY - 12f, scaleBarTextPaint)
    }
}
