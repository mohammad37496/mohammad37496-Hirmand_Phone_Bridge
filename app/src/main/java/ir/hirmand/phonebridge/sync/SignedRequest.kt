package ir.hirmand.phonebridge.sync

import android.util.Base64
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SignedRequest {
    private val random = SecureRandom()

    fun addHeaders(
        builder: Request.Builder,
        secret: String,
        deviceId: String,
        body: ByteArray,
    ): Request.Builder {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = ByteArray(24).also(random::nextBytes).let {
            Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        val bodyHash = sha256(body)
        val signingInput = "v1.$deviceId.$timestamp.$nonce.$bodyHash"
        val signature = hmacSha256(secret, signingInput)

        return builder
            .header("X-Hirmand-Timestamp", timestamp)
            .header("X-Hirmand-Nonce", nonce)
            .header("X-Hirmand-Signature", signature)
            .header("X-Hirmand-Signature-Version", "1")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hmacSha256(secret: String, input: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
