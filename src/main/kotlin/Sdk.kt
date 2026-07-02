import breez_sdk_spark.BreezSdk
import breez_sdk_spark.Config
import breez_sdk_spark.SdkBuilder
import breez_sdk_spark.SdkContext
import breez_sdk_spark.Seed
import breez_sdk_spark.SignerException
import breez_sdk_spark.TurnkeyConfig
import breez_sdk_spark.TurnkeyProvisionedSigner
import breez_sdk_spark.TurnkeyRetryConfig
import breez_sdk_spark.createTurnkeySigner
import breez_sdk_spark.defaultServerConfig
import breez_sdk_spark.provisionTurnkeySigner
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
 *      secret (HMAC-SHA512); turnkey mode rebuilds remote signers against the
 *      user's Turnkey wallet from the persisted provisioning blob, with NO
 *      Turnkey round trips (keys stay in the enclave),
 *   2. builds an SDK over the shared context with `defaultServerConfig`,
 *   3. runs `op`,
 *   4. disconnects (flushes outstanding writes).
 *
 * The per-request lifecycle stays stateless. What used to make turnkey mode
 * expensive — rebuilding the signer meant materializing the identity account
 * and exporting the ECIES/HMAC key over the network on every request — is now a
 * one-time [provisionSigner] at user creation whose result is persisted
 * (encrypted) in the users row. [createTurnkeySigner] rebuilds from that blob
 * without touching Turnkey. If the SDK rejects the blob as outdated (a version
 * bump) or a user has none yet, we re-provision once and persist.
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

    suspend fun <T> withUser(userId: String, op: suspend (BreezSdk) -> T): T =
        withUserContext(userId) { sdk, _ -> op(sdk) }

    /**
     * Like [withUser], but the op also receives the [UserContext] read from the
     * same users row that drives signer setup — so a route needing both the SDK
     * and per-user fields (e.g. the turnkey spark address) does it with a single
     * row read instead of a separate query.
     */
    suspend fun <T> withUserContext(userId: String, op: suspend (BreezSdk, UserContext) -> T): T {
        val row = loadSignerRow(userId)
            ?: throw IllegalStateException("no users row for $userId")
        val mode = signerMode.name.lowercase()
        if (row.signer != mode) {
            throw SignerMismatchException(
                "user $userId was provisioned with signer '${row.signer}' but this deployment runs '$mode'"
            )
        }
        val ctx = UserContext(turnkeySparkAddress = row.turnkeySparkAddress)
        // Decrypt the persisted provisioning blob (turnkey only; null until the
        // user is provisioned, then rebuilt network-free from it).
        val provisioned = row.turnkeyProvisioned
            ?.let { ProvisionCrypto.decrypt(masterSecret, userId, it) }
            ?.let { TurnkeyProvisionedSigner(it) }
        return withSigner(userId, row.turnkeySubOrgId, row.turnkeyWalletId, provisioned) { sdk ->
            op(sdk, ctx)
        }
    }

    /**
     * Like [withUser] for a user whose row doesn't exist yet — POST /users
     * registers the webhook (a full SDK connect) before inserting the row,
     * so the signer inputs are passed explicitly. `provisioned` is the blob
     * [provisionSigner] just produced, so even this first connect is
     * network-free. The connect runs under the delegated key, proving it can
     * authenticate and receive on the fresh sub-org before the user is visible.
     */
    suspend fun <T> withProvisionalUser(
        userId: String,
        turnkeySubOrgId: String?,
        turnkeyWalletId: String?,
        provisioned: TurnkeyProvisionedSigner?,
        op: suspend (BreezSdk) -> T,
    ): T = withSigner(userId, turnkeySubOrgId, turnkeyWalletId, provisioned, op)

    /**
     * One-time Turnkey signer provisioning for a freshly created wallet. This is
     * the only call that pays the Turnkey init round trips (materialize the
     * identity account, export the ECIES/HMAC key). The returned blob is
     * persisted (encrypted) by the caller and replayed on every later init.
     */
    suspend fun provisionSigner(subOrgId: String, walletId: String): TurnkeyProvisionedSigner =
        provisionTurnkeySigner(turnkeyConfig(subOrgId, walletId))

    private suspend fun <T> withSigner(
        userId: String,
        turnkeySubOrgId: String?,
        turnkeyWalletId: String?,
        provisioned: TurnkeyProvisionedSigner?,
        op: suspend (BreezSdk) -> T,
    ): T {
        // SdkBuilder's `with*` methods return Unit (mutating in place) in
        // the KMP bindings, so call them as statements — not chained.
        val builder = when (signerMode) {
            SignerMode.SEED ->
                SdkBuilder(baseConfig, Seed.Entropy(deriveSeed(masterSecret, userId)))
            SignerMode.TURNKEY -> {
                checkNotNull(turnkey) { "SIGNER=turnkey without TurnkeySettings" }
                val subOrgId = checkNotNull(turnkeySubOrgId) {
                    "user $userId has signer 'turnkey' but no turnkey_sub_org_id"
                }
                val walletId = checkNotNull(turnkeyWalletId) {
                    "user $userId has signer 'turnkey' but no turnkey_wallet_id"
                }
                // The DELEGATED key on the user's sub-org. Turnkey policy lets it
                // authenticate, claim/receive and FROST-sign, but denies
                // SPARK_PREPARE_TRANSFER — so this server-side SDK can never move
                // funds out. Sends go through build/publishSignedTransferPackage
                // (Send.kt), with the user's passkey producing the transfer
                // signature client-side.
                val signers = turnkeySigners(userId, subOrgId, walletId, provisioned)
                SdkBuilder.newWithSigner(baseConfig, signers.breezSigner, signers.sparkSigner)
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

    /** The delegated-key Turnkey config for a user's sub-org/wallet. */
    private fun turnkeyConfig(subOrgId: String, walletId: String): TurnkeyConfig {
        val settings = checkNotNull(turnkey) { "SIGNER=turnkey without TurnkeySettings" }
        return TurnkeyConfig(
            baseUrl = settings.baseUrl,
            organizationId = subOrgId,
            apiPublicKey = settings.delegatedApiPublicKey,
            apiPrivateKey = settings.delegatedApiPrivateKey,
            walletId = walletId,
            network = network,
            // Null = the SDK's per-network default, the same account the
            // provisioner seeded the wallet with.
            accountNumber = null,
            retry = TurnkeyRetryConfig(
                // The Rust defaults, restated: uniffi records don't carry
                // Default impls across the FFI.
                initialDelayMs = 500uL,
                multiplier = 2.0,
                maxDelayMs = 5_000uL,
                maxRetries = 5u,
                requestTimeoutMs = 60_000uL,
            ),
        )
    }

    /**
     * Builds the per-user Turnkey signers with NO Turnkey round trips, from the
     * persisted provisioning blob. Self-healing: if the user has no blob yet
     * (provisioned lazily, or a row predating this feature) or the SDK rejects
     * it as [SignerException.ProvisioningOutdated] (an SDK version bump), we
     * (re)provision once — the only network cost — persist it, and rebuild.
     */
    private suspend fun turnkeySigners(
        userId: String,
        subOrgId: String,
        walletId: String,
        provisioned: TurnkeyProvisionedSigner?,
    ): breez_sdk_spark.ExternalSigners {
        val config = turnkeyConfig(subOrgId, walletId)
        val prov = provisioned ?: provisionAndPersist(userId, subOrgId, walletId)
        return try {
            createTurnkeySigner(config, prov)
        } catch (e: SignerException.ProvisioningOutdated) {
            log.info("turnkey provisioning outdated (user={}): re-provisioning", userId)
            createTurnkeySigner(config, provisionAndPersist(userId, subOrgId, walletId))
        }
    }

    /** Provisions the signer (network) and persists the encrypted blob. */
    private suspend fun provisionAndPersist(
        userId: String,
        subOrgId: String,
        walletId: String,
    ): TurnkeyProvisionedSigner {
        val prov = provisionTurnkeySigner(turnkeyConfig(subOrgId, walletId))
        persistProvisioned(userId, prov)
        return prov
    }

    /** Stores the provisioning blob, encrypted, on the user's row. */
    private fun persistProvisioned(userId: String, prov: TurnkeyProvisionedSigner) {
        val enc = ProvisionCrypto.encrypt(masterSecret, userId, prov.bytes)
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE users SET turnkey_provisioned = ? WHERE user_id = ?").use { ps ->
                ps.setBytes(1, enc)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
        }
    }

    private data class SignerRow(
        val signer: String,
        val turnkeySubOrgId: String?,
        val turnkeyWalletId: String?,
        val turnkeySparkAddress: String?,
        /** Encrypted provisioning blob (see [ProvisionCrypto]); null until provisioned. */
        val turnkeyProvisioned: ByteArray?,
    )

    private fun loadSignerRow(userId: String): SignerRow? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT signer, turnkey_sub_org_id, turnkey_wallet_id, turnkey_spark_address, turnkey_provisioned FROM users WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) {
                        SignerRow(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getBytes(5),
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }
}

/** Per-user fields read alongside the signer row and handed to a
 * [SdkAccess.withUserContext] op, so one users-row read serves both. */
data class UserContext(val turnkeySparkAddress: String?)

/** HMAC-SHA512(masterSecret, userId) → 64-byte wallet entropy. */
fun deriveSeed(masterSecret: ByteArray, userId: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(masterSecret, "HmacSHA512"))
    return mac.doFinal(userId.toByteArray(Charsets.UTF_8))
}
