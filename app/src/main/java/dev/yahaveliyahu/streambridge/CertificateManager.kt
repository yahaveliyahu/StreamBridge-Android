package dev.yahaveliyahu.streambridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Manages a self-signed TLS certificate stored in the Android Keystore.
 *
 * The certificate is generated once on first run and persists forever.
 * The private key never leaves the secure element — only the public cert bytes
 * are ever shared (during pairing) so Windows can pin to exactly this certificate.
 */
class CertificateManager {

    companion object {
        private const val TAG = "CertificateManager"
        private const val KEY_ALIAS = "streambridge_tls_v4"
//        private const val OLD_ALIAS3 = "streambridge_tls_v3"
//        private const val OLD_ALIAS2 = "streambridge_tls_v2"
//        private const val OLD_ALIAS = "streambridge_tls"
        private const val ANDROID_KS = "AndroidKeyStore"
    }

    // Lazy: loads/opens the AndroidKeyStore on first access.
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KS).also { it.load(null) }
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Ensures the TLS key pair exists; generates one the very first time.
     * Safe to call multiple times — is a no-op after first generation.
     */
    fun ensureCertificate() {
//        if (keyStore.containsAlias(OLD_ALIAS)) {
//            keyStore.deleteEntry(OLD_ALIAS)
//            Log.d(TAG, "Removed RSA v1 key")
//        }
//        if (keyStore.containsAlias(OLD_ALIAS2)) {
//            keyStore.deleteEntry(OLD_ALIAS2)
//            Log.d(TAG, "Removed RSA v2 key")
//        }
//        if (keyStore.containsAlias(OLD_ALIAS3)) {
//            keyStore.deleteEntry(OLD_ALIAS3)
//            Log.d(TAG, "Removed ECDSA v3 key")
//        }

        if (keyStore.containsAlias(KEY_ALIAS)) return

        Log.d(TAG, "Generating new TLS key pair in Android Keystore…")
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KS)
            .apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        // PURPOSE_SIGN    — used by TLS for certificate signatures
                        // PURPOSE_DECRYPT — needed for TLS 1.2 RSA key-exchange fallback
                        KeyProperties.PURPOSE_SIGN  // TLS 1.2 (ECDHE) + TLS 1.3 only need signing
                    )
                        // P-256 is the standard TLS curve — supported by all JVMs and Android
                        .setAlgorithmParameterSpec(
                            java.security.spec.ECGenParameterSpec("secp256r1")
                        )
                        .setDigests(
                            KeyProperties.DIGEST_NONE,
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512)
                        .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=StreamBridge"))
                        .setCertificateSerialNumber(java.math.BigInteger.ONE)
                        .build()
                )
            }
            .generateKeyPair()

        Log.d(TAG, "ECDSA key generated. Fingerprint: ${getFingerprint()}")
    }

    /**
     * Returns an SSLContext whose KeyManager routes signing operations through
     * the Android Keystore.  The private key is never exposed in memory.
     */
    fun getSSLContext(): SSLContext {
        ensureCertificate()
        val kmf = KeyManagerFactory.getInstance("X509")
        kmf.init(keyStore, null)                         // null = no password for AndroidKeyStore
        return SSLContext.getInstance("TLS").also {
            it.init(kmf.keyManagers, null, SecureRandom())
        }
    }

    /**
     * Raw DER bytes of the self-signed X.509 certificate.
     * Safe to transmit — this is the public portion only.
     */
    fun getCertificateBytes(): ByteArray {
        ensureCertificate()
        return (keyStore.getCertificate(KEY_ALIAS) as X509Certificate).encoded
    }

    /**
     * Base64 (no line breaks) of the DER cert — safe to embed in a JSON string.
     */
    fun getCertificateBase64(): String = Base64.encodeToString(getCertificateBytes(), Base64.NO_WRAP)

    /**
     * SHA-256 fingerprint formatted as colon-separated hex pairs, e.g. "A1:B2:C3:…".
     * Used by Windows to verify it is talking to the exact phone it paired with.
     */
    fun getFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(getCertificateBytes())
        return digest.joinToString(":") { "%02X".format(it) }
    }
}