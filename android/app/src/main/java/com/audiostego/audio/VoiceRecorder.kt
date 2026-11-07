package com.audiostego.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.audiostego.stego.StegoException
import java.io.File
import java.io.IOException

/** Кратък глас като OGG/OPUS, който после се крие като файл. Иска API 29. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 29) {
            throw StegoException("Записът на глас работи на Android 10 и по-нов (OGG/OPUS)")
        }
        cancel()
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.ogg")
        val rec = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(16_000)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: IOException) {
            rec.release()
            out.delete()
            throw StegoException("Записът не тръгна")
        } catch (e: RuntimeException) {
            rec.release()
            out.delete()
            throw StegoException("Това устройство не записа OGG/OPUS")
        }
        recorder = rec
        file = out
    }

    fun stop(): ByteArray {
        val rec = recorder ?: throw StegoException("Няма активен запис")
        try {
            rec.stop()
        } catch (_: RuntimeException) {
            rec.release()
            recorder = null
            file?.delete()
            file = null
            throw StegoException("Записът е твърде кратък или беше прекъснат")
        }
        rec.release()
        recorder = null
        val recorded = file ?: throw StegoException("Няма запис")
        val bytes = recorded.readBytes()
        recorded.delete()
        file = null
        if (bytes.isEmpty()) throw StegoException("Празен запис")
        return bytes
    }

    fun cancel() {
        val rec = recorder ?: return
        try {
            rec.stop()
        } catch (_: RuntimeException) {
        }
        try {
            rec.release()
        } catch (_: RuntimeException) {
        }
        recorder = null
        file?.delete()
        file = null
    }
}
