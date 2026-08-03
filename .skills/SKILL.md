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
    - core/* (non-hot-path logic)

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

- core/domain/            app-level use cases and non-hot-path business rules
- core/data/              settings, media store, encrypted embedding store handles, panic delete, temp cleanup
- core/model/             shared immutable app entities (LEAF module)
- core/designsystem/      Compose theme, reusable components, AR/EN localization
- core/common/            dispatchers, Result types, safe logging helpers, utilities

- engine/api/             public engine contracts consumed by app/features
- engine/impl/            real-time pipeline orchestration + real Hilt bindings
- engine/camera/          CameraX session + SurfaceProcessor pipeline. The ONLY module allowed to write video.
- engine/render/          GPU blur/pixelate/mask rendering, watermark, metadata strip on export
- engine/ml/              TFLite/LiteRT model wrappers + delegate management
- engine/tracking/        ByteTrack multi-object tracking
- engine/recognition/     face embedding matching + enrollment
- benchmark/              macrobenchmark guarding frame latency

## 3. Dependency direction: MUST NOT create cycles

Allowed dependencies:

- app -> feature/*, core/*, engine/api, engine/impl
- feature/* -> core/domain, core/data, core/model, core/designsystem, core/common, engine/api ONLY
- core/domain -> core/data, core/model, core/common, engine/api
- engine/api -> core/model, core/common only
- engine/impl -> engine/api, engine/camera, engine/render, engine/ml, engine/tracking, engine/recognition, core/data, core/model, core/common
- engine/camera/render/ml/tracking/recognition -> engine/api, core/model, core/common

Forbidden dependencies:

- feature/* MUST NOT depend on engine/impl or engine implementation modules.
- engine/api MUST NOT depend on engine/impl or implementation modules.
- Only app may depend on engine/impl for DI composition.

## 4. Hard privacy & safety invariants: NON-NEGOTIABLE

1. ENGINE BOUNDARY: app and feature modules consume the frame engine only through `engine/api`.
2. SINGLE VIDEO WRITER: only `engine/camera` writes video, and only the processed anonymized SurfaceProcessor output.
3. NO RAW PERSISTENCE: un-anonymized frames must NEVER reach disk, cache, MediaStore, logs, crash reports, or analytics.
4. NO RAW FRAME EXPOSURE: `engine/api` must not expose raw frames, raw surfaces, ImageProxy, Bitmap, or raw frame byte arrays.
5. ON-DEVICE ONLY: no inference, embedding, or footage may leave the device.
6. FAIL CLOSED: if detection/tracking/recognition confidence is uncertain, anonymize rather than reveal.

## 5. Conventions

- UI is Compose + MVVM + UDF: one immutable UiState per screen, events up, state down.
- ViewModels map `engine/api` state into feature-specific UiState.
- Use fake `BlurGuardEngine` implementations for feature tests.
- Per-frame hot loop has no allocations or blocking calls.
