package com.audiostego.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF14160F)
val InkRaised = Color(0xFF22261B)
val InkHigh = Color(0xFF2C3224)
val Paper = Color(0xFFF4F1E6)
val PaperDim = Color(0xFFC9C4B4)
val Amber = Color(0xFFE4A04A)
val AmberDeep = Color(0xFFB8752A)
val Sage = Color(0xFF9CB089)
val Clay = Color(0xFFD46A58)
val Line = Color(0xFF3A4030)

private val Colors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Sage,
    background = Ink,
    surface = InkRaised,
    onBackground = Paper,
    onSurface = Paper,
    error = Clay,
)

private val Type = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        color = Paper,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = Paper,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        color = Paper,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        color = PaperDim,
    ),
)

@Composable
fun AudioStegoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}
