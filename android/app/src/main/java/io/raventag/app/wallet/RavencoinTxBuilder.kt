/**
 * RavencoinTxBuilder.kt
 *
 * Constructs and signs raw Ravencoin transactions entirely on-device, with no
 * dependency on a remote signing service or a full node. Supports three kinds
 * of transaction:
 *
 *   1. Plain RVN P2PKH transfer (buildAndSign)
 *   2. Ravencoin asset transfer with OP_RVN_ASSET output (buildAndSignAssetTransfer)
 *   3. Ravencoin asset issuance: root, sub-asset, and unique token (buildAndSignAssetIssue)
 *
 * Wire format is identical to Bitcoin legacy transactions (version 2, no SegWit)
 * with Ravencoin-specific OP_RVN_ASSET outputs appended for asset operations.
 *
 * Signing uses RFC 6979 deterministic ECDSA (secp256k1) via Bouncy Castle, with
 * low-S canonicalization (BIP 62) to ensure signature malleability protection.
 *
 * Address encoding: Ravencoin P2PKH, version byte 0x3C (60 decimal). Addresses
 * are decoded from Base58Check format; the 4-byte checksum is verified on every
 * decode to catch typos before broadcasting.
 *
 * All integer fields (version, index, sequence, locktime, value) are serialized
 * in little-endian order as required by the Bitcoin/Ravencoin wire protocol.
 */
package io.raventag.app.wallet

import android.util.Log
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.jce.ECNamedCurveTable
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Builds and signs Ravencoin P2PKH transactions entirely on-device.
 * Compatible with Ravencoin mainnet (same wire format as Bitcoin with version 2).
 */
object RavencoinTxBuilder {

    /** SIGHASH_ALL: the entire transaction is committed to by the signature. */
    private const val SIGHASH_ALL = 1

    /**
     * nSequence = 0xFFFFFFFF disables relative locktime (RBF opt-out).
     * Stored as Long to avoid sign issues when writing as unsigned 32-bit LE.
     */
    private const val SEQUENCE = 0xFFFFFFFFL

    /** nLockTime = 0 means the transaction can be mined immediately. */
    private const val LOCKTIME = 0L

    /**
     * Transaction version 2. Ravencoin requires version 2 for asset transactions
     * (assets.cpp enforces this at the consensus layer).
     */
    private const val VERSION = 2

    // ── Ravencoin canonical burn addresses ───────────────────────────────────
    // These special addresses are hardcoded in the Ravencoin consensus rules.
    // Sending the correct burn amount to the matching address authorises asset
    // creation; any deviation is rejected by the network.

    /** Burn address for root asset issuance (cost: 500 RVN). */
    const val BURN_ADDRESS_ROOT = "RXissueAssetXXXXXXXXXXXXXXXXXhhZGt"

    /** Burn address for sub-asset issuance (cost: 100 RVN). */
    const val BURN_ADDRESS_SUB = "RXissueSubAssetXXXXXXXXXXXXXWcwhwL"

    /** Burn address for unique-token issuance (cost: 5 RVN). */
    const val BURN_ADDRESS_UNIQUE = "RXissueUniqueAssetXXXXXXXXXXWEAe58"

    /** Generic burn address for asset revocation/destruction (not for issuance). */
    const val BURN_ADDRESS = "RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV"

    // ── Issuance burn amounts in satoshis (1 RVN = 1e8 satoshis) ─────────────

    /** RVN cost to issue a root asset: 500 RVN in satoshis. */
    const val BURN_ROOT_SAT = 50_000_000_000L

    /** RVN cost to issue a sub-asset: 100 RVN in satoshis. */
    const val BURN_SUB_SAT = 10_000_000_000L

    /** RVN cost to issue a unique token: 5 RVN in satoshis. */
    const val BURN_UNIQUE_SAT = 500_000_000L

    // ── Data types ────────────────────────────────────────────────────────────

    /**
     * A plain RVN transaction output addressed to a Ravencoin P2PKH address.
     * The scriptPubKey is derived lazily from [address] inside the serializers.
     */
    data class TxOutput(val satoshis: Long, val address: String)

    /**
     * An output with a pre-built scriptPubKey (used for asset transfer and
     * issuance outputs, where the script is more complex than a simple P2PKH).
     */
    private data class ScriptedOutput(val satoshis: Long, val script: ByteArray)

    /** Hex-encoded raw transaction and its txid (both in display/broadcast form). */
    data class SignedTx(val hex: String, val txid: String)

    // ── Public API: RVN transfer ──────────────────────────────────────────────

