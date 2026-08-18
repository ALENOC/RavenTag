package io.raventag.app.security

/**
 * Crash-safe migration of the exact secret-bearing keys historically stored in
 * the legacy `raventag_secure` preference file.
 *
 * The caller supplies storage operations so the state machine is JVM-testable.
 * A source value is removed only after the destination was written and read back
 * byte-for-byte (String equality here). If deletion is interrupted, a repeated
 * invocation sees the already-verified destination and safely finishes cleanup.
 */
object LegacySecretMigration {
    val SENSITIVE_KEYS: Set<String> = setOf(
        "admin_key",
        "operator_key",
        "initial_master_key",
        "pinata_jwt"
    )

    data class Outcome(val migrated: Int, val cleanedUp: Int)

    fun migrate(
        readLegacy: (String) -> String?,
        writeSecure: (String, String) -> Unit,
        readSecure: (String) -> String?,
        removeLegacy: (String) -> Unit
    ): Outcome {
        var migrated = 0
        var cleaned = 0
        for (key in SENSITIVE_KEYS) {
            val legacy = readLegacy(key) ?: continue
            val existing = readSecure(key)
            if (existing != legacy) {
                writeSecure(key, legacy)
                check(readSecure(key) == legacy) {
                    "secure migration read-back mismatch for $key"
                }
                migrated++
            }
            // If a previous run stopped after verified write, equality above lets
            // this run resume at cleanup without overwriting or losing the source.
            removeLegacy(key)
            check(readLegacy(key) == null) {
                "legacy secret cleanup did not persist for $key"
            }
            cleaned++
        }
        return Outcome(migrated, cleaned)
    }
}
