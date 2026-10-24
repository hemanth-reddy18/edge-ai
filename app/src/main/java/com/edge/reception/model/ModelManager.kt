package com.edge.reception.model

import android.content.Context
import org.json.JSONObject
import java.io.File

class ModelManager(private val context: Context) {

    val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun loadManifest(): ModelManifest {
        val jsonStr = context.assets.open("models/manifest.json").bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)
        val version = root.getString("version")
        val date = root.getString("published_date")
        val storageMb = root.getInt("storage_required_mb")
        val array = root.getJSONArray("models")

        val list = mutableListOf<ModelEntry>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val langArray = obj.getJSONArray("languages")
            val langs = mutableListOf<String>()
            for (j in 0 until langArray.length()) {
                langs.add(langArray.getString(j))
            }
            list.add(
                ModelEntry(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    component = obj.getString("component"),
                    filename = obj.getString("filename"),
                    url = obj.getString("url"),
                    sizeBytes = obj.getLong("size_bytes"),
                    sha256 = obj.getString("sha256"),
                    license = obj.getString("license"),
                    attribution = obj.getString("attribution"),
                    languages = langs
                )
            )
        }
        return ModelManifest(version, date, storageMb, list)
    }

    fun getModelFile(filename: String): File {
        return File(modelsDir, filename)
    }

    fun isModelReady(entry: ModelEntry): Boolean {
        val file = getModelFile(entry.filename)
        return file.exists() && file.length() > 0
    }

    fun areAllModelsReady(): Boolean {
        val manifest = loadManifest()
        return manifest.models.all { isModelReady(it) }
    }

    fun getDownloadProgressPercent(): Int {
        val manifest = loadManifest()
        var totalExpected: Long = 0
        var totalCurrent: Long = 0
        for (m in manifest.models) {
            totalExpected += m.sizeBytes
            val file = getModelFile(m.filename)
            if (file.exists()) {
                totalCurrent += file.length()
            }
        }
        if (totalExpected == 0L) return 100
        return ((totalCurrent.toDouble() / totalExpected.toDouble()) * 100).toInt().coerceIn(0, 100)
    }
}
