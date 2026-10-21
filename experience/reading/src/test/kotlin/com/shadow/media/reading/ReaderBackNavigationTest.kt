package com.shadow.media.reading

import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderBackNavigationTest {
    @Test fun systemBackExitsReadingWithCenterMenuOpenInsteadOfOnlyHidingControls() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.installReaderBackNavigation({ false }) { fail("Center controls are not a modal panel") }
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }
    @Test fun directoryOrSettingsDismissFirstThenTheNextSystemBackExits() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var panel = true
        activity.installReaderBackNavigation({ panel }) { panel = false }
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(panel)
        assertFalse(activity.isFinishing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
