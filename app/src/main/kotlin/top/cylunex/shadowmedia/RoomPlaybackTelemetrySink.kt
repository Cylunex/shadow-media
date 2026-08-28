package top.cylunex.shadowmedia

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import top.cylunex.shadowmedia.database.LocalMediaStateRepository
import top.cylunex.shadowmedia.database.PlaybackMetricEntity
import top.cylunex.shadowmedia.database.SourceHealthEntity
import top.cylunex.shadowmedia.playback.PlaybackTelemetrySink
import top.cylunex.shadowmedia.playback.PlaybackTelemetrySnapshot

class RoomPlaybackTelemetrySink(
    private val repository: LocalMediaStateRepository,
    private val scope: CoroutineScope,
) : PlaybackTelemetrySink {
    private val processedTerminalIds = ConcurrentHashMap.newKeySet<String>()

    override fun record(snapshot: PlaybackTelemetrySnapshot) {
        scope.launch(Dispatchers.IO) {
            repository.upsertPlaybackMetric(
                PlaybackMetricEntity(
                    id = snapshot.id,
                    providerId = snapshot.providerId,
                    itemId = snapshot.itemId,
                    playSessionId = snapshot.playSessionId,
                    method = snapshot.method,
                    requestHost = snapshot.requestHost,
                    candidateIndex = snapshot.candidateIndex,
                    startedAtEpochMs = snapshot.startedAtEpochMs,
                    firstFrameMs = snapshot.firstFrameMs,
                    bufferingCount = snapshot.bufferingCount,
                    bufferingDurationMs = snapshot.bufferingDurationMs,
                    errorCode = snapshot.errorCode,
                    errorMessage = snapshot.errorMessage,
                    completed = snapshot.completed,
                )
            )
            if (snapshot.terminal && processedTerminalIds.add(snapshot.id)) updateSourceHealth(snapshot)
        }
    }

    private suspend fun updateSourceHealth(snapshot: PlaybackTelemetrySnapshot) {
        val host = snapshot.requestHost ?: return
        val key = "${snapshot.providerId}:$host"
        val current = repository.sourceHealth(key)
        val successes = (current?.successes ?: 0) + if (snapshot.completed) 1 else 0
        val failures = (current?.failures ?: 0) + if (snapshot.completed) 0 else 1
        val averageFirstFrame = snapshot.firstFrameMs?.let { latest ->
            val previous = current?.averageFirstFrameMs
            if (previous == null) latest else ((previous * 3L) + latest) / 4L
        } ?: current?.averageFirstFrameMs
        repository.upsertSourceHealth(
            SourceHealthEntity(
                sourceKey = key,
                providerId = snapshot.providerId,
                successes = successes,
                failures = failures,
                consecutiveFailures = if (snapshot.completed) 0 else (current?.consecutiveFailures ?: 0) + 1,
                averageFirstFrameMs = averageFirstFrame,
                lastError = if (snapshot.completed) null else snapshot.errorMessage,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )
    }
}
