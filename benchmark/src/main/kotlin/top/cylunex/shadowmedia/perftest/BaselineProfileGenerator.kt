package top.cylunex.shadowmedia.perftest

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    @Before fun prepare() = Journeys.seed()
    @Test fun startup() = profile.collect(TARGET, includeInStartupProfile = true) {
        pressHome(); startActivityAndWait(); Journeys.home()
    }
    @Test fun criticalPaths() = profile.collect(TARGET) {
        startActivityAndWait(); Journeys.home(); Journeys.scrollSongs()
        Journeys.launch(); Journeys.reader()
        Journeys.launch(); Journeys.audio()
        Journeys.launch(); Journeys.video()
        Journeys.launch(); Journeys.switchAccount()
    }
}
