package com.shadow.media.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme

internal val ShadowDarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F4), onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF282828), onPrimaryContainer = Color(0xFFF1F1F1),
    secondary = Color(0xFFC4C4C4), onSecondary = Color(0xFF181818),
    secondaryContainer = Color(0xFF222222), onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFB8B8B8), onTertiary = Color(0xFF171717),
    tertiaryContainer = Color(0xFF303030), onTertiaryContainer = Color(0xFFEAEAEA),
    background = Color(0xFF101010), onBackground = Color(0xFFF4F4F4),
    surface = Color(0xFF171717), onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF252525), onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceDim = Color(0xFF101010), surfaceBright = Color(0xFF333333),
    surfaceContainerLowest = Color(0xFF0C0C0C), surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1B1B1B), surfaceContainerHigh = Color(0xFF242424), surfaceContainerHighest = Color(0xFF2D2D2D),
    outline = Color(0xFF666666), outlineVariant = Color(0xFF333333),
    inverseSurface = Color(0xFFE8E8E8), inverseOnSurface = Color(0xFF202020), inversePrimary = Color(0xFF242424),
    surfaceTint = Color.Transparent,
    error = Color(0xFFFFB4AB), onError = Color(0xFF300909),
    errorContainer = Color(0xFF5B1D1D), onErrorContainer = Color(0xFFFFDAD6),
)

internal val ShadowLightColors = lightColorScheme(
    primary = Color(0xFF171717), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8), onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF515151), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0), onSecondaryContainer = Color(0xFF252525),
    tertiary = Color(0xFF555555), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2E2E2), onTertiaryContainer = Color(0xFF252525),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF171717),
    surface = Color.White, onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF646464),
    surfaceDim = Color(0xFFE3E3E3), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2), surfaceContainerHigh = Color(0xFFECECEC), surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = Color(0xFF777777), outlineVariant = Color(0xFFDDDDDD),
    inverseSurface = Color(0xFF242424), inverseOnSurface = Color(0xFFF4F4F4), inversePrimary = Color(0xFFEAEAEA),
    surfaceTint = Color.Transparent,
)

private val ShadowTypography = Typography(
    displayLarge = TextStyle(fontSize = 42.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    displayMedium = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
)

@Composable
fun ShadowMediaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(preferences.getString("theme", "深色")) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "theme") mode = preferences.getString("theme", "深色") }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark = when (mode) { "浅色" -> false; "跟随系统" -> isSystemInDarkTheme(); else -> true }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) ShadowDarkColors else ShadowLightColors,
        typography = ShadowTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
