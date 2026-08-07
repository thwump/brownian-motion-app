package com.brownian.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.brownian.tracker.camera.CameraXManager
import com.brownian.tracker.databinding.ActivityMainBinding
import com.brownian.tracker.detector.ParticleDetector
import com.brownian.tracker.physics.PhysicsEngine
import com.brownian.tracker.simulator.PhysicsSimulator
import com.brownian.tracker.tracker.ParticleTracker
import com.brownian.tracker.tracker.Vector2D
import com.brownian.tracker.ui.ChartType
import com.brownian.tracker.video.VideoFileLoader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraXManager
    private lateinit var simulator: PhysicsSimulator
    private lateinit var videoLoader: VideoFileLoader

    private val detector = ParticleDetector()
    private val tracker = ParticleTracker()
    private val physics = PhysicsEngine()

    private var activeMode = "sim" // "sim", "camera", "video"
    private var isPaused = false
    private var lastSimTimeSec = System.currentTimeMillis() / 1000.0
    private var lastFrameTimestamp = SystemClock.elapsedRealtime()
    private var frameCounter = 0
    private var simScheduler: ScheduledExecutorService? = null
    private var simBitmap: Bitmap? = null

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadAndProcessVideoFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // HD 1280x720 Processing & Simulation Buffer for real-time 60 FPS performance
        simulator = PhysicsSimulator(1280, 720)
        videoLoader = VideoFileLoader(this)
        simBitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)

        // Explicitly set Fluid Drift = 0.0 and Drift Correction OFF by default
        simulator.driftMicronsPerSec = 0.0
        tracker.enableDriftCorrection = false
        binding.switchDriftFix.isChecked = false
        binding.seekBarDrift.progress = 0
        binding.tvDriftSliderLabel.text = "Fluid Drift: 0.00 μm/s"

        // Synchronize initial physics engine scale to simulator scale (0.3125 μm/px)
        physics.scaleMicronsPerPixel = simulator.scaleMicronsPerPixel
        binding.switchMilkMode.isChecked = false

        setupNavigationTabs()
        setupUIControls()
        setupCalibrationGesture()

        startSimulationLoop()
    }

    private fun setupNavigationTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                when (tab.position) {
                    0 -> switchMode("sim")
                    1 -> switchMode("camera")
                    2 -> switchMode("video")
                    3 -> switchMode("analytics")
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun switchMode(mode: String) {
        activeMode = mode
        binding.panelSim.visibility = if (mode == "sim") View.VISIBLE else View.GONE
        binding.panelCamera.visibility = if (mode == "camera") View.VISIBLE else View.GONE
        binding.panelVideo.visibility = if (mode == "video") View.VISIBLE else View.GONE
        binding.panelAnalytics.visibility = if (mode == "analytics") View.VISIBLE else View.GONE

        binding.viewFinder.visibility = if (mode == "camera") View.VISIBLE else View.GONE
        binding.simCanvas.visibility = if (mode == "sim") View.VISIBLE else View.GONE

        if (mode == "camera") {
            binding.tvStatusHud.text = "Tracking • Live CameraX Sensor Stream"
            stopSimulationLoop()
            if (allPermissionsGranted()) {
                startCameraX()
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        } else if (mode == "sim") {
            binding.tvStatusHud.text = "Tracking • Physics Simulator Active"
            detector.invert = true
            if (::cameraManager.isInitialized) cameraManager.shutdown()
            startSimulationLoop()
        } else if (mode == "analytics") {
            updateAnalyticsDashboard()
        }
    }

    private fun setupUIControls() {
        binding.btnStart.setOnClickListener {
            isPaused = false
            binding.tvStatusHud.text = "Tracking • Active"
        }

        binding.btnPause.setOnClickListener {
            isPaused = !isPaused
            binding.tvStatusHud.text = if (isPaused) "Paused" else "Tracking • Active"
        }

        binding.btnReset.setOnClickListener {
            tracker.reset()
            physics.resetAccumulators()
            simulator.initParticles()
            updateUI(0.214, 20.0, 2.0, 0, 100.0)
        }

        binding.btnCalibrate.setOnClickListener {
            binding.overlayView.isCalibrationMode = true
            Toast.makeText(this, "Calibration: Drag a line on the video matching a known physical distance (e.g. 1000 μm for 1mm ruler mark).", Toast.LENGTH_LONG).show()
        }

        binding.switchShowOverlay.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.isOverlayEnabled = isChecked
        }

        binding.switchMilkMode.setOnCheckedChangeListener { _, isChecked ->
            simulator.isPolydisperse = isChecked
            simulator.initParticles()
            physics.resetAccumulators()
            tracker.reset()
        }

        binding.switchDriftFix.setOnCheckedChangeListener { _, isChecked ->
            tracker.enableDriftCorrection = isChecked
        }

        binding.btnToggleTorch.setOnClickListener {
            if (::cameraManager.isInitialized) {
                cameraManager.toggleTorch()
            }
        }

        binding.seekBarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1.0f + (progress / 100.0f) * 9.0f // 1.0x to 10.0x
                val baseScale = binding.overlayView.baseScaleMicronsPerPixel
                val effectiveScale = baseScale / zoom
                val fovMm = (effectiveScale * 1280.0) / 1000.0

                binding.tvZoomSliderLabel.text = String.format("Digital Sensor Crop Zoom: %.1fx (FOV: ~%.2f mm)", zoom, fovMm)
                binding.overlayView.currentZoomRatio = zoom

                // Synchronize physics engine and simulator scale to current zoom level
                physics.scaleMicronsPerPixel = effectiveScale
                simulator.scaleMicronsPerPixel = effectiveScale

                if (::cameraManager.isInitialized) {
                    cameraManager.setZoomRatio(zoom)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSelectVideo.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }

        binding.seekBarTemp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                simulator.tempCelsius = progress.toDouble()
                binding.tvTempSliderLabel.text = String.format("Temperature: %.1f °C", simulator.tempCelsius)
                physics.resetAccumulators()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekBarVisc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                simulator.viscosityMpaSec = Math.max(0.1, progress / 10.0)
                binding.tvViscSliderLabel.text = String.format("Fluid Viscosity: %.2f mPa·s", simulator.viscosityMpaSec)
                physics.resetAccumulators()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekBarDrift.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                simulator.driftMicronsPerSec = progress / 10.0
                binding.tvDriftSliderLabel.text = String.format("Fluid Drift: %.2f μm/s", simulator.driftMicronsPerSec)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupCalibrationGesture() {
        binding.overlayView.onCalibrationComplete = { distPx ->
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Enter Calibration Distance")
            builder.setMessage(String.format("Measured line: %.1f pixels at 1.0x zoom.\nEnter physical distance in micrometers (e.g. 1000 μm for 1mm ruler mark):", distPx))

            val input = android.widget.EditText(this)
            input.setText("1000.0") // Default to 1mm (1000 μm) ruler mark
            builder.setView(input)

            builder.setPositiveButton("Set Base Scale") { _, _ ->
                val microns = input.text.toString().toDoubleOrNull() ?: 1000.0
                val baseScale = (microns / distPx).toFloat()
                
                binding.overlayView.baseScaleMicronsPerPixel = baseScale
                val currentZoom = binding.overlayView.currentZoomRatio
                val effectiveScale = baseScale / currentZoom

                physics.scaleMicronsPerPixel = effectiveScale
                simulator.scaleMicronsPerPixel = effectiveScale
                Toast.makeText(this, String.format("Base Scale set! 1.0x: %.3f μm/px | Effective (%.1fx): %.3f μm/px", baseScale, currentZoom, effectiveScale), Toast.LENGTH_LONG).show()
            }
            builder.setNegativeButton("Cancel", null)
            builder.show()
        }
    }

    private fun startSimulationLoop() {
        stopSimulationLoop()
        lastSimTimeSec = System.currentTimeMillis() / 1000.0
        lastFrameTimestamp = SystemClock.elapsedRealtime()
        simScheduler = Executors.newSingleThreadScheduledExecutor()

        simScheduler?.scheduleAtFixedRate({
            if (isPaused || activeMode != "sim") return@scheduleAtFixedRate

            val nowClock = SystemClock.elapsedRealtime()
            val frameDeltaMs = Math.max(1L, nowClock - lastFrameTimestamp)
            lastFrameTimestamp = nowClock

            val nowSec = System.currentTimeMillis() / 1000.0
            val dtSec = Math.max(0.005, Math.min(0.05, nowSec - lastSimTimeSec))
            lastSimTimeSec = nowSec
            frameCounter++

            val liveFps = Math.max(1, (1000.0 / frameDeltaMs.toDouble()).toInt())

            simulator.update(dtSec)
            simBitmap?.let { bmp ->
                simulator.renderToBitmap(bmp)

                val (yBuffer, width) = videoLoader.bitmapToYBuffer(bmp)
                val height = bmp.height

                val detections = detector.detectParticles(yBuffer, width, height, width)
                val tracks = tracker.update(detections, nowSec)

                runOnUiThread {
                    binding.simCanvas.setImageBitmap(bmp)
                    binding.overlayView.updateData(detections, tracks, width, height)
                    binding.tvFpsHud.text = String.format("%d FPS", liveFps)
                }

                if (frameCounter % 5 == 0) {
                    processAnalytics(tracks)
                }
            }
        }, 0, 16, TimeUnit.MILLISECONDS)
    }

    private fun stopSimulationLoop() {
        simScheduler?.shutdown()
        simScheduler = null
    }

    private fun startCameraX() {
        cameraManager = CameraXManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.viewFinder
        ) { imageProxy ->
            if (isPaused || activeMode != "camera") {
                imageProxy.close()
                return@CameraXManager
            }

            val nowClock = SystemClock.elapsedRealtime()
            val frameDeltaMs = Math.max(1L, nowClock - lastFrameTimestamp)
            lastFrameTimestamp = nowClock
            val liveFps = Math.max(1, (1000.0 / frameDeltaMs.toDouble()).toInt())

            val nowSec = System.currentTimeMillis() / 1000.0
            frameCounter++

            val plane = imageProxy.planes[0]
            val yBuffer = plane.buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride

            val detections = detector.detectParticles(yBuffer, width, height, rowStride)
            val tracks = tracker.update(detections, nowSec)

            runOnUiThread {
                binding.overlayView.updateData(detections, tracks, width, height)
                binding.tvFpsHud.text = String.format("%d FPS", liveFps)
            }

            if (frameCounter % 5 == 0) {
                processAnalytics(tracks)
            }

            imageProxy.close()
        }

        cameraManager.startCamera()
    }

    private fun loadAndProcessVideoFile(uri: Uri) {
        Toast.makeText(this, "Loading video file for analysis...", Toast.LENGTH_SHORT).show()
        val bmp = videoLoader.extractFrameAtTime(uri, 1000000)
        if (bmp != null) {
            binding.simCanvas.setImageBitmap(bmp)
            binding.simCanvas.visibility = View.VISIBLE
            val (yBuffer, width) = videoLoader.bitmapToYBuffer(bmp)
            val detections = detector.detectParticles(yBuffer, width, bmp.height, width)
            val tracks = tracker.update(detections, System.currentTimeMillis() / 1000.0)
            binding.overlayView.updateData(detections, tracks, width, bmp.height)
            processAnalytics(tracks)
        }
    }

    private fun processAnalytics(tracks: List<com.brownian.tracker.tracker.ParticleTrack>) {
        val isPoly = binding.switchMilkMode.isChecked
        // Use exact harmonic mean radius for milk (0.7704 μm) or exact particle radius (1.0 μm)
        val refRadius = if (isPoly) 0.7704 else simulator.particleRadiusMicrons

        // Synchronize scaleMicronsPerPixel between simulator and physics engine
        physics.scaleMicronsPerPixel = simulator.scaleMicronsPerPixel

        val driftPxPerSec = if (tracker.enableDriftCorrection) {
            Vector2D(
                tracker.bulkDriftVector.vx * 60f,
                tracker.bulkDriftVector.vy * 60f
            )
        } else {
            Vector2D(0f, 0f)
        }

        val cumul = physics.accumulateSteps(tracks, driftPxPerSec, refRadius)

        runOnUiThread {
            updateUI(cumul.D_converged, cumul.T_converged_C, if (isPoly) 1.54 else (simulator.particleRadiusMicrons * 2), cumul.totalSteps, cumul.stdErrPercent)
            if (activeMode == "analytics") {
                updateAnalyticsDashboard()
            }
        }
    }

    private fun updateAnalyticsDashboard() {
        val allTracks = tracker.getAllTracks()
        val msdResult = physics.calculateMSD(allTracks, 20)
        binding.msdChartView.updateMsdData(msdResult)

        val polyData = physics.calculatePolydisperseSizing(allTracks, simulator.tempCelsius, simulator.viscosityMpaSec)

        binding.psdChartView.updateHistogramData(
            ChartType.PSD_HISTOGRAM,
            polyData.sizeHistogram.bins,
            polyData.sizeHistogram.counts
        )
    }

    private fun updateUI(D: Double, tempC: Double, meanDiam: Double, totalSteps: Long = 0, stdErr: Double = 0.0) {
        binding.tvDiffVal.text = String.format("%.3f μm²/s", D)
        binding.tvTempVal.text = String.format("%.1f °C (±%.1f%%, N=%d)", tempC, stdErr, totalSteps)
        binding.tvSizeVal.text = String.format("%.2f μm", meanDiam)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSimulationLoop()
        if (::cameraManager.isInitialized) cameraManager.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
