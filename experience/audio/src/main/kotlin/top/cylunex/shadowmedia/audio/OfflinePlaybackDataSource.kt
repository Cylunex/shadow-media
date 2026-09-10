@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package top.cylunex.shadowmedia.audio

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import top.cylunex.shadowmedia.database.ShadowMediaDatabase
import java.io.IOException

class OfflinePlaybackDataSource(private val context: Context) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val asset = runBlocking(Dispatchers.IO) { ShadowMediaDatabase.create(context).libraryDao().asset(dataSpec.uri.host.orEmpty()) } ?: throw IOException("离线作品已移除")
        val source = runBlocking(Dispatchers.IO) { MediaOfflineStore.get(context).cached(asset) } ?: throw IOException("没有完整的离线副本")
        delegate = source; listeners.forEach(source::addTransferListener)
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate?.read(buffer, offset, length) ?: -1
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders() = delegate?.responseHeaders.orEmpty()
    override fun close() { delegate?.close(); delegate = null }
}
