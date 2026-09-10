package top.cylunex.shadowmedia.reading

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.cylunex.shadowmedia.database.LibraryAssetEntity
import top.cylunex.shadowmedia.library.*
import java.io.File
import java.util.zip.ZipFile

/** Source coordinates always name physical pages, independent of RTL, spreads and scroll layout. */
internal interface ComicSourceAdapter {
    suspend fun pages(): List<String>
    suspend fun page(id: String, target: File): ComicPageResource
}
internal sealed interface ComicPageResource {
    data class Image(val file: File) : ComicPageResource
    data class Pdf(val file: File, val index: Int) : ComicPageResource
}
internal class LibraryComicSource(private val asset: LibraryAssetEntity, private val library: LibraryRepository) : ComicSourceAdapter {
    private val source by lazy { library.localFile(asset) }
    override suspend fun pages(): List<String> = when (asset.format) {
        "komga" -> requireNotNull(LibraryResources.pageManifest)(asset)
        "pdf" -> PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> List(renderer.pageCount) { it.toString() } }
        else -> { SafeArchives.validate(source); LibraryRepository.comicPages(source) }
    }
    override suspend fun page(id: String, target: File): ComicPageResource {
        if (asset.format == "pdf") return ComicPageResource.Pdf(source, id.toInt())
        if (asset.format == "komga") requireNotNull(LibraryResources.pageReader)(asset, id, target)
        else ZipFile(source).use { zip ->
            val entry = requireNotNull(zip.getEntry(id))
            require(entry.size in 1..SafeArchives.MAX_ENTRY_BYTES)
            zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= SafeArchives.MAX_ENTRY_BYTES)
                    require(target.parentFile!!.usableSpace > 32L * 1024 * 1024) { "页面缓存空间不足" }
                    output.write(buffer, 0, n)
                }
            } }
        }
        return ComicPageResource.Image(target)
    }
}
internal interface ComicRenderAdapter { suspend fun render(resource: ComicPageResource, target: File): File }
/** Rasterize one PDF page at bounded resolution; image files retain SSIV tile decoding in the view. */
internal class PlatformComicRenderer : ComicRenderAdapter {
    override suspend fun render(resource: ComicPageResource, target: File): File {
        if (resource is ComicPageResource.Image) return resource.file
        resource as ComicPageResource.Pdf
        currentCoroutineContext().ensureActive()
        PdfRenderer(ParcelFileDescriptor.open(resource.file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            renderer.openPage(resource.index).use { page ->
                val ratio = minOf(2048f / page.width, 2048f / page.height, 2f)
                val bitmap = Bitmap.createBitmap((page.width * ratio).toInt().coerceAtLeast(1), (page.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    currentCoroutineContext().ensureActive()
                    target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        }
        return target
    }
}
