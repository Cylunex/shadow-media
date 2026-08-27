package top.cylunex.shadowmedia.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.PlayMethod
import top.cylunex.shadowmedia.model.PlaybackEvent

class PersistentPlaybackOutbox(
    context: Context,
    private val repository: EmbyRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : PlaybackOutbox {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val countState = MutableStateFlow(readRecords().size)

    override val pendingCount: StateFlow<Int> = countState.asStateFlow()

    override suspend fun submit(session: EmbySession, report: PlaybackReport) {
        mutex.withLock {
            val record = report.toRecord(session)
            writeRecords(enqueuePlaybackRecord(readRecords(), record, MAX_RECORDS))
        }
        flush(session)
    }

    override suspend fun flush(session: EmbySession) = mutex.withLock {
        val records = readRecords().toMutableList()
        while (true) {
            val index = records.indexOfFirst { it.belongsTo(session) }
            if (index < 0) break
            val record = records[index]
            val result = runCatching { repository.reportPlayback(session, record.toReport()) }
            if (result.isFailure) {
                records[index] = record.copy(retryCount = record.retryCount + 1)
                writeRecords(records)
                break
            }
            records.removeAt(index)
            writeRecords(records)
        }
    }

    override suspend fun discard(session: EmbySession) = mutex.withLock {
        writeRecords(readRecords().filterNot { it.belongsTo(session) })
    }

    private fun readRecords(): List<PlaybackOutboxRecordDto> = runCatching {
        preferences.getString(KEY_RECORDS, null)
            ?.let { json.decodeFromString<List<PlaybackOutboxRecordDto>>(it) }
            .orEmpty()
    }.getOrElse {
        preferences.edit().remove(KEY_RECORDS).commit()
        emptyList()
    }

    private fun writeRecords(records: List<PlaybackOutboxRecordDto>) {
        check(preferences.edit().putString(KEY_RECORDS, json.encodeToString(records)).commit()) {
            "无法保存待同步播放进度"
        }
        countState.value = records.size
    }

    private fun PlaybackReport.toRecord(session: EmbySession) = PlaybackOutboxRecordDto(
        serverUrl = session.serverUrl,
        serverId = session.serverId,
        userId = session.userId,
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
        canSeek = canSeek,
        event = event.name,
        playMethod = playMethod.name,
        createdAtEpochMs = System.currentTimeMillis(),
    )

    private fun PlaybackOutboxRecordDto.toReport() = PlaybackReport(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
        canSeek = canSeek,
        event = PlaybackEvent.valueOf(event),
        playMethod = PlayMethod.valueOf(playMethod),
    )

    private fun PlaybackOutboxRecordDto.belongsTo(session: EmbySession): Boolean =
        serverUrl == session.serverUrl && serverId == session.serverId && userId == session.userId

    companion object {
        private const val PREFERENCES_NAME = "playback_outbox"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 500
    }
}

internal fun enqueuePlaybackRecord(
    records: List<PlaybackOutboxRecordDto>,
    record: PlaybackOutboxRecordDto,
    maxRecords: Int,
): List<PlaybackOutboxRecordDto> {
    val next = records.toMutableList()
    if (record.event == PlaybackEvent.TIME_UPDATE.name) {
        next.removeAll {
            it.serverUrl == record.serverUrl &&
                it.serverId == record.serverId &&
                it.userId == record.userId &&
                it.itemId == record.itemId &&
                it.playSessionId == record.playSessionId &&
                it.event == PlaybackEvent.TIME_UPDATE.name
        }
    }
    next += record
    return next.takeLast(maxRecords)
}
