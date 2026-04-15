package io.raventag.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * Suspend extension function for OkHttp Call.
 * Converts blocking execute() to suspend using withContext(Dispatchers.IO).
 * This approach properly switches dispatchers to prevent UI thread blocking.
 * The call is executed on the IO dispatcher, allowing non-blocking behavior from calling contexts.
 */
suspend fun Call.executeSuspend(): okhttp3.Response = withContext(Dispatchers.IO) {
    execute()
}
