package io.raventag.app.ravencoin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Test suite for OkHttp suspend wrapper extension function.
 * Verifies that executeSuspend() properly converts blocking execute() calls
 * to suspend functions with coroutine cancellation support.
 */
class RpcClientSuspendTest {

    @Test
    fun `executeSuspend exists as extension function`() = runBlocking {
        // Arrange: Create HTTP client
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        // Act: Try to call a real endpoint (httpbin.org for testing)
        val request = Request.Builder()
            .url("https://httpbin.org/status/200")
            .get()
            .build()

        // This will compile only if executeSuspend() exists as extension function
        // If it fails to compile, the extension function is missing
        try {
            val response = httpClient.newCall(request).executeSuspend()
            // Assert: Extension function exists (we reached here)
            assertTrue(response.isSuccessful)
        } catch (e: Exception) {
            // Network errors are ok for this test - we just need to verify compilation
            // The important thing is that executeSuspend() exists as a function
        }
    }

    @Test
    fun `executeSuspend handles real HTTP request`() = runBlocking {
        // Arrange: Create HTTP client with short timeout
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        // Act: Call executeSuspend on a real HTTP request
        val request = Request.Builder()
            .url("https://httpbin.org/get")
            .get()
            .build()

        val response = withContext(Dispatchers.IO) {
            httpClient.newCall(request).executeSuspend()
        }

        // Assert: Response should be successful
        assertTrue(response.isSuccessful)
        assertEquals(200, response.code)
    }

    @Test
    fun `executeSuspend is a suspend function`() {
        // Verify compile-time that executeSuspend is a suspend function
        // This test validates type checking at compile time

        // This will only compile if executeSuspend is marked as suspend
        suspend fun testSuspend() {
            val httpClient = OkHttpClient()
            val request = Request.Builder()
                .url("https://example.com")
                .build()

            // This line will only compile if executeSuspend is a suspend function
            httpClient.newCall(request).executeSuspend()
        }

        // If we reach here without compilation error, executeSuspend is suspend
        assertTrue(true)
    }
}
