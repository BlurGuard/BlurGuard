# BlurGuard — Architecture Specification

> **Source of truth:** `AGENTS.md` at the repo root is the canonical, always-current
> description of module boundaries and dependency rules. If this document and
> `AGENTS.md` ever disagree, `AGENTS.md` wins — and this file must be updated.
> Agent-facing guardrails live in `.skills/SKILL.md`.

## 1. Purpose & scope

BlurGuard is a native Android app (Kotlin, Jetpack Compose, CameraX) that detects and
anonymizes faces and license plates in real time, fully on-device and offline, and
persists **only** the anonymized video. This document specifies the module
architecture, the two data paths, the engine API boundary, concurrency model,
privacy invariants, and requirements traceability.

## 2. Architectural style

- **Multi-module, layered architecture** with a strict internal SDK boundary.
- **MVVM + UDF** in the UI layer: one immutable `UiState` per screen, events up, state down.
- **Real-time privacy engine** isolated behind `engine/api`; app and feature modules
  never touch engine implementation modules.
- **Dependency injection** via Hilt; the composition root is `app/`.
- **Runtime vs. policy separation** inside the engine: `engine/ml` runs models,
  `engine/recognition` decides identity and trust, and the two never depend on each other.

## 3. Module layout

The list below matches `settings.gradle.kts` on `dev`. Modules marked *PLANNED* are
described here but are not yet declared in `settings.gradle.kts`; do not import them.

blurguard/

├── app/                     Entry point, navigation, Hilt composition root,

│                            offline-only network policy, app lock host

├── feature/

│   ├── camera/              Recording screen + ViewModel, preview host,

│   │                        engine commands, uncertainty warning UI

│   ├── gallery/             Gallery browse + post-capture manual edit

│   └── settings/            Privacy / anonymization / security / export menus

├── core/

│   ├── domain/              PLANNED — app-level use cases, non-hot-path business

│   │                        rules. Not in settings.gradle.kts yet.

│   ├── data/                Settings (Proto DataStore), MediaStore access,

│   │                        encrypted embedding store handles, panic delete,

│   │                        temp cleanup

│   ├── model/               Cross-module contracts + shared immutable entities.

│   │                        LEAF module: interfaces, data classes, enums and

│   │                        value classes ONLY — no mutable state, no

│   │                        implementations, no in-memory stores.

│   ├── designsystem/        Compose theme, reusable components, AR/EN localization

│   └── common/              DispatcherProvider, Result types, safe logging, utilities

├── engine/

│   ├── api/                 Public engine contracts consumed by app/features,

│   │                        including the keep-visible contracts

│   │                        (KeepVisibleController, KeepVisibleRecognizer)

│   ├── impl/                Real-time pipeline orchestration, pipeline stages

│   │                        (incl. KeepVisibleStage) + real Hilt bindings

│   ├── camera/              CameraX session + SurfaceProcessor pipeline.

│   │                        The ONLY module allowed to write video.

│   ├── render/              GPU blur/pixelate/mask rendering, watermark,

│   │                        metadata strip on export. The ONLY GPU consumer.

│   ├── ml/                  On-device MODEL RUNTIME: all TFLite/LiteRT +

│   │                        MediaPipe wrappers. Detection (YoloDetector,

│   │                        MediaPipeFaceDetector), embedding extraction

│   │                        (MobileFaceNetRecognizer), landmark alignment

│   │                        (FaceAligner, SimilarityTransform), accelerator

│   │                        policy owner TfliteInterpreterFactory, and the

│   │                        debug primitives DetectorDiagnostics /

│   │                        DetectorClock / isDebugBuild().

│   │                        The ONLY module declaring litert / mediapipe deps.

│   ├── tracking/            OC-SORT multi-object tracking

│   └── recognition/         IDENTITY POLICY: KeepVisibleOrchestrator,

│                            verification state store implementation,

│                            trusted-person gallery (SessionTrustedPersonStore),

│                            thresholds, re-verification cadence, quality

│                            gating. No TFLite dependency, no dependency on

│                            engine/ml.

└── benchmark/               Macrobenchmark guarding frame latency

