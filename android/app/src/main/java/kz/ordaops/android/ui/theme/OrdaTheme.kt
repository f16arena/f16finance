package kz.ordaops.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Цвета из apple/OrdaUI/Tokens.swift — те же пары «тёмная / светлая». */
@Immutable
data class OrdaColors(
    val background: Color,
    val elevated: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    val textDim: Color,
    val brand: Color,
    val brandBright: Color,
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val info: Color,
    val accent: Color,
    val isDark: Boolean,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

private val Dark = OrdaColors(
    background = c(0x081830), elevated = c(0x0C1F3C), surface = c(0x0F2544), surfaceRaised = c(0x15305A),
    border = c(0x1E3A63), text = c(0xF3F4F6), textMuted = c(0xB4BFD0), textDim = c(0x8593AA),
    brand = c(0x3B82F6), brandBright = c(0x60A5FA), positive = c(0x22C55E), negative = c(0xF87171),
    warning = c(0xF59E0B), info = c(0x60A5FA), accent = c(0x818CF8), isDark = true,
)

private val Light = OrdaColors(
    background = c(0xF6F6F3), elevated = c(0xFFFFFF), surface = c(0xFFFFFF), surfaceRaised = c(0xF3F4F6),
    border = c(0xE5E7EB), text = c(0x111827), textMuted = c(0x4B5563), textDim = c(0x6B7280),
    brand = c(0x2563EB), brandBright = c(0x3B82F6), positive = c(0x16A34A), negative = c(0xDC2626),
    warning = c(0xD97706), info = c(0x2563EB), accent = c(0x4F46E5), isDark = false,
)

val Navy = c(0x0B1F3B)

val LocalOrdaColors = staticCompositionLocalOf { Dark }

/** Отступы и скругления — Spacing / Radius из Tokens.swift. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

object Radius {
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 28.dp
}

object Orda {
    val colors: OrdaColors
        @Composable get() = LocalOrdaColors.current
}

@Composable
fun OrdaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.brand, onPrimary = Color.White, background = colors.background, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textMuted, outline = colors.border, error = colors.negative,
            surfaceContainer = colors.elevated,
        )
    } else {
        lightColorScheme(
            primary = colors.brand, onPrimary = Color.White, background = colors.background, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text, surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textMuted, outline = colors.border, error = colors.negative,
            surfaceContainer = colors.elevated,
        )
    }
    CompositionLocalProvider(LocalOrdaColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
