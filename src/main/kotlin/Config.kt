import breez_sdk_spark.Network
import java.net.URI

/** Which signer backend the deployment runs. Users record the mode they were
 * provisioned with; requests hard-fail when the two disagree. */
enum class SignerMode { SEED, TURNKEY }

/**
 * Turnkey API access for SIGNER=turnkey, under the delegated-access model
 * (client-approved sends).
 *
 * Two P-256 keypairs, by design distinct:
 *
 *  - **parent admin** (`apiPublicKey`/`apiPrivateKey`, registered with the
 *    parent `organizationId`) — used only to create a per-user
 *    sub-organization at `POST /users`.
 *  - **delegated** (`delegatedApiPublicKey`/`delegatedApiPrivateKey`) — the
 *    backend's policy-scoped member of every sub-org. It signs the receive /
 *    auth / FROST activities the SDK runs per request, but Turnkey policy
 *    denies it `SPARK_PREPARE_TRANSFER`, so the server can never move funds
 *    out; only the user's passkey can. It also stamps the one-time policy and
 *    root-quorum setup during provisioning (while still a root user).
 *
 * They are distinct keypairs for blast radius, not because scoping needs it (a
 * sub-org member is policy-scoped however its keypair is shared): the delegated
 * key is hot (every request) while the admin key is used only at sign-up, so a
 * delegated-key leak is contained to receive/FROST on existing sub-orgs and
 * can't also spawn sub-orgs in the parent org. It also lets the rarely-used
 * admin key be protected/rotated independently of the hot path later.
 *
 * P-256 because the app's stamper signs with plain JCA and the SDK detects the
 * curve from the key material (the JDK doesn't ship secp256k1).
 */
data class TurnkeySettings(
    val baseUrl: String,
    /** Parent organization that owns the per-user sub-orgs. */
    val organizationId: String,
    /** Parent admin API public key (compressed, hex). Creates sub-orgs. */
    val apiPublicKey: String,
    /** Parent admin API private key (hex scalar). */
    val apiPrivateKey: String,
    /** Delegated API public key (compressed, hex), enrolled in every sub-org. */
    val delegatedApiPublicKey: String,
    /** Delegated API private key (hex scalar). Per-request receive/auth signing. */
    val delegatedApiPrivateKey: String,
)

/**
 * App config, loaded once at boot from env. Fails fast on missing required
 * vars. `.env.example` documents every field.
 */
data class AppConfig(
    val network: Network,
    val databaseUrl: String,
    val masterSecret: String,
    val webhookSecret: String,
    val publicBaseUrl: String,
    /** Only required on mainnet (Breez partner JWT). Null on regtest. */
    val breezApiKey: String?,
    val port: Int,
    val corsOrigins: List<String>,
    /** EnvFilter for the SDK's Rust logs (e.g. "info", "info,spark=debug"). */
    val sdkLogFilter: String,
    /**
     * Max connections in the SDK's shared Postgres pool. Null leaves the SDK
     * default (`num_cpus * 4`), which is just 4 on a 1-CPU box. Made explicit
     * so the pool size doesn't silently track the host's core count.
     */
    val sdkPgMaxPoolSize: Int?,
    val signer: SignerMode,
    /** Non-null iff signer == TURNKEY. */
    val turnkey: TurnkeySettings?,
) {
    /** Hikari + Flyway want a jdbc:* URL with credentials supplied separately. */
    val postgres: PostgresDsn = PostgresDsn.parse(databaseUrl)

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): AppConfig {
            fun required(name: String): String =
                env(name)?.takeIf { it.isNotBlank() }
                    ?: error("Missing required env var: $name (see .env.example)")

            val networkStr = required("NETWORK").lowercase()
            val network = when (networkStr) {
                "mainnet" -> Network.MAINNET
                "regtest" -> Network.REGTEST
                else -> error("NETWORK must be 'mainnet' or 'regtest'; got '$networkStr'")
            }

            val breezApiKey = env("BREEZ_API_KEY")?.takeIf { it.isNotBlank() }
            if (network == Network.MAINNET && breezApiKey == null) {
                error("BREEZ_API_KEY is required on mainnet")
            }

            val signerStr = env("SIGNER")?.takeIf { it.isNotBlank() }?.lowercase() ?: "seed"
            val signer = when (signerStr) {
                "seed" -> SignerMode.SEED
                "turnkey" -> SignerMode.TURNKEY
                else -> error("SIGNER must be 'seed' or 'turnkey'; got '$signerStr'")
            }
            val turnkey = if (signer == SignerMode.TURNKEY) {
                TurnkeySettings(
                    baseUrl = env("TURNKEY_BASE_URL")?.takeIf { it.isNotBlank() }?.trimEnd('/')
                        ?: "https://api.turnkey.com",
                    organizationId = required("TURNKEY_ORG_ID"),
                    apiPublicKey = required("TURNKEY_API_PUBLIC_KEY"),
                    apiPrivateKey = required("TURNKEY_API_PRIVATE_KEY"),
                    delegatedApiPublicKey = required("TURNKEY_DELEGATED_API_PUBLIC_KEY"),
                    delegatedApiPrivateKey = required("TURNKEY_DELEGATED_API_PRIVATE_KEY"),
                )
            } else {
                null
            }

            return AppConfig(
                network = network,
                databaseUrl = required("DATABASE_URL"),
                masterSecret = required("MASTER_SECRET"),
                webhookSecret = required("WEBHOOK_SECRET"),
                publicBaseUrl = required("PUBLIC_BASE_URL").trimEnd('/'),
                breezApiKey = breezApiKey,
                port = env("PORT")?.toIntOrNull() ?: 8080,
                corsOrigins = env("CORS_ORIGINS")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                sdkLogFilter = env("SDK_LOG_FILTER")?.takeIf { it.isNotBlank() } ?: "info",
                sdkPgMaxPoolSize = env("SDK_PG_MAX_POOL_SIZE")?.takeIf { it.isNotBlank() }?.let {
                    it.toIntOrNull()?.takeIf { n -> n > 0 }
                        ?: error("SDK_PG_MAX_POOL_SIZE must be a positive integer; got '$it'")
                },
                signer = signer,
                turnkey = turnkey,
            )
        }
    }
}

/**
 * Parsed `postgres://user:pass@host:port/db` (or `postgresql://…` / `jdbc:postgresql://…`).
 * The SDK accepts the original URL string; Hikari + Flyway need the JDBC form plus
 * username/password fields broken out.
 */
data class PostgresDsn(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    companion object {
        fun parse(raw: String): PostgresDsn {
            val noJdbc = raw.removePrefix("jdbc:")
            val uri = URI(noJdbc)
            require(uri.scheme.equals("postgres", ignoreCase = true) ||
                uri.scheme.equals("postgresql", ignoreCase = true)) {
                "DATABASE_URL scheme must be postgres:// or postgresql:// (was ${uri.scheme})"
            }
            val ui = uri.userInfo?.split(':', limit = 2) ?: emptyList()
            val user = ui.getOrNull(0)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } ?: ""
            val pass = ui.getOrNull(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } ?: ""
            val host = uri.host ?: error("DATABASE_URL missing host")
            val port = if (uri.port < 0) 5432 else uri.port
            val db = uri.path.orEmpty().trimStart('/')
            require(db.isNotEmpty()) { "DATABASE_URL missing /database path" }
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return PostgresDsn(
                jdbcUrl = "jdbc:postgresql://$host:$port/$db$query",
                user = user,
                password = pass,
            )
        }
    }
}
