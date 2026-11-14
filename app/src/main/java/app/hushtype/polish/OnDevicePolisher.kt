package app.hushtype.polish

import app.hushtype.core.DictationContext
import app.hushtype.core.OnDeviceText
import app.hushtype.core.Prompts
import app.hushtype.data.CleanupEngine
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

/** Whether Gemini Nano can run here, and if not, what the user can do about it. */
enum class OnDeviceAvailability(val explanation: String?) {
    AVAILABLE(null),
    DOWNLOADABLE("Gemini Nano needs a one-time download on this phone."),
    DOWNLOADING("Gemini Nano is still downloading. Hushtype will use it once it's ready."),
    UNSUPPORTED("This phone can't run Gemini Nano. Use Claude to polish instead."),
}

sealed interface ModelDownload {
    data class Progress(val fraction: Float?) : ModelDownload
    data object Done : ModelDownload
    data class Failed(val message: String) : ModelDownload
}

/**
 * Polishes with Gemini Nano through ML Kit's Prompt API. Nothing leaves the phone, it costs
 * nothing and it works offline. Runs on phones with AICore (Pixel 9 and later, recent Galaxy S…).
 */
class OnDevicePolisher : Polisher {
    override val engine = CleanupEngine.ON_DEVICE

    private val model: GenerativeModel by lazy { Generation.getClient() }

    suspend fun availability(): OnDeviceAvailability = runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> OnDeviceAvailability.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> OnDeviceAvailability.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> OnDeviceAvailability.DOWNLOADING
            else -> OnDeviceAvailability.UNSUPPORTED
        }
    }.getOrDefault(OnDeviceAvailability.UNSUPPORTED)

    fun download(): Flow<ModelDownload> {
        var total = 0L
        return model.download().map { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> {
                    total = status.bytesToDownload
                    ModelDownload.Progress(0f)
                }
                is DownloadStatus.DownloadProgress ->
                    ModelDownload.Progress(if (total > 0) status.totalBytesDownloaded.toFloat() / total else null)
                is DownloadStatus.DownloadFailed -> ModelDownload.Failed(status.e.message ?: "The download failed.")
                else -> ModelDownload.Done
            }
        }
    }

    override fun prewarm() {
        // Loading the model is what makes the first polish slow; warmup does that ahead of time.
        CoroutineScope(Dispatchers.Default).launch { runCatching { model.warmup() } }
    }

    override suspend fun clean(
        transcript: String,
        vocabulary: List<String>,
        context: DictationContext?,
        keepVerbatim: List<String>,
    ): PolishOutput? {
        val hasCursorText = context != null && (context.textBefore.isNotEmpty() || context.textAfter.isNotEmpty())
        val instructions = Prompts.onDevice(hasCursorText, keepVerbatim.isNotEmpty())
        val prompt = instructions + "\n\n" + Prompts.cleanupContent(transcript, vocabulary, context, keepVerbatim)
        val started = System.nanoTime()
        val reply = try {
            withTimeout(TIMEOUT_MS) {
                model.generateContent(
                    generateContentRequest(TextPart(prompt)) {
                        // Greedy-ish decoding: the same words in give the same cleanup out.
                        temperature = 0f
                        topK = 1
                        maxOutputTokens = minOf(1024, transcript.length / 2 + 128)
                    },
                ).candidates.firstOrNull()?.text
            }
        } catch (e: TimeoutCancellationException) {
            throw PolishTimeout()
        } catch (e: GenAiException) {
            throw PolishError(describe(e))
        }
        val text = OnDeviceText.unwrap(reply ?: return null)
        if (text.isEmpty()) return null
        // A small model sometimes answers the transcript instead of cleaning it.
        if (!OnDeviceText.isPlausibleCleanup(text, transcript)) return null
        return PolishOutput(OnDeviceText.punctuate(text, transcript), (System.nanoTime() - started) / 1e9)
    }

    private fun describe(e: GenAiException): String = when (e.errorCode) {
        GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NOT_SUPPORTED,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE ->
            "Gemini Nano isn't available on this phone right now, so the transcript was inserted as heard."
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
            "Android paused Gemini Nano while Hushtype was in the background, so the transcript was inserted as heard."
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
            "Gemini Nano hit Android's battery limit for now, so the transcript was inserted as heard."
        GenAiException.ErrorCode.BUSY -> "Gemini Nano was busy, so the transcript was inserted as heard."
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "That was too long for Gemini Nano, so the transcript was inserted as heard."
        else -> "Gemini Nano couldn't polish that, so the transcript was inserted as heard."
    }

    companion object {
        /** A cleanup normally takes a second or two; the first one also loads the model. */
        const val TIMEOUT_MS = 10_000L
    }
}
