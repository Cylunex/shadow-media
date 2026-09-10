package top.cylunex.shadowmedia.reading

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Text order belongs to the platform extractor; every block retains its physical source page. */
internal data class PdfTextBlock(val page: Int, val ordinal: Int, val text: String)

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
internal suspend fun extractPdfText(file: File, pageIndex: Int): List<PdfTextBlock> {
    currentCoroutineContext().ensureActive()
    return PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
        renderer.openPage(pageIndex).use { page ->
            var characters = 0
            page.textContents.mapIndexedNotNull { index, content ->
                currentCoroutineContext().ensureActive()
                val text = content.text.trim()
                characters += text.length
                require(characters <= 256_000) { "本页文本过大，请使用原页阅读" }
                text.takeIf { it.isNotBlank() }?.let { PdfTextBlock(pageIndex, index, it) }
            }
        }
    }
}
