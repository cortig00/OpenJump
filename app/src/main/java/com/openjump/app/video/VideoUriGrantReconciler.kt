package com.openjump.app.video

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Narrow platform boundary so grant reconciliation policy remains JVM-testable. */
internal interface PersistedVideoGrantStore {
    fun persistedReadUris(): Set<String>
    fun takeReadGrant(uri: String)
    fun releaseReadGrant(uri: String)
}

internal class AndroidPersistedVideoGrantStore(
    private val resolver: ContentResolver,
) : PersistedVideoGrantStore {
    override fun persistedReadUris(): Set<String> = resolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()

    override fun takeReadGrant(uri: String) {
        resolver.takePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releaseReadGrant(uri: String) {
        resolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * Releases only stale persisted read grants. Callers register transient owners synchronously before
 * permission acquisition, AppSession hand-off, or a save dispatcher hand-off. Room ownership is
 * queried once per sweep. Provider calls are serialized by [gate], but never run under [ownerLock].
 * If ownership changes during a provider release, the grant is reacquired before the sweep exits.
 * Query failures retain the current and all remaining grants (fail closed).
 */
internal class VideoUriGrantReconciler(
    private val grants: PersistedVideoGrantStore,
    private val savedReferences: suspend () -> Set<String>,
    private val liveReferences: () -> Set<String>,
    private val diagnostic: (String) -> Unit = { code -> Log.w(TAG, code) },
) {
    /** Serializes provider list/take/release IPC and the Room ownership snapshot. */
    private val gate = Mutex()
    /** Synchronous registration/close stays brief even when provider IPC is blocked. */
    private val ownerLock = Any()
    private var nextOwnerId = 0L
    private var ownerGeneration = 0L
    private val owners = mutableMapOf<Long, Set<String>>()
    private val releasing = mutableSetOf<String>()

    fun registerOwner(uriStrings: Set<String>): Lease {
        val uris = uriStrings.filterTo(linkedSetOf(), ::isContentUri)
        val id = synchronized(ownerLock) {
            nextOwnerId += 1L
            if (uris.isNotEmpty()) {
                owners[nextOwnerId] = uris
                ownerGeneration += 1L
            }
            nextOwnerId
        }
        return Lease {
            synchronized(ownerLock) {
                if (owners.remove(id) != null) ownerGeneration += 1L
            }
        }
    }

    suspend fun takeReadGrant(uri: String) = gate.withLock {
        if (!isContentUri(uri)) return@withLock
        try {
            grants.takeReadGrant(uri)
        } catch (_: SecurityException) {
            diagnostic("URI_GRANT_TAKE_DENIED")
        } catch (_: IllegalArgumentException) {
            diagnostic("URI_GRANT_TAKE_INVALID")
        }
    }

    suspend fun reconcile() = gate.withLock {
        val persisted = try {
            grants.persistedReadUris()
        } catch (_: SecurityException) {
            diagnostic("URI_GRANT_LIST_DENIED")
            return@withLock
        } catch (_: IllegalArgumentException) {
            diagnostic("URI_GRANT_LIST_INVALID")
            return@withLock
        } catch (_: Exception) {
            diagnostic("URI_GRANT_LIST_FAILED")
            return@withLock
        }
        val candidates = persisted.filter(::isContentUri)
        if (candidates.isEmpty()) return@withLock

        // A save may commit and close its lease while this query is in flight. Trust the Room
        // snapshot only if no owner mutation overlaps it or occurs before a release reservation.
        val generationBeforeQuery = ownerGeneration()
        val saved = try {
            savedReferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diagnostic("URI_GRANT_OWNER_QUERY_FAILED")
            return@withLock
        }
        if (ownerGeneration() != generationBeforeQuery) {
            diagnostic("URI_GRANT_OWNER_CHANGED_DURING_QUERY")
            return@withLock
        }

        for (uri in candidates) {
            if (uri in saved) continue

            val canConsiderRelease = synchronized(ownerLock) {
                ownerGeneration == generationBeforeQuery && owners.values.none { uri in it } && uri !in releasing
            }
            if (!canConsiderRelease) {
                // A new lease invalidates the cached saved snapshot for the rest of this sweep.
                if (ownerGeneration() != generationBeforeQuery) return@withLock
                continue
            }

            // AppSession changes on the main thread; producers register an owner before publishing
            // a URI there. Query outside ownerLock, then reserve against the owner generation.
            val live = try {
                liveReferences()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                diagnostic("URI_GRANT_LIVE_OWNER_QUERY_FAILED")
                return@withLock
            }
            if (uri in live) continue

            val reservation = synchronized(ownerLock) {
                if (ownerGeneration != generationBeforeQuery || owners.values.any { uri in it } || uri in releasing) {
                    null
                } else {
                    releasing += uri
                    ReleaseReservation(uri, ownerGeneration)
                }
            } ?: return@withLock

            var releaseFailed = false
            var ownershipChanged = false
            try {
                try {
                    // Never hold ownerLock across this synchronous Binder/provider call.
                    grants.releaseReadGrant(uri)
                } catch (_: SecurityException) {
                    diagnostic("URI_GRANT_RELEASE_DENIED")
                    releaseFailed = true
                } catch (_: IllegalArgumentException) {
                    diagnostic("URI_GRANT_RELEASE_INVALID")
                    releaseFailed = true
                } catch (_: Exception) {
                    diagnostic("URI_GRANT_RELEASE_FAILED")
                    releaseFailed = true
                }
            } finally {
                ownershipChanged = synchronized(ownerLock) {
                    releasing.remove(reservation.uri)
                    ownerGeneration != reservation.generation || owners.values.any { uri in it }
                }
                if (ownershipChanged) {
                    // A save can commit and close its lease while release IPC is blocked. Compensate
                    // before leaving the serialized gate, even if the cached Room set was empty.
                    takeReadGrantBestEffort(uri)
                }
            }
            if (ownershipChanged) {
                // The saved snapshot is now stale; let the next sweep decide whether to release.
                return@withLock
            }
            if (releaseFailed) return@withLock
        }
    }

    private suspend fun takeReadGrantBestEffort(uri: String) {
        try {
            grants.takeReadGrant(uri)
        } catch (_: SecurityException) {
            diagnostic("URI_GRANT_RESTORE_DENIED")
        } catch (_: IllegalArgumentException) {
            diagnostic("URI_GRANT_RESTORE_INVALID")
        } catch (_: Exception) {
            diagnostic("URI_GRANT_RESTORE_FAILED")
        }
    }

    private fun ownerGeneration(): Long = synchronized(ownerLock) { ownerGeneration }

    private data class ReleaseReservation(val uri: String, val generation: Long)

    private fun isContentUri(value: String): Boolean = try {
        URI(value).scheme == "content"
    } catch (_: URISyntaxException) {
        false
    }

    internal class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private companion object {
        const val TAG = "OpenJumpUriGrant"
    }
}

/** Ensures synchronously-owned resources are released even if [scope] is already cancelled. */
internal fun CoroutineScope.launchWithLease(
    lease: VideoUriGrantReconciler.Lease?,
    onCompletion: () -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = launch(block = block)
    job.invokeOnCompletion {
        try {
            lease?.close()
        } finally {
            onCompletion()
        }
    }
    return job
}