There is **no** `core:camera`, `core:ml`, `core:recognition`, `core:processing` or
`core:blurring` module, and no `engine:blurring`. All real-time pipeline code lives
under `engine/*`: GPU blur/pixelate/mask renderers in `engine/render`, frame-path
orchestration in `engine/impl`, model inference in `engine/ml`, identity policy in
`engine/recognition`. If those stale names appear anywhere in code or docs, they are bugs.

## 4. Dependency rules

| Module | May depend on |
|---|---|
| `app` | `feature/*`, `core/*`, `engine/api`, `engine/impl` (DI composition only) |
| `feature/*` | `core/domain` (when added), `core/data`, `core/model`, `core/designsystem`, `core/common`, `engine/api` **only** |
| `core/domain` (PLANNED) | `core/data`, `core/model`, `core/common`, `engine/api` |
| `core/data` | `core/model`, `core/common` |
| `core/designsystem` | `core/model`, `core/common` |
| `core/model` | nothing (leaf; interfaces and immutable data only) |
| `core/common` | nothing (leaf) |
| `engine/api` | `core/model`, `core/common` (+ minimal lifecycle/surface abstractions) |
| `engine/impl` | `engine/api`, `engine/camera`, `engine/render`, `engine/ml`, `engine/tracking`, `engine/recognition`, `core/data`, `core/model`, `core/common` |
| `engine/camera`, `engine/render`, `engine/ml`, `engine/tracking`, `engine/recognition` | `engine/api`, `core/model`, `core/common` |
| `benchmark` | `app` and engine modules as required for measurement |

**Forbidden:**

- `feature/*` → another `feature/*`
- `feature/*` → `engine/impl` or any engine implementation module
- `feature/*` importing CameraX, TFLite, MediaCodec, OpenGL, tracker, or
  MobileFaceNet implementation details
- `core/domain` → engine implementation modules
- `engine/api` → any implementation module
- engine implementation modules → `feature/*`, `app/`, `core/designsystem`
- `engine/recognition` → `engine/ml`, and `engine/ml` → `engine/recognition`. Identity
  policy reaches models only through the `core/model` abstractions (`FaceRecognizer`,
  `TrustedPersonStore`); `engine/impl` injects the concrete implementation.
- any `litert` / `mediapipe.tasks.vision` dependency outside `engine/ml`
- mutable state or implementations (`MutableStateFlow`, mutable collections, ID
  counters, in-memory stores) in `core/model`
- duplicating a source file across two modules to avoid a dependency

## 5. The two data paths

### 5.1 UI-state path (slow, screen-rate)
Compose UI  →  ViewModel (UiState: StateFlow)  →  core/domain use cases
→  repositories (core/data)        →  engine/api (when needed)

Carries: recording state, settings, warnings, gallery lists, user actions, screen state.

### 5.2 Frame path (fast, 24–30 fps, never touches UI state)
Camera ──► engine/camera (SurfaceProcessor) ──► engine/render ──► Preview
│                                        └──► Encoder (anonymized only)
│
└─ ImageAnalysis (subsampled)
└──► engine/ml ──► engine/tracking ──► engine/recognition
└──────► engine/impl ──► engine/render

Rules:

- Pixel/frame data **never** flows through a ViewModel, StateFlow, LiveData,
  repository, or UI state.
- The app observes only safe engine state (`RecordingState`, `EngineWarning`),
  never raw frames.

## 6. Engine API boundary (`engine/api`)

Allowed surface: `BlurGuardEngine`, `EngineConfig`, `RecordingRequest`,
`RecordingState`, `EngineWarning`, `AnonymizationMode`, `TrustedFaceRef`,
`KeepVisibleController` (non-generic, UI-facing keep-visible commands),
`KeepVisibleRecognizer<F>` (frame-generic pipeline SPI), and restricted
preview/recording targets.

Never exposed: `ImageProxy` streams, raw `Bitmap` frames, raw YUV/RGB byte arrays,
raw camera `Surface` objects, TFLite `Interpreter`, MediaCodec internals, OpenGL
renderer internals, tracker internals, embedding-model internals, or any
UI-specific state class such as `CameraUiState`.

