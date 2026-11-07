package com.audiostego.stego

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.ByteArrayOutputStream

/**
 * PNG -> плосък масив R,G,B (без alpha), ред по ред.
 * На устройство пикселите идват от Bitmap ARGB_8888 без premultiplied alpha.
 */
object ImageCodec {
    const val JPEG_MESSAGE = "JPEG е със загуби — не ползвай като носител, конвертирай в PNG"

    fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    fun isPng(bytes: ByteArray): Boolean = PngRgb.isPng(bytes)

    fun decode(bytes: ByteArray): RgbImage {
        if (isJpeg(bytes)) throw StegoException(JPEG_MESSAGE)
        if (!isPng(bytes)) throw StegoException("Нужен е PNG с 8 бита на канал, не формат със загуби")
        PngRgb.requireRgb8(bytes)
        return try {
            decodeBitmap(bytes)
        } catch (error: RuntimeException) {
            if (isStub(error)) PngRgb.decode(bytes) else throw error
        }
    }

    fun encode(image: RgbImage): ByteArray {
        return try {
            encodeBitmap(image)
        } catch (error: RuntimeException) {
            if (isStub(error)) PngRgb.encode(image) else throw error
        }
    }

    private fun decodeBitmap(bytes: ByteArray): RgbImage {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw StegoException("PNG е повреден")
        return try {
            try {
                bitmap.isPremultiplied = false
            } catch (_: RuntimeException) {
            }
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val samples = IntArray(argb.size * 3)
            var o = 0
            for (pixel in argb) {
                samples[o++] = (pixel ushr 16) and 0xFF
                samples[o++] = (pixel ushr 8) and 0xFF
                samples[o++] = pixel and 0xFF
            }
            RgbImage(bitmap.width, bitmap.height, samples)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeBitmap(image: RgbImage): ByteArray {
        val count = image.width * image.height
        if (image.samples.size != count * 3) throw StegoException("PNG е повреден")
        val argb = IntArray(count)
        var o = 0
        for (i in argb.indices) {
            val r = image.samples[o++] and 0xFF
            val g = image.samples[o++] and 0xFF
            val b = image.samples[o++] and 0xFF
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.isPremultiplied = false
            bitmap.setPixels(argb, 0, image.width, 0, 0, image.width, image.height)
            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw StegoException("PNG не може да се запише")
            }
            stream.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun isStub(error: RuntimeException): Boolean {
        val message = error.message ?: return false
        return message.contains("not mocked") || message.contains("Stub!")
    }
}
