# Edge Telephony & Call Bridge Architecture Topology

## 1. Executive Topology Comparison

```
+---------------------------------------------------------------------------------------------------+
| PATH A: LOCAL WEBSOCKET TEST HARNESS (VERIFIED ON PHYSICAL VIVO V2509)                           |
+---------------------------------------------------------------------------------------------------+

  [MacBook Browser / Test Dialer]
       |
       | Web Audio (Microphone downsampled to 16kHz mono PCM)
       v
  [FastAPI Control Plane (127.0.0.1:8000)]
       |
       | USB ADB Reverse Tunnel (tcp:8000 -> tcp:8000)
       v
  [Vivo Phone: com.edge.reception (LocalVoiceTestManager)]
       |
       |-- 1. EnergyVAD / Silence Hangover Detection
       |-- 2. MultilingualASREngine (Caller Speech Transcription)
       |-- 3. LocalLLMEngine (Business Prompt & Dialogue Reasoning)
       |-- 4. TTSEngine (On-Device Speech Synthesis to WAV)
       |-- 5. WavTo16kHzPcmConverter (Dynamic RIFF chunk parsing & 16kHz resampling)
       |-- 6. StereoCallWavRecorder (Interleaved 2-Channel Stereo Recording: L=Caller, R=AI)
       v
  [Return 16kHz PCM Stream over USB WebSocket Relay]
       v
  [MacBook Speakers (Audible AI Response)]

  Status: VERIFIED PASS over USB relay.
  Note: This is an internal microphone/speaker audio loop test. It is NOT a carrier telephone call.
```

```
+---------------------------------------------------------------------------------------------------+
| PATH B: REAL PUBLIC TELEPHONE (PSTN / CELLULAR SIM) CALL TOPOLOGY                                 |
+---------------------------------------------------------------------------------------------------+

  [Remote Caller Phone (Any mobile/landline telephone)]
       |
       | 1. Cellular Carrier / PSTN (VoLTE, 5G, SS7)
       v
  [Public Telephone Network / Carrier Call-Forwarding Destination]
       |
       | 2. SIP Trunk (DID Inbound SIP INVITE / G.711u, G.711a, Opus)
       v
  [Edge Telephony Gateway / SBC (FreeSWITCH / Asterisk / LiveKit SIP / Telnyx SIP)]
       |
       | 3. Authenticated Media Stream (WebRTC / SRTP or Secure Bi-directional Audio WebSocket)
       |    - Protocol: TLS + SRTP (or WSS with HMAC-SHA256 session token)
       |    - Media: 16000 Hz 16-bit linear PCM (or Opus decoded at boundary)
       v
  [Vivo Phone: com.edge.reception (TelephonyCallBridge)]
       |
       |-- 1. On-Device VAD Engine
       |-- 2. Multilingual ASR (On-Device Whisper)
       |-- 3. Local LLM (On-Device Qwen3 / Business Engine)
       |-- 4. On-Device TTS Engine (Google TTS / Local Voice Model)
       |-- 5. Interleaved Stereo Call Recorder (Local compliance recording)
       v
  [Return Synthesized Speech PCM over Media Channel]
       v
  [Edge Telephony Gateway]
       |
       | 4. RTP Audio Uplink back to Carrier SIP Trunk
       v
  [Carrier PSTN / Cellular Baseband]
       v
  [Remote Caller Hears Edge AI Receptionist in Telephone Earpiece]
```

---

## 2. Technical Feasibility & Android Security Boundary

### Why Direct SIM/Cellular Interception on Stock Android is Impossible:
1. **Android Audio Architecture:**
   - In standard Android, the cellular modem (RIL) routes downlink audio directly to the audio DSP / AudioFlinger for earpiece/speaker output.
   - Recording `VOICE_CALL`, `VOICE_DOWNLINK`, or `VOICE_UPLINK` requires the `CAPTURE_AUDIO_OUTPUT` permission.
   - In Android 10, 11, 12, 13, 14, 15, and 16, `CAPTURE_AUDIO_OUTPUT` has protection level `signature|privileged`. Standard third-party non-OEM apps are denied this permission by Android SEPolicy.
