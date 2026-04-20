package io.raventag.app.wallet

// Wave 0 scaffolding stubs. These exceptions are referenced by WalletManagerMnemonicTest.kt.
// Full implementations in plan 30-06.

class BackupRequiredException(msg: String = "backup required before restore") : RuntimeException(msg)
class IntegrityException(msg: String = "seed HMAC mismatch") : RuntimeException(msg)
class KeystoreInvalidatedException(cause: Throwable? = null) : RuntimeException("keystore invalidated", cause)
