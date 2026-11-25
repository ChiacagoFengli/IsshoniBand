package com.isshoniband.v2.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** 一套配色：主色调 + 强调色，深浅各一版 */
data class Palette(
    val id: String,
    val label: String,
    val primaryDark: Color,
    val accentDark: Color,
    val primaryLight: Color,
    val accentLight: Color
)

val Palettes: List<Palette> = listOf(
    Palette(
        id = "mint", label = "薄荷青",
        primaryDark = Color(0xFF5CE1B9), accentDark = Color(0xFF7FD8FF),
        primaryLight = Color(0xFF047857), accentLight = Color(0xFF0369A1)
    ),
    Palette(
        id = "amber", label = "琥珀",
        primaryDark = Color(0xFFFCD34D), accentDark = Color(0xFFFDBA74),
        primaryLight = Color(0xFFB45309), accentLight = Color(0xFFC2410C)
    ),
    Palette(
        id = "violet", label = "紫罗兰",
        primaryDark = Color(0xFFC4B5FD), accentDark = Color(0xFFF0ABFC),
        primaryLight = Color(0xFF6D28D9), accentLight = Color(0xFFA21CAF)
    ),
    Palette(
        id = "sakura", label = "樱花粉",
        primaryDark = Color(0xFFF9A8D4), accentDark = Color(0xFFFDA4AF),
        primaryLight = Color(0xFFBE185D), accentLight = Color(0xFF9F1239)
    ),
    Palette(
        id = "ocean", label = "海蓝",
        primaryDark = Color(0xFF7DD3FC), accentDark = Color(0xFF5EEAD4),
        primaryLight = Color(0xFF0369A1), accentLight = Color(0xFF0F766E)
    ),
    Palette(
        id = "slate", label = "石墨",
        primaryDark = Color(0xFFCBD5E1), accentDark = Color(0xFF7DD3FC),
        primaryLight = Color(0xFF475569), accentLight = Color(0xFF0369A1)
    ),
    Palette(
        id = "wine", label = "酒红",
        primaryDark = Color(0xFFFDA4AF), accentDark = Color(0xFFC4B5FD),
        primaryLight = Color(0xFFBE123C), accentLight = Color(0xFF6D28D9)
    ),
    Palette(
        id = "lime", label = "青柠",
        primaryDark = Color(0xFFBEF264), accentDark = Color(0xFF86EFAC),
        primaryLight = Color(0xFF4D7C0F), accentLight = Color(0xFF15803D)
    )
)

fun paletteById(id: String): Palette = Palettes.firstOrNull { it.id == id } ?: Palettes.first()

private val BgDark = Color(0xFF0B0F0E)
private val SurfaceDark = Color(0xFF121817)
private val SurfaceVariantDark = Color(0xFF1B2422)
private val OnSurfaceDark = Color(0xFFE2E9E6)
private val OnSurfaceVariantDark = Color(0xFF93A39E)
private val OutlineDark = Color(0xFF2A3533)

private val BgLight = Color(0xFFF7FAF8)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceVariantLight = Color(0xFFE4ECE9)
private val OnSurfaceLight = Color(0xFF191C1B)
private val OnSurfaceVariantLight = Color(0xFF3F4945)
private val OutlineLight = Color(0xFFBFC9C5)

/** 按底色亮度挑前景色，保证文字可读 */
private fun onColor(background: Color): Color =
    if (background.luminance() > 0.55f) Color(0xFF101413) else Color(0xFFFFFFFF)

private fun schemeOf(palette: Palette, dark: Boolean): ColorScheme {
    val primary = if (dark) palette.primaryDark else palette.primaryLight
    val accent = if (dark) palette.accentDark else palette.accentLight
    val bg = if (dark) BgDark else BgLight
    val surface = if (dark) SurfaceDark else SurfaceLight
    val surfaceVariant = if (dark) SurfaceVariantDark else SurfaceVariantLight
    val onSurface = if (dark) OnSurfaceDark else OnSurfaceLight
    val onSurfaceVariant = if (dark) OnSurfaceVariantDark else OnSurfaceVariantLight
    val outline = if (dark) OutlineDark else OutlineLight

    val primaryContainer = primary.copy(alpha = if (dark) 0.18f else 0.16f).compositeOver(surface)
    val onPrimaryContainer = if (dark) primary else palette.primaryLight
    val accentContainer = accent.copy(alpha = if (dark) 0.16f else 0.14f).compositeOver(surface)

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = accent,
            onSecondary = onColor(accent),
            secondaryContainer = accentContainer,
            onSecondaryContainer = accent,
            tertiary = accent,
            onTertiary = onColor(accent),
            tertiaryContainer = accentContainer,
            onTertiaryContainer = accent,
            background = bg,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = Color(0xFFFF8A80)
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = accent,
            onSecondary = onColor(accent),
            secondaryContainer = accentContainer,
            onSecondaryContainer = accent,
            tertiary = accent,
            onTertiary = onColor(accent),
            tertiaryContainer = accentContainer,
            onTertiaryContainer = accent,
            background = bg,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = Color(0xFFB3261E)
        )
    }
}

@Composable
fun IsshoniTheme(
    paletteId: String = Palettes.first().id,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = schemeOf(paletteById(paletteId), darkTheme),
        content = content
    )
}
