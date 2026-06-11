import breez_sdk_spark.Network
import com.turnkey.types.V1ActivityResponse
import com.turnkey.types.V1ActivityStatus
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1CreateWalletIntent
import com.turnkey.types.V1CreateWalletRequest
import com.turnkey.types.V1Curve
import com.turnkey.types.V1GetActivityRequest
import com.turnkey.types.V1GetWalletsRequest
import com.turnkey.types.V1GetWalletsResponse
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1WalletAccountParams
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Minimal Turnkey client for wallet provisioning (SIGNER=turnkey).
 *
 * The SDK deliberately leaves wallet management to the integrator — this is
 * what "bring your own wallet" looks like server-side. Request/response
 * models come from Turnkey's official `com.turnkey:types` artifact; their
 * `http`/`stamper` packages are Android-only (AARs), so the transport is the
 * JDK HttpClient and the X-Stamp signature is plain JCA — which is also why
 * the API key must be P-256 (the JDK doesn't ship secp256k1).
 *
 * All signing during request handling happens inside the SDK; this client
 * only runs at POST /users.
 */

class TurnkeyException(message: String) : RuntimeException(message)

/** Demo wallets are name-prefixed so they're identifiable (and reapable) in
 * an organization shared with other tooling. */
const val TURNKEY_WALLET_NAME_PREFIX = "sdk-mu-demo-"

/** The `{account}` in every Spark derivation path (`m/8797555'/{account}'/…`),
 * matching the SDK's per-network default so either backend derives the same
 * paths for a given wallet. */
fun defaultAccountNumber(network: Network): Int =
    when (network) {
        Network.REGTEST -> 0
        else -> 1
    }

/**
 * Produces Turnkey's `X-Stamp` header value: base64url-no-pad JSON
 * `{publicKey, scheme, signature}`, where signature is hex DER ECDSA-SHA256
 * over the exact request body, signed by the P-256 API key.
 */
class P256Stamper(private val publicKeyHex: String, privateKeyHex: String) {
    private val privateKey = KeyFactory.getInstance("EC")
        .generatePrivate(ECPrivateKeySpec(BigInteger(1, hexToBytes(privateKeyHex)), P256_PARAMS))

    fun stamp(body: ByteArray): String {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(body)
            sign()
        }
        val json = Json.encodeToString(
            ApiStamp.serializer(),
            ApiStamp(publicKey = publicKeyHex, scheme = "SIGNATURE_SCHEME_TK_API_P256", signature = bytesToHex(der)),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    @Serializable
    data class ApiStamp(val publicKey: String, val scheme: String, val signature: String)

    companion object {
        private val P256_PARAMS: ECParameterSpec = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }
}

class TurnkeyProvisioner(
    private val settings: TurnkeySettings,
    private val network: Network,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val stamper = P256Stamper(settings.apiPublicKey, settings.apiPrivateKey)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /**
     * Creates the user's HD wallet (random 24-word seed inside Turnkey's
     * enclave) and returns its wallet id.
     *
     * Seeds BOTH identity-account formats in this one activity — compressed
     * (ECDSA) and Spark (BIP-340 Schnorr) — because Turnkey can't add a
     * second format to an occupied path later, and `create_turnkey_signer`
     * expects both to exist. A retried submit hits Turnkey's fingerprint
     * dedup (409); the wallet name is unique per user, so recover the id by
     * name in that case.
     */
    suspend fun createWallet(userId: String): String {
        val walletName = TURNKEY_WALLET_NAME_PREFIX + userId
        val account = defaultAccountNumber(network)
        val identityPath = "m/8797555'/$account'/0'"
        val identityAccount = { format: V1AddressFormat ->
            V1WalletAccountParams(
                curve = V1Curve.CURVE_SECP256K1,
                pathFormat = V1PathFormat.PATH_FORMAT_BIP32,
                path = identityPath,
                addressFormat = format,
            )
        }
        val sparkFormat = when (network) {
            Network.MAINNET -> V1AddressFormat.ADDRESS_FORMAT_SPARK_MAINNET
            else -> V1AddressFormat.ADDRESS_FORMAT_SPARK_REGTEST
        }
        val request = V1CreateWalletRequest(
            type = "ACTIVITY_TYPE_CREATE_WALLET",
            timestampMs = System.currentTimeMillis().toString(),
            organizationId = settings.organizationId,
            parameters = V1CreateWalletIntent(
                walletName = walletName,
                accounts = listOf(
                    identityAccount(V1AddressFormat.ADDRESS_FORMAT_COMPRESSED),
                    identityAccount(sparkFormat),
                ),
                mnemonicLength = 24,
            ),
            generateAppProofs = null,
        )

        val response = post(
            "/public/v1/submit/create_wallet",
            json.encodeToString(V1CreateWalletRequest.serializer(), request),
        )
        if (response.statusCode() == 409) {
            // Fingerprint dedup: an earlier identical submit already created
            // the wallet. The name is unique, so look the id up.
            return walletIdByName(walletName)
                ?: throw TurnkeyException("create_wallet got 409 but wallet '$walletName' was not found")
        }
        val activity = decodeActivity(response, "create_wallet")
        val completed = awaitCompletion(activity)
        return completed.result?.createWalletResult?.walletId
            ?: throw TurnkeyException("create_wallet activity ${completed.id} has no createWalletResult")
    }

    /** Polls a CREATED/PENDING activity to a terminal status, then returns it
     * (throwing unless COMPLETED). Backoff mirrors the SDK's Turnkey retry
     * defaults: 500ms doubling to a 5s cap, within a 60s budget. */
    private suspend fun awaitCompletion(initial: com.turnkey.types.V1Activity): com.turnkey.types.V1Activity {
        var activity = initial
        var delayMs = 500L
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (true) {
            when (activity.status) {
                V1ActivityStatus.ACTIVITY_STATUS_COMPLETED -> return activity
                V1ActivityStatus.ACTIVITY_STATUS_CREATED,
                V1ActivityStatus.ACTIVITY_STATUS_PENDING,
                -> {
                    if (System.nanoTime() > deadline) {
                        throw TurnkeyException("activity ${activity.id} still ${activity.status} after 60s")
                    }
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(5_000)
                    val response = post(
                        "/public/v1/query/get_activity",
                        json.encodeToString(
                            V1GetActivityRequest.serializer(),
                            V1GetActivityRequest(
                                activityId = activity.id,
                                organizationId = settings.organizationId,
                            ),
                        ),
                    )
                    activity = decodeActivity(response, "get_activity")
                }
                else -> throw TurnkeyException(
                    "activity ${activity.id} ended ${activity.status}: ${activity.failure?.message ?: "no failure detail"}"
                )
            }
        }
    }

    private suspend fun walletIdByName(walletName: String): String? {
        val response = post(
            "/public/v1/query/list_wallets",
            json.encodeToString(
                V1GetWalletsRequest.serializer(),
                V1GetWalletsRequest(organizationId = settings.organizationId),
            ),
        )
        requireSuccess(response, "list_wallets")
        return json.decodeFromString(V1GetWalletsResponse.serializer(), response.body())
            .wallets.firstOrNull { it.walletName == walletName }?.walletId
    }

    private suspend fun post(path: String, body: String): HttpResponse<String> {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(settings.baseUrl + path))
            .header("Content-Type", "application/json")
            .header("X-Stamp", stamper.stamp(bytes))
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    }

    private fun decodeActivity(response: HttpResponse<String>, what: String): com.turnkey.types.V1Activity {
        requireSuccess(response, what)
        return json.decodeFromString(V1ActivityResponse.serializer(), response.body()).activity
    }

    private fun requireSuccess(response: HttpResponse<String>, what: String) {
        if (response.statusCode() !in 200..299) {
            log.warn("turnkey {} failed: HTTP {} {}", what, response.statusCode(), response.body().take(500))
            throw TurnkeyException("turnkey $what failed: HTTP ${response.statusCode()}")
        }
    }
}

// --- hex ---------------------------------------------------------------

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
    }
}

fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append("0123456789abcdef"[(b.toInt() ushr 4) and 0xf])
        sb.append("0123456789abcdef"[b.toInt() and 0xf])
    }
    return sb.toString()
}
