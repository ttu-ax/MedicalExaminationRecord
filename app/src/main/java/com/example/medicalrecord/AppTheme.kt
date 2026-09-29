package com.example.medicalrecord

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = lightColorScheme(
    primary = Color(0xFF176B68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EFEB),
    onPrimaryContainer = Color(0xFF153E3B),
    secondary = Color(0xFF526C69),
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF1C2A29),
    surface = Color.White,
    onSurface = Color(0xFF1C2A29),
    surfaceVariant = Color(0xFFEEF3F1),
    onSurfaceVariant = Color(0xFF52615F),
    error = Color(0xFFB3261E)
)

@Composable
fun MedicalRecordTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
