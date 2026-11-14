package app.hushtype.core

import kotlinx.serialization.Serializable

/**
 * The dictionary and snippets as stored on disk, with the edit times and removals that sync
 * needs: every entry carries when it last changed, and a removed entry stays as a tombstone so
 * the removal wins over an older copy from another device.
 */
@Serializable
data class VocabularyState(
    val terms: List<TermRecord> = emptyList(),
    val snippets: List<SnippetRecord> = emptyList(),
) {
    /** Dictionary terms in the order they were added. */
    val liveTerms: List<String> get() = terms.filter { !it.deleted }.sortedBy { it.createdAt }.map { it.text }
    val liveSnippets: List<Snippet> get() = snippets.filter { !it.deleted }.sortedBy { it.createdAt }
        .map { Snippet(it.id, it.trigger, it.expansion) }
}

@Serializable
data class TermRecord(
    /** The term, as typed. Its lowercase form is its identity, so two devices adding it agree. */
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Learned from a correction rather than typed in. */
    val learned: Boolean = false,
) {
    val key: String get() = keyOf(text)

    companion object {
        fun keyOf(text: String) = text.trim().lowercase()
    }
}

@Serializable
data class SnippetRecord(
    val id: String,
    val trigger: String,
    val expansion: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** Pure edits and the per-item merge that dictionary sync plugs into. */
object VocabularyEdits {
    fun addTerms(state: VocabularyState, terms: List<String>, now: Long, learned: Boolean = false): VocabularyState {
        val byKey = state.terms.associateBy { it.key }.toMutableMap()
        for (raw in terms) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val key = TermRecord.keyOf(text)
            val existing = byKey[key]
            byKey[key] = when {
                existing == null -> TermRecord(text, now, now, learned = learned)
                existing.deleted -> TermRecord(text, now, now, learned = learned)
                existing.text != text -> existing.copy(text = text, updatedAt = now)
                else -> existing
            }
        }
        return state.copy(terms = byKey.values.toList())
    }

    fun removeTerm(state: VocabularyState, term: String, now: Long): VocabularyState {
        val key = TermRecord.keyOf(term)
        return state.copy(terms = state.terms.map { if (it.key == key && !it.deleted) it.copy(deleted = true, updatedAt = now) else it })
    }

    fun upsertSnippet(state: VocabularyState, snippet: Snippet, now: Long): VocabularyState {
        val existing = state.snippets.firstOrNull { it.id == snippet.id }
        val record = SnippetRecord(
            id = snippet.id,
            trigger = snippet.trigger.trim(),
            expansion = snippet.expansion,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        return state.copy(snippets = state.snippets.filter { it.id != snippet.id } + record)
    }

    fun removeSnippet(state: VocabularyState, id: String, now: Long): VocabularyState =
        state.copy(snippets = state.snippets.map { if (it.id == id && !it.deleted) it.copy(deleted = true, updatedAt = now) else it })

    /**
     * Combines two copies item by item: the most recent edit of each entry wins, removals
     * included. Terms match by their lowercase text, snippets by id. Ties keep the local copy.
     */
    fun merge(local: VocabularyState, remote: VocabularyState): VocabularyState {
        val terms = (local.terms + remote.terms).groupBy { it.key }.map { (_, copies) ->
            copies.reduce { a, b -> if (b.updatedAt > a.updatedAt) b.copy(createdAt = minOf(a.createdAt, b.createdAt)) else a.copy(createdAt = minOf(a.createdAt, b.createdAt)) }
        }
        val snippets = (local.snippets + remote.snippets).groupBy { it.id }.map { (_, copies) ->
            copies.reduce { a, b -> if (b.updatedAt > a.updatedAt) b else a }
        }
        return VocabularyState(terms, snippets)
    }

    /** Drops tombstones older than [cutoff], once every device has had time to see them. */
    fun compact(state: VocabularyState, cutoff: Long): VocabularyState = state.copy(
        terms = state.terms.filter { !it.deleted || it.updatedAt >= cutoff },
        snippets = state.snippets.filter { !it.deleted || it.updatedAt >= cutoff },
    )
}
