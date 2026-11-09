package app.mangascribe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF0B0D12)
private val Paper = Color(0xFFF6F1E7)
private val Coral = Color(0xFFFF7A59)
private val Teal = Color(0xFF35C2A5)
private val Slate = Color(0xFF1A1F2A)

private val Dark = darkColorScheme(
    primary = Coral,
    onPrimary = Color(0xFF2A0D06),
    primaryContainer = Color(0xFF3A1A12),
    onPrimaryContainer = Color(0xFFFFD9CE),
    secondary = Teal,
    onSecondary = Color(0xFF00201A),
    background = Ink,
    onBackground = Color(0xFFEDE9E1),
    surface = Slate,
    onSurface = Color(0xFFEDE9E1),
    surfaceVariant = Color(0xFF232A36),
    onSurfaceVariant = Color(0xFFB9C0CC),
    error = Color(0xFFFF6B6B),
)

private val Light = lightColorScheme(
    primary = Color(0xFFD7492A),
    onPrimary = Color.White,
    secondary = Color(0xFF0E7F6B),
    background = Paper,
    onBackground = Color(0xFF14161B),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF14161B),
    surfaceVariant = Color(0xFFEDE6D8),
    onSurfaceVariant = Color(0xFF4A4E57),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp),
)

@Composable
fun MangaScribeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
