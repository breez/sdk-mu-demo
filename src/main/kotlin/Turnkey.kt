import breez_sdk_spark.Network
import com.turnkey.types.V1AddressFormat
import com.turnkey.types.V1ApiKeyCurve
import com.turnkey.types.V1ApiKeyParamsV2
import com.turnkey.types.V1Attestation
import com.turnkey.types.V1AuthenticatorParamsV2
import com.turnkey.types.V1AuthenticatorTransport
import com.turnkey.types.V1CreatePolicyIntentV3
import com.turnkey.types.V1CreatePolicyRequest
import com.turnkey.types.V1CreateSubOrganizationIntentV8
import com.turnkey.types.V1CreateSubOrganizationRequest
import com.turnkey.types.V1Curve
import com.turnkey.types.V1Effect
import com.turnkey.types.V1GetActivityRequest
import com.turnkey.types.V1PathFormat
import com.turnkey.types.V1RootUserParamsV5
import com.turnkey.types.V1UpdateRootQuorumIntent
import com.turnkey.types.V1UpdateRootQuorumRequest
import com.turnkey.types.V1WalletAccountParams
import com.turnkey.types.V1WalletParams
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Turnkey client for the delegated-access provisioning at `POST /users`
 * (SIGNER=turnkey).
 *
 * Each user gets a Turnkey **sub-organization** whose only root user is the
 * end user (authenticated by the passkey registered in the browser). A
 * backend-controlled **delegated** API key is enrolled as a second member,
 * scoped by policy to receive / auth / FROST activities only — never
 * `SPARK_PREPARE_TRANSFER`. So the server can receive, claim and sync
 * autonomously, but cannot move funds out; only the user's passkey authorizes
 * a send. See DESIGN.md.
 *
 * Provisioning (all driven from here):
 *   1. parent admin key → CREATE_SUB_ORGANIZATION (root users = [owner passkey,
 *      delegated api key], quorum 1, wallet with both Spark accounts);
 *   2. delegated key (still root) → CREATE_POLICY granting itself the receive
 *      allowlist;
 *   3. delegated key → UPDATE_ROOT_QUORUM dropping itself from root, leaving the
 *      owner as sole root.
 *
 * Request bodies are built from the official `com.turnkey:types` models
 * (`V1CreateSubOrganizationRequest` / `V1CreatePolicyRequest` /
 * `V1UpdateRootQuorumRequest`, all `kotlinx`-`@Serializable`), so the wire
 * contract tracks the artifact instead of a hand-maintained copy. Results,
 * however, are still parsed structurally (by field, taking the single result
 * object regardless of its versioned key like `createSubOrganizationResultV8`)
 * so a result-schema version bump doesn't break us.
 *
 * The delegated key is a *distinct* keypair from the parent admin key not
 * because policy scoping requires it (a sub-org member is policy-scoped however
 * its keypair is shared), but for blast radius: the delegated key signs on
 * every request (the hot key) while the admin key creates sub-orgs and is used
 * only at sign-up. Keeping them separate contains a delegated-key leak to
 * receive/FROST on existing sub-orgs — it can't also spawn sub-orgs in the
 * parent org — and leaves room to protect/rotate the rarely-used admin key
 * independently of the hot path.
 */

class TurnkeyException(message: String) : RuntimeException(message)

/** Demo sub-orgs are name-prefixed so they're identifiable (and reapable) in a
 * parent org shared with other tooling. */
const val TURNKEY_SUB_ORG_NAME_PREFIX = "sdk-mu-demo-"

/**
 * TTL for the per-user *session* API key — the client's browser-held P-256 key,
 * pre-authorized on the owner (passkey) root user at sub-org creation so the
 * client can stamp denomination *swaps* silently without a second passkey tap at
 * sign-up. While valid, swaps need no prompt; once it expires the client falls
 * back to `loginWithPasskey` (one pinned tap) to mint a fresh session. Set to
 * 15 minutes to match the `loginWithPasskey` default — same expiry the
 * client-minted session already uses, so this changes how the session is created
 * (no second tap), not how long a browser-held key stays valid. Real sends are
 * always passkey-stamped regardless. */
const val SESSION_API_KEY_TTL_SECONDS = "900"

