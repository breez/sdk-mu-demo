import breez_sdk_spark.LogEntry
import breez_sdk_spark.Logger
import breez_sdk_spark.initLogging
import org.slf4j.LoggerFactory

private val sdkLog = LoggerFactory.getLogger("breez_sdk")

private object Slf4jSdkLogger : Logger {
    override fun log(l: LogEntry) {
        when (l.level.uppercase()) {
            "ERROR" -> sdkLog.error(l.line)
            "WARN" -> sdkLog.warn(l.line)
            "INFO" -> sdkLog.info(l.line)
            "DEBUG" -> sdkLog.debug(l.line)
            else -> sdkLog.trace(l.line)
        }
    }
}

/**
 * Bridge the SDK's Rust `tracing` output into slf4j. Call once at boot, before
 * building any SDK. `filter` is EnvFilter syntax ("info", "info,spark=debug")
 * and is the single control point for SDK verbosity — the `breez_sdk` logback
 * logger is pinned to TRACE so logback never re-filters what crosses the FFI.
 *
 * Process-global: a single subscriber serves every SDK instance, so lines are
 * not attributable to a specific user. Correlate to a user by time window
 * against the app's own userId-tagged logs.
 */
fun initSdkLogging(filter: String) = initLogging(null, Slf4jSdkLogger, filter)
