package top.cylunex.shadowmedia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val ShadowDarkColors = darkColorScheme(
    primary = Color(0xFF8AD8FF),
    onPrimary = Color(0xFF002F43),
    primaryContainer = Color(0xFF12394B),
    onPrimaryContainer = Color(0xFFC6EAFF),
    secondary = Color(0xFFC8BFFF),
    onSecondary = Color(0xFF2E285F),
    secondaryContainer = Color(0xFF342E64),
    onSecondaryContainer = Color(0xFFE5DFFF),
    tertiary = Color(0xFF72DFB3),
    onTertiary = Color(0xFF003827),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5B1D1D),
    background = Color(0xFF080A0E),
    onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF0F131A),
    onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF1A202A),
    onSurfaceVariant = Color(0xFFB6C1CE),
    outline = Color(0xFF3E4854),
)

private val ShadowLightColors = lightColorScheme(
    primary = Color(0xFF00658A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2E8FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF5B5790),
    onSecondary = Color.White,
    tertiary = Color(0xFF006C50),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF171C22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C22),
    surfaceVariant = Color(0xFFE6EBF1),
    onSurfaceVariant = Color(0xFF424A53),
)

private val ShadowTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun ShadowMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) ShadowDarkColors else ShadowLightColors,
        typography = ShadowTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        ),
        content = content,
    )
}
