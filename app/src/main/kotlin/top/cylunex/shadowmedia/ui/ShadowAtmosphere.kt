package top.cylunex.shadowmedia.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ShadowBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val motion = rememberInfiniteTransition(label = "shadow-atmosphere")
    val phase by motion.animateFloat(
        initialValue = -0.12f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(12_000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shadow-atmosphere-phase",
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val background = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .background(background)
            .drawWithCache {
                onDrawBehind {
                    drawAtmosphere(background, primary, secondary, phase)
                }
            },
        content = content,
    )
}

private fun DrawScope.drawAtmosphere(
    background: Color,
    primary: Color,
    secondary: Color,
    phase: Float,
) {
    drawRect(
        Brush.linearGradient(
            colors = listOf(background, background.copy(alpha = 0.96f), Color(0xFF05070A)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(primary.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(size.width * (0.82f + phase), size.height * 0.08f),
            radius = size.maxDimension * 0.62f,
        ),
        radius = size.maxDimension * 0.62f,
        center = Offset(size.width * (0.82f + phase), size.height * 0.08f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(secondary.copy(alpha = 0.11f), Color.Transparent),
            center = Offset(size.width * (0.05f - phase), size.height * 0.82f),
            radius = size.maxDimension * 0.54f,
        ),
        radius = size.maxDimension * 0.54f,
        center = Offset(size.width * (0.05f - phase), size.height * 0.82f),
    )
}

@Composable
fun ShadowGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier.border(
            BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
            shape,
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        tonalElevation = 0.dp,
    ) {
        Box(
            Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.055f),
                            Color.Transparent,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.035f),
                        )
                    )
                )
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

private val emojiPattern = Regex(
    "[\\u2600-\\u27BF\\uD83C\\uDF00-\\uD83D\\uDDFF\\uD83E\\uDD00-\\uD83E\\uDFFF]"
)

fun String.withoutEmoji(): String = replace(emojiPattern, "")
    .replace(Regex("\\s{2,}"), " ")
    .trim()
