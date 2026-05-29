import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.time.Duration as JDuration
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import routes.deposits
import routes.events
import routes.info
import routes.payments
import routes.receive
import routes.send
import routes.webhooks

private val log = LoggerFactory.getLogger("Main")

private val CREATE_USER_LIMIT = RateLimitName("create_user")

fun main(): Unit = runBlocking {
    val cfg = AppConfig.fromEnv()
    initSdkLogging(cfg.sdkLogFilter)

    val ds = buildDataSource(cfg)
    migrate(ds)

    log.info("building shared SDK context (network={})", cfg.network)
    val sharedCtx = buildSharedContext(cfg)
    val eventBus = EventBus()
    val sdk = SdkAccess(
        masterSecret = cfg.masterSecret.toByteArray(Charsets.UTF_8),
        sharedContext = sharedCtx,
        network = cfg.network,
        apiKey = cfg.breezApiKey,
        eventBus = eventBus,
    )

    val optimizer = OptimizeQueue(sdk)
    val syncer = SyncQueue(sdk)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("shutting down")
        try { ds.close() } catch (e: Exception) { log.warn("HikariCP close: {}", e.message) }
    })

    log.info("listening on :{}", cfg.port)
    embeddedServer(Netty, port = cfg.port) {
        install(CallLogging) {
            // Skip Fly's healthcheck polling; everything else is signal.
            filter { call -> call.request.path() !in setOf("/healthz", "/readyz") }
        }

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        install(WebSockets) {
            pingPeriod = JDuration.ofSeconds(30)
            timeout = JDuration.ofSeconds(60)
        }

        if (cfg.corsOrigins.isNotEmpty()) {
            install(CORS) {
                cfg.corsOrigins.forEach { allowHost(it.removePrefix("https://").removePrefix("http://"), schemes = listOf("http", "https")) }
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader("Idempotency-Key")
                allowCredentials = true
            }
        }

        install(RateLimit) {
            // Per-IP cap on POST /users — open registration is otherwise an
            // easy way to spam SSP-side webhook registrations + DB rows.
            register(CREATE_USER_LIMIT) {
                rateLimiter(limit = 10, refillPeriod = 1.minutes)
                requestKey { call -> call.request.local.remoteHost }
            }
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                log.error("uncaught: {}", cause.message, cause)
                call.respondError(
                    HttpStatusCode.InternalServerError,
                    ErrorCodes.INTERNAL,
                    cause.message ?: cause::class.qualifiedName ?: "internal error",
                )
            }
        }

        routing {
            health(ds)
            info(ds, sdk)
            payments(ds, sdk)
            receive(ds, sdk)
            send(ds, sdk, optimizer, syncer)
            deposits(ds, sdk)
            events(ds, eventBus)
            webhooks(cfg.webhookSecret, sdk, optimizer)
            rateLimit(CREATE_USER_LIMIT) {
                users(ds, sdk, cfg)
            }
        }
    }.start(wait = true)
}

private fun buildDataSource(cfg: AppConfig): HikariDataSource {
    val hc = HikariConfig().apply {
        jdbcUrl = cfg.postgres.jdbcUrl
        username = cfg.postgres.user
        password = cfg.postgres.password
        maximumPoolSize = 10
        minimumIdle = 1
        poolName = "sdk-mu-demo-app"
        // The SDK has its own Postgres pool (via SdkContext). This one is
        // app-only — auth lookups, /readyz pings.
    }
    return HikariDataSource(hc)
}

private fun migrate(ds: DataSource) {
    val flyway = Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
    flyway.migrate()
}
