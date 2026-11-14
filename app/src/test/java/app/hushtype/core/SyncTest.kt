package app.hushtype.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Vectors from hushtype's sync-server/reference.mjs (secret = bytes 0…31). */
class SyncTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val keys = SyncKeys(secret)
    private val plaintext = """{"terms":[{"text":"Kubernetes","createdAt":1763100000000,"updatedAt":1763100000000,"deleted":false,"learned":true}],"snippets":[{"id":"6f1c2b8e-1d5a-4c1e-9a77-1b2c3d4e5f60","trigger":"my link","expansion":"https://cal.com/x","createdAt":1763100000000,"updatedAt":1763100000001,"deleted":false}]}"""
    private val blob = Base64.getDecoder().decode(
        "AaChoqOkpaanqKmqq+w8k+U+nxPol7F8T1tCZASQ3/wy82AKchj2R7iDTY3TQIs69xlgRBb1L4rvJ9CKbwB2oHzinE3YmwlvqkSy4Br2oO6oUcsrrfBFr4jElm2vJ+0h+N48uLG4htgtB4jJ+bO+d2k7FNX8HiQnMoCsb4g4ikGQ5MZ6kEwYrIsRrHUWko0gnGbMoV7RK/YwS7/FBd/lMCINf7y4FK623S9TPlSEY0BvqR0NmnKx76YEuUWi1fB4ZNsBC6HPqsWebIvCnldejMu0i92FFo8Fq2GF5c95NXZ+C73lyK3166w0ObXGZZvNXxmnn7/nY2SxMF8RXzo4JGojx+gebk227qUz0jDx56mPtqcdNtzp6s7jXkzpG+DTHTA20TAkLmmeTipmAMH+VRILGTozFnPhkEk2EobL1nqEtpif",
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun derivesIdsAndCode() {
        assertEquals("8187ad2336c78c03a3802eb930117608", keys.groupId)
        assertEquals("5ZN7bna256_f6Ck-1W9euZAu54gAfn1DpAFt9HDKQzU", keys.authToken)
        assertEquals("HT1-000G-40R4-0M30-E209-185G-R38E-1W81-24GK-2GAH-C5RR-34D1-P70X-3RFG", keys.pairingCode)
    }

    @Test fun readsCodesLeniently() {
        assertArrayEquals(secret, PairingCode.decode("ht1 oooG 40R4 0M30 E209 185G R38E 1W81 24GK 2GAH C5RR 34D1 P70X 3RFG"))
        assertNull(PairingCode.decode("HT2-000G"))
        assertNull(PairingCode.decode("HT1-000G-40R4"))
    }

    @Test fun opensReferenceBlob() {
        assertEquals(plaintext, String(keys.open(blob)))
    }

    @Test fun sealsLikeReference() {
        val nonce = ByteArray(12) { (0xa0 + it).toByte() }
        assertArrayEquals(blob, keys.seal(plaintext.toByteArray(), nonce))
    }

    @Test fun jsonMatchesWireFormat() {
        val state = json.decodeFromString(VocabularyState.serializer(), plaintext)
        assertEquals(plaintext, json.encodeToString(VocabularyState.serializer(), state))
        assertEquals(listOf("Kubernetes"), state.liveTerms)
    }

    @Test fun editsAlwaysMoveTimeForward() {
        var s = VocabularyEdits.addTerms(VocabularyState(), listOf("Priya"), now = 100)
        s = VocabularyEdits.removeTerm(s, "Priya", now = 50)
        assertTrue(s.terms.single().updatedAt > 100)
    }

    @Test fun compactDropsOldTombstones() {
        var s = VocabularyEdits.addTerms(VocabularyState(), listOf("a", "b"), now = 1)
        s = VocabularyEdits.removeTerm(s, "a", now = 2)
        assertEquals(1, VocabularyEdits.compact(s, cutoff = 10).terms.size)
    }
}
