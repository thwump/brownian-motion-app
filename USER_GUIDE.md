# User Guide: Calibration, Manual Focus, and Data Export

## New Features Added (v1.1)

### 1. 📏 Calibration Feature

**Why it's critical**: Without proper calibration, all measurements will be incorrect!

**How to calibrate:**

1. Place a ruler with millimeter markings under your microscope (or use a micrometer calibration slide)
2. Tap the **"Calibrate"** button
3. **Drag a line** on the screen over a known distance (e.g., 1mm on the ruler)
4. A dialog will appear showing the measured distance in pixels
5. **Enter the actual physical distance** in micrometers:
   - For 1mm = enter `1000`
   - For 2mm = enter `2000`
   - For 100μm = enter `100`
6. Tap **"Set Base Scale"**
7. The app will calculate and store your calibration: μm/pixel

**Tips:**
- Use the longest distance that fits in your field of view for best accuracy
- Recalibrate if you change digital zoom or microscope lens
- The calibration is preserved between recordings
- For a 200× lens on Pixel 9, expect ~0.005-0.010 μm/px

---

### 2. 🔍 Manual Focus Control (for Microscopy)

**Why it's important**: At 200× magnification, depth of field is only ~2 μm! Autofocus will constantly hunt and lose particles.

**How to use:**

Located in the **Camera tab**, scroll down to find:

**Manual Focus Slider (0.0 - 1.0)**
- **0.0** = Focused at infinity (far)
- **1.0** = Focused at minimum distance (close)
- Move slider to find the plane where particles are in focus

**Lock Focus Button**
- Once you find the right focus, tap **"Lock Focus"**
- This disables autofocus and maintains the current focal plane
- Critical for tracking Brownian motion over time!

**Re-enable AF Button**
- If you need to refocus, tap **"Re-enable AF"**
- Resets to autofocus mode

**Best Practice:**
1. Start with autofocus to get approximately in focus
2. Fine-tune with manual focus slider
3. Once particles are sharp and clear, **Lock Focus**
4. Begin data collection

**Note on Depth of Field:**
The manual focus gives you ~10-20 μm of adjustment range, which helps compensate for:
- Sample chamber thickness variations
- Microscope holder alignment
- Coverslip tilt

---

### 3. 📥 Data Export Features

**Export formats available:**

#### **JSON Export** (Recommended for research)
- Complete experimental session data
- Includes:
  - All particle trajectories (x, y, t)
  - MSD curve data points
  - Temperature extraction results
  - Diffusion coefficients
  - Particle size distribution (if polydisperse mode)
  - All experimental parameters
  - Calibration scale
  - Timestamps

**File location**: `Documents/BrownianNTA/brownian_nta_YYYY-MM-DD_HH-MM-SS.json`

#### **CSV Export** (For Excel/Python/MATLAB)
- Simplified trajectory data
- Format: `track_id, time_s, x_px, y_px, x_um, y_um`
- Easy to load in spreadsheets or analysis software

**File location**: `Documents/BrownianNTA/brownian_tracks_YYYY-MM-DD_HH-MM-SS.csv`

**How to export:**

1. Switch to the **Analytics tab**
2. Scroll to the bottom
3. Tap either **"Export JSON"** or **"Export CSV"**
4. A dialog will show the file path
5. Choose **"Share"** to send via email, Drive, etc.

**What to do with exported data:**

**For Python analysis:**
```python
import json
import pandas as pd

# Load JSON data
with open('brownian_nta_2026-08-11_14-30-00.json', 'r') as f:
    data = json.load(f)

# Extract temperature
temp = data['physics_results']['temperature_celsius']
print(f"Measured temperature: {temp}°C")

# Extract trajectories
tracks = data['trajectories']

# Load CSV directly
df = pd.read_csv('brownian_tracks_2026-08-11_14-30-00.csv')
```

**For Excel:**
- Open CSV file directly in Excel
- Create pivot tables for analysis
- Plot trajectories (x vs y scatter plot)
- Calculate statistics

---

## Complete Workflow for 200× Milk Experiment

### Sample Preparation:
1. Dilute milk 1:100 in distilled water
2. Place small drop on glass slide
3. Add coverslip (20 μm spacer recommended)
4. Let settle for 30-60 seconds

### Setup:
1. Attach 200× lens to Pixel 9
2. Place sample under phone
3. Enable LED torch (or use backlight)
4. Switch to **Camera** tab

### Calibration:
1. Place millimeter ruler under microscope
2. Tap **"Calibrate"**
3. Drag line over 1mm (or 2mm)
4. Enter `1000` (or `2000`) in dialog
5. Verify scale shows ~0.005-0.010 μm/px

