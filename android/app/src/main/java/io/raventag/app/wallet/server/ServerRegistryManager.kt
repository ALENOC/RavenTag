package io.raventag.app.wallet.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.raventag.app.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime source of ElectrumX query endpoints and signed corroborator metadata. */
object ServerRegistryManager {
    private const val TAG = "ServerRegistry"
    private const val PREFS = "electrum_server_registry"
    private const val KEY_CACHED_DOCUMENT = "cached_signed_document"
    private const val KEY_HIGH_WATER = "registry_high_water_version"
    private const val KEY_HIGH_WATER_DIGEST = "registry_high_water_digest"
    private const val KEY_USER_SERVERS = "user_servers"
    private const val MAX_DOCUMENT_BYTES = 128L * 1024L
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val RETRY_INTERVAL_MS = 5L * 60L * 1000L
    private const val BASELINE_REGISTRY_VERSION = 2L
    private const val BASELINE_REGISTRY_DIGEST =
        "a53384d73b94da18ee091eaa2044d527f2f200d7dbafef51f2ae2049a8ebabc8"

    private val initLock = Any()
    private val refreshStarted = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null
    @Volatile private var signedRegistry: ServerRegistry.VerifiedRegistry? = null
    @Volatile private var userServers: List<ServerRegistry.Server> = emptyList()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val applicationContext = context.applicationContext
            val loadedUsers = loadUserServers(applicationContext)
            val loadedRegistry = loadCachedVerified(applicationContext)
            userServers = loadedUsers
            signedRegistry = loadedRegistry
            appContext = applicationContext
            initialized = true
        }
        if (refreshStarted.compareAndSet(false, true)) {
            scope.launch {
                while (true) {
                    val updated = refresh()
                    delay(if (updated) REFRESH_INTERVAL_MS else RETRY_INTERVAL_MS)
                }
            }
        }
    }

    /** Signed registry when valid, otherwise the compiled AppConfig baseline, plus user endpoints. */
    /** Signed registry when valid, otherwise the compiled AppConfig baseline, plus user endpoints. */
    fun servers(): List<ServerRegistry.Server> {
        val baseline = AppConfig.ELECTRUM_SERVERS.map { (host, port) ->
            ServerRegistry.Server(host.lowercase(), port, null, ServerRegistry.Source.BASELINE)
        }
        val authoritative = activeSignedRegistry()?.servers ?: baseline
        val seen = authoritative.mapTo(mutableSetOf()) { it.host.lowercase() to it.port }
        val all = authoritative + userServers.filter { seen.add(it.host.lowercase() to it.port) }
        val primaryHost = AppConfig.ELECTRUM_SERVERS.first().first.lowercase()
        val primaryPort = AppConfig.ELECTRUM_SERVERS.first().second
        val primary = all.firstOrNull { it.host == primaryHost && it.port == primaryPort }
        return if (primary != null) {
            listOf(primary) + all.filterNot { it.host == primaryHost && it.port == primaryPort }
        } else {
            all
        }
    }

    fun queryServers(): List<Pair<String, Int>> = servers().map { it.host to it.port }

    /** Only a verified signed operator assignment can participate in Core corroboration. */
    fun operatorGroup(host: String): String? = activeSignedRegistry()?.servers
        ?.firstOrNull { it.host.equals(host, ignoreCase = true) }
        ?.takeIf { it.canCorroborate }
        ?.operatorGroup

    fun corroborationServers(): List<Pair<String, Int>> = activeSignedRegistry()?.servers.orEmpty()
        .filter { it.canCorroborate }
        .map { it.host to it.port }

    fun userConfiguredServers(): List<ServerRegistry.Server> = userServers

    @Synchronized
    fun addUserServer(context: Context, host: String, port: Int): Boolean {
        init(context)
        val normalized = host.trim().lowercase()
        if (!isValidUserHost(normalized) || port !in 1..65535) return false
        val entry = ServerRegistry.Server(normalized, port, null, ServerRegistry.Source.USER)
        if (userServers.any { it.host == normalized && it.port == port }) return true
        val updated = userServers + entry
        persistUserServers(context.applicationContext, updated)
        userServers = updated
        return true
    }

    @Synchronized
    fun removeUserServer(context: Context, host: String, port: Int) {
        init(context)
        val updated = userServers.filterNot {
            it.host.equals(host, ignoreCase = true) && it.port == port
        }
        persistUserServers(context.applicationContext, updated)
        userServers = updated
    }

    fun refresh(): Boolean {
        val context = appContext ?: return false
        if (!refreshing.compareAndSet(false, true)) return false
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Expiry remains security state while the process stays alive.
            signedRegistry = loadCachedVerified(context)
            val storedVersion = prefs.getLong(KEY_HIGH_WATER, 0L)
            val highWater = maxOf(BASELINE_REGISTRY_VERSION, storedVersion)
            val digest = if (storedVersion > BASELINE_REGISTRY_VERSION) {
                prefs.getString(KEY_HIGH_WATER_DIGEST, null) ?: ""
            } else {
                BASELINE_REGISTRY_DIGEST
            }
            val fetched = fetchDocument() ?: return false
            val verified = try {
                ServerRegistry.verify(fetched, minimumRegistryVersion = highWater,
                    acceptedDigestAtMinimum = digest)
            } catch (e: ServerRegistry.RegistryException) {
                Log.w(TAG, "Fetched registry rejected: ${e.message}")
                return false
            }
            if (!prefs.edit()
                    .putString(KEY_CACHED_DOCUMENT, fetched)
                    .putLong(KEY_HIGH_WATER, verified.registryVersion)
                    .putString(KEY_HIGH_WATER_DIGEST, verified.canonicalDigest)
                    .commit()
            ) {
                Log.w(TAG, "Verified registry could not be persisted")
                return false
            }
            signedRegistry = verified
            return true
        } finally {
            refreshing.set(false)
        }
    }

    private fun loadCachedVerified(context: Context): ServerRegistry.VerifiedRegistry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_CACHED_DOCUMENT, null) ?: return null
        return try {
            val storedVersion = prefs.getLong(KEY_HIGH_WATER, 0L)
            ServerRegistry.verify(
                cached,
                minimumRegistryVersion = maxOf(BASELINE_REGISTRY_VERSION, storedVersion),
                acceptedDigestAtMinimum = if (storedVersion > BASELINE_REGISTRY_VERSION) {
                    prefs.getString(KEY_HIGH_WATER_DIGEST, null) ?: ""
                } else {
                    BASELINE_REGISTRY_DIGEST
                }
            )
        } catch (e: ServerRegistry.RegistryException) {
            Log.w(TAG, "Cached registry rejected: ${e.message}")
            null
        }
    }

    private fun fetchDocument(): String? = try {
        httpClient.newCall(Request.Builder().url(ServerRegistry.REGISTRY_URL).get().build())
            .execute().use responseUse@ { response ->
                if (!response.isSuccessful) return@responseUse null
                val body = response.body ?: return@responseUse null
                if (body.contentLength() > MAX_DOCUMENT_BYTES) return@responseUse null
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size().toLong() + read > MAX_DOCUMENT_BYTES) {
                            return@responseUse null
                        }
                        output.write(buffer, 0, read)
                    }
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
    } catch (_: Exception) {
        null
    }

    private fun loadUserServers(context: Context): List<ServerRegistry.Server> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USER_SERVERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<UserServer>>() {}.type
            Gson().fromJson<List<UserServer>>(json, type).orEmpty().mapNotNull {
                if (isValidUserHost(it.host) && it.port in 1..65535) {
                    ServerRegistry.Server(
                        it.host.lowercase(), it.port, null, ServerRegistry.Source.USER
                    )
                } else null
            }.distinctBy { it.host to it.port }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistUserServers(context: Context, entries: List<ServerRegistry.Server>) {
        val json = Gson().toJson(entries.map { UserServer(it.host, it.port) })
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_USER_SERVERS, json).commit())
    }

    private fun isValidUserHost(host: String): Boolean = host.isNotEmpty() &&
        host.length <= 255 &&
        host.none { it.isWhitespace() || it.code < 32 || it.code == 127 } &&
        "://" !in host && ':' !in host && '/' !in host && '\\' !in host

    private fun activeSignedRegistry(): ServerRegistry.VerifiedRegistry? =
        signedRegistry?.takeIf { System.currentTimeMillis() <= it.expiresAtMs }

    private data class UserServer(val host: String, val port: Int)
}
