package io.raventag.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * Suspend extension function for OkHttp Call.
 * Converts blocking execute() to suspend using withContext(Dispatchers.IO).
 * This approach properly switches dispatchers to prevent UI thread blocking.
 * The call is executed on the IO dispatcher, allowing non-blocking behavior from calling contexts.
 */
suspend fun Call.executeSuspend(): okhttp3.Response = withContext(Dispatchers.IO) {
    execute()
}

/**
 * Convenience suspend function that builds a Request and executes it on the shared client.
 */
suspend fun okhttp3.OkHttpClient.getWithTimeout(url: String): okhttp3.Response = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).get().build()
    newCall(request).execute()
}
