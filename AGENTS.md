---
name: blurguard-architecture
description: Enforce BlurGuard's modular Android architecture, engine boundary, and privacy invariants. Apply to ALL code changes in this repository.
always_apply: true
---

# SKILL: BlurGuard Architecture Guardrails

You are working on BlurGuard, a native Android (Kotlin/Compose/CameraX) app that detects and anonymizes faces and license plates in real time, on-device, and offline, and saves ONLY the anonymized video.

Follow these rules strictly. If a request conflicts with them, STOP and flag the conflict instead of silently violating the architecture.

## 1. Architecture summary

BlurGuard has two major parts:

1. The normal Android app:
    - app/
    - feature/*
    - core/*

2. The real-time privacy engine:
    - engine/api
    - engine/impl
    - engine/camera
    - engine/render
    - engine/ml
    - engine/tracking
    - engine/recognition

The engine is an internal SDK. App and feature modules consume it through `engine/api` only. They must not import engine implementation modules.

## 2. Module map: where code MUST live

- app/                    entry point, navigation, Hilt composition root, offline-only network policy, app lock host
- feature/camera/         recording screen + ViewModel, preview host, engine commands, uncertainty warning UI
- feature/gallery/        gallery browse + post-capture manual edit
- feature/settings/       privacy / anonymization / security / export menus

- core/domain/            PLANNED — app-level use cases and non-hot-path business rules.
                          Not in settings.gradle.kts yet; do not import it until it is added.
- core/data/              settings, media store, encrypted embedding store handles, panic delete, temp cleanup
- core/model/             cross-module contracts + shared immutable entities.
                          LEAF module: interfaces, data classes, enums and value classes ONLY.
                          No mutable state, no behaviour, no implementations.
- core/designsystem/      Compose theme, reusable components, AR/EN localization
- core/common/            dispatchers, Result types, safe logging helpers, utilities

- engine/api/             public engine contracts consumed by app/features, including the
                          keep-visible contracts (KeepVisibleController, KeepVisibleRecognizer)
- engine/impl/            real-time pipeline orchestration, pipeline stages (incl. KeepVisibleStage)
                          + the real Hilt bindings
- engine/camera/          CameraX session + SurfaceProcessor pipeline. The ONLY module allowed to write video.
- engine/render/          GPU blur/pixelate/mask rendering, watermark, metadata strip on export.
                          The ONLY module doing GPU work.
- engine/ml/              ON-DEVICE MODEL RUNTIME. All TFLite/LiteRT + MediaPipe wrappers and
                          accelerator management:
                            • detection: YoloDetector, MediaPipeFaceDetector
                            • face embedding extraction: MobileFaceNetRecognizer
                            • landmark alignment: FaceAligner, SimilarityTransform
                            • accelerator policy owner: TfliteInterpreterFactory
                              (NNAPI/NPU first, automatic CPU/XNNPACK fallback,
                              GPU reserved for engine/render)
                            • debug primitives: DetectorDiagnostics, DetectorClock, isDebugBuild()
                          The ONLY module allowed to declare litert / mediapipe dependencies.
- engine/tracking/        multi-object tracking (OC-SORT)
- engine/recognition/     IDENTITY POLICY. Keep-visible trust decisions and their state:
                          KeepVisibleOrchestrator, the verification state store implementation,
                          the trusted-person gallery (SessionTrustedPersonStore), thresholds,
                          re-verification cadence and quality gating.
                          Programs against the FaceRecognizer / TrustedPersonStore abstractions in
                          core/model — it must NEVER depend on engine/ml and must contain NO TFLite
                          dependency.
- benchmark/              macrobenchmark guarding frame latency

Split of responsibility to remember: **engine/ml runs models** (where faces are, what their
embedding is); **engine/recognition decides identity and trust** (who they are, whether the box
may be unblurred, how often to re-check). They are siblings with no dependency between them; the
concrete recognizer is injected into the orchestrator by engine/impl's Hilt module.

There is NO `core/ml`, `core/recognition`, `core/processing` or `core/blurring` module, and no
`engine/blurring`. All real-time pipeline code lives under `engine/*`: GPU blur/pixelate/mask
renderers in `engine/render`, frame-path orchestration in `engine/impl`, model inference in
`engine/ml`, identity policy in `engine/recognition`.

Before adding code, decide which module owns it using the list above. Never put logic in the wrong layer for convenience.

## 3. Dependency direction: MUST NOT create cycles

Allowed dependencies:

- app -> feature/*, core/*, engine/api, engine/impl
- feature/* -> core/domain (when added), core/data, core/model, core/designsystem, core/common, engine/api ONLY
- core/domain -> core/data, core/model, core/common, engine/api
- core/data -> core/model, core/common
- core/designsystem -> core/model, core/common
- engine/api -> core/model, core/common only, plus minimal Android lifecycle/surface abstractions if required
- engine/impl -> engine/api, engine/camera, engine/render, engine/ml, engine/tracking, engine/recognition, core/data, core/model, core/common
- engine/camera/render/ml/tracking/recognition -> engine/api, core/model, core/common
- benchmark -> app and engine modules as required for measurement

Forbidden dependencies:

- feature/* MUST NOT depend on another feature/*
- feature/* MUST NOT depend on engine/impl or engine implementation modules
- feature/* MUST NOT import CameraX, TFLite, MediaCodec, OpenGL, tracker, or MobileFaceNet implementation details
- core/domain MUST NOT depend on engine/impl or engine implementation modules
- engine/api MUST NOT depend on engine/impl or implementation modules
- engine implementation modules MUST NOT depend on feature/*, app/, or core/designsystem
- engine/recognition MUST NOT depend on engine/ml, and engine/ml MUST NOT depend on
  engine/recognition. Identity policy talks to models only through the core/model abstractions
  (FaceRecognizer, TrustedPersonStore); engine/impl wires the concrete implementation in.
- No module other than engine/ml may declare a litert / mediapipe.tasks.vision dependency.
- Do not duplicate a source file across two modules to avoid a dependency. Move it, don't copy it.
- app may depend on engine/impl ONLY as the DI composition root. Do not put feature logic in app that calls engine internals.

## 4. The two data paths: NEVER conflate

UI-STATE PATH (slow):
Compose -> ViewModel(UiState as StateFlow) -> core/domain use cases -> repositories -> engine/api when needed.

Carries:
- recording state
- settings
- warnings
- gallery lists
- user actions
- screen state

FRAME PATH (fast, 24-30 fps):
Camera -> engine/camera -> engine/render -> {Preview, Encoder}
with a parallel branch:
ImageAnalysis -> engine/ml -> engine/tracking -> engine/recognition -> engine/impl -> engine/render.

Rules:
- Pixel/frame data MUST NEVER flow through a ViewModel, StateFlow, LiveData, repository, or UI state.
- Never collect camera frames in UI state.
- The app may observe safe engine state/warnings, not raw frames.

## 5. Engine API rules

`engine/api` is the only API surface that app/features may use.

Allowed API concepts:
- BlurGuardEngine
- EngineConfig
- RecordingRequest
- RecordingState
- EngineWarning
- AnonymizationMode
- TrustedFaceRef
- KeepVisibleController (non-generic, UI-facing keep-visible commands)
- KeepVisibleRecognizer<F> (frame-generic pipeline SPI)
- safe preview/recording targets

Forbidden API concepts:
- ImageProxy streams
- raw Bitmap frames
- raw YUV/RGB byte arrays
- raw camera Surface objects unless wrapped as a restricted preview/processed target
- TFLite Interpreter
- MediaCodec internals
- OpenGL renderer internals
- tracker internals
- MobileFaceNet/ArcFace internals
- CameraUiState or any UI-specific state class

The engine API must be stable, narrow, and privacy-preserving.

## 6. Hard privacy & safety invariants: NON-NEGOTIABLE

1. ENGINE BOUNDARY: app and feature modules consume the frame engine only through `engine/api`.
2. SINGLE VIDEO WRITER: only `engine/camera` writes video, and only the processed anonymized SurfaceProcessor output.
3. NO RAW PERSISTENCE: un-anonymized frames must NEVER reach disk, cache, MediaStore, logs, crash reports, analytics, or telemetry — not even temporarily.
4. NO RAW FRAME EXPOSURE: `engine/api` must not expose raw frames, raw surfaces, raw ImageProxy, raw Bitmap, or raw frame byte arrays.
5. ON-DEVICE ONLY: no inference, embedding, or footage may leave the device.
6. OFFLINE-ONLY: network policy is enforced centrally in app/, not per-feature.
7. METADATA STRIP: GPS/device/timestamp metadata removal is mandatory and default-on during export in `engine/render`.
8. EMBEDDING SAFETY: trusted-face embeddings stay on-device (session-only in-memory today, encrypted storage if ever persisted), are wiped by panic delete, and are never exported.
9. FRAME PATH OFF UI THREAD: no per-frame work in ViewModels or Compose.
10. FAIL CLOSED: if detection/tracking/recognition confidence is uncertain, anonymize rather than reveal. A recognition pass that is skipped, throttled, quality-gated or failed keeps the face blurred; only a positive trusted match may unblur.

If a change could violate any invariant above, refuse and explain. Do not weaken an invariant to make a test pass.

## 7. Concurrency rules

- Detection runs on a background/ML dispatcher with the NNAPI delegate (CPU fallback), on SUBSAMPLED ImageAnalysis frames, not every frame.
- Recognition is rarer still: at most one recognizer call per detection frame, gated per track by a minimum re-verification interval and cheap quality gates. Skipping is always fail-closed.
- Identity state (verification map, trusted galleries) is single-threaded on the ml dispatcher; UI threads reach it only through KeepVisibleController.
- Rendering runs on the GPU every frame; use tracker output to interpolate between detections.
- No allocations or blocking calls in the per-frame hot loop.
- Pass detection results to the renderer via a latest-value/conflated channel; drop stale frames, never queue-and-lag.
- All dispatchers come from `core/common` DispatcherProvider; do not hardcode Dispatchers in modules.
- Engine state exposed to UI should be low-frequency, safe state such as `RecordingState` or `EngineWarning`.

## 8. Conventions

- UI is Compose + MVVM + UDF: one immutable UiState per screen, events up, state down.
- ViewModels map `engine/api` state into feature-specific UiState.
- Use fake `BlurGuardEngine` implementations for feature tests.
- Settings use Proto DataStore in `core/data`.
- Saved files use names like `BlurGuard_YYYY-MM-DD_HH-mm.mp4`.
- Keep models/runtimes swappable behind interfaces.
- No direct TFLite/LiteRT or MediaPipe call outside `engine/ml`.
- No direct CameraX session ownership outside `engine/camera`.
- No direct GPU anonymization/rendering implementation outside `engine/render`.
- No direct tracker implementation outside `engine/tracking`.
- No identity/trust decision logic outside `engine/recognition`.
- Kotlin sources end in `.kt` — never `.k.kt`.
- Debug instrumentation follows the DetectorDiagnostics pattern in `engine/ml`: a no-op when
  disabled, enabled from `isDebugBuild()`, time sourced from an injectable clock. No
  commented-out `Log` lines in production sources.

## 9. Decision guide: where does this go?

- New screen/UI -> feature/* (+ core/designsystem for shared components)
- New screen state/event handling -> feature/* ViewModel
- New app-level use case that is not per-frame hot path -> core/domain (once added)
- Settings, media listing, deletion, encrypted stores -> core/data
- Shared cross-module contract or immutable entity -> core/model (interface or immutable data only)
- Shared utility, dispatcher, safe logging -> core/common
- Engine public contract -> engine/api
- Real-time pipeline orchestration and pipeline stages -> engine/impl
- CameraX session, processed recording, SurfaceProcessor ownership -> engine/camera
- GPU blur/pixelate/mask/watermark/metadata strip -> engine/render
- Any TFLite/MediaPipe model wrapper, delegate, or alignment step -> engine/ml
- Tracking logic -> engine/tracking
- Trust/identity decisions, verification state, trusted-person gallery, recognition cadence -> engine/recognition
- Performance tests for frame latency/FPS -> benchmark

## 10. Pre-change review checklist: apply to every diff

- [ ] Code is in the correct module per section 2.
- [ ] No forbidden dependency or cycle introduced.
- [ ] Feature modules import only `engine/api`, not engine implementation modules.
- [ ] `engine/recognition` still has no dependency on `engine/ml` and no TFLite dependency.
- [ ] No litert / mediapipe dependency added outside `engine/ml`.
- [ ] No frame/pixel data routed through ViewModel/StateFlow/LiveData.
- [ ] `engine/api` does not expose raw frames, raw surfaces, ImageProxy, Bitmap, or byte arrays.
- [ ] Only `engine/camera` writes video, and only processed anonymized output.
- [ ] No raw footage written anywhere.
- [ ] No network access in record/process paths.
- [ ] Metadata strip preserved on export.
- [ ] Embeddings stay on-device and panic-deletable.
- [ ] Per-frame hot loop has no allocations/blocking.
- [ ] `core/model` still holds interfaces and immutable data only (no MutableStateFlow, mutable collections, counters, or implementations).
- [ ] No source file duplicated across two modules.
- [ ] Tests use fake engine where possible.
- [ ] App uses `engine/impl` only for DI composition.

## 11. Anti-patterns to reject

- Recording with CameraX VideoCapture bound directly to the raw camera stream.
- Collecting camera frames into StateFlow, LiveData, Compose state, or a ViewModel.
- A feature module importing `engine/camera`, `engine/ml`, `engine/render`, `engine/tracking`, `engine/recognition`, or `engine/impl`.
- Exposing `ImageProxy`, `Bitmap`, YUV/RGB byte arrays, or raw surfaces through `engine/api`.
- Saving a temporary raw clip "just to process it later."
- Adding cloud inference, telemetry, crash uploads, or analytics that could carry frame data.
- God-classes in feature ViewModels that embed detection/tracking/rendering logic.
- Putting real-time frame orchestration in `core/domain` instead of `engine/impl`.
- Putting mutable state or implementations (MutableStateFlow, mutable collections, ID counters, in-memory stores) in `core/model`.
- `engine/recognition` importing `engine/ml` instead of the core/model abstraction.
- Adding TFLite/MediaPipe dependencies to a module other than `engine/ml`.
- Copying a class into a second module instead of moving it, leaving two live packages.
- Making `engine/api` UI-specific by exposing `CameraUiState`.
