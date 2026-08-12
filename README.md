# Brownian NTA Lab - Native Kotlin Android App

Native Android application built in **Kotlin** for real-time Brownian motion particle tracking, nanoparticle tracking analysis (NTA), and statistical mechanics parameter estimation on smartphones.

Optimized for **Google Pixel 9** and modern multi-camera Android devices.

---

## 🔒 Privacy Policy & Data Governance

**Brownian NTA Lab** is committed to 100% user privacy and data security:
- 📷 **Camera Access (`android.permission.CAMERA`)**: Used exclusively for real-time local particle tracking during active live sessions. No camera footage or images are recorded, stored, or transmitted over any network.
- 📁 **File & Storage Access**: Used via standard Android system pickers to let users select local pre-recorded video files for offline analysis and export trajectory datasets (`.json` / `.csv`) to local device storage.
- 🌐 **Offline & Privacy Compliant**: 100% offline, zero remote telemetry, zero analytics SDKs.

Read the full Privacy Policy live on GitHub Pages:
👉 **[https://thwump.github.io/brownian-motion-app/privacy.html](https://thwump.github.io/brownian-motion-app/privacy.html)** or view [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md).

---

## 🌟 Key Native Features

- **Hardware Physical Lens Locking**: Utilizes Android **CameraX** to lock `LENS_FACING_BACK` and optical `zoom = 1.0f`, completely forbidding HAL3 multi-camera auto-switching when attached to clip-on microscope lenses.
- **Zero-Copy Image Analysis**: Processes YUV420_888 camera buffers directly on background execution threads with zero CPU bitmap copy overhead.
- **Joint Maximum Likelihood Estimator (MLE)**: Jointly estimates temperature $T$ (°C) and individual particle hydrodynamic diameters $d_i$ from position variances without assuming optical blob sizes.
- **Polydisperse Milk Mode**: Handles complex fluids with dynamic harmonic mean radius calculation ($a_{\text{harmonic}} = N / \sum(1/a_i)$) for accurate temperature extraction.
- **Bulk Flow Subtraction**: Real-time convective drift vector calculation and removal.
- **Hardware Torch Light Control**: Integrated toggle for smartphone LED flash.

---

## 🚀 Building & Running

### Prerequisites
- JDK 17 or JDK 21 (e.g. Android Studio JBR)
- Android SDK Platform 34

### Build Command
```bash
./gradlew assembleDebug
```

### USB ADB Deployment
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.brownian.tracker/.MainActivity
```
