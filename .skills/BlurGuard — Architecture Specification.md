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

## 3. Module layout
blurguard/

├── app/                     Entry point, navigation, Hilt composition root,

│                            offline-only network policy, app lock host

├── feature/

│   ├── camera/              Recording screen + ViewModel, preview host,

│   │                        engine commands, uncertainty warning UI

│   ├── gallery/             Gallery browse + post-capture manual edit

│   └── settings/            Privacy / anonymization / security / export menus

├── core/

│   ├── domain/              App-level use cases, non-hot-path business rules

│   ├── data/                Settings (Proto DataStore), MediaStore access,

│   │                        encrypted embedding store handles, panic delete,

│   │                        temp cleanup

│   ├── model/               Shared immutable app entities (LEAF module)

│   ├── designsystem/        Compose theme, reusable components, AR/EN localization

│   └── common/              DispatcherProvider, Result types, safe logging, utilities

├── engine/

│   ├── api/                 Public engine contracts consumed by app/features

│   ├── impl/                Real-time pipeline orchestration + real Hilt bindings

│   ├── camera/              CameraX session + SurfaceProcessor pipeline.

│   │                        The ONLY module allowed to write video.

│   ├── render/              GPU blur/pixelate/mask rendering, watermark,

│   │                        metadata strip on export

│   ├── ml/                  TFLite/LiteRT model wrappers + delegate management

│   ├── tracking/            ByteTrack multi-object tracking

│   └── recognition/         Face embedding matching + enrollment

└── benchmark/               Macrobenchmark guarding frame latency

There is **no** `core:camera`, `core:ml`, or `core:processing` module. All real-time
pipeline code lives under `engine/*`.

## 4. Dependency rules

| Module | May depend on |
|---|---|
| `app` | `feature/*`, `core/*`, `engine/api`, `engine/impl` (DI composition only) |
| `feature/*` | `core/domain`, `core/data`, `core/model`, `core/designsystem`, `core/common`, `engine/api` **only** |
| `core/domain` | `core/data`, `core/model`, `core/common`, `engine/api` |
| `core/data` | `core/model`, `core/common` |
| `core/designsystem` | `core/model`, `core/common` |
| `core/model` | nothing (leaf) |
| `core/common` | nothing (leaf) |
| `engine/api` | `core/model`, `core/common` (+ minimal lifecycle/surface abstractions) |
| `engine/impl` | `engine/api`, `engine/camera`, `engine/render`, `engine/ml`, `engine/tracking`, `engine/recognition`, `core/data`, `core/model`, `core/common` |
| `engine/camera`, `engine/render`, `engine/ml`, `engine/tracking`, `engine/recognition` | `engine/api`, `core/model`, `core/common` |
| `benchmark` | `app` and engine modules as required for measurement |

**Forbidden:**

- `feature/*` → another `feature/*`
- `feature/*` → `engine/impl` or any engine implementation module
- `feature/*` importing CameraX, TFLite, MediaCodec, OpenGL, ByteTrack, or
  MobileFaceNet implementation details
- `core/domain` → engine implementation modules
- `engine/api` → any implementation module
- engine implementation modules → `feature/*`, `app/`, `core/designsystem`

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
`RecordingState`, `EngineWarning`, `AnonymizationMode`, `TrustedFaceRef`, and
restricted preview/recording targets.

Never exposed: `ImageProxy` streams, raw `Bitmap` frames, raw YUV/RGB byte arrays,
raw camera `Surface` objects, TFLite `Interpreter`, MediaCodec internals, OpenGL
renderer internals, ByteTrack internals, embedding-model internals, or any
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

GPU anonymization. `AnonymizationCameraEffect` is a `CameraEffect` targeting
`PREVIEW or VIDEO_CAPTURE`, guaranteeing the same anonymized stream reaches both
the on-screen preview and the encoder. Also owns watermarking and metadata
stripping on export.

### 7.3 `engine/ml`

TFLite/LiteRT wrappers for detection models (e.g. YOLOv8 face/plate, BlazeFace)
with NNAPI/GPU delegate management. No TFLite call exists outside this module.

### 7.4 `engine/tracking`

ByteTrack multi-object tracking to interpolate boxes between detections.
Alternative backends (OC-SORT) stay behind the same interface.

### 7.5 `engine/recognition`

Face embedding matching and trusted-face enrollment (MobileFaceNet-class models).
Embeddings live only in encrypted on-device storage.

### 7.6 `engine/impl`

Real-time pipeline orchestration (`RealBlurGuardEngine`) plus the real Hilt
bindings (`EngineImplModule`). Only `app/` may depend on it, and only as the DI
composition root.

### 7.7 `app/` and `feature/*`

`app/` wires everything together (navigation, Hilt, offline-only network policy,
app lock). `feature/camera` maps `engine/api` state into `CameraUiState` for the
recording screen; `feature/gallery` and `feature/settings` never touch the engine
beyond `engine/api`.

### 7.8 `core/*`

`core/domain` hosts non-hot-path use cases; `core/data` hosts settings,
MediaStore listing/deletion, encrypted embedding store handles, panic delete and
temp cleanup; `core/model` and `core/common` are leaf utility/entity modules
(`DispatcherProvider`, Result types, safe logging).

## 8. Concurrency model

- Detection runs on a background/ML dispatcher with NNAPI/GPU delegates, on
  **subsampled** `ImageAnalysis` frames — not every frame.
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
8. **Embedding safety** — trusted-face embeddings are encrypted on-device, wiped by panic delete, never exported.
9. **Frame path off the UI thread** — no per-frame work in ViewModels or Compose.
10. **Fail closed** — when detection/tracking/recognition confidence is uncertain, anonymize rather than reveal.

## 10. Requirements traceability

| Requirement | Where satisfied |
|---|---|
| FR: Real-time face & plate anonymization | `engine/ml` + `engine/tracking` + `engine/render` |
| FR: Anonymized preview matches saved video | `AnonymizationCameraEffect` (PREVIEW or VIDEO_CAPTURE) in `engine/render`, wired by `CameraUseCaseFactory` |
| FR: Save only anonymized footage | Single-writer rule in `engine/camera` (invariants 2–3) |
| FR: Keep-visible (trusted faces) | `engine/recognition` + encrypted store handles in `core/data` |
| FR: Gallery & post-capture edit | `feature/gallery` + `core/data` |
| FR: Settings (mode, security, export) | `feature/settings` + Proto DataStore in `core/data` |
| FR: Panic delete | `core/data` (also wipes embeddings, invariant 8) |
| NFR: ≥24–30 fps | GPU render path, subsampled detection, conflated hand-off, `benchmark/` guardrails |
| NFR: On-device / offline | Invariants 5–6; offline policy in `app/` |
| NFR: Metadata privacy | Invariant 7 in `engine/render` |
| NFR: Localization (AR/EN) | `core/designsystem` |

## 11. Testing strategy

- Feature/ViewModel tests use **fake `BlurGuardEngine`** implementations — never
  the real pipeline.
- Engine modules are tested in isolation behind their interfaces.
- `benchmark/` macrobenchmarks guard frame latency and FPS regressions.
- Architecture rules (section 4) should be enforced by module Gradle dependency
  declarations; any new dependency must be checked against the table above.

## 12. Naming & conventions

- Saved files: `BlurGuard_YYYY-MM-DD_HH-mm.mp4`.
- Models/runtimes stay swappable behind interfaces (`engine/ml`, `engine/tracking`).
- One immutable `UiState` per screen; events up, state down.
- Safe logging only — no frame data, no PII in logs.

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