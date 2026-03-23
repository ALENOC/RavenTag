# RavenTag Protocol Specification, RTP-1

## Overview

RTP-1 (RavenTag Protocol version 1) defines the standard for linking NTAG 424 DNA NFC tags to Ravencoin assets in a trustless, verifiable manner.

**Version:** 1.1  
**Effective Date:** March 2026  
**Reference Implementation:** https://github.com/ALENOC/RavenTag

---

## 1. Tag Requirements

- **Chip**: NTAG 424 DNA (NXP Semiconductors)
- **Feature**: SUN (Secure Unique NFC) with SDM mirror enabled
- **Encryption**: AES-128 (hardware, non-extractable keys)
- **Counter**: 24-bit monotonic counter (anti-replay)
- **NDEF Record**: Contains SUN URL template with mirror placeholders

---

## 2. SUN Message Format

When scanned, NTAG 424 DNA generates a URL with embedded SUN data:

```
https://[domain]/verify?asset=[ASSET]&e=[ENC]&m=[MAC]
```

Where:
- `[domain]` = Brand's verification server domain (configured per brand)
- `[ASSET]` = Ravencoin asset name (e.g., `BRAND/PRODUCT#SERIAL`)
- `[ENC]` = 32 hex chars (16 bytes): AES-128-CBC encrypted PICCData
- `[MAC]` = 16 hex chars (8 bytes): Truncated CMAC tag

### 2.1 PICCData Plaintext Layout (AN12196 Table 2)

After decryption, the 16-byte plaintext contains:

```
Byte 0      : PICCDataTag = 0xC7 (full UID + counter block identifier)
Bytes 1-7   : UID (7 bytes for NTAG 424 DNA)
Bytes 8-10  : SDMReadCtr (3 bytes, little-endian counter)
Bytes 11-15 : Padding (unused)
```

### 2.2 Session MAC Key Derivation (AN12196 §3.3)

The session MAC key is derived per-tap using SV2:

```
SV2_SDM = 0x3C 0xC3 0x00 0x01 0x00 0x80 || UID[7] || Counter[3 LE]   (16 bytes)
K_SesSDMFileReadMAC = AES-CMAC(K_SDMFileRead, SV2_SDM)
```

Where:
- `0x3C 0xC3 0x00 0x01 0x00 0x80` = SV2 prefix for SDM MAC key
- `UID[7]` = 7-byte chip UID
- `Counter[3 LE]` = 24-bit counter in little-endian byte order

### 2.3 MAC Truncation (AN12196 Table 4)

The 16-byte CMAC output is truncated to 8 bytes:

```
Truncated_MAC[i] = Full_CMAC[i * 2 + 1]   for i = 0..7
```

This retains only the **odd-indexed bytes** (indices 1, 3, 5, 7, 9, 11, 13, 15).

---

## 3. Verification Flow (Trustless)

```
1. App reads NFC tag → gets SUN URL with e and m parameters
2. App sends {asset, e, m} to brand's verification backend
3. Backend looks up registered chip UID for this asset
4. Backend derives per-chip AES keys from BRAND_MASTER_KEY + UID:
   - sdmEncKey (Key 2) = AES128_ECB(masterKey, [0x02 || UID || 0x00...])
   - sdmMacKey (Key 3) = AES128_ECB(masterKey, [0x03 || UID || 0x00...])
5. Backend decrypts e using sdmEncKey:
   - AES-128-CBC with zero IV
   - Extracts UID and counter from plaintext
6. Backend verifies UID matches registered UID (anti-substitution)
7. Backend derives session MAC key from sdmMacKey + UID + counter
8. Backend verifies MAC: AES-CMAC(session_key, empty)[odd_bytes] == m
9. Backend checks counter > stored counter (anti-replay)
10. Backend fetches Ravencoin asset metadata from IPFS
11. Backend computes nfc_pub_id = SHA-256(UID || BRAND_SALT)
12. Backend compares nfc_pub_id with metadata
13. Backend checks revocation list (SQLite)
14. Returns: { authentic: true/false, revoked: false/true, reason }
```

**Note:** Steps 5-9 are performed server-side. The brand's backend derives all keys on-demand from the master key; keys are never stored per-chip.

---

## 4. Key Management

### 4.1 Per-Chip Key Derivation

All four per-chip AES-128 keys are derived from a single `BRAND_MASTER_KEY`:

```
For each key slot N (0, 1, 2, 3):

  Input_Block_N = [slot_N (1 byte)] || [UID (7 bytes)] || [0x00 × 8 bytes padding]
                  └───── 16 bytes total ─────┘

  Derived_Key_N = AES-128-ECB(BRAND_MASTER_KEY, Input_Block_N)
```

### 4.2 Key Roles

| Slot | Key Name | Role | Used For |
|------|----------|------|----------|
| 0x00 | appMasterKey | Application Master Key (Key 0) | NFC application authentication |
| 0x01 | sdmmacInputKey | SDM MAC Input Key (Key 1) | Reserved for future authenticated operations |
| 0x02 | sdmEncKey | SDM Encryption Key (Key 2) | Encrypts PICCData (UID + counter) → URL "e" parameter |
| 0x03 | sdmMacKey | SDM MAC Key (Key 3) | Base for session MAC key derivation → URL "m" parameter |

