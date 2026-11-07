package com.audiostego.stego

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs

/** 8-bit RGB или RGBA PNG, без interlace. Alpha не влиза в масива. */
class RgbImage(
    val width: Int,
    val height: Int,
    val samples: IntArray,
)

object PngRgb {
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isPng(bytes: ByteArray): Boolean {
        return bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }
    }

    fun requireRgb8(bytes: ByteArray) {
        val info = header(bytes)
        if (info.bitDepth != 8 || (info.colorType != 2 && info.colorType != 6)) {
            throw StegoException("PNG трябва да е RGB, 8 бита на канал")
        }
        if (info.interlace != 0) throw StegoException("PNG с interlace не се поддържа")
    }

    fun decode(bytes: ByteArray): RgbImage {
        requireRgb8(bytes)
        val info = header(bytes)
        val bpp = if (info.colorType == 2) 3 else 4
        val rowBytes = info.width * bpp
        val inflated = inflate(collectIdat(bytes))
        val expected = info.height * (rowBytes + 1)
        if (inflated.size < expected) throw StegoException("PNG е повреден")
        val recon = unfilter(inflated, info.height, rowBytes, bpp)
        val samples = IntArray(info.width * info.height * 3)
        var dst = 0
        for (y in 0 until info.height) {
            val row = y * rowBytes
            for (x in 0 until info.width) {
                val i = row + x * bpp
                samples[dst++] = recon[i].toInt() and 0xFF
                samples[dst++] = recon[i + 1].toInt() and 0xFF
                samples[dst++] = recon[i + 2].toInt() and 0xFF
            }
        }
        return RgbImage(info.width, info.height, samples)
    }

    fun encode(image: RgbImage): ByteArray {
        if (image.samples.size != image.width * image.height * 3) {
            throw StegoException("PNG е повреден")
        }
        val raw = ByteArray(image.height * (1 + image.width * 3))
        var sample = 0
        var out = 0
        for (y in 0 until image.height) {
            raw[out++] = 0
            repeat(image.width * 3) {
                raw[out++] = (image.samples[sample++] and 0xFF).toByte()
            }
        }
        val ihdr = ByteArray(13)
        writeU32(ihdr, 0, image.width)
        writeU32(ihdr, 4, image.height)
        ihdr[8] = 8
        ihdr[9] = 2
        val body = ByteArrayOutputStream()
        body.write(SIGNATURE)
        body.write(chunk("IHDR", ihdr))
        body.write(chunk("IDAT", deflate(raw)))
        body.write(chunk("IEND", ByteArray(0)))
        return body.toByteArray()
    }

    private class Info(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int, val interlace: Int)

    private fun header(bytes: ByteArray): Info {
        if (!isPng(bytes)) throw StegoException("Нужен е PNG с 8 бита на канал, не формат със загуби")
        val chunk = readChunk(bytes, 8) ?: throw StegoException("PNG е повреден")
        if (chunk.type != "IHDR" || chunk.data.size < 13) throw StegoException("PNG е повреден")
        val width = readU32(chunk.data, 0)
        val height = readU32(chunk.data, 4)
        if (width <= 0 || height <= 0 || width > 100_000 || height > 100_000) {
            throw StegoException("PNG е повреден")
        }
        return Info(width, height, chunk.data[8].toInt() and 0xFF, chunk.data[9].toInt() and 0xFF, chunk.data[12].toInt() and 0xFF)
    }

    private class Chunk(val type: String, val data: ByteArray, val next: Int)

    private fun readChunk(bytes: ByteArray, at: Int): Chunk? {
        if (at + 8 > bytes.size) return null
        val length = readU32(bytes, at)
        if (length < 0 || at + 12L + length > bytes.size) throw StegoException("PNG е повреден")
        val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
        val data = bytes.copyOfRange(at + 8, at + 8 + length)
        val crc = CRC32()
        crc.update(bytes, at + 4, 4 + length)
        val stored = readU32(bytes, at + 8 + length).toLong() and 0xFFFFFFFFL
        if (crc.value != stored) throw StegoException("PNG е повреден")
        return Chunk(type, data, at + 12 + length)
    }

    private fun collectIdat(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 8
        var sawEnd = false
        while (true) {
            val chunk = readChunk(bytes, at) ?: break
            if (chunk.type == "IDAT") out.write(chunk.data)
            if (chunk.type == "IEND") {
                sawEnd = true
                break
            }
            at = chunk.next
        }
        if (!sawEnd || out.size() == 0) throw StegoException("PNG е повреден")
        return out.toByteArray()
    }

    private fun unfilter(src: ByteArray, height: Int, rowBytes: Int, bpp: Int): ByteArray {
        val recon = ByteArray(height * rowBytes)
        var s = 0
        for (y in 0 until height) {
            val filter = src[s++].toInt() and 0xFF
            val row = y * rowBytes
            for (x in 0 until rowBytes) {
                val raw = src[s++].toInt() and 0xFF
                val a = if (x >= bpp) recon[row + x - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) recon[row - rowBytes + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= bpp) recon[row - rowBytes + x - bpp].toInt() and 0xFF else 0
                val value = when (filter) {
                    0 -> raw
                    1 -> raw + a
                    2 -> raw + b
                    3 -> raw + (a + b) / 2
                    4 -> raw + paeth(a, b, c)
                    else -> throw StegoException("PNG е повреден")
                }
                recon[row + x] = (value and 0xFF).toByte()
            }
        }
        return recon
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
                else if (inflater.needsInput()) break
            }
            out.toByteArray()
        } catch (_: Exception) {
            throw StegoException("PNG е повреден")
        } finally {
            inflater.end()
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(12 + data.size)
        writeU32(out, 0, data.size)
        typeBytes.copyInto(out, 4)
        data.copyInto(out, 8)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeU32(out, 8 + data.size, crc.value.toInt())
        return out
    }

    private fun readU32(data: ByteArray, at: Int): Int {
        return ((data[at].toInt() and 0xFF) shl 24) or
            ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or
            (data[at + 3].toInt() and 0xFF)
    }

    private fun writeU32(data: ByteArray, at: Int, value: Int) {
        data[at] = (value ushr 24).toByte()
        data[at + 1] = (value ushr 16).toByte()
        data[at + 2] = (value ushr 8).toByte()
        data[at + 3] = value.toByte()
    }
}
