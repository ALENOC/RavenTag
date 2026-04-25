package io.raventag.app.utils

import android.util.Log
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException

/**
 * Retry utility with exponential backoff for transient network failures.
 *
 * Implements D-02 and D-06 from CONTEXT.md:
 * - 5 retries with exponential backoff (base 1s, multiplier 2x)
 * - Transient errors trigger retries (timeout, connection, network)
 * - Non-transient errors fail immediately
 *
 * Usage:
 * ```kotlin
 * val result = retryWithBackoff(maxAttempts = 5) {
 *     networkCall()
 * }
 * ```
 */
object RetryUtils {
    private const val TAG = "RetryUtils"

    /**
     * Execute [block] with exponential backoff retry on transient failures.
     *
     * @param maxAttempts Maximum number of attempts (default 5 per D-02, D-06)
     * @param initialDelayMs Base delay in milliseconds (default 1000ms per D-02, D-06)
     * @param backoffMultiplier Delay multiplier (default 2.0 for exponential backoff)
     * @param block The suspend function to execute
     * @return The result of [block] on success
     * @throws The last exception if all attempts fail or error is non-transient
     */
    suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 5,
        initialDelayMs: Long = 1000L,
        backoffMultiplier: Double = 2.0,
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var currentDelay = initialDelayMs

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                val isTransient = isTransientError(e)

                if (attempt < maxAttempts - 1 && isTransient) {
                    Log.w(TAG, "Attempt ${attempt + 1}/$maxAttempts failed, retrying in ${currentDelay}ms: ${e.message}")
                    delay(currentDelay)
                    currentDelay = (currentDelay * backoffMultiplier).toLong()
                } else {
                    // Last attempt or non-transient error: throw immediately
                    val reason = if (!isTransient) "non-transient error" else "all retries exhausted"
                    Log.e(TAG, "Failed after $reason: ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
        }

        // Should not reach here, but handle edge case
        throw lastException ?: IllegalStateException("Retry logic failed with no exception")
    }

    /**
     * Determine if an exception represents a transient (retryable) error.
     *
     * Transient errors:
     * - SocketTimeoutException: Network timeout
     * - UnknownHostException: DNS resolution failure
     * - IOException with "timeout", "connection", "network", "temporary" in message
     *
     * Non-transient errors:
     * - Validation errors (insufficient funds, invalid address)
     * - Logic errors (wrong asset, unauthorized)
     * - Auth errors (invalid credentials)
     *
     * @param e The exception to evaluate
     * @return true if the error is transient and should trigger retry
     */
    fun isTransientError(e: Exception): Boolean {
        when (e) {
            is SocketTimeoutException -> return true
            is UnknownHostException -> return true
            is IOException -> {
                val message = e.message?.lowercase() ?: return false
                return message.contains("timeout") ||
                       message.contains("connection") ||
                       message.contains("network") ||
                       message.contains("temporary")
            }
            else -> return false
        }
    }
}
