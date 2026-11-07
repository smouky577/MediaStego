package com.audiostego.stego

/** Съвместим вход за WAV. Криптото е в [StegoCore], пробите минават през [AudioCodec]. */
object AudioStego {
    const val HEADER_BITS = StegoCore.HEADER_BITS
    const val GCM_OVERHEAD = StegoCore.GCM_OVERHEAD

    fun maxPayloadBytes(sampleCount: Int): Int = StegoCore.maxPayloadBytes(sampleCount)

    fun embed(
        wav: WavPcm,
        payload: ByteArray,
        password: String,
        salt: ByteArray? = null,
        nonce: ByteArray? = null,
        progress: ((String, Float) -> Unit)? = null,
    ): WavPcm = AudioCodec.embed(wav, payload, password, salt, nonce, progress)

    fun extract(
        wav: WavPcm,
        password: String,
        progress: ((String, Float) -> Unit)? = null,
    ): ByteArray = AudioCodec.extract(wav, password, progress)

    fun decodeUtf8OrNull(data: ByteArray): String? = StegoCore.decodeUtf8OrNull(data)

    internal fun shufflePositions(
        pool: Int,
        nbits: Int,
        next: () -> Long,
        sparse: Boolean,
        onStep: ((Float) -> Unit)? = null,
    ): IntArray = StegoCore.shufflePositions(pool, nbits, next, sparse, onStep)

    internal fun sparseChoice(pool: Int, nbits: Int): Boolean = StegoCore.sparseChoice(pool, nbits)
}
