package com.jarvispoc.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val JarvisBlue = Color(0xFF00E5FF)
val JarvisDarkBlue = Color(0xFF001F3F)
val JarvisBackground = Color(0xFF050B14)
val JarvisText = Color(0xFFE0F7FA)
val JarvisAccent = Color(0xFF00B4D8)
val JarvisDanger = Color(0xFFFF3D00)

val JarvisColorScheme = darkColorScheme(
    primary = JarvisBlue,
    onPrimary = Color.Black,
    secondary = JarvisAccent,
    onSecondary = Color.Black,
    background = JarvisBackground,
    onBackground = JarvisText,
    surface = JarvisDarkBlue.copy(alpha = 0.5f),
    onSurface = JarvisText,
    error = JarvisDanger,
    onError = Color.White
)

val JarvisTypography = androidx.compose.material3.Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = JarvisText
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        color = JarvisBlue
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = JarvisAccent
    )
)
