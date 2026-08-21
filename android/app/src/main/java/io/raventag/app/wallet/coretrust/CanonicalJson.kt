package io.raventag.app.wallet.coretrust

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

/**
 * Deterministic JSON serializer byte-compatible with the reference policy
 * signer's canonical form:
 *
 * ```python
 * json.dumps(document, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
 * ```
 *
 * The Ed25519 policy signature is computed over exactly those bytes prefixed
 * with the domain separator, so this serializer must reproduce Python's
 * choices: recursively sorted object keys, no whitespace, ASCII-only output
 * with \uXXXX escapes (lowercase hex, surrogate pairs above the BMP), the
 * short escapes \b \t \n \f \r, and plain integer literals.
 *
 * Anything that cannot be serialized in that exact form (non-integer number
 * literals) throws [CanonicalJsonException] — the caller fails closed, the
 * signature simply does not verify.
 */
object CanonicalJson {

    class CanonicalJsonException(message: String) : Exception(message)

    private val INT_LITERAL = Regex("-?\\d+")

    fun serialize(element: JsonElement): String {
        val sb = StringBuilder(256)
        write(element, sb)
        return sb.toString()
    }

    private fun write(element: JsonElement, sb: StringBuilder) {
        when {
            element.isJsonNull -> sb.append("null")
            element.isJsonPrimitive -> writePrimitive(element.asJsonPrimitive, sb)
            element.isJsonArray -> {
                sb.append('[')
                var first = true
                for (item in element.asJsonArray) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            element.isJsonObject -> {
                sb.append('{')
                var first = true
                // Python sorts by Unicode code point; every policy key is ASCII,
                // where code-point order and UTF-16 String order coincide.
                for (key in element.asJsonObject.keySet().sorted()) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(key, sb)
                    sb.append(':')
                    write(element.asJsonObject.get(key), sb)
                }
                sb.append('}')
            }
            else -> throw CanonicalJsonException("unknown JsonElement type")
        }
    }

    private fun writePrimitive(primitive: JsonPrimitive, sb: StringBuilder) {
        if (primitive.isBoolean) {
            sb.append(primitive.asBoolean)
            return
        }
        if (primitive.isNumber) {
            val literal = primitive.asNumber.toString()
            // The signed policy body only ever contains integers; a float or
            // exponent literal cannot be reproduced in Python's canonical form
            // without float-repr semantics, so refuse it (fails the signature).
            if (!INT_LITERAL.matches(literal)) {
                throw CanonicalJsonException("non-integer number literal: $literal")
            }
            sb.append(literal)
            return
        }
        writeString(primitive.asString, sb)
    }

    private fun writeString(value: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' || c > '~' -> {
                    // Python ensure_ascii escapes everything outside 0x20..0x7E.
                    // Supplementary characters are emitted as their surrogate pair.
                    if (Character.isHighSurrogate(c) &&
                        i + 1 < value.length &&
                        Character.isLowSurrogate(value[i + 1])
                    ) {
                        appendUnicodeEscape(c, sb)
                        appendUnicodeEscape(value[i + 1], sb)
                        i++
                    } else {
                        appendUnicodeEscape(c, sb)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun appendUnicodeEscape(c: Char, sb: StringBuilder) {
        sb.append("\\u")
        sb.append(String.format("%04x", c.code))
    }
}