    /**
     * Build and sign a plain P2PKH RVN transfer transaction.
     *
     * The function automatically handles the "send-all / sweep" case: if
     * [amountSat] + [feeSat] exceeds the total UTXO value, the fee is subtracted
     * from the recipient amount so the sender can drain the wallet completely.
     *
     * A change output is added only when the change exceeds the 546-satoshi
     * dust limit, to avoid creating unspendable outputs.
     *
     * @param utxos         UTXOs to spend (must total >= [feeSat])
     * @param toAddress     Recipient Ravencoin address
     * @param amountSat     Amount to send in satoshis
     * @param feeSat        Miner fee in satoshis
     * @param changeAddress Change address (usually the same as the sender)
     * @param privKeyBytes  Raw 32-byte private key (BIP44 derived)
     * @param pubKeyBytes   Compressed 33-byte public key matching [privKeyBytes]
     * @return [SignedTx] containing the raw hex and txid, ready to broadcast
     */
    fun buildAndSign(
        utxos: List<Utxo>,
        toAddress: String,
        amountSat: Long,
        feeSat: Long,
        changeAddress: String,
        privKeyBytes: ByteArray,
        pubKeyBytes: ByteArray
    ): SignedTx {
        val totalIn = utxos.sumOf { it.satoshis }
        require(totalIn > feeSat) {
            "Insufficient funds to cover fee: have ${totalIn / 1e8} RVN, fee ${feeSat / 1e8} RVN"
        }

        // If the requested amount leaves no room for the fee, subtract the fee from the
        // recipient amount (send-all / sweep mode). The recipient gets slightly less.
        val effectiveAmount = if (amountSat + feeSat > totalIn) totalIn - feeSat else amountSat
        require(effectiveAmount > 546) { "Amount too small after fee deduction" }

        val changeSat = totalIn - effectiveAmount - feeSat
        val outputs = mutableListOf(TxOutput(effectiveAmount, toAddress))
        if (changeSat > 546) outputs.add(TxOutput(changeSat, changeAddress)) // dust limit

        // Sign each input independently (legacy P2PKH: sign with that input's scriptPubKey)
        val signatures = utxos.mapIndexed { idx, utxo ->
            val sigHash = sigHashForInput(utxos, outputs, idx, utxo.script)
            signEcdsa(sigHash, privKeyBytes)
        }

        val raw = serializeTx(utxos, outputs, signatures, pubKeyBytes)
        val txid = txid(raw)
        return SignedTx(raw.toHex(), txid)
    }

    // ── Public API: asset transfer ────────────────────────────────────────────

    /**
     * Build and sign a Ravencoin asset transfer transaction (on-device, no backend).
     *
     * Asset transfers require two kinds of inputs:
     *   - [assetUtxos]: UTXOs that carry the asset (each has a full asset scriptPubKey)
     *   - [rvnUtxos]: extra RVN-only UTXOs to cover the miner fee (may be empty if the
     *     dust attached to the asset UTXOs already covers the fee)
     *
     * Outputs are ordered to satisfy the Ravencoin consensus requirement that all plain
     * P2PKH outputs must come before OP_RVN_ASSET outputs:
     *   1. RVN change back to [changeAddress] (omitted if below dust limit)
     *   2. Asset transfer output (600-satoshi dust + OP_RVN_ASSET "rvnt") to [toAddress]
     *   3. Asset change output (600-satoshi dust + OP_RVN_ASSET "rvnt") back to [changeAddress]
     *      (only when [assetChangeAmount] > 0, i.e. partial transfer of a fungible asset)
     *
     * The asset UTXOs' scripts (Utxo.script) must be the full asset scriptPubKey
     * including the OP_RVN_ASSET suffix, as returned by
     * RavencoinPublicNode.getAssetUtxosFull().
     *
     * @param assetUtxos       UTXOs carrying the asset (each has a full asset scriptPubKey in .script)
     * @param rvnUtxos         Extra RVN-only UTXOs for fee coverage (may be empty)
     * @param toAddress        Recipient Ravencoin address
     * @param assetName        Asset name (e.g. "BRAND/ITEM#SN001")
     * @param assetAmount      Raw asset amount to send to [toAddress]
     * @param assetChangeAmount Remaining raw asset amount to return to [changeAddress];
     *                          0 for full transfers (unique tokens, owner tokens, sweep)
     * @param feeSat           Miner fee in satoshis
     * @param changeAddress    Address for RVN change and asset change (usually the sender)
     * @param privKeyBytes     Raw 32-byte private key
     * @param pubKeyBytes      Compressed 33-byte public key
     * @return [SignedTx] ready to broadcast
     */
    fun buildAndSignAssetTransfer(
        assetUtxos: List<Utxo>,
        rvnUtxos: List<Utxo>,
        toAddress: String,
        assetName: String,
        assetAmount: Long,
        assetChangeAmount: Long = 0L,
        feeSat: Long,
        changeAddress: String,
        privKeyBytes: ByteArray,
        pubKeyBytes: ByteArray
    ): SignedTx {
        // Asset UTXOs from issuance may have 0 satoshis (dustOut = 0 in buildAndSignAssetIssue).
        // To avoid consensus error "bad-txns-asset-transfer-amount-isn't-zero", we must preserve
        // the satoshi value from input to output for each asset UTXO.
        // If input asset UTXO has 0 satoshis, output must also have 0 satoshis.
        val assetDustIn = assetUtxos.sumOf { it.satoshis }
        val rvnFromRvnUtxosOnly = rvnUtxos.sumOf { it.satoshis }

        // Dust for asset outputs: use 0 if input asset had 0 satoshis to preserve value balance.
        // This prevents the "creating satoshi from nothing" consensus error.
        val dustForRecipient = if (assetDustIn > 0) 600L else 0L
        val dustForAssetChange = if (assetChangeAmount > 0 && assetDustIn > 0) 600L else 0L

        // RVN change comes only from RVN-only inputs, after covering fee (dust is separate).
        val rvnChange = rvnFromRvnUtxosOnly - feeSat
        require(rvnChange >= 0) {
            "Insufficient RVN for fee: have ${rvnFromRvnUtxosOnly / 1e8} RVN, " +
            "need ${feeSat / 1e8} RVN"
        }

        val allInputs = assetUtxos + rvnUtxos

        // Output order: plain P2PKH first, then all OP_RVN_ASSET outputs (Ravencoin consensus).
        val outputs = mutableListOf<ScriptedOutput>()
        if (rvnChange > 546) outputs.add(ScriptedOutput(rvnChange, p2pkhScript(changeAddress)))
        outputs.add(ScriptedOutput(dustForRecipient,
            buildAssetTransferScript(toAddress, assetName, assetAmount)))
        if (assetChangeAmount > 0) {
            outputs.add(ScriptedOutput(dustForAssetChange,
                buildAssetTransferScript(changeAddress, assetName, assetChangeAmount)))
        }

        // Sign each input (subscript = the input's own scriptPubKey as required by legacy signing)
        val signatures = allInputs.mapIndexed { idx, utxo ->
            val sigHash = sigHashWithScriptedOutputs(allInputs, outputs, idx, utxo.script)
            signEcdsa(sigHash, privKeyBytes)
        }

        val raw = serializeTxWithScripts(allInputs, outputs, signatures, pubKeyBytes)
        val txid = txid(raw)
        return SignedTx(raw.toHex(), txid)
    }

