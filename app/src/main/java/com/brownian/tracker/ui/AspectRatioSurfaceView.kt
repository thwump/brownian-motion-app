package com.brownian.tracker.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView

/**
 * SurfaceView that maintains a specific aspect ratio.
 * Critical for accurate particle tracking - ensures pixels are square (same scale in x and y).
 */
class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private var aspectRatio: Float = 0f

    /**
     * Set the aspect ratio for this view.
     * @param width Sensor width in pixels
     * @param height Sensor height in pixels
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Width and height must be positive" }
        
        val newAspectRatio = width.toFloat() / height.toFloat()
        
        if (aspectRatio != newAspectRatio) {
            aspectRatio = newAspectRatio
            Log.d("AspectRatioSurfaceView", "Aspect ratio set to $aspectRatio ($width x $height)")
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        if (aspectRatio == 0f) {
            // No aspect ratio set, use default behavior
            return
        }
        
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        
        // CROP TO FILL WIDTH: Always use full width, calculate height to maintain aspect ratio
        // This crops top/bottom if needed but fills the screen width
        val finalWidth = width
        val finalHeight = (width / aspectRatio).toInt()
        
        Log.d("AspectRatioSurfaceView", "Container: ${width}x${height}, Aspect: $aspectRatio, Fill width mode - Final: ${finalWidth}x${finalHeight}")
        
        setMeasuredDimension(finalWidth, finalHeight)
    }
}
