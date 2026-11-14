package app.hushtype.core

/** Finds words the user fixed by hand after Hushtype inserted text, so the dictionary can learn them. */
object CorrectionLearner {
    data class Correction(
        /** What Hushtype typed. */
        val from: String,
        /** What the user changed it to. */
        val to: String,
        /** Only the capitalization changed. */
        val caseOnly: Boolean,
        /** The word starts a sentence, where capitalization says nothing about names. */
        val atSentenceStart: Boolean,
    )

    /**
     * Respellings to remember: names and jargon, not everyday word swaps. Android has no quick
     * synchronous spell checker, so a lowercase respelling counts only when [isKnownWord] says the
     * new spelling isn't an ordinary word.
     */
    fun worthLearning(
        correction: Correction,
        known: Set<String>,
        isKnownWord: (String) -> Boolean = { true },
    ): Boolean {
        val word = correction.to
        if (word.length < 2 || word.lowercase() in known) return false
        val capitalized = word.first().isUpperCase()
        if (correction.caseOnly) {
            // "jen" → "Jen" names someone; "the" → "The" at a sentence start doesn't.
            return !correction.atSentenceStart && capitalized
        }
        val mixedCase = word.drop(1).any { it.isUpperCase() } && word.any { it.isLowerCase() }
        if (mixedCase || word.any { it.isDigit() }) return true
        if (capitalized && !correction.atSentenceStart) return true
        // Ordinary words ("their" → "there") are grammar fixes, not vocabulary.
        return !isKnownWord(word)
    }

    data class Analysis(
        val corrections: List<Correction>,
        /** The dictation is still in the field, as typed or with a few words respelled. */
        val isPresent: Boolean,
    )

    fun corrections(inserted: String, edited: String): List<Correction> = analyze(inserted, edited).corrections

    /** Compares what was inserted with the same stretch of the field a little later. */
    fun analyze(inserted: String, edited: String): Analysis {
        val old = words(inserted)
        val new = words(edited)
        if (old.isEmpty() || new.isEmpty() || old.size > 400 || new.size > 600) return Analysis(emptyList(), false)

        val result = ArrayList<Correction>()
        val (hunks, matched) = diff(old.map { it.text }, new.map { it.text })
        for (hunk in hunks) {
            // Only same-length replacements of up to three words: a respelling, not new wording.
            if (hunk.removed.size != hunk.added.size || hunk.removed.size > 3) continue
            hunk.removed.forEachIndexed { offset, index ->
                val from = old[index].text
                val target = new[hunk.added[offset]]
                if (from != target.text && looksLikeRespelling(from, target.text)) {
                    result += Correction(
                        from = from,
                        to = target.text,
                        caseOnly = from.lowercase() == target.text.lowercase(),
                        atSentenceStart = target.atSentenceStart,
                    )
                }
            }
        }
        val isPresent = (matched + result.size) * 2 >= old.size
        return Analysis(result, isPresent)
    }

    /** True when `to` is plausibly the same word spelled differently. */
    fun looksLikeRespelling(from: String, to: String): Boolean {
        val a = from.lowercase()
        val b = to.lowercase()
        if (a == b) return true
        return editDistance(a, b) <= maxOf(2, (maxOf(a.length, b.length) * 0.5).toInt())
    }

    data class Word(val text: String, val atSentenceStart: Boolean)

    private val keep = setOf('-', '_', '@', '\'')

    fun words(text: String): List<Word> {
        val result = ArrayList<Word>()
        var sentenceStart = true
        for (raw in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val word = raw.trim { !it.isLetterOrDigit() && it !in keep }
            if (word.isNotEmpty()) result += Word(word, sentenceStart)
            sentenceStart = raw.last() in ".!?\n"
        }
        return result
    }

    class Hunk(val removed: MutableList<Int> = mutableListOf(), val added: MutableList<Int> = mutableListOf())

    /**
     * Word-level LCS diff of the inserted words [a] against the field's words [b]. Words of [b]
     * before and after the inserted stretch are surrounding text and are left out. Also returns
     * how many words of [a] appear unchanged in [b].
     */
    fun diff(a: List<String>, b: List<String>): Pair<List<Hunk>, Int> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }

        val hunks = ArrayList<Hunk>()
        var current = Hunk()
        var i = 0
        var j = 0
        var matchedAny = false
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) {
                if (!matchedAny) {
                    // Leading run: surrounding text, then possibly a respelled first word.
                    if (current.removed.isNotEmpty()) {
                        hunks += Hunk(current.removed, current.added.takeLast(current.removed.size).toMutableList())
                    }
                } else if (current.removed.isNotEmpty() || current.added.isNotEmpty()) {
                    hunks += current
                }
                current = Hunk()
                matchedAny = true
                i++; j++
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                current.added += j; j++
            } else {
                current.removed += i; i++
            }
        }
        val matched = lcs[0][0]
        if (current.removed.isEmpty()) return hunks to matched
        if (matchedAny) {
            // Trailing run: a respelled last word, then surrounding text.
            hunks += Hunk(current.removed, current.added.take(current.removed.size).toMutableList())
        } else if (n == 1) {
            // A single inserted word with nothing else to anchor on: pick the closest word.
            val best = current.added.minByOrNull { editDistance(a[0].lowercase(), b[it].lowercase()) }
            if (best != null) hunks += Hunk(mutableListOf(0), mutableListOf(best))
        }
        return hunks to matched
    }

    fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[b.length]
    }
}
