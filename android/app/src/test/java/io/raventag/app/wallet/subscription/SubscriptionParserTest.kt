// Wave 0 tests. Wave 1-3 implementations will replace the Stub objects below with real classes.
// Until then, tests MUST fail. Do not make them pass by weakening assertions.
package io.raventag.app.wallet.subscription

import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {

    @Test
    fun parses_response_with_id_as_Response() {
        val parsed = SubscriptionParser.parseLine("""{"id":42,"result":"abc","jsonrpc":"2.0"}""")
        assertTrue(parsed is SubscriptionParser.Parsed.Response)
        val resp = parsed as SubscriptionParser.Parsed.Response
        assertEquals(42, resp.id)
        assertEquals(JsonPrimitive("abc"), resp.result)
    }

    @Test
    fun parses_scripthash_notification_as_Notification() {
        val parsed = SubscriptionParser.parseLine("""{"jsonrpc":"2.0","method":"blockchain.scripthash.subscribe","params":["a1b2","statusHash"]}""")
        assertTrue(parsed is SubscriptionParser.Parsed.Notification)
        val notif = parsed as SubscriptionParser.Parsed.Notification
        assertEquals("a1b2", notif.scripthash)
        assertEquals("statusHash", notif.status)
    }

    @Test
    fun parses_scripthash_notification_with_null_status() {
        val parsed = SubscriptionParser.parseLine("""{"jsonrpc":"2.0","method":"blockchain.scripthash.subscribe","params":["a1b2",null]}""")
        assertTrue(parsed is SubscriptionParser.Parsed.Notification)
        val notif = parsed as SubscriptionParser.Parsed.Notification
        assertEquals("a1b2", notif.scripthash)
        assertEquals(null, notif.status)
    }

    @Test
    fun parses_response_with_null_result() {
        val parsed = SubscriptionParser.parseLine("""{"id":3,"result":null}""")
        assertTrue(parsed is SubscriptionParser.Parsed.Response)
        val resp = parsed as SubscriptionParser.Parsed.Response
        assertEquals(3, resp.id)
        // result MAY be JsonNull.INSTANCE or null; accept either
        val result = resp.result
        assertTrue(result == null || result is JsonNull)
    }

    @Test
    fun unknown_method_falls_through_to_Unknown() {
        val parsed = SubscriptionParser.parseLine("""{"jsonrpc":"2.0","method":"server.ping"}""")
        assertTrue(parsed is SubscriptionParser.Parsed.Unknown)
    }

    @Test
    fun malformed_json_throws_or_returns_Unknown() {
        val result = runCatching { SubscriptionParser.parseLine("not json") }
        assertTrue(
            result.isFailure || (result.getOrNull() is SubscriptionParser.Parsed.Unknown)
        )
    }
}
