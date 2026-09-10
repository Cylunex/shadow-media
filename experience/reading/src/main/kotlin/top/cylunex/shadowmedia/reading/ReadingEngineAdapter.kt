@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package top.cylunex.shadowmedia.reading

import android.content.Context
import androidx.fragment.app.FragmentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** The reader's engine boundary owns opening and navigator construction; identity/progress stay in the library. */
internal interface ReadingEngineAdapter {
    suspend fun open(file: File): Publication
    fun navigator(publication: Publication, initial: Locator?, preferences: EpubPreferences): FragmentFactory
}
internal class ReadiumEngineAdapter(private val context: Context) : ReadingEngineAdapter {
    override suspend fun open(file: File): Publication = withContext(Dispatchers.IO) {
        require(file.isFile) { "本地图书已移除" }
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val resource = retriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { throw IllegalArgumentException("无法读取本地图书") }
        PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null))
            .open(resource, allowUserInteraction = false).getOrElse { throw IllegalArgumentException("图书格式损坏或受 DRM 保护") }
    }
    override fun navigator(publication: Publication, initial: Locator?, preferences: EpubPreferences): FragmentFactory =
        EpubNavigatorFactory(publication).createFragmentFactory(initialLocator = initial, initialPreferences = preferences)
}