### 4.3 Security Properties

- **Master Key Protection**: `BRAND_MASTER_KEY` never leaves the brand's backend server
- **No Per-Chip Storage**: All chip keys are derived on-demand from UID + master key
- **Cryptographic Independence**: Each slot produces independent keys; compromise of one does not reveal others
- **One-Way Derivation**: Knowledge of a derived chip key does NOT reveal the master key

### 4.4 Salt for nfc_pub_id

A random 16-byte salt (`BRAND_SALT`) is used to compute the public identifier:

```
nfc_pub_id = SHA-256(tag_uid_bytes || salt_bytes)
```

The salt is:
- Generated once at brand setup
- Stored securely on the backend (never on-chain, never in IPFS metadata)
- Used to compute `nfc_pub_id` during chip registration and verification

This prevents UID correlation attacks while maintaining privacy.

---

## 5. Asset Metadata Schema (v1.1)

```json
{
  "raventag_version": "RTP-1",
  "parent_asset": "BRAND",
  "sub_asset": "BRAND/PRODUCT",
  "variant_asset": "SERIAL",
  "nfc_pub_id": "0f000a78d8936e1e35fd7fce8bbd9a7a837fa091ea5b4afeb4e74bf151548d8b",
  "crypto_type": "ntag424_sun",
  "algo": "aes-128",
  "image": "ipfs://QmWb2H2CCZ3yBhbnq5fEZ2ibFqaVjgFzaxKhLXLD63ELdD",
  "description": "Black Leather Bag"
}
```

### 5.1 Field Rules

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `raventag_version` | string | Yes | Protocol version, MUST be `"RTP-1"` |
| `parent_asset` | string | Yes | Root asset name (max 32 chars, uppercase + digits) |
| `sub_asset` | string | Yes | Full sub-asset path (e.g., `"BRAND/PRODUCT"`) |
| `variant_asset` | string | Only for unique tokens | Serial identifier (e.g., `"SN0001"`) |
| `nfc_pub_id` | string | Yes | 64-char lowercase hex: `SHA-256(UID \|\| BRAND_SALT)` |
| `crypto_type` | string | Yes | MUST be `"ntag424_sun"` for NTAG 424 DNA |
| `algo` | string | Yes | MUST be `"aes-128"` |
| `image` | string | No | IPFS URI of product image (`"ipfs://<CID>"`) |
| `description` | string | No | Product description |

### 5.2 Immutability Note

**IPFS metadata is immutable.** Once uploaded, the metadata cannot be changed. The `status` field (if present) reflects only the state at issuance time.

For revocation, use the **backend revocation API** (`POST /api/brand/revoke`), which maintains a real-time SQLite revocation table checked during verification.

---

## 6. Asset Hierarchy and Naming

### 6.1 Three-Level Hierarchy

Ravencoin supports a three-level asset hierarchy. RavenTag uses all three levels:

```
FASHIONX                       Root asset      500 RVN   (brand identity)
FASHIONX/BAG001                Sub-asset       100 RVN   (product line)
FASHIONX/BAG001#SN0001         Unique token      5 RVN   (individual item)
```

### 6.2 Naming Rules

- **Characters**: Uppercase letters (A-Z), digits (0-9) only
- **Maximum length**: 32 characters per level
- **Separators**: 
  - `/` separates parent from sub-asset (handled by Ravencoin protocol)
  - `#` separates sub-asset from variant (unique token marker)

### 6.3 Examples

```
LUXURY_BRAND                   ← Root (brand)
LUXURY_BRAND/LUXURY_BAG        ← Sub-asset (product line)
LUXURY_BRAND/LUXURY_BAG#SN0001 ← Unique token (serialized item)
```

---

## 7. On-Chain Registration Flow

### 7.1 Brand Setup

1. Generate `BRAND_MASTER_KEY` (16 bytes AES-128, stored as 32 hex chars)
2. Generate `BRAND_SALT` (16 bytes random, stored as 32 hex chars)
3. Store both securely on backend (never commit to version control)

### 7.2 Asset Issuance

1. **Issue Root Asset** (500 RVN fee):
   ```
   POST /api/brand/issue
   { "asset_name": "BRAND", "qty": 1, "reissuable": false }
   ```

2. **Issue Sub-Asset** (100 RVN fee):
   ```
   POST /api/brand/issue-sub
   { "parent_asset": "BRAND", "child_name": "PRODUCT", "qty": 1 }
   ```

3. **Issue Unique Token** (5 RVN fee):
   ```
   POST /api/brand/issue-unique
   { "parent_sub_asset": "BRAND/PRODUCT", "asset_tags": ["SN0001"] }
   ```

### 7.3 Chip Programming

1. Tap NTAG 424 DNA chip, read 7-byte UID
2. Call `POST /api/brand/derive-chip-key` to derive per-chip keys
3. Upload RTP-1 metadata JSON to IPFS (via Pinata or Kubo), get CID
4. Write to chip via ISO 7816-4 APDUs:
   - Set Application Master Key (Key 0)
   - Set SDM keys (Keys 1, 2, 3)
   - Configure SDM mirror with SUN URL template
   - Write NDEF record with SUN URL

