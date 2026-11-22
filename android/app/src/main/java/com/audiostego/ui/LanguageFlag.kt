package com.audiostego.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun LanguageFlag(english: Boolean, onClick: () -> Unit) {
    Canvas(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .size(width = 30.dp, height = 18.dp),
    ) {
        if (english) {
            drawUnionJack()
        } else {
            val band = size.height / 3f
            drawRect(Color.White, size = Size(size.width, band))
            drawRect(Color(0xFF00966E), topLeft = Offset(0f, band), size = Size(size.width, band))
            drawRect(Color(0xFFD62612), topLeft = Offset(0f, band * 2f), size = Size(size.width, band))
        }
        drawRect(Color(0xFF3A4250), style = Stroke(width = 1.dp.toPx()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnionJack() {
    val width = size.width
    val height = size.height
    drawRect(Color(0xFF012169))
    val white = Color.White
    val red = Color(0xFFC8102E)
    val whiteSaltire = height * 0.22f
    val redSaltire = height * 0.09f
    drawLine(white, Offset(0f, 0f), Offset(width, height), whiteSaltire, StrokeCap.Butt)
    drawLine(white, Offset(width, 0f), Offset(0f, height), whiteSaltire, StrokeCap.Butt)
    drawLine(red, Offset(0f, 0f), Offset(width, height), redSaltire, StrokeCap.Butt)
    drawLine(red, Offset(width, 0f), Offset(0f, height), redSaltire, StrokeCap.Butt)
    val whiteBar = height * 0.34f
    val redBar = height * 0.18f
    drawRect(white, topLeft = Offset((width - whiteBar) / 2f, 0f), size = Size(whiteBar, height))
    drawRect(white, topLeft = Offset(0f, (height - whiteBar) / 2f), size = Size(width, whiteBar))
    drawRect(red, topLeft = Offset((width - redBar) / 2f, 0f), size = Size(redBar, height))
    drawRect(red, topLeft = Offset(0f, (height - redBar) / 2f), size = Size(width, redBar))
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
