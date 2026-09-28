package com.geotree.app.core.camera

import android.content.Context
import java.io.File
import java.util.UUID

/** App-private storage for tree photos. Room stores only the file path, never the bytes. */
class TreeImageStore(context: Context) {
    private val directory = File(context.filesDir, "tree_images")

    fun newImageFile(): File {
        directory.mkdirs()
        return File(directory, "${UUID.randomUUID()}.jpg")
    }

    /** Deletes only files inside the app's tree image directory. */
    fun delete(path: String?) {
        val file = path?.let(::File) ?: return
        if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
    }
}
