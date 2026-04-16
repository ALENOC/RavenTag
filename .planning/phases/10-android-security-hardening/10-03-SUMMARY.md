---
phase: 10
plan: 03
subsystem: backend-security
tags: [sql, security, explicit-columns, api-contracts]
dependency_graph:
  requires: []
  provides: [explicit-column-lists]
  affects: [admin-api, cache-api]
tech_stack:
  added: []
  patterns:
    - explicit-column-lists: SQL queries now use explicit column lists instead of SELECT *
    - type-safe-queries: TypeScript type annotations match SQL column lists
key_files:
  created: []
  modified:
    - backend/src/routes/admin.ts
    - backend/src/middleware/cache.ts
decisions: []
metrics:
  duration: 727
  completed_date: "2026-04-13T13:52:12Z"
---

# Phase 10 Plan 03: Replace SELECT * with Explicit Column Lists Summary

Replace all SELECT * queries in backend admin endpoints with explicit column lists to prevent accidental data exposure and make API contracts clear.

## Changes Made

### Task 1: Replace SELECT * in admin.ts

**File:** `backend/src/routes/admin.ts`

**Change:** Updated GET /api/admin/tags endpoint to use explicit column list:
```typescript
// Before: SELECT * FROM registered_tags
// After:
SELECT
    nfc_pub_id,
    asset_name,
    brand_info,
    metadata_ipfs,
    created_at
FROM registered_tags
ORDER BY created_at DESC
```

**Rationale:** The `registered_tags` table uses `nfc_pub_id` as primary key, not an `id` column. Explicit columns make the API response contract clear and prevent exposure of any new columns added to the schema (e.g., debug fields, audit timestamps).

**Commit:** 323ab3c

### Task 2: Replace SELECT * in cache.ts

**File:** `backend/src/middleware/cache.ts`

**Changes:**

1. **listRevokedAssets() function:**
```typescript
// Before: SELECT * FROM revoked_assets
// After:
SELECT
    asset_name,
    reason,
    burned_on_chain,
    burn_txid,
    revoked_at
FROM revoked_assets
ORDER BY revoked_at DESC
```

2. **listChips() function:**
```typescript
// Before: SELECT * FROM chip_registry
// After:
SELECT
    asset_name,
    tag_uid,
    nfc_pub_id,
    registered_at
FROM chip_registry
ORDER BY registered_at DESC
```

**Rationale:** Both functions return data to API clients. Explicit columns ensure only documented fields are exposed. Note: `revoked_assets` table has a `revoked_by` column in the schema, but it was intentionally excluded from the original type annotation and thus from the explicit column list.

**Commit:** e01fc7b

### Task 3: Verify no remaining SELECT * in backend

**Verification:** Ran comprehensive grep search across all TypeScript files in backend/src:
```bash
grep -rn "SELECT \*" backend/src --include="*.ts"
# Result: No matches found
```

**Outcome:** Verified that all SELECT * queries have been replaced. All queries in the backend now use explicit column lists matching table schemas.

**Documentation:** Recorded in commit message.

## Deviations from Plan

None. Plan executed exactly as written.

### Schema Adjustments

**Minor deviation from plan artifact:** The plan expected `id` column in `registered_tags` table, but the actual schema uses `nfc_pub_id` as the primary key. This was discovered during Task 1 and the implementation was adjusted to match the actual schema (nfc_pub_id, asset_name, brand_info, metadata_ipfs, created_at).

## Threat Surface Scan

No new security-relevant surface introduced. This plan reduces threat surface by making API contracts explicit.

## Known Stubs

None. All queries are fully implemented with explicit column lists.

## Files Modified

1. `backend/src/routes/admin.ts` - Updated GET /api/admin/tags endpoint
2. `backend/src/middleware/cache.ts` - Updated listRevokedAssets() and listChips() functions

## Performance Impact

None. Explicit column lists have no performance difference from SELECT * in SQLite. In fact, they may improve performance by reducing data transfer when new columns are added to tables.

## Security Benefits

1. **Prevents accidental data exposure:** If new columns are added to tables (e.g., debug fields, audit logs), they won't be automatically exposed in API responses.
2. **Makes API contracts explicit:** Developers can clearly see what data is exposed by reading the SQL queries.
3. **Type safety:** TypeScript type annotations now match SQL column lists, providing compile-time verification.
4. **Defense in depth:** Parameterized queries (better-sqlite3) prevent SQL injection, while explicit column lists prevent schema-based information disclosure.

## Self-Check: PASSED

**Created files:**
- FOUND: /home/ale/Projects/RavenTag/.planning/phases/10-android-security-hardening/10-03-SUMMARY.md

**Commits:**
- FOUND: 323ab3c - feat(10-03): replace SELECT * with explicit column list in admin.ts
- FOUND: e01fc7b - feat(10-03): replace SELECT * with explicit column lists in cache.ts

**Modified files:**
- backend/src/routes/admin.ts (11 insertions, 1 deletion)
- backend/src/middleware/cache.ts (21 insertions, 2 deletions)

**Verification checklist:**
- [x] All tasks executed (3/3)
- [x] Each task committed individually
- [x] SUMMARY.md created
- [x] No SELECT * queries remain in backend
- [x] All queries use explicit column lists
- [x] Type annotations match SQL columns
