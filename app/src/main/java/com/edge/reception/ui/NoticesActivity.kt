package com.edge.reception.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.edge.reception.databinding.ActivityNoticesBinding

class NoticesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoticesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoticesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.tvNoticesContent.text = """
            EDGE AI RECEPTIONIST — OPEN SOURCE & THIRD-PARTY NOTICES

            1. Silero VAD
            Repository: https://github.com/snakers4/silero-vad
            License: MIT License
            Copyright (c) Silero Team
            Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files...

            2. Whisper.cpp & Sherpa-ONNX
            Repository: https://github.com/ggml-org/whisper.cpp
            License: MIT License / Apache License 2.0
            Copyright (c) 2023 Georgi Gerganov & Contributors; OpenAI; k2-fsa
            Multilingual speech recognition supporting English, Hindi, Telugu, Tamil, Malayalam.

            3. Qwen3-0.6B / Llama.cpp
            Repository: https://github.com/ggml-org/llama.cpp
            Model: https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF
            License: Apache License, Version 2.0
            Copyright (c) Alibaba Cloud / Georgi Gerganov & Contributors

            4. AI4Bharat Indic-TTS Evaluation
            Repository: https://github.com/AI4Bharat/indic-tts
            License: MIT License / CC-BY-4.0
            Copyright (c) AI4Bharat & IIT Madras

            5. Rhasspy Piper TTS Audit
            Repository: https://github.com/rhasspy/piper
            License: Engine MIT; espeak-ng dependency GPLv3; per-voice licenses vary.
            Note: Official Piper voices do not provide native Telugu, Tamil, or Malayalam checkpoints.

            6. Pocket TTS (Kyutai Labs Fine-Tune)
            Repository: https://huggingface.co/Thanushttk/pocket-tts-multilingual
            License: Creative Commons Attribution 4.0 International (CC-BY-4.0)

            7. Android Open Source Project (AOSP) & AndroidX
            Repository: https://source.android.com/
            License: Apache License, Version 2.0
            Copyright (c) The Android Open Source Project
        """.trimIndent()
    }
}
