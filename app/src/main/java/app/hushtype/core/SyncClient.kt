package app.hushtype.core

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Something went wrong syncing; [message] is for the user. */
class SyncException(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    enum class Kind { NO_SUCH_GROUP, UNAUTHORIZED, NETWORK, OTHER }
}

/**
 * Talks to the sync server: fetch the blob, merge, and write back with a version check, retrying
 * when another device wrote first.
 */
class SyncClient(
    private val keys: SyncKeys,
    private val server: String = DEFAULT_SERVER,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Remote(val state: VocabularyState?, val etag: String?)

    /**
     * One round: returns what the device should now hold. With [mustExist] (joining a group), a
     * group nobody has written to yet is an error instead of a fresh start.
     */
    fun sync(local: VocabularyState, mustExist: Boolean = false): VocabularyState {
        repeat(5) {
            val remote = fetch()
            if (remote == null && mustExist) throw SyncException("No device is syncing with that code yet.", SyncException.Kind.NO_SUCH_GROUP)
            val merged = VocabularyEdits.compact(
                if (remote?.state != null) VocabularyEdits.merge(local, remote.state) else local,
                clock() - TOMBSTONE_DAYS * 86_400_000L,
            )
            if (remote?.state == merged) return merged
            if (put(merged, remote?.etag)) return merged
        }
        throw SyncException("Other devices kept changing the dictionary. Try again in a moment.")
    }

    private fun fetch(): Remote? {
        val connection = open("GET")
        try {
            return when (val status = connection.responseCode) {
                200 -> {
                    val blob = connection.inputStream.use { it.readBytes() }
                    val state = runCatching { json.decodeFromString(VocabularyState.serializer(), String(keys.open(blob))) }
                        .getOrElse { throw SyncException("Couldn't read the synced dictionary. Is the code from the same group?") }
                    Remote(state, connection.getHeaderField("ETag"))
                }
                404 -> null
                401 -> throw SyncException("The sync server didn't accept this device. Pair it again.", SyncException.Kind.UNAUTHORIZED)
                else -> throw SyncException("The sync server returned an error ($status).")
            }
        } catch (e: IOException) {
            throw SyncException("Couldn't reach the sync server.", SyncException.Kind.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    /** Returns false when another device wrote first (412). */
    private fun put(state: VocabularyState, etag: String?): Boolean {
        val body = keys.seal(json.encodeToString(VocabularyState.serializer(), state).toByteArray())
        val connection = open("PUT").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            if (etag == null) setRequestProperty("If-None-Match", "*") else setRequestProperty("If-Match", etag)
        }
        try {
            connection.outputStream.use { it.write(body) }
            return when (val status = connection.responseCode) {
                200, 201, 204 -> true
                412 -> false
                401 -> throw SyncException("The sync server didn't accept this device. Pair it again.", SyncException.Kind.UNAUTHORIZED)
                413 -> throw SyncException("The dictionary is too big to sync.")
                else -> throw SyncException("The sync server returned an error ($status).")
            }
        } catch (e: IOException) {
            throw SyncException("Couldn't reach the sync server.", SyncException.Kind.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(method: String) = (URL("$server/v1/groups/${keys.groupId}").openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Authorization", "Bearer ${keys.authToken}")
    }

    companion object {
        const val DEFAULT_SERVER = "https://hushtype-sync.chiranjib-jena.workers.dev"
        const val TOMBSTONE_DAYS = 30L
    }
}
