package com.audiostego.stego

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Същите вектори, но върху крипто доставчика на устройството. */
@RunWith(AndroidJUnit4::class)
class DeviceCompatTest {
    @Test
    fun pythonWavRoundTripOnDevice() {
        for (name in listOf("mono", "stereo", "tight", "junk")) {
            val payload = asset("payload_$name.bin")
            val cover = WavPcm.read(asset("cover_$name.wav"))
            val expected = asset("from_python_$name.wav")
            val actual = WavPcm.write(
                AudioStego.embed(cover, payload, Vectors.PASSWORD, Vectors.SALT, Vectors.NONCE),
            )
            assertTrue(name, actual.contentEquals(expected))
            val extracted = AudioStego.extract(WavPcm.read(expected), Vectors.PASSWORD)
            assertTrue("$name extract", extracted.contentEquals(payload))
        }
    }

    @Test
    fun pythonPngRoundTripOnDevice() {
        val payload = asset("payload_png.bin")
        val cover = ImageCodec.decode(asset("cover_png.png"))
        val expected = readRgb(asset("from_python_png.rgb"))
        assertEquals(expected.width, cover.width)
        assertEquals(expected.height, cover.height)
        assertTrue(cover.samples.contentEquals(readRgb(asset("cover_png.rgb")).samples))
        val embedded = StegoCore.embed(cover.samples, payload, Vectors.PASSWORD, Vectors.SALT, Vectors.NONCE)
        assertTrue(embedded.contentEquals(expected.samples))
        val decoded = ImageCodec.decode(asset("from_python_png.png"))
        assertTrue(decoded.samples.contentEquals(expected.samples))
        assertTrue(StegoCore.extract(decoded.samples, Vectors.PASSWORD).contentEquals(payload))
        val rewritten = ImageCodec.decode(ImageCodec.encode(RgbImage(cover.width, cover.height, embedded)))
        assertTrue(rewritten.samples.contentEquals(embedded))
    }

    @Test
    fun rejectsJpegOnDevice() {
        val error = runCatching { ImageCodec.decode(asset("cover.jpg")) }.exceptionOrNull()
        assertTrue(error is StegoException)
        assertEquals(ImageCodec.JPEG_MESSAGE, error?.message)
    }

    @Test
    fun wrongPasswordOnDevice() {
        val wav = WavPcm.read(asset("from_python_mono.wav"))
        val error = runCatching { AudioStego.extract(wav, Vectors.WRONG_PASSWORD) }.exceptionOrNull()
        assertTrue(error is StegoException)
        assertEquals(Vectors.BAD_PASSWORD, error?.message)
    }

    private fun readRgb(bytes: ByteArray): RgbImage {
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val width = buffer.int
        val height = buffer.int
        val samples = IntArray(width * height * 3)
        for (i in samples.indices) samples[i] = buffer.get().toInt() and 0xFF
        return RgbImage(width, height, samples)
    }

    private fun asset(name: String): ByteArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open(name).use { it.readBytes() }
    }
}
