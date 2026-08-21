package io.raventag.app.wallet.coretrust

/**
 * Core Trust Status — 1.0.8 feature.
 *
 * Classifies whether the Ravencoin Core daemon behind the currently used
 * ElectrumX server can be considered trustworthy, using only evidence the
 * ElectrumX-Ravencoin 1.13 protocol actually exposes:
 *
 *  1. `server.features` → `ravencoin.backend_info` capability advertisement;
 *  2. `server.ravencoin_backend` → self-reported backend evidence
 *     (version, network, sync flags, checkpoint claim, build identity);
 *  3. the Ed25519-signed safe-Core policy published by the
 *     electrumx-ravencoin maintainers (certified releases keyed on
 *     repository + exact commit — a version string alone is never trust);
 *  4. independent chain corroboration: the block header at the incident
 *     checkpoint height fetched from at least one server operated by a
 *     DIFFERENT operator group must be byte-identical.
 *
 * Everything the server reports about itself is a claim, not a proof. The
 * best claim still only yields TRUSTED when it matches a signature-verified
 * certification AND survives cross-operator chain comparison. A legacy
 * server without the capability is UNKNOWN — never unsafe, never trusted.
 */
enum class CoreTrustLevel { TRUSTED, UNKNOWN, UNSAFE }

/** Why the evaluator reached its classification. Kept machine-readable for tests. */
enum class CoreTrustReason {
    /** Server does not implement the 1.13 core-evidence capability (legacy ElectrumX). */
    LEGACY_SERVER,
    /** RPC answered but the payload failed structural validation. */
    MALFORMED_RESPONSE,
    /** Capability advertised (or probe attempted) but evidence could not be retrieved. */
    VERIFICATION_FAILED,
    /** Network timeout / transport error while collecting evidence. */
    TIMEOUT,
    /** No usable signed policy: fetch failed, signature invalid, or expired everywhere. */
    POLICY_UNAVAILABLE,
    /** The fetched policy failed signature/expiry/rollback checks (cache/baseline used). */
    POLICY_INVALID_SIGNATURE,
    /** Backend reports no build identity, so it cannot be matched to a certification. */
    IDENTITY_MISSING,
    /** Identity is present but no certified release matches repository+commit. */
    NOT_CERTIFIED,
    /** Backend admits a Core below the 4.8.0 safety floor. */
    INCOMPATIBLE_CORE,
    /** Core version is known-vulnerable, or its release was revoked / marked unsafe. */
    KNOWN_UNSAFE_CORE,
    /** Backend reports a chain other than Ravencoin mainnet. */
    WRONG_NETWORK,
    /** Compatibility/synchronization flags are not all true. */
    NOT_SYNCHRONIZED,
    /** Backend evidence timestamp is too old to act on. */
    STALE_EVIDENCE,
    /** Checkpoint header could not be corroborated by an independent operator. */
    NO_CHAIN_CORROBORATION,
    /** Accompanies TRUSTED: identity matched a certified release and all checks passed. */
    CERTIFIED_BUILD
}

/**
 * Immutable result of a core-trust evaluation.
 *
 * All display strings are derived by the UI layer; this type carries only
 * facts and classification so it stays unit-testable.
 */
data class CoreTrustResult(
    val level: CoreTrustLevel,
    val reason: CoreTrustReason,
    /** Self-reported Core version string (display only; never a trust input alone). */
    val coreVersion: String? = null,
    val identityRepository: String? = null,
    val identityCommit: String? = null,
    val identityEvidence: String? = null,
    /** Hosts in other operator groups whose checkpoint header matched byte-for-byte. */
    val corroboratedBy: List<String> = emptyList(),
    /** Host of the server this result describes (may differ from the live current node). */
    val serverHost: String? = null,
    /** Wall-clock millis of the evaluation. */
    val evaluatedAt: Long
)
