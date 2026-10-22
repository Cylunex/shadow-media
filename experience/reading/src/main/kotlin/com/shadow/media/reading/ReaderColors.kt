package com.shadow.media.reading

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Menus and sheets use the same paper palette as the page, including their selected states. */
internal fun readerColorScheme(paper: ReaderPaper): androidx.compose.material3.ColorScheme {
    val night = paper == ReaderPaper.NIGHT
    val base = Color(paper.background)
    val ink = Color(paper.foreground)
    val accent = when (paper) {
        ReaderPaper.NIGHT -> Color(0xFFB7C9D8)
        ReaderPaper.GREEN -> Color(0xFF35563D)
        else -> Color(0xFF72522B)
    }
    return (if (night) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = if (night) Color(0xFF173042) else Color.White,
        primaryContainer = lerp(base, accent, .16f), onPrimaryContainer = ink,
        secondary = accent, onSecondary = if (night) Color(0xFF173042) else Color.White,
        secondaryContainer = lerp(base, accent, .12f), onSecondaryContainer = ink,
        background = base, onBackground = ink, surface = base, onSurface = ink,
        surfaceVariant = lerp(base, ink, .08f), onSurfaceVariant = lerp(ink, base, .18f),
        outline = lerp(base, ink, .5f), outlineVariant = lerp(base, ink, .18f),
        surfaceContainerLowest = base, surfaceContainerLow = lerp(base, ink, .035f),
        surfaceContainer = lerp(base, ink, .05f), surfaceContainerHigh = lerp(base, ink, .08f),
        surfaceContainerHighest = lerp(base, ink, .1f), surfaceTint = accent,
    )
}
