package top.cylunex.shadowmedia.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import top.cylunex.shadowmedia.database.ShadowMediaDatabase
import top.cylunex.shadowmedia.database.SyncOperationEntity
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
    private val dao = ShadowMediaDatabase.create(context).libraryDao()
    private val migrationMutex = Mutex()
    private val writerMutex = Mutex()
    private val flushLocks = ConcurrentHashMap<String, Mutex>()
    private val countState = MutableStateFlow(0)
    private val clock = AtomicLong(System.currentTimeMillis())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { scope.launch { dao.pendingCount().collect { countState.value = it } } }

    override val pendingCount: StateFlow<Int> = countState.asStateFlow()

    override suspend fun submit(session: EmbySession, report: PlaybackReport) {
        ensureImported()
        writerMutex.withLock {
            val record = report.toRecord(session)
            dao.enqueueCoalesced(record.operation(UUID.randomUUID().toString()), report.event == PlaybackEvent.TIME_UPDATE)
        }
        flush(session)
    }

    override suspend fun flush(session: EmbySession) {
        ensureImported()
        val account = accountScope(session.serverUrl, session.serverId, session.userId)
        flushLocks.getOrPut(account) { Mutex() }.withLock {
            for (operation in dao.pending(account)) {
                if (operation.nextAttemptAt > System.currentTimeMillis()) break
                try {
                    val record = json.decodeFromString<PlaybackOutboxRecordDto>(operation.payload)
                    repository.reportPlayback(session, record.toReport())
                    dao.acknowledge(operation.id)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    val delayMs = (1000L shl operation.attempts.coerceAtMost(8)).coerceAtMost(300_000)
                    dao.retry(operation.id, System.currentTimeMillis() + delayMs)
                    break
                }
            }
        }
    }

    override suspend fun discard(session: EmbySession) {
        ensureImported()
        val account = accountScope(session.serverUrl, session.serverId, session.userId)
        flushLocks.getOrPut(account) { Mutex() }.withLock { dao.discardScope(account) }
    }

    private suspend fun ensureImported() = migrationMutex.withLock {
        if (dao.imported("emby_preferences_v1")) return@withLock
        // Do not erase malformed old data. It must remain recoverable instead of silently losing
        // pending progress. The Room transaction makes retries after process death idempotent.
        val raw = preferences.getString(KEY_RECORDS, null)
        val records = try { raw?.let { json.decodeFromString<List<PlaybackOutboxRecordDto>>(it) }.orEmpty() }
            catch (e: Exception) { throw IllegalStateException("旧播放同步队列无法读取，原数据已保留", e) }
        dao.importOnce("emby_preferences_v1", records.mapIndexed { index, record -> record.operation("legacy-emby-$index") })
        if (raw != null) preferences.edit().putString("records_legacy_backup", raw).remove(KEY_RECORDS).commit()
    }

    private fun accountScope(url: String, server: String, user: String) = "emby:" + listOf(url, server, user).joinToString("") { "${it.length}:$it" }
    private fun PlaybackOutboxRecordDto.operation(id: String) = SyncOperationEntity(
        id = id, scope = accountScope(serverUrl, serverId, userId), target = "${itemId.length}:$itemId${playSessionId.length}:$playSessionId",
        kind = event, payload = json.encodeToString(this), createdAt = clock.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }, attempts = retryCount,
    )

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
