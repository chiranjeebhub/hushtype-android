package app.hushtype.data

import app.hushtype.core.Snippet
import app.hushtype.core.VocabularyEdits
import app.hushtype.core.VocabularyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The dictionary and snippets. Everything that reads or edits them goes through here, so a sync
 * engine only has to plug into [state] (to push) and [merge] (to apply what came from another device).
 */
interface VocabularyRepository {
    /** Everything, including removals, for sync. */
    val state: StateFlow<VocabularyState>

    val terms: List<String> get() = state.value.liveTerms
    val snippets: List<Snippet> get() = state.value.liveSnippets

    fun addTerms(terms: List<String>, learned: Boolean = false)
    fun removeTerm(term: String)
    fun upsertSnippet(snippet: Snippet)
    fun removeSnippet(id: String)

    /** Applies a copy from another device, entry by entry; the newest edit of each entry wins. */
    fun merge(remote: VocabularyState)
}

/** Keeps the vocabulary in one JSON file in the app's private storage. */
class FileVocabularyRepository(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : VocabularyRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _state = MutableStateFlow(load())
    override val state: StateFlow<VocabularyState> = _state.asStateFlow()

    override fun addTerms(terms: List<String>, learned: Boolean) =
        update { VocabularyEdits.addTerms(it, terms, clock(), learned) }

    override fun removeTerm(term: String) = update { VocabularyEdits.removeTerm(it, term, clock()) }

    override fun upsertSnippet(snippet: Snippet) = update { VocabularyEdits.upsertSnippet(it, snippet, clock()) }

    override fun removeSnippet(id: String) = update { VocabularyEdits.removeSnippet(it, id, clock()) }

    override fun merge(remote: VocabularyState) = update { VocabularyEdits.merge(it, remote) }

    private fun update(edit: (VocabularyState) -> VocabularyState) {
        synchronized(lock) {
            val next = edit(_state.value)
            if (next == _state.value) return
            _state.value = next
            save(next)
        }
    }

    private fun load(): VocabularyState = runCatching {
        if (file.exists()) json.decodeFromString(VocabularyState.serializer(), file.readText()) else seed()
    }.getOrElse { VocabularyState() }

    /** A fresh install starts with the app's own name spelled right. */
    private fun seed(): VocabularyState = VocabularyEdits.addTerms(VocabularyState(), listOf("Hushtype"), clock())

    private fun save(state: VocabularyState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(VocabularyState.serializer(), state))
        tmp.renameTo(file)
    }
}
