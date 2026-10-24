# Android Cellular Call Audio Feasibility & Legal/Technical Analysis

## 1. Executive Summary
This document provides an engineering audit regarding the technical feasibility of intercepting, recording, and injecting synthesized audio into two-way cellular (SIM) phone calls on a production Samsung Galaxy S22 running Android 16 (API 34+).

## 2. Android Security Architecture & Telecom APIs

### 2.1 CallScreeningService
* **Capability:** Allows an application to screen incoming calls, show caller metadata, silence the ringtone, or reject the call.
* **Limitation:** `CallScreeningService` operates strictly in the signaling plane. It provides **zero access** to the voice media stream (audio uplink or downlink). It cannot be used to answer calls or inject speech audio.

### 2.2 InCallService & RoleManager.ROLE_DIALER
* **Capability:** By holding `ROLE_DIALER`, an application becomes the default phone app. It manages incoming call alerts, call states, speakerphone/bluetooth routing, and dialing.
* **Limitation:** Being the default dialer allows answering a call via `Call.answer(VideoProfile.STATE_AUDIO_ONLY)`. However, answering a call delegates the audio routing to the Android Telecom Audio Subsystem (RIL and AudioFlinger). The actual voice modem stream (`VOICE_CALL`) is not exposed as a standard `AudioRecord` source to third-party apps.

### 2.3 AudioSource Permissions in Android 14, 15, and 16
* Android defines specific audio sources in `MediaRecorder.AudioSource`:
  - `VOICE_UPLINK` (uplink / caller mic)
  - `VOICE_DOWNLINK` (downlink / remote caller speech)
  - `VOICE_CALL` (bidirectional audio)
* **Access Control:** Android OS explicitly marks `CAPTURE_AUDIO_OUTPUT` and `VOICE_CALL` with `signature|privileged` protection levels.
* Third-party applications installed on production user builds (non-rooted, non-OEM signed) receive `SecurityException` when attempting to record from `VOICE_UPLINK`, `VOICE_DOWNLINK`, or `VOICE_CALL`.
* Furthermore, Android provides no public API for third-party applications to inject raw PCM audio into the cellular modem's uplink transmit buffer during an active circuit-switched or VoLTE SIM call.

## 3. Physical Device Verification (Samsung Galaxy S22 / Android 16)
* **Device:** Samsung Galaxy S22 (`SM-S901B`)
* **OS:** Android 16
* **Verification Finding:** Standard non-root third-party APKs cannot capture remote cellular audio streams nor inject synthesized audio directly into the cellular baseband without acoustic coupling (e.g. physical speaker-to-mic feedback), which is unsuitable for production.

## 4. Architectural Alternatives & Production Roadmap

### Option A: Clean Telephony Adapter Pattern (Implemented)
The application defines a strict `CallAudioBridge` interface:
1. `LocalLoopAudioBridge`: Used for local testing, microphone speech input, and speaker output.
2. `SimulatedTelephonyBridge`: Used for integration and automated test suites.
3. `VoipSipAudioBridge` (Production Path): Carrier call forwarding to a SIP/PSTN bridge.

### Option B: Carrier Call-Forwarding to SIP/PSTN Gateway
* The client sets conditional call forwarding on their mobile SIM (`*67*`, `*61*`, etc.) to a virtual DID.
* The SIP gateway receives the call, establishes an encrypted WebRTC/RTP media stream directly with Edge running on the phone.
* Edge processes the audio locally on the phone's NPU/CPU (VAD -> ASR -> LLM -> TTS) and streams synthesized audio back over RTP.
* **Benefit:** 100% legal, compliant, high-fidelity bidirectional digital audio without rooting or OEM firmware modification.
