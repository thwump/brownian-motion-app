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

        // Full HD High-Resolution 1920x1080 Processing & Simulation Buffer
        simulator = PhysicsSimulator(1920, 1080)
        videoLoader = VideoFileLoader(this)
        simBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)

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
            binding.tvStatusHud.text = "Tracking • Live CameraX Full HD (1920x1080)"
            stopSimulationLoop()
            if (allPermissionsGranted()) {
                startCameraX()
            } else {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            }
        } else if (mode == "sim") {
            binding.tvStatusHud.text = "Tracking • Physics Simulator Full HD Active"
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
            updateUI(0.0, 20.0, 1.54, 0, 100.0)
        }

        binding.btnCalibrate.setOnClickListener {
            binding.overlayView.isCalibrationMode = true
            Toast.makeText(this, "Calibration: Drag a line on the video matching a known physical distance (e.g. 10 μm).", Toast.LENGTH_LONG).show()
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
            builder.setMessage(String.format("Measured line: %.1f pixels.\nEnter physical distance in micrometers (μm):", distPx))

            val input = android.widget.EditText(this)
            input.setText("10.0")
            builder.setView(input)

            builder.setPositiveButton("Set Scale") { _, _ ->
                val microns = input.text.toString().toDoubleOrNull() ?: 10.0
                val scale = (microns / distPx).toFloat()
                physics.scaleMicronsPerPixel = scale
                simulator.scaleMicronsPerPixel = scale
                Toast.makeText(this, String.format("Calibration set! Scale: %.4f μm/px", scale), Toast.LENGTH_SHORT).show()
            }
            builder.setNegativeButton("Cancel", null)
            builder.show()
        }
    }

    private fun startSimulationLoop() {
        stopSimulationLoop()
        lastSimTimeSec = System.currentTimeMillis() / 1000.0
        simScheduler = Executors.newSingleThreadScheduledExecutor()

        simScheduler?.scheduleAtFixedRate({
            if (isPaused || activeMode != "sim") return@scheduleAtFixedRate

            val nowSec = System.currentTimeMillis() / 1000.0
            val dtSec = Math.max(0.005, Math.min(0.05, nowSec - lastSimTimeSec))
            lastSimTimeSec = nowSec
            frameCounter++

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

            val nowSec = System.currentTimeMillis() / 1000.0
            frameCounter++

            val plane = imageProxy.planes[0]
            val yBuffer = plane.buffer
            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride

            val detections = detector.detectParticles(yBuffer, width, height, rowStride)
            val tracks = tracker.update(detections, nowSec)

            binding.overlayView.updateData(detections, tracks, width, height)

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
        val refRadius = if (isPoly) 0.7704 else simulator.particleRadiusMicrons

        val driftPxPerSec = Vector2D(
            tracker.bulkDriftVector.vx * 60f,
            tracker.bulkDriftVector.vy * 60f
        )

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

        val isPoly = binding.switchMilkMode.isChecked
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
