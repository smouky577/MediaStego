package com.audiostego.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

@Composable
fun LanguageFlag(english: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(end = 8.dp)
            .size(width = 30.dp, height = 18.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (english) drawUnionJack() else drawBulgaria()
        }
    }
}

private fun DrawScope.drawBulgaria() {
    val band = size.height / 3f
    drawRect(Color.White, size = Size(size.width, band))
    drawRect(Color(0xFF00966E), topLeft = Offset(0f, band), size = Size(size.width, band))
    drawRect(Color(0xFFD62612), topLeft = Offset(0f, band * 2f), size = Size(size.width, size.height - band * 2f))
}

private fun DrawScope.drawUnionJack() {
    val w = size.width
    val h = size.height
    val blue = Color(0xFF012169)
    val red = Color(0xFFC8102E)
    drawRect(blue)
    val whiteDiag = h * 0.22f
    val redDiag = h * 0.08f
    drawLine(Color.White, Offset(0f, 0f), Offset(w, h), whiteDiag)
    drawLine(Color.White, Offset(w, 0f), Offset(0f, h), whiteDiag)
    drawLine(red, Offset(0f, redDiag), Offset(w, h), redDiag)
    drawLine(red, Offset(w, 0f), Offset(0f, h - redDiag), redDiag)
    val whiteCross = h * 0.36f
    val redCross = h * 0.2f
    drawRect(Color.White, Offset(0f, (h - whiteCross) / 2f), Size(w, whiteCross))
    drawRect(Color.White, Offset((w - whiteCross) / 2f, 0f), Size(whiteCross, h))
    drawRect(red, Offset(0f, (h - redCross) / 2f), Size(w, redCross))
    drawRect(red, Offset((w - redCross) / 2f, 0f), Size(redCross, h))
}

fun tr(english: Boolean, text: String): String {
    if (!english || text.isEmpty()) return text
    phrases[text]?.let { return it }
    val big = Regex("""Твърде голямо: нужни (\d+) бита, налични (\d+)""")
    big.matchEntire(text)?.let {
        return "Too big: need ${it.groupValues[1]} bits, available ${it.groupValues[2]}"
    }
    if (text.startsWith("Готово · ")) return "Done · " + text.removePrefix("Готово · ")
    return text
        .replace(" · моно · ", " · mono · ")
        .replace(" · стерео · ", " · stereo · ")
        .replace(" канала · ", " channels · ")
        .replace(" · до ", " · up to ")
}

private val phrases = mapOf(
    "Грешна парола или няма съобщение" to "Wrong password or no message",
    "Няма съобщение или файлът е повреден" to "No message, or the file is damaged",
    "Носителят е твърде къс" to "The carrier is too short",
    "Нужен е по-дълъг 16-bit PCM WAV" to "A longer 16-bit PCM WAV is required",
    "Нужен е 16-bit PCM WAV" to "A 16-bit PCM WAV is required",
    "Нужен е 16-bit PCM WAV или PNG с 8 бита на канал" to "A 16-bit PCM WAV or an 8-bit-per-channel PNG is required",
    "JPEG е със загуби — не ползвай като носител, конвертирай в PNG" to "JPEG is lossy — do not use it as a carrier, convert it to PNG",
    "PNG трябва да е RGB, 8 бита на канал" to "PNG must be RGB, 8 bits per channel",
    "Нужен е PNG с 8 бита на канал, не формат със загуби" to "A PNG with 8 bits per channel is required, not a lossy format",
    "PNG е повреден" to "The PNG is damaged",
    "PNG с interlace не се поддържа" to "Interlaced PNG is not supported",
    "PNG не може да се запише" to "The PNG cannot be saved",
    "Избери WAV или PNG" to "Choose a WAV or PNG",
    "Въведи парола" to "Enter a password",
    "Напиши текста" to "Type the text",
    "Избери файл" to "Choose a file",
    "Няма записан глас" to "No voice recording",
    "Файлът не може да се прочете" to "The file cannot be read",
    "Файлът не може да се запише" to "The file cannot be saved",
    "Няма достатъчно памет за този носител" to "Not enough memory for this carrier",
    "Грешка" to "Error",
    "Четене на носителя…" to "Reading the carrier…",
    "Ключ от паролата…" to "Key from the password…",
    "Криптиране…" to "Encrypting…",
    "Вграждане…" to "Embedding…",
    "Четене…" to "Reading…",
    "Проверка на паролата…" to "Checking the password…",
    "Готово" to "Done",
    "Няма достъп до микрофона" to "No microphone access",
    "Записът на глас работи на Android 10 и по-нов (OGG/OPUS)" to "Voice recording works on Android 10 and newer (OGG/OPUS)",
)