## 7. Module specifications

### 7.1 `engine/camera`

Owns the CameraX session and the processed-recording pipeline. The camera
controller responsibilities are split across focused collaborators:

- `CameraSessionController` — session lifecycle and use-case binding orchestration.
- `CameraXSessionFacade` — thin facade over the CameraX `ProcessCameraProvider`.
- `CameraUseCaseFactory` — builds Preview / VideoCapture / ImageAnalysis use cases
  and attaches the anonymization `CameraEffect` so that preview **and** recording
  are anonymized.
- `CameraVideoRecorder` — starts/stops recordings of the processed output.
- `AnalysisFrameSource` — subsampled `ImageAnalysis` frames for the ML branch.
- `MediaStoreOutputFactory` — anonymized-output MediaStore destinations.
- `CameraPreviewViewFactory` — preview surface hosting for Compose.

This module is the **sole video writer** in the codebase, and it writes only the
anonymized SurfaceProcessor output.

### 7.2 `engine/render`

GPU anonymization — the only home of blur/pixelate/mask rendering (there is no
separate blurring module). `AnonymizationCameraEffect` is a `CameraEffect` targeting
`PREVIEW or VIDEO_CAPTURE`, guaranteeing the same anonymized stream reaches both the
on-screen preview and the encoder. `AnonymizingSurfaceProcessor` runs the GL pipeline
per frame; the renderers (blur, pixelate, mask) live behind a common interface. This
module also owns watermarking and metadata stripping on export.

The GPU is reserved for this path: inference deliberately does **not** take a GPU
delegate so that rendering keeps the GPU budget (see NFR-02 in §10).

### 7.3 `engine/ml` — on-device model runtime

Every TFLite/LiteRT and MediaPipe model wrapper in the app lives here, and this is the
only module allowed to declare those dependencies:

- **Detection** — `YoloDetector` (faces + plates), `MediaPipeFaceDetector` (fallback).
- **Face embedding extraction** — `MobileFaceNetRecognizer`, implementing the
  `FaceRecognizer` contract from `core/model`.
- **Landmark alignment** — `FaceAligner` (MediaPipe `FaceLandmarker`) and
  `SimilarityTransform` (5-point similarity warp to the 112x112 model input). Alignment
  exists solely to build a model input tensor, so it belongs with the model wrapper.
- **Accelerator policy** — `TfliteInterpreterFactory` is the single owner: NNAPI/NPU
  first, automatic CPU/XNNPACK fallback on any failure, GPU reserved for
  `engine/render`. `DetectorConfig` documents the policy in one line and points here
  rather than restating it.
- **Debug primitives** — `DetectorDiagnostics` (EMA split timings, throttled logging,
  zero-cost no-op when disabled), the `DetectorClock` / `SystemDetectorClock` time
  source, and the `Context.isDebugBuild()` helper.

The module's single reason to change is the model runtime: models, delegates, tensors
and assets. It knows nothing about trust, thresholds or keep-visible policy.

### 7.4 `engine/tracking`

OC-SORT multi-object tracking to interpolate boxes between detections.
Alternative backends stay behind the same interface.

### 7.5 `engine/recognition` — identity policy

Owns every decision about *who* a face is and whether it may be revealed:

- `KeepVisibleOrchestrator` — per-track trust state machine
  (UNKNOWN → PENDING → TRUSTED / REJECTED), enrollment on user tap, priority-ordered
  recognizer budget of at most one call per detection frame, hysteresis before
  revoking, and the re-verification cadence.
- The verification state store implementation behind the `core/model`
  `KeepVisibleStateStore` contract.
- `SessionTrustedPersonStore` — the in-memory trusted-person gallery implementing
  `TrustedPersonStore`: enrollment, gallery growth with near-duplicate rejection,
  best-match search, revoke / revoke-all. Session-only, so trust dies with the process.

