package com.openjump.app.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Publishes a completed camera recording in the user's shared video collection. */
object RecordedVideoPublisher {
    private const val MIME_TYPE = "video/mp4"
    private const val ALBUM_NAME = "OpenJump"
    private const val DISPLAY_PREFIX = "OpenJump_"

    /**
     * Copies [source] to the public Movies/OpenJump collection and removes the private source only
     * after the copy has completed. A null result leaves the source untouched so the selector can
     * still open it as an app-private fallback.
     */
    fun publish(
        context: Context,
        source: File,
        timestampMs: Long = System.currentTimeMillis(),
        shouldCancel: () -> Boolean = { false },
    ): Uri? {
        if (shouldCancel() || !source.isFile || source.length() == 0L) return null
        val displayName = "$DISPLAY_PREFIX$timestampMs.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(context, source, displayName, shouldCancel)
        } else {
            publishLegacy(context, source, displayName, shouldCancel)
        }
    }

    private fun publishWithMediaStore(
        context: Context,
        source: File,
        displayName: String,
        shouldCancel: () -> Boolean,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        // Insert is part of the transaction: provider exceptions must not escape to main.
        return runCatching {
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: return@runCatching null
            try {
                check(!shouldCancel()) { "Publicación cancelada" }
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            check(!shouldCancel()) { "Publicación cancelada" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("No se pudo abrir el vídeo publicado")
                check(resolver.openFileDescriptor(uri, "r")?.use { it.statSize == source.length() } == true) {
                    "La copia publicada está incompleta"
                }
                check(!shouldCancel()) { "Publicación cancelada" }
                check(resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1) { "No se pudo finalizar el vídeo publicado" }
                check(!shouldCancel()) { "Publicación cancelada" }
                check(runCatching { source.delete() }.getOrDefault(false)) {
                    "No se pudo eliminar la copia privada"
                }
                uri
            } catch (_: Exception) {
                // Roll back the pending row; cleanup itself must never mask the original failure.
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        context: Context,
        source: File,
        displayName: String,
        shouldCancel: () -> Boolean,
    ): Uri? {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ALBUM_NAME,
        )
        if (!directory.exists() && !directory.mkdirs()) return null
        val destination = File(directory, displayName)
        val partial = File(directory, ".$displayName.partial")
        return try {
            runCatching { partial.delete() }
            check(!shouldCancel()) { "Publicación cancelada" }
            source.inputStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        check(!shouldCancel()) { "Publicación cancelada" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(partial.isFile && partial.length() == source.length()) {
                "La copia publicada está incompleta"
            }
            check(!shouldCancel()) { "Publicación cancelada" }
            check(partial.renameTo(destination)) { "No se pudo finalizar el vídeo publicado" }
            check(!shouldCancel()) { "Publicación cancelada" }
            check(runCatching { source.delete() }.getOrDefault(false)) {
                "No se pudo eliminar la copia privada"
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destination.absolutePath),
                arrayOf(MIME_TYPE),
                null,
            )
            Uri.fromFile(destination)
        } catch (_: Exception) {
            runCatching { partial.delete() }
            runCatching { destination.delete() }
            null
        }
    }
}
