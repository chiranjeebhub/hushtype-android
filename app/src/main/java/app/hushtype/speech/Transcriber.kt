package app.hushtype.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live speech-to-text with Android's recognizer, on the phone when it can be. Android's
 * recognizer stops on its own after a pause, so while the user is still dictating Hushtype
 * starts it again and keeps what it heard, which makes one long dictation out of several takes.
 * Use from the main thread.
 */
class Transcriber(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    /** On-device recognition failed for this language; use the regular recognizer from now on. */
    private var onDeviceRefused = false

    private val _level = MutableStateFlow(0f)
    /** Microphone loudness, 0…1, for the bubble's animation. */
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _text = MutableStateFlow("")
    /** Everything heard so far, including words that may still change. */
    val text: StateFlow<String> = _text.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** The recognizer is actually hearing. It can take most of a second to get there after [start]. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var active = false
    private var finishing = false
    private val committed = StringBuilder()
    private var partial = ""
    private var finished: CompletableDeferred<String>? = null
    private var options: Options? = null
    private var errorHandler: ((String) -> Unit)? = null
    private var restarts = 0

    data class Options(val language: String?, val bias: List<String>, val preferOffline: Boolean)

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) ||
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Starts listening. [onError] gets a sentence for the user when the recognizer gives up. */
    fun start(options: Options, onError: (String) -> Unit) {
        cancel()
        this.options = options
        errorHandler = onError
        committed.clear()
        partial = ""
        _text.value = ""
        _ready.value = false
        restarts = 0
        active = true
        finishing = false
        finished = CompletableDeferred()
        listen()
    }

    /** Stops listening and returns everything heard. */
    suspend fun finish(): String {
        val deferred = finished ?: return ""
        if (!active) return currentText()
        finishing = true
        recognizer?.stopListening()
        // The recognizer normally answers within a few hundred milliseconds of being stopped.
        val result = withTimeoutOrNull(4_000) { deferred.await() } ?: currentText()
        active = false
        _level.value = 0f
        return result
    }

    fun cancel() {
        if (!active) return
        active = false
        finishing = false
        recognizer?.cancel()
        finished?.complete(currentText())
        _level.value = 0f
    }

    fun release() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun currentText(): String = join(committed.toString(), partial)

    private fun listen() {
        val opts = options ?: return
        val wantOnDevice = opts.preferOffline && !onDeviceRefused &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (recognizer == null || onDevice != wantOnDevice) {
            recognizer?.destroy()
            recognizer = if (wantOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            onDevice = wantOnDevice
            recognizer?.setRecognitionListener(listener)
        }
        recognizer?.startListening(intent(opts))
    }

    private fun intent(opts: Options) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (!opts.language.isNullOrBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, opts.language)
        if (opts.preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Ask for long pauses before the recognizer decides the user is done.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Punctuation and capitals from the recognizer itself, and the dictionary as hints.
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            if (opts.bias.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(opts.bias.take(MAX_BIAS)))
            }
        }
    }

    private fun commit(results: Bundle?) {
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        val text = heard.ifBlank { partial }
        if (text.isNotBlank()) {
            if (committed.isNotEmpty()) committed.append(' ')
            committed.append(text.trim())
        }
        partial = ""
        _text.value = committed.toString()
    }

    /** The recognizer ended a take. Keep going if the user hasn't stopped, otherwise hand back the text. */
    private fun takeEnded() {
        if (active && !finishing && restarts < MAX_RESTARTS) {
            restarts++
            main.post { if (active && !finishing) listen() }
        } else {
            active = false
            _level.value = 0f
            finished?.complete(committed.toString())
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _ready.value = true
        }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            // Roughly -2 dB (silence) to 10 dB (speaking up).
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _level.value = _level.value * 0.5f + normalized * 0.5f
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (heard.isBlank()) return
            partial = heard
            _text.value = currentText()
        }

        override fun onResults(results: Bundle?) {
            commit(results)
            takeEnded()
        }

        override fun onError(error: Int) {
            Log.i(TAG, "Recognizer error $error (on-device: $onDevice)")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Silence. Keep anything half-heard and carry on.
                    commit(null)
                    takeEnded()
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    commit(null)
                    active = false
                    finished?.complete(committed.toString())
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (onDevice) {
                        // This language isn't on the phone; switch to the regular recognizer.
                        onDeviceRefused = true
                        main.post { if (active) listen() }
                    } else {
                        fail("Android's speech recognizer doesn't support this language yet.")
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.destroy()
                    recognizer = null
                    if (active && restarts++ < 3) main.postDelayed({ if (active) listen() }, 150) else fail("The speech recognizer is busy. Try again in a moment.")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Hushtype needs microphone access. Open Hushtype to allow it.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                    fail("Speech recognition needs the network for this language. Download it offline in Hushtype → Settings.")
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> fail("Android's speech recognizer is rate limited. Try again in a moment.")
                SpeechRecognizer.ERROR_AUDIO -> fail("The microphone isn't available right now. Another app may be using it.")
                else -> fail("Speech recognition stopped (error $error).")
            }
        }
    }

    private fun fail(message: String) {
        val hadText = committed.isNotEmpty() || partial.isNotEmpty()
        commit(null)
        active = false
        _level.value = 0f
        finished?.complete(committed.toString())
        // With words already heard, the dictation still goes through; only an empty one reports the error.
        if (!hadText) errorHandler?.invoke(message)
    }

    companion object {
        private const val TAG = "HushtypeSpeech"
        private const val MAX_BIAS = 200
        /** About ten minutes of pauses. */
        private const val MAX_RESTARTS = 120

        fun join(a: String, b: String): String = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
