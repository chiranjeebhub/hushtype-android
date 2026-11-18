package app.hushtype.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.hushtype.HushtypeApp
import app.hushtype.core.CorrectionLearner
import app.hushtype.core.DictationContext
import app.hushtype.data.CleanupEngine
import app.hushtype.data.HistoryEntry
import app.hushtype.polish.ClaudePolisher
import app.hushtype.polish.DictationPipeline
import app.hushtype.polish.Polisher
import app.hushtype.speech.Transcriber
import app.hushtype.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where a dictation goes: the text field that had focus when it started. */
interface TextTarget {
    /** The focused field and what's around the cursor, or null when no text field has focus. */
    fun capture(): FieldSnapshot?
    /** Types [text] at the snapshot's cursor. Returns false when it couldn't be typed. */
    fun insert(snapshot: FieldSnapshot?, text: String): Boolean
    /** The field's current text, for learning corrections; null once it's gone. */
    fun read(snapshot: FieldSnapshot): String?
    /** Puts text on the clipboard for the user to paste. */
    fun copy(text: String)
}

/** A focused text field at the moment dictation started. */
class FieldSnapshot(
    val handle: Any,
    val context: DictationContext,
    /** Where the cursor (or selection) started, in characters. */
    val selectionStart: Int,
    val isPassword: Boolean,
)

/** What the bubble shows. */
sealed interface Phase {
    data object Idle : Phase
    data object Listening : Phase
    /** Text is coming: the recognizer is finishing or the polish is running. */
    data class Working(val polishing: Boolean) : Phase
    data object Done : Phase
    data class Notice(val message: String, val isError: Boolean = false, val action: NoticeAction? = null) : Phase
}

enum class NoticeAction { OPEN_APP }

/**
 * Runs one dictation at a time: listen, polish, type, then watch the field for corrections.
 * Main thread only.
 */
