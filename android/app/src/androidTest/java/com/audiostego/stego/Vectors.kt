package com.audiostego.stego

object Vectors {
    const val PASSWORD = "Тайна-парола"
    const val WRONG_PASSWORD = "грешна"
    val SALT: ByteArray = ByteArray(16) { it.toByte() }
    val NONCE: ByteArray = ByteArray(12) { (0x20 + it).toByte() }
    const val TOO_BIG = "Твърде голямо: нужни 408 бита, налични 400"
    const val BAD_PASSWORD = "Грешна парола или няма съобщение"
    const val NOT_PCM = "Нужен е 16-bit PCM WAV"
    val OVERFLOW: ByteArray = ByteArray(23) { 0xA5.toByte() }
}
