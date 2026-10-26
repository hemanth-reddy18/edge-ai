package com.edge.reception

import com.edge.reception.audio.StereoCallWavRecorder
import com.edge.reception.audio.WavTo16kHzPcmConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioProcessingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testStereoCallWavRecorderCreatesValidStereoHeaderAndInterleaves() {
        val testFile = File(tempFolder.root, "test_stereo.wav")
        val recorder = StereoCallWavRecorder(testFile, sampleRate = 16000)
        recorder.start()

        // Caller PCM chunk (2 samples: 1000, 2000)
        val callerChunk = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1000.toShort())
            .putShort(2000.toShort())
            .array()

        // Write Caller chunk (Left channel)
        recorder.writeMonoChunkAsStereo(callerChunk, isCallerLeftChannel = true)

        // AI PCM chunk (2 samples: 3000, 4000)
        val aiChunk = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(3000.toShort())
            .putShort(4000.toShort())
            .array()

        // Write AI chunk (Right channel)
        recorder.writeMonoChunkAsStereo(aiChunk, isCallerLeftChannel = false)

        recorder.closeAndFinalizeHeader()

        assertTrue(testFile.exists())
        val bytes = testFile.readBytes()
        assertTrue(bytes.size >= 44)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Verify RIFF header
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        assertEquals("RIFF", riff)

        // Channels = 2
        val channels = buf.getShort(22).toInt()
        assertEquals(2, channels)

        // Sample rate = 16000
        val sampleRate = buf.getInt(24)
        assertEquals(16000, sampleRate)

        // Byte rate = 64000 (16000 * 2 * 2)
        val byteRate = buf.getInt(28)
        assertEquals(64000, byteRate)

        // Block align = 4 (2 channels * 2 bytes)
        val blockAlign = buf.getShort(32).toInt()
        assertEquals(4, blockAlign)

        // Bits per sample = 16
        val bitsPerSample = buf.getShort(34).toInt()
        assertEquals(16, bitsPerSample)

        // Verify interleaved audio data starting at byte 44
        // Chunk 1 (Caller): L=1000, R=0; L=2000, R=0
        assertEquals(1000.toShort(), buf.getShort(44))
        assertEquals(0.toShort(), buf.getShort(46))
        assertEquals(2000.toShort(), buf.getShort(48))
        assertEquals(0.toShort(), buf.getShort(50))

        // Chunk 2 (AI): L=0, R=3000; L=0, R=4000
        assertEquals(0.toShort(), buf.getShort(52))
        assertEquals(3000.toShort(), buf.getShort(54))
        assertEquals(0.toShort(), buf.getShort(56))
        assertEquals(4000.toShort(), buf.getShort(58))
    }

    @Test
    fun testWavTo16kHzPcmConverterParsesAndResamplesFrom24kHz() {
        val wav24k = File(tempFolder.root, "sample_24k.wav")
        // Create 24kHz 1-channel WAV file with 240 samples (10ms)
        val sampleCount = 240
        val dataBytes = sampleCount * 2
        val wavBuffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // 1 channel
            putInt(24000) // 24 kHz
            putInt(48000) // Byte rate
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
            for (i in 0 until sampleCount) {
                putShort(5000.toShort())
            }
        }
        wav24k.writeBytes(wavBuffer.array())

        val resampled = WavTo16kHzPcmConverter.decodeAndResampleTo16kMono(wav24k)
        // Ratio 24000 / 16000 = 1.5. 240 / 1.5 = 160 samples -> 320 bytes
        assertEquals(320, resampled.size)

        val resampledBuf = ByteBuffer.wrap(resampled).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(5000.toShort(), resampledBuf.getShort(0))
    }
}
