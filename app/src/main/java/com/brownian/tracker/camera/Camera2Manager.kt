package com.brownian.tracker.camera

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresPermission
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "Camera2Manager"
private const val MAX_IMAGES = 3

/**
 * Camera2-based camera manager with full manual control including focus distance.
 * Provides YUV_420_888 frames at native sensor resolution for Brownian motion tracking.
 * 
 * Designed for cross-device compatibility (Pixel, Samsung, etc.)
 */
class Camera2Manager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val surfaceView: SurfaceView,
    private val onFrameAnalyzer: (ImageProxy) -> Unit
) {
    
    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    // Background thread for camera callbacks
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraExecutor: Executor? = null
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    private var activeCharacteristics: CameraCharacteristics? = null
    private var previewSurface: Surface? = null
    
    var torchActive: Boolean = false
        private set
    
    private var currentZoomRatio: Float = 1.0f
    private var currentFocusDistance: Float? = null // null = autofocus, 0f = infinity
    private var minFocusDistance: Float = 0f
    
    /**
     * Find the best back camera with YUV_420_888 support.
     * Prioritizes cameras with larger sensors and better capabilities.
     */
    private fun findBestCamera(): CameraCapabilities? {
        val candidates = mutableListOf<CameraCapabilities>()
        
        for (id in cameraManager.cameraIdList) {
            val chars = try {
                cameraManager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get characteristics for camera $id: ${e.message}")
                continue
            }
            
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
            
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: continue
            val largest = yuvSizes.maxByOrNull { it.width * it.height } ?: continue
            
            // Get focus distance range
            val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val focusModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            
            val cap = CameraCapabilities(
                cameraId = id,
                characteristics = chars,
                maxYuvWidth = largest.width,
                maxYuvHeight = largest.height,
                minFocusDistance = minFocusDist,
                supportsManualFocus = focusModes.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF)
            )
            
            candidates.add(cap)
            Log.d(TAG, "Camera $id: ${largest.width}x${largest.height} YUV, minFocusDist=$minFocusDist")
        }
        
        // Sort by resolution (higher is better for microscopy)
        return candidates.maxByOrNull { it.maxYuvWidth * it.maxYuvHeight }
    }
    
    private fun startBackgroundThread() {
        cameraThread = HandlerThread("Camera2Thread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraExecutor = Executor { command -> cameraHandler?.post(command) }
    }
    
    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
            cameraExecutor = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread interrupted: ${e.message}")
        }
    }
    
    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCamera() {
        startBackgroundThread()
        
        // Wait for surface to be ready
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface created, initializing camera...")
                initializeCamera()
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // No action needed
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Surface destroyed")
                shutdown()
            }
        })
        
        // If surface already exists, start immediately
        if (surfaceView.holder.surface.isValid) {
            initializeCamera()
        }
    }
    
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun initializeCamera() {
        val capabilities = findBestCamera()
        if (capabilities == null) {
            Log.e(TAG, "No suitable camera found")
            return
        }
        
        activeCharacteristics = capabilities.characteristics
        minFocusDistance = capabilities.minFocusDistance
        
        Log.d(TAG, "Selected camera ${capabilities.cameraId}: ${capabilities.maxYuvWidth}x${capabilities.maxYuvHeight}")
        
        // Create ImageReader for YUV analysis frames
        imageReader = ImageReader.newInstance(
            capabilities.maxYuvWidth,
            capabilities.maxYuvHeight,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            // Convert to ImageProxy wrapper for compatibility with existing code
            val proxy = ImageProxyWrapper(image)
            onFrameAnalyzer(proxy)
            
            // Note: The analyzer is responsible for closing the proxy/image
        }, cameraHandler)
        
        // Open camera device
        try {
            cameraManager.openCamera(
                capabilities.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession()
                    }
                    
                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "Camera disconnected")
                        camera.close()
                        cameraDevice = null
                    }
                    
                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera error: $error")
                        camera.close()
                        cameraDevice = null
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
        }
    }
    
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        
        previewSurface = surfaceView.holder.surface
        val previewSurf = previewSurface ?: return
        
        val outputs = listOf(
            OutputConfiguration(previewSurf),
            OutputConfiguration(reader.surface)
        )
        
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor!!,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startPreview()
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            }
        )
        
        device.createCaptureSession(sessionConfig)
    }
    
    private fun startPreview() {
        val session = captureSession ?: return
        val device = cameraDevice ?: return
        val previewSurf = previewSurface ?: return
        val reader = imageReader ?: return
        
        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurf)
                addTarget(reader.surface)
                
                // Manual control mode
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                
                // Apply current focus setting
                applyFocusSetting(this)
                
                // Apply current zoom
                applyZoomSetting(this)
                
                // Torch
                if (torchActive) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
                
                // Disable scene mode for manual control
                set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
            }
            
            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
            Log.d(TAG, "Preview started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview: ${e.message}", e)
        }
    }
    
    private fun applyFocusSetting(builder: CaptureRequest.Builder) {
        val focusDist = currentFocusDistance
        
        if (focusDist != null) {
            // Manual focus mode
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
        } else {
            // Continuous autofocus
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }
    
    private fun applyZoomSetting(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use zoom ratio API on Android 11+
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        } else {
            // Use crop region on older Android
            val chars = activeCharacteristics ?: return
            val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            
            val cropW = (sensorRect.width() / currentZoomRatio).toInt()
            val cropH = (sensorRect.height() / currentZoomRatio).toInt()
            val cropX = (sensorRect.width() - cropW) / 2
            val cropY = (sensorRect.height() - cropH) / 2
            
            val cropRegion = android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
        }
    }
    
    /**
     * Set manual focus distance.
     * 
     * @param focusDistance Focus distance in diopters (1/meters)
     *   - 0.0f = infinity focus (ideal for distant objects/stars)
     *   - Higher values = closer focus
     *   - maxFocusDistance = minimum focus distance (closest possible focus)
     *   
     * For microscopy: Use values close to maxFocusDistance (close focus)
     * The slider should map 0.0-1.0 to the device's min-max focus range
     */
    fun setManualFocus(focusDistance: Float): Boolean {
        if (minFocusDistance <= 0f) {
            Log.w(TAG, "Manual focus not supported on this device")
            return false
        }
        
        // Clamp to valid range: 0.0 (infinity) to minFocusDistance (closest)
        val clampedDistance = focusDistance.coerceIn(0f, minFocusDistance)
        currentFocusDistance = clampedDistance
        
        Log.d(TAG, "Manual focus set to $clampedDistance diopters (range: 0.0 - $minFocusDistance)")
        
        // Update preview with new focus setting
        startPreview()
        return true
    }
    
    /**
     * Re-enable continuous autofocus
     */
    fun unlockAutoFocus(): Boolean {
        currentFocusDistance = null
        Log.d(TAG, "Autofocus unlocked (continuous AF)")
        startPreview()
        return true
    }
    
    /**
     * Get the maximum focus distance (for closest focus)
     */
    fun getMaxFocusDistance(): Float = minFocusDistance
    
    /**
     * Check if manual focus is supported
     */
    fun supportsManualFocus(): Boolean = minFocusDistance > 0f
    
    fun setZoomRatio(ratio: Float) {
        currentZoomRatio = ratio.coerceIn(1.0f, 10.0f)
        startPreview()
    }
    
    fun toggleTorch(): Boolean {
        torchActive = !torchActive
        startPreview()
        return torchActive
    }
    
    fun shutdown() {
        try {
            captureSession?.close()
            captureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            imageReader?.close()
            imageReader = null
            
            stopBackgroundThread()
            
            Log.d(TAG, "Camera shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown: ${e.message}", e)
        }
    }
}