/** The `{account}` in every Spark derivation path (`m/8797555'/{account}'/…`),
 * matching the SDK's per-network default so the SDK derives the same paths. */
fun defaultAccountNumber(network: Network): Int =
    when (network) {
        Network.REGTEST -> 0
        else -> 1
    }

/** Activity type constants. The receive allowlist is the set the delegated key
 * is granted; `SPARK_PREPARE_TRANSFER` is deliberately absent. */
object TurnkeyActivity {
    const val CREATE_SUB_ORGANIZATION = "ACTIVITY_TYPE_CREATE_SUB_ORGANIZATION_V8"
    const val CREATE_POLICY = "ACTIVITY_TYPE_CREATE_POLICY_V3"
    const val UPDATE_ROOT_QUORUM = "ACTIVITY_TYPE_UPDATE_ROOT_QUORUM"

    const val SPARK_SIGN_FROST = "ACTIVITY_TYPE_SPARK_SIGN_FROST"
    const val SPARK_CLAIM_TRANSFER = "ACTIVITY_TYPE_SPARK_CLAIM_TRANSFER"
    const val SPARK_PREPARE_LIGHTNING_RECEIVE = "ACTIVITY_TYPE_SPARK_PREPARE_LIGHTNING_RECEIVE"
    const val SIGN_RAW_PAYLOAD = "ACTIVITY_TYPE_SIGN_RAW_PAYLOAD_V2"
    const val CREATE_WALLET_ACCOUNTS = "ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS"
    const val EXPORT_WALLET_ACCOUNT = "ACTIVITY_TYPE_EXPORT_WALLET_ACCOUNT"

    /** Activities the delegated (backend) key may run unconditionally. Excludes
     * SPARK_PREPARE_TRANSFER (only the user's passkey can authorize a send) and
     * EXPORT_WALLET_ACCOUNT (allowed only for the encryption account, by a
     * separate address-scoped policy — see [provision]). CREATE_WALLET_ACCOUNTS
     * is here because the SDK re-issues it on every signer init (idempotent,
     * 409s for accounts that already exist); it materializes addresses only and
     * can't move funds — and since export is address-scoped, it can't be abused
     * to re-derive then export the identity key. */
    val DELEGATED_UNCONDITIONAL_ALLOWLIST = listOf(
        SPARK_SIGN_FROST,
        SPARK_CLAIM_TRANSFER,
        SPARK_PREPARE_LIGHTNING_RECEIVE,
        SIGN_RAW_PAYLOAD,
        CREATE_WALLET_ACCOUNTS,
    )
}

/**
 * The WebAuthn passkey attestation the browser produced during sign-up, relayed
 * verbatim into the sub-org's owner root user. Field names match Turnkey's
 * authenticator `attestation` schema.
 */
@Serializable
data class PasskeyAttestation(
    val authenticatorName: String = "passkey",
    val challenge: String,
    val attestation: AttestationObject,
) {
    @Serializable
    data class AttestationObject(
        val credentialId: String,
        val clientDataJson: String,
        val attestationObject: String,
        val transports: List<String> = emptyList(),
    )
}

