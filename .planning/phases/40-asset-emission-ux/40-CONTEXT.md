# Phase 40: Asset Emission UX - Context

**Gathered:** 2026-04-25
**Status:** Ready for planning

<domain>
## Phase Boundary

Make asset/sub-asset issuance error handling robust with clear user feedback. Catch and classify RPC errors, make failures actionable via localized messages, add pre-issuance validation to prevent doomed submissions, implement safe retry policies, and provide confirmation progress tracking. This phase improves the error/UX path; the issuance mechanism itself (RPC broadcast, consolidation, on-chain signing) already works and must not be broken.

Out of scope: backend stability (Phase 50), new issuance asset types, changes to the on-chain issuance protocol.
</domain>

<decisions>
## Implementation Decisions

### Error Classification + Messaging
- **D-01:** Classify known RPC errors into Italian user-facing messages. Fallback to raw error message for unknown errors. All messages defined in `AppStrings.kt` for localization across all 9 app languages.
- **D-02:** IPFS upload errors classified separately from RPC issuance errors. IPFS failures allow retry without restarting the entire form.
- **D-03:** Known error categories to classify: insufficient funds, duplicate asset name, RPC node unreachable/connection refused, RPC timeout, fee estimation failure, IPFS gateway down, IPFS auth expired, and invalid address format.

### Pre-issuance Validation
- **D-04:** Full pre-flight validation in three sequential steps on submit:
  1. Wallet balance check: verify wallet has enough RVN for issuance fee (500/100/5 RVN per asset type) + estimated network fee. Show inline warning if insufficient.
  2. Asset name uniqueness check via backend API call.
  3. IPFS metadata upload.
- **D-05:** Multi-step progress indicator shown on submit button tap: "Caricamento IPFS..." → "Verifica disponibilita'..." → "Emissione in corso..." → "Conferma in corso...". Each step shows success/failure before advancing.
- **D-06:** IPFS upload triggered on submit (not as separate button, not auto on image select). Sequential steps with clear per-step status. Uploaded CID preserved for retry.

### Issuance Retry Policy
- **D-07:** Auto-retry only safe errors with 5x exponential backoff (consistent with Phase 20 D-02/D-06). Safe errors: connection failures, DNS resolution failures, IPFS upload failures. These carry no double-spend risk since no tx was broadcast.
- **D-08:** On RPC timeout: do NOT re-broadcast. Instead query tx status via `getrawtransaction`. If tx landed on-chain → treat as success. If tx not found → prompt user to retry manually. This prevents accidental double-spend of the issuance fee.
- **D-09:** RPC rejections (duplicate asset name, insufficient funds, invalid parameters) → never auto-retry. Show classified error with suggested action (e.g., "Fondi insufficienti — invia RVN al wallet brand e riprova").

### Post-issuance Confirmation UX
- **D-10:** Show confirmation progress after successful issuance: "Pending..." → "N/6 conferme" → "Confermato". Consistent with Phase 30 D-08 receive confirmation pattern. Auto-dismiss banner after 6 confirmations.
- **D-11:** Txid in result banner is tappable. Opens block explorer at `https://ravencoin.network/tx/{txid}`.
- **D-12:** Issued asset appears in transaction history after next WalletScreen sync (Phase 30 D-01 periodic poll).
- **D-13:** Combined "Issue + Write Tag" flow: progress indicator includes NFC programming as distinct step: "Caricamento IPFS..." → "Emissione in corso..." → "Programmazione tag NFC..." → "Conferma (N/6)". Tag write step has its own progress since user must hold phone to tag.

### Critical Constraints (non-negotiable)
- **C-01:** Unique token issuance flow (issue + NFC tag programming) must remain intact. All error handling changes are additive layers on top of the existing working flow. Do not restructure the `onIssueUniqueAndWriteTag` path.
- **C-02:** Asset emission currently works. Changes to error/retry path must not alter the successful issuance code path. Add try/catch classification and pre-flight checks without changing `WalletManager.issueAssetLocal()` or `RpcClient` internals.
- **C-03:** The `IssueAssetScreen` composable API (callback signatures) is the boundary. Error handling improvements happen inside the ViewModel callbacks in `MainActivity.kt` and inside `AssetManager.kt`; the screen composable receives only `resultMessage`, `resultSuccess`, and `isLoading`.

### Claude's Discretion
- Exact Italian error string content for each classification category
- IPFS retry UX details (inline retry button vs auto-retry within submit flow)
- Balance check threshold display format and minimum balance calculation
- Confirmation progress indicator visual design and animation
- Exact placement of progress step indicator in the IssueAssetScreen layout
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Asset Emission
- `android/app/src/main/java/io/raventag/app/ui/screens/IssueAssetScreen.kt` — Multi-mode form UI (ROOT_ASSET, SUB_ASSET, UNIQUE_TOKEN, REVOKE, UNREVOKE). Result banner at line 256. Submit button at line 710.
- `android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt` — Backend API client for issue/revoke/upload operations. `AssetOperationResult` data class at line 97. All methods return typed results.
- `android/app/src/main/java/io/raventag/app/MainActivity.kt` §1605-1796 — ViewModel issuance callbacks (`issueRootAsset`, `issueSubAsset`, `issueUniqueToken`, `revokeAsset`, `unrevokeAsset`, `registerChip`). Current error handling at lines 1625-1627 (generic catch).
- `android/app/src/main/java/io/raventag/app/MainActivity.kt` §2270-2309 — `processIssueAndWrite` flow combining issuance + NFC tag programming.
- `android/app/src/main/java/io/raventag/app/wallet/WalletManager.kt` — `issueAssetLocal()` function and consolidation logic.

