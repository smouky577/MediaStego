package com.audiostego.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiostego.audio.VoiceRecorder
import com.audiostego.stego.AudioCodec
import com.audiostego.stego.ImageCodec
import com.audiostego.stego.RgbImage
import com.audiostego.stego.StegoCore
import com.audiostego.stego.StegoException
import com.audiostego.stego.WavPcm
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PayloadMode { TEXT, FILE, VOICE }

data class HideUi(
    val coverName: String? = null,
    val coverInfo: String? = null,
    val carrier: String? = null,
    val outputMime: String = "application/octet-stream",
    val mode: PayloadMode = PayloadMode.TEXT,
    val text: String = "",
    val fileName: String? = null,
    val voiceLabel: String? = null,
    val recording: Boolean = false,
    val recordStart: Long = 0L,
    val password: String = "",
    val showPassword: Boolean = false,
    val busy: Boolean = false,
    val progress: String = "",
    val progressValue: Float = 0f,
    val error: String? = null,
    val doneMessage: String? = null,
    val outputUri: Uri? = null,
    val outputName: String? = null,
)

data class ExtractUi(
    val stegoName: String? = null,
    val password: String = "",
    val showPassword: Boolean = false,
    val busy: Boolean = false,
    val progress: String = "",
    val progressValue: Float = 0f,
    val error: String? = null,
    val text: String? = null,
    val pendingName: String? = null,
    val pendingSize: Int = 0,
    val savedUri: Uri? = null,
    val savedName: String? = null,
)

class StegoViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    private val recorder = VoiceRecorder(app)
    private var coverBytes: ByteArray? = null
    private var fileBytes: ByteArray? = null
    private var voiceBytes: ByteArray? = null
    private var stegoBytes: ByteArray? = null
    private var pendingBytes: ByteArray? = null

    private val _hide = MutableStateFlow(HideUi())
    val hide: StateFlow<HideUi> = _hide.asStateFlow()

    private val _extract = MutableStateFlow(ExtractUi())
    val extract: StateFlow<ExtractUi> = _extract.asStateFlow()

    fun setMode(mode: PayloadMode) = _hide.update { it.copy(mode = mode, error = null) }

    fun setText(value: String) = _hide.update { it.copy(text = value, error = null) }

    fun setHidePassword(value: String) = _hide.update { it.copy(password = value, error = null) }

    fun setExtractPassword(value: String) = _extract.update { it.copy(password = value, error = null) }

    fun toggleHidePassword() = _hide.update { it.copy(showPassword = !it.showPassword) }

    fun toggleExtractPassword() = _extract.update { it.copy(showPassword = !it.showPassword) }

    fun showHideError(message: String) = _hide.update { it.copy(error = message, busy = false) }

    fun onCoverPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = displayName(uri)
                val bytes = readUri(uri)
                val opened = inspect(bytes)
                coverBytes = bytes
                _hide.update {
                    it.copy(
                        coverName = name,
                        coverInfo = opened.info,
                        carrier = opened.kind,
                        error = null,
                        doneMessage = null,
                        outputUri = null,
                        outputName = null,
                        outputMime = "application/octet-stream",
                    )
                }
            } catch (e: StegoException) {
                coverBytes = null
                _hide.update { it.copy(coverName = null, coverInfo = null, error = e.message) }
            } catch (e: Exception) {
                coverBytes = null
                _hide.update { it.copy(error = e.message ?: "Файлът не може да се прочете") }
            }
        }
    }

    fun onPayloadPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = displayName(uri)
                val bytes = readUri(uri)
                fileBytes = bytes
                _hide.update { it.copy(fileName = "$name · ${formatBytes(bytes.size.toLong())}", error = null, mode = PayloadMode.FILE) }
            } catch (e: Exception) {
                fileBytes = null
                _hide.update { it.copy(error = e.message ?: "Файлът не може да се прочете") }
            }
        }
    }

    fun startVoice() {
        if (_hide.value.recording) return
        try {
            recorder.start()
            _hide.update {
                it.copy(
                    recording = true,
                    recordStart = System.currentTimeMillis(),
                    error = null,
                    voiceLabel = null,
                    mode = PayloadMode.VOICE,
                )
            }
            voiceBytes = null
        } catch (e: StegoException) {
            _hide.update { it.copy(recording = false, error = e.message) }
        }
    }

    fun stopVoice() {
        if (!_hide.value.recording) return
        try {
            val bytes = recorder.stop()
            voiceBytes = bytes
            _hide.update {
                it.copy(
                    recording = false,
                    voiceLabel = "глас.ogg · ${formatBytes(bytes.size.toLong())}",
                    error = null,
                )
            }
        } catch (e: StegoException) {
            voiceBytes = null
            _hide.update { it.copy(recording = false, error = e.message) }
        }
    }

    fun clearVoice() {
        recorder.cancel()
        voiceBytes = null
        _hide.update { it.copy(recording = false, voiceLabel = null) }
    }

    fun hideBlocker(): String? {
        val state = _hide.value
        if (coverBytes == null) return "Избери WAV или PNG"
        if (state.password.isEmpty()) return "Въведи парола"
        return when (state.mode) {
            PayloadMode.TEXT -> if (state.text.isEmpty()) "Напиши текста" else null
            PayloadMode.FILE -> if (fileBytes == null) "Избери файл" else null
            PayloadMode.VOICE -> if (voiceBytes == null) "Няма записан глас" else null
        }
    }

    fun suggestedOutputName(): String {
        val ext = if (_hide.value.carrier == "PNG") "png" else "wav"
        val base = _hide.value.coverName?.substringBeforeLast('.')?.ifBlank { "media" } ?: "media"
        return "$base-stego.$ext"
    }

    fun embedTo(uri: Uri, name: String) {
        val state = _hide.value
        val cover = coverBytes
        if (cover == null) {
            showHideError("Избери WAV или PNG")
            return
        }
        if (state.password.isEmpty()) {
            showHideError("Въведи парола")
            return
        }
        val payload = when (state.mode) {
            PayloadMode.TEXT -> {
                if (state.text.isEmpty()) {
                    showHideError("Напиши текста")
                    return
                }
                state.text.encodeToByteArray()
            }
            PayloadMode.FILE -> fileBytes ?: run {
                showHideError("Избери файл")
                return
            }
            PayloadMode.VOICE -> voiceBytes ?: run {
                showHideError("Няма записан глас")
                return
            }
        }
        viewModelScope.launch {
            _hide.update { it.copy(busy = true, error = null, doneMessage = null, progress = "Четене на носителя…", progressValue = 0.02f) }
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = embedCover(cover, state.carrier, payload, state.password) { message, fraction ->
                        _hide.update { it.copy(progress = message, progressValue = fraction) }
                    }
                    writeUri(uri, bytes)
                    bytes.size
                }
                _hide.update {
                    it.copy(
                        busy = false,
                        progress = "",
                        progressValue = 1f,
                        doneMessage = "Готово · ${formatBytes(result.toLong())}",
                        outputUri = uri,
                        outputName = name,
                        outputMime = if (state.carrier == "PNG") "image/png" else "application/octet-stream",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _hide.update { it.copy(busy = false, progress = "", error = friendly(e)) }
            }
        }
    }

    fun onStegoPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = displayName(uri)
                val bytes = readUri(uri)
                inspect(bytes)
                stegoBytes = bytes
                pendingBytes = null
                _extract.update {
                    it.copy(
                        stegoName = name,
                        error = null,
                        text = null,
                        pendingName = null,
                        savedUri = null,
                        savedName = null,
                    )
                }
            } catch (e: StegoException) {
                stegoBytes = null
                _extract.update { it.copy(stegoName = null, error = e.message) }
            } catch (e: Exception) {
                stegoBytes = null
                _extract.update { it.copy(error = e.message ?: "Файлът не може да се прочете") }
            }
        }
    }

    fun extract() {
        val state = _extract.value
        val bytes = stegoBytes
        if (bytes == null) {
            _extract.update { it.copy(error = "Избери WAV или PNG") }
            return
        }
        if (state.password.isEmpty()) {
            _extract.update { it.copy(error = "Въведи парола") }
            return
        }
        viewModelScope.launch {
            _extract.update {
                it.copy(busy = true, error = null, text = null, pendingName = null, savedUri = null, progress = "Четене на носителя…", progressValue = 0.02f)
            }
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val data = extractCover(bytes, state.password) { message, fraction ->
                        _extract.update { it.copy(progress = message, progressValue = fraction) }
                    }
                    val text = StegoCore.decodeUtf8OrNull(data)
                    if (text != null) ExtractOutcome.Text(text) else ExtractOutcome.File(data, suggestedFileName(data))
                }
                when (outcome) {
                    is ExtractOutcome.Text -> {
                        pendingBytes = null
                        _extract.update { it.copy(busy = false, progress = "", text = outcome.value, pendingName = null) }
                    }
                    is ExtractOutcome.File -> {
                        pendingBytes = outcome.bytes
                        _extract.update {
                            it.copy(
                                busy = false,
                                progress = "",
                                text = null,
                                pendingName = outcome.name,
                                pendingSize = outcome.bytes.size,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _extract.update { it.copy(busy = false, progress = "", error = friendly(e)) }
            }
        }
    }

    fun saveExtracted(uri: Uri) {
        val bytes = pendingBytes ?: return
        val name = _extract.value.pendingName ?: "extracted.bin"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                writeUri(uri, bytes)
                _extract.update { it.copy(savedUri = uri, savedName = name, error = null) }
            } catch (e: Exception) {
                _extract.update { it.copy(error = e.message ?: "Файлът не може да се запише") }
            }
        }
    }

    override fun onCleared() {
        recorder.cancel()
    }

    private fun inspect(bytes: ByteArray): Inspected {
        if (ImageCodec.isJpeg(bytes)) throw StegoException(ImageCodec.JPEG_MESSAGE)
        if (ImageCodec.isPng(bytes)) {
            val image = ImageCodec.decode(bytes)
            val room = StegoCore.maxPayloadBytes(image.samples.size).toLong()
            return Inspected("PNG", "PNG · ${image.width}×${image.height} · до ${formatBytes(room)}")
        }
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            return Inspected("WAV", describe(WavPcm.read(bytes)))
        }
        throw StegoException("Нужен е 16-bit PCM WAV или PNG с 8 бита на канал")
    }

    private fun embedCover(
        cover: ByteArray,
        carrier: String?,
        payload: ByteArray,
        password: String,
        progress: (String, Float) -> Unit,
    ): ByteArray {
        return if (carrier == "PNG") {
            val image = ImageCodec.decode(cover)
            val samples = StegoCore.embed(image.samples, payload, password, progress = progress)
            ImageCodec.encode(RgbImage(image.width, image.height, samples))
        } else {
            val wav = WavPcm.read(cover)
            WavPcm.write(AudioCodec.embed(wav, payload, password, progress = progress))
        }
    }

    private fun extractCover(
        bytes: ByteArray,
        password: String,
        progress: (String, Float) -> Unit,
    ): ByteArray {
        return if (ImageCodec.isPng(bytes)) {
            StegoCore.extract(ImageCodec.decode(bytes).samples, password, progress)
        } else {
            AudioCodec.extract(WavPcm.read(bytes), password, progress)
        }
    }

    private fun describe(wav: WavPcm): String {
        val frames = wav.samples.size / wav.channels
        val total = if (wav.sampleRate > 0) frames / wav.sampleRate else 0
        val channel = when (wav.channels) {
            1 -> "моно"
            2 -> "стерео"
            else -> "${wav.channels} канала"
        }
        val room = StegoCore.maxPayloadBytes(wav.samples.size).toLong()
        val mm = total / 60
        val ss = (total % 60).toString().padStart(2, '0')
        return "${wav.sampleRate} Hz · $channel · $mm:$ss · до ${formatBytes(room)}"
    }

    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "audio.wav"
            }
        }
        return "audio.wav"
    }

    private fun readUri(uri: Uri): ByteArray {
        return resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw StegoException("Файлът не може да се прочете")
    }

    private fun writeUri(uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw StegoException("Файлът не може да се запише")
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    private fun suggestedFileName(data: ByteArray): String {
        if (data.size >= 4 && data[0] == 'O'.code.toByte() && data[1] == 'g'.code.toByte() &&
            data[2] == 'g'.code.toByte() && data[3] == 'S'.code.toByte()
        ) {
            return "extracted.ogg"
        }
        if (data.size >= 4 && data[0] == '%'.code.toByte() && data[1] == 'P'.code.toByte()) return "extracted.pdf"
        if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte()) return "extracted.png"
        if (data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) return "extracted.jpg"
        return "extracted.bin"
    }

    private fun friendly(error: Throwable): String {
        if (error is OutOfMemoryError) return "Няма достатъчно памет за този носител"
        return error.message ?: "Грешка"
    }
}

private class Inspected(val kind: String, val info: String)

private sealed interface ExtractOutcome {
    class Text(val value: String) : ExtractOutcome
    class File(val bytes: ByteArray, val name: String) : ExtractOutcome
}

internal fun formatBytes(size: Long): String {
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
