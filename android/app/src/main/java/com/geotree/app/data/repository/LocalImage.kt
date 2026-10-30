package com.geotree.app.data.repository

/** An image picked on the device (e.g. a leaf photo from the gallery), read into memory. */
class LocalImage(val name: String, val mimeType: String?, val bytes: ByteArray)

/** Reads a picked image (content:// URI string). Null when it cannot be read. */
fun interface LocalImageReader {
    suspend fun read(uri: String): LocalImage?
}
