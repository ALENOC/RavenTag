# Phase 20: Android Performance Optimization - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-13
**Phase:** 20-android-performance-optimization
**Areas discussed:** Wallet restore optimization, Send operation UX

---

## Wallet Restore Optimization

| Option | Description | Selected |
|--------|-------------|----------|
| Parallel loading | Load UTXOs, balances, and transactions simultaneously (~3x speedup) | ✓ |
| Sequential async | Load sequentially but with suspend functions (simpler, slower) | |
| Progressive loading | Show partial results as they load (best UX, most complex) | |

**User's choice:** Parallel loading
**Notes:** Fastest approach, ~3x speedup over sequential

### Error handling for parallel restore

| Option | Description | Selected |
|--------|-------------|----------|
| Fail all or nothing | Fail entire restore if any part errors | |
| Partial success | Show what succeeded, error for what failed | |
| Auto-retry | Retry failed parts automatically before giving up | ✓ |

**User's choice:** Auto-retry
**Notes:** Consistent with send operation retry policy

### Retry count for wallet restore

| Option | Description | Selected |
|--------|-------------|----------|
| Quick (2 retries) | Retry 1-2 times, then show error | |
| Balanced (5 retries) | Retry 3-5 times with backoff | ✓ |
| Persistent (unlimited) | Retry indefinitely until user cancels | |

**User's choice:** Balanced (5 retries with backoff)
**Notes:** Good balance between resilience and user feedback

---

## Send Operation UX

| Option | Description | Selected |
|--------|-------------|----------|
| Blocking modal | User can't dismiss, app waits for completion | |
| Dismissible dialog | User can cancel, dialog shows progress | |
| Background + notification | User can dismiss app, shows progress in notification | ✓ |
| Snackbar + loading | In-app feedback only (simplest) | |

**User's choice:** Background + notification
**Notes:** Best UX, user can leave the app, requires notification system

### Notification tap behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Open to transaction details | Opens the app and shows transaction status | ✓ |
| Open to wallet | Opens the app to the main wallet screen | |
| No action | Notification is informational only | |

**User's choice:** Open to transaction details
**Notes:** Direct access to the relevant information

### Notification frequency

| Option | Description | Selected |
|--------|-------------|----------|
| Progress updates | Multiple notifications (broadcasting, confirming, completed/failed) | ✓ |
| Single updating | One notification that updates as status changes | |
| Result only | Only notify when transaction is complete | |

**User's choice:** Progress updates
**Notes:** Multiple notifications showing broadcast, confirmation, and completion stages

### Send failure retry

| Option | Description | Selected |
|--------|-------------|----------|
| Retry from notification | Show error notification with Retry and Cancel buttons | |
| Open app to retry | User must open app to retry from send screen | |
| Auto-retry first | Auto-retry N times before showing error | ✓ |

**User's choice:** Auto-retry first
**Notes:** Consistent with wallet restore retry policy

### Send retry count

| Option | Description | Selected |
|--------|-------------|----------|
| Quick (2 retries) | Retry 1-2 times, then show error | |
| Balanced (5 retries) | Retry 3-5 times with backoff | ✓ |
| Persistent (unlimited) | Retry indefinitely until user cancels | |

**User's choice:** Balanced (5 retries with backoff)
**Notes:** Consistent with wallet restore

### Confirmation before send

| Option | Description | Selected |
|--------|-------------|----------|
| Always confirm | Show amount, address, and fee before confirming | ✓ |
| Conditional confirm | No confirmation for small amounts | |
| No confirm | No confirmation dialog | |

**User's choice:** Always confirm
**Notes:** Safest approach, shows amount, recipient address, and fee

---

## Claude's Discretion

- Loading UI pattern for general async operations (spinners, progress indicators)
- Async error handling for non-send/non-restore operations
- Cancellation policy for in-progress operations
- IPFS upload async conversion details

## Deferred Ideas

None — discussion stayed within phase scope