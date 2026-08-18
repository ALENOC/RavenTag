package io.raventag.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacySecretMigrationTest {
    private class Store(initial: Map<String, String> = emptyMap()) {
        val values = initial.toMutableMap()
        var failWrite = false
        var failRemoveOnce = false
        fun read(k: String): String? = values[k]
        fun write(k: String, v: String) {
            if (failWrite) throw IllegalStateException("secure store unavailable")
            values[k] = v
        }
        fun remove(k: String) {
            if (failRemoveOnce) {
                failRemoveOnce = false
                throw IllegalStateException("interrupted before cleanup")
            }
            values.remove(k)
        }
    }

    private fun run(legacy: Store, secure: Store) = LegacySecretMigration.migrate(
        legacy::read, secure::write, secure::read, legacy::remove
    )

    @Test fun noLegacyValuesIsNoOp() {
        val out = run(Store(), Store())
        assertEquals(0, out.migrated)
        assertEquals(0, out.cleanedUp)
    }

    @Test fun successfulMigrationVerifiesThenDeletesLegacy() {
        val legacy = Store(mapOf("operator_key" to "secret"))
        val secure = Store()
        val out = run(legacy, secure)
        assertEquals("secret", secure.read("operator_key"))
        assertNull(legacy.read("operator_key"))
        assertEquals(1, out.migrated)
        assertEquals(1, out.cleanedUp)
    }

    @Test fun encryptedStoreFailurePreservesLegacy() {
        val legacy = Store(mapOf("pinata_jwt" to "jwt"))
        val secure = Store().also { it.failWrite = true }
        assertThrows(IllegalStateException::class.java) { run(legacy, secure) }
        assertEquals("jwt", legacy.read("pinata_jwt"))
    }

    @Test fun interruptedMigrationIsSafelyResumed() {
        val legacy = Store(mapOf("initial_master_key" to "master"))
        val secure = Store()
        legacy.failRemoveOnce = true
        assertThrows(IllegalStateException::class.java) { run(legacy, secure) }
        assertEquals("master", legacy.read("initial_master_key"))
        assertEquals("master", secure.read("initial_master_key"))
        val second = run(legacy, secure)
        assertNull(legacy.read("initial_master_key"))
        assertEquals(0, second.migrated)
        assertEquals(1, second.cleanedUp)
    }

    @Test fun repeatedMigrationIsIdempotent() {
        val legacy = Store(mapOf("admin_key" to "admin"))
        val secure = Store()
        run(legacy, secure)
        val second = run(legacy, secure)
        assertEquals("admin", secure.read("admin_key"))
        assertEquals(0, second.migrated)
        assertEquals(0, second.cleanedUp)
    }
}
