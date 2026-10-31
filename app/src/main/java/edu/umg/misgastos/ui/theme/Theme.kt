package edu.umg.misgastos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColors = lightColorScheme(
    primary = Forest,
    onPrimary = White,
    primaryContainer = Forest,
    onPrimaryContainer = White,
    secondary = IncomeGreen,
    onSecondary = White,
    tertiary = ExpenseCoral,
    background = Cream,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = PaleMint,
    onSurfaceVariant = MutedGreen,
    error = ExpenseCoral,
    errorContainer = PaleCoral,
    onErrorContainer = Ink
)

@Composable
fun MISGASTOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = Typography, content = content)
}