It depends on the `FaceRecognizer` and `TrustedPersonStore` abstractions in
`core/model`, never on `engine/ml` (DIP): `engine/impl` injects the concrete
`MobileFaceNetRecognizer` at composition time. Consequently this module carries **no**
TFLite/MediaPipe dependency, and its single reason to change is identity policy —
thresholds, trust rules, cadence, gating.

### 7.6 `engine/impl`

Real-time pipeline orchestration (`RealBlurGuardEngine`,
`DefaultAnonymizationPipeline`), the pipeline stages that plug identity policy into the
frame path (`KeepVisibleStage`, `DetectionRunner`), the keep-visible use cases, and the
real Hilt bindings (`EngineImplModule`) that wire `engine/ml`'s recognizer into
`engine/recognition`'s orchestrator. Only `app/` may depend on this module, and only as
the DI composition root.

### 7.7 `app/` and `feature/*`

`app/` wires everything together (navigation, Hilt, offline-only network policy,
app lock). `feature/camera` maps `engine/api` state into `CameraUiState` for the
recording screen; `feature/gallery` and `feature/settings` never touch the engine
beyond `engine/api`.

### 7.8 `core/*`

`core/domain` (PLANNED) will host non-hot-path use cases; `core/data` hosts settings,
MediaStore listing/deletion, encrypted embedding store handles, panic delete and temp
cleanup; `core/designsystem` hosts theme and shared components; `core/model` and
`core/common` are leaf modules.

`core/model` carries cross-module contracts and immutable entities **only**: interfaces
(`Detector`, `FaceRecognizer`, `TrustedPersonStore`, `KeepVisibleStateStore`), data
classes (`TrackedBox`, `TrackVerification`, `FrameMetadata`, configs), enums
(`VerificationState`, `DetectionClass`) and value classes (`TrackId`, `PersonId`).
Never mutable state, implementations or behaviour — those live in the owning engine
module. `core/common` carries `DispatcherProvider`, Result types and safe logging.

## 8. Concurrency model

- Detection runs on a background/ML dispatcher with the NNAPI delegate (CPU
  fallback), on **subsampled** `ImageAnalysis` frames — not every frame.
- Recognition is rarer still: at most one recognizer call per detection frame, gated
  per track by a minimum re-verification interval and cheap quality gates. Skipping is
  always fail-closed (see invariant 10).
- Identity state (verification map, trusted galleries) is single-threaded on the ml
  dispatcher; UI threads reach it only through `KeepVisibleController`, which hands work
  off via atomics drained on the ml thread.
- Rendering runs on the GPU every frame; tracker output interpolates between
  detections.
- No allocations or blocking calls in the per-frame hot loop.
- Detection results reach the renderer via a latest-value/conflated channel:
  drop stale frames, never queue-and-lag.
- All dispatchers come from `core/common` `DispatcherProvider`; never hardcode
  `Dispatchers.*` in modules.
- Engine state exposed to the UI is low-frequency and safe (`RecordingState`,
  `EngineWarning`).

## 9. Privacy & safety invariants (non-negotiable)

1. **Engine boundary** — app/features consume the frame engine only through `engine/api`.
2. **Single video writer** — only `engine/camera` writes video, and only the processed anonymized output.
3. **No raw persistence** — un-anonymized frames never reach disk, cache, MediaStore, logs, crash reports, analytics, or telemetry, even temporarily.
4. **No raw frame exposure** — `engine/api` never exposes raw frames, surfaces, `ImageProxy`, `Bitmap`, or byte arrays.
5. **On-device only** — no inference, embedding, or footage leaves the device.
6. **Offline-only** — network policy enforced centrally in `app/`.
7. **Metadata strip** — GPS/device/timestamp removal is mandatory and default-on during export in `engine/render`.
8. **Embedding safety** — trusted-face embeddings stay on-device (session-only and in-memory today; encrypted storage is required if they are ever persisted), are wiped by revoke-all and panic delete, and are never exported.
9. **Frame path off the UI thread** — no per-frame work in ViewModels or Compose.
10. **Fail closed** — when detection/tracking/recognition confidence is uncertain, anonymize rather than reveal. A recognition pass that is skipped, throttled, quality-gated or failed leaves the face **blurred**; only a positive, trusted match may unblur.

