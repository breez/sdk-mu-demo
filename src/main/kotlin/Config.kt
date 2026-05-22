import breez_sdk_spark.Network
import java.net.URI

/**
 * App config, loaded once at boot from env. Fails fast on missing required
 * vars. `.env.example` documents every field.
 */
data class AppConfig(
    val network: Network,
    val mysqlUrl: String,
    val masterSecret: String,
    val webhookSecret: String,
    val publicBaseUrl: String,
    /** Only required on mainnet (Breez partner JWT). Null on regtest. */
    val breezApiKey: String?,
    val port: Int,
    val corsOrigins: List<String>,
) {
    /** Hikari + Flyway want a jdbc:* URL with credentials supplied separately. */
    val mysql: MysqlDsn = MysqlDsn.parse(mysqlUrl)

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
                mysqlUrl = required("MYSQL_URL"),
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
            )
        }
    }
}

/**
 * Parsed `mysql://user:pass@host:port/db` (or `jdbc:mysql://…`). The SDK
 * accepts the original URL string; Hikari + Flyway need the JDBC form plus
 * username/password fields broken out.
 */
data class MysqlDsn(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    companion object {
        fun parse(raw: String): MysqlDsn {
            val noJdbc = raw.removePrefix("jdbc:")
            val uri = URI(noJdbc)
            require(uri.scheme.equals("mysql", ignoreCase = true)) {
                "MYSQL_URL scheme must be mysql:// (was ${uri.scheme})"
            }
            val ui = uri.userInfo?.split(':', limit = 2) ?: emptyList()
            val user = ui.getOrNull(0)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } ?: ""
            val pass = ui.getOrNull(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) } ?: ""
            val host = uri.host ?: error("MYSQL_URL missing host")
            val port = if (uri.port < 0) 3306 else uri.port
            val db = uri.path.orEmpty().trimStart('/')
            require(db.isNotEmpty()) { "MYSQL_URL missing /database path" }
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return MysqlDsn(
                jdbcUrl = "jdbc:mysql://$host:$port/$db$query",
                user = user,
                password = pass,
            )
        }
    }
}
