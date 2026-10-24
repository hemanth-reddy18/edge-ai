# Edge Architecture & Implementation Decision Log

## Decision 001: Mobile Application ID & Build Stack
* **Date:** 2026-10-25
* **Decision:** Retain `com.edge.reception` as application ID. Use Kotlin DSL (`build.gradle.kts`), AGP 8.4.2, Gradle 8.8, compileSdk 34, targetSdk 34, minSdk 26.
* **Rationale:** Preserves verified baseline that built and installed cleanly on the Samsung Galaxy S22.

## Decision 002: Model Profile Selection for Galaxy S22
* **Date:** 2026-10-25
* **Decision:** Select Qwen3-0.6B Q4_0 GGUF (~429 MB) and Whisper Multilingual Base quantized (~57 MiB) as primary mobile inference candidates. Do not load 4B model by default.
* **Rationale:** The phone reports 5.5 GB total RAM and ~2.1 GB currently available. Concurrently running OS services, UI, VAD, ASR, LLM context, and TTS requires staying within ~1.0 GB active footprint to avoid Android Low Memory Killer (LMK).

## Decision 003: Telecom Integration & Cellular Audio Honesty
* **Date:** 2026-10-25
* **Decision:** Expose clean `CallAudioBridge` interface. Fully document platform restriction on `VOICE_CALL` / `CAPTURE_AUDIO_OUTPUT`. Do not claim cellular call uplink injection is possible for standard third-party apps on stock Android 16 without root or carrier bridge.
* **Rationale:** Adherence to strict non-negotiable instruction: "Never claim cellular call audio injection is possible without device-specific verification."

## Decision 004: Dashboard Architecture
* **Date:** 2026-10-25
* **Decision:** Implement FastAPI control plane in `backend/` with SQLite storage, typed endpoints, signed Ed25519/HMAC leases, and embedded responsive dashboard UI.
* **Rationale:** High performance, zero unnecessary overhead, runs locally on macOS, communicates over `adb reverse` with `http://127.0.0.1:8000`.
