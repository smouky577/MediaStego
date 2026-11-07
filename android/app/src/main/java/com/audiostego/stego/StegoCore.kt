package com.audiostego.stego

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Общ формат за WAV и PNG. [samples] е плоският масив s[0..N-1]:
 * int16 проби при звук, стойности 0..255 на R,G,B при картина.
 * Пише се и се чете само най-младшият бит.
 */
object StegoCore {
    const val HEADER_BITS = 160
    const val GCM_OVERHEAD = 28
    private const val PBKDF2_ITERS = 200_000

    fun maxPayloadBytes(sampleCount: Int): Int {
        val pool = sampleCount - HEADER_BITS
        if (pool < GCM_OVERHEAD * 8) return 0
        return pool / 8 - GCM_OVERHEAD
    }

    fun embed(
        samples: IntArray,
        payload: ByteArray,
        password: String,
        salt: ByteArray? = null,
        nonce: ByteArray? = null,
        progress: ((String, Float) -> Unit)? = null,
    ): IntArray {
        if (samples.size < HEADER_BITS) throw StegoException("Носителят е твърде къс")
        val pool = samples.size - HEADER_BITS
        val need = (payload.size + GCM_OVERHEAD) * 8
        if (need > pool) throw StegoException("Твърде голямо: нужни $need бита, налични $pool")

        progress?.invoke("Ключ от паролата…", 0.08f)
        val saltBytes = salt ?: random(16)
        val nonceBytes = nonce ?: random(12)
        if (saltBytes.size != 16) throw StegoException("salt трябва да е 16 байта")
        if (nonceBytes.size != 12) throw StegoException("nonce трябва да е 12 байта")
        val (ekey, pkey) = derive(password, saltBytes)
        progress?.invoke("Криптиране…", 0.22f)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ekey, "AES"), GCMParameterSpec(128, nonceBytes))
        val body = concat(nonceBytes, cipher.doFinal(payload))
        if (body.size != payload.size + GCM_OVERHEAD) throw StegoException("Вътрешна грешка в AES-GCM")

        val headerBits = bytesToBits(concat(saltBytes, u32be(body.size)))
        val bodyBits = bytesToBits(body)
        val out = samples.copyOf()
        for (i in headerBits.indices) out[i] = setLsb(out[i], headerBits[i])
        progress?.invoke("Вграждане…", 0.3f)
        var lastPct = -1
        val places = shufflePositions(pool, bodyBits.size, CtrStream(pkey, bodyBits.size), sparseChoice(pool, bodyBits.size)) { done ->
            val pct = (done * 100f).toInt()
            if (pct != lastPct) {
                lastPct = pct
                progress?.invoke("Вграждане…", 0.3f + 0.65f * done)
            }
        }
        for (i in bodyBits.indices) out[HEADER_BITS + places[i]] = setLsb(out[HEADER_BITS + places[i]], bodyBits[i])
        progress?.invoke("Готово", 1f)
        return out
    }

    fun extract(
        samples: IntArray,
        password: String,
        progress: ((String, Float) -> Unit)? = null,
    ): ByteArray {
        if (samples.size < HEADER_BITS) throw StegoException("Няма съобщение или файлът е повреден")
        val header = bitsToBytes(IntArray(HEADER_BITS) { samples[it] and 1 })
        val salt = header.copyOfRange(0, 16)
        val length = readU32be(header, 16)
        val pool = samples.size - HEADER_BITS
        if (length < GCM_OVERHEAD || length * 8L > pool) {
            throw StegoException("Няма съобщение или файлът е повреден")
        }
        val nbits = (length * 8L).toInt()
        progress?.invoke("Ключ от паролата…", 0.08f)
        val (ekey, pkey) = derive(password, salt)
        progress?.invoke("Четене…", 0.3f)
        var lastPct = -1
        val places = shufflePositions(pool, nbits, CtrStream(pkey, nbits), sparseChoice(pool, nbits)) { done ->
            val pct = (done * 100f).toInt()
            if (pct != lastPct) {
                lastPct = pct
                progress?.invoke("Четене…", 0.3f + 0.5f * done)
            }
        }
        val body = bitsToBytes(IntArray(nbits) { samples[HEADER_BITS + places[it]] and 1 })
        progress?.invoke("Проверка на паролата…", 0.9f)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(ekey, "AES"),
                GCMParameterSpec(128, body.copyOfRange(0, 12)),
            )
            cipher.doFinal(body.copyOfRange(12, body.size))
        } catch (_: GeneralSecurityException) {
            throw StegoException("Грешна парола или няма съобщение")
        }
    }

    fun decodeUtf8OrNull(data: ByteArray): String? {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    internal fun shufflePositions(
        pool: Int,
        nbits: Int,
        next: () -> Long,
        sparse: Boolean,
        onStep: ((Float) -> Unit)? = null,
    ): IntArray {
        if (nbits < 0 || pool < nbits) throw StegoException("Няма съобщение или файлът е повреден")
        return if (sparse) shuffleSparse(pool, nbits, next, onStep) else shuffleFull(pool, nbits, next, onStep)
    }

    internal fun sparseChoice(pool: Int, nbits: Int): Boolean {
        return pool > 65_536 && nbits.toLong() * 32 < pool.toLong()
    }

    private fun shuffleFull(pool: Int, nbits: Int, next: () -> Long, onStep: ((Float) -> Unit)?): IntArray {
        val idx = IntArray(pool) { it }
        val out = IntArray(nbits)
        var last = -1
        for (i in 0 until nbits) {
            val j = i + (next() % (pool - i).toLong()).toInt()
            val vi = idx[i]
            val vj = idx[j]
            idx[i] = vj
            idx[j] = vi
            out[i] = vj
            if (onStep != null) report(onStep, i, nbits, last).also { last = it }
        }
        return out
    }

    private fun shuffleSparse(pool: Int, nbits: Int, next: () -> Long, onStep: ((Float) -> Unit)?): IntArray {
        val override = HashMap<Int, Int>(nbits * 2)
        val out = IntArray(nbits)
        var last = -1
        for (i in 0 until nbits) {
            val j = i + (next() % (pool - i).toLong()).toInt()
            val vi = override[i] ?: i
            val vj = override[j] ?: j
            override[i] = vj
            override[j] = vi
            out[i] = vj
            if (onStep != null) last = report(onStep, i, nbits, last)
        }
        return out
    }

    private fun report(onStep: (Float) -> Unit, i: Int, nbits: Int, last: Int): Int {
        val pct = ((i + 1) * 100) / nbits
        if (pct != last) onStep((i + 1).toFloat() / nbits)
        return pct
    }

    private fun derive(password: String, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERS, 64 * 8)
        try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            val key = if (encoded != null && encoded.size == 64) {
                encoded
            } else {
                pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERS, 64)
            }
            return key.copyOfRange(0, 32) to key.copyOfRange(32, 64)
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val block = ByteArray(salt.size + 4)
        salt.copyInto(block)
        for (i in 1..blocks) {
            block[salt.size] = (i ushr 24).toByte()
            block[salt.size + 1] = (i ushr 16).toByte()
            block[salt.size + 2] = (i ushr 8).toByte()
            block[salt.size + 3] = i.toByte()
            var u = mac.doFinal(block)
            val t = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * hLen)
        }
        return out.copyOf(dkLen)
    }

    private class CtrStream(pkey: ByteArray, nbits: Int) : () -> Long {
        private val raw: ByteArray
        private var index = 0

        init {
            val bytes = nbits.toLong() * 4L
            if (nbits < 0 || bytes > Int.MAX_VALUE) {
                throw StegoException("Твърде голямо: нужни повече битове, отколкото носителят позволява")
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pkey, "AES"), IvParameterSpec(ByteArray(16)))
            raw = cipher.doFinal(ByteArray(bytes.toInt()))
            if (raw.size != bytes.toInt()) throw StegoException("Вътрешна грешка в AES-CTR")
        }

        override fun invoke(): Long {
            val o = index
            index += 4
            return ((raw[o].toLong() and 0xFF) shl 24) or
                ((raw[o + 1].toLong() and 0xFF) shl 16) or
                ((raw[o + 2].toLong() and 0xFF) shl 8) or
                (raw[o + 3].toLong() and 0xFF)
        }
    }

    private fun bytesToBits(data: ByteArray): IntArray {
        val bits = IntArray(data.size * 8)
        var k = 0
        for (raw in data) {
            val value = raw.toInt() and 0xFF
            for (shift in 7 downTo 0) bits[k++] = (value shr shift) and 1
        }
        return bits
    }

    private fun bitsToBytes(bits: IntArray): ByteArray {
        val out = ByteArray(bits.size / 8)
        for (i in out.indices) {
            var value = 0
            val base = i * 8
            for (b in 0 until 8) value = (value shl 1) or (bits[base + b] and 1)
            out[i] = value.toByte()
        }
        return out
    }

    private fun setLsb(sample: Int, bit: Int): Int = (sample and 1.inv()) or (bit and 1)

    private fun u32be(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readU32be(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        a.copyInto(out)
        b.copyInto(out, a.size)
        return out
    }

    private fun random(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
