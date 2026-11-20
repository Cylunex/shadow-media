package com.shadow.media.reading

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/** Basic in-reader narration. Only a locally installed, offline voice is allowed. */
internal class ReadAloud(private val context: Context, private val onInterrupted: () -> Unit = {}) : AutoCloseable {
    private val audible = com.shadow.media.model.PlaybackCoordinator.process.participant { stop(); onInterrupted() }
    private val initialized = CompletableDeferred<Unit>()
    private val engine = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) initialized.complete(Unit)
        else initialized.completeExceptionally(IllegalStateException("系统朗读引擎不可用"))
    }
    private val audio = context.getSystemService(AudioManager::class.java)
    private var speaking: Job? = null
    var speed: Float = 1f
        set(value) { field = value.coerceIn(.5f, 3f) }
    var stopAt: Long? = null
    var voiceName: String? = null
    private var language: Locale? = null
    val availableVoices get() = engine.voices.orEmpty().filter { voice ->
        voice.isInstalledOffline() && language?.let { sameSpeechLanguage(voice.locale, it) } != false
    }.sortedBy { it.name }

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) { stop(); onInterrupted() } }.build()

    suspend fun read(publication: Publication, locator: Locator?, onLocation: (Locator) -> Unit) = narrate(Locale.getDefault()) {
        val iterator = requireNotNull(publication.content(locator)) { "这本书不支持正文提取" }.iterator()
        while (currentCoroutineContext().isActive && iterator.hasNext()) {
            val element = iterator.next() as? Content.TextElement ?: continue
            if (element.text.isBlank()) continue
            for (chunk in element.text.chunked(2500)) speak(chunk) { onLocation(element.locator) }
        }
    }

    suspend fun readText(text: String, offset: Int, onRange: ((Int) -> Unit)? = null, onLocation: (Int) -> Unit) = narrate(
        if (text.any { it.code in 0x3400..0x9fff }) {
            Locale.getDefault().takeIf { sameSpeechLanguage(it, Locale.CHINESE) } ?: Locale.SIMPLIFIED_CHINESE
        } else Locale.getDefault()
    ) {
        var start = safeTextOffset(text, offset)
        while (start < text.length) {
            currentCoroutineContext().ensureActive()
            if (stopAt?.let { android.os.SystemClock.elapsedRealtime() >= it } == true) throw CancellationException("定时停止")
            engine.setSpeechRate(speed)
            availableVoices.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
            val maximum = (start + 1000).coerceAtMost(text.length)
            val sentence = text.indexOfAny(charArrayOf('。', '！', '？', '\n', '.', '!', '?'), start)
            val end = safeTextOffset(text, if (sentence in start until maximum) sentence + 1 else maximum)
            speak(text.substring(start, end), onRange?.let { callback ->
                { point -> callback(safeTextOffset(text, start + point)) }
            }) { started -> onLocation(if (started) start else end) }
            start = end
        }
    }

    private suspend fun narrate(language: Locale, block: suspend () -> Unit) {
        this.language = language
        speaking = currentCoroutineContext()[Job]
        try {
            initialized.await()
            currentCoroutineContext().ensureActive()
            val voices = availableVoices
            val preferred = voices.firstOrNull { it.locale.language == language.language && it.locale.country == language.country }
            val candidates = sequenceOf(voices.firstOrNull { it.name == voiceName }, preferred)
                .filterNotNull().plus(voices).distinctBy { it.name }
            check(candidates.any { engine.setVoice(it) == TextToSpeech.SUCCESS } || selectEngineOfflineVoice(language)) {
                "请先在系统文字转语音设置中安装当前语言的离线语音"
            }
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "其他应用正在占用音频" }
            audible.claim()
            block()
        } finally { audible.abandon(); engine.stop(); audio.abandonAudioFocusRequest(focus); speaking = null }
    }

    @Suppress("DEPRECATION")
    private fun selectEngineOfflineVoice(language: Locale): Boolean {
        val selected = engine.voice
        if (selected != null && selected.isInstalledOffline() && sameSpeechLanguage(selected.locale, language)) return true

        // Some engines publish their default voice only after selecting its language.
        if (engine.setLanguage(language) >= TextToSpeech.LANG_AVAILABLE) {
            val voice = engine.voice
            if (voice != null && voice.isInstalledOffline() && sameSpeechLanguage(voice.locale, language)) return true
        }
        val advertised = engine.voices.orEmpty()
        val matching = advertised.filter { sameSpeechLanguage(it.locale, language) }
        Log.w("ReaderNarration", "No offline voice for ${language.toLanguageTag()} on ${engine.defaultEngine}; " +
            "advertised=${advertised.size}, matching=${matching.size}, selected=${engine.voice?.locale?.toLanguageTag()}")
        return false
    }

    private fun Voice.isInstalledOffline() = !isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()

    private suspend fun speak(text: String, onRange: ((Int) -> Unit)? = null, onAudible: (Boolean) -> Unit) = coroutineScope {
        val audible = CompletableDeferred<Boolean>()
        val ranges = if (onRange != null) Channel<Int>(Channel.CONFLATED) else null
        launch {
            onAudible(audible.await())
            if (ranges != null && onRange != null) for (point in ranges) onRange(point)
        }
        try { awaitSpeech(text, audible, ranges) }
        finally { ranges?.close() }
    }

    private suspend fun awaitSpeech(text: String, audible: CompletableDeferred<Boolean>, ranges: Channel<Int>?) = suspendCancellableCoroutine<Unit> { continuation ->
        val requestId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == requestId && continuation.isActive) audible.complete(true)
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId == requestId && continuation.isActive && start in text.indices) {
                    audible.complete(true)
                    ranges?.trySend(start)
                }
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId == requestId && continuation.isActive) {
                    audible.complete(false)
                    continuation.resume(Unit)
                }
            }
            @Deprecated("Platform callback") override fun onError(utteranceId: String?) { if (utteranceId == requestId && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统朗读失败")) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == requestId) continuation.cancel() }
        })
        continuation.invokeOnCancellation { engine.stop() }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId) == TextToSpeech.ERROR && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统朗读失败"))
    }
    fun stop() { audible.abandon(); speaking?.cancel(); engine.stop(); audio.abandonAudioFocusRequest(focus) }
    override fun close() { audible.close(); stop(); engine.shutdown() }
}

internal fun sameSpeechLanguage(first: Locale, second: Locale): Boolean {
    if (first.language == second.language) return true
    fun languageKey(locale: Locale) = when (locale.language) {
        "chi", "cmn", "zho", "zh" -> "zho"
        else -> runCatching { locale.isO3Language }.getOrDefault(locale.language)
    }
    return languageKey(first) == languageKey(second)
}
