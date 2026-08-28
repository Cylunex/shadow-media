package top.cylunex.shadowmedia.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

@Composable
fun isTelevision(): Boolean =
    LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun Modifier.shadowTvFocus(): Modifier {
    if (!isTelevision()) return this
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tv-focus-scale")
    return this
        .zIndex(if (focused) 2f else 0f)
        .scale(scale)
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = MaterialTheme.shapes.large,
        )
        .onFocusChanged { focused = it.isFocused }
        .focusable()
}
