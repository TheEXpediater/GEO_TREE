package com.geotree.app.core.camera

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.geotree.app.data.repository.LocalImage
import com.geotree.app.data.repository.LocalImageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads images returned by the system photo picker. Oversized files are refused before upload. */
class ContentImageReader(context: Context, private val maxBytes: Int = MAX_BYTES) : LocalImageReader {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun read(uri: String): LocalImage? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(uri)
            val name = resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: parsed.lastPathSegment ?: "image"
            val bytes = resolver.openInputStream(parsed)?.use { input ->
                val buffer = input.readNBytesCompat(maxBytes + 1)
                if (buffer.size > maxBytes) return@runCatching null
                buffer
            } ?: return@runCatching null
            LocalImage(name, resolver.getType(parsed), bytes)
        }.getOrNull()
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (read < 0) break
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        /** Matches the server's 10 MB image limit. */
        const val MAX_BYTES = 10 * 1024 * 1024
    }
}
