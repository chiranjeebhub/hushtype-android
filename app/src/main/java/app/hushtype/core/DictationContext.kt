package app.hushtype.core

/** How the polished text should sound, picked from the app you're dictating into. */
enum class WritingStyle {
    /** Chat apps: relaxed punctuation, contractions, no sign-offs. */
    CASUAL,
    /** Email and documents: full sentences and proper punctuation. */
    FORMAL,
    /** Code editors and terminals: keep identifiers, symbols and casing exactly as spoken. */
    TECHNICAL,
    /** Anything else. */
    NEUTRAL;

    /** One line for the polish prompt. */
    val guidance: String
        get() = when (this) {
            CASUAL -> "a chat app: keep it conversational and light; no greeting or sign-off unless spoken; a trailing period on a one-line message is optional"
            FORMAL -> "email or a document: use complete sentences and careful punctuation, but keep the speaker's words"
            TECHNICAL -> "a code editor or terminal: keep identifiers, file names, flags and symbols exactly as spoken; add no prose punctuation to commands"
            NEUTRAL -> "a general app: use standard written style"
        }

    companion object {
        /** Picks a style from the Android package name of the app being typed into. */
        fun forPackage(packageName: String?): WritingStyle {
            val id = packageName?.lowercase() ?: return NEUTRAL
            return when {
                casualApps.any { id.startsWith(it) } -> CASUAL
                formalApps.any { id.startsWith(it) } -> FORMAL
                technicalApps.any { id.startsWith(it) } -> TECHNICAL
                else -> NEUTRAL
            }
        }

        val casualApps = listOf(
            "com.whatsapp", "org.telegram", "org.thunderdog.challegram", "com.discord", "com.slack",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "org.thoughtcrime.securesms",
            "com.facebook.orca", "com.instagram.android", "com.snapchat.android", "com.microsoft.teams",
            "us.zoom.videomeetings", "com.twitter.android", "com.reddit.frontpage", "jp.naver.line.android",
            "com.viber.voip", "com.google.android.apps.dynamite",
        )
        val formalApps = listOf(
            "com.google.android.gm", "com.microsoft.office.outlook", "com.readdle.spark", "com.superhuman",
            "ch.protonmail", "com.google.android.apps.docs", "com.microsoft.office.word", "notion.id",
            "com.linear", "com.samsung.android.email", "com.yahoo.mobile.client.android.mail",
            "com.google.android.keep", "com.evernote", "md.obsidian", "com.linkedin.android",
        )
        val technicalApps = listOf(
            "com.termux", "com.github.android", "com.foxdebug.acode", "com.jetbrains", "org.connectbot",
            "com.server.auditor.ssh.client", "dev.warp",
        )
    }
}

/**
 * What Hushtype knows about where the text is going: the app and the text around the cursor.
 * Captured when dictation starts.
 */
class DictationContext(
    val appName: String? = null,
    val packageName: String? = null,
    textBefore: String = "",
    textAfter: String = "",
    /** The currently selected text, if any. */
    val selectedText: String = "",
) {
    /** Text just before the cursor (or selection), most recent last. */
    val textBefore: String = textBefore.takeLast(BEFORE_LIMIT)
    /** Text just after the cursor (or selection). */
    val textAfter: String = textAfter.take(AFTER_LIMIT)

    val style: WritingStyle get() = WritingStyle.forPackage(packageName)

    val hasSelection: Boolean get() = selectedText.isNotBlank()

    /** True when the cursor sits in the middle of a sentence, so the dictation continues it. */
    val continuesSentence: Boolean
        get() {
            val last = textBefore.lastOrNull { !it.isWhitespace() } ?: return false
            return last !in ".!?:;\n…\"”)]}"
        }

    /** Names and jargon visible near the cursor, to help the transcriber spell them. */
    val nearbyTerms: List<String> get() = terms("$textBefore $selectedText $textAfter")

    /**
     * Fits text to the spot it's going into: adds a space after a word and lowercases the first
     * letter when it continues a sentence. The polish prompt handles this too; this covers the
     * fast path that skips polishing.
     */
    fun fit(input: String): String {
        var text = input
        if (text.isEmpty() || hasSelection) return text
        val first = text.first()
        if (continuesSentence && first.isUpperCase()) {
            val firstWord = text.takeWhile { it.isLetter() || it == '\'' }
            val isAcronym = firstWord.length > 1 && firstWord.none { it.isLowerCase() }
            val isPronoun = firstWord == "I" || firstWord.startsWith("I'")
            val isKnownName = nearbyTerms.contains(firstWord)
            if (!isAcronym && !isPronoun && !isKnownName) {
                text = first.lowercaseChar() + text.drop(1)
            }
        }
        // Mid-sentence insertions shouldn't end with the period the transcriber adds.
        val next = textAfter.firstOrNull { !it.isWhitespace() }
        if (next != null && next.isLowerCase() && text.endsWith(".") && !text.endsWith("...")) {
            text = text.dropLast(1)
        }
        val before = textBefore.lastOrNull()
        val head = text.firstOrNull()
        if (before != null && !before.isWhitespace() && before !in "([{\"“'/-" &&
            head != null && !head.isWhitespace() && head !in ",.;:!?)]}"
        ) {
            text = " $text"
        }
        val after = textAfter.firstOrNull()
        val tail = text.lastOrNull()
        if (after != null && !after.isWhitespace() && after !in ",.;:!?)]}" && tail != null && !tail.isWhitespace()) {
            text += " "
        }
        return text
    }

    companion object {
        /** How many characters of surrounding text to keep on each side. */
        const val BEFORE_LIMIT = 600
        const val AFTER_LIMIT = 200

        private val trimSet = "!\"#$%&()*+,/:;<=>?[\\]^`{|}~“”‘’…".toSet()

        /** Picks capitalized words that don't start a sentence, mixed-case words and words with digits. */
        fun terms(text: String, limit: Int = 30): List<String> {
            val seen = HashSet<String>()
            val result = ArrayList<String>()
            var sentenceStart = true
            for (raw in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val word = raw.trim { it in trimSet }.trim('.')
                val startsSentence = sentenceStart
                sentenceStart = raw.lastOrNull()?.let { it in ".!?" } ?: false
                if (word.length !in 3..40 || word.none { it.isLetter() }) continue
                val innerCaps = word.drop(1).any { it.isUpperCase() } && word.any { it.isLowerCase() }
                val hasDigit = word.any { it.isDigit() }
                val capitalized = word.first().isUpperCase() && !startsSentence && word.drop(1).any { it.isLowerCase() }
                if (!innerCaps && !hasDigit && !capitalized) continue
                if (word in setOf("I'm", "I've", "I'll", "I'd")) continue
                if (seen.add(word.lowercase())) {
                    result.add(word)
                    if (result.size == limit) break
                }
            }
            return result
        }
    }
}