### 7.4 Chip Registration

```
POST /api/brand/register-chip
{
  "asset_name": "BRAND/PRODUCT#SN0001",
  "tag_uid": "04A1B2C3D4E5F6"
}
```

Backend computes `nfc_pub_id = SHA-256(UID || BRAND_SALT)` and stores in SQLite `chip_registry` table.

---

## 8. Replay Attack Prevention

The NTAG 424 DNA counter increments on every tap (24-bit, monotonic).

### 8.1 Server-Side Counter Cache

The backend maintains a counter cache per `nfc_pub_id`:

```sql
CREATE TABLE nfc_counter_cache (
  nfc_pub_id TEXT PRIMARY KEY,
  last_counter INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
)
```

### 8.2 Verification Check

During verification:
```
if (counter <= stored_counter) {
  return { authentic: false, step_failed: 'counter_replay' }
}
update_counter_cache(nfc_pub_id, counter)
```

This prevents replay attacks using captured SUN URLs.

---

## 9. Revocation Mechanism

### 9.1 Backend Revocation (Reversible)

```
POST /api/brand/revoke
{
  "asset_name": "BRAND/PRODUCT#SN0001",
  "reason": "Counterfeit detected"
}
```

- Stored in SQLite `revocations` table
- Checked during verification (before IPFS metadata)
- Can be reversed via `DELETE /api/brand/revoke/:assetName`

### 9.2 On-Chain Burn (Irreversible)

Transfer asset to Ravencoin burn address:
```
RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV
```

- Permanent and irreversible
- Used only for confirmed counterfeits or destroyed items

### 9.3 Verification Priority

During verification, checks are performed in this order:
1. SUN MAC verification (cryptographic authenticity)
2. UID match (anti-substitution)
3. Counter replay check
4. **Backend revocation list** (SQLite)
5. IPFS metadata `nfc_pub_id` match

---

## 10. Privacy Considerations

- **Raw UID never on-chain**: Only `nfc_pub_id = SHA-256(UID || BRAND_SALT)` appears in IPFS metadata
- **Salt never on-chain**: `BRAND_SALT` is stored only on the brand's backend
- **Unlinkable**: Without the salt, observers cannot correlate different `nfc_pub_id` values to the same physical chip
- **Counter values not stored**: Only the last seen counter is cached server-side for replay detection

---

## 11. API Endpoints

### 11.1 Public Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/health` | Health check |
| `POST` | `/api/verify/tag` | Full verification (SUN + blockchain + revocation) |
| `GET` | `/api/assets/:name/revocation` | Check revocation status |

### 11.2 Operator Endpoints (X-Operator-Key)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/brand/register-chip` | Register chip UID |
| `GET` | `/api/brand/chips` | List all chips |
| `GET` | `/api/brand/chip/:assetName` | Get specific chip |

### 11.3 Admin Endpoints (X-Admin-Key)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/brand/revoke` | Revoke asset |
| `DELETE` | `/api/brand/revoke/:name` | Un-revoke asset |
| `GET` | `/api/brand/revoked` | List revoked assets |
| `POST` | `/api/brand/derive-chip-key` | Derive chip AES keys |

---

## 12. Security Architecture

RavenTag provides four layers of security:

| Layer | Technology | Attacker Must Compromise |
|-------|-----------|-------------------------|
| **1. Ravencoin Consensus** | Parent asset ownership verified by every node | Own the parent Ravencoin asset |
| **2. nfc_pub_id Binding** | SHA-256(UID \|\| BRAND_SALT) in IPFS metadata | Know `BRAND_SALT` (never public) |
| **3. AES Key Derivation** | AES-ECB(slot \|\| UID) per-chip | Know `BRAND_MASTER_KEY` (never leaves backend) |
| **4. NTAG 424 DNA Silicon** | Non-clonable NXP hardware | Physically clone NXP silicon (impossible) |

---

## 13. Version History

| Version | Date | Changes |
|---------|------|---------|
| RTP-1 v1.0 | 2026-03 | Initial specification |
| RTP-1 v1.1 | 2026-03 | Corrected metadata schema (removed `version`, `status`, `attributes`; added `image`, `description`; clarified `sub_asset` format) |

---

## References

- [NXP AN12196](https://www.nxp.com/docs/en/application-note/AN12196.pdf) - NTAG 424 DNA features and hints
- [NXP AN12413](https://www.nxp.com/docs/en/application-note/AN12413.pdf) - NTAG 424 DNA configuration guide
- [RFC 4493](https://tools.ietf.org/html/rfc4493) - AES-CMAC Algorithm
- [Ravencoin Documentation](https://ravencoin.org) - Asset protocol specification
- [IPFS Specification](https://ipfs.tech) - InterPlanetary File System

---

**Copyright 2026 Alessandro Nocentini. All rights reserved.**  
**Licensed under RavenTag Source License (RTSL-1.0).**
