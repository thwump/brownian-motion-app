package com.brownian.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.brownian.tracker.camera.Camera2Manager
import com.brownian.tracker.databinding.ActivityMainBinding
import com.brownian.tracker.detector.DetectedParticle
import com.brownian.tracker.detector.ParticleDetector
import com.brownian.tracker.export.DataExporter
import com.brownian.tracker.physics.PhysicsEngine
import com.brownian.tracker.simulator.PhysicsSimulator
import com.brownian.tracker.tracker.ParticleTracker
import com.brownian.tracker.tracker.Vector2D
import com.brownian.tracker.ui.ChartType
import com.brownian.tracker.video.VideoFileLoader
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: Camera2Manager
    private lateinit var simulator: PhysicsSimulator
    private lateinit var videoLoader: VideoFileLoader
    private lateinit var dataExporter: DataExporter

    private val detector = ParticleDetector()
    private val tracker = ParticleTracker()
    private val physics = PhysicsEngine()

    private var activeMode = "intro" // "intro", "sim", "camera", "video"
    private var isPaused = false
    private var lastSimTimeNanos = SystemClock.elapsedRealtimeNanos()
    private var lastFrameTimestamp = SystemClock.elapsedRealtime()
    private var frameCounter = 0
    private var simScheduler: ScheduledExecutorService? = null
    private var simBitmap: Bitmap? = null
    private var lastCameraFrameBitmap: Bitmap? = null
    private var isUpdatingFromCode = false

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadAndProcessVideoFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 960x1280 (3:4 portrait) to match camera sensor aspect ratio (3072x4080)
        simulator = PhysicsSimulator(960, 1280)
        videoLoader = VideoFileLoader(this)
        dataExporter = DataExporter(this)
        simBitmap = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888)

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
        setupIntroTabContent()

        // App starts directly on the Intro tab
        switchMode("intro")
    }

    private fun setupIntroTabContent() {
        val physicsHtml = """
            In 1908, French physicist <b>Jean Perrin</b> measured the random thermal motion of microscopic resin spheres suspended in water. Using <b>Albert Einstein's 1905 diffusion equation</b>:
            <br><br>
            &nbsp;&nbsp;&nbsp;&nbsp;<b><i>D</i> = (<i>k</i><sub>B</sub> · <i>T</i>) / (6 · π · η · <i>a</i>)</b>
            <br><br>
            Perrin extracted <b>Boltzmann's constant (<i>k</i><sub>B</sub>)</b> and <b>Avogadro's number (<i>N</i><sub>A</sub>)</b>, winning the 1926 Nobel Prize in Physics for proving the physical existence of atoms.
            <br><br>
            This app uses <b>Nanoparticle Tracking Analysis (NTA)</b> algorithms to let you replicate Perrin's Nobel Prize experiment using your smartphone!
        """.trimIndent()

        val equipmentHtml = """
            • <b>Microscope Lens:</b> Any clip-on smartphone microscope lens (e.g., 50× to 200× magnification).<br>
            • <b>Sample Liquids:</b> Diluted milk (fat globules 0.8–3.5 μm), polystyrene latex micro-beads, or starch particles in water.<br>
            • <b>Slide Preparation:</b> Place a single drop under a glass cover-slip. Ensure the slide is flat to minimize fluid drift.
        """.trimIndent()

        val quickstartHtml = """
            1. Tap the <b>🔬 Sim</b> tab to explore the real-time physics simulator.<br>
            2. Attach your clip-on lens &amp; tap the <b>📷 Camera</b> tab.<br>
            3. Turn on Torch, adjust Manual Focus &amp; Sensitivity sliders.<br>
            4. Tap <b>Start</b> to track trajectories and watch <i>k</i><sub>B</sub> and <i>N</i><sub>A</sub> converge!
        """.trimIndent()

        binding.tvIntroPhysicsText.text = Html.fromHtml(physicsHtml, Html.FROM_HTML_MODE_LEGACY)
        binding.tvIntroEquipmentText.text = Html.fromHtml(equipmentHtml, Html.FROM_HTML_MODE_LEGACY)
        binding.tvIntroQuickstartText.text = Html.fromHtml(quickstartHtml, Html.FROM_HTML_MODE_LEGACY)

        // Clickable Wikipedia Web Resource Buttons embedded contextually under each section
        binding.btnWikiPerrin.setOnClickListener { openUrl("https://en.wikipedia.org/wiki/Jean_Baptiste_Perrin") }
        binding.btnWikiEinstein.setOnClickListener { openUrl("https://en.wikipedia.org/wiki/Brownian_motion") }
        binding.btnWikiBoltzmann.setOnClickListener { openUrl("https://en.wikipedia.org/wiki/Boltzmann_constant") }
        binding.btnWikiAvogadro.setOnClickListener { openUrl("https://en.wikipedia.org/wiki/Avogadro_constant") }
        binding.btnWikiNTA.setOnClickListener { openUrl("https://en.wikipedia.org/wiki/Nanoparticle_tracking_analysis") }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNavigationTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                when (tab.position) {
                    0 -> switchMode("intro")
                    1 -> switchMode("sim")
                    2 -> switchMode("camera")
                    3 -> switchMode("video")
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })
    }

    private fun switchMode(mode: String) {
        activeMode = mode
        
        // Reset tracking state when switching modes so old tracks don't bleed across tabs
        tracker.reset()
        physics.resetAccumulators()
        binding.overlayView.updateData(emptyList(), emptyList(), 960, 1280)

        // Hide viewport container, action buttons, and metrics bar on Intro tab
        val isIntro = (mode == "intro")
        val controlsVisibility = if (isIntro) View.GONE else View.VISIBLE
        
        binding.viewportContainer.visibility = controlsVisibility
        binding.toolbarActionGrid.visibility = controlsVisibility
        binding.realtimeMetricsBar.visibility = controlsVisibility

        binding.panelIntro.visibility = if (mode == "intro") View.VISIBLE else View.GONE
        binding.panelSim.visibility = if (mode == "sim") View.VISIBLE else View.GONE
        binding.panelCamera.visibility = if (mode == "camera") View.VISIBLE else View.GONE
        binding.panelVideo.visibility = if (mode == "video") View.VISIBLE else View.GONE

        binding.viewFinder.visibility = if (mode == "camera") View.VISIBLE else View.GONE
        binding.simCanvas.visibility = if (mode == "sim") View.VISIBLE else View.GONE

        if (mode == "camera") {
            binding.tvStatusHud.text = "Tracking • Live Camera2 Sensor Stream"
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
        } else {
            // Intro or Video tab: stop simulator & camera background loops completely
            binding.tvStatusHud.text = if (mode == "intro") "Ready • Overview & Documentation" else "Ready • Video Analysis Mode"
            stopSimulationLoop()
            if (::cameraManager.isInitialized) cameraManager.shutdown()
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
            // Freeze video frame during calibration so finger drag doesn't shift the camera image
            isPaused = true
            binding.tvStatusHud.text = "Calibration • Frame Freezed"

            if (activeMode == "camera") {
                // Display last captured frame on simCanvas and hide live hardware preview SurfaceView to freeze 100% solid
                lastCameraFrameBitmap?.let { bmp ->
                    binding.simCanvas.setImageBitmap(bmp)
                    binding.simCanvas.visibility = View.VISIBLE
                    binding.viewFinder.visibility = View.GONE
                }
            }

            binding.layoutCalibrationBar.visibility = View.VISIBLE
            binding.overlayView.isCalibrationMode = true
            
            updateCalibrationReadout(binding.overlayView.getCalibrationDistancePx())
            Toast.makeText(this, "Calibration Mode: Drag touch handles to align line with 1mm mark, then tap Confirm.", Toast.LENGTH_LONG).show()
        }

        binding.btnConfirmCalibration.setOnClickListener {
            val distPx = binding.overlayView.getCalibrationDistancePx()
            if (distPx <= 10f) {
                Toast.makeText(this, "Line is too short. Drag handles to set calibration line length.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val microns = binding.etCalibMicrons.text.toString().toDoubleOrNull() ?: 1000.0
            val baseScale = (microns / distPx).toFloat()
            val currentZoom = binding.overlayView.currentZoomRatio
            val effectiveScale = baseScale / currentZoom

            binding.overlayView.baseScaleMicronsPerPixel = baseScale
            physics.scaleMicronsPerPixel = effectiveScale
            simulator.scaleMicronsPerPixel = effectiveScale

            binding.overlayView.isCalibrationMode = false
            binding.layoutCalibrationBar.visibility = View.GONE
            isPaused = false
            binding.tvStatusHud.text = "Tracking • Active"

            if (activeMode == "camera") {
                binding.simCanvas.visibility = View.GONE
                binding.viewFinder.visibility = View.VISIBLE
            }

            Toast.makeText(this, String.format("✅ Scale Calibrated! 1.0x: %.3f μm/px | Effective (%.1fx): %.3f μm/px", baseScale, currentZoom, effectiveScale), Toast.LENGTH_LONG).show()
        }

        binding.btnCancelCalibration.setOnClickListener {
            binding.overlayView.isCalibrationMode = false
            binding.layoutCalibrationBar.visibility = View.GONE
            isPaused = false
            binding.tvStatusHud.text = "Tracking • Active"

            if (activeMode == "camera") {
                binding.simCanvas.visibility = View.GONE
                binding.viewFinder.visibility = View.VISIBLE
            }
        }

        binding.switchShowOverlay.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.isOverlayEnabled = isChecked
        }

        binding.switchMilkMode.setOnCheckedChangeListener { _, isChecked ->
            simulator.isPolydisperse = isChecked
            simulator.initParticles()
            physics.resetAccumulators()
            tracker.reset()
            
            binding.seekBarSize.isEnabled = !isChecked
            binding.etSizeInput.isEnabled = !isChecked
        }

        binding.switchDriftFix.setOnCheckedChangeListener { _, isChecked ->
            tracker.enableDriftCorrection = isChecked
        }

        binding.btnToggleTorch.setOnClickListener {
            if (::cameraManager.isInitialized) {
                val torchOn = cameraManager.toggleTorch()
                Toast.makeText(this, if (torchOn) "Flashlight ON" else "Flashlight OFF", Toast.LENGTH_SHORT).show()
            }
        }

        // Manual focus controls
        binding.seekBarFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::cameraManager.isInitialized) {
                    val maxFocus = cameraManager.getMaxFocusDistance()
                    val focusDistance = (progress / 100.0f) * maxFocus
                    cameraManager.setManualFocus(focusDistance)
                    binding.tvFocusLabel.text = String.format("Focus: %.2f D (%.0f%% close)", focusDistance, progress.toFloat())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnLockFocus.setOnClickListener {
            if (::cameraManager.isInitialized) {
                val progress = binding.seekBarFocus.progress
                val maxFocus = cameraManager.getMaxFocusDistance()
                val focusDistance = (progress / 100.0f) * maxFocus
                cameraManager.setManualFocus(focusDistance)
                Toast.makeText(this, String.format("Focus Locked at %.2f D", focusDistance), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAutoFocus.setOnClickListener {
            if (::cameraManager.isInitialized) {
                cameraManager.unlockAutoFocus()
                binding.tvFocusLabel.text = "Focus: Continuous Autofocus"
                Toast.makeText(this, "Continuous Autofocus Enabled", Toast.LENGTH_SHORT).show()
            }
        }

        // Export Buttons
        binding.btnExportJSON.setOnClickListener { exportDataJSON() }
        binding.btnExportCSV.setOnClickListener { exportDataCSV() }
        binding.btnExportJSONCamera.setOnClickListener { exportDataJSON() }
        binding.btnExportCSVCamera.setOnClickListener { exportDataCSV() }
        binding.btnExportJSONVideo.setOnClickListener { exportDataJSON() }
        binding.btnExportCSVVideo.setOnClickListener { exportDataCSV() }

        // Particle Detection Sensitivity Tuning Controls
        binding.switchInvertPolarity.setOnCheckedChangeListener { _, isChecked ->
            detector.invert = isChecked
        }

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

        // Temperature, Viscosity, Drift, Particle Size Controls
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

        binding.seekBarZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1.0f + (progress / 100.0f) * 9.0f
                val baseScale = binding.overlayView.baseScaleMicronsPerPixel
                val effectiveScale = baseScale / zoom
                val fovMm = (effectiveScale * 1280.0) / 1000.0

                binding.tvZoomSliderLabel.text = String.format("Digital Sensor Crop Zoom: %.1fx (FOV: ~%.2f mm)", zoom, fovMm)
                binding.overlayView.currentZoomRatio = zoom

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
        binding.overlayView.onCalibrationLineChanged = { distPx ->
            updateCalibrationReadout(distPx)
        }

        binding.etCalibMicrons.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val distPx = binding.overlayView.getCalibrationDistancePx()
                updateCalibrationReadout(distPx)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun updateCalibrationReadout(distPx: Float) {
        val microns = binding.etCalibMicrons.text.toString().toDoubleOrNull() ?: 1000.0
        val liveScale = if (distPx > 0f) (microns / distPx) else 0.0
        binding.tvCalibrationReadout.text = String.format("Line Length: %.1f px | Live Scale (1.0x): %.3f μm/px", distPx, liveScale)
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
                    binding.overlayView.updateData(detections, tracks, 960, 1280)
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
        cameraManager = Camera2Manager(
            context = this,
            lifecycleOwner = this,
            surfaceView = binding.viewFinder
        ) { imageProxy ->
            if (isPaused || activeMode != "camera") {
                imageProxy.close()
                return@Camera2Manager
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

            // Convert YUV frame to Bitmap to store snapshot for 100% frozen calibration
            if (frameCounter % 2 == 0) {
                lastCameraFrameBitmap = yuvToBitmap(yBuffer, width, height, rowStride)
            }

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

    private fun yuvToBitmap(yBuffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): Bitmap? {
        return try {
            val yData = ByteArray(yBuffer.remaining())
            yBuffer.get(yData)
            yBuffer.rewind()

            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * rowStride
                val outOffset = y * width
                for (x in 0 until width) {
                    val gray = yData[rowOffset + x].toInt() and 0xFF
                    pixels[outOffset + x] = 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
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
        
        val refRadius = if (isPoly) simulator.getHarmonicMeanRadiusMicrons() else simulator.particleRadiusMicrons
        val displayMeanDiam = if (isPoly) simulator.getArithmeticMeanDiameterMicrons() else (simulator.particleRadiusMicrons * 2.0)

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
            updateAnalyticsDashboard(cumul.D_converged, refRadius, simulator.tempCelsius)
        }
    }

    private fun updateAnalyticsDashboard(
        measuredDiffusion: Double = 0.214, 
        particleRadius: Double = 1.0,
        knownTemperature: Double = 20.0
    ) {
        val allTracks = tracker.getAllTracks()
        val msdResult = physics.calculateMSD(allTracks, 20)
        val polyData = physics.calculatePolydisperseSizing(allTracks, simulator.tempCelsius, simulator.viscosityMpaSec)

        val constants = physics.calculateFundamentalConstants(
            measuredDiffusion = measuredDiffusion,
            knownTempCelsius = knownTemperature,
            knownViscosity = simulator.viscosityMpaSec,
            knownRadiusMicrons = particleRadius
        )

        when (activeMode) {
            "sim" -> {
                binding.msdChartView.updateMsdData(msdResult)
                binding.psdChartView.updateHistogramData(
                    ChartType.PSD_HISTOGRAM,
                    polyData.sizeHistogram.bins,
                    polyData.sizeHistogram.counts
                )
                updatePerrinDisplay(
                    binding.tvBoltzmannConstant,
                    binding.tvAvogadroNumber,
                    constants
                )
            }
            "camera" -> {
                binding.msdChartViewCamera.updateMsdData(msdResult)
                binding.psdChartViewCamera.updateHistogramData(
                    ChartType.PSD_HISTOGRAM,
                    polyData.sizeHistogram.bins,
                    polyData.sizeHistogram.counts
                )
                updatePerrinDisplay(
                    binding.tvBoltzmannConstantCamera,
                    binding.tvAvogadroNumberCamera,
                    constants
                )
            }
            "video" -> {
                binding.msdChartViewVideo.updateMsdData(msdResult)
                binding.psdChartViewVideo.updateHistogramData(
                    ChartType.PSD_HISTOGRAM,
                    polyData.sizeHistogram.bins,
                    polyData.sizeHistogram.counts
                )
                updatePerrinDisplay(
                    binding.tvBoltzmannConstantVideo,
                    binding.tvAvogadroNumberVideo,
                    constants
                )
            }
        }
    }

    private fun updatePerrinDisplay(
        tvBoltzmann: android.widget.TextView,
        tvAvogadro: android.widget.TextView,
        constants: com.brownian.tracker.physics.FundamentalConstantsResult
    ) {
        val kB_str = String.format("%.3e", constants.measuredBoltzmann)
        val kB_ref_str = String.format("%.3e", constants.referenceBoltzmann)
        val kB_error = String.format("%.1f", kotlin.math.abs(constants.boltzmannErrorPercent))
        
        val NA_str = String.format("%.3e", constants.measuredAvogadro)
        val NA_ref_str = String.format("%.3e", constants.referenceAvogadro)
        val NA_error = String.format("%.1f", kotlin.math.abs(constants.avogadroErrorPercent))

        tvBoltzmann.text = "k_B = $kB_str J/K (±$kB_error%) | True: $kB_ref_str J/K"
        tvAvogadro.text = "N_A = $NA_str /mol (±$NA_error%) | True: $NA_ref_str /mol"
    }

    private fun updateUI(D: Double, tempC: Double, meanDiam: Double, totalSteps: Long = 0, stdErr: Double = 0.0) {
        binding.tvDiffVal.text = String.format("%.3f μm²/s", D)
        binding.tvTempVal.text = String.format("%.1f °C (±%.1f%%, N=%d)", tempC, stdErr, totalSteps)
        binding.tvSizeVal.text = String.format("%.2f μm", meanDiam)
    }

    private fun exportDataJSON() {
        try {
            val tracks = tracker.getAllTracks()
            if (tracks.isEmpty()) {
                Toast.makeText(this, "No trajectory data to export. Record some data first!", Toast.LENGTH_SHORT).show()
                return
            }

            val isPoly = binding.switchMilkMode.isChecked
            val refRadius = if (isPoly) simulator.getHarmonicMeanRadiusMicrons() else simulator.particleRadiusMicrons
            val currentViscosity = simulator.viscosityMpaSec

            val driftPxPerSec = if (tracker.enableDriftCorrection) {
                Vector2D(tracker.bulkDriftVector.vx * 60f, tracker.bulkDriftVector.vy * 60f)
            } else {
                Vector2D(0f, 0f)
            }

            val cumul = physics.accumulateSteps(tracks, driftPxPerSec, refRadius, currentViscosity)
            val msdResult = physics.calculateMSD(tracks, 20)
            val polydisperseSizing = physics.calculatePolydisperseSizing(
                tracks, 
                simulator.tempCelsius, 
                currentViscosity, 
                driftPxPerSec
            )

            val experimentalParams = mapOf(
                "mode" to activeMode,
                "temperature_set_C" to simulator.tempCelsius,
                "viscosity_mPa_s" to currentViscosity,
                "particle_count" to simulator.numParticles,
                "drift_correction_enabled" to tracker.enableDriftCorrection,
                "polydisperse_mode" to isPoly,
                "mean_particle_radius_um" to refRadius,
                "drift_um_per_s" to simulator.driftMicronsPerSec,
                "detector_threshold" to detector.minThreshold,
                "detector_invert" to detector.invert
            )

            val file = dataExporter.exportToJSON(
                tracks = tracks,
                scaleMicronsPerPixel = physics.scaleMicronsPerPixel,
                tempCelsius = cumul.T_converged_C,
                diffusionCoeff = cumul.D_converged,
                polydisperseSizing = polydisperseSizing,
                msdPoints = msdResult.msdPoints,
                experimentalParams = experimentalParams
            )

            Toast.makeText(this, "✅ Exported: ${file.name}", Toast.LENGTH_SHORT).show()

            AlertDialog.Builder(this)
                .setTitle("Export Complete")
                .setMessage("Data exported to:\n${file.absolutePath}\n\nWould you like to share it?")
                .setPositiveButton("Share") { _, _ -> dataExporter.shareFile(file) }
                .setNegativeButton("Close", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportDataCSV() {
        try {
            val tracks = tracker.getAllTracks()
            if (tracks.isEmpty()) {
                Toast.makeText(this, "No trajectory data to export. Record some data first!", Toast.LENGTH_SHORT).show()
                return
            }

            val file = dataExporter.exportToCSV(
                tracks = tracks,
                scaleMicronsPerPixel = physics.scaleMicronsPerPixel
            )

            Toast.makeText(this, "✅ Exported: ${file.name}", Toast.LENGTH_SHORT).show()

            AlertDialog.Builder(this)
                .setTitle("Export Complete")
                .setMessage("Trajectory data exported to:\n${file.absolutePath}\n\nWould you like to share it?")
                .setPositiveButton("Share") { _, _ -> dataExporter.shareFile(file) }
                .setNegativeButton("Close", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
