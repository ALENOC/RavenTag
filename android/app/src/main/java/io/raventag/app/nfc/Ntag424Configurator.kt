package io.raventag.app.nfc

import android.util.Log
import android.nfc.Tag
import android.nfc.tech.IsoDep
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ntag424Configurator.kt
 *
 * Full NTAG 424 DNA chip programming for RavenTag SUN (Secure Unique NFC) operation.
 *
 * Implements the complete NXP NTAG 424 DNA command set via ISO 7816-4 wrapped APDUs:
 *  - ISOSelectFile: selects the NDEF application by its registered AID (D2760000850101)
 *  - AuthenticateEV2First: 3-pass AES-128 mutual authentication + session key derivation
 *  - WriteData: writes the NDEF record containing the SUN URL template with SDM placeholders
 *  - ChangeFileSettings: enables SDM (Secure Dynamic Messaging) with encrypted PICC data,
 *    ReadCtr mirror, and SDMMAC mirror using CommMode.FULL (encrypted + MACed settings)
 *  - ChangeKey: rotates application keys (0-3) to brand-specific values
 *
 * After configuration, each chip tap produces a fresh SUN URL:
 *   https://[domain]/verify?asset=BRAND/ITEM&e=[PICC_32hex]&m=[MAC_16hex]
 * where e= is AES-CBC-ENC(K_SDMMetaRead, IV=0, PICCData) and m= is the NXP-truncated CMAC.
 *
 * Reference: NXP Application Note AN12196, Rev. 2.0.
 */
class Ntag424Configurator {

    // ── Data classes ────────────────────────────────────────────────────────

    /**
     * Parameters required to configure a factory-fresh NTAG 424 DNA tag.
     *
     * @property baseUrl          Full URL prefix ending with "&", for example
     *                            "https://verify.raventag.com/verify?asset=X/Y001&".
     *                            The SDM placeholders e= and m= are appended after this.
     * @property newAppMasterKey  Optional new Key 0 (application master key). When provided,
     *                            it is changed last, after all other keys, to avoid locking
     *                            out the session that is still active under the old master key.
     * @property newSdmmacInputKey Key 1: reserved for future SDMCtrRet access or SDM input.
     * @property newSdmEncKey     Key 2 (K_SDMMetaRead): used by the chip to AES-CBC-encrypt
     *                            PICCData (UID + ReadCtr) into the e= URL parameter.
     * @property newSdmMacKey     Key 3 (K_SDMFileRead): base key for session MAC key derivation;
     *                            K_SesSDMFileReadMAC = CMAC(Key3, SV2_SDM).
     * @property currentMasterKey Current Key 0 before rotation. Factory default is all zeros.
     */
    data class WriteParams(
        val baseUrl: String,
        val newAppMasterKey: ByteArray? = null,
        val newSdmmacInputKey: ByteArray,
        val newSdmEncKey: ByteArray,
        val newSdmMacKey: ByteArray,
        val currentMasterKey: ByteArray = ByteArray(16)
    )

    /**
     * Successful result of a tag configuration.
     *
     * @property tagUid 7-byte chip UID as read from the Android NFC Tag object.
     */
    data class WriteResult(val tagUid: ByteArray)

    /**
     * Holds the two session keys and transaction identifier derived during AuthenticateEV2First.
     *
     * @property kmac    KSesAuthMAC: session MAC key derived from SV2 (0x5A 0xA5 prefix).
     *                   Used to authenticate all subsequent commands in this session.
     * @property kenc    KSesAuthENC: session encryption key derived from SV1 (0xA5 0x5A prefix).
     *                   Used to encrypt data payloads in CommMode.FULL commands.
     * @property ti      4-byte Transaction Identifier assigned by the chip during auth.
     *                   Included in every command MAC input to bind MACs to this session.
     * @property cmdCtr  Command counter, incremented after each authenticated command.
     *                   Prevents MAC replay within the same session.
     */
    private data class Session(
        val kmac: ByteArray,
        val kenc: ByteArray,
        val ti: ByteArray,
        var cmdCtr: Int = 0
    )

