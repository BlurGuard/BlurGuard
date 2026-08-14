![BlurGuard](docs/images/blurguard-splash.png "BlurGuard")

BlurGuard App
=============

This is the repository for **BlurGuard**, a native Android app that detects and anonymizes **faces** and **license plates** in real time, fully **on-device** and **offline**.

**BlurGuard** is a fully functional Android app built entirely with Kotlin and Jetpack Compose. It follows Android design and development best practices and is intended to be a useful reference for real-time, privacy-preserving camera pipelines. As a running app, it lets a user record video in which every face and every license plate is anonymized *before* the frame is ever previewed or encoded.

The core privacy rule of the project is a single sentence: **only anonymized video is ever previewed, encoded, or saved.** Raw camera frames never leave the engine, never reach a `ViewModel`, and are never written to disk.

> [!NOTE]
> This app is a fourth-year graduation project at the **Higher Institute for Applied Sciences and Technology (HIAST)**, Department of Informatics — Software and Artificial Intelligence, academic year 2025–2026.
> **Author:** Abd Al-Hadi Nashawati · **Supervisors:** Dr. Mustafa Al-Daqqaq, Eng. Marah Hassan.

# Features

**BlurGuard** turns the camera into a privacy-safe recorder:

- **Real-time detection** of faces and license plates using a single two-class YOLO detector running on-device.
- **Anonymized preview** — what you see on screen is already anonymized, so the user is never shown un-anonymized faces.
- **Anonymized recording only** — the encoder consumes the same processed surface as the preview, so an un-anonymized file cannot be produced.
- **Four anonymization modes**, selectable at runtime: `BLUR` (Gaussian), `PIXELATE`, `BLACK_BOX`, and `BOUNDING` (outline, for debugging and demos).
- **Stable anonymization between detector runs** — a ByteTrack multi-object tracker keeps identities stable so boxes do not flicker when the detector skips a frame.
- **Keep visible** — the user can mark a person as trusted so that person stays un-blurred. Recognition uses MobileFaceNet embeddings matched on-device. **License plates can never be kept visible.**
- **Fail-safe behavior** — whenever detection, tracking, or recognition is uncertain, the app anonymizes rather than reveals.
- **Fully offline** — no network permission is required for the pipeline; no frame, embedding, or inference request leaves the device.

## Screenshots

![Screenshot showing the camera screen, anonymization mode selection and the settings screen](docs/images/screenshots.png "Screenshot showing the camera screen, anonymization mode selection and the settings screen")

# Development Environment

