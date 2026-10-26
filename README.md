# Edge — On-Device AI Receptionist for Android

Edge is an on-device AI receptionist built for Indian businesses. Speech recognition (ASR), local conversation reasoning (LLM), inventory/fact retrieval, and voice synthesis (TTS) run natively on the client's Android phone without streaming live customer conversation audio to third-party cloud AI vendors.

---

## 1. Quick Start

### Android Application
* **Prerequisites:** Android SDK 34, JDK 21, connected physical Android device (`aarch64`).
* **Build Debug APK:**
  ```bash
  ./gradlew assembleDebug
  ```
* **Run Unit Tests:**
  ```bash
  ./gradlew testDebugUnitTest
  ```
* **Install to Connected Phone:**
  ```bash
  ./scripts/install-device.sh
  ```

### Founder Dashboard & Control Plane
* **Prerequisites:** Python 3.12+
* **Start Server & Reverse Port:**
  ```bash
  ./scripts/dev-dashboard.sh
  ```
* Access the founder control plane in your browser at: [http://127.0.0.1:8000/](http://127.0.0.1:8000/)

### Localhost Voice Lab
* **Prerequisites:** Node.js 20+
* **Start Voice Lab:**
  ```bash
  ./scripts/start_local_voice_lab.sh
  ```
* Open [http://localhost:3000/](http://localhost:3000/) to converse live with the AI using your laptop microphone and speakers with full instant barge-in interruption.

---

## 2. Architecture & Design Documentation
* [Architecture Overview](docs/architecture.md)
* [Cellular Call Audio Feasibility Analysis](docs/call_audio_feasibility.md)
* [Model Licensing & Asset Matrix](docs/model-licence-matrix.json)
* [Architecture Decision Log](docs/decision_log.md)
* [Open Source Third-Party Notices](THIRD_PARTY_NOTICES.md)