### Focus Optimization:
1. Use manual focus slider to find particles
2. Particles should appear as dark spots ~40-80 pixels diameter
3. When sharp and clear, tap **"Lock Focus"**

### Detection Tuning:
1. Enable **"Show Overlay"** switch
2. Adjust **Detection Threshold** until particles are highlighted
3. Toggle **"Invert Polarity"** if needed (dark particles on bright background)
4. Aim for 10-30 detected particles

### Data Collection:
1. Enable **"Drift Correction"** switch
2. Tap **"Start"**
3. Wait 30 seconds for initial equilibration
4. Record for 2-5 minutes minimum
5. Watch temperature converge to room temperature

### Analysis & Export:
1. Switch to **Analytics** tab
2. Verify:
   - Temperature within ±3°C of room temp
   - Particle sizes 1-5 μm (typical for milk)
   - MSD curve is linear (R² > 0.95)
3. Tap **"Export JSON"** for complete data
4. Share via email or cloud storage

---

## Troubleshooting

**Temperature doesn't converge:**
- Check calibration (most common issue!)
- Ensure enough tracks (>10 particles, >100 steps each)
- Enable drift correction
- Wait longer (2-3 minutes minimum)

**Can't see particles:**
- Adjust detection threshold
- Toggle invert polarity
- Sample may be too dilute (or too concentrated)
- Check focus - move manual focus slider

**Particles keep disappearing:**
- Lock focus (they're moving out of focal plane)
- Sample chamber may be too thick
- Try thinner sample preparation

**Focus keeps drifting:**
- Tap "Lock Focus" after finding correct plane
- Phone may be heating up - let cool
- Mechanical mount may not be stable

**Export fails:**
- Need to record some data first
- Check phone storage space
- Try CSV export if JSON fails

---

## Advanced Tips

**For publication-quality data:**
- Calibrate with certified micrometer slide (±1% accuracy)
- Use thermocouple to verify room temperature
- Record for 10+ minutes
- Collect multiple datasets at different times
- Export JSON for reproducibility

**For teaching demonstrations:**
- Use polydisperse milk mode to show size distribution
- Export CSV for students to analyze
- Compare measured T to thermometer reading
- Demonstrate effect of changing temperature (warm/cool sample)

**For best results:**
- Use fresh milk (fat globules are more uniform)
- Control room temperature (minimize drafts/heating)
- Use anti-vibration surface
- Allow thermal equilibration before measurement

---

## Technical Notes

**Manual Focus Implementation:**
- Uses CameraX `setLinearZoom()` API
- Range: 0.0 (far) to 1.0 (close)
- Actual focus distance depends on camera hardware
- Provides ~10-20 μm effective adjustment range at 200×

**Calibration Storage:**
- Base scale stored in OverlayView
- Automatically adjusts for digital zoom
- Persists during session (not saved between app restarts)
- Future: Save to SharedPreferences

**Data Export Format:**
- JSON: RFC 8259 compliant
- CSV: RFC 4180 compliant (comma-separated)
- Timestamps: Unix milliseconds + ISO 8601 string
- All positions: Both pixels and micrometers provided

---

## Questions about depth of field with manual focus?

**Q: Can manual focus solve the depth of field problem completely?**

A: Partially. Manual focus helps by:
- ✅ Preventing autofocus hunting (critical!)
- ✅ Allowing fine adjustment to find optimal plane
- ✅ Providing ~10-20 μm range of adjustment
- ❌ BUT depth of field is still only ~2 μm at 200×

**Q: What does this mean practically?**

At 200× magnification:
- Particles 2-4 μm diameter will be larger than DOF
- You'll see particles moving in/out of focus (appears as brightness changes)
- Some particles will be untrackable if they spend too much time out of plane
- **This is OK!** The tracking algorithm handles this:
  - Tracks lost particles for up to 3 frames
  - Statistics computed only on valid tracks
  - Temperature extraction still accurate

**Q: How to maximize tracking success?**

1. **Use thin sample**: 10-20 μm chamber thickness (vs. 50-100 μm)
2. **Find the median plane**: Focus midway through sample depth
3. **Dilute appropriately**: So particles don't overlap vertically
4. **Accept 50-70% track retention**: This is normal for 200× microscopy

**Q: Should I get a mechanical focus stage?**

**Yes, highly recommended!** A threaded Z-axis stage will let you:
- Precisely control sample height
- Scan through depth to find best plane  
- Make finer adjustments than phone focus alone
- Maintain focus during long recordings

Your CAD/3D printing plan is the right approach!

**Typical DIY microscope stage:**
- M3 or M4 threaded rod (0.5mm pitch)
- 3D printed slide holder
- Spring-loaded base
- Total travel: 5-10 mm
- Precision: ~10 μm per turn

This will complement the manual focus feature perfectly!
