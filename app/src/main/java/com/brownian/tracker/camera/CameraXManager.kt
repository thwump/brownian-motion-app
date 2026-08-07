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
                        if (physSet.isNotEmpty()) {
                            targetPhysicalId = physSet.iterator().next()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraXManager", "Error querying physical camera IDs: ${e.message}")
            }

            // 2. High-Resolution Full HD Preview (1920x1080)
            val previewBuilder = Preview.Builder().setTargetResolution(Size(1920, 1080))
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

            // 3. High-Resolution ImageAnalysis Stream (1920x1080 Full HD YUV_420_888)
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(1920, 1080))
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

            // 4. Bind Use-Cases
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

                // LOCK OPTICAL ZOOM RATIO TO 1.0f
                cameraControl?.setZoomRatio(1.0f)

            } catch (e: Exception) {
                Log.e("CameraXManager", "Primary physical camera binding failed, retrying fallback: ${e.message}")
                try {
                    val fallbackPreview = Preview.Builder().setTargetResolution(Size(1920, 1080)).build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val fallbackAnalyzer = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1920, 1080))
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
                } catch (fallbackEx: Exception) {
                    Log.e("CameraXManager", "Fallback camera binding failed: ${fallbackEx.message}", fallbackEx)
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