/**
 * Camera capabilities data class
 */
private data class CameraCapabilities(
    val cameraId: String,
    val characteristics: CameraCharacteristics,
    val maxYuvWidth: Int,
    val maxYuvHeight: Int,
    val minFocusDistance: Float,
    val supportsManualFocus: Boolean
)

/**
 * Minimal wrapper to provide Image data in a format compatible with ImageProxy
 * We avoid implementing the full ImageProxy interface to sidestep compatibility issues
 * Instead, we provide only the methods actually used by the tracking code
 */
private class ImageProxyWrapper(private val image: android.media.Image) : ImageProxy {
    
    override fun close() = image.close()
    
    override fun getCropRect(): android.graphics.Rect = image.cropRect
    
    override fun setCropRect(rect: android.graphics.Rect?) {
        rect?.let { image.cropRect = it }
    }
    
    override fun getFormat(): Int = image.format
    
    override fun getHeight(): Int = image.height
    
    override fun getWidth(): Int = image.width
    
    override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
        return image.planes.map { plane ->
            object : ImageProxy.PlaneProxy {
                override fun getRowStride(): Int = plane.rowStride
                override fun getPixelStride(): Int = plane.pixelStride
                override fun getBuffer(): ByteBuffer = plane.buffer
            }
        }.toTypedArray()
    }
    
    // These methods are not used by the particle tracking code but are required by the interface
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getImageInfo(): androidx.camera.core.ImageInfo {
        TODO("getImageInfo() not implemented - not needed for tracking")
    }
    
    override fun getImage(): android.media.Image? = image
}
