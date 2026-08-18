package io.raventag.app.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Fail-closed authentication gate used by wallet secret-reveal operations.
 *
 * Two modes are intentionally exposed:
 *  - [authenticate] establishes a recent OS authentication for Keystore keys
 *    configured with a short user-authentication validity window.
 *  - [decryptWithBiometric] binds a CryptoObject directly to a prompt for keys
 *    configured for per-operation authentication.
 *
 * No error, cancellation, hardware problem or missing enrollment is ever mapped
 * to success.
 */
class BiometricGate(private val activity: FragmentActivity) {

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    private fun promptInfo(titleRes: Int, subtitleRes: Int): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(titleRes))
            .setSubtitle(activity.getString(subtitleRes))
            .setAllowedAuthenticators(authenticators())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText("Cancel")
        }
        return builder.build()
    }

    /** Authenticate the user and return only after a genuine OS-auth success. */
    suspend fun authenticate(titleRes: Int, subtitleRes: Int): Unit =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (cont.isActive) {
                            cont.resumeWithException(BiometricCancelledException(code, msg.toString()))
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // A failed attempt is not success; BiometricPrompt may allow another attempt.
                    }
                }
            )
            try {
                prompt.authenticate(promptInfo(titleRes, subtitleRes))
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    suspend fun decryptWithBiometric(
        cipher: Cipher,
        ciphertext: ByteArray,
        titleRes: Int,
        subtitleRes: Int
    ): ByteArray = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val c = result.cryptoObject?.cipher
                            ?: return cont.resumeWithException(IllegalStateException("no cipher bound"))
                        cont.resume(c.doFinal(ciphertext))
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    cont.resumeWithException(BiometricCancelledException(code, msg.toString()))
                }
            }
        )
        try {
            prompt.authenticate(promptInfo(titleRes, subtitleRes), BiometricPrompt.CryptoObject(cipher))
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
        }
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}

class BiometricCancelledException(
    val code: Int,
    message: String
) : RuntimeException(message)
