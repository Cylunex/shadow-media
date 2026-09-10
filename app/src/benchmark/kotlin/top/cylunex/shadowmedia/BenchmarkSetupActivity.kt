package top.cylunex.shadowmedia

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.cylunex.shadowmedia.database.*
import top.cylunex.shadowmedia.model.EmbySession
import java.io.File
import java.security.MessageDigest

/** Fixture installation happens in benchmark setup, outside measured startup/scroll blocks. */
class BenchmarkSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Preparing fixture" }; setContentView(status)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val container = (application as ShadowMediaApplication).container
                container.accountScopesReady.await()
                val directory = File(filesDir, "publications/benchmark-fixtures").apply { mkdirs() }
                for (name in listOf("silence.wav", "epub3-cjk-font.epub", "physical-pages.cbz", "physical-pages.pdf")) {
                    val file = File(directory, name)
                    if (!file.exists()) assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
                }
                val db = ShadowMediaDatabase.create(this@BenchmarkSetupActivity)
                val preferences = getSharedPreferences("benchmark_fixture", MODE_PRIVATE)
                if (!preferences.getBoolean("installed-v1", false)) {
                    db.withTransaction {
                        for (i in 0 until 10_000) {
                            val id = id("song:$i")
                            db.libraryDao().putAsset(LibraryAssetEntity(id, "local", "song:$i", "Fixture Song ${i.toString().padStart(5, '0')}", kind = "MUSIC", format = "wav", localUri = File(directory, "silence.wav").toURI().toString(), revision = "fixture-v1", addedAt = i.toLong()))
                            db.musicDao().putTrack(MusicTrackEntity(id, album = "Album ${i / 20}", albumKey = "album:${i / 20}", artist = "Artist ${i / 100}", artistKey = "artist:${i / 100}", track = i % 20 + 1, durationMs = 6000))
                        }
                        for ((file, kind, title) in listOf(Triple("epub3-cjk-font.epub", "BOOK", "Fixture EPUB"), Triple("physical-pages.cbz", "COMIC", "Fixture Comic"), Triple("physical-pages.pdf", "BOOK", "Fixture PDF"), Triple("silence.wav", "AUDIOBOOK", "Fixture Audiobook"))) {
                            db.libraryDao().putAsset(LibraryAssetEntity(id(file), "local", file, title, kind = kind, format = file.substringAfterLast('.'), localUri = File(directory, file).toURI().toString(), revision = "fixture-v1", addedAt = 1))
                        }
                    }
                    preferences.edit().putBoolean("installed-v1", true).commit()
                }
                val first = EmbySession(BenchmarkFixtureServer.ADDRESS, "fixture", "first", "Fixture First", "fixture-token", true)
                container.sessionStore.save(first.copy(userId = "second", userName = "Fixture Second")); container.sessionStore.save(first)
            }
            status.text = "fixture-ready"; status.contentDescription = "fixture-ready"
        }
    }
    private fun id(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
