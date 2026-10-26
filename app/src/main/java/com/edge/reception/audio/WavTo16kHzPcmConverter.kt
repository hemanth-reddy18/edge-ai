package com.edge.reception.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavTo16kHzPcmConverter {

    /**
     * Safely locates the "data" chunk of a RIFF WAV file (skipping extraneous chunks like LIST or JUNK),
     * extracts 16-bit mono PCM samples, and resamples to 16000 Hz using linear interpolation if needed.
     */
    fun decodeAndResampleTo16kMono(wavFile: File): ByteArray {
        if (!wavFile.exists()) return ByteArray(0)
        val bytes = wavFile.readBytes()
        if (bytes.size < 44) return ByteArray(0)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numChannels = buf.getShort(22).toInt().coerceAtLeast(1)
        val srcRate = buf.getInt(24)

        // Locate actual "data" chunk offset safely
        var offset = 12
        var dataOffset = 44
        var dataSize = bytes.size - 44
        while (offset + 8 <= bytes.size && offset >= 0) {
            val chunkId = try {
                String(bytes, offset, 4, Charsets.US_ASCII)
            } catch (_: Exception) {
                break
            }
            val chunkSize = buf.getInt(offset + 4)
            if (chunkId == "data") {
                dataOffset = offset + 8
                dataSize = if (chunkSize > 0) chunkSize.coerceAtMost(bytes.size - dataOffset) else (bytes.size - dataOffset)
                break
            }
            if (chunkSize <= 0 || chunkSize > bytes.size) {
                // If chunk size is invalid, break and fallback to standard 44 header
                break
            }
            offset += 8 + chunkSize + (chunkSize and 1)
        }

        val frameCount = dataSize / (2 * numChannels)
        if (frameCount <= 0) return ByteArray(0)

        val monoSamples = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            val byteIdx = dataOffset + i * 2 * numChannels
            if (byteIdx + 1 < bytes.size) {
                monoSamples[i] = buf.getShort(byteIdx)
            }
        }

        if (srcRate == 16000) {
            val out = ByteBuffer.allocate(monoSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            monoSamples.forEach { out.putShort(it) }
            return out.array()
        }

        // Linear interpolation resampling to 16000 Hz
        val ratio = srcRate.toDouble() / 16000.0
        val outCount = (monoSamples.size / ratio).toInt()
        val outBuf = ByteBuffer.allocate(outCount * 2).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until outCount) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, monoSamples.size - 1)
            val idx1 = (idx0 + 1).coerceIn(0, monoSamples.size - 1)
            val frac = srcPos - idx0
            val interpolated = (monoSamples[idx0] * (1.0 - frac) + monoSamples[idx1] * frac).toInt()
            outBuf.putShort(interpolated.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return outBuf.array()
    }
}
