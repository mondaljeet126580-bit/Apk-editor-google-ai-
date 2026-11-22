package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ApkEditorLightColorScheme =
  lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = PureWhite,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = TextPrimaryDark,
    secondary = TextSecondaryWarm,
    onSecondary = PureWhite,
    secondaryContainer = SoftPeachSurface,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = TerracottaPrimaryPressed,
    onTertiary = PureWhite,
    background = PureWhite,
    onBackground = TextPrimaryDark,
    surface = PureWhite,
    onSurface = TextPrimaryDark,
    surfaceVariant = SoftPeachSurface,
    onSurfaceVariant = TextSecondaryWarm,
    surfaceContainerLowest = PureWhite,
    surfaceContainerLow = SoftPeachSurface,
    surfaceContainer = SoftPeachSurface,
    surfaceContainerHigh = SoftPeachSurfacePressed,
    surfaceContainerHighest = TerracottaContainer,
    outline = InputUnderlineColor,
    outlineVariant = DividerWarm,
    error = ErrorRed,
    onError = PureWhite,
  )

val ApkEditorShapes =
  Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(26.dp),
  )

@Composable
fun ApkEditorTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = ApkEditorLightColorScheme,
    typography = ApkEditorTypography,
    shapes = ApkEditorShapes,
    content = content,
  )
}
