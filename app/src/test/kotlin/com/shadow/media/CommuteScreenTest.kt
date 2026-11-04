package com.shadow.media

import android.net.Uri
import com.shadow.media.audio.localAudioUri
import com.shadow.media.database.CommuteAudioRow
import com.shadow.media.database.LibraryAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
class CommuteScreenTest {
    private fun row(id: String, position: Int, duration: Int?, kind: String = "AUDIOBOOK") =
        CommuteAudioRow(LibraryAssetEntity(id, "local", id, id, kind = kind, format = "mp3", addedAt = 1),
            """{"positionMs":${position * 60000}}""", duration?.times(60_000L), 1, false)

    @Test fun fitsBudgetUsingRemainingTimeAndKeepsUnknownSeparate() = runBlocking {
        val rows = listOf(row("missing", 1, 4), row("unstarted", 0, 18), row("over", 15, 36),
            row("unknown", 7, null), row("done", 5, 5), row("current", 30, 50), row("song", 5, 24, "MUSIC"))
        val choices = commuteChoices(rows, 20) { it.asset.id != "missing" }
        assertEquals(listOf("current","unstarted","over","song","unknown"), choices.map { it.row.asset.id })
        assertEquals(listOf(0,0,1,1,2), choices.map { it.group(20) })
        assertEquals(0L, choices[3].position)
    }

    @Test fun deepLinksAcceptOnlyBudgetOrDeviceLocalSavedIdentifiers() {
        fun read(raw: String) = Uri.parse(raw).localMediaLink()
        assertEquals(LocalMediaLink.Commute(20), read("shadowmedia://commute?minutes=20"))
        val id = "00000001-0000-0000-0000-000000000001"
        assertEquals(LocalMediaLink.Saved("quote",id), read("shadowmedia://saved/quote/$id"))
        assertEquals(LocalMediaLink.Saved("moment",id), read("shadowmedia://saved/moment/$id"))
        listOf("shadowmedia://commute?minutes=0","shadowmedia://commute?minutes=181","shadowmedia://commute?minutes=20&minutes=1",
            "shadowmedia://commute?minutes=20&play=1","shadowmedia://commute/start?minutes=20",
            "shadowmedia://u@commute?minutes=20","shadowmedia://saved/quote/anything","shadowmedia://saved/moment/$id?play=1").forEach { assertNull(it, read(it)) }
    }

    @Test fun cloudDocumentsAndNetworkUrisAreNotEvidenceOfOfflineBytes() {
        assertTrue(localAudioUri(Uri.parse("content://com.android.externalstorage.documents/document/primary%3Atrack.mp3")))
        assertTrue(localAudioUri(Uri.parse("content://media/external/audio/media/123")))
        assertFalse(localAudioUri(Uri.parse("content://media/picker/example")))
        assertFalse(localAudioUri(Uri.parse("content://cloud.example.com/document/file")))
        assertFalse(localAudioUri(Uri.parse("https://example.com/file.mp3")))
    }
}
