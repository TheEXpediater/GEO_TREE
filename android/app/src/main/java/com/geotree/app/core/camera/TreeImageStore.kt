package com.geotree.app.core.camera

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * App-private storage for photos (tree photos in "tree_images", leaf-assessment photos in
 * "leaf_images"). Room stores only the file path, never the bytes.
 */
class TreeImageStore(context: Context, directoryName: String = "tree_images") {
    private val directory = File(context.filesDir, directoryName)

    fun newImageFile(): File {
        directory.mkdirs()
        return File(directory, "${UUID.randomUUID()}.jpg")
    }

    /** Copies picked image bytes into this store. */
    fun save(bytes: ByteArray): File = newImageFile().apply { writeBytes(bytes) }

    /** Deletes only files inside this store's directory. */
    fun delete(path: String?) {
        val file = path?.let(::File) ?: return
        if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
    }
}
