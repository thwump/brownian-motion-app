package com.brownian.tracker.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager as HardwareCameraManager
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class CameraXManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrameAnalyzer: (ImageProxy) -> Unit
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    var torchActive: Boolean = false
        private set

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // 1. Inspect Physical Sub-Camera IDs if available
            val hardwareCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as HardwareCameraManager
            var targetPhysicalId: String? = null

            try {
                for (id in hardwareCameraManager.cameraIdList) {
                    val characteristics = hardwareCameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val physSet = characteristics.physicalCameraIds
                        Log.d("CameraXManager", "Rear Camera ID $id physical sub-cameras: $physSet")
                        if (physSet.isNotEmpty()) {
                            targetPhysicalId = physSet.iterator().next()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraXManager", "Error querying physical camera IDs: ${e.message}")
            }

            Log.d("CameraXManager", "Target Physical Camera ID for lock: $targetPhysicalId")

            // 2. Build Preview
            val previewBuilder = Preview.Builder().setTargetResolution(Size(640, 480))
            val previewInterop = Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

            if (targetPhysicalId != null) {
                try {
                    previewInterop.setPhysicalCameraId(targetPhysicalId)
                } catch (e: Exception) {
                    Log.w("CameraXManager", "Could not set physical camera ID on preview: ${e.message}")
                }
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 3. Build ImageAnalysis
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            val analysisInterop = Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

            if (targetPhysicalId != null) {
                try {
                    analysisInterop.setPhysicalCameraId(targetPhysicalId)
                } catch (e: Exception) {
                    Log.w("CameraXManager", "Could not set physical camera ID on analysis: ${e.message}")
                }
            }

            val imageAnalyzer = analysisBuilder.build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    onFrameAnalyzer(imageProxy)
                }
            }

            // 4. Bind Use-Cases with Fail-Safe Fallback
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo

                // HARDCODE OPTICAL ZOOM RATIO TO 1.0f TO LOCK MAIN 1X SENSOR
                cameraControl?.setZoomRatio(1.0f)
                Log.d("CameraXManager", "Camera successfully bound to lifecycle!")

            } catch (e: Exception) {
                Log.e("CameraXManager", "Primary physical camera binding failed, retrying with fallback: ${e.message}")
                try {
                    // Fallback without physical ID override
                    val fallbackPreview = Preview.Builder().setTargetResolution(Size(640, 480)).build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val fallbackAnalyzer = ImageAnalysis.Builder()
                        .setTargetResolution(Size(480, 360))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build().also {
                            it.setAnalyzer(cameraExecutor) { imageProxy -> onFrameAnalyzer(imageProxy) }
                        }

                    cameraProvider?.unbindAll()
                    val fallbackCamera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        fallbackPreview,
                        fallbackAnalyzer
                    )
                    cameraControl = fallbackCamera?.cameraControl
                    cameraControl?.setZoomRatio(1.0f)
                    Log.d("CameraXManager", "Fallback camera successfully bound!")
                } catch (fallbackEx: Exception) {
                    Log.e("CameraXManager", "Fallback camera binding also failed: ${fallbackEx.message}", fallbackEx)
                }
            }

        }, ContextCompat.getMainExecutor(context))
    }

    fun toggleTorch(): Boolean {
        cameraControl?.let { control ->
            torchActive = !torchActive
            control.enableTorch(torchActive)
            return torchActive
        }
        return false
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
