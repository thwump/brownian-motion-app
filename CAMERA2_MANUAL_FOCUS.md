# Camera2 Manual Focus Implementation

## Overview

The app has been refactored from CameraX to Camera2 API to enable **true manual focus control** using focus distance in diopters. This provides precise control essential for microscopy applications.

## Key Changes

### 1. Camera2Manager (New)
- **File**: `app/src/main/java/com/brownian/tracker/camera/Camera2Manager.kt`
- **Purpose**: Direct Camera2 API implementation with full manual control
- **Features**:
  - Manual focus distance control (0.0 = infinity, maxFocusDistance = closest)
  - YUV_420_888 native resolution frames for particle tracking
  - Cross-device compatibility (Pixel, Samsung, etc.)
  - Automatic camera selection (chooses highest resolution back camera)
  - Digital zoom support (Android 11+ uses CONTROL_ZOOM_RATIO, older uses crop region)
  - Torch/flashlight control

### 2. Focus Distance Control

**Understanding Focus Distance (Diopters)**:
- **0.0 diopters** = Infinity focus (for distant objects, stars)
- **Higher values** = Closer focus
- **maxFocusDistance** = Closest possible focus (device-specific, typically 10-15 diopters)

**For Microscopy with 200× lens**:
- Use values close to **maxFocusDistance** for close-up focus
- Slider maps 0-100% to 0.0-maxFocusDistance range
- Example: If maxFocusDistance = 12.0, slider at 100% = 12.0 diopters (closest focus)

### 3. UI Changes

**Focus Slider** (0-100%):
- Directly controls focus distance in real-time
- No auto-revert on release (unlike CameraX version)
- Label shows actual diopter value and percentage

**Lock Focus Button**:
- Locks focus at current slider position
- Critical for stable microscopy measurements
- Toast shows locked focus distance

**Re-enable AF Button**:
- Returns to continuous autofocus
- Useful when not measuring

### 4. Device Compatibility

**Tested On**:
- ✅ Google Pixel 9 (minFocusDistance: ~12.0 diopters)

**Designed For Portability**:
- Samsung Galaxy devices
- OnePlus, Xiaomi, Motorola, etc.
- Any Android device with Camera2 API support (Android 8.0+)

**Fallback Behavior**:
- If manual focus not supported: `setManualFocus()` returns `false`
- Toast notifies user: "Manual focus not supported on this device"
- App continues to work with autofocus

### 5. Architecture Changes

**Before (CameraX)**:
```kotlin
CameraXManager(
    previewView: PreviewView  // CameraX widget
) {
    // Limited to FocusMeteringAction (point focus)
    // No direct focus distance control
}
```

**After (Camera2)**:
```kotlin
Camera2Manager(
    surfaceView: SurfaceView  // Standard Android view
) {
    // Full manual control via CaptureRequest
    set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
}
```

## Usage Instructions

### For 200× Microscopy Setup:

1. **Mount Phone with 200× Lens**:
   - Attach lens clip to phone
   - Position 200× lens over camera

2. **Frame Your Sample**:
   - Use preview to center milk fat globules or particles
   - Adjust sample distance (~2mm from lens typically)

3. **Manual Focus**:
   - Drag **Focus Slider** to **80-100%** (close focus range)
   - Fine-tune while watching preview for sharpest image
   - Tap **Lock Focus** to prevent autofocus hunting

4. **Start Tracking**:
   - Switch to Camera mode
   - Tap **Start Tracking**
   - System uses locked focus for stable measurements

5. **Export Data**:
   - After measurement completes
   - Use Export JSON or Export CSV buttons

### Focus Distance Examples:

| Device | Min Focus Distance | Slider Position | Use Case |
|--------|-------------------|-----------------|----------|
| Pixel 9 | 12.0 D | 100% = 12.0 D | Microscopy (closest) |
| Pixel 9 | 12.0 D | 50% = 6.0 D | Macro photography |
| Pixel 9 | 12.0 D | 0% = 0.0 D | Infinity (stars, distant objects) |
| Generic | 10.0 D | 100% = 10.0 D | Device-specific closest focus |

## Technical Details

### Camera Selection Logic:
1. Enumerate all cameras via `CameraManager.getCameraIdList()`
2. Filter for `LENS_FACING_BACK`
3. Check for YUV_420_888 support
4. Select camera with **largest YUV resolution** (best for microscopy)
5. Query `LENS_INFO_MINIMUM_FOCUS_DISTANCE` for focus range

### Image Pipeline:
```
Camera2 Device
  ↓
ImageReader (YUV_420_888, native resolution)
  ↓
ImageProxyWrapper (compatibility shim)
  ↓
ParticleDetector (existing code, unchanged)
  ↓
ParticleTracker → PhysicsEngine
```

### Preview vs Analysis:
- **Preview Surface**: 1920×1080 for UI display (lower res for performance)
- **Analysis Surface**: Full native resolution (e.g. 4080×3072 on Pixel 9)
- Both surfaces bound to same `CaptureSession` with shared focus/zoom settings

## Comparison: CameraX vs Camera2

| Feature | CameraX | Camera2 |
|---------|---------|---------|
| **Focus Control** | Point-based (AF lock at coordinates) | Direct distance in diopters |
| **Microscopy Suitability** | ❌ Poor (autofocus hunting) | ✅ Excellent (precise manual control) |
| **API Complexity** | Simple | Advanced |
| **Device Compatibility** | High-level abstraction | Direct hardware access |
| **Our Use Case** | **Not suitable** | **Ideal** |

## Troubleshooting

### Issue: "Manual focus not supported"
**Solution**: Device doesn't support manual focus (rare on modern phones). Try autofocus mode or test on different device.

### Issue: Focus slider doesn't change focus
**Check**:
1. Is "Auto Focus" enabled? (Disable it first)
2. Does device support manual focus? (Check toast after tapping Lock)
3. Try tapping **Lock Focus** after adjusting slider

### Issue: Image blurry at 200× magnification
**Solutions**:
1. Increase slider to 90-100% for closest focus
2. Adjust physical distance between lens and sample
3. Use bright illumination (LED torch button)
4. Consider mechanical Z-stage for fine adjustment

### Issue: App crashes on Samsung/other device
**Check**:
1. Logcat for Camera2 errors: `adb logcat -s Camera2Manager`
2. Device may have camera restrictions
3. Report issue with device model for compatibility fix

## Future Enhancements

**Possible Additions**:
1. **Focus Stacking**: Capture multiple focal planes, merge for extended depth-of-field
2. **Focus Peaking**: Visual aid showing in-focus regions (edge detection overlay)
3. **Motorized Z-Stage Integration**: Bluetooth/USB control of mechanical focus
4. **Focus Distance Presets**: Save optimal focus for specific lenses/samples
5. **Auto-Focus Bracketing**: Capture series at different focus distances

## Testing on Wife's Samsung Galaxy

**Before Testing**:
1. Install app: `adb install -r app-debug.apk`
2. Grant camera permission
3. Check focus range: Look for "maxFocusDistance" in logs

**Expected Behavior**:
- Manual focus should work (most Samsung devices support it)
- Focus range may differ from Pixel (e.g. 8.0-15.0 diopters)
- Slider automatically adapts to device's focus range

**Report Back**:
- Device model
- Focus distance range (shown in log or toast)
- Image quality with 200× lens
- Any crashes or errors

---

**Author**: GitHub Copilot  
**Date**: August 11, 2026  
**Commit**: Camera2 manual focus refactor for precise microscopy control
