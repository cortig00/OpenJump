package com.openjump.app.ui.settings

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DataExportOperationTest {
    private suspend inline fun <reified T : Throwable> expectFailure(crossinline block: suspend () -> Unit): T {
        try {
            block()
            throw AssertionError("expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
    }

    @Test
    fun cancellationCleansUpAndIsPropagated() = runBlocking {
        val deletes = AtomicInteger(0)
        var chunks = 0
        expectFailure<CancellationException> {
            runDataExportOperation(
                openOutputStream = { ByteArrayOutputStream() },
                write = { writer ->
                    writer.write("first")
                    chunks++
                    throw CancellationException("cancelled")
                },
                delete = { deletes.incrementAndGet() },
            )
        }
        assertEquals(1, chunks)
        assertEquals(1, deletes.get())
    }

    @Test
    fun writerFailureCleansUpAndPreservesOriginalError() = runBlocking {
        val deletes = AtomicInteger(0)
        val failure = expectFailure<IOException> {
            runDataExportOperation(
                openOutputStream = { ByteArrayOutputStream() },
                write = { throw IOException("writer failed") },
                delete = { deletes.incrementAndGet() },
            )
        }
        assertEquals("writer failed", failure.message)
        assertEquals(1, deletes.get())
    }

    @Test
    fun nullOutputStreamIsAnErrorAndIsDeletedBestEffort() = runBlocking {
        val deletes = AtomicInteger(0)
        expectFailure<IOException> {
            runDataExportOperation(
                openOutputStream = { null },
                write = { error("must not write") },
                delete = { deletes.incrementAndGet() },
            )
        }
        assertEquals(1, deletes.get())
    }

    @Test
    fun deleteFailureDoesNotHideCancellationOrWriterError() = runBlocking {
        val cancellation = expectFailure<CancellationException> {
            runDataExportOperation(
                openOutputStream = { ByteArrayOutputStream() },
                write = { throw CancellationException("cancelled") },
                delete = { throw IOException("delete failed") },
            )
        }
        assertEquals("cancelled", cancellation.message)

        val failure = expectFailure<IOException> {
            runDataExportOperation(
                openOutputStream = { ByteArrayOutputStream() },
                write = { throw IOException("write failed") },
                delete = { throw IOException("delete failed") },
            )
        }
        assertEquals("write failed", failure.message)
    }
}
