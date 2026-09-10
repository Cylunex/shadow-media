package top.cylunex.shadowmedia.perftest

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real app journeys on an isolated, release-derived target with deterministic local fixtures. */
@RunWith(AndroidJUnit4::class)
class CriticalJourneyBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun prepare() = Journeys.seed()

    private fun startup(mode: StartupMode) = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.None(), startupMode = mode, iterations = 5,
        setupBlock = { pressHome() }, measureBlock = { startActivityAndWait(); Journeys.home() },
    )
    @Test fun coldStartup() = startup(StartupMode.COLD)
    @Test fun warmStartup() = startup(StartupMode.WARM)
    private fun journey(block: () -> Unit) = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(warmupIterations = 2), iterations = 5,
        setupBlock = { Journeys.launch(); Journeys.home() }, measureBlock = { block() },
    )
    @Test fun tenThousandSongScroll() = journey { Journeys.scrollSongs() }
    @Test fun videoCatalogAndPlayback() = journey { Journeys.video() }
    @Test fun publicationOpenAndToc() = journey { Journeys.reader() }
    @Test fun audioQueueStart() = journey { Journeys.audio() }
    @Test fun accountSwitch() = journey { Journeys.switchAccount() }
}
