package com.brownian.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.brownian.tracker.camera.CameraXManager
import com.brownian.tracker.databinding.ActivityMainBinding
import com.brownian.tracker.detector.ParticleDetector
import com.brownian.tracker.physics.PhysicsEngine
import com.brownian.tracker.tracker.ParticleTracker
import com.brownian.tracker.tracker.Vector2D

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraXManager

    private val detector = ParticleDetector()
    private val tracker = ParticleTracker()
    private val physics = PhysicsEngine()

    private var isPaused = false
    private var lastFrameTimestamp = SystemClock.elapsedRealtime()
    private var frameCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUIControls()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setupUIControls() {
        binding.switchMilkMode.setOnCheckedChangeListener { _, isChecked ->
            physics.resetAccumulators()
            tracker.reset()
        }

        binding.switchDriftFix.setOnCheckedChangeListener { _, isChecked ->
            tracker.enableDriftCorrection = isChecked
        }

        binding.btnToggleTorch.setOnClickListener {
            val active = cameraManager.toggleTorch()
            binding.btnToggleTorch.isSelected = active
        }

        binding.btnResetTracks.setOnClickListener {
            tracker.reset()
            physics.resetAccumulators()
            updateUI(0.0, 20.0, 1.54)
        }
    }

    private fun startCamera() {
        cameraManager = CameraXManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.viewFinder
        ) { imageProxy ->
            if (isPaused) {
                imageProxy.close()
                return@CameraXManager
            }

            val nowTime = SystemClock.elapsedRealtime()
            val dtSec = Math.max(0.001, (nowTime - lastFrameTimestamp) / 1000.0)
            lastFrameTimestamp = nowTime
            frameCounter++

            if (frameCounter % 15 == 0) {
                val fps = Math.round(1.0 / dtSec).toInt()
                runOnUiThread {
                    binding.tvFpsHud.text = "$fps FPS"
                }
            }

            // Extract Y-plane luminance buffer directly
            val plane = imageProxy.planes[0]
            val yBuffer = plane.buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride

            val detections = detector.detectParticles(yBuffer, width, height, rowStride)
            val nowSec = System.currentTimeMillis() / 1000.0
            val tracks = tracker.update(detections, nowSec)

            // Update UI Overlay Canvas
            binding.overlayView.updateData(detections, tracks, width, height)

            // Analytics calculation every 10 frames
            if (frameCounter % 10 == 0) {
                val allTracks = tracker.getAllTracks()
                val isPoly = binding.switchMilkMode.isChecked
                val refRadius = if (isPoly) 0.77 else 1.0

                val driftPxPerSec = Vector2D(
                    tracker.bulkDriftVector.vx * 60f,
                    tracker.bulkDriftVector.vy * 60f
                )

                val cumul = physics.accumulateSteps(allTracks, driftPxPerSec, refRadius)

                runOnUiThread {
                    updateUI(cumul.D_converged, cumul.T_converged_C, if (isPoly) 1.54 else 2.0)
                }
            }

            imageProxy.close()
        }

        cameraManager.startCamera()
    }

    private fun updateUI(D: Double, tempC: Double, meanDiam: Double) {
        binding.tvDiffVal.text = String.format("%.3f μm²/s", D)
        binding.tvTempVal.text = String.format("%.1f °C", tempC)
        binding.tvSizeVal.text = String.format("%.2f μm", meanDiam)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required for microscope tracking.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
