package com.openjump.app.video

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the byte size of a recorded or imported video without loading its bytes.
 *
 * Resolution order per URI: explicit [File] length, `file://` path length,
 * `OpenableColumns.SIZE` query, then the file-descriptor `statSize`. No branch
 * ever reads media bytes, and unknown stays `null`: never a fabricated `0`.
 *
 * Blocking I/O: call from an IO dispatcher, never from the main thread nor
 * from the playback path. Provider, security and I/O failures all map to
 * `null` (unknown); only [CancellationException] is rethrown.
 */
object VideoFileSizeReader {

    fun sizeBytes(contentResolver: ContentResolver, uri: Uri): Long? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return null
            return sizeBytes(File(path))
        }
        val queried = querySizeBytes(contentResolver, uri)
        if (queried != null && queried > 0L) return queried
        // A zero query is ambiguous: a stale provider can report 0 for a deleted
        // backing file without throwing. Confirm via descriptor (real empty stays 0,
        // stale/missing maps to null); an unknown query falls through the same way.
        return descriptorSizeBytes(contentResolver, uri)
    }

    fun sizeBytes(context: Context, uri: Uri): Long? =
        sizeBytes(context.contentResolver, uri)

    /** Exact length of an existing regular file; `null` for missing, non-files or errors. */
    fun sizeBytes(file: File): Long? = try {
        if (!file.isFile) {
            null
        } else {
            val length = file.length()
            if (length < 0L) null else length
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun querySizeBytes(contentResolver: ContentResolver, uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || cursor.isNull(column)) {
                null
            } else {
                cursor.getLong(column).takeIf { it >= 0L }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun descriptorSizeBytes(contentResolver: ContentResolver, uri: Uri): Long? = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.statSize.takeIf { it >= 0L }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
