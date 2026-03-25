# RavenTag Functional Report

## 1. Executive Summary

RavenTag is an open-source, brand-sovereign authentication framework that links physical NFC tags to Ravencoin blockchain assets. It enables brands to prove the authenticity of physical products by embedding NTAG 424 DNA chips that generate cryptographically signed messages on every tap, verified server-side against on-chain asset metadata.

The system is trustless by design: end users do not need to trust RavenTag's infrastructure, only the brand's own server and the public Ravencoin blockchain. No central authority can forge or revoke tokens outside of the brand's own actions.

Protocol: **RTP-1** (RavenTag Protocol v1, current version v1.1)

---

## 2. Architecture Overview

### Brand-Sovereign Model

Each brand runs its own verification backend. The brand holds and never shares:
- `BRAND_MASTER_KEY`: AES-128 master key for per-chip key derivation
- `BRAND_SALT`: 32-byte random salt for nfc_pub_id computation

No key material ever leaves the brand's infrastructure. The Android apps receive only verification results (authentic / revoked / invalid MAC), not raw keys.

### Key Components

| Component | Technology | Role |
|-----------|-----------|------|
| Backend | Node.js 20, Express 4, TypeScript | SUN verification, Ravencoin RPC proxy, revocation DB |
| Android Apps | Kotlin 1.9, Jetpack Compose, BouncyCastle | Native NFC scan, chip programming, HD wallet |
| Database | SQLite (better-sqlite3) | Asset revocation, chip registration cache |
| Blockchain | Ravencoin (RVN) | Immutable asset registry, token ownership |
| Metadata | IPFS (Pinata, uploaded by Android app) | Off-chain JSON with nfc_pub_id, serial, attributes |

---

## 3. Verification Flow

### Step-by-step

1. **Chip tap**: The user taps their Android phone to the NTAG 424 DNA chip embedded in the product.

2. **SUN generation (on-chip)**: The chip computes, in hardware:
   - `e = AES-128-CBC(SDMEncKey, IV=0, UID || counter)` encoded as hex ASCII
   - `m = Truncated_CMAC(SessionMACKey, empty_input)[odd_bytes]` (8 bytes = 16 hex chars)
   - The read counter increments atomically on every tap.

3. **App verification**: The Android app sends `{asset, e, m}` to the brand's backend via `POST /api/verify/tag`.

4. **Backend verification**:
   - Derives per-chip AES keys from `BRAND_MASTER_KEY` + registered UID
   - Decrypts `e` to recover UID and counter
   - Verifies UID matches registered UID (anti-substitution)
   - Derives session MAC key from sdmMacKey + UID + counter
   - Verifies `m` matches the recomputed truncated CMAC
   - Queries the Ravencoin node for asset metadata
   - Fetches IPFS JSON and compares `nfc_pub_id` with `SHA-256(uid || BRAND_SALT)`
   - Checks the backend revocation table
   - Checks counter > last-seen counter (anti-replay)

5. **Result**: The Android app displays AUTHENTIC, REVOKED, or INVALID MAC with asset details.

### Trust Model

- The brand's server is trusted for MAC verification (brand-sovereign).
- The Ravencoin blockchain is trusted for asset existence and ownership (public, immutable).
- The chip hardware is trusted for key non-extractability (NTAG 424 DNA, NXP certified).
- RavenTag infrastructure is NOT trusted: the system works without RavenTag if the brand self-hosts.

---

## 4. NTAG 424 DNA SUN Background

SUN (Secure Unique NFC) is an NXP standard for NTAG 424 DNA chips. On each read:
- The chip's internal AES-128 engine encrypts a combination of chip UID and a monotonic read counter.
- A CMAC is computed over the encrypted data using a second chip AES key.
- Both values are appended to the chip's programmed URL as `e` and `m` parameters.

This makes every tap unique: replay attacks fail because the counter was already seen, and cloning attacks fail because the AES keys are fused in silicon and cannot be read out.

---

## 5. Wallet Management

All on-chain operations (asset issuance, transfers, RVN sends) are performed directly from the Android app using a BIP44 HD wallet (derivation path `m/44'/175'/0'/0/0`, coin type 175 for Ravencoin). The wallet seed is stored encrypted in Android Keystore using AES-256-GCM. Transactions are built and signed on-device and broadcast via ElectrumX public servers (port 50002 TLS), with no dependency on a full Ravencoin node or backend.

