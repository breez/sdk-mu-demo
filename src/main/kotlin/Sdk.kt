import breez_sdk_spark.BreezSdk
import breez_sdk_spark.Config
import breez_sdk_spark.SdkBuilder
import breez_sdk_spark.SdkContext
import breez_sdk_spark.Seed
import breez_sdk_spark.defaultServerConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

/**
 * Server-mode SDK access. Each call:
 *   1. derives the user's seed from the master secret (HMAC-SHA512),
 *   2. builds an SDK over the shared context with `defaultServerConfig`,
 *   3. runs `op`,
 *   4. disconnects (flushes outstanding writes).
 *
 * Concurrent same-`userId` calls run in parallel — no per-user serialization.
 * Operator-level single-spend (FROST), SDK claim retries, and idempotent
 * tree-store writes make that safe.
 */
class SdkAccess(
    private val masterSecret: ByteArray,
    private val sharedContext: SdkContext,
    network: breez_sdk_spark.Network,
    private val apiKey: String?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val baseConfig: Config = defaultServerConfig(network).also {
        // SdkContext cross-checks (Config.api_key, Config.network) on
        // build() and rejects a mismatch. The context is built once with
        // these values; every per-request Config must reuse them.
        it.apiKey = apiKey
        // Keep BOLT11 invoices pure Lightning — don't embed a spark address
        // hint. Already false in defaultServerConfig, set explicitly so the
        // intent survives an upstream default flip.
        it.preferSparkOverLightning = false
    }

    suspend fun <T> withUser(userId: String, op: suspend (BreezSdk) -> T): T {
        val seed: Seed = Seed.Entropy(deriveSeed(masterSecret, userId))
        // SdkBuilder's `with*` methods return Unit (mutating in place) in
        // the KMP bindings, so call them as statements — not chained.
        val builder = SdkBuilder(baseConfig, seed)
        builder.withSharedContext(sharedContext)
        val sdk = builder.build()
        return try {
            op(sdk)
        } finally {
            try {
                sdk.disconnect()
            } catch (e: Exception) {
                log.warn("disconnect warning (user={}): {}", userId, e.message)
            }
        }
    }
}

/** HMAC-SHA512(masterSecret, userId) → 64-byte wallet entropy. */
fun deriveSeed(masterSecret: ByteArray, userId: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(masterSecret, "HmacSHA512"))
    return mac.doFinal(userId.toByteArray(Charsets.UTF_8))
}
