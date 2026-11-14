package app.hushtype.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetTest {
    private val snippets = listOf(
        Snippet(trigger = "my calendar link", expansion = "cal.com/me/30"),
        Snippet(trigger = "my address", expansion = "1 Main St"),
        Snippet(trigger = "my work address", expansion = "2 Office Rd"),
    )

    @Test fun wholeMatchIgnoresCaseAndLeadWords() {
        assertEquals("cal.com/me/30", SnippetExpander.wholeMatch("Insert my calendar link.", snippets))
        assertNull(SnippetExpander.wholeMatch("send my calendar link", snippets))
    }

    @Test fun expandsInsideSentencesLongestFirst() {
        assertEquals("Ship it to 2 Office Rd, please", SnippetExpander.expand("Ship it to my work address, please", snippets))
        assertEquals("Book via cal.com/me/30.", SnippetExpander.expand("Book via my calendar link.", snippets))
        assertEquals("myaddressbook", SnippetExpander.expand("myaddressbook", snippets))
    }
}

class ContextTest {
    @Test fun continuesSentenceInLowercaseWithSpace() {
        val context = DictationContext(textBefore = "I think we should", textAfter = "")
        assertEquals(" meet on Friday.", context.fit("Meet on Friday."))
    }

    @Test fun keepsNamesAndPronounCapitalized() {
        val context = DictationContext(textBefore = "Ask Priya whether", textAfter = "")
        assertEquals(" I can join", context.fit("I can join"))
        assertEquals(" Priya agrees", context.fit("Priya agrees"))
    }

    @Test fun dropsPeriodBeforeLowercaseContinuation() {
        val context = DictationContext(textBefore = "We'll ", textAfter = "and then leave")
        assertEquals("go tomorrow ", context.fit("Go tomorrow."))
    }

    @Test fun startOfFieldIsUntouched() {
        assertEquals("Hello there.", DictationContext().fit("Hello there."))
    }

    @Test fun nearbyTermsFindsNamesAndJargon() {
        val terms = DictationContext.terms("The deploy to Kubernetes broke. Ask Priya about gRPC and k8s today.")
        assertTrue("Kubernetes" in terms)
        assertTrue("Priya" in terms)
        assertTrue("gRPC" in terms)
        assertTrue("k8s" in terms)
        assertFalse("The" in terms)
        assertFalse("Ask" in terms)
    }

    @Test fun styleFromPackage() {
        assertEquals(WritingStyle.CASUAL, WritingStyle.forPackage("com.whatsapp"))
        assertEquals(WritingStyle.FORMAL, WritingStyle.forPackage("com.google.android.gm"))
        assertEquals(WritingStyle.TECHNICAL, WritingStyle.forPackage("com.termux"))
        assertEquals(WritingStyle.NEUTRAL, WritingStyle.forPackage("com.example"))
    }
}

class PromptsTest {
    @Test fun skipsPolishForShortCleanPhrases() {
        assertFalse(Prompts.needsPolish("See you at six"))
        assertTrue(Prompts.needsPolish("um see you at six"))
        assertTrue(Prompts.needsPolish("see you at at six"))
        assertTrue(Prompts.needsPolish("one two three four five six seven eight nine"))
    }

    @Test fun cleanupContentHasBlocks() {
        val content = Prompts.cleanupContent("hello", listOf("Hushtype"), DictationContext(appName = "WhatsApp", packageName = "com.whatsapp", textBefore = "Hi"), listOf("my link"))
        assertTrue(content.contains("<app>\nWhatsApp — com.whatsapp\nStyle: a chat app"))
        assertTrue(content.contains("<before_cursor>\nHi\n</before_cursor>"))
        assertTrue(content.contains("<dictionary>\nHushtype\n</dictionary>"))
        assertTrue(content.contains("<keep_verbatim>\nmy link\n</keep_verbatim>"))
        assertTrue(content.endsWith("<transcript>\nhello\n</transcript>"))
    }

    @Test fun onDevicePromptLeavesOutAbsentBlocks() {
        assertFalse(Prompts.onDevice(hasCursorText = false, hasKeepVerbatim = false).contains("before_cursor"))
        assertTrue(Prompts.onDevice(hasCursorText = true, hasKeepVerbatim = true).contains("keep_verbatim"))
    }