    /**
     * NDEF file byte content with the SDM URL template, plus the byte offsets that
     * tell the chip where to mirror its dynamic values at tap time.
     *
     * @property ndefFileBytes      Complete NDEF file content including the 2-byte NLEN header.
     * @property piccDataOffset     Byte offset within the NDEF file where the chip writes the
     *                              32 ASCII hex chars of AES-CBC-ENC(K_SDMMetaRead, UID+ReadCtr).
     * @property sdmmacInputOffset  Byte offset where the SDMMAC computation input starts.
     *                              Equal to sdmmacOffset for zero-length CMAC input.
     * @property sdmmacOffset       Byte offset within the NDEF file where the chip writes the
     *                              16 ASCII hex chars of the NXP-truncated SDMMAC.
     */
    private data class NdefOffsets(
        val ndefFileBytes: ByteArray,
        val piccDataOffset: Int,
        val sdmmacInputOffset: Int,
        val sdmmacOffset: Int
    )

    companion object {
        // NTAG 424 DNA NDEF application AID (D2760000850101)
        private val AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01
        )
        private const val NDEF_FILE_NO = 0x02.toByte()
        private val DEFAULT_KEY = ByteArray(16)

        // PICC data = AES-CBC-ENC(SDMEncKey, 0, PICCDataTag||UID[7]||ReadCtr[3]||Padding[5])
        //           → 16 bytes → 32 hex ASCII chars in URL
        private const val PICC_DATA_LEN = 32
        // SDMMAC = MACt(KSesSDMFileReadMAC, zero-length) → 8 bytes → 16 hex ASCII chars in URL
        private const val SDMMAC_LEN = 16
    }

    private val rng = SecureRandom()

    // ── Public API ──────────────────────────────────────────────────────────

    /** Read the 7-byte UID from tag without authentication. */
    fun readTagUid(tag: Tag): ByteArray? = tag.id?.takeIf { it.size == 7 }

    /**
     * Verifies that the tag is a writable NTAG 424 DNA before any on-chain issuance.
     * This is intentionally non-destructive: it only selects the application and
     * authenticates with the current master key (default on fresh tags).
     */
    fun verifyWritable(tag: Tag, currentMasterKey: ByteArray = DEFAULT_KEY): Result<ByteArray> {
        val isoDep = IsoDep.get(tag)
            ?: return Result.failure(Exception("Tag does not support ISO-DEP (not NTAG 424 DNA)"))

        return try {
            isoDep.connect()
            isoDep.timeout = 15_000
            val uid = tag.id ?: throw Exception("Cannot read tag UID")
            Log.i("Ntag424Configurator", "verifyWritable start uid=${uid.toHex()}")
            selectApplication(isoDep)
            authenticateEV2First(isoDep, 0x00, currentMasterKey)
            Log.i("Ntag424Configurator", "verifyWritable success uid=${uid.toHex()}")
            isoDep.close()
            Result.success(uid)
        } catch (e: Exception) {
            Log.e("Ntag424Configurator", "verifyWritable failed error=${e.message}", e)
            runCatching { isoDep.close() }
            Result.failure(e)
        }
    }

    /**
     * Configure a factory-fresh NTAG 424 DNA tag for SUN operation.
     *
     * Steps:
     * 1. Select NDEF application
     * 2. Authenticate with master key 0 (default = 0x00*16)
     * 3. Write NDEF with SDM URL template
     * 4. ChangeFileSettings to enable SDM (encrypted PICC data + ReadCtr + SDMMAC) , CommMode.FULL
     * 5. ChangeKey for keys 1, 2, 3
     *
     * @return WriteResult with tag UID, or failure with error description.
     */
    fun configure(tag: Tag, params: WriteParams): Result<WriteResult> {
        val isoDep = IsoDep.get(tag)
            ?: return Result.failure(Exception("Tag does not support ISO-DEP (not NTAG 424 DNA)"))

        return try {
            isoDep.connect()
            isoDep.timeout = 15_000

            val uid = tag.id ?: throw Exception("Cannot read tag UID")
            Log.i("Ntag424Configurator", "configure start uid=${uid.toHex()} baseUrl=${params.baseUrl}")

            // 1. Select NDEF application
            selectApplication(isoDep)
            Log.i("Ntag424Configurator", "configure selectApplication ok uid=${uid.toHex()}")

            // 2. Authenticate with Key 0
            val session = authenticateEV2First(isoDep, 0x00, params.currentMasterKey)
            Log.i("Ntag424Configurator", "configure authenticateEV2First ok uid=${uid.toHex()} ti=${session.ti.toHex()}")

            // 3. Build NDEF bytes with SDM placeholders and compute offsets
            val offsets = buildNdefWithPlaceholders(params.baseUrl)
            Log.i(
                "Ntag424Configurator",
                "configure ndef-built uid=${uid.toHex()} piccDataOffset=${offsets.piccDataOffset} sdmmacInputOffset=${offsets.sdmmacInputOffset} sdmmacOffset=${offsets.sdmmacOffset}"
            )

            // 4. Write NDEF file (plain CommMode , file write access = key 0, comm = plain)
            writeData(isoDep, session, NDEF_FILE_NO, 0, offsets.ndefFileBytes)
            Log.i("Ntag424Configurator", "configure writeData ok uid=${uid.toHex()}")

            // 5. Change File Settings (enable SDM) , CommMode.FULL per AN12196 §5.9
            changeFileSettings(
                isoDep, session,
                offsets.piccDataOffset,
                offsets.sdmmacInputOffset,
                offsets.sdmmacOffset
            )
            Log.i("Ntag424Configurator", "configure changeFileSettings ok uid=${uid.toHex()}")

            // 6. Change keys 1, 2, 3 (from factory default = zeros)
            changeKey(isoDep, session, 0x01, DEFAULT_KEY, params.newSdmmacInputKey)
            Log.i("Ntag424Configurator", "configure changeKey1 ok uid=${uid.toHex()}")
            changeKey(isoDep, session, 0x02, DEFAULT_KEY, params.newSdmEncKey)
            Log.i("Ntag424Configurator", "configure changeKey2 ok uid=${uid.toHex()}")
            changeKey(isoDep, session, 0x03, DEFAULT_KEY, params.newSdmMacKey)
            Log.i("Ntag424Configurator", "configure changeKey3 ok uid=${uid.toHex()}")
            params.newAppMasterKey?.let {
                changeKey(isoDep, session, 0x00, params.currentMasterKey, it)
                Log.i("Ntag424Configurator", "configure changeKey0 ok uid=${uid.toHex()}")
            }

            isoDep.close()
            Log.i("Ntag424Configurator", "configure success uid=${uid.toHex()}")
            Result.success(WriteResult(uid))
        } catch (e: Exception) {
            Log.e("Ntag424Configurator", "configure failed error=${e.message}", e)
            runCatching { isoDep.close() }
            Result.failure(e)
        }
    }

    // ── APDU Commands ────────────────────────────────────────────────────────

    /** ISO 7816-4 SELECT FILE for NTAG 424 DNA NDEF application. */
    private fun selectApplication(dep: IsoDep) {
        val apdu = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, AID.size.toByte()) +
                AID + byteArrayOf(0x00)
        val resp = dep.transceive(apdu)
        if (!isSw9000(resp)) throw Exception("SELECT APPLICATION failed: SW=${resp.swHex()}")
    }

    /**
     * AuthenticateEV2First , 3-pass AES-128 challenge-response per AN12196 Table 14.
     *
     * Pass 1: host → tag: [90 71 00 00 02 keyNo 00]
     *         tag → host: [ekRndB(16)] [91 AF]
     * Pass 2: host → tag: [90 AF 00 00 20] + E(K, IV=00..00, RndA||RndB')[32]
     *         tag → host: E(K, IV=00..00, TI||RndA'||PDcap2||PCDcap2)[32] [91 00]
     * Verify: decrypted[4..19] == rotateLeft(RndA, 1)
     *
     * Session key derivation (AN12196 Table 14 steps 25-28):
     *  svData = RndA[0:2] || XOR(RndA[2:8], RndB[0:6]) || RndB[6:16] || RndA[8:16]  (26 bytes)
     *  SV1 (→ KSesAuthENC) = 0xA5 0x5A 0x00 0x01 0x00 0x80 || svData
     *  SV2 (→ KSesAuthMAC) = 0x5A 0xA5 0x00 0x01 0x00 0x80 || svData
     */
    private fun authenticateEV2First(dep: IsoDep, keyNo: Int, key: ByteArray): Session {
        // Step 1
        val cmd1 = byteArrayOf(0x90.toByte(), 0x71, 0x00, 0x00, 0x02, keyNo.toByte(), 0x00, 0x00)
        val resp1 = dep.transceive(cmd1)
        if (resp1.size < 18 || !isSw91AF(resp1))
            throw Exception("AuthEV2First step 1 failed: ${resp1.toHex()}")

        val ekRndB = resp1.copyOfRange(0, 16)

        // Decrypt RndB: AES-CBC-DEC(key, IV=zeros, ekRndB)
        val rndB = aesDec(key, ByteArray(16), ekRndB)

        // Generate random RndA
        val rndA = ByteArray(16).also { rng.nextBytes(it) }

        // RndB' = RndB rotated left by 1 byte
        val rndBp = rndB.rotateLeft(1)

        // Step 2 uses AES-CBC with zero IV for the full 32-byte payload.
        val ekRndARndBp = aesEnc(key, ByteArray(16), rndA + rndBp)

        val cmd2 = byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x20) +
                ekRndARndBp + byteArrayOf(0x00)
        val resp2 = dep.transceive(cmd2)

        // Response: E(K, 00..00, TI||RndA'||PDcap2(6)||PCDcap2(6))[32] || [91 00]
        if (resp2.size < 34 || !isSw9100(resp2))
            throw Exception("AuthEV2First step 2 failed: ${resp2.toHex()}")

        val decResp = aesDec(key, ByteArray(16), resp2.copyOfRange(0, 32))

        val ti = decResp.copyOfRange(0, 4)
        val rndAp = decResp.copyOfRange(4, 20)
        // PDcap2 = decResp[20..25], PCDcap2 = decResp[26..31] (ignored)

        // Verify RndA' = RndA rotated left by 1
        if (!rndAp.contentEquals(rndA.rotateLeft(1)))
            throw Exception("AuthEV2First: RndA mismatch , wrong key or tampered tag")

        // Derive session keys , AN12196 Table 14 steps 25-28
        // svData (26 bytes):
        //   RndA[0:2] || XOR(RndA[2:8], RndB[0:6]) || RndB[6:16] || RndA[8:16]
        val svData = rndA.copyOfRange(0, 2) +
                ByteArray(6) { i -> (rndA[i + 2].toInt() xor rndB[i].toInt()).toByte() } +
                rndB.copyOfRange(6, 16) +
                rndA.copyOfRange(8, 16)  // total = 2+6+10+8 = 26 bytes

        // SV1 (0xA5 0x5A prefix) → KSesAuthENC
        // SV2 (0x5A 0xA5 prefix) → KSesAuthMAC
        val sv1 = byteArrayOf(0xA5.toByte(), 0x5A, 0x00, 0x01, 0x00, 0x80.toByte()) + svData
        val sv2 = byteArrayOf(0x5A, 0xA5.toByte(), 0x00, 0x01, 0x00, 0x80.toByte()) + svData

        return Session(
            kenc = cmac(key, sv1),   // KSesAuthENC
            kmac = cmac(key, sv2),   // KSesAuthMAC
            ti = ti
        )
    }

    /**
     * WriteData , write to a standard data file.
     * CommMode: Plain (no trailing MAC for plain files).
     * APDU: [90 8D 00 00 Lc] [fileNo] [offset LE3] [length LE3] [data] [00]
     */
    private fun writeData(dep: IsoDep, session: Session, fileNo: Byte, offset: Int, data: ByteArray) {
        val header = byteArrayOf(fileNo) +
                le3(offset) + le3(data.size)
        val cmdData = header + data
        val apdu = byteArrayOf(0x90.toByte(), 0x8D.toByte(), 0x00, 0x00, cmdData.size.toByte()) +
                cmdData + byteArrayOf(0x00)
        val resp = dep.transceive(apdu)
        if (!isSw9100(resp)) throw Exception("WriteData failed: ${resp.swHex()}")
        session.cmdCtr++
    }

    /**
     * ChangeFileSettings for File 02 , enable SDM with full SUN configuration.
     * Uses CommMode.FULL per AN12196 §5.9: settings data is AES-encrypted, then MACed.
     *
     * SDM config written:
     *  FileOption   = 0x40  (SDM enabled, plain CommMode for reads)
     *  AccessRights = [0x00, 0xE0]  (RW=key0, Change=key0, Read=free, Write=key0)
     *  SDMOptions   = 0xC1  (UID mirror=1, ReadCtr mirror=1, ASCII encoding=1)
     *  SDMAccessRights = [0xF3, 0x23]
     *    SDMCtrRetPerm=key3, MetaReadPerm=key2, SDMFileReadPerm=key3
     *  PICCDataOffset (LE3), SDMMACInputOffset (LE3), SDMMACOffset (LE3)
     *  Note: SDMMACInputOffset == SDMMACOffset → zero-length CMAC input
     */
    private fun changeFileSettings(
        dep: IsoDep,
        session: Session,
        piccDataOffset: Int,
        sdmmacInputOffset: Int,
        sdmmacOffset: Int
    ) {
        // Plaintext settings block per AN12196 §5.9.
        // For NTAG 424 DNA File 02 (NDEF):
        //  FileOption   = 0x40 (SDM enabled, CommMode.Plain for the file itself)
        //  AccessRights = 0x00 0xE0 (RW=0, Change=0, Read=E(free), Write=0)
        //  SDMOptions   = 0xC1 (UID mirror=1, ReadCtr mirror=1, ASCII=1)
        //  SDMAccessRights = 0xF3 0x23
        //     - SDMCtrRet: Key 3
        //     - MetaRead (UID/Ctr encryption): Key 2 (sdmEncKey)
        //     - SDMFileRead / SDMMAC base: Key 3 (sdmMacKey)
        //
        // Offsets are 3-byte LE. Total settings = 1 + 2 + 1 + 2 + 3 + 3 + 3 = 15 bytes.
        val settingsPlain = byteArrayOf(
            0x40,
            0x00, 0xE0.toByte(),
            0xC1.toByte(),
            0xF3.toByte(), 0x23.toByte()
        ) + le3(piccDataOffset) + le3(sdmmacInputOffset) + le3(sdmmacOffset)

        // CommMode.FULL: settings data is AES-encrypted, then MACed.
        val padded = nxpPad(settingsPlain)         // 15 -> 16 bytes
        val encIV = sessionEncIV(session)
        val encData = aesEnc(session.kenc, encIV, padded)

        // MAC input: Header(0x5F) + FileNo + encData
        val macPayload = byteArrayOf(NDEF_FILE_NO) + encData
        val mac = computeCmdMac(session, 0x5F, macPayload)
        val fullData = byteArrayOf(NDEF_FILE_NO) + encData + mac

        val apdu = byteArrayOf(0x90.toByte(), 0x5F, 0x00, 0x00, fullData.size.toByte()) +
                fullData + byteArrayOf(0x00)
        val resp = dep.transceive(apdu)
        if (!isSw9100(resp)) throw Exception("ChangeFileSettings failed: ${resp.swHex()}")
        session.cmdCtr++
    }

    /**
     * ChangeKey in secure messaging.
     * Case 1 (keyNo != authenticated key): XOR(old,new) || KeyVersion || JamCRC32(new) || NXP-pad
     * Case 2 (keyNo == authenticated key): newKey || KeyVersion || NXP-pad
     *
     * @param keyNo   Key number to change (1, 2, or 3)
     * @param oldKey  Current key value (factory default = zeros)
     * @param newKey  New key value
     */
    private fun changeKey(dep: IsoDep, session: Session, keyNo: Int, oldKey: ByteArray, newKey: ByteArray) {
        val keyVersion = byteArrayOf(0x00) // key version = 0
        val plainData = if (keyNo == 0) {
            newKey + keyVersion
        } else {
            val xorKey = ByteArray(16) { i -> (newKey[i].toInt() xor oldKey[i].toInt()).toByte() }
            val crc = computeJamCrc32(newKey)
            xorKey + keyVersion + crc
        }
        val padded = nxpPad(plainData)

        val encIV = sessionEncIV(session)
        val encKey = aesEnc(session.kenc, encIV, padded)

        val cmdPayload = byteArrayOf(keyNo.toByte()) + encKey
        val mac = computeCmdMac(session, 0xC4.toByte().toInt(), cmdPayload)
        val fullData = cmdPayload + mac

        val apdu = byteArrayOf(0x90.toByte(), 0xC4.toByte(), 0x00, 0x00, fullData.size.toByte()) +
                fullData + byteArrayOf(0x00)
        val resp = dep.transceive(apdu)
        if (!isSw9100(resp)) throw Exception("ChangeKey($keyNo) failed: ${resp.swHex()}")
        session.cmdCtr++
    }

    // ── NDEF Builder ─────────────────────────────────────────────────────────

    /**
     * Build NDEF file content with ASCII hex placeholder positions for SDM mirroring.
     *
     * URL format: [baseUrl]e=[00*32]&m=[00*8]
     * The NTAG 424 DNA replaces the placeholder characters at runtime with:
     *  - e= : 32 ASCII hex chars of AES-CBC-ENC(K_SDMMetaRead, 0, PICCData)
     *  - m= : 16 ASCII hex chars of MACt(K_SesSDMFileReadMAC, []) , zero-length input
     *
     * SDMMACInputOffset == SDMMACOffset (zero-length CMAC input) per standard NXP SUN config.
     * The session key K_SesSDMFileReadMAC = CMAC(K_SDMFileRead, SV2_SDM) already
     * incorporates UID and ReadCtr, providing freshness without additional CMAC input.
     */
    private fun buildNdefWithPlaceholders(baseUrl: String): NdefOffsets {
        // Strip https:// since it is encoded as URI prefix byte 0x04
        val urlNoScheme = baseUrl.removePrefix("https://")
        val urlPath = "${urlNoScheme}e=${"0".repeat(PICC_DATA_LEN)}&m=${"0".repeat(SDMMAC_LEN)}"
        val urlBytes = urlPath.toByteArray(Charsets.UTF_8)

        // NDEF URI record payload: [0x04 (https://)] + urlBytes
        val payload = byteArrayOf(0x04) + urlBytes
        val payloadLen = payload.size

        // NDEF record (short record, well-known, URI type)
        val ndefMsg = byteArrayOf(
            0xD1.toByte(),           // record header: MB=1 ME=1 SR=1 TNF=001
            0x01,                    // type length = 1
            payloadLen.toByte(),     // payload length (short record: 1 byte, max 255)
            0x55                     // type = 'U' (URI)
        ) + payload

        // NDEF file: [NLEN high] [NLEN low] [NDEF message]
        val nlenHigh = ((ndefMsg.size shr 8) and 0xFF).toByte()
        val nlenLow = (ndefMsg.size and 0xFF).toByte()
        val ndefFile = byteArrayOf(nlenHigh, nlenLow) + ndefMsg

        // Compute offset of PICC data within NDEF file:
        // 2 (NLEN) + 1 (header) + 1 (typeLen) + 1 (payloadLen) + 1 (type) + 1 (0x04 prefix) + urlNoScheme.length + "e=".length
        val piccDataOffset = 2 + 1 + 1 + 1 + 1 + 1 + urlNoScheme.length + 2

        // SDMMACOffset: after the 32 PICC hex chars and "&m="
        val sdmmacOffset = piccDataOffset + PICC_DATA_LEN + 3  // 3 = len("&m=")

        // SDMMACInputOffset == SDMMACOffset (zero-length CMAC input, session key provides freshness)
        val sdmmacInputOffset = sdmmacOffset

        return NdefOffsets(ndefFile, piccDataOffset, sdmmacInputOffset, sdmmacOffset)
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    /**
     * Compute command MAC: MACt(Kmac, INS || CmdCtr_LE16 || TI || cmdData).
     * MACt keeps bytes S14||S12||...||S0 from the full CMAC per AN12196.
     */
    private fun computeCmdMac(session: Session, ins: Int, cmdData: ByteArray): ByteArray {
        val macInput = byteArrayOf(
            ins.toByte(),
            (session.cmdCtr and 0xFF).toByte(),
            ((session.cmdCtr shr 8) and 0xFF).toByte()
        ) + session.ti + cmdData
        return truncateMac(cmac(session.kmac, macInput))
    }

    /**
     * Session encryption IV (IVc):
     * IV = AES_ENC(Kenc, zeros, [0xA5, 0x5A, TI[4], CmdCtr_LE16, zeros_8])
     * Per AN12196 §4.4 Figure 10.
     */
    private fun sessionEncIV(session: Session): ByteArray {
        val ivInput = byteArrayOf(0xA5.toByte(), 0x5A) +
                session.ti +
                byteArrayOf(
                    (session.cmdCtr and 0xFF).toByte(),
                    ((session.cmdCtr shr 8) and 0xFF).toByte()
                ) + ByteArray(8)  // zero padding to 16 bytes total
        return aesEnc(session.kenc, ByteArray(16), ivInput)
    }

    /**
     * NXP byte-stuffing padding: append 0x80, then zero bytes to next 16-byte boundary.
     * If already aligned, append 0x80 + 15 zeros (full padding block).
     * Per AN12196 §6.3.
     */
    private fun nxpPad(data: ByteArray): ByteArray {
        val rem = data.size % 16
        val padLen = if (rem == 0) 16 else 16 - rem
        return data + byteArrayOf(0x80.toByte()) + ByteArray(padLen - 1)
    }

    /**
     * JamCRC32 in little-endian, as used by NTAG 424 ChangeKey examples.
     */
    private fun computeJamCrc32(data: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(data)
        val value = crc.value xor 0xFFFF_FFFFL
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    /**
     * MACt = S14 || S12 || S10 || S8 || S6 || S4 || S2 || S0.
     */
    private fun truncateMac(fullCmac: ByteArray): ByteArray =
        ByteArray(8) { i -> fullCmac[i * 2 + 1] }

    /** AES-128-CBC encrypt (no padding , input must be multiple of 16 bytes). */
    private fun aesEnc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** AES-128-CBC decrypt (no padding). */
    private fun aesDec(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** AES-CMAC (RFC 4493) via BouncyCastle. */
    private fun cmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = CMac(AESEngine())
        mac.init(KeyParameter(key))
        mac.update(message, 0, message.size)
        return ByteArray(16).also { mac.doFinal(it, 0) }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Rotate byte array left by n positions. */
    private fun ByteArray.rotateLeft(n: Int): ByteArray {
        val pos = n % size
        return copyOfRange(pos, size) + copyOfRange(0, pos)
    }

    /** 3-byte little-endian encoding. */
    private fun le3(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte()
    )

    /** Check ISO 7816 SW = 0x9000. */
    private fun isSw9000(resp: ByteArray) =
        resp.size >= 2 && resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()

    /** Check NTAG 424 SW = 0x9100 (success). */
    private fun isSw9100(resp: ByteArray) =
        resp.size >= 2 && resp[resp.size - 2] == 0x91.toByte() && resp[resp.size - 1] == 0x00.toByte()

    /** Check NTAG 424 SW = 0x91AF (more frames). */
    private fun isSw91AF(resp: ByteArray) =
        resp.size >= 2 && resp[resp.size - 2] == 0x91.toByte() && resp[resp.size - 1] == 0xAF.toByte()

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    private fun ByteArray.swHex() = if (size >= 2) takeLast(2).joinToString("") { "%02X".format(it) } else "??"
}
