package com.openjump.app.data.export

import java.io.Writer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A bounded, cancellable sink. Production writers never build the complete export text. */
internal class CancellableExportWriter(
    private val delegate: Writer,
    private val chunkSize: Int = 8 * 1024,
) {
    init { require(chunkSize > 0) }

    suspend fun write(text: CharSequence) {
        var offset = 0
        while (offset < text.length) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + chunkSize, text.length)
            delegate.write(text.subSequence(offset, end).toString())
            offset = end
        }
    }

    suspend fun writeChar(value: Char) = write(value.toString())

    suspend fun flush() {
        currentCoroutineContext().ensureActive()
        delegate.flush()
    }
}

/** Kept for compatibility with existing cancellation tests; not used by production export. */
suspend fun writeExportChunks(text: String, chunkSize: Int = 8 * 1024, writeChunk: (String) -> Unit) {
    require(chunkSize > 0) { "chunkSize must be positive" }
    var offset = 0
    while (offset < text.length) {
        currentCoroutineContext().ensureActive()
        val end = minOf(offset + chunkSize, text.length)
        writeChunk(text.substring(offset, end))
        offset = end
    }
    currentCoroutineContext().ensureActive()
}
