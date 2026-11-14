package app.hushtype.core

import kotlinx.serialization.Serializable
import java.util.UUID

/** A spoken shortcut: say the trigger, get the full text. Same model as the Mac and iOS apps. */
@Serializable
data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    /** What you say, like "my calendar link". */
    val trigger: String,
    /** What gets typed. */
    val expansion: String,
) {
    val isUsable: Boolean get() = SnippetExpander.normalize(trigger).isNotEmpty() && expansion.isNotEmpty()
}

object SnippetExpander {
    private val leads = listOf("insert ", "paste ", "type ")

    /** When the whole dictation is a trigger ("insert my calendar link"), its expansion, typed as is. */
    fun wholeMatch(transcript: String, snippets: List<Snippet>): String? {
        var spoken = normalize(transcript)
        for (lead in leads) if (spoken.startsWith(lead)) spoken = spoken.removePrefix(lead)
        return snippets.firstOrNull { it.isUsable && normalize(it.trigger) == spoken }?.expansion
    }

    /** Replaces triggers spoken inside a sentence with their expansions. */
    fun expand(text: String, snippets: List<Snippet>): String {
        var result = text
        // Longer triggers first, so "my work address" wins over "my address".
        for (snippet in snippets.filter { it.isUsable }.sortedByDescending { it.trigger.length }) {
            val words = normalize(snippet.trigger).split(" ").map { Regex.escape(it) }
            val pattern = "(?<![\\p{L}\\p{N}])" + words.joinToString("[\\s,]+") + "(?![\\p{L}\\p{N}])"
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            result = regex.replace(result) { snippet.expansion }
        }
        return result
    }

    /** Lowercased words without punctuation, single-spaced. */
    fun normalize(text: String): String =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
