package io.raventag.app.wallet.subscription

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Pure JSON-RPC line parser for ElectrumX subscription sockets.
 *
 * Routes each incoming line into one of three categories:
 * - [Parsed.Response]: a JSON-RPC response with an integer `id` field.
 * - [Parsed.Notification]: a `blockchain.scripthash.subscribe` server push.
 * - [Parsed.Unknown]: malformed JSON or any other structure.
 *
 * Thread-safe: this object is stateless; [parseLine] has no side effects.
 */
object SubscriptionParser {
    sealed class Parsed {
        data class Response(val id: Int, val result: JsonElement?) : Parsed()
        data class Notification(val scripthash: String, val status: String?) : Parsed()
        data class Unknown(val raw: String) : Parsed()
    }

    fun parseLine(line: String): Parsed {
        if (line.isBlank()) return Parsed.Unknown(line)
        val obj = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: JsonSyntaxException) { return Parsed.Unknown(line) }
          catch (_: IllegalStateException) { return Parsed.Unknown(line) }

        // id present: response
        val idEl = obj.get("id")
        if (idEl != null && !idEl.isJsonNull) {
            val id = try { idEl.asInt } catch (_: Exception) { return Parsed.Unknown(line) }
            val result: JsonElement? = obj.get("result").takeUnless { it == null || it.isJsonNull }
            return Parsed.Response(id = id, result = result)
        }

        // server notification
        val method = obj.get("method")?.takeUnless { it.isJsonNull }?.asString
            ?: return Parsed.Unknown(line)
        if (method == "blockchain.scripthash.subscribe") {
            val params = obj.getAsJsonArray("params") ?: return Parsed.Unknown(line)
            if (params.size() < 1) return Parsed.Unknown(line)
            val sh = params.get(0).takeUnless { it.isJsonNull }?.asString
                ?: return Parsed.Unknown(line)
            val status = if (params.size() >= 2 && !params.get(1).isJsonNull) params.get(1).asString else null
            return Parsed.Notification(scripthash = sh, status = status)
        }
        return Parsed.Unknown(line)
    }
}
