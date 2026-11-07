package com.audiostego.stego

/** WAV -> плосък масив от int16 проби, interleaved, после обратно. */
object AudioCodec {
    fun embed(
        wav: WavPcm,
        payload: ByteArray,
        password: String,
        salt: ByteArray? = null,
        nonce: ByteArray? = null,
        progress: ((String, Float) -> Unit)? = null,
    ): WavPcm {
        if (wav.samples.size < StegoCore.HEADER_BITS) {
            throw StegoException("Нужен е по-дълъг 16-bit PCM WAV")
        }
        val input = IntArray(wav.samples.size) { wav.samples[it].toInt() }
        val output = StegoCore.embed(input, payload, password, salt, nonce, progress)
        return WavPcm(wav.channels, wav.sampleRate, ShortArray(output.size) { output[it].toShort() })
    }

    fun extract(
        wav: WavPcm,
        password: String,
        progress: ((String, Float) -> Unit)? = null,
    ): ByteArray {
        val input = IntArray(wav.samples.size) { wav.samples[it].toInt() }
        return StegoCore.extract(input, password, progress)
    }
}
