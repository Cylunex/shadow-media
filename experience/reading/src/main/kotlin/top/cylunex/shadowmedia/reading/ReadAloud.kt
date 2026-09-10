package top.cylunex.shadowmedia.reading

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/** Basic in-reader narration. Only a locally installed, offline voice is allowed. */
internal class ReadAloud(private val context: Context) : AutoCloseable {
    private val audible = top.cylunex.shadowmedia.model.PlaybackCoordinator.process.participant { stop() }
    private val initialized = CompletableDeferred<Unit>()
    private val engine = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) initialized.complete(Unit)
        else initialized.completeExceptionally(IllegalStateException("系统朗读引擎不可用"))
    }
    private val audio = context.getSystemService(AudioManager::class.java)
    private var speaking: Job? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) stop() }.build()

    suspend fun read(publication: Publication, locator: Locator?, onLocation: (Locator) -> Unit) {
        // Stop/background must cancel even while Android is still initializing the engine.
        speaking = currentCoroutineContext()[Job]
        try {
            initialized.await()
            currentCoroutineContext().ensureActive()
            val voice = engine.voices.orEmpty().filterNot { it.isNetworkConnectionRequired }.firstOrNull { it.locale.language == Locale.getDefault().language }
                ?: throw IllegalStateException("请先在系统文字转语音设置中安装当前语言的离线语音")
            engine.voice = voice
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "其他应用正在占用音频" }
            audible.claim()
            val iterator = requireNotNull(publication.content(locator)) { "这本书不支持正文提取" }.iterator()
            while (currentCoroutineContext().isActive && iterator.hasNext()) {
                val element = iterator.next() as? Content.TextElement ?: continue
                if (element.text.isBlank()) continue
                onLocation(element.locator)
                // TTS binder calls have a bounded text size; do not load the entire publication.
                for (chunk in element.text.chunked(2500)) speak(chunk)
            }
        } finally { audible.abandon(); engine.stop(); audio.abandonAudioFocusRequest(focus); speaking = null }
    }

    private suspend fun speak(text: String) = suspendCancellableCoroutine<Unit> { continuation ->
        val requestId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == requestId && continuation.isActive) continuation.resume(Unit) }
            @Deprecated("Platform callback") override fun onError(utteranceId: String?) { if (utteranceId == requestId && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统朗读失败")) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == requestId) continuation.cancel() }
        })
        continuation.invokeOnCancellation { engine.stop() }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId) == TextToSpeech.ERROR && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统朗读失败"))
    }
    fun stop() { audible.abandon(); speaking?.cancel(); engine.stop(); audio.abandonAudioFocusRequest(focus) }
    override fun close() { audible.close(); stop(); engine.shutdown() }
}