### UI Strings / Localization
- `android/app/src/main/java/io/raventag/app/ui/theme/AppStrings.kt` — All 9-language string resources. New error messages must be added here.

### Prior Phase Context
- `.planning/phases/20-android-performance-optimization/20-CONTEXT.md` — D-02 retry policy (5x exp backoff), D-05 progress notifications, D-07 confirmation dialog
- `.planning/phases/30-wallet-reliability/30-CONTEXT.md` — D-04 cold start cache, D-08 confirmation progress (N/6), D-12 connection status badge, D-20 reserved UTXOs

### Project Context
- `.planning/PROJECT.md` — Current milestone focus, constraints, key decisions
- `.planning/codebase/CONVENTIONS.md` — Kotlin error handling patterns (typed result objects, no exceptions to UI)
- `.planning/codebase/INTEGRATIONS.md` — Ravencoin RPC integration details, IPFS gateway config

### Deferred Items
- `.planning/phases/30-wallet-reliability/deferred-items.md` — Phase 30 deferred: em-dash occurrences in `RavencoinTxBuilder.kt:907,908`
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `IssueAssetScreen.kt` result banner (lines 256-269): green/red Card with icon + message. Already handles `resultSuccess` nullable Boolean. Reuse for classified error display.
- `AssetManager.kt` `AssetOperationResult(success, txid, assetName, error)`: typed result envelope already used by all issuance methods. Classification layer can wrap this without changing the type.
- Phase 20 `retryWithBackoff` utility: 5x exp backoff directly applies to safe-error retries (D-07).
- Phase 20 `TransactionNotificationHelper`: notification channel pattern for tracking confirmation (D-10).
- Phase 30 scripthash subscription (`RavencoinPublicNode`): can track issued asset's parent address for confirmation progress.

### Established Patterns
- `withContext(Dispatchers.IO)` for all network/DB operations (Phase 20).
- `viewModelScope.launch` for coroutine dispatch from UI callbacks (MainActivity.kt).
- Result banner pattern: nullable `resultSuccess` drives green/red Card visibility.
- Button Loading Spinner (20-UI-SPEC.md): 20.dp white CircularProgressIndicator, 2.dp stroke, container at 30% opacity.
- String resources via `LocalStrings.current` composable — all user-facing text goes through `AppStrings.kt`.

### Integration Points
- `MainActivity.kt` issuance callbacks (lines 1611-1677): classification logic inserted in catch blocks.
- `AssetManager.kt` `adminRequest()` (line 235): IOException with error message from backend — classification can parse this.
- `IssueAssetScreen.kt` `resultMessage` / `resultSuccess` parameters: existing result state channel.
- `WalletManager.issueAssetLocal()`: throws on failure — catch blocks in MainActivity classify the exception.
- `ImagePickerButton` composable: IPFS upload integrated into form — upload step extraction happens here.

### Concerns
- The combined "Issue + Write Tag" flow (`processIssueAndWrite`, lines 2270-2309) has its own error handling with `Result.failure(Exception(...))`. Classification must be applied consistently across both standalone issuance callbacks and this combined flow.
- `revokeAsset` in MainActivity (line 1714) calls `am.revokeAsset()` but discards the `AssetOperationResult` (line 1721). Bug: always sets `issueSuccess = true` regardless of actual result.
</code_context>

<specifics>
## Specific Ideas

- Error messages in Italian by default, with AppStrings.kt keys for all 9 languages (consistent with existing localization pattern).
- Multi-step progress indicator displayed above or replacing the submit button, showing current step name and a checkmark for completed steps.
- Timeout handling: use `getrawtransaction` to query tx status rather than assuming failure. If the txid is unknown, the issuance tx was never broadcast.
- Revoke flow has a bug (line 1721, MainActivity.kt): `am.revokeAsset()` result is discarded, always sets success. Fix in this phase is appropriate since it's a silent failure elimination.
</specifics>

<deferred>
## Deferred Ideas

- Burn on-chain in revocation flow: currently hardcoded to `burnOnChain = false` (line 1721). Full on-chain burn UX belongs in a future phase.
- Asset transfer UX (TRANSFER_ROOT, TRANSFER_SUB modes): dedicated screens already exist, not in this phase scope.
- Notification on confirmation: background tracking + push notification when 6 confirmations reached. Discussed and deferred: adds complexity, user can see confirmation on WalletScreen.
- Em-dash cleanup in `RavencoinTxBuilder.kt:907,908`: pick up in housekeeping.

### Reviewed Todos (not folded)
None — no pending todos matched Phase 40.
</deferred>

---

*Phase: 40-asset-emission-ux*
*Context gathered: 2026-04-25*