/** What provisioning produces and the user row stores. */
data class SubOrgResult(
    val subOrgId: String,
    val walletId: String,
    /** Spark-format wallet account address — the `signWith` the client passes to
     * Turnkey's SPARK_PREPARE_TRANSFER. */
    val sparkAddress: String,
)

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

    /** Stamps CREATE_SUB_ORGANIZATION on the parent org. */
    private val adminStamper = P256Stamper(settings.apiPublicKey, settings.apiPrivateKey)

    /** Stamps the in-sub-org policy + quorum setup, and (in SdkAccess) every
     * per-request receive/auth/FROST activity. */
    private val delegatedStamper = P256Stamper(settings.delegatedApiPublicKey, settings.delegatedApiPrivateKey)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Creates the user's sub-org with the passkey as sole root user and the
     * delegated key scoped to the receive allowlist. Returns the ids the user
     * row stores. On any failure the (at most) orphaned sub-org is logged and
     * left for manual reaping by its `sdk-mu-demo-` name prefix.
     */
    suspend fun provision(
        userId: String,
        passkey: PasskeyAttestation,
        sessionPublicKey: String?,
    ): SubOrgResult {
        val subOrgName = TURNKEY_SUB_ORG_NAME_PREFIX + userId
        val account = defaultAccountNumber(network)
        val identityPath = "m/8797555'/$account'/0'"
        // The SDK's reserved, non-Spark child key (max hardened index). It exports
        // this on every signer init to seed local ECIES/HMAC. We pre-create it
        // here so we know its address and can scope export to it alone (below).
        val encryptionPath = "m/8797555'/$account'/2147483647'"
        val sparkFormat = when (network) {
            Network.MAINNET -> V1AddressFormat.ADDRESS_FORMAT_SPARK_MAINNET
            else -> V1AddressFormat.ADDRESS_FORMAT_SPARK_REGTEST
        }
        // Account order is load-bearing: addresses[] in the result follows it.
        // [0] compressed identity, [1] Spark identity (signWith), [2] encryption.
        val secp256k1 = V1Curve.CURVE_SECP256K1
        val bip32 = V1PathFormat.PATH_FORMAT_BIP32
        val accounts = listOf(
            V1WalletAccountParams(curve = secp256k1, pathFormat = bip32, path = identityPath, addressFormat = V1AddressFormat.ADDRESS_FORMAT_COMPRESSED),
            V1WalletAccountParams(curve = secp256k1, pathFormat = bip32, path = identityPath, addressFormat = sparkFormat),
            V1WalletAccountParams(curve = secp256k1, pathFormat = bip32, path = encryptionPath, addressFormat = V1AddressFormat.ADDRESS_FORMAT_COMPRESSED),
        )

        val createReq = V1CreateSubOrganizationRequest(
            type = TurnkeyActivity.CREATE_SUB_ORGANIZATION,
            timestampMs = System.currentTimeMillis().toString(),
            organizationId = settings.organizationId,
            generateAppProofs = null,
            parameters = V1CreateSubOrganizationIntentV8(
                subOrganizationName = subOrgName,
                rootUsers = listOf(
                    // Order matters: rootUserIds[] in the result follows it.
                    V1RootUserParamsV5(
                        userName = "owner",
                        userEmail = null,
                        userPhoneNumber = null,
                        // Pre-authorize the client's browser-held session key (if it
                        // sent one) as a short-lived API key on the owner, so swaps
                        // can be stamped silently right after sign-up without a
                        // second passkey tap. See [SESSION_API_KEY_TTL_SECONDS].
                        apiKeys = sessionPublicKey?.let {
                            listOf(
                                V1ApiKeyParamsV2(
                                    apiKeyName = "session",
                                    publicKey = it,
                                    curveType = V1ApiKeyCurve.API_KEY_CURVE_P256,
                                    expirationSeconds = SESSION_API_KEY_TTL_SECONDS,
                                )
                            )
                        } ?: emptyList(),
                        authenticators = listOf(
                            V1AuthenticatorParamsV2(
                                authenticatorName = passkey.authenticatorName,
                                challenge = passkey.challenge,
                                attestation = V1Attestation(
                                    credentialId = passkey.attestation.credentialId,
                                    clientDataJson = passkey.attestation.clientDataJson,
                                    attestationObject = passkey.attestation.attestationObject,
                                    // Transports are hints; drop any value this artifact
                                    // doesn't know rather than failing the whole sign-up.
                                    transports = passkey.attestation.transports.mapNotNull {
                                        runCatching { V1AuthenticatorTransport.valueOf(it) }.getOrNull()
                                    },
                                )
                            )
                        ),
                        oauthProviders = emptyList(),
                    ),
                    V1RootUserParamsV5(
                        userName = "delegated",
                        userEmail = null,
                        userPhoneNumber = null,
                        apiKeys = listOf(
                            V1ApiKeyParamsV2(
                                apiKeyName = "delegated-backend",
                                publicKey = settings.delegatedApiPublicKey,
                                curveType = V1ApiKeyCurve.API_KEY_CURVE_P256,
                                expirationSeconds = null,
                            )
                        ),
                        authenticators = emptyList(),
                        oauthProviders = emptyList(),
                    ),
                ),
                rootQuorumThreshold = 1L,
                wallet = V1WalletParams(walletName = "$subOrgName-wallet", accounts = accounts, mnemonicLength = 24L),
                // Passkey-only demo: no email/SMS recovery surface.
                disableEmailRecovery = true,
                disableEmailAuth = true,
                disableSmsAuth = true,
                disableOtpEmailAuth = true,
                clientSignature = null,
                verificationToken = null,
            ),
        )

        val createResult = submit(
            "/public/v1/submit/create_sub_organization",
            json.encodeToString(V1CreateSubOrganizationRequest.serializer(), createReq),
            adminStamper,
            settings.organizationId,
            "create_sub_organization",
        )

        // Parse structurally: the result is keyed by a versioned name
        // (createSubOrganizationResultV7/…); take the single result object.
        val resultObj = createResult["result"]?.jsonObject
            ?.values?.firstOrNull()?.jsonObject
            ?: throw TurnkeyException("create_sub_organization: no result object")
        val subOrgId = resultObj["subOrganizationId"]?.jsonPrimitive?.content
            ?: throw TurnkeyException("create_sub_organization: no subOrganizationId")
        val walletObj = resultObj["wallet"]?.jsonObject
            ?: throw TurnkeyException("create_sub_organization: no wallet in result")
        val walletId = walletObj["walletId"]?.jsonPrimitive?.content
            ?: throw TurnkeyException("create_sub_organization: no walletId")
        val addresses = walletObj["addresses"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw TurnkeyException("create_sub_organization: no wallet addresses")
        // accounts = [compressed, spark, encryption]; addresses follows order.
        val sparkAddress = addresses.getOrNull(1)
            ?: throw TurnkeyException("create_sub_organization: missing Spark account address")
        val encryptionAddress = addresses.getOrNull(2)
            ?: throw TurnkeyException("create_sub_organization: missing encryption account address")
        val rootUserIds = resultObj["rootUserIds"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw TurnkeyException("create_sub_organization: no rootUserIds")
        val ownerUserId = rootUserIds.getOrNull(0)
            ?: throw TurnkeyException("create_sub_organization: missing owner user id")
        val delegatedUserId = rootUserIds.getOrNull(1)
            ?: throw TurnkeyException("create_sub_organization: missing delegated user id")

        // 2. Grant the delegated user its scope (still a root user, so it can
        //    create policies). Everything not granted stays default-denied for
        //    the (soon non-root) delegated user.
        val consensus = "approvers.any(user, user.id == '$delegatedUserId')"

        // 2a. The unconditional allowlist: receive/auth/FROST + account
        //     materialization. SPARK_PREPARE_TRANSFER is absent (send needs the
        //     passkey), as is unconditional EXPORT_WALLET_ACCOUNT.
        val allowCondition = TurnkeyActivity.DELEGATED_UNCONDITIONAL_ALLOWLIST
            .joinToString(" || ") { "activity.type == '$it'" }
        createPolicy(
            subOrgId,
            policyName = "delegated-allow",
            consensus = consensus,
            condition = allowCondition,
            notes = "Backend delegated key: receive/auth/FROST + account materialization; no SPARK_PREPARE_TRANSFER.",
        )

        // 2b. Export is allowed ONLY for the encryption account, by address. This
        //     is the security boundary for export: the Spark identity key (a
        //     different account/address) is never allow-listed, so the delegated
        //     key can never export it — even though it may create accounts. An
        //     allowlist (not a denylist) defeats re-deriving the identity key
        //     under a fresh address: any new address is simply not on the list.
        createPolicy(
            subOrgId,
            policyName = "delegated-export-encryption-only",
            consensus = consensus,
            condition = "activity.type == '${TurnkeyActivity.EXPORT_WALLET_ACCOUNT}' && " +
                "wallet_account.address == '$encryptionAddress'",
            notes = "Backend delegated key: export the non-Spark encryption key only; never the identity key.",
        )

        // 3. Drop the delegated user from root, leaving the owner (passkey) as
        //    sole root. The delegated user keeps only its policy-granted scope.
        val quorumReq = V1UpdateRootQuorumRequest(
            type = TurnkeyActivity.UPDATE_ROOT_QUORUM,
            timestampMs = System.currentTimeMillis().toString(),
            organizationId = subOrgId,
            generateAppProofs = null,
            parameters = V1UpdateRootQuorumIntent(threshold = 1L, userIds = listOf(ownerUserId)),
        )
        submit(
            "/public/v1/submit/update_root_quorum",
            json.encodeToString(V1UpdateRootQuorumRequest.serializer(), quorumReq),
            delegatedStamper,
            subOrgId,
            "update_root_quorum",
        )

        return SubOrgResult(
            subOrgId = subOrgId,
            walletId = walletId,
            sparkAddress = sparkAddress,
        )
    }

    /** Creates one ALLOW policy in the sub-org, stamped by the (still-root)
     * delegated key. */
    private suspend fun createPolicy(
        subOrgId: String,
        policyName: String,
        consensus: String,
        condition: String,
        notes: String,
    ) {
        val req = V1CreatePolicyRequest(
            type = TurnkeyActivity.CREATE_POLICY,
            timestampMs = System.currentTimeMillis().toString(),
            organizationId = subOrgId,
            generateAppProofs = null,
            parameters = V1CreatePolicyIntentV3(
                policyName = policyName,
                effect = V1Effect.EFFECT_ALLOW,
                consensus = consensus,
                condition = condition,
                notes = notes,
            ),
        )
        submit(
            "/public/v1/submit/create_policy",
            json.encodeToString(V1CreatePolicyRequest.serializer(), req),
            delegatedStamper,
            subOrgId,
            "create_policy($policyName)",
        )
    }

    /** Submits an activity, polls to a terminal status, and returns the
     * terminal `activity` JSON object. */
    private suspend fun submit(
        path: String,
        body: String,
        stamper: P256Stamper,
        organizationId: String,
        what: String,
    ): JsonObject {
        val response = post(path, body, stamper)
        return awaitCompletion(decodeActivity(response, what), stamper, organizationId)
    }

    /** Polls a CREATED/PENDING activity to a terminal status, then returns its
     * `activity` object (throwing unless COMPLETED). Backoff mirrors the SDK's
     * Turnkey retry defaults: 500ms doubling to a 5s cap, within a 60s budget. */
    private suspend fun awaitCompletion(
        initial: JsonObject,
        stamper: P256Stamper,
        organizationId: String,
    ): JsonObject {
        var activity = initial
        var delayMs = 500L
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (true) {
            val status = activity["status"]?.jsonPrimitive?.content
            when (status) {
                "ACTIVITY_STATUS_COMPLETED" -> return activity
                "ACTIVITY_STATUS_CREATED", "ACTIVITY_STATUS_PENDING" -> {
                    val id = activity["id"]?.jsonPrimitive?.content
                        ?: throw TurnkeyException("activity missing id")
                    if (System.nanoTime() > deadline) {
                        throw TurnkeyException("activity $id still $status after 60s")
                    }
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(5_000)
                    val response = post(
                        "/public/v1/query/get_activity",
                        json.encodeToString(
                            V1GetActivityRequest.serializer(),
                            V1GetActivityRequest(activityId = id, organizationId = organizationId),
                        ),
                        stamper,
                    )
                    activity = decodeActivity(response, "get_activity")
                }
                else -> {
                    val msg = activity["failure"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    throw TurnkeyException("activity ${activity["id"]?.jsonPrimitive?.content} ended $status: ${msg ?: "no detail"}")
                }
            }
        }
    }

    private suspend fun post(path: String, body: String, stamper: P256Stamper): HttpResponse<String> {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(settings.baseUrl + path))
            .header("Content-Type", "application/json")
            .header("X-Stamp", stamper.stamp(bytes))
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    }

    private fun decodeActivity(response: HttpResponse<String>, what: String): JsonObject {
        if (response.statusCode() !in 200..299) {
            log.warn("turnkey {} failed: HTTP {} {}", what, response.statusCode(), response.body().take(500))
            throw TurnkeyException("turnkey $what failed: HTTP ${response.statusCode()}")
        }
        return json.parseToJsonElement(response.body()).jsonObject["activity"]?.jsonObject
            ?: throw TurnkeyException("turnkey $what: response has no activity")
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

// --- Turnkey session JWT verification (for POST /login) ---------------------

/** The claims we read from a verified Turnkey session JWT. */
data class TurnkeySessionClaims(
    val organizationId: String,
    val userId: String,
    val sessionType: String,
    val expSeconds: Long,
)

/**
 * Verifies a Turnkey **session JWT** and returns its claims, or throws
 * [TurnkeyException] if the signature, format, type, or expiry is invalid.
 *
 * A session JWT is Turnkey-signed metadata referencing the client's session
 * keypair — exactly what a backend uses to confirm a session server-side. The
 * scheme mirrors `@turnkey/crypto`'s `verifySessionJwtSignature`: the signature
 * is P-256 ECDSA (raw r||s, base64url) by Turnkey's notarizer key over
 * `sha256(sha256(header "." payload))`. The notarizer key is global to
 * Turnkey's production API (not network-specific).
 *
 * Note: this proves Turnkey issued the session for `organizationId`; like any
 * bearer token it could be replayed until `exp`, so sessions are short-lived
 * and this gates only api-key minting (sends still require the passkey).
 */
object TurnkeySessionVerifier {
    // @turnkey/crypto PRODUCTION_NOTARIZER_SIGN_PUBLIC_KEY (uncompressed P-256).
    private const val NOTARIZER_PUBLIC_KEY_HEX =
        "04d498aa87ac3bf982ac2b5dd9604d0074905cfbda5d62727c5a237b895e6749205e9f7cd566909c4387f6ca25c308445c60884b788560b785f4a96ac33702a469"

    private val p256Params: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)

    private val notarizerKey by lazy {
        val raw = hexToBytes(NOTARIZER_PUBLIC_KEY_HEX)
        require(raw.size == 65 && raw[0].toInt() == 0x04) { "notarizer key must be an uncompressed P-256 point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Params))
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun verify(jwt: String, nowMs: Long = System.currentTimeMillis()): TurnkeySessionClaims {
        val parts = jwt.split(".")
        if (parts.size != 3) throw TurnkeyException("session JWT must have 3 parts")
        val (headerB64, payloadB64, signatureB64) = parts

        val digest = MessageDigest.getInstance("SHA-256").let { md ->
            val first = md.digest("$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII))
            md.reset()
            md.digest(first)
        }
        val rawSig = Base64.getUrlDecoder().decode(signatureB64)
        if (rawSig.size != 64) throw TurnkeyException("session JWT signature must be 64 bytes (r||s)")
        val ok = Signature.getInstance("NONEwithECDSA").run {
            initVerify(notarizerKey)
            update(digest)
            verify(rsToDer(rawSig))
        }
        if (!ok) throw TurnkeyException("session JWT signature verification failed")

        val claims = json.decodeFromString(
            JwtClaims.serializer(),
            String(Base64.getUrlDecoder().decode(payloadB64), Charsets.UTF_8),
        )
        if (claims.expSeconds * 1000 <= nowMs) throw TurnkeyException("session JWT expired")
        if (claims.organizationId.isBlank()) throw TurnkeyException("session JWT missing organization_id")
        return TurnkeySessionClaims(
            organizationId = claims.organizationId,
            userId = claims.userId,
            sessionType = claims.sessionType,
            expSeconds = claims.expSeconds,
        )
    }

    @Serializable
    private data class JwtClaims(
        @kotlinx.serialization.SerialName("organization_id") val organizationId: String = "",
        @kotlinx.serialization.SerialName("user_id") val userId: String = "",
        @kotlinx.serialization.SerialName("session_type") val sessionType: String = "",
        @kotlinx.serialization.SerialName("exp") val expSeconds: Long = 0,
    )

    /** Raw 64-byte r||s → DER SEQUENCE { INTEGER r, INTEGER s } for JCA. */
    private fun rsToDer(sig: ByteArray): ByteArray {
        fun trimInt(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            var v = b.copyOfRange(i, b.size)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v // keep positive
            return v
        }
        val r = trimInt(sig.copyOfRange(0, 32))
        val s = trimInt(sig.copyOfRange(32, 64))
        fun tlv(v: ByteArray) = byteArrayOf(0x02, v.size.toByte()) + v
        val body = tlv(r) + tlv(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
