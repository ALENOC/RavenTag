package io.raventag.app.security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TofuFingerprint(
    val host: String,
    val fingerprint: String,
    val pinnedAt: Long
)

data class TofuMismatch(
    val host: String,
    val expectedFingerprint: String,
    val observedFingerprint: String,
    val detectedAt: Long,
    val systemTrusted: Boolean
)

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
    private const val MISMATCH_TABLE = "tofu_mismatches"
    private const val DB_VERSION = 2

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
            createMismatchTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createMismatchTable(db)
        }

        private fun createMismatchTable(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $MISMATCH_TABLE (
                    host TEXT PRIMARY KEY,
                    expected_fingerprint TEXT NOT NULL,
                    observed_fingerprint TEXT NOT NULL,
                    detected_at INTEGER NOT NULL,
                    system_trusted INTEGER NOT NULL
                )
            """.trimIndent())
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
        val database = requireDatabase()
        val cursor = database.query(
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

    fun getAllPins(): List<TofuFingerprint> {
        val cursor = requireDatabase().query(
            CERT_TABLE,
            arrayOf("host", "fingerprint", "pinned_at"),
            null, null, null, null,
            "host ASC"
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(TofuFingerprint(it.getString(0), it.getString(1), it.getLong(2)))
                }
            }
        }
    }

    fun getMismatch(host: String): TofuMismatch? {
        val cursor = requireDatabase().query(
            MISMATCH_TABLE,
            arrayOf(
                "host",
                "expected_fingerprint",
                "observed_fingerprint",
                "detected_at",
                "system_trusted"
            ),
            "host = ?",
            arrayOf(host),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) {
                TofuMismatch(
                    it.getString(0),
                    it.getString(1),
                    it.getString(2),
                    it.getLong(3),
                    it.getInt(4) != 0
                )
            } else {
                null
            }
        }
    }

    fun getAllMismatches(): List<TofuMismatch> {
        val cursor = requireDatabase().query(
            MISMATCH_TABLE,
            arrayOf(
                "host",
                "expected_fingerprint",
                "observed_fingerprint",
                "detected_at",
                "system_trusted"
            ),
            null, null, null, null,
            "detected_at DESC"
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        TofuMismatch(
                            it.getString(0),
                            it.getString(1),
                            it.getString(2),
                            it.getLong(3),
                            it.getInt(4) != 0
                        )
                    )
                }
            }
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
        val database = requireDatabase()
        val values = ContentValues().apply {
            put("host", host)
            put("fingerprint", fingerprint)
            put("pinned_at", System.currentTimeMillis())
        }
        val result = database.insertWithOnConflict(
            CERT_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        check(result != -1L && getFingerprint(host) == fingerprint) {
            "Failed to persist certificate fingerprint for $host"
        }
    }

    fun recordMismatch(
        host: String,
        expectedFingerprint: String,
        observedFingerprint: String,
        systemTrusted: Boolean
    ): TofuMismatch {
        val database = requireDatabase()
        val mismatch = TofuMismatch(
            host,
            expectedFingerprint,
            observedFingerprint,
            System.currentTimeMillis(),
            systemTrusted
        )
        val values = ContentValues().apply {
            put("host", mismatch.host)
            put("expected_fingerprint", mismatch.expectedFingerprint)
            put("observed_fingerprint", mismatch.observedFingerprint)
            put("detected_at", mismatch.detectedAt)
            put("system_trusted", if (mismatch.systemTrusted) 1 else 0)
        }
        val result = database.insertWithOnConflict(
            MISMATCH_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        check(result != -1L && getMismatch(host) == mismatch) {
            "Failed to persist certificate mismatch for $host"
        }
        return mismatch
    }

    fun removeFingerprint(host: String): Boolean {
        val database = requireDatabase()
        database.beginTransaction()
        try {
            val removedPins = database.delete(CERT_TABLE, "host = ?", arrayOf(host))
            val removedMismatches = database.delete(MISMATCH_TABLE, "host = ?", arrayOf(host))
            check(removedPins <= 1 && removedMismatches <= 1 &&
                getFingerprint(host) == null && getMismatch(host) == null
            ) {
                "Failed to remove certificate fingerprint for $host"
            }
            database.setTransactionSuccessful()
            return removedPins + removedMismatches > 0
        } finally {
            database.endTransaction()
        }
    }

    fun conditionalRotateFingerprint(
        host: String,
        expectedFingerprint: String,
        observedFingerprint: String
    ): Boolean {
        val database = requireDatabase()
        database.beginTransaction()
        try {
            val mismatch = getMismatch(host)
            if (
                mismatch == null ||
                mismatch.expectedFingerprint != expectedFingerprint ||
                mismatch.observedFingerprint != observedFingerprint
            ) {
                return false
            }
            val values = ContentValues().apply {
                put("fingerprint", observedFingerprint)
                put("pinned_at", System.currentTimeMillis())
            }
            val updated = database.update(
                CERT_TABLE,
                values,
                "host = ? AND fingerprint = ?",
                arrayOf(host, expectedFingerprint)
            )
            if (updated != 1) return false
            val cleared = database.delete(
                MISMATCH_TABLE,
                "host = ? AND expected_fingerprint = ? AND observed_fingerprint = ?",
                arrayOf(host, expectedFingerprint, observedFingerprint)
            )
            check(cleared == 1) { "Failed to clear certificate mismatch for $host" }
            check(getFingerprint(host) == observedFingerprint && getMismatch(host) == null) {
                "Failed to verify certificate rotation for $host"
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return true
    }

    /**
     * Clears all stored fingerprints from the database.
     * Use this when the user wants to reset TOFU trust (e.g., after a legitimate server certificate rotation).
     */
    fun clearFingerprints() {
        val database = requireDatabase()
        database.beginTransaction()
        try {
            database.delete(CERT_TABLE, null, null)
            database.delete(MISMATCH_TABLE, null, null)
            val pinCursor = database.rawQuery("SELECT COUNT(*) FROM $CERT_TABLE", null)
            val pinsRemaining = pinCursor.use { if (it.moveToFirst()) it.getLong(0) else -1L }
            val mismatchCursor = database.rawQuery("SELECT COUNT(*) FROM $MISMATCH_TABLE", null)
            val mismatchesRemaining = mismatchCursor.use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
            check(pinsRemaining == 0L && mismatchesRemaining == 0L) {
                "Failed to clear certificate fingerprints"
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun requireDatabase(): SQLiteDatabase =
        db ?: throw IllegalStateException("TofuFingerprintDao is not initialized")
}