## 10. Requirements traceability

| Requirement | Where satisfied |
|---|---|
| FR: Real-time face & plate anonymization | `engine/ml` + `engine/tracking` + `engine/render` |
| FR: Anonymized preview matches saved video | `AnonymizationCameraEffect` (PREVIEW or VIDEO_CAPTURE) in `engine/render`, wired by `CameraUseCaseFactory` |
| FR: Save only anonymized footage | Single-writer rule in `engine/camera` (invariants 2–3) |
| FR: Keep-visible (trusted faces) | Embedding extraction + alignment in `engine/ml`; trust policy, verification state and trusted-person gallery in `engine/recognition`; contracts in `core/model`; pipeline stage + DI in `engine/impl` |
| FR: Gallery & post-capture edit | `feature/gallery` + `core/data` |
| FR: Settings (mode, security, export) | `feature/settings` + Proto DataStore in `core/data` |
| FR: Panic delete | `core/data`, plus `revokeAll` on the trusted-person store (invariant 8) |
| NFR: ≥24–30 fps | GPU render path, subsampled detection, interval-gated recognition, conflated hand-off, `benchmark/` guardrails |
| NFR-02: GPU reserved for anonymization | Inference uses NNAPI/NPU with CPU fallback (`TfliteInterpreterFactory`); no GPU delegate is wired so `engine/render` keeps the GPU |
| NFR: On-device / offline | Invariants 5–6; offline policy in `app/` |
| NFR: Metadata privacy | Invariant 7 in `engine/render` |
| NFR: Localization (AR/EN) | `core/designsystem` |

## 11. Testing strategy

- Feature/ViewModel tests use **fake `BlurGuardEngine`** implementations — never
  the real pipeline.
- Engine modules are tested in isolation behind their interfaces. Because
  `engine/recognition` depends only on `core/model` abstractions, its trust state
  machine, gallery and cadence are JVM-testable with a fake recognizer and a fake clock —
  no Android or TFLite needed.
- `benchmark/` macrobenchmarks guard frame latency and FPS regressions.
- Architecture rules (section 4) should be enforced by module Gradle dependency
  declarations; any new dependency must be checked against the table above.

## 12. Naming & conventions

- Saved files: `BlurGuard_YYYY-MM-DD_HH-mm.mp4`.
- Models/runtimes stay swappable behind interfaces (`Detector`, `FaceRecognizer`,
  tracker interfaces).
- One immutable `UiState` per screen; events up, state down.
- Safe logging only — no frame data, no PII in logs.
- Debug instrumentation follows the `DetectorDiagnostics` pattern: a no-op when
  disabled, enabled from `isDebugBuild()`, time from an injectable clock. Production
  sources contain no commented-out `Log` lines and no ad-hoc debug fields.
- Kotlin sources end in `.kt` — never `.k.kt` or other typo'd extensions.

## 13. Change management

Any change to module boundaries, the engine API surface, or an invariant must
update `AGENTS.md` first, then this specification and `.skills/SKILL.md` in the
same commit.

## 14. Anti-patterns to reject in review

- Recording with `VideoCapture` bound directly to the raw camera stream.
- Collecting camera frames into StateFlow/LiveData/Compose state or a ViewModel.
- A feature module importing any `engine/*` implementation module.
- Exposing `ImageProxy`, `Bitmap`, byte arrays, or raw surfaces through `engine/api`.
- Saving a temporary raw clip "just to process it later."
- Cloud inference, telemetry, or crash uploads that could carry frame data.
- God-class ViewModels embedding detection/tracking/rendering logic.
- Real-time frame orchestration in `core/domain` instead of `engine/impl`.
- Mutable state, implementations or in-memory stores in `core/model`.
- `engine/recognition` importing `engine/ml` instead of the `core/model` abstraction.
- Adding a TFLite/MediaPipe dependency to any module other than `engine/ml`.
- Copying a class into a second module instead of moving it, leaving two live packages.
- References to `core/processing`, `core/blurring`, `core/ml`, `core/recognition` or
  `engine/blurring` — those modules do not exist.
