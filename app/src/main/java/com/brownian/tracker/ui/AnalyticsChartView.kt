package com.brownian.tracker.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.brownian.tracker.physics.MsdResult

enum class ChartType {
    MSD_CURVE,
    PSD_HISTOGRAM,
    STEP_DISTRIBUTION
}

class AnalyticsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var chartType: ChartType = ChartType.MSD_CURVE
    private var msdResult: MsdResult? = null
    private var histogramBins: List<String> = emptyList()
    private var histogramCounts: List<Int> = emptyList()

    private val linePaint = Paint().apply {
        color = Color.parseColor("#00f2fe")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val fitLinePaint = Paint().apply {
        color = Color.parseColor("#f72585")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        isAntiAlias = true
    }

    private val barPaint = Paint().apply {
        color = Color.parseColor("#00f2fe")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#94a3b8")
        textSize = 28f
        isAntiAlias = true
    }

    private val titlePaint = Paint().apply {
        color = Color.parseColor("#f8fafc")
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1f293d")
        strokeWidth = 2f
    }

    fun updateMsdData(msd: MsdResult) {
        this.chartType = ChartType.MSD_CURVE
        this.msdResult = msd
        postInvalidate()
    }

    fun updateHistogramData(type: ChartType, bins: List<String>, counts: List<Int>) {
        this.chartType = type
        this.histogramBins = bins
        this.histogramCounts = counts
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val paddingLeft = 80f
        val paddingBottom = 60f
        val paddingTop = 60f
        val paddingRight = 40f

        val graphWidth = width - paddingLeft - paddingRight
        val graphHeight = height - paddingTop - paddingBottom

        if (graphWidth <= 0 || graphHeight <= 0) return

        // Draw Axes & Gridlines
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, height - paddingBottom, gridPaint)
        canvas.drawLine(paddingLeft, height - paddingBottom, width - paddingRight, height - paddingBottom, gridPaint)

        when (chartType) {
            ChartType.MSD_CURVE -> drawMsdGraph(canvas, paddingLeft, paddingTop, graphWidth, graphHeight)
            ChartType.PSD_HISTOGRAM -> drawHistogram(canvas, paddingLeft, paddingTop, graphWidth, graphHeight, "Particle Size Distribution P(d)", "d (μm)")
            ChartType.STEP_DISTRIBUTION -> drawHistogram(canvas, paddingLeft, paddingTop, graphWidth, graphHeight, "Step Displacement P(Δr)", "Δr (μm)")
        }
    }

    private fun drawMsdGraph(canvas: Canvas, padLeft: Float, padTop: Float, gWidth: Float, gHeight: Float) {
        val result = msdResult ?: return
        canvas.drawText("MSD ⟨Δr²⟩ vs Time Delay Δt", padLeft, padTop - 20, titlePaint)

        val points = result.msdPoints
        if (points.isEmpty()) return

        val maxX = points.maxOf { it.dt }
        val maxY = Math.max(0.1, points.maxOf { it.msd })

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = padLeft + (p.dt / maxX).toFloat() * gWidth
            val y = (padTop + gHeight) - (p.msd / maxY).toFloat() * gHeight

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, 6f, linePaint)
        }
        canvas.drawPath(path, linePaint)

        // Draw Einstein linear fit line (4D Δt)
        val fitPath = Path()
        fitPath.moveTo(padLeft, padTop + gHeight)
        val fitYEnd = (padTop + gHeight) - (4.0 * result.D * maxX / maxY).toFloat() * gHeight
        fitPath.lineTo(padLeft + gWidth, fitYEnd)
        canvas.drawPath(fitPath, fitLinePaint)

        canvas.drawText(String.format("R² = %.3f | D = %.3f μm²/s", result.rSquared, result.D), padLeft + 20, padTop + 40, textPaint)
    }

    private fun drawHistogram(canvas: Canvas, padLeft: Float, padTop: Float, gWidth: Float, gHeight: Float, title: String, xLabel: String) {
        canvas.drawText(title, padLeft, padTop - 20, titlePaint)
        if (histogramCounts.isEmpty()) return

        val maxCount = Math.max(1, histogramCounts.maxOrNull() ?: 1)
        val barWidth = gWidth / histogramCounts.size

        histogramCounts.forEachIndexed { i, count ->
            val barH = (count.toFloat() / maxCount) * gHeight
            val left = padLeft + i * barWidth + 4f
            val right = left + barWidth - 8f
            val top = (padTop + gHeight) - barH
            val bottom = padTop + gHeight

            canvas.drawRect(left, top, right, bottom, barPaint)

            if (i % 3 == 0 && i < histogramBins.size) {
                canvas.drawText(histogramBins[i], left, bottom + 40, textPaint)
            }
        }
    }
}
