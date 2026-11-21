package co.atomiclabs.otpair

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pulls the one-time code out of a text message, or returns null when the message isn't an OTP. */
object Otp {
    private val keyword =
        Regex("""\b(otp|one[\s-]?time|passcode|password|verification|code)\b""", RegexOption.IGNORE_CASE)

    // 4 to 8 digits standing alone: not part of a word (XX1234), an amount (1,499.00) or a date (21-11-2026).
    private val number = Regex("""(?<![\w.,/])(?<!\d-)\d{4,8}(?!\w)(?![.,/:-]\d)""")

    // A number introduced by one of these is an amount, a card ending, a reference or a year. Never the code.
    private val notACode = Regex(
        """(\b(rs\.?|inr|usd|ending|ending (with|in)|ends with|card|a/c|acct?|account|""" +
            """ref(erence)?( no\.?| id)?|txn( id| no\.?)?|call|dial|""" +
            """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?)|[₹$])\s*[:#,-]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // ponytail: keyword + nearest-number heuristic tuned on Indian bank messages.
    // Codes split by a space or dash ("123 456") are missed; add a pattern if a bank sends those.
    fun extract(sms: String): String? {
        val keywords = keyword.findAll(sms).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        return number.findAll(sms)
            .filterNot { notACode.containsMatchIn(sms.substring(0, it.range.first)) }
            .minByOrNull { n -> keywords.minOf { k -> gap(n.range, k) } }
            ?.value
    }

    private fun gap(a: IntRange, b: IntRange) = maxOf(a.first - b.last, b.first - a.last, 0)
}

private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/**
 * Pairing. The QR code carries only a public key, and the lasting secret is worked out by the
 * two phones between themselves (ECDH on P-256). So a photo of the QR code is useless once the
 * phones have paired, and a pair is exactly two phones: if someone else scans first, the real
 * partner's phone fails to pair instead of quietly joining.
 */
class Pairing private constructor(private val keys: KeyPair) {
    /** This phone's public key: the 65 bytes of an uncompressed P-256 point. */
    val public: ByteArray = keys.public.encoded.takeLast(65).toByteArray()

    /** What the QR code says. */
    fun qr() = QR + b64(public)

    /** How the scanning phone sends its public key back, inside its hello. */
    fun code() = b64(public)

    /**
     * The secret both phones end up with. [showed] is whether this phone showed the QR code.
     * Null when [theirs] is not a valid key.
     */
    fun finish(theirs: ByteArray, showed: Boolean): Link? = try {
        val their = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(SPKI + theirs))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keys.private)
            doPhase(their, true)
            generateSecret()
        }
        val (shower, scanner) = if (showed) public to theirs else theirs to public
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(shared, "HmacSHA256")) }
        Link(mac.doFinal("otpair-link".toByteArray() + shower + scanner))
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val QR = "otpair2:"

        // The fixed header that turns a raw P-256 point into the standard public-key encoding.
        private val SPKI = "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun new() = Pairing(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair())

        /** The other phone's public key from scanned QR text. Null for anything that isn't an OTPair code. */
        fun scan(text: String): ByteArray? = text.trim().takeIf { it.startsWith(QR) }?.let { key(it.removePrefix(QR)) }

        /** A public key as sent in a hello. Null when it isn't one. */
        fun key(text: String): ByteArray? = try {
            Base64.getUrlDecoder().decode(text).takeIf { it.size == 65 && it[0] == 4.toByte() }
        } catch (e: IllegalArgumentException) {
            null
        }

        /**
         * Where the two phones meet while pairing. Anyone who saw the QR code can find this
         * channel, but all that crosses it is the scanning phone's public key.
         */
        fun meet(shower: ByteArray) = Link(MessageDigest.getInstance("SHA-256").digest("otpair-meet".toByteArray() + shower))
    }
}

/**
 * The secret two paired phones share. The relay channel name and the encryption key are both
 * derived from it, so the relay can route messages without being able to read them.
 */
class Link(private val secret: ByteArray) {
    val topic = "otpair-" + hmac("topic").take(16).joinToString("") { "%02x".format(it) }
    private val key = SecretKeySpec(hmac("key"), "AES")

    /** Four digits that are the same on both phones only when they hold the same secret. */
    val check = hmac("check").let { "%04d".format((((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)) % 10000) }

    /** How the secret is kept on the phone. It is never shown or sent. */
    fun encode() = PREFIX + b64(secret)

    /** AES-256-GCM with a fresh random nonce, as base64url(nonce + ciphertext). */
    fun seal(plain: String): String {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return b64(nonce + cipher.doFinal(plain.toByteArray()))
    }

    /** Null when the text wasn't sealed with this link's key (wrong pair, tampered, or junk). */
    fun open(sealed: String): String? = try {
        val bytes = Base64.getUrlDecoder().decode(sealed.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    } catch (e: Exception) {
        null
    }

    private fun hmac(label: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(label.toByteArray())

    companion object {
        private const val PREFIX = "otpair1:"

        /** Null for anything that isn't a stored OTPair secret. */
        fun decode(text: String): Link? = try {
            text.trim().takeIf { it.startsWith(PREFIX) }
                ?.let { Base64.getUrlDecoder().decode(it.removePrefix(PREFIX)) }
                ?.takeIf { it.size == 32 }
                ?.let(::Link)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * What travels between the phones, always sealed. [type] is "hello" (pairing), "want" (one
 * phone is asking for a code) or "otp" (the answer to a "want"). [at] is when it was written.
 */
data class Msg(
    val type: String,
    val from: String,
    val code: String = "",
    val sender: String = "",
    val body: String = "",
    val at: Long = System.currentTimeMillis(),
) {
    // One field per line; the message body goes last because it may itself contain line breaks.
    fun pack() = listOf(type, from, at, code, sender.replace('\n', ' '), body).joinToString("\n")

    /**
     * Written within the last few minutes, by this phone's clock. An old message played back
     * by the relay or by someone watching it fails this and is ignored.
     */
    fun fresh(now: Long = System.currentTimeMillis()) = kotlin.math.abs(now - at) <= MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 3 * 60 * 1000L

        fun unpack(text: String): Msg? {
            val parts = text.split("\n", limit = 6).takeIf { it.size == 6 } ?: return null
            return Msg(parts[0], parts[1], parts[3], parts[4], parts[5], parts[2].toLongOrNull() ?: return null)
        }
    }
}
