import breez_sdk_spark.SdkContext
import breez_sdk_spark.SdkContextConfig
import breez_sdk_spark.defaultPostgresStorageConfig
import breez_sdk_spark.newSharedSdkContext
import breez_sdk_spark.postgresStorage

/**
 * Per-process bundle of expensive, share-safe resources: HTTP client,
 * operator gRPC channels, Breez backend gRPC client, Postgres pool, mainnet
 * JWT provider. Built once at boot and threaded into every per-request
 * SdkBuilder via `withSharedContext`.
 *
 * Without this sharing each request would open its own Postgres pool, redo
 * TCP+TLS+HTTP/2 to the SSP, and redial every operator — dominating
 * latency and exhausting FDs / ephemeral ports under load.
 */
suspend fun buildSharedContext(cfg: AppConfig): SdkContext {
    val postgres = defaultPostgresStorageConfig(cfg.databaseUrl).apply {
        waitTimeoutSecs = 15uL
        createTimeoutSecs = 15uL
        recycleTimeoutSecs = 10uL
        // The SDK default is `num_cpus * 4` — only 4 on a 1-CPU box, shared by
        // every per-request SDK build, webhook sync, and the optimize queue.
        // Pin it explicitly when configured so it doesn't track core count.
        cfg.sdkPgMaxPoolSize?.let { maxPoolSize = it.toUInt() }
    }
    val storage = postgresStorage(postgres)
    return newSharedSdkContext(
        SdkContextConfig(
            network = cfg.network,
            apiKey = cfg.breezApiKey,        // null on regtest is fine
            connectionsPerOperator = null,
            storage = storage,
        )
    )
}
