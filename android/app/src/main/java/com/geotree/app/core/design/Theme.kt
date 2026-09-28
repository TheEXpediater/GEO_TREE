package com.geotree.app.core.design

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

object GeoColors {
    val Forest = Color(0xFF1F3D2B)
    val ForestDeep = Color(0xFF142A1D)
    val Moss = Color(0xFF5E7A3A)
    val MossLight = Color(0xFFDDE6C8)
    val Clay = Color(0xFFB5653A)
    val ClayLight = Color(0xFFF3DCCB)
    val Sand = Color(0xFFF4EEDF)
    val Cream = Color(0xFFFBF8F1)
    val Charcoal = Color(0xFF23262A)
    val Stone = Color(0xFF5C605A)
    val Outline = Color(0xFFC9C3B2)
    val GpsAmber = Color(0xFFC98512)
    val GpsAmberLight = Color(0xFFFBEBCB)
    val Success = Color(0xFF2E7D4F)
    val SuccessLight = Color(0xFFD6EFDF)
    val Error = Color(0xFFB3261E)
    val ErrorLight = Color(0xFFF9DEDC)
}

private val LightScheme = lightColorScheme(
    primary = GeoColors.Forest,
    onPrimary = Color.White,
    primaryContainer = GeoColors.MossLight,
    onPrimaryContainer = GeoColors.ForestDeep,
    secondary = GeoColors.Moss,
    onSecondary = Color.White,
    secondaryContainer = GeoColors.MossLight,
    onSecondaryContainer = GeoColors.ForestDeep,
    tertiary = GeoColors.Clay,
    onTertiary = Color.White,
    tertiaryContainer = GeoColors.ClayLight,
    onTertiaryContainer = Color(0xFF4A2410),
    background = GeoColors.Sand,
    onBackground = GeoColors.Charcoal,
    surface = GeoColors.Cream,
    onSurface = GeoColors.Charcoal,
    surfaceVariant = Color(0xFFEAE3D2),
    onSurfaceVariant = GeoColors.Stone,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = GeoColors.Cream,
    surfaceContainer = Color(0xFFF6F1E5),
    surfaceContainerHigh = Color(0xFFF0EADB),
    surfaceContainerHighest = Color(0xFFEAE3D2),
    outline = GeoColors.Outline,
    outlineVariant = Color(0xFFE0D9C8),
    error = GeoColors.Error,
    onError = Color.White,
    errorContainer = GeoColors.ErrorLight,
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA9C98A),
    onPrimary = GeoColors.ForestDeep,
    primaryContainer = Color(0xFF2F4D38),
    onPrimaryContainer = GeoColors.MossLight,
    secondary = Color(0xFFBFD19E),
    tertiary = Color(0xFFE5A67F),
    background = Color(0xFF131813),
    onBackground = Color(0xFFE5E2D8),
    surface = Color(0xFF1A201B),
    onSurface = Color(0xFFE5E2D8),
    surfaceVariant = Color(0xFF2D332C),
    onSurfaceVariant = Color(0xFFC3C8BB),
    outline = Color(0xFF8C9285),
    error = Color(0xFFF2B8B5),
)

private val GeoTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Monospaced digits for coordinates so values don't jitter while updating. */
val CoordinateTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Medium)

@Composable
fun GeoTreeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = GeoTypography,
        content = content,
    )
}
