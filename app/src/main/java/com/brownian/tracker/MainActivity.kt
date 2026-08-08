package com.brownian.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
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
import com.brownian.tracker.detector.DetectedParticle
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
    private var lastSimTimeNanos = SystemClock.elapsedRealtimeNanos()
    private var lastFrameTimestamp = SystemClock.elapsedRealtime()
    private var frameCounter = 0
    private var simScheduler: ScheduledExecutorService? = null
    private var simBitmap: Bitmap? = null
    private var isUpdatingFromCode = false

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

        // Explicitly set Fluid Drift = 0.0, Particle Radius = 1.0 μm (2.0 μm diam), and Drift Correction OFF by default
        simulator.driftMicronsPerSec = 0.0
        simulator.particleRadiusMicrons = 1.0
        tracker.enableDriftCorrection = false
        binding.switchDriftFix.isChecked = false

        // Synchronize initial physics engine scale to simulator scale (0.05 μm/px High-Mag)
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

        // -------------------------------------------------------------
        // PARTICLE DETECTION TUNING CONTROLS
        // -------------------------------------------------------------
        binding.switchInvertPolarity.setOnCheckedChangeListener { _, isChecked ->
            detector.invert = isChecked
        }

        // 1. Detection Contrast Sensitivity Threshold (0 to 200)
        binding.seekBarDetectThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingFromCode) {
                    detector.minThreshold = progress
                    isUpdatingFromCode = true
                    binding.etDetectThresholdInput.setText(progress.toString())
                    isUpdatingFromCode = false
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etDetectThresholdInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromCode) {
                    val thresh = s.toString().toIntOrNull()
                    if (thresh != null && thresh in 0..255) {
                        detector.minThreshold = thresh
                        isUpdatingFromCode = true
                        binding.seekBarDetectThreshold.progress = thresh.coerceIn(0, 200)
                        isUpdatingFromCode = false
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 2. Min Particle Radius (1 to 20 px)
        binding.seekBarMinRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingFromCode) {
                    val r = Math.max(1, progress)
                    detector.minParticleRadius = r
                    isUpdatingFromCode = true
                    binding.etMinRadiusInput.setText(r.toString())
                    isUpdatingFromCode = false
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etMinRadiusInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromCode) {
                    val r = s.toString().toIntOrNull()
                    if (r != null && r in 1..50) {
                        detector.minParticleRadius = r
                        isUpdatingFromCode = true
                        binding.seekBarMinRadius.progress = r.coerceIn(1, 20)
                        isUpdatingFromCode = false
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 3. Max Particle Radius (5 to 100 px)
        binding.seekBarMaxRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingFromCode) {
                    val r = Math.max(5, progress)
                    detector.maxParticleRadius = r
                    isUpdatingFromCode = true
                    binding.etMaxRadiusInput.setText(r.toString())
                    isUpdatingFromCode = false
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etMaxRadiusInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromCode) {
                    val r = s.toString().toIntOrNull()
                    if (r != null && r in 5..200) {
                        detector.maxParticleRadius = r
                        isUpdatingFromCode = true
                        binding.seekBarMaxRadius.progress = r.coerceIn(5, 100)
                        isUpdatingFromCode = false
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // -------------------------------------------------------------
        // BI-DIRECTIONAL SYNCHRONIZED CONTROLS: TEXT BOX + SEEKBAR
        // -------------------------------------------------------------

        // 1. TEMPERATURE (0°C to 60°C)
        binding.seekBarTemp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingFromCode) {
                    val temp = progress.toDouble()
                    simulator.tempCelsius = temp
                    isUpdatingFromCode = true
                    binding.etTempInput.setText(String.format("%.1f", temp))
                    isUpdatingFromCode = false
                    physics.resetAccumulators()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etTempInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromCode) {
                    val valTemp = s.toString().toDoubleOrNull()
                    if (valTemp != null && valTemp in 0.0..100.0) {
                        simulator.tempCelsius = valTemp
                        isUpdatingFromCode = true
                        binding.seekBarTemp.progress = valTemp.toInt().coerceIn(0, 60)
                        isUpdatingFromCode = false
                        physics.resetAccumulators()
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 2. VISCOSITY (0.1 mPa·s to 10.0 mPa·s)
        binding.seekBarVisc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingFromCode) {
                    val visc = Math.max(0.1, progress / 10.0)
                    simulator.viscosityMpaSec = visc
                    isUpdatingFromCode = true
                    binding.etViscInput.setText(String.format("%.2f", visc))
                    isUpdatingFromCode = false
                    physics.resetAccumulators()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etViscInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromCode) {
                    val valVisc = s.toString().toDoubleOrNull()
                    if (valVisc != null && valVisc in 0.05..50.0) {
                        simulator.viscosityMpaSec = valVisc
                        isUpdatingFromCode = true
                        binding.seekBarVisc.progress = (valVisc * 10.0).toInt().coerceIn(0, 100)
                        isUpdatingFromCode = false
                        physics.resetAccumulators()
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 3. FLUID DRIFT (0.0 μm/s to 3.0 μm/s)
        binding.seekBarDrift.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingFromCode) {
                    val drift = progress / 10.0
                    simulator.driftMicronsPerSec = drift
                    isUpdatingFromCode = true
                    binding.etDriftInput.setText(String.format("%.2f", drift))
                    isUpdatingFromCode = false
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etDriftInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromCode) {
                    val valDrift = s.toString().toDoubleOrNull()
                    if (valDrift != null && valDrift in 0.0..10.0) {
                        simulator.driftMicronsPerSec = valDrift
                        isUpdatingFromCode = true
                        binding.seekBarDrift.progress = (valDrift * 10.0).toInt().coerceIn(0, 30)
                        isUpdatingFromCode = false
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 4. MEAN PARTICLE SIZE CONTROL (Text + Slider)
        binding.seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isUpdatingFromCode) {
                    val diam = Math.max(0.2, (progress / 10.0))
                    val radius = diam / 2.0
                    simulator.particleRadiusMicrons = radius
                    simulator.initParticles()
                    isUpdatingFromCode = true
                    binding.etSizeInput.setText(String.format("%.2f", diam))
                    isUpdatingFromCode = false
                    physics.resetAccumulators()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.etSizeInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromCode) {
                    val valDiam = s.toString().toDoubleOrNull()
                    if (valDiam != null && valDiam in 0.1..20.0) {
                        val radius = valDiam / 2.0
                        simulator.particleRadiusMicrons = radius
                        simulator.initParticles()
                        isUpdatingFromCode = true
                        binding.seekBarSize.progress = (valDiam * 10.0).toInt().coerceIn(0, 100)
                        isUpdatingFromCode = false
                        physics.resetAccumulators()
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // CAMERA CROP ZOOM SLIDER
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
        lastSimTimeNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameTimestamp = SystemClock.elapsedRealtime()
        simScheduler = Executors.newSingleThreadScheduledExecutor()

        simScheduler?.scheduleAtFixedRate({
            if (isPaused || activeMode != "sim") return@scheduleAtFixedRate

            val nowClock = SystemClock.elapsedRealtime()
            val frameDeltaMs = Math.max(1L, nowClock - lastFrameTimestamp)
            lastFrameTimestamp = nowClock

            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val dtSec = Math.max(0.005, Math.min(0.05, (nowNanos - lastSimTimeNanos) / 1e9))
            lastSimTimeNanos = nowNanos
            frameCounter++

            val nowSec = nowNanos / 1e9
            val liveFps = Math.max(1, (1000.0 / frameDeltaMs.toDouble()).toInt())

            simulator.update(dtSec)

            // Direct Floating-Point Particle Coordinates in Simulation Mode (Eliminates Bitmap Rasterization Noise)
            val detections = simulator.particles.map { p ->
                DetectedParticle(
                    x = p.x.toFloat(),
                    y = p.y.toFloat(),
                    radius = (p.radiusMicrons / simulator.scaleMicronsPerPixel).toFloat(),
                    intensity = 255f
                )
            }
            val tracks = tracker.update(detections, nowSec)

            simBitmap?.let { bmp ->
                simulator.renderToBitmap(bmp)

                runOnUiThread {
                    binding.simCanvas.setImageBitmap(bmp)
                    binding.overlayView.updateData(detections, tracks, 1280, 720)
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

            val nowSec = SystemClock.elapsedRealtimeNanos() / 1e9
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
            val tracks = tracker.update(detections, SystemClock.elapsedRealtimeNanos() / 1e9)
            binding.overlayView.updateData(detections, tracks, width, bmp.height)
            processAnalytics(tracks)
        }
    }

    private fun processAnalytics(tracks: List<com.brownian.tracker.tracker.ParticleTrack>) {
        val isPoly = binding.switchMilkMode.isChecked
        
        // Pass EXACT Harmonic Mean Radius of current particles when in polydisperse milk mode
        val refRadius = if (isPoly) simulator.getHarmonicMeanRadiusMicrons() else simulator.particleRadiusMicrons
        val displayMeanDiam = if (isPoly) simulator.getArithmeticMeanDiameterMicrons() else (simulator.particleRadiusMicrons * 2.0)

        // Synchronize scaleMicronsPerPixel and viscosityMpaSec between simulator and physics engine
        physics.scaleMicronsPerPixel = simulator.scaleMicronsPerPixel
        val currentViscosity = simulator.viscosityMpaSec

        val driftPxPerSec = if (tracker.enableDriftCorrection) {
            Vector2D(
                tracker.bulkDriftVector.vx * 60f,
                tracker.bulkDriftVector.vy * 60f
            )
        } else {
            Vector2D(0f, 0f)
        }

        val cumul = physics.accumulateSteps(tracks, driftPxPerSec, refRadius, currentViscosity)

        runOnUiThread {
            updateUI(cumul.D_converged, cumul.T_converged_C, displayMeanDiam, cumul.totalSteps, cumul.stdErrPercent)
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
