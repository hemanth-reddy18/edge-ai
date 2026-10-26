# Third-Party Notices, Open Source Licenses, and Model Architecture Review

Edge AI Receptionist incorporates and evaluates the following open-source frameworks, neural models, and telephony runtimes:

---

### 1. Silero VAD
* **Project:** [snakers4/silero-vad](https://github.com/snakers4/silero-vad)
* **License:** MIT License
* **Copyright:** (c) Silero Team
* **Terms:** Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software...
* **Production Status:** Verified active production VAD. Embedded model: `silero_vad.onnx` (2,327,524 bytes).

---

### 2. Whisper.cpp & Sherpa-ONNX Multilingual Speech Recognition
* **Projects:** [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp), [OpenAI Whisper](https://github.com/openai/whisper), [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
* **Licenses:** MIT License (Whisper / whisper.cpp), Apache License 2.0 (Sherpa-ONNX)
* **Copyright:** (c) 2023 Georgi Gerganov & Contributors; (c) OpenAI; (c) NextGen & k2-fsa
* **Language Coverage:** Universal multilingual ASR supporting 99 languages including English, Hindi, Telugu, Tamil, Malayalam, and natural code-switching.
* **Production Status:** Verified active ASR model: `ggml-base-q5_1.bin` (59,707,625 bytes, Q5_1 quantized ggml).

---

### 3. Qwen3-0.6B / Llama.cpp Local Reasoning Engine
* **Projects:** [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) & [QwenLM](https://github.com/QwenLM)
* **License:** Apache License, Version 2.0
* **Copyright:** (c) Alibaba Cloud / Georgi Gerganov & Contributors
* **Terms:** Licensed under the Apache License, Version 2.0. Commercial use, modification, and distribution permitted with attribution.
* **Production Status:** Verified active LLM model: `Qwen3-0.6B-Q4_0.gguf` (428,970,080 bytes, Q4_0 quantized).

---

### 4. AI4Bharat Indic-TTS Evaluation
* **Project:** [AI4Bharat/indic-tts](https://github.com/AI4Bharat/indic-tts) (IIT Madras)
* **License:** MIT License (Inference / Code) / Creative Commons Attribution 4.0 International (CC-BY-4.0) (Model Checkpoints & IndicTTS Corpus)
* **Copyright:** (c) AI4Bharat & IIT Madras
* **Architectural Evaluation:**
  - High quality FastPitch + HiFi-GAN acoustic vocoder architecture supporting 13 Indian languages (Hindi, Telugu, Tamil, Malayalam, Kannada, Marathi, etc.).
  - On-Device Android Feasibility: Checkpoints require ~120MB per language pair (~480MB for all 4 Indian languages) plus Indic-NLP phonemizer text-normalizers. ONNX Runtime Mobile or Sherpa-ONNX is required for execution.

---

### 5. Rhasspy Piper TTS & Piper-Voices Licensing Audit
* **Project:** [rhasspy/piper](https://github.com/rhasspy/piper) & [rhasspy/piper-voices](https://github.com/rhasspy/piper-voices)
* **License Audit:**
  - Piper inference engine is MIT licensed.
  - However, Piper strictly depends on `piper-phonemize`, which embeds `espeak-ng` licensed under **GNU General Public License v3.0 (GPLv3)**. Linking Piper natively inside a proprietary commercial mobile application triggers GPLv3 copyleft terms.
  - Furthermore, Piper voice checkpoints have heterogeneous, per-voice licenses (ranging from Public Domain to CC-BY-SA and non-commercial clauses).
  - Indian Language Coverage: Official Piper repositories **do NOT** support Telugu (`te`), Tamil (`ta`), and Malayalam (`ml`). Claiming universal MIT Indian language support with Piper is invalid.

---

### 6. Pocket TTS (Kyutai Labs Fine-Tune)
* **Project:** [Thanushttk/pocket-tts-multilingual](https://huggingface.co/Thanushttk/pocket-tts-multilingual) / [kyutai-labs/pocket-tts](https://github.com/kyutai-labs)
* **License:** Creative Commons Attribution 4.0 International (CC-BY-4.0)
* **Terms:** Allows commercial and non-commercial redistribution with appropriate credit.

---

### 7. Android Open Source Project (AOSP), Jetpack & System Neural TTS
* **Project:** [Android Open Source Project](https://source.android.com/)
* **License:** Apache License, Version 2.0
* **Components:** AndroidX Core, Telecom Framework, InCallService, RoleManager, Material Components 3.
* **System Speech Services:** Android `com.google.android.tts` provides zero-memory footprint on-device neural synthesis for English, Hindi, Telugu, Tamil, and Malayalam with sub-100ms initialization.