Key Android wallet operations:
- `getBalance`: UTXO balance query via ElectrumX `blockchain.scripthash.get_balance`
- `issueAssetLocal`: build, sign, and broadcast root/sub/unique asset issuance transaction
- `transferAssetLocal`: build, sign, and broadcast asset transfer; computes asset change output for partial transfers of fungible assets
- `sendRvnLocal`: build, sign, and broadcast plain RVN P2PKH transfer

The backend does not perform any on-chain write operations from the Android app flows. It retains optional REST endpoints (`/api/brand/issue`, `/api/brand/issue-sub`) for web/API integrations but the Android app does not use them.

### Wallet Role and Control Key

When creating or restoring a wallet in the Brand app, the user must enter a control key (admin or operator). The app validates the key against the backend to determine the role:

| Role | Control Key | Permissions |
|---|---|---|
| Admin | Admin API key | All operations unlocked: issue root/sub assets, issue unique tokens, revoke/un-revoke, send RVN, transfer all asset types |
| Operator | Operator API key | Issue unique tokens only. Root/sub asset creation, revocation, send RVN, and root/sub asset transfers are locked in the app UI. |

The role is stored alongside the wallet (as a preference) and cannot be changed without deleting and recreating/restoring the wallet. Admin/Operator key fields are no longer accessible through Settings; the key is set once at wallet creation time.

This enables a brand admin to pre-configure multiple operator devices on the same wallet that holds the owner assets. Each operator device can issue unique tokens (serials) with reduced privileges, while sharing the wallet address that holds the `BRAND/PRODUCT!` owner tokens required for on-chain unique token issuance.

---

## 6. Asset Hierarchy

Ravencoin supports a three-level asset hierarchy:

```
FASHIONX                        (root asset, 500 RVN)
  FASHIONX/BAG001               (sub-asset, product line, 100 RVN)
    FASHIONX/BAG001#SN0001      (unique token, individual item, 5 RVN)
    FASHIONX/BAG001#SN0002      (unique token, individual item, 5 RVN)
  FASHIONX/WATCH01              (sub-asset, product line, 100 RVN)
    FASHIONX/WATCH01#00084721   (unique token, individual item, 5 RVN)
```

Each unique token has associated IPFS metadata (RTP-1 JSON schema):

```json
{
  "protocol": "RTP-1",
  "version": "1.1",
  "nfc_pub_id": "<sha256(uid || salt)>",
  "serial": "SN0001",
  "status": "active",
  "attributes": { "color": "black", "size": "M" }
}
```

The `status` field can be `"active"` or `"revoked"`. Verification fails immediately if status is `"revoked"`, even if the SUN MAC is valid.

---

## 7. Security Analysis

### Chip Cloning

Impossible. The NTAG 424 DNA stores AES keys in non-extractable silicon fuses. NXP's security certification ensures the key cannot be read via any side channel. A cloned chip would lack the correct keys and produce invalid MACs.

### Replay Attacks

Rejected. The chip's read counter is included in the encrypted `e` parameter. The backend tracks the last-seen counter per chip and rejects any counter that is not strictly greater than the previous valid one.

### nfc_pub_id Privacy

The `nfc_pub_id = SHA-256(uid || BRAND_SALT)` construction means that even if the on-chain metadata is public, an observer cannot determine which physical chip corresponds to which Ravencoin asset without knowing the `BRAND_SALT`. The salt is held exclusively by the brand's backend.

### Key Diversification

Each chip receives a unique AES key derived from the Master Key:
```
Derived_Key = AES-128-ECB(BRAND_MASTER_KEY, UID_padded_to_16_bytes)
```
If one chip's derived key is extracted (e.g. via physical attack on the chip), it does not reveal the Master Key or any other chip's key.

### Admin Authentication

All brand management endpoints require the `ADMIN_KEY` environment variable presented as `X-Admin-Key` or `X-Api-Key` header. Public endpoints (verification, asset lookup) require no authentication.

---

## 8. Testnet Testing

For development and testing, RavenTag supports the Ravencoin testnet:

1. Set `TESTNET=true` in `backend/.env`.
2. Connect `RVN_RPC_URL` to a testnet Ravencoin node.
3. Get test RVN from the testnet faucet at `testnet-faucet.ravencoin.network`.
4. Set `DEMO_CHIP_UID` to a 7-byte hex string (e.g. `04aabbccddeeff`).
5. Use the NFC Simulator (`/simulator`) to generate valid SUN messages without physical chips.

All asset operations work identically on testnet. Asset names and blockchain data are independent of mainnet.

---

## 9. Revocation System

### Backend Database Revocation (Reversible)

