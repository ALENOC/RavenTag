# Codex Dynamic Security Test Plan

## Purpose

Perform an independent dynamic/adversarial validation against the post-remediation RavenTag `master`. Do not treat the remediation report or CI result as proof that the controls work under hostile runtime conditions. Record exact build SHA, APK/backend build artifacts, environment, fixtures, node/server versions, and raw evidence for every test.

## 1. ElectrumX certificate trust and MITM
- First connection with no pin: verify intended TOFU enrollment only once.
- Reconnect with identical certificate: succeeds.
- Present a different certificate for the same server without an explicit trusted rotation procedure: must fail and must not mutate the stored pin.
- Repeat after process restart and device restart.
- Simulate active MITM/DNS redirection and verify the attacker certificate is not adopted.
- Exercise legitimate certificate rotation procedure, if one exists, and prove it requires explicit trust/recovery rather than automatic mismatch acceptance.

## 2. Malicious relay fees and transaction amount integrity
- ElectrumX returns zero, negative-equivalent/malformed, extremely large, and boundary relay-fee values.
- Verify local fee floor/ceiling and absolute-fee policy.
- Normal send with insufficient amount+fee must fail without changing recipient amount.
- Explicit MAX must compute recipient value from inputs minus validated fee/dust and must be visibly/semantically distinct from a normal send.
- Test RVN-only and RVN+asset sweep cases.

## 3. Android biometric failure states
On representative API 26-29 and API 30+ devices/emulators:
- no biometric/credential enrollment;
- user cancel / negative button;
- repeated failed biometric;
- temporary/permanent lockout;
- prompt initialization exception/pathological lifecycle change;
- app background/foreground and rotation during prompt.
No state may become authenticated on an error path.

## 4. Real auth-bound Android Keystore recovery reveal
- Inspect generated alias `raventag_mnemonic_reveal_key_v1` and prove user authentication is required by KeyInfo/runtime behavior.
- Attempt direct decrypt before authentication: must fail.
- Authenticate and decrypt within validity window: succeeds.
- Wait beyond validity window: decrypt must fail again until re-authentication.
- Test device reboot/lock and biometric enrollment changes/key invalidation behavior.
- Verify normal seed/signing operations remain independent and do not require the recovery-reveal key.
- Confirm no alternate production method can recover mnemonic plaintext through the general wallet key.

## 5. Legacy wallet mnemonic migration
Create an installation containing only the pre-remediation mnemonic ciphertext/IV/HMAC and a valid wallet seed.
- Cancel authentication: legacy remains untouched; no new representation considered complete.
- Force failure during new-key creation/encryption: legacy remains.
- Force failure after new encryption but before persistence: legacy remains.
- Force process death after new persistent copy but before legacy deletion; restart and repeat reveal: new copy must decrypt and cleanup must finish safely.
- Verify migrated phrase exactly matches original and derives the same BIP39 seed/BIP44 addresses.
- Repeat migration/reveal multiple times; result is idempotent.

## 6. Legacy `raventag_secure` secret migration
Test both historical layouts:
- ordinary plaintext `SharedPreferences` file;
- v1 `EncryptedSharedPreferences` file.
For `admin_key`, `operator_key`, `initial_master_key`, and `pinata_jwt`:
- no legacy values -> no-op;
- valid values -> v2 encrypted/dedicated storage, read-back equality, then legacy deletion;
- unavailable/corrupt Keystore -> sensitive features blocked and legacy remains untouched;
- kill process after destination write but before source deletion -> repeat safely finishes cleanup;
- repeated migration -> no duplicate/destructive behavior.
Confirm `kubo_node_url` is handled as non-secret configuration rather than indiscriminately included in the secret whitelist.

## 7. Simultaneous UTXO spend attempts
- Launch two or more sends/transfers/issuances/sweeps concurrently against overlapping UTXOs.
- Prove only one operation reserves an outpoint and the losing operation never signs.
- Inject crash after reservation/before signing: safe release/recovery behavior.
- Inject timeout/drop after signing/before/after broadcast response: reservation remains until reconciliation proves safe release.
- Verify no direct builder/broadcast entrypoint bypasses the common boundary.

## 8. Registry validation with real Ravencoin transactions
Use known real transactions for:
- valid root issuance;
- valid sub-asset issuance;
- valid unique issuance;
- ordinary payment txid;
- issuance of a different asset name;
- wrong claimed issuance type;
- missing/wrong canonical burn;
- owner-token semantic mismatch;
- malformed/unavailable RPC response.
Only semantically matching issuance claims may enter the registry.

## 9. NTAG424 / NDEF / replay
With real NTAG424 DNA hardware and malformed synthetic NDEF records:
- valid SUN CMAC/counter progression;
- repeated counter/replay;
- out-of-order/racing scans;
- malformed/zero-length payloads;
- unsupported URI prefix codes and truncated records;
- tag clone attempts;
- concurrent backend verification requests for the same counter.
Verify no crash and no replay acceptance.

## 10. IPFS redirect/adversarial fetches
- 301/302/307/308 from an allowed gateway to disallowed HTTP/HTTPS/private/link-local destinations.
- redirect loops and long chains;
- DNS rebinding where feasible;
- oversized/slow responses.
Transparent redirects must not bypass endpoint validation.

## 11. Android cleartext and deep-link behavior
- Attempt HTTP connections from each app flavor: globally denied unless an intentionally scoped exception is documented (none expected).
- Send ACTION_VIEW `/verify` links from every authorized production host: accepted.
- Same `/verify` path from attacker-controlled HTTPS hosts, subdomain lookalikes, Unicode/punycode variants, explicit ports, userinfo URLs: rejected.

## 12. Backend dependency/runtime checks
- Re-run `npm ci` and `npm audit --audit-level=low` from a clean clone of the merged SHA.
- Exercise upload/IPFS, Express JSON parsing/routing, and rate-limit paths after dependency upgrades.
- Confirm no behavioral regression from Express 4.22.2, express-rate-limit 8.6.2, form-data 4.0.6, multer 2.2.0, tsx 4.23.12, and the final Axios 1.x pin.

## 13. BIP39/BIP32/BIP44 and transaction vectors
- Official BIP39 English vectors including passphrases and NFKD Unicode equivalence.
- BIP32 derivation vectors for hardened/non-hardened descendants where applicable.
- Ravencoin BIP44 coin type 175 addresses for known mnemonic fixtures.
- Base58Check valid/invalid checksum, foreign-network version bytes, non-canonical encodings.
- Known-good RVN transaction serialization/signature vectors for normal send, MAX, asset transfer, issuance, reissue, and sweep.

## Exit criteria
For every scenario capture PASS/FAIL, exact reproduction steps, raw logs/pcaps/transactions where applicable, and the tested commit SHA. Any exploitable bypass, unexpected fail-open behavior, incorrect transaction semantics, or wallet-data-loss condition reopens the corresponding RT-SEC finding. Do not issue a `SECURITY PASS` solely from completion of this plan.
