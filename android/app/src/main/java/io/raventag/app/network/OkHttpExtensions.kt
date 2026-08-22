package io.raventag.app.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspend extension function for OkHttp Call.
 * Converts OkHttp's callback API into a cancellable suspend call. Cancellation
 * actively cancels the underlying socket, so UI validation timeouts cannot leave
 * Pinata/Kubo checks stuck in CHECKING until OkHttp's longer upload timeout expires.
 */
suspend fun Call.executeSuspend(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}

/**
 * Convenience suspend function that builds a Request and executes it on the shared client.
 */
suspend fun okhttp3.OkHttpClient.getWithTimeout(url: String): Response {
    val request = Request.Builder().url(url).get().build()
    return newCall(request).executeSuspend()
}