The SQLite database maintains a `revoked_assets` table. Revocation is instant and reversible:
- `POST /api/brand/revoke` - mark asset as revoked with optional reason
- `DELETE /api/brand/revoke/:name` - remove revocation (un-revoke)
- `GET /api/brand/revoked` - list all revoked assets

On verification, the backend checks this table first. If the asset is found, verification fails with `RevocationStatus.REVOKED`.

### IPFS Metadata (Immutable)

The IPFS metadata is immutable once uploaded. The `status` field in the IPFS JSON represents the asset state at issuance time and cannot be changed. For revocation, use the backend API which maintains a real-time revocation list in the SQLite database. Verification checks this list first, before validating the NFC signature.

### Backend Revocation (Reversible)

The brand can revoke an asset via `POST /api/brand/revoke` endpoint. The revocation is stored in the backend SQLite database and is checked during verification. This is the primary revocation mechanism and works in real-time. Revocations can be reversed via the un-revoke endpoint.

### On-Chain Burn (Irreversible)

The asset can be transferred to the Ravencoin burn address:
```
RXBurnXXXXXXXXXXXXXXXXXXXXXXWUo9FV
```

Once burned, the asset no longer exists on-chain and all subsequent verifications will show REVOKED. This is irreversible and should be used only for confirmed counterfeits or permanently destroyed items.

---

## 10. Key Management

### BRAND_MASTER_KEY

- 128-bit AES key, stored as 32 lowercase hex characters.
- Never sent to user devices, never stored on-chain, never embedded in NFC URLs.
- Used exclusively on the backend to derive per-chip AES keys on demand.
- The only secret that must be backed up securely. If lost, all chip keys are lost.

### BRAND_SALT

- 32-byte random value, stored as 64 lowercase hex characters.
- Used to compute `nfc_pub_id = SHA-256(uid || salt)`.
- Never stored on-chain. Without it, the link between chip UID and Ravencoin asset cannot be verified.
- Must be backed up alongside the Master Key.

### Per-Chip Key Derivation

```
Derived_Key = AES-128-ECB(BRAND_MASTER_KEY, UID_padded_to_16_bytes)
```

Each chip gets two derived keys (SDMEncKey for encryption, SDMMACKey for CMAC), both derived from the Master Key and UID using the same mechanism with different padding. Derived keys are computed on demand and never stored.

### Android Keystore

The Android app stores the BIP39 mnemonic and any local secrets encrypted with AES-GCM using a key held in Android Keystore. The key is hardware-backed on devices with a secure element.

---

## 11. Demo Walkthrough

This walkthrough uses the NFC Simulator. No physical chip is required.

**Prerequisites:**
- Backend running with `BRAND_MASTER_KEY`, `BRAND_SALT`, `DEMO_CHIP_UID` configured.
- A Ravencoin asset registered with an IPFS metadata hash containing the correct `nfc_pub_id`.
- Android app installed and configured with backend URL.

**Steps:**

1. Open the RavenTag Android app.
2. Navigate to the Scan screen.
3. Tap "Simulate NFC Tap" (developer option).
4. The backend generates `e` and `m` for the demo chip.
5. The app calls `POST /api/verify/tag` with `{ asset, e, m }`.
6. The backend decrypts `e`, recovers the UID, verifies the CMAC, looks up the Ravencoin asset, fetches IPFS metadata, and checks `nfc_pub_id`.
7. The result (AUTHENTIC / REVOKED / error) is displayed with asset details.

---

## 12. Known Limitations

- **No offline verification**: SUN MAC verification requires the brand's backend to be reachable. If offline, the result is UNVERIFIED (fail-closed). Blockchain-only verification (no MAC check) is not supported for security reasons.

- **Single Ravencoin node**: The backend depends on a single `ravend` node. There is no built-in failover to public nodes for write operations (asset issuance, transfers).

- **IPFS availability**: Asset metadata is fetched from IPFS via a configurable gateway. If the gateway is unreachable, metadata verification degrades.

- **Counter tracking not persistent**: The backend does not currently persist the last-seen read counter per chip in the database. Replay attack detection relies on the chip's own counter monotonicity but does not maintain server-side counter state across restarts.

- **No multi-tenant support**: The current backend is designed for a single brand. Running multiple brands requires separate backend deployments with separate master keys.

- **Android-only verification**: Native NFC verification requires the Android app. iOS users cannot verify tags directly (no NFC access for third-party apps).

- **Asset name length**: Ravencoin limits asset names to 30 characters for root assets and has additional constraints for sub-assets and unique tokens. Very long serial numbers may need truncation.
