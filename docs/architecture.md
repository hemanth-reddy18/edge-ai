# Edge AI Receptionist — System Architecture

## 1. High-Level Overview
Edge is an on-device AI receptionist engineered for Indian small businesses (Real Estate, Supermarkets, Medical Shops / Pharmacies, and Customer Support). 
Local speech recognition, on-device reasoning (LLM), local tool lookups, and voice synthesis run natively on the client's Android device without routing private call audio to cloud AI providers.

A lightweight founder dashboard and control plane runs as an authenticated management plane (FastAPI + SQLite + Web UI) providing licensing, cryptographically signed leases, prompt configuration, and consented summary telemetry.

```
+---------------------------------------------------------------------------------------+
|                                    Android Device                                     |
|                                                                                       |
|  +--------------------+     +------------------------+     +-----------------------+  |
|  | Telecom Adapter /  | --> |     CallAudioBridge    | --> |       Silero VAD      |  |
|  | InCallService      |     | (Local & Telephony)    |     |    (Speech/Silence)   |  |
|  +--------------------+     +------------------------+     +-----------------------+  |
|                                                                        |              |
|                                                                        v              |
|  +--------------------+     +------------------------+     +-----------------------+  |
|  | Text-To-Speech     | <-- |    On-Device LLM       | <-- |  Whisper Multilingual |  |
|  | Engine (Local TTS) |     | (Qwen3-0.6B Q4 GGUF)   |     |  Base Quantized       |  |
|  +--------------------+     +------------------------+     +-----------------------+  |
|            |                             |                             ^              |
|            v                             v                             |              |
|  +--------------------+     +------------------------+                 |              |
|  | Audio Playback &   |     | Room Database          |                 |              |
|  | Recording Sink     |     | (Calls, Turns, Tools)  |                 |              |
|  +--------------------+     +------------------------+                 |              |
|            |                             |                             |              |
+------------|-----------------------------|-----------------------------|--------------+
             | (consented sync)            | (signed lease & config)     |
             v                             v                             |
+------------------------------------------------------------------------|--------------+
|                             Founder Control Plane / Dashboard          |              |
|                           (FastAPI + SQLite + Web UI @ :8000)          |              |
|                                                                        |              |
|  - Client & Subscription Management                                    |              |
|  - Cryptographic Signed Leases (Ed25519/HMAC-SHA256)                   |              |
|  - Master Kill-Switch (Online push/poll & offline 24h lease expiry)    |              |
|  - Business Config & Prompt Publishing                                 |              |
|  - Consented Summary Telemetry (Audio/Transcript disabled by default)  |              |
+---------------------------------------------------------------------------------------+
```

## 2. Component Specifications

### 2.1 On-Device Mobile Client (`com.edge.reception`)
* **Framework:** Native Android Kotlin, SDK 34, minSdk 26.
* **Database:** Room (SQLite) with schema-versioned entities:
  - `CallRecord`: Metadata, duration, language, Lead/Fail classification, 2-line English summary.
  - `TranscriptTurn`: Speaker (CUSTOMER vs EDGE), native script recognized, English translation, timestamp, confidence.
  - `BusinessProfile`: Business type, operating hours, catalog/property inventory.
  - `EntitlementLease`: Signed offline token, validity window, master kill-switch status.
  - `TelemetryQueue`: Offline queue for idempotent summary delivery.
* **UI/UX:** Modern Material 3 dark/light responsive interface, auto-answer toggle, compact status card, call inspector with interactive audio scrubber, turn badges, and support report dialog.

### 2.2 Local AI Inference Engine
* **VAD:** Silero VAD (ONNX runtime / Sherpa-onnx, MIT) for accurate turn-taking and barge-in.
* **ASR:** Whisper Multilingual Base quantized (Q5_1, MIT, ~57 MiB) supporting English, Hindi, Telugu, Tamil, Malayalam.
* **LLM:** Qwen3-0.6B Q4_0 GGUF (Apache 2.0, ~429 MB) running locally with bounded context and structured JSON tool calls.
* **TTS:** Android Native High-Fidelity TTS / Sherpa-onnx local synthesis with pre-rendered greeting caching.

### 2.3 Founder Control Plane & Dashboard
* **Runtime:** Python 3.12+ / FastAPI / Pydantic / SQLite.
* **Network Integration:** Physical USB debugging via `adb reverse tcp:8000 tcp:8000` mapping `http://127.0.0.1:8000`.
* **Security:**
  - Token-authenticated admin endpoints.
  - Signed lease generation with validity timestamps and kill switch verification.
  - Strict tenant isolation and idempotent telemetry ingest.
