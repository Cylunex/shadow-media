package com.shadow.media.reading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** One immutable texture per process, shared by page snapshots, previews and Readium. No activity retained. */
internal object ReaderPaperTexture {
    private var bitmap: Bitmap? = null
    private var dataUri: String? = null
    private const val ASSET = "reader/parchment.webp"

    @Synchronized fun bitmap(context: Context): Bitmap = bitmap ?: context.assets.open(ASSET).use {
        requireNotNull(BitmapFactory.decodeStream(it)) { "Unable to decode bundled reading paper" }
    }.also { bitmap = it }

    @Synchronized fun dataUri(context: Context): String = dataUri ?: context.assets.open(ASSET).use {
        "data:image/webp;base64," + Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
    }.also { dataUri = it }
}

internal fun drawReaderPaper(canvas: Canvas, width: Float, height: Float, value: ReaderSettings, texture: Bitmap?) {
    canvas.drawColor(value.paper.background)
    if (value.paper != ReaderPaper.PARCHMENT || value.paperTexture <= 0f || texture == null) return
    // Center-crop instead of stretching the fibres, matching background-size: cover in EPUB.
    val scale = maxOf(width / texture.width, height / texture.height)
    val x = (width - texture.width * scale) / 2f
    val y = (height - texture.height * scale) / 2f
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (value.paperTexture.coerceIn(0f, 1f) * 255).roundToInt() }
    canvas.drawBitmap(texture, null, RectF(x, y, x + texture.width * scale, y + texture.height * scale), paint)
}

@Composable internal fun ReaderPaperBackground(value: ReaderSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val texture by produceState<Bitmap?>(null) { this.value = withContext(Dispatchers.IO) { ReaderPaperTexture.bitmap(context) } }
    Canvas(modifier) {
        drawIntoCanvas { drawReaderPaper(it.nativeCanvas, size.width, size.height, value, texture) }
    }
}