2. **Uplink Audio Injection:**
   - There is no public Android API to inject raw PCM audio into an active circuit-switched or VoLTE SIM telephone call.
   - `InCallService` can answer, reject, or hold a call, but does **not** provide media stream read/write access.
3. **Conclusion:**
   - Attempting to route ordinary SIM calls through local speaker-to-mic acoustic feedback is unacceptable in production (echo, background noise, carrier clipping).
   - The only architecturally truthful, legally compliant, high-fidelity route for third-party Android apps is **SIP/PSTN Gateway Interconnection** (via carrier call forwarding or virtual DID).

---

## 3. Audit of Existing Codebase

| Component | File Path | Existing Implementation State | Real SIP/PSTN Capability |
| :--- | :--- | :--- | :--- |
| `VoipSipAudioBridge` | `app/.../telephony/CallAudioBridge.kt` | Skeleton stub with `isStreaming = true`, empty methods | **None** (No SIP stack, no RTP, no socket) |
| `EdgeInCallService` | `app/.../telephony/EdgeInCallService.kt` | Standard `InCallService` responding to `STATE_RINGING` | Signaling control only. **No media access** |
| `EdgeCallScreeningService` | `app/.../telephony/EdgeCallScreeningService.kt` | Filters spam calls via `CallScreeningService` | Signaling only. **No audio access** |
| `LocalVoiceTestManager` | `app/.../telephony/LocalVoiceTestManager.kt` | OkHttp WebSocket client connecting to `127.0.0.1:8000` | **Local test harness only** |
| Backend Relay Routes | `backend/app/main.py` | `/ws/test-caller` and `/ws/device-audio` WebSocket relay | **In-memory test relay only** |
| Web Dialer UI | `backend/app/main.py` (`/test-dialer`) | Browser Web Audio mic capture & playback | **Browser mic tester only** |

---

## 4. Required External Prerequisites for Real Public Telephone Calls

To make a real telephone call from a second phone reach Edge and establish two-way audio, the following external telephony infrastructure must be provisioned:

1. **Public Phone Number (DID):**
   - A real E.164 phone number (e.g., `+1-XXX-XXX-XXXX` or `+91-XXXXXXXXXX`) provisioned via a SIP/PSTN carrier (e.g. Twilio, Telnyx, Plivo, Bandwidth, or Tata Tele).
   - Alternatively: Carrier conditional call forwarding configured on the business SIM to forward unanswered/busy calls to the gateway DID.
2. **SIP / PSTN Media Gateway:**
   - A SIP gateway / Session Border Controller (SBC) running FreeSWITCH, Asterisk, Kamailio, LiveKit SIP, or a cloud SIP provider.
   - Responsibilities:
     - Handles incoming SIP `INVITE` from the telephone network.
     - Negotiates audio codecs (G.711u / G.711a / Opus).
     - Terminates PSTN RTP audio and bridges it into an authenticated, low-latency secure media channel (WebRTC or TLS/WSS audio stream) to the Edge client.
3. **Gateway Authentication & Network Config:**
   - SIP Registrar host, port, username, and password (or SIP Trunk IP ACL).
   - Public IP / FQDN with valid TLS certificate for secure media signaling outside the USB tunnel.
   - STUN / TURN servers for NAT traversal if the phone is not on the same local network as the gateway.

---

## 5. Architectural Separation in Code & UI

To prevent any confusion between local test tooling and real telephony:
- **Local Voice Test:** Strictly labeled as `"Local Browser-to-Phone Audio Loop Test (USB Relay)"`.
- **Telephony Call Bridge:** Labeled as `"Carrier Call Bridge (PSTN / SIP)"`. When gateway credentials or DID are not configured, it transitions to `STATUS_NOT_CONFIGURED` or `STATUS_BLOCKED`, clearly stating the missing telephony credentials.
