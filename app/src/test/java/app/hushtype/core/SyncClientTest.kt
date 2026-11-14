package app.hushtype.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

/** A tiny stand-in for the sync server: one blob, ETag versions, conditional writes. */
class SyncClientTest {
    private var blob: ByteArray? = null
    private var version = 0
    private var conflictsToInject = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/groups/") { exchange ->
            when (exchange.requestMethod) {
                "GET" -> {
                    val current = blob
                    if (current == null) exchange.sendResponseHeaders(404, -1) else {
                        exchange.responseHeaders.add("ETag", "\"$version\"")
                        exchange.sendResponseHeaders(200, current.size.toLong())
                        exchange.responseBody.use { it.write(current) }
                    }
                }
                "PUT" -> {
                    val body = exchange.requestBody.readBytes()
                    val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                    val ifNone = exchange.requestHeaders.getFirst("If-None-Match")
                    val ok = when {
                        conflictsToInject > 0 -> { conflictsToInject--; false }
                        ifNone == "*" -> blob == null
                        ifMatch != null -> ifMatch == "\"$version\""
                        else -> false
                    }
                    if (ok) {
                        blob = body
                        version++
                        exchange.responseHeaders.add("ETag", "\"$version\"")
                        exchange.sendResponseHeaders(204, -1)
                    } else {
                        exchange.sendResponseHeaders(412, -1)
                    }
                }
            }
            exchange.close()
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val keys = SyncKeys(ByteArray(32) { it.toByte() })

    @After fun stop() = server.stop(0)

    @Test fun joiningAnEmptyGroupFails() {
        try {
            SyncClient(keys, url).sync(VocabularyState(), mustExist = true)
            fail("expected no such group")
        } catch (e: SyncException) {
            assertEquals(SyncException.Kind.NO_SUCH_GROUP, e.kind)
        }
    }

    @Test fun twoDevicesConverge() {
        val mac = VocabularyEdits.addTerms(VocabularyState(), listOf("Kubernetes"), now = 1)
        val phone = VocabularyEdits.addTerms(VocabularyState(), listOf("Priya"), now = 2)
        SyncClient(keys, url).sync(mac)
        val joined = SyncClient(keys, url).sync(phone, mustExist = true)
        assertEquals(setOf("Kubernetes", "Priya"), joined.liveTerms.toSet())
        val macAgain = SyncClient(keys, url).sync(mac)
        assertEquals(setOf("Kubernetes", "Priya"), macAgain.liveTerms.toSet())
        assertEquals(2, version)
    }

    @Test fun retriesAfterAConflict() {
        SyncClient(keys, url).sync(VocabularyEdits.addTerms(VocabularyState(), listOf("a"), now = 1))
        conflictsToInject = 1
        val result = SyncClient(keys, url).sync(VocabularyEdits.addTerms(VocabularyState(), listOf("b"), now = 2))
        assertTrue(result.liveTerms.containsAll(listOf("a", "b")))
        assertEquals(2, version)
    }
}
