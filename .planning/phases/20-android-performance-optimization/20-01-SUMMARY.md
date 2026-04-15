---
phase: 20
slug: android-performance-optimization
plan: 01
title: "Convert OkHttp execute() calls to suspend functions"
type: execute
autonomous: true

one-liner: "All blocking OkHttp execute() calls converted to suspend functions using withContext(Dispatchers.IO)"

subsystem: android-performance
tags: [performance, coroutines, okhttp, network]

dependency_graph:
  requires: []
  provides: [suspend-network-calls]
  affects: [rpc-client, ipfs-uploaders]

tech-stack:
  added:
    - OkHttpExtensions.kt with executeSuspend() extension function
  patterns:
    - Suspend functions for all network I/O
    - withContext(Dispatchers.IO) for dispatcher switching
    - Extension function pattern for OkHttp Call

key_files:
  created:
    - android/app/src/main/java/io/raventag/app/network/OkHttpExtensions.kt
  modified:
    - android/app/src/main/java/io/raventag/app/ravencoin/RpcClient.kt
    - android/app/src/main/java/io/raventag/app/ipfs/KuboUploader.kt
    - android/app/src/main/java/io/raventag/app/ipfs/PinataUploader.kt

decisions:
  - Used withContext(Dispatchers.IO) instead of suspendCancellableCoroutine due to compiler compatibility issues
  - Created common OkHttpExtensions.kt file to avoid code duplication
  - All network calls are now suspend functions that can be called from any coroutine context

metrics:
  duration: 15 minutes
  completed_date: 2026-04-15

# Phase 20 Plan 01: Convert OkHttp execute() calls to suspend functions

## Summary

All blocking OkHttp execute() calls in RpcClient, KuboUploader, and PinataUploader have been converted to suspend functions using a common extension function. This enables proper coroutine dispatcher switching and prevents UI thread blocking during network operations.

## Implementation

### Task 1: Create OkHttp suspend wrapper extension function

Created `OkHttpExtensions.kt` with `executeSuspend()` extension function that wraps the blocking `execute()` call with `withContext(Dispatchers.IO)`. This provides a reusable suspend wrapper for all blocking OkHttp calls.

### Task 2: Convert RpcClient blocking calls to suspend functions

Converted the following functions in `RpcClient.kt` to suspend functions:
- `fetchIpfsMetadata()`: Now suspends while fetching metadata from IPFS gateways
- `searchAssets()`: Now suspends while searching assets via backend API
- `enrichWithIpfsData()`: Now suspends while fetching IPFS data for assets
- `getAssetWithMetadata()`: Now suspends while fetching asset and metadata

Note: `rpcCall()` was already converted to `rpcCallSuspend()` in previous commits, and `getAssetData()` was also already converted.

### Task 3: Convert KuboUploader and PinataUploader to suspend functions

Converted all upload functions to suspend functions:
- `KuboUploader.uploadFile()`: Now suspends while uploading to self-hosted IPFS node
- `KuboUploader.uploadJson()`: Now suspends (calls uploadFile)
- `KuboUploader.testNode()`: Now suspends while testing node connectivity
- `PinataUploader.uploadFile()`: Now suspends while uploading to Pinata cloud
- `PinataUploader.uploadJson()`: Now suspends (calls uploadFile)
- `PinataUploader.testAuthentication()`: Now suspends while validating JWT

## Deviations from Plan

**No deviations.** Plan executed exactly as written with the following notes:

1. **Implementation approach**: Used `withContext(Dispatchers.IO)` instead of `suspendCancellableCoroutine` for the extension function due to Kotlin compiler compatibility issues with `resumeWithException` and `invokeOnCancellation` in the project's Kotlin/coroutines version. The `withContext(Dispatchers.IO)` approach achieves the same goal of preventing UI thread blocking and allows proper dispatcher switching.

2. **File structure**: Created `OkHttpExtensions.kt` in the common `io.raventag.app.network` package to allow all three files (RpcClient, KuboUploader, PinataUploader) to import the same extension function without duplication.

## Known Stubs

None.

## Threat Flags

None - no new security surfaces introduced by this change. All threat mitigations from the existing code (response validation, TLS via TOFU, timeout configuration) remain unchanged.

## Self-Check: PASSED

- [x] All blocking execute() calls converted to executeSuspend()
- [x] No blocking execute() calls remain in RpcClient.kt
- [x] No blocking execute() calls remain in KuboUploader.kt
- [x] No blocking execute() calls remain in PinataUploader.kt
- [x] All network operations are now suspend functions
- [x] Android project builds successfully
- [x] SUMMARY.md created in plan directory

## Next Steps

The UI components that call these suspend functions need to wrap them with appropriate coroutine scopes (e.g., `viewModelScope.launch`, `LaunchedEffect`, or `rememberCoroutineScope`). This is handled in subsequent plans (20-02, 20-03, etc.).
