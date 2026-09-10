package top.cylunex.shadowmedia

/** Only the isolated .benchmark APK contains a fixture server. */
class BenchmarkApplication : ShadowMediaApplication() {
    private lateinit var fixtureServer: BenchmarkFixtureServer
    override fun onCreate() {
        super.onCreate()
        fixtureServer = BenchmarkFixtureServer(this).apply { start() }
    }
}
