# Logging Verification Tests

This directory contains verification tests to ensure sensitive data is not logged.

## Purpose

The RavenTag backend must never log sensitive data (e.g., tag_uid, chip keys, admin keys) to prevent exfiltration via log aggregation services (DataDog, CloudWatch, etc.).

## Verification Scripts

### verify-no-body-logging.sh

Verifies that the request logger (`src/middleware/logger.ts`) does NOT log request or response bodies.

**Usage:**
```bash
./src/__tests__/verify-no-body-logging.sh
```

**What it checks:**
1. SECURITY comment exists in logger.ts
2. No `req.body` logging in code
3. No `res.body` logging in code
4. Metadata-only logging is documented (method, path, status, duration, ip)

**Expected output:**
```
=== Backend Logging Verification ===

PASS: SECURITY comment found in logger.ts
PASS: No req.body logging in logger.ts
PASS: No res.body logging in logger.ts
PASS: Metadata logging documented (method, path, status, duration, ip)

=== All checks passed ===
The request logger only logs metadata, never request bodies.
```

## Logging Policy

The backend request logger (`requestLogger` middleware) follows a strict security policy:

- **NEVER logs:** Request bodies, response bodies, sensitive parameters
- **ALWAYS logs:** HTTP method, request path, status code, duration, IP address

This ensures that sensitive endpoints like `/api/brand/derive-chip-key` (which receives `tag_uid`) are safe from log exfiltration.

## Related Files

- `src/middleware/logger.ts` - Request logger implementation
- `android/app/src/main/java/io/raventag/app/wallet/AssetManager.kt` - Android client (also removes sensitive logging)
