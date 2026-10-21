package com.shadow.media.reading

import androidx.activity.ComponentActivity
import androidx.activity.addCallback

/** System/predictive back exits reading even while its center-tap controls are visible. */
internal fun ComponentActivity.installReaderBackNavigation(hasPanel: () -> Boolean, closePanel: () -> Unit) {
    onBackPressedDispatcher.addCallback(this) {
        if (hasPanel()) closePanel() else finish()
    }
}
