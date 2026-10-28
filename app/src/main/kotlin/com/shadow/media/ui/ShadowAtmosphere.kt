package com.shadow.media.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ShadowBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(MaterialTheme.colorScheme.background), content = content)
}

@Composable
fun ShadowGlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier.border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape,
        ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

private val emojiPattern = Regex(
    "[\\u2600-\\u27BF\\uD83C\\uDF00-\\uD83D\\uDDFF\\uD83E\\uDD00-\\uD83E\\uDFFF]"
)

fun String.withoutEmoji(): String = replace(emojiPattern, "")
    .replace(Regex("\\s{2,}"), " ")
    .trim()
