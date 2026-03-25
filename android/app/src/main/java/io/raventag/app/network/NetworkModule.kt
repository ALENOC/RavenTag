package io.raventag.app.network

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * NetworkModule provides a shared, thread-safe singleton instance of [OkHttpClient]
 * for the entire application.
 *
 * Sharing a single [OkHttpClient] instance is a performance best-practice in Android
 * for several reasons:
 *   1. Connection Pooling: allows reusing established TCP/TLS connections across
 *      different features (Wallet, RPC, IPFS), reducing latency and battery drain.
 *   2. Shared Cache: provides a single disk cache for all network resources,
 *      preventing lock contention when multiple components try to access the same
 *      cache directory.
 *   3. Resource Management: prevents thread and memory leaks associated with creating
 *      many client instances.
 */
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

object NetworkModule {
    private var client: OkHttpClient? = null
    private var imageLoader: ImageLoader? = null
    private const val CACHE_SIZE = 50L * 1024 * 1024 // 50MB for metadata/RPC
    private const val IMAGE_CACHE_SIZE = 100L * 1024 * 1024 // 100MB for processed images

    fun getHttpClient(context: Context): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: buildClient(context).also { client = it }
        }
    }

    /**
     * Returns a shared Coil ImageLoader that uses the shared OkHttpClient and
     * a dedicated disk cache for processed Bitmaps. This makes loading near-instant on restart.
     */
    fun getImageLoader(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: ImageLoader.Builder(context)
                .okHttpClient { getHttpClient(context) }
                .memoryCache {
                    MemoryCache.Builder(context)
                        .maxSizePercent(0.25) // 25% of app RAM
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache"))
                        .maxSizeBytes(IMAGE_CACHE_SIZE)
                        .build()
                }
                .crossfade(true)
                .build().also { imageLoader = it }
        }
    }

    private fun buildClient(context: Context): OkHttpClient {
        val cacheDir = File(context.cacheDir, "raventag_network_cache")
        val cache = Cache(cacheDir, CACHE_SIZE)

        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Use a browser-like User-Agent: public IPFS gateways (ipfs.io, cloudflare)
            // block requests with the default okhttp/* user agent.
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .build()
                )
            }
            // Follow redirects for IPFS gateways
            .followRedirects(true)
            .followSslRedirects(true)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 50
                maxRequestsPerHost = 20
            })
            .build()
    }
}
