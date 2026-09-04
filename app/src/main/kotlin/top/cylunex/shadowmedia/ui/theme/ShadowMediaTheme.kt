package top.cylunex.shadowmedia.ui.theme

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

private val ShadowDarkColors = darkColorScheme(
    primary = Color(0xFF76A9F5),
    onPrimary = Color(0xFF08234B),
    primaryContainer = Color(0xFF203A60),
    onPrimaryContainer = Color(0xFFDAE8FF),
    secondary = Color(0xFFC1C8C2),
    onSecondary = Color(0xFF1B211C),
    secondaryContainer = Color(0xFF252B26),
    onSecondaryContainer = Color(0xFFDEE5DE),
    tertiary = Color(0xFFCBB38A),
    onTertiary = Color(0xFF292116),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF300909),
    errorContainer = Color(0xFF5B1D1D),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101114),
    onBackground = Color(0xFFF0F2F5),
    surface = Color(0xFF191B20),
    onSurface = Color(0xFFF0F2F5),
    surfaceVariant = Color(0xFF24272E),
    onSurfaceVariant = Color(0xFFB7BDC9),
    outline = Color(0xFF454B57),
)

private val ShadowLightColors = lightColorScheme(
    primary = Color(0xFF3E8437),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF3CB),
    onPrimaryContainer = Color(0xFF0A220A),
    secondary = Color(0xFF59615A),
    onSecondary = Color.White,
    tertiary = Color(0xFF3E7F49),
    background = Color(0xFFF6F8F5),
    onBackground = Color(0xFF171C22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C22),
    surfaceVariant = Color(0xFFE7ECE7),
    onSurfaceVariant = Color(0xFF454C46),
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
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
