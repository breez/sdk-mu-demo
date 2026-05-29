import breez_sdk_spark.Network
import java.net.URI

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
