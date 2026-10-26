# SIP & WebRTC Media Stack Evaluation for Edge Android

## 1. Requirement & Evaluation Criteria
To bridge real public telephone calls (PSTN / SIM / VoLTE) into Edge's on-device AI receptionist on a physical Vivo phone running Android 16 (API 36), the telephony subsystem requires:
1. **Commercial License Compatibility:** Must not enforce viral copyleft (e.g. GPLv3) unless commercially licensed, preserving proprietary ownership.
2. **Android 14/15/16 Compatibility:** Must support Android 16 (API 36), 64-bit ARM (`arm64-v8a`), and adhere to strict background execution and battery-saver restrictions (e.g. Vivo `PACKAGE_FREEZE`).
3. **Native Media Pipeline Integration:** Must deliver raw signed 16-bit little-endian mono PCM at 16000 Hz directly into the local VAD/ASR pipeline without unnecessary format transcoding.
4. **Reliability & Maintenance:** Must maintain clean lifecycle teardown, error recovery, and NAT traversal.

---

## 2. Technical Stack Comparison Matrix

| Candidate | License | Android 16 & ABI Support | Build & Size Impact | Background / Doze Feasibility | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Android `android.net.sip`** | Apache 2.0 | **Removed** (Deprecated in API 31, removed in API 36) | 0 MB (Platform) | Fails (Cannot run on modern Android) | **REJECTED**: Platform API removed |
| **Linphone (Liblinphone)** | **GPLv3** / Proprietary | Supports arm64-v8a | ~45 MB native `.so` binaries | Requires external Flexisip push server; GPLv3 taints codebase | **REJECTED**: License & binary bloat |
| **PJSIP (Teluu)** | **GPLv2** / Proprietary | Supports arm64-v8a | ~25 MB native `.so` binaries | Direct UDP/TLS keepalives drain battery and freeze in background | **REJECTED**: GPL copyleft restriction |
| **Baresip / Libre** | BSD 3-Clause | Custom NDK build needed | ~15 MB native C binaries | High maintenance cost; no official Android AAR release | **REJECTED**: High maintenance risk |
| **Gateway-Assisted Telephony Bridge (Standard Cloud/On-Prem Telecom)** | **Apache 2.0 / MIT** | **Full Native Support** (Pure Kotlin + OkHttp + Python SBC) | **0 MB added overhead** (Uses existing OkHttp 4.12.0) | **Optimal**: External gateway maintains 24/7 SIP registration; phone only wakes on incoming call media | **SELECTED**: Commercially compliant, zero NDK bloat, optimal battery lifecycle |

---

## 3. Selected Production Architecture: Gateway-Assisted Carrier Call Bridge

### Architectural Decision:
Instead of compiling a 40MB GPL-licensed C SIP stack directly into the handset—which Android 16 will freeze or kill after 60 seconds in the background—we select the industry-standard **Gateway-Assisted Carrier Call Bridge**:

1. **Carrier SIP Trunking & Session Border Controller (SBC):**
   - The SIP Gateway (FreeSWITCH / Asterisk / LiveKit SIP / Telnyx SIP) maintains continuous SIP registration with the carrier.
   - When an incoming call arrives at the business DID (or via conditional call forwarding):
     - The gateway responds with SIP `180 Ringing`.
     - The gateway notifies the Edge control plane via secure authenticated webhook (`POST /v1/telephony/webhook/inbound-call`).
2. **Authenticated Secure Media Channel:**
   - The Edge backend opens an authenticated carrier media bridge (`/ws/telephony/carrier-media?call_id={id}&token={token}`).
   - The Vivo phone connects to this channel via its native `CarrierCallBridge`.
3. **On-Device AI Processing:**
   - The phone receives 16kHz mono PCM frames from the remote caller.
   - It runs on-device VAD, on-device Whisper transcription, on-device local LLM dialogue generation, and on-device speech synthesis (TTS).
   - Synthesized speech PCM is streamed back through the carrier media channel.
4. **PSTN Uplink Delivery:**
   - The gateway encodes the speech into the negotiated telephone codec (G.711u / G.711a / Opus) and transmits it over RTP directly into the caller's telephone earpiece.
