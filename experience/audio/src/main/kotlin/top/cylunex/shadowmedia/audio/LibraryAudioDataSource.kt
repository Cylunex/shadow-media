@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia.audio

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.cylunex.shadowmedia.library.*
import top.cylunex.shadowmedia.playback.RoutingDataSource
import java.io.IOException

internal class LibraryAudioDataSource(private val context: Context, private val library: LibraryRepository, private val candidates: AudioCandidateSessions) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != "shadow-audio") {
            return DefaultDataSource.Factory(context).createDataSource().also { delegate = it; listeners.forEach(it::addTransferListener) }.open(dataSpec)
        }
        // Media3 invokes DataSource.open on its loader thread, never on the UI thread.
        val asset = runBlocking(Dispatchers.IO) { library.dao.asset(requireNotNull(dataSpec.uri.host)) } ?: throw IOException("书库条目已移除")
        if (dataSpec.uri.getQueryParameter("revision") != asset.revision) throw IOException("音频版本已变化，请重新打开")
        val entryId = dataSpec.uri.getQueryParameter("entry") ?: throw IOException("音频队列条目标识缺失")
        var lastError: IOException? = null
        for (attempt in 0 until 18) {
            val candidate = runBlocking(Dispatchers.IO) { candidates.current(asset, entryId) }
            val origin = (candidate.credentialOrigin ?: candidate.url).toHttpUrlOrNull()
            val client = baseClient.newBuilder().addNetworkInterceptor { chain ->
                val request = chain.request(); val url = request.url; val builder = request.newBuilder()
                if (origin?.isHttps == true && !url.isHttps) throw IOException("拒绝将加密音频资源降级到 HTTP")
                if (origin == null || origin.scheme != url.scheme || origin.host != url.host || origin.port != url.port) {
                    listOf("Authorization", "Cookie", "X-Emby-Token", "X-Emby-Authorization", "X-MediaBrowser-Token", "X-MediaBrowser-Authorization", "X-API-Key").forEach(builder::removeHeader)
                }
                chain.proceed(builder.build())
            }.build()
            val source = RoutingDataSource(OkHttpDataSource.Factory(client).setDefaultRequestProperties(candidate.requiredHeaders).createDataSource(), LibraryResources.networkStorage)
            delegate = source; listeners.forEach(source::addTransferListener)
            try {
                val length = source.open(dataSpec.buildUpon().setUri(candidate.url).build())
                candidates.selected(entryId, candidate)
                return length
            } catch (e: IOException) {
                runCatching { source.close() }; delegate = null; lastError = e
                val refresh = e is HttpDataSource.InvalidResponseCodeException && e.responseCode in setOf(401, 403, 404, 410) &&
                    runBlocking(Dispatchers.IO) { candidates.refresh(asset, entryId) }
                if (!refresh && (dataSpec.position > 0 || !candidates.advance(entryId))) throw e
            }
        }
        throw IOException("音频线路均无法打开", lastError)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate?.read(buffer, offset, length) ?: -1
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { delegate?.close(); delegate = null }
    companion object { private val baseClient = OkHttpClient() }
}
