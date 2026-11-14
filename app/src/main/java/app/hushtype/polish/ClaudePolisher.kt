package app.hushtype.polish

import app.hushtype.core.DictationContext
import app.hushtype.core.Prompts
import app.hushtype.data.CleanupEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polishes with Claude Haiku over the Anthropic API, streamed so a slow start can be cut off.
 * Same request as the Mac app: cached system prompt, no thinking, low effort.
 */
class ClaudePolisher(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : Polisher {
    override val engine = CleanupEngine.CLAUDE

    override fun prewarm() {
        Thread {
            runCatching {
                (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 5000
                    readTimeout = 5000
                    responseCode
                    disconnect()
                }
            }
        }.start()
    }

    override suspend fun clean(
        transcript: String,
        vocabulary: List<String>,
        context: DictationContext?,
        keepVerbatim: List<String>,
    ): PolishOutput? {
        if (apiKey.isBlank()) throw PolishError("Add your Anthropic API key in Hushtype to polish with Claude.")
        val content = Prompts.cleanupContent(transcript, vocabulary, context, keepVerbatim)
        // Roughly 4 characters per token; leave room for formatting, but stop a runaway reply.
        val maxTokens = minOf(4096, transcript.length / 2 + 256)
        return streamWithDeadline(body(content, maxTokens))
    }

    private fun body(content: String, maxTokens: Int): String = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("stream", true)
        putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", Prompts.system)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        // Cleanup is a light rewrite; skip thinking to keep latency low.
        putJsonObject("thinking") { put("type", "disabled") }
        putJsonObject("output_config") { put("effort", "low") }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", content)
            }
        }
    }.toString()

    private suspend fun streamWithDeadline(body: String): PolishOutput? = coroutineScope {
        val started = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val sent = System.nanoTime()
        val connection = open()
        val work = async(Dispatchers.IO) { stream(connection, body) { started.set(true) } }
        val watchdog = async {
            delay(FIRST_TOKEN_TIMEOUT_MS)
            if (!started.get()) {
                timedOut.set(true)
                connection.disconnect()
            }
            delay(TOTAL_TIMEOUT_MS - FIRST_TOKEN_TIMEOUT_MS)
            timedOut.set(true)
            connection.disconnect()
        }
        try {
            val text = work.await()
            text?.let { PolishOutput(it, (System.nanoTime() - sent) / 1e9) }
        } catch (e: java.io.IOException) {
            if (timedOut.get()) throw PolishTimeout()
            throw PolishError("Couldn't reach Claude, so the transcript was inserted as heard.")
        } finally {
            watchdog.cancel()
            // Closing the socket also unblocks the read when the dictation is cancelled.
            connection.disconnect()
        }
    }

    private fun open() = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("x-api-key", apiKey)
        setRequestProperty("anthropic-version", API_VERSION)
        setRequestProperty("content-type", "application/json")
        setRequestProperty("accept", "text/event-stream")
    }

    private fun stream(connection: HttpURLConnection, body: String, onFirstToken: () -> Unit): String? {
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        if (status !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText().take(2000) } ?: ""
            throw PolishError(describeHttpError(status, error))
        }
        val text = StringBuilder()
        var sawFirst = false
        connection.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                when (val event = SseEvent.parse(line)) {
                    is SseEvent.Text -> {
                        if (!sawFirst && event.text.isNotEmpty()) {
                            sawFirst = true
                            onFirstToken()
                        }
                        text.append(event.text)
                    }
                    is SseEvent.Stop -> {
                        if (event.reason == "refusal") return null
                        if (event.reason == "max_tokens") throw PolishError("Claude's reply was cut short, so the transcript was inserted as heard.")
                    }
                    is SseEvent.Failure -> throw PolishError("Claude: ${event.message}")
                    SseEvent.End, null -> Unit
                }
            }
        }
        return text.toString().trim().ifEmpty { null }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        const val API_VERSION = "2026-10-21"
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        /** Give up on Claude and type the raw transcript if the first word hasn't arrived by then. */
        const val FIRST_TOKEN_TIMEOUT_MS = 2_000L
        /** Hard ceiling for a whole cleanup. */
        const val TOTAL_TIMEOUT_MS = 10_000L

        fun describeHttpError(status: Int, body: String): String {
            val message = runCatching {
                Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return when (status) {
                401 -> "Claude didn't accept the API key. Check it in Hushtype → Polish."
                429 -> "Claude is rate limiting this key right now, so the transcript was inserted as heard."
                529, 503 -> "Claude is overloaded right now, so the transcript was inserted as heard."
                else -> "Claude returned an error ($status${message?.let { ": $it" } ?: ""})."
            }
        }

        /** Checks a key with a one-token request. Returns null when it works, or what went wrong. */
        suspend fun validate(apiKey: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(15_000) {
                    val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        setRequestProperty("x-api-key", apiKey.trim())
                        setRequestProperty("anthropic-version", API_VERSION)
                        setRequestProperty("content-type", "application/json")
                    }
                    val body = buildJsonObject {
                        put("model", DEFAULT_MODEL)
                        put("max_tokens", 1)
                        putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", "hi") } }
                    }.toString()
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    val status = connection.responseCode
                    val error = if (status in 200..299) null else connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    connection.disconnect()
                    error?.let { describeHttpError(status, it) }
                }
            }.getOrElse { e ->
                if (e is TimeoutCancellationException) "Couldn't reach Claude. Check your connection." else "Couldn't reach Claude (${e.message})."
            }
        }
    }
}

/** One server-sent event line from the Messages API stream. Only `data:` lines carry anything. */
sealed interface SseEvent {
    data class Text(val text: String) : SseEvent
    data class Stop(val reason: String?) : SseEvent
    data class Failure(val message: String) : SseEvent
    data object End : SseEvent

    companion object {
        fun parse(line: String): SseEvent? {
            if (!line.startsWith("data:")) return null
            val obj = runCatching { Json.parseToJsonElement(line.substring(5).trim()) as JsonObject }.getOrNull() ?: return null
            val delta = obj["delta"] as? JsonObject
            return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "content_block_delta" -> {
                    if (delta?.get("type")?.jsonPrimitive?.contentOrNull != "text_delta") return null
                    Text(delta["text"]?.jsonPrimitive?.contentOrNull ?: return null)
                }
                "message_delta" -> Stop(delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull)
                "message_stop" -> End
                "error" -> Failure((obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "stream error")
                else -> null
            }
        }
    }
}
