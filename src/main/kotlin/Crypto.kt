import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption for the persisted Turnkey provisioning blob
 * (`users.turnkey_provisioned`). The blob holds the exported non-Spark
 * ECIES/HMAC key; it can't move funds or derive a Spark key, but it's still a
 * per-user secret, so it is never stored in the clear.
 *
 * AES-256-GCM under a per-user key derived from the deployment `MASTER_SECRET`
 * (HMAC-SHA256, domain-separated + salted by userId). Output layout is
 * `nonce(12) || ciphertext || tag(16)`; the nonce is random per encryption.
 */
object ProvisionCrypto {
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private val RNG = SecureRandom()

    /** HMAC-SHA256(masterSecret, "turnkey-provision:" + userId) → 32-byte key. */
    private fun deriveKey(masterSecret: ByteArray, userId: String): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterSecret, "HmacSHA256"))
        val key = mac.doFinal("turnkey-provision:$userId".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(key, "AES")
    }

    fun encrypt(masterSecret: ByteArray, userId: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { RNG.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(masterSecret, userId), GCMParameterSpec(TAG_BITS, nonce))
        }
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(masterSecret: ByteArray, userId: String, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_LEN) { "provisioning blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(masterSecret, userId), GCMParameterSpec(TAG_BITS, nonce))
        }
        return cipher.doFinal(blob, NONCE_LEN, blob.size - NONCE_LEN)
    }
}
