# Phase 40: Asset Emission UX — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-25
**Phase:** 40-asset-emission-ux
**Mode:** discuss
**Areas discussed:** Error classification + messaging, Pre-issuance validation, Issuance retry policy, Post-issuance confirmation UX

---

## Error Classification + Messaging

| Option | Description | Selected |
|--------|-------------|----------|
| Classify known errors, fallback raw for unknown | Map known RPC errors to Italian user messages in AppStrings.kt. Unknown errors show raw message. Safe: adds classification layer on top of existing catch. | ✓ |
| Classify + suggest action per error | Each error includes a suggested action. More helpful but more strings to maintain. | |
| Keep generic message, add error code | Show "Emissione fallita (codice: X)". Not directly actionable. | |

**User's choice:** Classify known errors, fallback raw for unknown
**Notes:** All error messages must be translated in all 9 app languages (AppStrings.kt). IPFS upload errors classified separately from RPC errors — IPFS failures allow retry without restarting the form.

---

## Pre-issuance Validation

| Option | Description | Selected |
|--------|-------------|----------|
| Full pre-flight: balance + name + IPFS | Balance check, name uniqueness via backend API, IPFS upload as distinct steps with individual progress. Most thorough. | ✓ |
| Balance + asset name uniqueness | Balance check + backend API call for name. Adds one network round-trip. | |
| Wallet balance check only | Verify wallet has enough RVN for issuance fee + network fee. Simple. | |

**User's choice:** Full pre-flight: balance + name + IPFS
**Notes:** IPFS upload triggered on submit (not separate button, not auto on image select). Multi-step progress indicator: "Caricamento IPFS..." → "Verifica disponibilita'..." → "Emissione in corso..." → "Conferma in corso...". Unique token flow (issue + NFC write) must remain intact — all changes additive.

---

## Issuance Retry Policy

| Option | Description | Selected |
|--------|-------------|----------|
| Retry only safe errors | Auto-retry connection/DNS/IPFS failures (no cost). On timeout: query tx status instead of re-broadcast. Never retry RPC rejections. | ✓ |
| No auto-retry, always ask user | Show error with "Riprova" button. Safest for fund safety. | |
| Retry all except RPC rejections | Retry network errors AND timeouts with exp backoff. Risk: timeout retry could double-submit. | |

**User's choice:** Retry only safe errors
**Notes:** Timeout handling: query tx status via `getrawtransaction` — if tx landed, treat as success; if not found, ask user to retry manually. RPC rejections (duplicate name, insufficient funds) → never retry.

---

## Post-issuance Confirmation UX

| Option | Description | Selected |
|--------|-------------|----------|
| Confirmation progress + explorer link | Green banner with asset name, tappable txid → explorer, N/6 confirmation counter. Consistent with Phase 30 D-08. | ✓ |
| Minimal: txid + explorer link only | Show full txid with explorer link, no confirmation tracking. | |
| Notification when confirmed | Background tracking + system notification at 6 confirmations. | |

**User's choice:** Confirmation progress + explorer link
**Notes:** Explorer URL: `https://ravencoin.network/tx/{txid}`. Combined "Issue + Write Tag" flow: progress shows NFC programming as separate step. Tag write has own progress indicator since user must hold phone to tag.

---

## Claude's Discretion

- Exact Italian error string content for each error classification
- IPFS retry UX details (inline retry button vs auto-retry within submit flow)
- Balance check threshold display format
- Confirmation progress indicator visual design
- Exact placement of progress step indicator in IssueAssetScreen layout

## Deferred Ideas

- Burn on-chain in revocation flow (currently hardcoded `burnOnChain = false`)
- Notification on confirmation (background tracking + push at 6 confirmations)
- Asset transfer UX improvements
- Em-dash cleanup in `RavencoinTxBuilder.kt:907,908`
