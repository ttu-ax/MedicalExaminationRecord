package com.example.medicalrecord

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
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

@Immutable
data class MedicalSemanticColors(
    val flagHigh: Color,
    val flagHighContainer: Color,
    val flagLow: Color,
    val flagLowContainer: Color,
    val statusPending: Color,
    val statusPendingContainer: Color,
    val statusConfirmed: Color,
    val statusConfirmedContainer: Color,
    val heroGradientStart: Color,
    val heroGradientEnd: Color,
    val cardBorder: Color
)

val LocalMedicalColors = staticCompositionLocalOf {
    MedicalSemanticColors(
        flagHigh = Color(0xFFC62828),
        flagHighContainer = Color(0xFFFFEBEE),
        flagLow = Color(0xFF1565C0),
        flagLowContainer = Color(0xFFE3F2FD),
        statusPending = Color(0xFFB45309),
        statusPendingContainer = Color(0xFFFEF3C7),
        statusConfirmed = Color(0xFF16A34A),
        statusConfirmedContainer = Color(0xFFDCFCE7),
        heroGradientStart = Color(0xFF123F3B),
        heroGradientEnd = Color(0xFF1B554F),
        cardBorder = Color(0x1F19312F)
    )
}

private val LightMedicalColors = MedicalSemanticColors(
    flagHigh = Color(0xFFC62828),
    flagHighContainer = Color(0xFFFFEBEE),
    flagLow = Color(0xFF1565C0),
    flagLowContainer = Color(0xFFE3F2FD),
    statusPending = Color(0xFFB45309),
    statusPendingContainer = Color(0xFFFEF3C7),
    statusConfirmed = Color(0xFF16A34A),
    statusConfirmedContainer = Color(0xFFDCFCE7),
    heroGradientStart = Color(0xFF123F3B),
    heroGradientEnd = Color(0xFF1A5852),
    cardBorder = Color(0x1A19312F)
)

private val DarkMedicalColors = MedicalSemanticColors(
    flagHigh = Color(0xFFEF5350),
    flagHighContainer = Color(0xFF3E1F1F),
    flagLow = Color(0xFF42A5F5),
    flagLowContainer = Color(0xFF192A3E),
    statusPending = Color(0xFFFBBF24),
    statusPendingContainer = Color(0xFF3D2C13),
    statusConfirmed = Color(0xFF4ADE80),
    statusConfirmedContainer = Color(0xFF163823),
    heroGradientStart = Color(0xFF0F2C29),
    heroGradientEnd = Color(0xFF17423D),
    cardBorder = Color(0x33FFFFFF)
)

val MaterialTheme.medicalColors: MedicalSemanticColors
    @Composable
    get() = LocalMedicalColors.current

private val LightColorScheme = lightColorScheme(
    primary = MedicalPalette.Teal,
    onPrimary = Color.White,
    primaryContainer = MedicalPalette.Mint,
    onPrimaryContainer = MedicalPalette.DeepTeal,
    secondary = Color(0xFF527A70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EBE7),
    onSecondaryContainer = Color(0xFF123F3B),
    background = MedicalPalette.Canvas,
    onBackground = MedicalPalette.Ink,
    surface = Color.White,
    onSurface = MedicalPalette.Ink,
    surfaceVariant = Color(0xFFEDF3EF),
    onSurfaceVariant = MedicalPalette.Muted,
    outline = MedicalPalette.Outline,
    outlineVariant = Color(0xFFE8EFEA),
    error = Color(0xFFB44F42),
    errorContainer = Color(0xFFFBE8E4),
    onErrorContainer = Color(0xFF4A1009)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF1B3D37),
    onPrimaryContainer = Color(0xFFA7F0D8),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF243B37),
    onSecondaryContainer = Color(0xFFBCECE3),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF1A1F1D),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF232A28),
    onSurfaceVariant = Color(0xFF909A96),
    outline = Color(0xFF303B37),
    outlineVariant = Color(0xFF252D2A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF6B1D16),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val typography = Typography(
    headlineMedium = TextStyle(
        fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    ),
    headlineSmall = TextStyle(
        fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum"
    ),
    titleLarge = TextStyle(
        fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 21.sp,
        fontFeatureSettings = "tnum"
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp,
        fontFeatureSettings = "tnum"
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum"
    )
)

@Composable
fun MedicalRecordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkMedicalColors else LightMedicalColors

    CompositionLocalProvider(LocalMedicalColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
