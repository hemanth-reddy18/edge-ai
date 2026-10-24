package com.edge.reception.model

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelManager = ModelManager(applicationContext)
        val manifest = try {
            modelManager.loadManifest()
        } catch (e: Exception) {
            return Result.failure(workDataOf("error" to "Failed to read manifest: ${e.message}"))
        }

        val modelsDir = modelManager.modelsDir
        val freeBytes = modelsDir.freeSpace
        val requiredBytes = manifest.storageRequiredMb * 1024L * 1024L
        if (freeBytes < requiredBytes) {
            return Result.failure(workDataOf("error" to "Insufficient storage. Free: ${freeBytes / (1024 * 1024)}MB, Required: ${manifest.storageRequiredMb}MB"))
        }

        for (model in manifest.models) {
            val targetFile = File(modelsDir, model.filename)
            if (targetFile.exists() && targetFile.length() > 0) {
                continue
            }

            val tempFile = File(modelsDir, "${model.filename}.tmp")
            try {
                downloadFileWithProgress(model.url, tempFile)
                if (isStopped) {
                    tempFile.delete()
                    return Result.failure(workDataOf("error" to "Download cancelled by user"))
                }
                // Atomic rename
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                return Result.retry()
            }
        }

        return Result.success()
    }

    private fun downloadFileWithProgress(urlString: String, destination: File) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true

        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
        }

        val totalLength = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloaded: Long = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (isStopped) break
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalLength > 0) {
                        val progress = ((downloaded.toDouble() / totalLength.toDouble()) * 100).toInt()
                        setProgressAsync(workDataOf("progress" to progress))
                    }
                }
            }
        }
    }
}
