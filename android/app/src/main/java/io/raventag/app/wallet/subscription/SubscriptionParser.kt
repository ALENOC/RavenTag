package io.raventag.app.wallet.subscription

import com.google.gson.JsonElement

/**
 * Wave 0 stub. Plan 30-03 replaces this with real implementation.
 */
object SubscriptionParser {

    sealed class Parsed {
        data class Response(val id: Int, val result: JsonElement?) : Parsed()
        data class Notification(val scripthash: String, val status: String?) : Parsed()
        data class Unknown(val raw: String) : Parsed()
    }

    fun parseLine(line: String): Parsed {
        TODO("30-03")
    }
}