    // ── Signature hash (BIP143 NOT used, Ravencoin uses legacy P2PKH signing) ──

    /**
     * Compute the legacy P2PKH signature hash for input at [sigIdx].
     *
     * Per the Bitcoin/Ravencoin signing protocol:
     *   - All inputs are serialized; the input being signed uses [subscriptHex]
     *     (the scriptPubKey of the UTXO being spent) as its scriptSig field.
     *   - All other inputs have an empty scriptSig (length = 0).
     *   - The full serialized transaction is double-SHA256 hashed.
     *   - SIGHASH_ALL (0x01) is appended as a 4-byte LE integer before hashing.
     *
     * @param inputs       All transaction inputs
     * @param outputs      All transaction outputs (plain P2PKH)
     * @param sigIdx       Index of the input being signed
     * @param subscriptHex Hex-encoded scriptPubKey of the UTXO at [sigIdx]
     * @return 32-byte double-SHA256 hash used as the ECDSA message
     */
    private fun sigHashForInput(
        inputs: List<Utxo>,
        outputs: List<TxOutput>,
        sigIdx: Int,
        subscriptHex: String
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        // Transaction version (4 bytes LE)
        buf.writeLE32(VERSION)
        // Input count as variable-length integer
        buf.writeVarInt(inputs.size)
        inputs.forEachIndexed { i, utxo ->
            // txid is stored in internal byte order (reversed from the display hex)
            buf.write(utxo.txid.hexToBytes().reversedArray())
            buf.writeLE32(utxo.outputIndex)
            if (i == sigIdx) {
                // Place subscript (scriptPubKey of the UTXO being spent) for the signed input
                val sub = subscriptHex.hexToBytes()
                buf.writeVarInt(sub.size)
                buf.write(sub)
            } else {
                // All other inputs: empty scriptSig during signing
                buf.writeVarInt(0)
            }
            buf.writeLE32U(SEQUENCE)
        }
        // Output count and each output
        buf.writeVarInt(outputs.size)
        outputs.forEach { out ->
            buf.writeLE64(out.satoshis)
            val script = p2pkhScript(out.address)
            buf.writeVarInt(script.size)
            buf.write(script)
        }
        buf.writeLE32(LOCKTIME.toInt())
        // SIGHASH_ALL appended as 4-byte LE before hashing (not included in the final scriptSig)
        buf.writeLE32(SIGHASH_ALL)
        return doubleSha256(buf.toByteArray())
    }

    // ── ECDSA signing ─────────────────────────────────────────────────────────

