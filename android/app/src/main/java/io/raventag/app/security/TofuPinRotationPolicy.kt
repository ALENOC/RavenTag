package io.raventag.app.security

/** Pure authorization gate for an explicit TLS pin rotation. */
object TofuPinRotationPolicy {
    fun isAuthorized(
        mismatch: TofuMismatch?,
        expectedFingerprint: String,
        observedFingerprint: String,
        explicitConfirmation: Boolean,
        allowUntrusted: Boolean
    ): Boolean = explicitConfirmation &&
        mismatch != null &&
        mismatch.expectedFingerprint == expectedFingerprint &&
        mismatch.observedFingerprint == observedFingerprint &&
        (mismatch.systemTrusted || allowUntrusted)
}
