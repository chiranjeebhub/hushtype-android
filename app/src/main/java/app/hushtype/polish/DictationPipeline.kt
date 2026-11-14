package app.hushtype.polish

import app.hushtype.core.DictationContext
import app.hushtype.core.Prompts
import app.hushtype.core.Snippet
import app.hushtype.core.SnippetExpander
import kotlinx.coroutines.CancellationException

data class DictationResult(
    val raw: String,
    val text: String,
    /** Seconds the polish took, when it ran. */
    val polishSeconds: Double? = null,
    val polishedBy: String? = null,
    /** Why the polished version wasn't used, when it wasn't. */
    val fallbackReason: String? = null,
)

/** Transcript in, polished text out. Same steps as the Mac app's pipeline. */
class DictationPipeline(
    private val polisher: Polisher?,
    private val vocabulary: List<String>,
    private val context: DictationContext?,
    private val snippets: List<Snippet>,
) {
    /** Returns null when nothing was said. Never throws for a polish failure: the dictation is never lost. */
    suspend fun polish(transcript: String, onPolishStart: () -> Unit = {}): DictationResult? {
        val raw = transcript.trim()
        if (raw.isEmpty()) return null

        // Saying just a snippet's trigger types its text exactly, with no polishing.
        SnippetExpander.wholeMatch(raw, snippets)?.let { expansion ->
            return DictationResult(raw, context?.fit(expansion) ?: expansion)
        }

        var cleaned = raw
        var seconds: Double? = null
        var polishedBy: String? = null
        var fallback: String? = null
        if (polisher != null && Prompts.needsPolish(raw)) {
            onPolishStart()
            try {
                polisher.clean(raw, vocabulary, context, snippets.map { it.trigger })?.let {
                    cleaned = it.text
                    seconds = it.seconds
                    polishedBy = polisher.engine.serviceName
                }
            } catch (e: PolishTimeout) {
                fallback = "${polisher.engine.serviceName} was slow, so the transcript was inserted as heard."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallback = e.message ?: "Polishing failed, so the transcript was inserted as heard."
            }
        }
        cleaned = SnippetExpander.expand(cleaned, snippets)
        if (context != null) cleaned = context.fit(cleaned)
        return DictationResult(raw, cleaned, seconds, polishedBy, fallback)
    }
}
