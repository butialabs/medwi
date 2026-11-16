package br.com.butialabs.medwi.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Medwi brand palette, taken from logo.png:
 *   lime #8EE03A · vivid green #22B04B · deep rind green #0B5D12
 *   kiwi flesh #D4F25A -> #7ED321 · seed brown #3A2716 · cross white #FAFBF5
 * Primary is a darker take on the vivid green (#1A7F37) so white text on it meets AA.
 * Tertiary is amber: it marks "partial" days in the Progress calendar and has to read
 * clearly apart from green (complete) and red (missed).
 */

val LightColors = lightColorScheme(
    primary = Color(0xFF1A7F37),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9F2A1),
    onPrimaryContainer = Color(0xFF002108),
    inversePrimary = Color(0xFF8ED88A),
    secondary = Color(0xFF4C7A12),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8F3A6),
    onSecondaryContainer = Color(0xFF132000),
    tertiary = Color(0xFF8A6100),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF2B1D00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF1),
    onBackground = Color(0xFF181D17),
    surface = Color(0xFFF6FBF1),
    onSurface = Color(0xFF181D17),
    surfaceVariant = Color(0xFFDCE6D4),
    onSurfaceVariant = Color(0xFF404A3E),
    surfaceTint = Color(0xFF1A7F37),
    surfaceBright = Color(0xFFF6FBF1),
    surfaceDim = Color(0xFFD6DCD1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5EB),
    surfaceContainer = Color(0xFFEAF0E5),
    surfaceContainerHigh = Color(0xFFE4EAE0),
    surfaceContainerHighest = Color(0xFFDFE4DA),
    outline = Color(0xFF70796D),
    outlineVariant = Color(0xFFC0C9BB),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED88A),
    onPrimary = Color(0xFF003911),
    primaryContainer = Color(0xFF00531C),
    onPrimaryContainer = Color(0xFFB9F2A1),
    inversePrimary = Color(0xFF1A7F37),
    secondary = Color(0xFFB4DB75),
    onSecondary = Color(0xFF233600),
    secondaryContainer = Color(0xFF354E00),
    onSecondaryContainer = Color(0xFFD8F3A6),
    tertiary = Color(0xFFF3BF48),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF634000),
    onTertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F150E),
    onBackground = Color(0xFFDFE4DA),
    surface = Color(0xFF0F150E),
    onSurface = Color(0xFFDFE4DA),
    surfaceVariant = Color(0xFF404A3E),
    onSurfaceVariant = Color(0xFFC0C9BB),
    surfaceTint = Color(0xFF8ED88A),
    surfaceBright = Color(0xFF353B33),
    surfaceDim = Color(0xFF0F150E),
    surfaceContainerLowest = Color(0xFF0A1009),
    surfaceContainerLow = Color(0xFF181D17),
    surfaceContainer = Color(0xFF1C211B),
    surfaceContainerHigh = Color(0xFF262B25),
    surfaceContainerHighest = Color(0xFF31362F),
    outline = Color(0xFF8A9386),
    outlineVariant = Color(0xFF404A3E),
)
