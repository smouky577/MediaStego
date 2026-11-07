package com.audiostego.stego

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatTest {
    @Test
    fun fisherYatesPathsMatch() {
        assertSameShuffle(80_000, 592)
        assertSameShuffle(400, 400)
        assertSameShuffle(29_840, 2_288)
        assertSameShuffle(3_840, 80)
    }

    @Test
    fun canonicalWavRoundTrip() {
        val original = file("cover_mono.wav").readBytes()
        val rewritten = WavPcm.write(WavPcm.read(original))
        assertSameBytes("cover_mono", original, rewritten)
        val stereo = file("cover_stereo.wav").readBytes()
        assertSameBytes("cover_stereo", stereo, WavPcm.write(WavPcm.read(stereo)))
    }

    @Test
    fun monoTextMatchesPython() = roundTrip("mono")

    @Test
    fun stereoBinaryMatchesPython() = roundTrip("stereo")

    @Test
    fun tightCapacityMatchesPython() = roundTrip("tight")

    @Test
    fun junkChunkMatchesPython() = roundTrip("junk")

    @Test
    fun wrongPassword() {
        val wav = WavPcm.read(file("from_python_mono.wav").readBytes())
        val error = runCatching { AudioStego.extract(wav, Vectors.WRONG_PASSWORD) }.exceptionOrNull()
        assertTrue(error is StegoException)
        assertEquals(Vectors.BAD_PASSWORD, error?.message)
    }

    @Test
    fun overflowMatchesPythonMessage() {
        val cover = WavPcm.read(file("cover_tight.wav").readBytes())
        val error = runCatching {
            AudioStego.embed(cover, Vectors.OVERFLOW, Vectors.PASSWORD, Vectors.SALT, Vectors.NONCE)
        }.exceptionOrNull()
        assertTrue(error is StegoException)
        assertEquals(Vectors.TOO_BIG, error?.message)
    }

    @Test
    fun pngMatchesPython() {
        val payload = file("payload_png.bin").readBytes()
        val cover = ImageCodec.decode(file("cover_png.png").readBytes())
        val expected = readRgb(file("from_python_png.rgb").readBytes())
        assertEquals(expected.width, cover.width)
        assertEquals(expected.height, cover.height)
        assertTrue(cover.samples.contentEquals(readRgb(file("cover_png.rgb").readBytes()).samples))
        val embedded = StegoCore.embed(cover.samples, payload, Vectors.PASSWORD, Vectors.SALT, Vectors.NONCE)
        for (i in cover.samples.indices) {
            assertEquals("LSB-only png @$i", cover.samples[i] and 1.inv(), embedded[i] and 1.inv())
        }
        assertTrue(embedded.contentEquals(expected.samples))
        val encoded = ImageCodec.encode(RgbImage(cover.width, cover.height, embedded))
        File(dir(), "from_kotlin_png.png").writeBytes(encoded)
        val extracted = StegoCore.extract(ImageCodec.decode(file("from_python_png.png").readBytes()).samples, Vectors.PASSWORD)
        assertTrue(extracted.contentEquals(payload))
    }

    @Test
    fun rejectsJpegAndGrayPng() {
        val jpeg = runCatching { ImageCodec.decode(file("cover.jpg").readBytes()) }.exceptionOrNull()
        assertTrue(jpeg is StegoException)
        assertEquals(ImageCodec.JPEG_MESSAGE, jpeg?.message)
        val gray = runCatching { ImageCodec.decode(file("cover_gray.png").readBytes()) }.exceptionOrNull()
        assertTrue(gray is StegoException)
        assertEquals("PNG трябва да е RGB, 8 бита на канал", gray?.message)
    }

    @Test
    fun rejectsNonPcm() {
        for (name in listOf("cover_8bit.wav", "not_wav.bin")) {
            val error = runCatching { WavPcm.read(file(name).readBytes()) }.exceptionOrNull()
            assertTrue(name, error is StegoException)
            assertEquals(name, Vectors.NOT_PCM, error?.message)
        }
    }

    private fun roundTrip(name: String) {
        val payload = file("payload_$name.bin").readBytes()
        val coverBytes = file("cover_$name.wav").readBytes()
        val expected = file("from_python_$name.wav").readBytes()
        val cover = WavPcm.read(coverBytes)
        val embedded = AudioStego.embed(cover, payload, Vectors.PASSWORD, Vectors.SALT, Vectors.NONCE)
        for (i in cover.samples.indices) {
            val before = (cover.samples[i].toInt() and 1.inv()).toShort()
            val after = (embedded.samples[i].toInt() and 1.inv()).toShort()
            assertEquals("LSB-only $name @$i", before, after)
        }
        val actual = WavPcm.write(embedded)
        File(dir(), "from_kotlin_$name.wav").writeBytes(actual)
        assertSameBytes(name, expected, actual)
        val extracted = AudioStego.extract(WavPcm.read(expected), Vectors.PASSWORD)
        assertTrue("$name extract", extracted.contentEquals(payload))
    }

    private fun assertSameShuffle(pool: Int, nbits: Int) {
        val values = LongArray(nbits) { index -> (index.toLong() * 2654435761L) and 0xffffffffL }
        var fullAt = 0
        var sparseAt = 0
        val full = AudioStego.shufflePositions(pool, nbits, { values[fullAt++] }, sparse = false)
        val sparse = AudioStego.shufflePositions(pool, nbits, { values[sparseAt++] }, sparse = true)
        assertTrue("shuffle $pool/$nbits", full.contentEquals(sparse))
        assertEquals(nbits, full.toSet().size)
        assertTrue(full.all { it in 0 until pool })
    }

    private fun assertSameBytes(name: String, expected: ByteArray, actual: ByteArray) {
        if (expected.contentEquals(actual)) return
        val limit = minOf(expected.size, actual.size)
        var at = 0
        while (at < limit && expected[at] == actual[at]) at++
        error("$name: разлика при байт $at (python ${expected.size}, kotlin ${actual.size})")
    }

    private fun readRgb(bytes: ByteArray): RgbImage {
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val width = buffer.int
        val height = buffer.int
        val samples = IntArray(width * height * 3)
        for (i in samples.indices) samples[i] = buffer.get().toInt() and 0xFF
        return RgbImage(width, height, samples)
    }

    private fun dir(): File {
        val path = System.getProperty("audiostego.testdata")
            ?: error("Липсва audiostego.testdata. Пусни python/test_compat.py")
        return File(path)
    }

    private fun file(name: String): File {
        val item = File(dir(), name)
        check(item.isFile) { "Липсва ${item.absolutePath}. Пусни python/test_compat.py" }
        return item
    }
}
