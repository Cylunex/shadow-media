package com.shadow.media.reading

import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File

/** Detect paper margins on a thumbnail; the original file remains the tiled image source. */
internal fun comicContentRegion(file: File, width: Int, height: Int): Rect? {
    if (width < 48 || height < 48) return null
    var sample = 1
    while (maxOf(width, height) / sample > 384) sample *= 2
    val preview = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    try {
        val w = preview.width; val h = preview.height
        var left = w; var right = -1; var top = h; var bottom = -1
        val row = IntArray(w)
        for (y in 0 until h) {
            preview.getPixels(row, 0, w, 0, y, w, 1)
            for (x in row.indices) {
                val color = row[x]
                if (color ushr 24 < 64 || minOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255) >= 242) continue
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        if (left >= right || top >= bottom) return null
        val region = Rect(((left - 2).coerceIn(0, w / 5).toLong() * width / w).toInt(), ((top - 2).coerceIn(0, h / 5).toLong() * height / h).toInt(),
            ((right + 3).coerceIn(w - w / 5, w).toLong() * width / w).toInt(),
            ((bottom + 3).coerceIn(h - h / 5, h).toLong() * height / h).toInt())
        return region.takeUnless { it == Rect(0, 0, width, height) }
    } finally { preview.recycle() }
}
