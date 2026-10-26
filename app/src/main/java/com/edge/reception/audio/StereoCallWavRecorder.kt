package com.edge.reception.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StereoCallWavRecorder(private val outputFile: File, private val sampleRate: Int = 16000) {
    private var fos: FileOutputStream? = null
    private var dataBytesWritten: Int = 0

    fun start() {
        outputFile.parentFile?.mkdirs()
        fos = FileOutputStream(outputFile)
        fos?.write(ByteArray(44)) // Reserve 44-byte WAV header
        dataBytesWritten = 0
    }

    /**
     * Writes a 16-bit mono PCM chunk into either the Left (Caller) or Right (AI) stereo channel,
     * padding the opposite channel with digital silence (0) for true 2-channel separation.
     */
    @Synchronized
    fun writeMonoChunkAsStereo(monoPcm16Le: ByteArray, isCallerLeftChannel: Boolean) {
        val stream = fos ?: return
        val sampleCount = monoPcm16Le.size / 2
        if (sampleCount <= 0) return

        val inBuf = ByteBuffer.wrap(monoPcm16Le).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val stereoBuf = ByteBuffer.allocate(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until sampleCount) {
            val sample = inBuf.get(i)
            if (isCallerLeftChannel) {
                stereoBuf.putShort(sample) // Left: Caller
                stereoBuf.putShort(0)      // Right: Silence
            } else {
                stereoBuf.putShort(0)      // Left: Silence
                stereoBuf.putShort(sample) // Right: AI
            }
        }
        val bytes = stereoBuf.array()
        stream.write(bytes)
        dataBytesWritten += bytes.size
    }

    @Synchronized
    fun closeAndFinalizeHeader() {
        try {
            fos?.flush()
            fos?.close()
        } catch (e: Exception) {
            // Ignore stream close exceptions
        } finally {
            fos = null
        }

        if (!outputFile.exists() || dataBytesWritten == 0) return

        val channels = 2
        val bitsPerSample = 16
        val blockAlign = channels * (bitsPerSample / 8) // 4 bytes per stereo frame
        val byteRate = sampleRate * blockAlign          // 64000 bytes/sec at 16kHz
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytesWritten)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM format
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytesWritten)
        }
        try {
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.seek(0)
                raf.write(header.array())
            }
        } catch (e: Exception) {
            // Log/handle file header finalization exception
        }
    }
}
