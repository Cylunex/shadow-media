package top.cylunex.shadowmedia.ui.theme

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
    primary = Color(0xFFE4FF71),
    onPrimary = Color(0xFF1B2100),
    primaryContainer = Color(0xFF303A0A),
    onPrimaryContainer = Color(0xFFEDFF9D),
    secondary = Color(0xFFA7C7FF),
    onSecondary = Color(0xFF082044),
    secondaryContainer = Color(0xFF162D50),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFF7DE1C3),
    onTertiary = Color(0xFF00382C),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5B1D1D),
    background = Color(0xFF050608),
    onBackground = Color(0xFFF0F2F5),
    surface = Color(0xFF101216),
    onSurface = Color(0xFFF0F2F5),
    surfaceVariant = Color(0xFF191C22),
    onSurfaceVariant = Color(0xFFB8BDC7),
    outline = Color(0xFF444A55),
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
    displayLarge = TextStyle(fontSize = 58.sp, lineHeight = 58.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.4).sp),
    headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
)

@Composable
fun ShadowMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShadowDarkColors,
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
