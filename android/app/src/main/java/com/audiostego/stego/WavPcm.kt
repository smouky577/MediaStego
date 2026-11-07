package com.audiostego.stego

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-bit PCM WAV. Пробите са interleaved int16, както ги вижда и Python `wave`:
 * един масив, без разделяне на канали.
 */
class WavPcm(
    val channels: Int,
    val sampleRate: Int,
    val samples: ShortArray,
) {
    companion object {
        private val PCM_SUBTYPE = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
        )

        fun read(data: ByteArray): WavPcm {
            if (data.size < 12 || fourcc(data, 0) != "RIFF" || fourcc(data, 8) != "WAVE") {
                throw StegoException("Нужен е 16-bit PCM WAV")
            }
            var offset = 12L
            val end = data.size.toLong()
            var channels = 0
            var rate = 0
            var blockAlign = 0
            var sawFmt = false
            var dataStart = -1L
            var dataSize = 0L
            while (offset + 8 <= end) {
                val at = offset.toInt()
                val id = fourcc(data, at)
                val chunkSize = u32le(data, at + 4)
                val start = offset + 8
                if (start > end) break
                when (id) {
                    "fmt " -> {
                        val parsed = parseFmt(data, start, chunkSize, end)
                        channels = parsed.channels
                        rate = parsed.rate
                        blockAlign = parsed.blockAlign
                        sawFmt = true
                    }
                    "data" -> {
                        if (!sawFmt) throw StegoException("Нужен е 16-bit PCM WAV")
                        dataStart = start
                        dataSize = chunkSize
                        break
                    }
                }
                val step = 8 + chunkSize + (chunkSize and 1L)
                if (step <= 0) break
                offset += step
            }
            if (!sawFmt || dataStart < 0 || channels < 1 || blockAlign != channels * 2) {
                throw StegoException("Нужен е 16-bit PCM WAV")
            }
            val available = (end - dataStart).coerceAtLeast(0)
            val usable = minOf(dataSize, available)
            val frames = usable / blockAlign
            val count = (frames * channels).toInt()
            if (count <= 0) throw StegoException("Нужен е 16-bit PCM WAV")
            val samples = ShortArray(count)
            val buf = ByteBuffer.wrap(data, dataStart.toInt(), count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) samples[i] = buf.short
            return WavPcm(channels, rate, samples)
        }

        fun write(wav: WavPcm): ByteArray {
            val dataBytes = wav.samples.size * 2
            val riffSize = 36 + dataBytes
            val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(ascii("RIFF"))
            buf.putInt(riffSize)
            buf.put(ascii("WAVE"))
            buf.put(ascii("fmt "))
            buf.putInt(16)
            buf.putShort(1)
            buf.putShort(wav.channels.toShort())
            buf.putInt(wav.sampleRate)
            buf.putInt(wav.sampleRate * wav.channels * 2)
            buf.putShort((wav.channels * 2).toShort())
            buf.putShort(16)
            buf.put(ascii("data"))
            buf.putInt(dataBytes)
            for (sample in wav.samples) buf.putShort(sample)
            return buf.array()
        }

        private fun parseFmt(data: ByteArray, start: Long, chunkSize: Long, end: Long): Fmt {
            if (chunkSize < 16 || start + 16 > end) throw StegoException("Нужен е 16-bit PCM WAV")
            val at = start.toInt()
            val format = u16le(data, at)
            val channels = u16le(data, at + 2)
            val rate = u32le(data, at + 4).toInt()
            val blockAlign = u16le(data, at + 12)
            val bits = u16le(data, at + 14)
            if (bits != 16 || channels < 1 || rate <= 0) {
                throw StegoException("Нужен е 16-bit PCM WAV")
            }
            when (format) {
                1 -> Unit
                0xFFFE -> {
                    if (chunkSize < 40 || start + 40 > end) throw StegoException("Нужен е 16-bit PCM WAV")
                    val sub = data.copyOfRange(at + 24, at + 40)
                    if (!sub.contentEquals(PCM_SUBTYPE)) throw StegoException("Нужен е 16-bit PCM WAV")
                }
                else -> throw StegoException("Нужен е 16-bit PCM WAV")
            }
            return Fmt(channels, rate, blockAlign)
        }

        private fun fourcc(data: ByteArray, at: Int): String {
            return String(data, at, 4, Charsets.US_ASCII)
        }

        private fun u16le(data: ByteArray, at: Int): Int {
            return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        }

        private fun u32le(data: ByteArray, at: Int): Long {
            return (data[at].toLong() and 0xFF) or
                ((data[at + 1].toLong() and 0xFF) shl 8) or
                ((data[at + 2].toLong() and 0xFF) shl 16) or
                ((data[at + 3].toLong() and 0xFF) shl 24)
        }

        private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

        private class Fmt(val channels: Int, val rate: Int, val blockAlign: Int)
    }
}
