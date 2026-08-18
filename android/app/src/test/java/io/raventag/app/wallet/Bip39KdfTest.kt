package io.raventag.app.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Bip39KdfTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun officialBip39Vector_zeroEntropy_withTREZORPassphrase() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val expected = "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
        assertEquals(expected, Bip39Kdf.deriveSeed(mnemonic, "TREZOR").hex())
    }

    @Test
    fun unicodePassphraseIsNfkdNormalized() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val composed = "caf\u00e9"
        val decomposed = "cafe\u0301"
        val a = Bip39Kdf.deriveSeed(mnemonic, composed)
        val b = Bip39Kdf.deriveSeed(mnemonic, decomposed)
        assertArrayEquals(a, b)
    }
}
