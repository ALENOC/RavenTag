package io.raventag.app.security

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite DAO for persistent TOFU certificate fingerprints.
 *
 * Stores ElectrumX server certificate fingerprints in a local SQLite database,
 * allowing TOFU (Trust On First Use) certificate pinning to survive app restarts.
 * This closes the security gap where in-memory-only TOFU caches would accept
 * different certificates after each restart, creating a window for MITM attacks.
 *
 * Database: electrum_certificates.db
 * Table:   tofu_fingerprints
 * Schema:  host (TEXT PRIMARY KEY), fingerprint (TEXT NOT NULL), pinned_at (INTEGER NOT NULL)
 */
object TofuFingerprintDao {
    private const val CERT_DB_NAME = "electrum_certificates.db"
    private const val CERT_TABLE = "tofu_fingerprints"
    private const val DB_VERSION = 1

    /**
     * SQLite helper class for the certificate fingerprint database.
     */
    private class CertDbHelper(context: Context) : SQLiteOpenHelper(context, CERT_DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $CERT_TABLE (
                    host TEXT PRIMARY KEY,
                    fingerprint TEXT NOT NULL,
                    pinned_at INTEGER NOT NULL
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No migration needed for version 1
        }
    }

    private var dbHelper: CertDbHelper? = null
    private var db: SQLiteDatabase? = null
    private var initialized = false
    private val initLock = Any()

    /**
     * Initializes the DAO with the application context.
     * This must be called before any other methods.
     * Thread-safe and idempotent.
     *
     * @param context Application context (use applicationContext for safety)
     */
    fun init(context: Context) {
        synchronized(initLock) {
            if (initialized) return
            dbHelper = CertDbHelper(context.applicationContext)
            db = dbHelper!!.writableDatabase
            initialized = true
        }
    }

    /**
     * Retrieves the stored fingerprint for a given ElectrumX host.
     *
     * @param host ElectrumX server hostname
     * @return Fingerprint hex string if previously pinned, null otherwise
     */
    fun getFingerprint(host: String): String? {
        db ?: return null
        val cursor = db!!.query(
            CERT_TABLE,
            arrayOf("fingerprint"),
            "host = ?",
            arrayOf(host),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /**
     * Pins a certificate fingerprint for an ElectrumX host.
     * If a fingerprint already exists for the host, it is replaced.
     *
     * @param host ElectrumX server hostname
     * @param fingerprint SHA-256 fingerprint hex string
     */
    fun pinFingerprint(host: String, fingerprint: String) {
        db ?: return
        val values = ContentValues().apply {
            put("host", host)
            put("fingerprint", fingerprint)
            put("pinned_at", System.currentTimeMillis())
        }
        db!!.insertWithOnConflict(
            CERT_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Clears all stored fingerprints from the database.
     * Use this when the user wants to reset TOFU trust (e.g., after a legitimate server certificate rotation).
     */
    fun clearFingerprints() {
        db ?: return
        db!!.delete(CERT_TABLE, null, null)
    }
}
