package app.hushtype.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val raw: String,
    val text: String,
    val date: Long = System.currentTimeMillis(),
    val appName: String? = null,
    /** Seconds from stopping to text ready. */
    val seconds: Double = 0.0,
    val polishedBy: String? = null,
    /** Why the polished version wasn't used, when it wasn't. */
    val note: String? = null,
    /** Kept because it couldn't be typed anywhere (no text field), so it's ready to paste. */
    val notInserted: Boolean = false,
)

/** Recent dictations, newest first, so nothing said is ever lost. */
class HistoryStore(private val file: File, private val limit: Int = 300) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HistoryEntry.serializer())
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun add(entry: HistoryEntry) = update { (listOf(entry) + it).take(limit) }
    fun remove(id: String) = update { list -> list.filter { it.id != id } }
    fun clear() = update { emptyList() }

    @Synchronized
    private fun update(edit: (List<HistoryEntry>) -> List<HistoryEntry>) {
        val next = edit(_entries.value)
        _entries.value = next
        runCatching { file.writeText(json.encodeToString(serializer, next)) }
    }

    private fun load(): List<HistoryEntry> = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrElse { emptyList() }
}
