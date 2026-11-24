// SPDX-FileCopyrightText: Copyright 2026 PocketOberon contributors
// SPDX-License-Identifier: GPL-2.0-only

package org.capyps5.emulator

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** Maps storage-picker URIs back to real file paths (the app has all-files access). */
object StoragePaths {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** "primary:Games/X" or "1234-ABCD:Games/X" or "raw:/storage/..." to an absolute path. */
    fun documentIdToPath(docId: String): String? {
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
        if (!docId.contains(':')) return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /** Real file behind a picked document, or null when it can't be found or read in place. */
    fun documentUriToFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.canRead() }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val fromId = docId?.takeIf {
            uri.authority == EXTERNAL_STORAGE_AUTHORITY || it.startsWith("raw:")
        }?.let(::documentIdToPath)
        val fromData = fromId ?: runCatching {
            // Media and Downloads providers still expose the path for files they index.
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromData?.let(::File)?.takeIf { it.isFile && it.canRead() }
    }
}
