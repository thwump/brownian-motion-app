package com.brownian.tracker.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager as HardwareCameraManager
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

            // 1. Locate Physical Main Rear Hardware Sensor ID (e.g. "0")
            val hardwareCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as HardwareCameraManager
            var physicalMainCameraId: String? = null

            try {
                for (id in hardwareCameraManager.cameraIdList) {
                    val characteristics = hardwareCameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        physicalMainCameraId = id
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Preview Use-case with Camera2Interop Physical Camera Lock
            val previewBuilder = Preview.Builder()
                .setTargetResolution(Size(640, 480))
            
            val previewInterop = Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

            physicalMainCameraId?.let { physId ->
                previewInterop.setPhysicalCameraId(physId)
            }

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 3. ImageAnalysis Use-case (Zero-copy YUV_420_888 stream)
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            val analysisInterop = Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)

            physicalMainCameraId?.let { physId ->
                analysisInterop.setPhysicalCameraId(physId)
            }

            val imageAnalyzer = analysisBuilder.build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    onFrameAnalyzer(imageProxy)
                }
            }

            // 4. Strict Lens Facing BACK Selector
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

                // HARDCODE OPTICAL ZOOM RATIO TO 1.0f TO PREVENT MULTI-LENS FLIPPING ON PIXEL 9
                cameraControl?.setZoomRatio(1.0f)

            } catch (e: Exception) {
                e.printStackTrace()
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
