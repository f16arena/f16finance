package kz.ordaops.android.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kz.ordaops.android.ui.theme.Orda

/**
 * Волнистая полоса прогресса (Material 3 Expressive) вместо плоской линии.
 * Размеры и цвета те же, что были у LinearProgressIndicator: меняется только
 * форма линии, а не её место в вёрстке.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Orda.colors.brand,
    trackColor: Color = Orda.colors.surfaceRaised,
) {
    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
    )
}
