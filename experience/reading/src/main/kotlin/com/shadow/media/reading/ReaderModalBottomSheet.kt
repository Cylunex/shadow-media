@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.shadow.media.reading

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Size the surface from the window, before its offset-dependent content insets. */
@Composable
fun ReaderModalBottomSheet(
    onDismissRequest: () -> Unit,
    heightFraction: Float = .92f,
    sheetGesturesEnabled: Boolean = true,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    require(heightFraction > 0f && heightFraction <= 1f)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(heightFraction),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = sheetGesturesEnabled,
        dragHandle = dragHandle,
        content = content,
    )
}
