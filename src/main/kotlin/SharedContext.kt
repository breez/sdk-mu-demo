import breez_sdk_spark.SdkContext
import breez_sdk_spark.SdkContextConfig
import breez_sdk_spark.defaultMysqlStorageConfig
import breez_sdk_spark.newSharedSdkContext

/**
 * Per-process bundle of expensive, share-safe resources: HTTP client,
 * operator gRPC channels, Breez backend gRPC client, MySQL pool, mainnet
 * JWT provider. Built once at boot and threaded into every per-request
 * SdkBuilder via `withSharedContext`.
 *
 * Without this sharing each request would open its own MySQL pool, redo
 * TCP+TLS+HTTP/2 to the SSP, and redial every operator — dominating
 * latency and exhausting FDs / ephemeral ports under load.
 */
suspend fun buildSharedContext(cfg: AppConfig): SdkContext {
    val mysql = defaultMysqlStorageConfig(cfg.mysqlUrl).also {
        // Recycle pool connections every 5 min — bench default.
        it.recycleTimeoutSecs = 300UL
    }
    return newSharedSdkContext(
        SdkContextConfig(
            network = cfg.network,
            apiKey = cfg.breezApiKey,        // null on regtest is fine
            connectionsPerOperator = null,
            mysqlConfig = mysql,
        )
    )
}
