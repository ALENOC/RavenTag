package io.raventag.app.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * D-15: binds BiometricPrompt authentication to a Keystore decrypt operation via
 * `BiometricPrompt.CryptoObject`. Authentication is NOT a boolean flag; no auth, no
 * plaintext.
 *
 * Caller constructs a fresh instance per reveal. Not thread-safe on purpose.
 */
class BiometricGate(private val activity: FragmentActivity) {

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
                            ?: return cont.resumeWithException(
                                IllegalStateException("no cipher bound")
                            )
                        cont.resume(c.doFinal(ciphertext))
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    cont.resumeWithException(
                        BiometricCancelledException(code, msg.toString())
                    )
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(titleRes))
            .setSubtitle(activity.getString(subtitleRes))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}

class BiometricCancelledException(
    val code: Int,
    message: String
) : RuntimeException(message)
