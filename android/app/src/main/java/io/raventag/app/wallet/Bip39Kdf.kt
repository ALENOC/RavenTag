package io.raventag.app.wallet

import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Pure BIP39 PBKDF2 boundary, shared by production and deterministic JVM vectors. */
internal object Bip39Kdf {
    fun deriveSeed(mnemonic: String, passphrase: String): ByteArray {
        val normalizedMnemonic = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
        val normalizedPassphrase = Normalizer.normalize(passphrase, Normalizer.Form.NFKD)
        val password = normalizedMnemonic.toCharArray()
        val salt = ("mnemonic" + normalizedPassphrase).toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password, salt, 2048, 512)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            java.util.Arrays.fill(password, '\u0000')
            salt.fill(0)
        }
    }
}
