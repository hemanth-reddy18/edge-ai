package com.edge.reception.model

data class ModelEntry(
    val id: String,
    val name: String,
    val component: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val attribution: String,
    val languages: List<String>
)

data class ModelManifest(
    val version: String,
    val publishedDate: String,
    val storageRequiredMb: Int,
    val models: List<ModelEntry>
)
