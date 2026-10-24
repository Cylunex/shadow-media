package com.shadow.media.reading

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.shadow.media.library.SafeArchives
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** User-selected font bytes stay in app-private storage; settings contain only a content hash. */
internal class ReaderFonts(private val context: Context) {
    private val folder = File(context.filesDir, "reader-fonts")
    suspend fun import(uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "自选字体"
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选字体" }.use { SafeArchives.readBounded(it, 32L * 1024 * 1024) }
        require(bytes.size >= 12) { "字体文件无效" }
        val signature = bytes.take(4).map(Byte::toInt)
        require(signature == listOf(0, 1, 0, 0) || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) in setOf("OTTO", "ttcf", "true")) { "请选择 TTF、OTF 或 TTC 字体" }
        val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "ttcf") {
            val count = header.getInt(8)
            require(count in 1..256 && 12L + count * 4 <= bytes.size) { "字体集合目录损坏" }
            repeat(count) { require(header.getInt(12 + it * 4) in 0..bytes.size - 12) { "字体集合索引损坏" } }
        } else {
            val count = header.getShort(4).toInt() and 0xffff
            require(count in 1..4096 && 12L + count * 16 <= bytes.size) { "字体文件目录损坏" }
            repeat(count) { index ->
                val offset = header.getInt(12 + index * 16 + 8).toLong() and 0xffffffffL
                val length = header.getInt(12 + index * 16 + 12).toLong() and 0xffffffffL
                require(offset + length <= bytes.size) { "字体数据不完整" }
            }
        }
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        folder.mkdirs()
        val target = File(folder, "$id.font")
        if (!target.isFile) {
            val temp = File.createTempFile("font-", ".part", folder)
            try {
                temp.writeBytes(bytes); Typeface.createFromFile(temp); ensureActive()
                check(temp.renameTo(target)) { "字体保存失败，请重试" }
            } finally { temp.delete() }
        }
        id to name.substringBeforeLast('.').take(120)
    }

    fun typeface(settings: ReaderSettings): Typeface? {
        val id = settings.customFontId.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        synchronized(fonts) {
            fonts.get(id)?.let { return Typeface.create(it, if (settings.bold) Typeface.BOLD else Typeface.NORMAL) }
            val file = File(folder, "$id.font")
            if (!file.isFile) return null
            return runCatching { Typeface.createFromFile(file).also { fonts.put(id, it) } }
                .getOrNull()?.let { Typeface.create(it, if (settings.bold) Typeface.BOLD else Typeface.NORMAL) }
        }
    }
    companion object { private val fonts = android.util.LruCache<String, Typeface>(4) }
}
