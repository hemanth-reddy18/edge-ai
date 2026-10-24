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

            2. Whisper.cpp
            Repository: https://github.com/ggml-org/whisper.cpp
            License: MIT License
            Copyright (c) 2023 Georgi Gerganov & Contributors

            3. Qwen3-0.6B / Llama.cpp
            Repository: https://github.com/ggml-org/llama.cpp
            Model: https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF
            License: Apache License, Version 2.0
            Copyright (c) Alibaba Cloud / Georgi Gerganov & Contributors

            4. Android Open Source Project (AOSP) & AndroidX
            Repository: https://source.android.com/
            License: Apache License, Version 2.0
            Copyright (c) The Android Open Source Project
        """.trimIndent()
    }
}
