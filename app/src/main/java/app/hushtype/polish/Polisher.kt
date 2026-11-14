package app.hushtype.polish

import app.hushtype.core.DictationContext
import app.hushtype.data.CleanupEngine

/** What a polish pass returned and how long it took. */
data class PolishOutput(val text: String, val seconds: Double)

/** The polish took too long; the transcript is typed as heard. */
class PolishTimeout : Exception()

/** A polish failure the user should hear about, in plain words. */
class PolishError(message: String) : Exception(message)

/** Something that cleans dictated transcripts: Claude or the on-device model. */
interface Polisher {
    val engine: CleanupEngine

    /** Gets ready ahead of the request (a TLS handshake, or loading the model). */
    fun prewarm() {}

    /** Cleans a transcript. Returns null when the engine declines or returns nothing. */
    suspend fun clean(
        transcript: String,
        vocabulary: List<String>,
        context: DictationContext?,
        keepVerbatim: List<String>,
    ): PolishOutput?
}
