import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The stamper is the only crypto written in this repo (everything else is
 * inside the SDK), so verify the X-Stamp it produces end to end: decode the
 * base64url JSON and check the DER ECDSA-SHA256 signature against the
 * keypair's public key.
 */
class P256StamperTest {

    private fun freshKeypair(): Triple<String, String, ECPublicKey> {
        val kp = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val priv = kp.private as ECPrivateKey
        val pub = kp.public as ECPublicKey
        // 32-byte scalar, zero-padded: BigInteger drops leading zeros.
        val scalar = priv.s.toByteArray().let { raw ->
            ByteArray(32).also { out -> raw.takeLast(32).toByteArray().copyInto(out, 32 - minOf(raw.size, 32)) }
        }
        // Compressed SEC1 point: 02/03 prefix by y parity, then 32-byte x.
        val x = pub.w.affineX.toByteArray().let { raw ->
            ByteArray(32).also { out -> raw.takeLast(32).toByteArray().copyInto(out, 32 - minOf(raw.size, 32)) }
        }
        val prefix: Byte = if (pub.w.affineY.testBit(0)) 0x03 else 0x02
        return Triple(bytesToHex(byteArrayOf(prefix) + x), bytesToHex(scalar), pub)
    }

    @Test
    fun `stamp verifies against the public key`() {
        val (pubHex, privHex, pub) = freshKeypair()
        val body = """{"type":"ACTIVITY_TYPE_CREATE_WALLET","organizationId":"test"}"""
            .toByteArray(Charsets.UTF_8)

        val stamp = P256Stamper(pubHex, privHex).stamp(body)

        assertFalse(stamp.contains('='), "stamp must be base64url without padding")
        val decoded = Base64.getUrlDecoder().decode(stamp).toString(Charsets.UTF_8)
        val fields = Json.parseToJsonElement(decoded).jsonObject
        assertEquals(pubHex, fields.getValue("publicKey").jsonPrimitive.content)
        assertEquals("SIGNATURE_SCHEME_TK_API_P256", fields.getValue("scheme").jsonPrimitive.content)

        val der = hexToBytes(fields.getValue("signature").jsonPrimitive.content)
        val verifies = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update(body)
            verify(der)
        }
        assertTrue(verifies, "DER signature must verify over the exact body bytes")
    }

    @Test
    fun `stamp signs the exact bytes - a different body fails verification`() {
        val (pubHex, privHex, pub) = freshKeypair()
        val stamp = P256Stamper(pubHex, privHex).stamp("body-a".toByteArray())
        val decoded = Base64.getUrlDecoder().decode(stamp).toString(Charsets.UTF_8)
        val sigHex = Json.parseToJsonElement(decoded).jsonObject.getValue("signature").jsonPrimitive.content
        val verifies = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update("body-b".toByteArray())
            verify(hexToBytes(sigHex))
        }
        assertFalse(verifies)
    }
}