    /**
     * Sign [hash] with [privKey] using RFC 6979 deterministic ECDSA on secp256k1.
     *
     * RFC 6979 derives the ephemeral key k deterministically from the private key
     * and the hash, eliminating any dependency on a CSPRNG and preventing the
     * catastrophic k-reuse vulnerability.
     *
     * The S value is canonicalised to the lower half of the curve order (BIP 62
     * low-S rule) to prevent transaction malleability.
     *
     * The result is DER-encoded and the SIGHASH_ALL byte (0x01) is appended,
     * producing the format expected in a P2PKH scriptSig.
     *
     * @param hash     32-byte transaction signature hash
     * @param privKey  Raw 32-byte private key bytes
     * @return DER-encoded signature with SIGHASH_ALL suffix
     */
    private fun signEcdsa(hash: ByteArray, privKey: ByteArray): ByteArray {
        val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val domainParams = org.bouncycastle.crypto.params.ECDomainParameters(
            spec.curve, spec.g, spec.n, spec.h
        )
        // RFC6979: deterministic k derivation, eliminates PRNG dependency for signing
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(BigInteger(1, privKey), domainParams))
        val (r, sRaw) = signer.generateSignature(hash)
        // Low-S canonicalization (BIP62): if S > n/2, replace S with n - S
        val s = if (sRaw > spec.n.shiftRight(1)) spec.n.subtract(sRaw) else sRaw
        return derEncodeSignature(r, s) + byteArrayOf(SIGHASH_ALL.toByte())
    }

    /**
     * DER-encode an ECDSA (r, s) pair as required by Bitcoin/Ravencoin scriptSig.
     *
     * DER format: 0x30 <total_len> 0x02 <r_len> <r> 0x02 <s_len> <s>
     * Both r and s are padded with a leading 0x00 byte if their high bit is set,
     * to ensure they are interpreted as positive integers by DER parsers.
     */
    private fun derEncodeSignature(r: BigInteger, s: BigInteger): ByteArray {
        val rb = r.toByteArray()
        val sb = s.toByteArray()
        // Ensure positive: pad with 0x00 if high bit is set (would be treated as negative)
        val rPadded = if (rb[0] < 0) byteArrayOf(0) + rb else rb
        val sPadded = if (sb[0] < 0) byteArrayOf(0) + sb else sb
        val payload = byteArrayOf(0x02, rPadded.size.toByte()) + rPadded +
                byteArrayOf(0x02, sPadded.size.toByte()) + sPadded
        return byteArrayOf(0x30, payload.size.toByte()) + payload
    }

    // ── Transaction serialization (plain RVN outputs) ─────────────────────────

    /**
     * Serialize a fully-signed transaction with plain P2PKH outputs into raw bytes.
     *
     * Layout: version | input_count | inputs[] | output_count | outputs[] | locktime
     * Each input scriptSig: <sig_push><sig_bytes> <pubkey_push><pubkey_bytes>
     */
    private fun serializeTx(
        inputs: List<Utxo>,
        outputs: List<TxOutput>,
        signatures: List<ByteArray>,
        pubKey: ByteArray
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.writeLE32(VERSION)
        buf.writeVarInt(inputs.size)
        inputs.forEachIndexed { i, utxo ->
            // txid in internal byte order (little-endian / reversed)
            buf.write(utxo.txid.hexToBytes().reversedArray())
            buf.writeLE32(utxo.outputIndex)
            val sig = signatures[i]
            // P2PKH scriptSig: push(sig) push(pubkey)
            val script = byteArrayOf(sig.size.toByte()) + sig +
                    byteArrayOf(pubKey.size.toByte()) + pubKey
            buf.writeVarInt(script.size)
            buf.write(script)
            buf.writeLE32U(SEQUENCE)
        }
        buf.writeVarInt(outputs.size)
        outputs.forEach { out ->
            buf.writeLE64(out.satoshis)
            val script = p2pkhScript(out.address)
            buf.writeVarInt(script.size)
            buf.write(script)
        }
        buf.writeLE32(LOCKTIME.toInt())
        return buf.toByteArray()
    }

    // ── Public API: asset issuance ────────────────────────────────────────────

    /**
     * Build and sign a Ravencoin asset issuance transaction (on-device, no backend).
     *
     * Handles root assets, sub-assets, and unique tokens. The correct burn address
     * and issuance script marker ("rvnq"/"rvno") are chosen automatically based on
     * whether the asset name contains '/' (sub-asset) or '#' (unique token).
     *
     * Output order (Ravencoin consensus, assets.cpp):
     *   1. Burn output: [burnSat] RVN to the canonical issuance burn address
     *   2. RVN change back to [changeAddress] (omitted if below dust limit)
     *   3. Parent owner-token return (rvnt): present for sub-assets and unique tokens;
     *      returns the spent "PARENT!" owner UTXO to the issuer so future child
     *      issuances remain possible.
     *   4. New owner-token output (rvno "ASSETNAME!"): required for root and
     *      sub-assets; omitted for unique tokens.
     *   5. Issuance output (rvnq): always last (consensus requirement).
     *
     * IPFS metadata: if [ipfsHash] is provided as a CIDv0 ("Qm..."), it is decoded
     * to a 34-byte sha2-256 multihash and embedded in the issuance payload.
     *
     * @param utxos          RVN UTXOs (must cover [burnSat] + [feeSat])
     * @param ownerAssetUtxos Owner-token UTXOs for sub-asset/unique issuance (empty for root)
     * @param assetName      Full asset name: "ROOT", "ROOT/SUB", or "ROOT/SUB#UNIQUE"
     * @param qtyRaw         Asset quantity in native units (qty * 10^[units])
     * @param toAddress      Address that receives the newly-issued asset
     * @param changeAddress  Address that receives any RVN change
     * @param units          Divisibility 0-8
     * @param reissuable     Whether more supply can be issued later
     * @param ipfsHash       Optional CIDv0 base58 IPFS hash ("Qm...") for metadata
     * @param burnSat        RVN to burn: use BURN_ROOT_SAT / BURN_SUB_SAT / BURN_UNIQUE_SAT
     * @param feeSat         Miner fee in satoshis
     * @param privKeyBytes   Raw 32-byte private key
     * @param pubKeyBytes    Compressed 33-byte public key
     * @return [SignedTx] ready to broadcast
     */
    fun buildAndSignAssetIssue(
        utxos: List<Utxo>,
        ownerAssetUtxos: List<Utxo> = emptyList(),
        assetName: String,
        qtyRaw: Long,
        toAddress: String,
        changeAddress: String,
        units: Int = 0,
        reissuable: Boolean = false,
        ipfsHash: String? = null,
        burnSat: Long,
        feeSat: Long,
        privKeyBytes: ByteArray,
        pubKeyBytes: ByteArray
    ): SignedTx {
        // Unique tokens (containing '#') don't mint a new owner output.
        // Sub-assets and unique tokens still spend the parent owner token as an input,
        // then return it to the issuer as an rvnt transfer of "PARENT!" with raw amount 100_000_000.
        // Root and sub-assets mint a new owner output ("ASSETNAME!") at vout[size-2].
        // Ravencoin asset issuance and owner-related outputs carry zero RVN; ownership lives in the payload.
        val isUnique = assetName.contains('#')

        // Determine the name of the parent owner token that must be returned to the issuer.
        // For "BRAND/ITEM#SN001" the parent owner is "BRAND/ITEM!".
        // For "BRAND/ITEM" the parent owner is "BRAND!".
        // For a root asset there is no parent, so nothing to return.
        val preservedOwnerAssetName = when {
            assetName.contains('#') -> assetName.substringBefore('#') + "!"
            assetName.contains('/') -> assetName.substringBefore('/') + "!"
            else -> null
        }

        // 1 owner unit in Ravencoin raw asset units (owner tokens always have divisibility 8,
        // so 1 owner token = 100_000_000 raw units).
        val preservedOwnerAmount = 100_000_000L

        // Issuance and owner outputs carry 0 satoshis (asset value is in the script payload).
        val ownerDust = 0L
        val dustOut = 0L

        val totalIn = (utxos + ownerAssetUtxos).sumOf { it.satoshis }
        val required = burnSat + ownerDust + dustOut + feeSat
        require(totalIn >= required) {
            "Insufficient RVN: have ${"%.4f".format(totalIn / 1e8)} RVN, need ${"%.4f".format(required / 1e8)} RVN"
        }
        if (preservedOwnerAssetName != null) {
            require(ownerAssetUtxos.isNotEmpty()) {
                "Missing owner asset input for $assetName: require $preservedOwnerAssetName"
            }
        }
        val changeSat = totalIn - burnSat - ownerDust - dustOut - feeSat

        // Select burn address based on asset type (Ravencoin consensus requirement)
        val burnAddress = when {
            assetName.contains('#') -> BURN_ADDRESS_UNIQUE  // Unique asset
            assetName.contains('/') -> BURN_ADDRESS_SUB     // Sub-asset
            else -> BURN_ADDRESS_ROOT                        // Root asset
        }
        val burnScript = p2pkhScript(burnAddress)
        val issueScript = buildAssetIssueScript(toAddress, assetName, qtyRaw, units, reissuable, ipfsHash)

        // Ravencoin consensus (assets.cpp):
        //   vout[size-1] = create output (rvnq) — always last
        //   vout[size-2] = owner token (rvno, "ASSETNAME!") — required for root/sub assets
        // Order: burn, change (if any), [parent owner token transfer], [new owner token], create output.
        val outputs = mutableListOf(ScriptedOutput(burnSat, burnScript))
        if (changeSat > 546) outputs.add(ScriptedOutput(changeSat, p2pkhScript(changeAddress)))

        // Return the spent parent owner token to the issuer for future child issuances.
        if (preservedOwnerAssetName != null) {
            val preservedOwnerScript = buildAssetTransferScript(
                changeAddress,
                preservedOwnerAssetName,
                preservedOwnerAmount
            )
            Log.i(
                "RavencoinTxBuilder",
                "owner-return asset=$preservedOwnerAssetName amountRaw=$preservedOwnerAmount script=${preservedOwnerScript.toHex()}"
            )
            outputs.add(ScriptedOutput(0L, preservedOwnerScript))
        }
        if (!isUnique) {
            // Root/sub issuance always mints the new owner token output.
            val ownerScript = buildOwnerTokenScript(toAddress, assetName)
            outputs.add(ScriptedOutput(ownerDust, ownerScript))
        }
        // Issuance output is always last (consensus rule).
        outputs.add(ScriptedOutput(dustOut, issueScript))

        val allInputs = utxos + ownerAssetUtxos
        val signatures = allInputs.mapIndexed { idx, utxo ->
            val sigHash = sigHashWithScriptedOutputs(allInputs, outputs, idx, utxo.script)
            signEcdsa(sigHash, privKeyBytes)
        }
        val raw = serializeTxWithScripts(allInputs, outputs, signatures, pubKeyBytes)
        return SignedTx(raw.toHex(), txid(raw))
    }

    // ── Asset script builders ─────────────────────────────────────────────────

    /**
     * Build the OP_RVN_ASSET scriptPubKey for a new-asset issuance output.
     *
     * Script structure:
     *   OP_DUP OP_HASH160 <20-byte hash160> OP_EQUALVERIFY OP_CHECKSIG
     *   OP_RVN_ASSET (0xc0) <push> <payload> OP_DROP (0x75)
     *
     * Payload ("rvnq" marker):
     *   "rvnq"                  4 bytes: new-asset issuance type marker
     *   compact_size(name_len)  1 byte for names < 253 chars
     *   name_bytes              ASCII asset name
     *   LE64(qtyRaw)            8 bytes: asset quantity in raw units
     *   units                   1 byte: divisibility (0-8)
     *   reissuable              1 byte: 0x00 or 0x01
     *   has_ipfs                1 byte: 0 or 1
     *   [ipfs_multihash]        34 bytes if has_ipfs = 1 (0x12 0x20 + 32-byte sha256)
     *
     * The push opcode before the payload varies based on payload size:
     *   <= 75 bytes: 1-byte direct push
     *   <= 255 bytes: OP_PUSHDATA1 (0x4c) + 1-byte length
     *   otherwise: OP_PUSHDATA2 (0x4d) + 2-byte LE length
     */
    private fun buildAssetIssueScript(
        address: String,
        assetName: String,
        qtyRaw: Long,
        units: Int,
        reissuable: Boolean,
        ipfsHash: String?
    ): ByteArray {
        val decoded = base58Decode(address)
        // Bytes 1..20 are the 20-byte HASH160 of the public key (skip the 1-byte version prefix)
        val hash160 = decoded.copyOfRange(1, 21)
        val nameBytes = assetName.toByteArray(Charsets.US_ASCII)
        // Decode CIDv0 IPFS hash to 34 raw bytes (multihash: 0x12 + 0x20 + 32-byte sha256)
        val ipfsBytes = ipfsHash?.let { decodeIpfsCidV0(it) }

        val payload = ByteArrayOutputStream()
        // New asset issuance payload marker: "rvnq" in ASCII
        payload.write(byteArrayOf(0x72, 0x76, 0x6e, 0x71)) // "rvnq"
        payload.write(nameBytes.size)                        // compact_size name length
        payload.write(nameBytes)
        payload.writeLE64(qtyRaw)                            // 8-byte LE quantity
        payload.write(units)                                 // divisibility byte
        payload.write(if (reissuable) 1 else 0)              // reissuable flag

        // IPFS: has_ipfs flag (1 byte) followed by 34-byte multihash if present
        if (ipfsBytes != null) {
            payload.write(1)           // has_ipfs = true
            payload.write(ipfsBytes)   // 34 bytes: 0x12 0x20 + sha256 hash
        } else {
            payload.write(0)           // has_ipfs = false
        }
        val payloadBytes = payload.toByteArray()

        // Assemble: P2PKH prefix, OP_RVN_ASSET (0xc0), push opcode, payload, OP_DROP (0x75)
        val buf = ByteArrayOutputStream()
        buf.write(byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte())) // OP_DUP OP_HASH160 PUSH20
        buf.write(hash160)
        buf.write(byteArrayOf(0x88.toByte(), 0xac.toByte(), 0xc0.toByte())) // OP_EQUALVERIFY OP_CHECKSIG OP_RVN_ASSET
        when {
            payloadBytes.size <= 75  -> { buf.write(payloadBytes.size); buf.write(payloadBytes) }
            payloadBytes.size <= 255 -> { buf.write(0x4c); buf.write(payloadBytes.size); buf.write(payloadBytes) }
            else -> { buf.write(0x4d)
                buf.write(payloadBytes.size and 0xff)
                buf.write((payloadBytes.size shr 8) and 0xff)
                buf.write(payloadBytes) }
        }
        buf.write(0x75) // OP_DROP
        return buf.toByteArray()
    }

    /**
     * Build the OP_RVN_ASSET scriptPubKey for the owner-token output ("ASSETNAME!").
     *
     * Required for root and sub-asset issuance. This output must occupy vout[size-2]
     * (second-to-last) per Ravencoin consensus (assets.cpp).
     *
     * Payload ("rvno" marker):
     *   "rvno"                  4 bytes: owner-token type marker
     *   compact_size(name_len)  1 byte
     *   name_bytes              ASCII owner token name (includes trailing "!")
     */
    private fun buildOwnerTokenScript(address: String, assetName: String): ByteArray {
        val decoded = base58Decode(address)
        val hash160 = decoded.copyOfRange(1, 21)
        // Owner token name always ends with "!" (e.g. "BRAND!" or "BRAND/ITEM!")
        val ownerName = "$assetName!"
        val nameBytes = ownerName.toByteArray(Charsets.US_ASCII)
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0x72, 0x76, 0x6e, 0x6f)) // "rvno"
        payload.write(nameBytes.size)
        payload.write(nameBytes)
        val payloadBytes = payload.toByteArray()
        val buf = ByteArrayOutputStream()
        buf.write(byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte())) // OP_DUP OP_HASH160 PUSH20
        buf.write(hash160)
        buf.write(byteArrayOf(0x88.toByte(), 0xac.toByte(), 0xc0.toByte())) // OP_EQUALVERIFY OP_CHECKSIG OP_RVN_ASSET
        when {
            payloadBytes.size <= 75  -> { buf.write(payloadBytes.size); buf.write(payloadBytes) }
            payloadBytes.size <= 255 -> { buf.write(0x4c); buf.write(payloadBytes.size); buf.write(payloadBytes) }
            else -> { buf.write(0x4d)
                buf.write(payloadBytes.size and 0xff); buf.write((payloadBytes.size shr 8) and 0xff)
                buf.write(payloadBytes) }
        }
        buf.write(0x75) // OP_DROP
        return buf.toByteArray()
    }

    /**
     * Decode a CIDv0 base58 IPFS hash ("Qm...") to its 34 raw bytes.
     *
     * CIDv0 is plain base58 (no version byte, no checksum) encoding a multihash.
     * A valid sha2-256 multihash is: 0x12 (sha2-256 code) + 0x20 (32 bytes) + 32-byte digest.
     *
     * This differs from Ravencoin Base58Check addresses, which include a version
     * byte and a 4-byte checksum.
     *
     * @param cid Base58-encoded CIDv0 string, must start with "Qm" and be 44-46 chars
     * @return 34 raw bytes: [0x12, 0x20, sha256_byte_0, ..., sha256_byte_31]
     * @throws IllegalArgumentException if the CID is malformed or decodes to wrong size
     */
    private fun decodeIpfsCidV0(cid: String): ByteArray {
        require(cid.startsWith("Qm") && cid.length in 44..46) { "Invalid CIDv0: $cid" }
        var num = java.math.BigInteger.ZERO
        for (c in cid) {
            val d = B58.indexOf(c)
            require(d >= 0) { "Invalid base58 char: $c" }
            num = num.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(d.toLong()))
        }
        // Drop a leading zero byte produced by BigInteger.toByteArray() if the value is positive
        val bytes = num.toByteArray().let { if (it[0] == 0.toByte()) it.drop(1).toByteArray() else it }
        require(bytes.size == 34 && bytes[0] == 0x12.toByte() && bytes[1] == 0x20.toByte()) {
            "CIDv0 decoded to ${bytes.size} bytes, expected 34 (multihash sha2-256)"
        }
        return bytes
    }

    // ── Asset transfer helpers ────────────────────────────────────────────────

    /**
     * Build the OP_RVN_ASSET scriptPubKey for an asset transfer output.
     *
     * Script structure:
     *   OP_DUP OP_HASH160 <20-byte hash160> OP_EQUALVERIFY OP_CHECKSIG
     *   OP_RVN_ASSET (0xc0) <push> <payload> OP_DROP (0x75)
     *
     * Payload ("rvnt" marker):
     *   "rvnt"                  4 bytes: asset-transfer type marker
     *   compact_size(name_len)  1 byte for names < 253 chars
     *   name_bytes              ASCII asset name
     *   LE64(assetAmount)       8 bytes: raw asset amount
     */
    private fun buildAssetTransferScript(address: String, assetName: String, assetAmount: Long): ByteArray {
        val decoded = base58Decode(address)
        val hash160 = decoded.copyOfRange(1, 21)
        val nameBytes = assetName.toByteArray(Charsets.US_ASCII)
        // Payload: "rvnt" + compact_size(len) + name + LE64(amount)
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0x72.toByte(), 0x76.toByte(), 0x6e.toByte(), 0x74.toByte())) // "rvnt"
        payload.write(nameBytes.size)  // compact size (1 byte for names < 253 chars)
        payload.write(nameBytes)
        payload.writeLE64(assetAmount) // raw asset quantity in little-endian
        val payloadBytes = payload.toByteArray()
        // Build full script: P2PKH header + OP_RVN_ASSET + push + payload + OP_DROP
        val buf = ByteArrayOutputStream()
        buf.write(byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14.toByte())) // OP_DUP OP_HASH160 PUSH20
        buf.write(hash160)
        buf.write(byteArrayOf(0x88.toByte(), 0xac.toByte(), 0xc0.toByte())) // OP_EQUALVERIFY OP_CHECKSIG OP_RVN_ASSET
        when {
            payloadBytes.size <= 75 -> { buf.write(payloadBytes.size); buf.write(payloadBytes) }
            payloadBytes.size <= 255 -> { buf.write(0x4c); buf.write(payloadBytes.size); buf.write(payloadBytes) }
            else -> throw IllegalArgumentException("Asset script payload too large")
        }
        buf.write(0x75)  // OP_DROP
        return buf.toByteArray()
    }

    /**
     * Compute the legacy P2PKH signature hash for input at [sigIdx] when all
     * outputs have pre-built scripts (used for asset transfer and issuance txs).
     *
     * Identical logic to [sigHashForInput] but accepts [ScriptedOutput] instead
     * of [TxOutput], so each output's script is written verbatim without
     * re-deriving it from an address.
     */
    private fun sigHashWithScriptedOutputs(
        inputs: List<Utxo>,
        outputs: List<ScriptedOutput>,
        sigIdx: Int,
        subscriptHex: String
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.writeLE32(VERSION)
        buf.writeVarInt(inputs.size)
        inputs.forEachIndexed { i, utxo ->
            buf.write(utxo.txid.hexToBytes().reversedArray()) // txid in internal byte order
            buf.writeLE32(utxo.outputIndex)
            if (i == sigIdx) {
                // Subscript for the input being signed (its own UTXO scriptPubKey)
                val sub = subscriptHex.hexToBytes()
                buf.writeVarInt(sub.size)
                buf.write(sub)
            } else {
                buf.writeVarInt(0) // empty scriptSig for all other inputs during signing
            }
            buf.writeLE32U(SEQUENCE)
        }
        buf.writeVarInt(outputs.size)
        outputs.forEach { out ->
            buf.writeLE64(out.satoshis)
            buf.writeVarInt(out.script.size)
            buf.write(out.script) // pre-built script written verbatim
        }
        buf.writeLE32(LOCKTIME.toInt())
        buf.writeLE32(SIGHASH_ALL)
        return doubleSha256(buf.toByteArray())
    }

    /**
     * Serialize a fully-signed transaction with pre-built output scripts into
     * raw bytes. Used for asset transfer and issuance transactions.
     *
     * Layout: version | input_count | inputs[] | output_count | outputs[] | locktime
     */
    private fun serializeTxWithScripts(
        inputs: List<Utxo>,
        outputs: List<ScriptedOutput>,
        signatures: List<ByteArray>,
        pubKey: ByteArray
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.writeLE32(VERSION)
        buf.writeVarInt(inputs.size)
        inputs.forEachIndexed { i, utxo ->
            buf.write(utxo.txid.hexToBytes().reversedArray()) // txid in internal byte order
            buf.writeLE32(utxo.outputIndex)
            val sig = signatures[i]
            // P2PKH scriptSig: push(sig) push(pubkey)
            val scriptSig = byteArrayOf(sig.size.toByte()) + sig + byteArrayOf(pubKey.size.toByte()) + pubKey
            buf.writeVarInt(scriptSig.size)
            buf.write(scriptSig)
            buf.writeLE32U(SEQUENCE)
        }
        buf.writeVarInt(outputs.size)
        outputs.forEach { out ->
            buf.writeLE64(out.satoshis)
            buf.writeVarInt(out.script.size)
            buf.write(out.script) // pre-built script written verbatim
        }
        buf.writeLE32(LOCKTIME.toInt())
        return buf.toByteArray()
    }

    // ── P2PKH script builder ──────────────────────────────────────────────────

    /**
     * Build a standard P2PKH (Pay-to-Public-Key-Hash) locking script from a
     * Ravencoin address.
     *
     * Script: OP_DUP OP_HASH160 <20-byte pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG
     *
     * The address is Base58Check decoded; bytes 1..20 are the HASH160 of the
     * public key (byte 0 is the Ravencoin version byte 0x3C and is skipped).
     */
    private fun p2pkhScript(address: String): ByteArray {
        val decoded = base58Decode(address)
        val pubKeyHash = decoded.copyOfRange(1, 21) // skip version byte 0x3C
        return byteArrayOf(
            0x76.toByte(), // OP_DUP
            0xa9.toByte(), // OP_HASH160
            0x14,          // push 20 bytes
            *pubKeyHash,
            0x88.toByte(), // OP_EQUALVERIFY
            0xac.toByte()  // OP_CHECKSIG
        )
    }

    // ── Transaction ID ────────────────────────────────────────────────────────

    /**
     * Compute the txid of a serialized transaction.
     *
     * txid = REVERSE(SHA256(SHA256(rawTx)))
     * The reversal converts from internal byte order to display/RPC byte order.
     */
    private fun txid(rawTx: ByteArray): String =
        doubleSha256(rawTx).reversedArray().toHex()

    // ── Crypto helpers ────────────────────────────────────────────────────────

    /**
     * Double-SHA256 hash used throughout Bitcoin/Ravencoin (txid, checksum, sighash).
     * SHA256(SHA256(data)) provides resistance against length-extension attacks.
     */
    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    // ── Serialization helpers (ByteArrayOutputStream extensions) ─────────────

    /** Write a 32-bit signed integer in little-endian byte order. */
    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    }

    /**
     * Write a 32-bit unsigned value (stored as Long to avoid Kotlin sign issues)
     * in little-endian byte order. Used for nSequence (0xFFFFFFFF).
     */
    private fun ByteArrayOutputStream.writeLE32U(v: Long) {
        write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    }

    /** Write a 64-bit value (satoshis or raw asset amount) in little-endian byte order. */
    private fun ByteArrayOutputStream.writeLE64(v: Long) {
        write(byteArrayOf(
            v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte(),
            (v shr 32).toByte(), (v shr 40).toByte(), (v shr 48).toByte(), (v shr 56).toByte()
        ))
    }

    /**
     * Write a Bitcoin/Ravencoin variable-length integer (VarInt / CompactSize).
     *
     * Encoding:
     *   0x00..0xFC: 1 byte
     *   0xFD..0xFFFF: 0xFD prefix + 2-byte LE value
     *   0x10000+: 0xFE prefix + 4-byte LE value
     */
    private fun ByteArrayOutputStream.writeVarInt(v: Int) {
        when {
            v < 0xfd -> write(v)
            v <= 0xffff -> { write(0xfd); write(v and 0xff); write((v shr 8) and 0xff) }
            else -> {
                write(0xfe)
                write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            }
        }
    }

    // ── Hex/Base58 helpers ────────────────────────────────────────────────────

    /**
     * Decode a hex string to a byte array.
     * Input length must be even; each pair of characters encodes one byte.
     */
    private fun String.hexToBytes(): ByteArray {
        val len = length
        val result = ByteArray(len / 2)
        for (i in 0 until len / 2) {
            result[i] = ((Character.digit(this[i * 2], 16) shl 4) +
                    Character.digit(this[i * 2 + 1], 16)).toByte()
        }
        return result
    }

    /** Encode a byte array to a lowercase hex string. */
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /**
     * Base58 alphabet used by both Ravencoin addresses and IPFS CIDv0.
     * Excludes visually ambiguous characters: 0 (zero), O, I, l.
     */
    private val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * Decode a Base58Check-encoded Ravencoin address to raw bytes.
     *
     * Steps:
     *   1. Decode base58 digits to a BigInteger.
     *   2. Re-encode leading '1' characters as zero bytes.
     *   3. Verify the 4-byte checksum: SHA256(SHA256(payload))[0..3] == last 4 bytes.
     *   4. Return the full decoded byte array (version byte + hash160 + checksum).
     *
     * @throws IllegalArgumentException on invalid characters or checksum mismatch
     */
    private fun base58Decode(input: String): ByteArray {
        var num = BigInteger.ZERO
        for (char in input) {
            val digit = B58.indexOf(char)
            require(digit >= 0) { "Invalid Base58 character: '$char' in address $input" }
            num = num.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = num.toByteArray()
        // '1' characters at the start of a Base58 string represent leading zero bytes
        val leadingZeros = input.takeWhile { it == '1' }.length
        val result = ByteArray(leadingZeros) + if (bytes[0] == 0.toByte()) bytes.drop(1).toByteArray() else bytes
        // Verify 4-byte checksum: SHA256(SHA256(payload))[0..3] must match the last 4 bytes
        require(result.size >= 5) { "Invalid address: decoded length ${result.size}, expected at least 5" }
        val payload = result.copyOf(result.size - 4)
        val checksum = result.copyOfRange(result.size - 4, result.size)
        val expected = doubleSha256(payload).copyOf(4)
        require(checksum.contentEquals(expected)) {
            "Invalid address: checksum mismatch for $input"
        }
        return result
    }
}
