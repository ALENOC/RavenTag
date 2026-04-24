# Phase 30 Deferred Items

## Em-dash occurrences outside Phase 30 scope

Found during 30-10 housekeeping audit but not in Phase 30 modified-files list:

- `android/app/src/main/java/io/raventag/app/wallet/RavencoinTxBuilder.kt:907`
- `android/app/src/main/java/io/raventag/app/wallet/RavencoinTxBuilder.kt:908`

Both occurrences are in Kotlin comments describing vout ordering. Replacement (e.g., with `,` or `:`) is safe but outside Phase 30 scope per the plan's `files_modified` list. Recommend picking up in the next phase's housekeeping or via a stand-alone style commit.