    @Test fun punctuatesSmallModelReplies() {
        assertEquals("See you soon.", OnDeviceText.punctuate("see you soon"))
        assertEquals("Can you call me?", OnDeviceText.punctuate("can you call me"))
        assertEquals("It works.", OnDeviceText.punctuate("It works?", heard = "it works"))
        assertEquals("Hello", OnDeviceText.unwrap("<transcript>Hello</transcript>"))
    }

    @Test fun vocabularyParsing() {
        assertEquals(listOf("Hushtype", "Priya", "gRPC"), Prompts.vocabulary("Hushtype, Priya\n gRPC \n"))
    }
}

class CorrectionLearnerTest {
    @Test fun findsRespelledName() {
        val fixes = CorrectionLearner.corrections("Ask pria about the deploy.", "Ask Priya about the deploy.")
        assertEquals(1, fixes.size)
        assertEquals("pria", fixes[0].from)
        assertEquals("Priya", fixes[0].to)
    }

    @Test fun ignoresRewrites() {
        assertTrue(CorrectionLearner.corrections("Let's meet tomorrow", "Let's postpone until next week").isEmpty())
    }

    @Test fun ignoresSurroundingText() {
        val fixes = CorrectionLearner.corrections("talk to cuber nettis", "Earlier text. talk to Kubernetes team later")
        assertTrue(fixes.none { it.to == "Earlier" || it.to == "later" })
    }

    @Test fun goneWhenCleared() {
        assertFalse(CorrectionLearner.analyze("Send the report to Priya today", "").isPresent)
        assertFalse(CorrectionLearner.analyze("Send the report to Priya today", "something else entirely").isPresent)
    }

    @Test fun worthLearning() {
        val name = CorrectionLearner.Correction("pria", "Priya", caseOnly = false, atSentenceStart = false)
        assertTrue(CorrectionLearner.worthLearning(name, emptySet()))
        assertFalse(CorrectionLearner.worthLearning(name, setOf("priya")))
        val grammar = CorrectionLearner.Correction("their", "there", caseOnly = false, atSentenceStart = false)
        assertFalse(CorrectionLearner.worthLearning(grammar, emptySet()))
        val sentence = CorrectionLearner.Correction("the", "The", caseOnly = true, atSentenceStart = true)
        assertFalse(CorrectionLearner.worthLearning(sentence, emptySet()))
    }
}

class VocabularyTest {
    @Test fun addRemoveAndReAdd() {
        var s = VocabularyEdits.addTerms(VocabularyState(), listOf("Priya", "gRPC", "priya"), now = 1)
        assertEquals(listOf("priya", "gRPC").map { it.lowercase() }.toSet(), s.liveTerms.map { it.lowercase() }.toSet())
        s = VocabularyEdits.removeTerm(s, "GRPC", now = 2)
        assertEquals(1, s.liveTerms.size)
        assertTrue(s.terms.any { it.deleted && it.key == "grpc" })
        s = VocabularyEdits.addTerms(s, listOf("gRPC"), now = 3)
        assertEquals(2, s.liveTerms.size)
    }

    @Test fun mergeKeepsNewestEditAndRemovals() {
        val base = VocabularyEdits.addTerms(VocabularyState(), listOf("Priya", "Kubernetes"), now = 1)
        val phone = VocabularyEdits.removeTerm(base, "Kubernetes", now = 5)
        val mac = VocabularyEdits.addTerms(base, listOf("Hushtype"), now = 3)
        val merged = VocabularyEdits.merge(phone, mac)
        assertEquals(setOf("Priya", "Hushtype"), merged.liveTerms.toSet())
        assertEquals(merged.liveTerms.toSet(), VocabularyEdits.merge(mac, phone).liveTerms.toSet())
    }

    @Test fun snippetEditsMerge() {
        val s1 = VocabularyEdits.upsertSnippet(VocabularyState(), Snippet("a", "sig", "Best, C"), now = 1)
        val newer = VocabularyEdits.upsertSnippet(s1, Snippet("a", "sig", "Cheers, C"), now = 4)
        val removed = VocabularyEdits.removeSnippet(s1, "a", now = 3)
        assertEquals("Cheers, C", VocabularyEdits.merge(removed, newer).liveSnippets.single().expansion)
        assertTrue(VocabularyEdits.merge(newer, VocabularyEdits.removeSnippet(newer, "a", now = 9)).liveSnippets.isEmpty())
    }
}
