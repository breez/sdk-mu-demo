import breez_sdk_spark.Network
import java.net.URI

/** Which signer backend the deployment runs. Users record the mode they were
 * provisioned with; requests hard-fail when the two disagree. */
enum class SignerMode { SEED, TURNKEY }

/**
 * Turnkey API access for SIGNER=turnkey. One organization-level API keypair
 * stamps every request (provisioning here, signing inside the SDK). Use a
 * P-256 key — Turnkey's console default; the app's stamper signs it with
 * plain JCA and the SDK detects the curve from the key material.
 */
data class TurnkeySettings(
    val baseUrl: String,
    val organizationId: String,
    /** API public key (compressed, hex), registered with the organization. */
    val apiPublicKey: String,
    /** API private key (hex scalar) used to stamp requests. */
    val apiPrivateKey: String,
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
