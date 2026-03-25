package io.raventag.app.wallet

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.jce.ECNamedCurveTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * JVM unit tests for RavencoinTxBuilder.
 *
 * Tests the fixes for:
 * - base58Decode checksum verification (prevents silent wrong-address bugs)
 * - Transaction structural correctness (version, inputs, outputs)
 *
 * These tests run on the JVM without an Android device.
 */
class RavencoinTxBuilderTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Derive compressed public key from raw private key bytes using secp256k1. */
    private fun pubKeyFromPrivKey(privKey: ByteArray): ByteArray {
        val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val point = spec.g.multiply(BigInteger(1, privKey)).normalize()
        return point.getEncoded(true)
    }

    /** Compute HASH160 = RIPEMD160(SHA256(data)). */
    private fun hash160(data: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256").digest(data)
        val result = ByteArray(20)
        RIPEMD160Digest().apply { update(sha, 0, sha.size); doFinal(result, 0) }
        return result
    }

    /** Compute double SHA256. */
    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    /** Encode data as Base58Check with the given version byte. */
    private fun toBase58Check(version: Byte, payload: ByteArray): String {
        val full = byteArrayOf(version) + payload
        val checksum = doubleSha256(full).copyOf(4)
        val data = full + checksum
        var num = BigInteger(1, data)
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(base)
            sb.append(alphabet[r.toInt()])
            num = q
        }
        for (b in data) {
            if (b == 0.toByte()) sb.append(alphabet[0]) else break
        }
        return sb.reverse().toString()
    }

    /** Build a P2PKH scriptPubKey hex from a hash160 (20 bytes). */
    private fun p2pkhScriptHex(hash160: ByteArray): String {
        val script = byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) +
                hash160 +
                byteArrayOf(0x88.toByte(), 0xac.toByte())
        return script.joinToString("") { "%02x".format(it) }
    }

    /** Generate a test Ravencoin address (version byte 0x3C) from a private key. */
    private fun testAddress(privKey: ByteArray): String {
        val pubKey = pubKeyFromPrivKey(privKey)
        val h160 = hash160(pubKey)
        return toBase58Check(0x3C.toByte(), h160)
    }

    // Fixed test private key (scalar = 1, always valid on secp256k1)
    private val testPrivKey = ByteArray(31) { 0 } + byteArrayOf(1)
    private val testPubKey by lazy { pubKeyFromPrivKey(testPrivKey) }
    private val senderAddress by lazy { testAddress(testPrivKey) }
    private val senderScript by lazy {
        val h160 = hash160(testPubKey)
        p2pkhScriptHex(h160)
    }

    // ── base58Decode checksum fix tests ────────────────────────────────────────

    @Test
    fun `buildAndSign with valid address succeeds`() {
        val utxos = listOf(
            Utxo(
                txid = "a".repeat(64),
                outputIndex = 0,
                satoshis = 2_000_000L,
                script = senderScript,
                height = 100
            )
        )
        val result = RavencoinTxBuilder.buildAndSign(
            utxos = utxos,
            toAddress = senderAddress,
            amountSat = 1_000_000L,
            feeSat = 100_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
        assertNotNull(result)
        assertTrue("tx hex must not be empty", result.hex.isNotEmpty())
        assertTrue("tx hex must be lowercase hex", result.hex.all { it.isDigit() || it in 'a'..'f' })
        assertEquals("txid must be 64 hex chars", 64, result.txid.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildAndSign with corrupted recipient address checksum throws`() {
        val validAddress = senderAddress
        // Flip the last character to corrupt the checksum
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val lastChar = validAddress.last()
        val corruptChar = alphabet[(alphabet.indexOf(lastChar) + 1) % alphabet.length]
        val badAddress = validAddress.dropLast(1) + corruptChar

        val utxos = listOf(
            Utxo("a".repeat(64), 0, 2_000_000L, senderScript, 100)
        )
        RavencoinTxBuilder.buildAndSign(
            utxos = utxos,
            toAddress = badAddress,
            amountSat = 1_000_000L,
            feeSat = 100_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildAndSign with corrupted change address checksum throws`() {
        val validAddress = senderAddress
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val lastChar = validAddress.last()
        val corruptChar = alphabet[(alphabet.indexOf(lastChar) + 1) % alphabet.length]
        val badChange = validAddress.dropLast(1) + corruptChar

        val utxos = listOf(
            Utxo("a".repeat(64), 0, 2_000_000L, senderScript, 100)
        )
        // amount + fee > funds so change < dust limit, no change output: test recipient is valid
        // Use badChange only: test amount such that change output IS generated
        RavencoinTxBuilder.buildAndSign(
            utxos = utxos,
            toAddress = senderAddress,
            amountSat = 500_000L,
            feeSat = 100_000L,
            changeAddress = badChange,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildAndSign with insufficient funds throws`() {
        val utxos = listOf(
            Utxo("a".repeat(64), 0, 1_000L, senderScript, 100)
        )
        RavencoinTxBuilder.buildAndSign(
            utxos = utxos,
            toAddress = senderAddress,
            amountSat = 1_000_000L,
            feeSat = 100_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
    }

    // ── Transaction structural tests ──────────────────────────────────────────

    @Test
    fun `buildAndSign transaction has correct version bytes`() {
        val utxos = listOf(
            Utxo("a".repeat(64), 0, 2_000_000L, senderScript, 100)
        )
        val result = RavencoinTxBuilder.buildAndSign(
            utxos = utxos,
            toAddress = senderAddress,
            amountSat = 1_000_000L,
            feeSat = 100_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
        // First 4 bytes of raw tx = version in little-endian = 02 00 00 00
        assertTrue("tx must start with version 2 (02000000)", result.hex.startsWith("02000000"))
    }

    @Test
    fun `buildAndSign with two inputs signs both`() {
        val utxos = listOf(
            Utxo("a".repeat(64), 0, 1_000_000L, senderScript, 100),
            Utxo("b".repeat(64), 1, 1_000_000L, senderScript, 101)
        )
        val result = RavencoinTxBuilder.buildAndSign(
            utxos = utxos,
            toAddress = senderAddress,
            amountSat = 1_500_000L,
            feeSat = 100_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
        assertNotNull(result)
        // input count byte is at position 8 (after 4 version bytes = 8 hex chars)
        val inputCount = result.hex.substring(8, 10).toInt(16)
        assertEquals("tx must have 2 inputs", 2, inputCount)
    }

    @Test
    fun `txid is double-sha256 of raw tx reversed`() {
        val utxos = listOf(
            Utxo("a".repeat(64), 0, 2_000_000L, senderScript, 100)
        )
        val result = RavencoinTxBuilder.buildAndSign(
            utxos = utxos,
            toAddress = senderAddress,
            amountSat = 1_000_000L,
            feeSat = 100_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
        val rawBytes = result.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val expectedTxid = doubleSha256(rawBytes).reversedArray()
            .joinToString("") { "%02x".format(it) }
        assertEquals("txid must be reversed double-SHA256 of raw tx", expectedTxid, result.txid)
    }

    // ── Asset transfer structural test ────────────────────────────────────────

    @Test
    fun `buildAndSignAssetTransfer with valid address succeeds`() {
        val assetScript = buildKnownAssetScript(senderAddress, "TEST#001", 1_00_000_000L)
        val assetUtxo = Utxo("c".repeat(64), 0, 600L, assetScript, 200)
        val rvnUtxo = Utxo("d".repeat(64), 0, 2_000_000L, senderScript, 200)

        val result = RavencoinTxBuilder.buildAndSignAssetTransfer(
            assetUtxos = listOf(assetUtxo),
            rvnUtxos = listOf(rvnUtxo),
            toAddress = senderAddress,
            assetName = "TEST#001",
            assetAmount = 1_00_000_000L,
            feeSat = 1_000_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
        assertNotNull(result)
        assertTrue(result.hex.isNotEmpty())
        assertEquals(64, result.txid.length)
        // Must start with version 2
        assertTrue(result.hex.startsWith("02000000"))
    }

    @Test
    fun `buildAndSignAssetIssue creates zero-valued owner and issue outputs`() {
        val utxos = listOf(
            Utxo(
                txid = "e".repeat(64),
                outputIndex = 1,
                satoshis = RavencoinTxBuilder.BURN_ROOT_SAT + 5_000_000L,
                script = senderScript,
                height = 250
            )
        )

        val result = RavencoinTxBuilder.buildAndSignAssetIssue(
            utxos = utxos,
            assetName = "RAVENTAG",
            qtyRaw = 100_000_000L,
            toAddress = senderAddress,
            changeAddress = senderAddress,
            units = 0,
            reissuable = true,
            ipfsHash = null,
            burnSat = RavencoinTxBuilder.BURN_ROOT_SAT,
            feeSat = 1_000_000L,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )

        val raw = result.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var offset = 4 // version
        val inputCount = raw[offset].toInt() and 0xff
        offset += 1
        repeat(inputCount) {
            offset += 32 // txid
            offset += 4  // vout
            val scriptLen = raw[offset].toInt() and 0xff
            offset += 1 + scriptLen
            offset += 4  // sequence
        }
        val outputCount = raw[offset].toInt() and 0xff
        offset += 1
        assertEquals(4, outputCount)

        val outputValues = mutableListOf<Long>()
        repeat(outputCount) {
            var value = 0L
            repeat(8) { i -> value = value or ((raw[offset + i].toLong() and 0xffL) shl (8 * i)) }
            outputValues.add(value)
            offset += 8
            val scriptLen = raw[offset].toInt() and 0xff
            offset += 1 + scriptLen
        }

        assertEquals(RavencoinTxBuilder.BURN_ROOT_SAT, outputValues[0])
        assertEquals(0L, outputValues[2])
        assertEquals(0L, outputValues[3])
    }

    @Test
    fun `buildAndSignAssetIssue for sub-asset signs rvno owner input and returns parent owner token`() {
        val rvnUtxo = Utxo(
            txid = "1".repeat(64),
            outputIndex = 0,
            satoshis = RavencoinTxBuilder.BURN_SUB_SAT + 5_000_000L,
            script = senderScript,
            height = 300
        )
        val parentOwnerScript = buildKnownOwnerScript(senderAddress, "RAVENTAG!")
        val ownerUtxo = Utxo(
            txid = "2".repeat(64),
            outputIndex = 1,
            satoshis = 0L,
            script = parentOwnerScript,
            height = 301
        )

        val result = RavencoinTxBuilder.buildAndSignAssetIssue(
            utxos = listOf(rvnUtxo),
            ownerAssetUtxos = listOf(ownerUtxo),
            assetName = "RAVENTAG/ITEM01",
            qtyRaw = 100_000_000L,
            toAddress = senderAddress,
            changeAddress = senderAddress,
            units = 0,
            reissuable = true,
            ipfsHash = null,
            burnSat = RavencoinTxBuilder.BURN_SUB_SAT,
            feeSat = 1_000_000L,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )

        assertTrue(result.hex.startsWith("02000000"))
        assertEquals(2, result.hex.substring(8, 10).toInt(16))
        assertTrue(result.hex.contains("72766e7409524156454e54414721"))
        assertTrue(result.hex.contains("72766e6f10524156454e5441472f4954454d303121"))
        assertTrue(result.hex.contains("72766e710f524156454e5441472f4954454d3031"))
        assertTrue(result.hex.contains("72766e7409524156454e5441472100e1f50500000000"))
        assertTrue(result.hex.contains("00000000000000003276a914"))
        assertTrue(parentOwnerScript.contains("72766e6f09524156454e54414721"))
    }

    @Test
    fun `buildAndSignAssetIssue for unique token returns parent owner token with raw owner amount and zero sat output`() {
        val rvnUtxo = Utxo(
            txid = "3".repeat(64),
            outputIndex = 0,
            satoshis = RavencoinTxBuilder.BURN_UNIQUE_SAT + 5_000_000L,
            script = senderScript,
            height = 320
        )
        val ownerUtxo = Utxo(
            txid = "4".repeat(64),
            outputIndex = 1,
            satoshis = 600L,
            script = buildKnownOwnerScript(senderAddress, "RAVENTAG/ITEM01!"),
            height = 321
        )

        val result = RavencoinTxBuilder.buildAndSignAssetIssue(
            utxos = listOf(rvnUtxo),
            ownerAssetUtxos = listOf(ownerUtxo),
            assetName = "RAVENTAG/ITEM01#SN0002",
            qtyRaw = 100_000_000L,
            toAddress = senderAddress,
            changeAddress = senderAddress,
            units = 0,
            reissuable = false,
            ipfsHash = null,
            burnSat = RavencoinTxBuilder.BURN_UNIQUE_SAT,
            feeSat = 1_000_000L,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )

        assertTrue(result.hex.startsWith("02000000"))
        assertEquals(2, result.hex.substring(8, 10).toInt(16))
        assertTrue(result.hex.contains("72766e7410524156454e5441472f4954454d30312100e1f50500000000"))
        assertTrue(result.hex.contains("72766e7116524156454e5441472f4954454d303123534e30303032"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildAndSignAssetTransfer with corrupted recipient address throws`() {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val lastChar = senderAddress.last()
        val corruptChar = alphabet[(alphabet.indexOf(lastChar) + 1) % alphabet.length]
        val badAddress = senderAddress.dropLast(1) + corruptChar

        val assetScript = buildKnownAssetScript(senderAddress, "TEST#001", 1_00_000_000L)
        val assetUtxo = Utxo("c".repeat(64), 0, 600L, assetScript, 200)
        val rvnUtxo = Utxo("d".repeat(64), 0, 2_000_000L, senderScript, 200)

        RavencoinTxBuilder.buildAndSignAssetTransfer(
            assetUtxos = listOf(assetUtxo),
            rvnUtxos = listOf(rvnUtxo),
            toAddress = badAddress,
            assetName = "TEST#001",
            assetAmount = 1_00_000_000L,
            feeSat = 1_000_000L,
            changeAddress = senderAddress,
            privKeyBytes = testPrivKey,
            pubKeyBytes = testPubKey
        )
    }

    // ── Private helpers for asset tests ──────────────────────────────────────

    /**
     * Build a minimal OP_RVN_ASSET scriptPubKey hex for test UTXOs.
     * Mirrors the format used by RavencoinPublicNode.getAssetUtxosFull().
     */
    private fun buildKnownAssetScript(address: String, assetName: String, rawAmount: Long): String {
        // Base58Check decode to get hash160
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger.ZERO
        for (c in address) num = num.multiply(BigInteger.valueOf(58))
            .add(BigInteger.valueOf(alphabet.indexOf(c).toLong()))
        val bytes = num.toByteArray()
        val leading = address.takeWhile { it == '1' }.length
        val decoded = ByteArray(leading) + if (bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        val hash160 = decoded.copyOfRange(1, 21)

        val nameBytes = assetName.toByteArray(Charsets.US_ASCII)
        // Payload: "rvnt" + 1-byte name len + name + LE64(amount)
        val payload = byteArrayOf(0x72, 0x76, 0x6e, 0x74, nameBytes.size.toByte()) +
                nameBytes +
                (0 until 8).map { i -> ((rawAmount shr (8 * i)) and 0xFF).toByte() }.toByteArray()

        val script = byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) +
                hash160 +
                byteArrayOf(0x88.toByte(), 0xac.toByte(), 0xc0.toByte()) +
                byteArrayOf(payload.size.toByte()) + payload +
                byteArrayOf(0x75)
        return script.joinToString("") { "%02x".format(it) }
    }

    private fun buildKnownOwnerScript(address: String, assetName: String): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = BigInteger.ZERO
        for (c in address) num = num.multiply(BigInteger.valueOf(58))
            .add(BigInteger.valueOf(alphabet.indexOf(c).toLong()))
        val bytes = num.toByteArray()
        val leading = address.takeWhile { it == '1' }.length
        val decoded = ByteArray(leading) + if (bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        val hash160 = decoded.copyOfRange(1, 21)

        val nameBytes = assetName.toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(0x72, 0x76, 0x6e, 0x6f, nameBytes.size.toByte()) + nameBytes

        val script = byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) +
                hash160 +
                byteArrayOf(0x88.toByte(), 0xac.toByte(), 0xc0.toByte()) +
                byteArrayOf(payload.size.toByte()) + payload +
                byteArrayOf(0x75)
        return script.joinToString("") { "%02x".format(it) }
    }
}