**BlurGuard** uses the Gradle build system and can be imported directly into Android Studio (make sure you are using the latest stable version available [here](https://developer.android.com/studio)).

## 1. Prerequisites

| Requirement | Version / notes |
| --- | --- |
| Android Studio | Latest stable release |
| JDK | 17 or newer — the JDK bundled with Android Studio is recommended |
| Android SDK Platform | API 37 (compile SDK) |
| Android SDK Build Tools | Latest available |
| Minimum device version | Android 8.1 (API 27) |
| Java target | 11 |
| Device | A **physical Android device is strongly recommended**; emulators do not reproduce real camera timing |

## 2. Clone the repository

```bash
git clone <REPOSITORY_URL>
cd BlurGuard
```

> [!NOTE]
> The first Gradle sync needs internet access to download the Android, Kotlin, Compose, CameraX, Hilt, and LiteRT dependencies. After that, the app itself runs completely offline.

## 3. Run the app

Open the root `BlurGuard/` folder in Android Studio and wait for Gradle sync to finish. Change the run configuration to `app`, then press **Run**.

Or from the terminal:

```bash
./gradlew installDebug
adb shell monkey -p com.nash.blurguard 1
```

On Windows PowerShell:

```powershell
.\gradlew.bat installDebug
```

Grant the **camera** permission when prompted, and the **microphone** permission if you are recording audio.

# Technologies, tools and libraries

Everything below is required for the project to build and run correctly. All versions are declared in the Gradle version catalog under [`gradle/`](gradle).

| Area | Technology | Role in BlurGuard |
| --- | --- | --- |
| Language | **Kotlin** | Whole codebase |
| UI | **Jetpack Compose** | All screens and state rendering |
| Camera | **CameraX** (`Preview`, `VideoCapture`, `ImageAnalysis`, `SurfaceProcessor`) | Single camera session, frame delivery, and encoding |
| Inference | **TensorFlow Lite / LiteRT** with the **XNNPACK** delegate | Runs the detector, the landmark model, and the embedding model |
| Tracking | **ByteTrack** (implemented in-project) | Stable track IDs between detector runs |
| Rendering | **OpenGL ES** | GPU blur / pixelate / black-box / outline on every frame |
| Encoding | **MediaCodec** (through CameraX `VideoCapture`) | Encodes the processed surface only |
| Storage | **MediaStore**, key-value preferences | Saves anonymized videos; persists app settings |
| Concurrency | **Kotlin Coroutines + Flow** | Dispatchers, backpressure, and UI state streams |
| Dependency injection | **Hilt** | Binds engine interfaces to implementations |
| Build | **Gradle** with Kotlin DSL | Multi-module build |

> [!NOTE]
> The GPU is deliberately **reserved for rendering**. Inference runs on the CPU through XNNPACK. NNAPI/NPU delegation was measured on the target device and did not deliver a real speedup, so it is not used.

# Architecture

BlurGuard follows an **engine-as-internal-SDK** architecture, layered on top of the [official Android architecture guidance](https://developer.android.com/topic/architecture). The project structure is inspired by [Now in Android](https://github.com/android/nowinandroid) and the camera pipeline by [Jetpack Camera App](https://github.com/google/jetpack-camera-app).

The app is split into two areas:

1. **A normal Android app layer** — Compose screens, ViewModels, settings, app state, and dependency injection.
2. **A real-time privacy engine** — camera session ownership, frame analysis, on-device inference, tracking, recognition, GPU anonymization, and recording.

The app and feature modules talk to the engine **through `engine/api` only**. They must never touch CameraX internals, raw frames, LiteRT interpreters, OpenGL renderers, trackers, or encoders.

```text
Compose UI / ViewModels
        │  safe commands and state only
        ▼
     engine/api
        │
        ▼
     engine/impl ──┬── engine/camera
                   ├── engine/render
                   ├── engine/ml
                   ├── engine/tracking
                   └── engine/recognition
```

## Two data paths

The most important architectural rule is that BlurGuard has **two data paths that are never conflated**.

**1. UI-state path (slow, reactive).** Standard unidirectional data flow. Carries recording state, settings, warnings, and user actions — never pixels.

```text
Compose screen → CameraViewModel → engine/api → UiState (StateFlow) → Compose screen
```

**2. Frame path (fast, real-time).** Runs at 24–30 fps inside the engine and never touches a `ViewModel` or a `StateFlow`.

```text
Camera sensor → engine/camera → engine/render ─┬─→ anonymized Preview
                                               └─→ anonymized VideoCapture / MediaCodec

ImageAnalysis (640×360 RGBA, parallel branch)
   → engine/ml       detection (YOLO, ~every 2nd frame)
   → engine/tracking  ByteTrack stable IDs
   → engine/recognition  MobileFaceNet keep-visible decision
   → engine/impl      decides blur vs. keep visible
   → engine/render    consumes the latest metadata
```

Rendering runs on **every** frame on the GPU and interpolates box positions from tracker output between detector runs. Analysis uses `KEEP_ONLY_LATEST` backpressure, so stale frames are dropped instead of queued.

## Privacy invariants

These are enforced by the module structure, not by discipline:

1. App and feature modules consume the engine only through `engine/api`.
2. Only `engine/camera` may write video, and it writes only the processed surface output.
3. Raw frames are never written to disk, cache, MediaStore, logs, or crash reports — not even temporarily.
4. Raw frames never flow through a `ViewModel`, `StateFlow`, or Compose state.
5. `engine/api` never exposes `ImageProxy`, `Bitmap`, YUV byte arrays, raw `Surface`, LiteRT interpreters, or renderer/encoder internals.
6. Inference and face embeddings stay on-device.
7. When detection, tracking, or recognition is uncertain, the pipeline **fails safe toward anonymizing**.
8. License plates are never eligible for keep-visible.

# Modularization

The app is fully modularized. Dependencies point inward and downward only; cycles are forbidden.

## Project structure

```text
BlurGuard/
├── app/                      # Entry point, navigation, DI composition root
├── feature/
│   ├── camera/               # Camera screen, recording controls, CameraViewModel
│   └── settings/             # Anonymization mode and permission settings
├── core/
│   ├── common/               # Dispatchers, Result types, shared utilities
│   ├── data/                 # Settings persistence and safe media access
│   ├── designsystem/         # Compose theme, colors, typography, components
│   └── model/                # Shared immutable models (leaf module)
├── engine/
│   ├── api/                  # Public engine contract consumed by app + features
│   ├── impl/                 # Pipeline orchestration and Hilt bindings
│   ├── camera/               # CameraX session — the SOLE video writer
│   ├── render/               # OpenGL ES anonymization rendering
│   ├── ml/                   # LiteRT model wrappers (detector, landmarks, embeddings)
│   ├── tracking/             # ByteTrack multi-object tracking
│   └── recognition/          # Keep-visible identity and trust policy
├── docs/                     # Project report and high-resolution figures
├── gradle/                   # Version catalog and Gradle wrapper
├── build.gradle.kts          # Root build file
├── settings.gradle.kts       # Module registration
└── README.md
```

## Module responsibilities

| Module | Responsibility | Key types |
| --- | --- | --- |
| `app` | Application shell and composition root: `Application`, `MainActivity`, navigation, Hilt graph, binding real engine implementations, app-wide offline policy. Contains **no** camera, ML, or rendering logic. | `BlurGuardApplication`, `MainActivity`, DI modules |
| `feature/camera` | The recording screen: Compose UI, permission UI, recording controls, preview host, warning banners, keep-visible interactions, and screen state. Calls the engine through `engine/api`. **Never owns raw frames.** | `CameraViewModel`, `CameraUiState` |
| `feature/settings` | User-facing configuration: anonymization mode selection and permission state. Persists through `core/data`, not UI state. | `SettingsViewModel` |
| `core/common` | Shared infrastructure safe for any layer: dispatcher providers, coroutine helpers, result/error types, safe logging. No Android UI, CameraX, ML, or engine dependencies. | `DispatcherProvider` |
| `core/data` | Non-hot-path persistence: settings read/write and safe media abstractions for saved anonymized videos. Never handles per-frame data. | `SettingsRepository`, `MediaRepository` |
| `core/designsystem` | Compose theme, color system, typography, and reusable components. No business logic. | `BlurGuardTheme` |
| `core/model` | Shared immutable value types and safe cross-module contracts. Leaf module — no implementations, no Android UI, no LiteRT, no OpenGL. | `BoundingBox`, `Detection`, `DetectionBox`, `TrackedObject`, `TrackedBox`, `FaceEmbedding`, `AnonymizationMode`, `DetectionClass`, `AppSettings` |
| `engine/api` | The public contract of the internal engine: engine commands, safe recording state, warnings, configuration, and safe preview targets. Exposes **no** implementation types. | `BlurGuardEngine`, `EngineConfig`, `RecordingRequest`, `RecordingState`, `EngineWarning`, `Detector`, `Tracker`, `FaceRecognizer` |
| `engine/impl` | Orchestrates the real-time pipeline: coordinates camera, render, ML, tracking, and recognition; maps API requests to internal operations; emits safe state; installs Hilt bindings; enforces fail-safe behavior. | `DefaultBlurGuardEngine`, `DefaultAnonymizationPipeline` |
| `engine/camera` | Owns the CameraX session: `Preview`, `ImageAnalysis`, and `VideoCapture` binding, `SurfaceProcessor` integration, recording event mapping, and executor management. **The only module allowed to write video, and only the processed output.** | `CameraController`, `ProcessedVideoWriter` |
| `engine/render` | GPU anonymization on the frame path: Gaussian blur, pixelation, black box, and outline rendering through OpenGL ES. Compose never renders anonymized pixels itself. | `FrameRenderer` |
| `engine/ml` | All on-device model runtime code: LiteRT interpreter creation, delegate selection, YOLO detection, output decoding, face landmarks, alignment preprocessing, and embedding extraction. No LiteRT dependency exists outside this module. | `YoloDetector`, `YoloOutputDecoder`, `MobileFaceNetRecognizer` |
| `engine/tracking` | Multi-object tracking: stable track IDs across frames, track lifecycle, detection-to-track association, and lost-frame handling. | `ByteTrackTracker` |
| `engine/recognition` | Identity and keep-visible policy: trusted-face session state, candidate selection, verification and re-verification, quality gates, and fail-safe trust decisions. | `TrustedFaceStore` |

> [!NOTE]
> `engine/ml` answers *"what embedding does this face produce?"*. `engine/recognition` answers *"is this person trusted and allowed to stay visible?"*. `engine/recognition` does not depend on `engine/ml` directly — the concrete recognizer is wired by `engine/impl`.

## Dependency rules

Allowed:

```text
app                → feature/*, core/*, engine/api, engine/impl (DI only)
feature/*          → core/*, engine/api
engine/impl        → engine/api, engine/{camera,render,ml,tracking,recognition}, core/*
engine/camera      → engine/api, core/model, core/common
engine/render      → engine/api, core/model, core/common
engine/ml          → engine/api, core/model, core/common
engine/tracking    → engine/api, core/model, core/common
engine/recognition → engine/api, core/model, core/common
core/data          → core/model, core/common
core/designsystem  → core/model, core/common
core/model         → external libraries only
core/common        → external libraries only
```

Forbidden:

```text
feature/*                    → engine/impl or any engine implementation module
feature/*                    → another feature/*
engine/api                   → engine implementation modules
engine implementation module  → feature/* or app
engine/recognition           ↔ engine/ml
```

# Machine learning models

Model assets live in `engine/ml/src/main/assets/`:

```text
best_int8_320.tflite            # Two-class detector (face, license plate)
blaze_face_short_range.tflite   # Face landmarks for alignment
mobilefacenet.tflite            # Face embedding extractor
```

| Model | Trained in this project? | Original source |
| --- | --- | --- |
| **Detector** — YOLO, 2 classes (`FACE`, `LICENSE_PLATE`), 320×320 input, INT8 quantized | **Yes.** Fine-tuned for this project and committed to the repository. | Architecture and training tooling: [Ultralytics YOLO](https://github.com/ultralytics/ultralytics) |
| **Landmarks** — BlazeFace (short range), 5 keypoints | No. Used pre-trained, without additional training. | [`blaze_face_short_range.tflite`](https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite) · [model card](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20%28Short%20Range%29.pdf) · [documentation](https://ai.google.dev/edge/mediapipe/solutions/vision/face_detector) |
| **Embeddings** — MobileFaceNet, 112×112×3 input → 192-D vector | No. Used pre-trained, without additional training. | [Reference implementation](https://github.com/sirius-ai/MobileFaceNet_TF) · [paper (arXiv:1804.07573)](https://arxiv.org/abs/1804.07573) |

> [!NOTE]
> The pre-trained BlazeFace and MobileFaceNet weights are third-party files used as-is; the links above are their original sources. The fine-tuned detector is committed because it is a project artifact and cannot be re-downloaded.

## Detection pipeline

- Input frames are letterboxed to **320×320** with gray (114) padding to preserve aspect ratio.
- Inference uses the INT8 model on **XNNPACK with 2 CPU threads**.
- Decoding applies **class-wise non-maximum suppression** with an IoU threshold of **0.45**, over at most **300** candidate boxes.
- The detector runs approximately **every second analysis frame**; the tracker fills the gaps.

## Keep-visible pipeline

A face is only enrolled or verified when it passes every quality gate:

| Gate | Threshold |
| --- | --- |
| Crop size | ≥ 64 px |
| Faces found in the crop | exactly 1 |
| Landmarks detected | ≥ 3 keypoints |
| Inter-eye distance | ≥ 20 px |
| Frontality (nose offset) | ≤ 0.45 × inter-eye distance |

The crop is dilated by **25%** before alignment. Embeddings are L2-normalized and compared with **cosine similarity ≥ 0.45**, and a match must hold for **2 consecutive frames** before a person is kept visible. At most **5 embeddings** are retained per person.

# Data and storage

BlurGuard uses **no relational database**. There are exactly two pieces of state:

| Store | Contents | Lifetime |
| --- | --- | --- |
| `AppSettings` | `anonymizationMode`, `cameraPermissionGranted`, `audioPermissionGranted` | Persisted in key-value preferences |
| `TrustedFaceStore` | Per track ID: up to 5 × 192-D embeddings, verification state (trusted / pending / rejected), last verified frame | **In memory only, for the duration of the session.** Never written to disk and erased when the session closes. |

Anonymized recordings are the only files the app produces. They are written through `MediaStore` with a timestamped name, for example:

```text
BlurGuard_2026-05-13_14-30.mp4
```

# Build

The app contains the usual `debug` and `release` build variants.

From the repository root:

```bash
./gradlew clean
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # release APK
```

Outputs are generated under:

```text
app/build/outputs/apk/debug/
app/build/outputs/apk/release/
```

For normal development use the `debug` variant. For performance measurement use the `release` variant — debug builds do not represent real frame timing.

> [!NOTE]
> `.tflite` assets must not be compressed by the packager. The `app` module already configures `noCompress` for them; if you add a model, keep that configuration.

# Testing

To facilitate testing of components, **BlurGuard** uses dependency injection with [Hilt](https://developer.android.com/training/dependency-injection/hilt-android).

The pipeline stages are defined as interfaces in `engine/api` — `Detector`, `Tracker`, `FaceRecognizer`, and `BlurGuardEngine`. Concrete implementations are bound through Hilt, so in tests the production implementations can be replaced with test doubles instead of mocks. Feature tests use a fake `BlurGuardEngine` and therefore need no CameraX, LiteRT, GPU, or MediaCodec.

To run the tests, execute the following Gradle tasks:

```bash
./gradlew test                        # all local unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests on a connected device
./gradlew clean test assembleDebug    # full verification pass
```

Per-module unit tests:

```bash
./gradlew :engine:impl:test
./gradlew :engine:ml:test
./gradlew :engine:tracking:test
./gradlew :engine:recognition:test
./gradlew :feature:camera:test
```

The test strategy has four layers:

- **Unit tests** — YOLO output decoding, NMS, tracker association and lifecycle, quality gates, and cosine matching.
- **Engine API contract tests** — assert that no raw frame type crosses `engine/api`.
- **Pipeline tests** in `engine/impl` — verify blur-vs-keep-visible decisions and fail-safe behavior.
- **Privacy regression tests** — assert that no file is written outside `engine/camera`'s processed output path and that no network call happens during recording.

# UI

The screens and UI elements are built entirely using [Jetpack Compose](https://developer.android.com/jetpack/compose), with a shared theme, color system, and typography in `core/designsystem`.

The camera screen hosts the anonymized preview surface, the recording controls, the anonymization mode selector, and the keep-visible interaction. Because the preview is fed from the same processed surface as the encoder, **the UI is physically unable to display an un-anonymized frame**.

The analysis stream is configured at **640×360 RGBA** with a **16:9** aspect ratio to match the preview and recording surfaces.

# Performance

The non-functional targets for the pipeline are:

| Target | Value |
| --- | --- |
| Sustained frame rate | 24–30 fps |
| End-to-end frame latency | ≤ 41 ms |
| Continuous recording | 10 minutes without degradation |
| Simultaneous kept-visible faces | ≥ 2 |
| Network usage | none — fully offline |

## Test environment

| Component | Value |
| --- | --- |
| Device | Xiaomi Redmi Note 14 4G |
| SoC | MediaTek Helio G99-Ultra |
| GPU | Mali-G57 |
| Analysis resolution | 640×360 RGBA |
| Detector input | 320×320 |

## Detector configuration comparison

Measured on the device above, averaged over a recording session:

| Configuration | Preprocessing | Inference | Decoding | Throughput |
| --- | --- | --- | --- | --- |
| FP16, 320 input, 4 threads | 37.1 ms | 134.9 ms | 0.4 ms | 5.8 detections/s |
| **INT8, 320 input, 2 threads** | **28.6 ms** | **35.6 ms** | **0.2 ms** | **15.0 detections/s** |

The INT8 configuration with 2 threads is the shipped configuration: it is roughly **3.8× faster at inference** while using fewer CPU threads, which leaves headroom for GPU rendering and encoding on the same frame budget.

# Documentation

The [`docs/`](docs) folder contains the full project documentation:

```text
docs/
├── abd_al_hadi_nashawati_software_BlurGuard.pdf   # Full project report
└── images/                                        # High-resolution figures
    ├── blurguard-splash.png
    ├── screenshots.png
    ├── fig1-use-case-diagram.png
    ├── fig2-system-block-diagram.png
    ├── fig3-class-diagram.png
    ├── fig4-single-frame-flowchart.png
    ├── fig5-keep-visible-flowchart.png
    ├── fig6-detection-model.png
    ├── fig7-recognition-model.png
    └── fig8-data-diagram.png
```

The report is the authoritative description of the requirements, design decisions, benchmark study, and evaluation. This README describes the same system from the perspective of someone who wants to read, build, and run the code.

> [!NOTE]
> Everything required to build and run the project is committed. Build outputs, Gradle caches, local SDK paths, and other generated files are excluded through [`.gitignore`](.gitignore).

# Contributing guidelines

## Where does new code belong?

| Change | Module |
| --- | --- |
| New screen | `feature/*` |
| Shared UI component | `core/designsystem` |
| App-wide startup, navigation, DI | `app` |
| Settings or media access | `core/data` |
| Shared immutable model | `core/model` |
| New engine command or state | `engine/api` |
| Pipeline orchestration | `engine/impl` |
| CameraX or video writing | `engine/camera` |
| Rendering or anonymization | `engine/render` |
| Model inference | `engine/ml` |
| Tracking | `engine/tracking` |
| Identity and keep-visible policy | `engine/recognition` |

## Keep the frame path separate

Never route frames through Compose state, ViewModels, `StateFlow`, repositories, logs, analytics, or disk caches.

## Fail safe

If the app cannot be certain whether a face or license plate should be visible, it anonymizes. Privacy outweighs visual convenience.

# Troubleshooting

<details>
<summary>Gradle sync fails because SDK 37 is missing</summary>

Install Android API 37 from **Tools → SDK Manager → SDK Platforms**. If it is not listed, update Android Studio and the SDK tools.

</details>

<details>
<summary>Gradle fails because the JDK is too old</summary>

Use JDK 17 or newer. In Android Studio: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**, then select the bundled JDK or another JDK 17+ installation.

</details>

<details>
<summary>Camera preview does not work on the emulator</summary>

Use a physical device. If you must use an emulator, open Device Manager, edit the emulator, confirm the camera is enabled, choose a system image with camera support, and restart it.

</details>

<details>
<summary>The app installs but the camera screen is blank</summary>

Check that the camera permission is granted, that no other app is holding the camera, and that the device supports the requested configuration. Inspect Logcat for CameraX binding errors.

</details>

<details>
<summary>Recording fails immediately</summary>

Check media permissions for the Android version under test, the microphone permission if audio is enabled, and available storage. Inspect Logcat for `VideoCapture` errors.

</details>

<details>
<summary>Model loading fails</summary>

Confirm the three `.tflite` files exist under `engine/ml/src/main/assets/`, confirm they are not being compressed by the packager, then rebuild.

</details>

<details>
<summary>Hilt or KSP generated-code errors</summary>

Run `./gradlew clean` followed by `./gradlew build --refresh-dependencies`, and verify that every Hilt module and injected class is annotated correctly.

</details>

<details>
<summary>Low frame rate or dropped frames</summary>

Measure on a `release` build, not `debug`. Confirm the INT8 detector is selected with 2 XNNPACK threads, and confirm the GPU is used for rendering rather than inference.

</details>

# Roadmap

The following were designed during the requirements phase but are **not part of this version** of the code:

- A gallery feature module for browsing, playing, renaming, and post-capture editing of saved recordings.
- Persistent encrypted trusted-face enrollment (embeddings are currently session-only, in memory).
- Panic delete, app lock, and screenshot protection.
- Watermarking and metadata stripping on export.
- Arabic/English in-app localization settings.
- A `benchmark` module with macrobenchmarks guarding sustained frame rate and latency.

# License

**BlurGuard** is an academic project produced at HIAST. The application source code in this repository is authored by Abd Al-Hadi Nashawati.

Third-party components keep their own licenses — notably the pre-trained BlazeFace and MobileFaceNet models and the Ultralytics YOLO training tooling. Review those licenses before redistributing the app or the model assets.