class DictationController(private val app: HushtypeApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transcriber by lazy { Transcriber(app) }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val level: StateFlow<Float> get() = transcriber.level
    val liveText: StateFlow<String> get() = transcriber.text
    val hearing: StateFlow<Boolean> get() = transcriber.ready

    var target: TextTarget? = null
    private var snapshot: FieldSnapshot? = null
    private var startedAt = 0L
    private var work: Job? = null
    private var phaseReset: Job? = null
    private var micRelease: Job? = null

    val isBusy: Boolean get() = _phase.value is Phase.Listening || _phase.value is Phase.Working

    /** Tap on the bubble: start, or stop and type. */
    fun toggle() {
        when (_phase.value) {
            is Phase.Listening -> stop()
            is Phase.Working -> Unit
            else -> start()
        }
    }

    fun start() {
        if (isBusy) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            show(Phase.Notice("Allow the microphone in Hushtype first.", isError = true, action = NoticeAction.OPEN_APP), 4_000)
            return
        }
        finishLearning()
        val captured = target?.capture()
        if (captured?.isPassword == true) {
            show(Phase.Notice("Hushtype doesn't listen in password fields.", isError = true), 2_500)
            return
        }
        snapshot = captured
        keepMicAlive()

        val settings = app.settings
        val vocabulary = app.vocabulary
        val bias = buildList {
            addAll(vocabulary.terms)
            addAll(vocabulary.snippets.map { it.trigger })
            if (settings.useNearbyText) captured?.context?.nearbyTerms?.let(::addAll)
        }.distinct()
        startedAt = SystemClock.elapsedRealtime()
        phaseReset?.cancel()
        _phase.value = Phase.Listening
        transcriber.start(
            Transcriber.Options(settings.language.ifBlank { null }, bias, settings.preferOffline),
        ) { message ->
            if (_phase.value is Phase.Listening) show(Phase.Notice(message, isError = true), 4_000)
        }
        polisher()?.prewarm()
    }

    /** Stop listening, polish, type. */
    fun stop() {
        if (_phase.value !is Phase.Listening) return
        _phase.value = Phase.Working(polishing = false)
        work = scope.launch {
            val stoppedAt = SystemClock.elapsedRealtime()
            val transcript = transcriber.finish()
            if (transcript.isBlank()) {
                show(Phase.Notice("Didn't catch that. Tap and speak."), 2_000)
                return@launch
            }
            val settings = app.settings
            val snap = snapshot
            val context = snap?.context?.takeIf { settings.useNearbyText }
                ?: snap?.context?.let { DictationContext(it.appName, it.packageName) }
            val pipeline = DictationPipeline(polisher(), app.vocabulary.terms, context, app.vocabulary.snippets)
            val result = pipeline.polish(transcript) { _phase.value = Phase.Working(polishing = true) } ?: run {
                show(Phase.Notice("Didn't catch that. Tap and speak."), 2_000)
                return@launch
            }
            val inserted = target?.insert(snap, result.text) == true
            if (!inserted) target?.copy(result.text.trim())
            val seconds = (SystemClock.elapsedRealtime() - stoppedAt) / 1000.0
            if (settings.keepHistory || !inserted) {
                app.history.add(
                    HistoryEntry(
                        raw = result.raw, text = result.text.trim(), appName = snap?.context?.appName,
                        seconds = seconds, polishedBy = result.polishedBy, note = result.fallbackReason,
                        notInserted = !inserted,
                    ),
                )
            }
            Log.i(TAG, "Dictation ready in ${"%.2f".format(seconds)} s (polish ${result.polishSeconds}, inserted $inserted)")
            when {
                !inserted -> show(Phase.Notice("Copied. No text field was selected, so paste it anywhere."), 3_500)
                result.fallbackReason != null -> show(Phase.Notice(result.fallbackReason), 3_500)
                else -> show(Phase.Done, 900)
            }
            if (inserted && snap != null && settings.learnFromCorrections) watchForCorrections(snap, result.text.trim())
        }
    }

    /** Stops without typing. What was heard is kept in History, so it's never lost. */
    fun cancel() {
        val heard = liveText.value
        when (_phase.value) {
            is Phase.Listening -> transcriber.cancel()
            is Phase.Working -> work?.cancel()
            else -> return
        }
        if (heard.isNotBlank()) {
            app.history.add(HistoryEntry(raw = heard, text = heard, appName = snapshot?.context?.appName, note = "Cancelled", notInserted = true))
            show(Phase.Notice("Cancelled. It's saved in History."), 2_000)
        } else {
            show(Phase.Idle, 0)
        }
    }

    fun openApp() {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun polisher(): Polisher? = when (app.settings.engine) {
        CleanupEngine.OFF -> null
        CleanupEngine.ON_DEVICE -> app.onDevice
        CleanupEngine.CLAUDE -> app.secrets.anthropicKey.takeIf { it.isNotEmpty() }?.let { ClaudePolisher(it) }
    }

    private fun show(phase: Phase, holdMs: Long) {
        phaseReset?.cancel()
        _phase.value = phase
        if (phase != Phase.Idle) {
            phaseReset = scope.launch {
                delay(holdMs)
                if (_phase.value == phase) _phase.value = Phase.Idle
            }
        }
    }

    /** Keeps a microphone foreground service up during and shortly after dictation. */
    private fun keepMicAlive() {
        micRelease?.cancel()
        MicService.start(app)
        micRelease = scope.launch {
            // Wait for this dictation to end, then a little longer for the next one.
            while (isBusy) delay(1_000)
            delay(MIC_LINGER_MS)
            MicService.stop(app)
        }
    }

    // region Learning from corrections

    private var learning: Job? = null
    private var candidate: List<CorrectionLearner.Correction> = emptyList()

    /**
     * Follows the field after typing, so a word the user respells joins the dictionary. Reads it
     * every second; learns once the text is gone (a chat app sent it), when the next dictation
     * starts, or after a minute and a half.
     */
    private fun watchForCorrections(snapshot: FieldSnapshot, inserted: String) {
        learning?.cancel()
        candidate = emptyList()
        learning = scope.launch {
            var last: String? = null
            var failures = 0
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                delay(1_000)
                val text = target?.read(snapshot)
                if (text == null) {
                    if (++failures >= 3) break else continue
                }
                failures = 0
                val window = window(text, snapshot.selectionStart, inserted.length)
                val analysis = CorrectionLearner.analyze(inserted, window)
                if (!analysis.isPresent) break
                // A read mid-word could catch half a fix, so it only counts once the field held still.
                if (window == last) candidate = analysis.corrections
                last = window
            }
            learn()
        }
    }

    private fun finishLearning() {
        if (learning?.isActive == true) {
            learning?.cancel()
            learn()
        }
    }

    private fun learn() {
        val corrections = candidate
        candidate = emptyList()
        learning = null
        if (corrections.isEmpty()) return
        val known = app.vocabulary.terms.map { it.lowercase() }.toSet()
        val learned = corrections.filter { CorrectionLearner.worthLearning(it, known) }.map { it.to }.distinct()
        if (learned.isEmpty()) return
        app.vocabulary.addTerms(learned, learned = true)
        if (_phase.value == Phase.Idle || _phase.value is Phase.Notice) {
            show(Phase.Notice("Learned ${learned.joinToString { "“$it”" }}."), 2_500)
        }
    }

    private fun window(text: String, start: Int, length: Int): String {
        val from = (start - 60).coerceIn(0, text.length)
        val to = (start + length + 120).coerceIn(from, text.length)
        return text.substring(from, to)
    }

    // endregion

    companion object {
        private const val TAG = "Hushtype"
        private const val MIC_LINGER_MS = 120_000L
    }
}
