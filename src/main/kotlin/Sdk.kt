import breez_sdk_spark.BreezSdk
import breez_sdk_spark.Config
import breez_sdk_spark.SdkBuilder
import breez_sdk_spark.SdkContext
import breez_sdk_spark.Seed
import breez_sdk_spark.TurnkeyConfig
import breez_sdk_spark.TurnkeyRetryConfig
import breez_sdk_spark.createTurnkeySigner
import breez_sdk_spark.defaultServerConfig
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import org.slf4j.LoggerFactory

/**
 * A user exists under the other signer backend than this deployment runs.
 * Serving them anyway would present an empty wallet under different keys, so
 * requests fail loudly instead (409 via StatusPages).
 */
class SignerMismatchException(message: String) : RuntimeException(message)

/**
 * Server-mode SDK access. Each call:
 *   1. obtains the user's signer — seed mode derives it from the master
 *      secret (HMAC-SHA512); turnkey mode builds remote signers against the
 *      user's Turnkey wallet (keys stay in the enclave),
 *   2. builds an SDK over the shared context with `defaultServerConfig`,
 *   3. runs `op`,
 *   4. disconnects (flushes outstanding writes).
 *
 * Signers are rebuilt every request — including turnkey mode's setup round
 * trips to Turnkey — keeping the per-request lifecycle fully stateless.
 *
 * Concurrent same-`userId` calls run in parallel — no per-user serialization.
 * Operator-level single-spend (FROST), SDK claim retries, and idempotent
 * tree-store writes make that safe.
 */
class SdkAccess(
    private val masterSecret: ByteArray,
    private val sharedContext: SdkContext,
    private val network: breez_sdk_spark.Network,
    private val apiKey: String?,
    private val eventBus: EventBus,
    private val signerMode: SignerMode,
    private val turnkey: TurnkeySettings?,
    private val ds: DataSource,
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
        val row = loadSignerRow(userId)
            ?: throw IllegalStateException("no users row for $userId")
        val mode = signerMode.name.lowercase()
        if (row.signer != mode) {
            throw SignerMismatchException(
                "user $userId was provisioned with signer '${row.signer}' but this deployment runs '$mode'"
            )
        }
        return withSigner(userId, row.turnkeyWalletId, op)
    }

    /**
     * Like [withUser] for a user whose row doesn't exist yet — POST /users
     * registers the webhook (a full SDK connect) before inserting the row,
     * so the signer inputs are passed explicitly.
     */
    suspend fun <T> withProvisionalUser(
        userId: String,
        turnkeyWalletId: String?,
        op: suspend (BreezSdk) -> T,
    ): T = withSigner(userId, turnkeyWalletId, op)

    private suspend fun <T> withSigner(
        userId: String,
        turnkeyWalletId: String?,
        op: suspend (BreezSdk) -> T,
    ): T {
        // SdkBuilder's `with*` methods return Unit (mutating in place) in
        // the KMP bindings, so call them as statements — not chained.
        val builder = when (signerMode) {
            SignerMode.SEED ->
                SdkBuilder(baseConfig, Seed.Entropy(deriveSeed(masterSecret, userId)))
            SignerMode.TURNKEY -> {
                val settings = checkNotNull(turnkey) { "SIGNER=turnkey without TurnkeySettings" }
                val walletId = checkNotNull(turnkeyWalletId) {
                    "user $userId has signer 'turnkey' but no turnkey_wallet_id"
                }
                val signers = createTurnkeySigner(
                    TurnkeyConfig(
                        baseUrl = settings.baseUrl,
                        organizationId = settings.organizationId,
                        apiPublicKey = settings.apiPublicKey,
                        apiPrivateKey = settings.apiPrivateKey,
                        walletId = walletId,
                        network = network,
                        // Null = the SDK's per-network default, the same
                        // account the provisioner seeded the wallet with.
                        accountNumber = null,
                        retry = TurnkeyRetryConfig(
                            // The Rust defaults, restated: uniffi records
                            // don't carry Default impls across the FFI.
                            initialDelayMs = 500uL,
                            multiplier = 2.0,
                            maxDelayMs = 5_000uL,
                            maxRetries = 5u,
                            requestTimeoutMs = 60_000uL,
                        ),
                    )
                )
                SdkBuilder.newWithSigner(baseConfig, signers.breez, signers.spark)
            }
        }
        builder.withSharedContext(sharedContext)
        val sdk = builder.build()
        sdk.addEventListener(EventBridge(userId, eventBus))
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

    private data class SignerRow(val signer: String, val turnkeyWalletId: String?)

    private fun loadSignerRow(userId: String): SignerRow? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT signer, turnkey_wallet_id FROM users WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) SignerRow(rs.getString(1), rs.getString(2)) else null
                }
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
