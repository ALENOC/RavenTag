package io.raventag.app.wallet

// Wave 0 scaffolding stubs. These exceptions are referenced by WalletManagerMnemonicTest.kt.
// Full implementations in plan 30-06.

class BackupRequiredException(msg: String = "backup required before restore") : RuntimeException(msg)
class IntegrityException(msg: String = "seed HMAC mismatch") : RuntimeException(msg)
class KeystoreInvalidatedException(cause: Throwable? = null) : RuntimeException("keystore invalidated", cause)

/**
 * Signaled by RPC / subscription paths when every ElectrumX server in the
 * pool is currently quarantined. UI (plan 30-08) uses this to drive the RED
 * pill + disabled Send/Receive snackbar ("Offline, all nodes unreachable").
 */
class AllNodesUnreachableException(msg: String = "all ElectrumX nodes quarantined") : RuntimeException(msg)
