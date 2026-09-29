package com.example.medicalrecord

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object MedicalPalette {
    val Ink = Color(0xFF19312F)
    val Muted = Color(0xFF60716D)
    val Teal = Color(0xFF176B60)
    val DeepTeal = Color(0xFF123F3B)
    val Mint = Color(0xFFDDF1E9)
    val Canvas = Color(0xFFF5F7F4)
    val Outline = Color(0xFFDCE5DF)
    val Amber = Color(0xFF9D601D)
}

private val colors = lightColorScheme(
    primary = MedicalPalette.Teal,
    onPrimary = Color.White,
    primaryContainer = MedicalPalette.Mint,
    onPrimaryContainer = MedicalPalette.DeepTeal,
    secondary = Color(0xFF527A70),
    onSecondary = Color.White,
    background = MedicalPalette.Canvas,
    onBackground = MedicalPalette.Ink,
    surface = Color.White,
    onSurface = MedicalPalette.Ink,
    surfaceVariant = Color(0xFFEDF3EF),
    onSurfaceVariant = MedicalPalette.Muted,
    outline = MedicalPalette.Outline,
    error = Color(0xFFB44F42),
    errorContainer = Color(0xFFFBE8E4)
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun MedicalRecordTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
