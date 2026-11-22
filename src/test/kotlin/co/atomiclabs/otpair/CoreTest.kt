package co.atomiclabs.otpair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreTest {
    @Test
    fun `finds the code in real-world bank messages`() {
        val cases = mapOf(
            "OTP is 482913 for txn of INR 1,499.00 at AMAZON on HDFC Bank card ending 1234. Valid till 12:34. Do not share OTP for security reasons" to "482913",
            "482913 is OTP for txn of INR 2,500.00 at Amazon on ICICI Bank Card XX1234. OTPs are SECRET. DO NOT disclose it to anyone." to "482913",
            "Dear Customer, 771204 is the OTP for your Axis Bank Credit Card ending 9876 txn of INR 12000 at FLIPKART. Valid for 10 mins." to "771204",
            "Your one time password for transaction of Rs 5000 on 21-11-2026 is 4829. Do not share." to "4829",
            "Your OTP for login on 21 Nov 2026 is 660318" to "660318",
            "Your OTP for A/c 1234 is 567890" to "567890",
            "G-482913 is your Google verification code." to "482913",
            "Use OTP:90817263 to verify your number" to "90817263",
            "Rs.5000 spent. Your verification code is 3141" to "3141",
        )
        for ((sms, code) in cases) assertEquals(sms, code, Otp.extract(sms))
    }

    @Test
    fun `ignores messages that are not OTPs`() {
        val cases = listOf(
            "Your A/c XX1234 debited INR 5000.00 on 21-11-26. Avl bal INR 12345.67",
            "Use code SAVE2026 to get 50% off. Call 18001234567",
            "Your order 40412345678 has shipped and arrives tomorrow",
            "Never share your OTP with anyone. Bank staff will not ask for it.",
            "",
        )
        for (sms in cases) assertNull(sms, Otp.extract(sms))
    }

    @Test
    fun `finds a copied code, and the code in an allowed text`() {
        assertEquals("482913", Otp.find("482913"))
        assertEquals("482913", Otp.find(" 482913\n"))
        assertEquals("482913", Otp.find("OTP is 482913 for txn of INR 1,499.00 at AMAZON"))
        assertEquals("5521", Otp.find("Swiggy: 5521 to log in"))
        assertNull(Otp.find("hello"))
        assertNull(Otp.find("1,499.00"))
    }

    @Test
    fun `two phones that pair can read each other, and nobody else can`() {
        val shower = Pairing.new()
        val scanner = Pairing.new()
        val seen = Pairing.scan(shower.qr())!! // what the scanning phone reads off the QR code
        val mine = shower.finish(Pairing.key(scanner.code())!!, showed = true)!!
        val partners = scanner.finish(seen, showed = false)!!
        val msg = Msg("otp", "device-1", "482913", "AD-HDFCBK", "OTP is 482913\nValid for 10 mins")

        val sealed = mine.seal(msg.pack())
        assertEquals(mine.topic, partners.topic)
        assertEquals(mine.check, partners.check)
        assertEquals(msg, Msg.unpack(partners.open(sealed)!!))
        assertNotEquals("each seal uses a fresh nonce", sealed, mine.seal(msg.pack()))
        assertEquals("the stored secret survives a restart", mine.topic, Link.decode(mine.encode())!!.topic)

        // Someone who photographed the QR code and scans it later ends up with a different secret.
        val stranger = Pairing.new().finish(seen, showed = false)!!
        assertNotEquals(mine.topic, stranger.topic)
        assertNull(stranger.open(sealed))
        // The meeting channel is all the photo gives them, and no code ever crosses it.
        assertNull(Pairing.meet(seen).open(sealed))
        assertNull("tampering is detected", partners.open(sealed.dropLast(2) + "AA"))
        assertNull(partners.open("not sealed at all"))
    }

    @Test
    fun `rejects anything that is not a pairing code`() {
        assertNull(Pairing.scan("https://example.com"))
        assertNull(Pairing.scan("otpair2:too-short"))
        assertNull(Pairing.scan("otpair2:***"))
        assertNull(Pairing.scan("otpair1:" + "A".repeat(43)))
        // 65 bytes that look like a key but are not a point on the curve.
        val bogus = "otpair2:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(65) { if (it == 0) 4 else 1 })
        assertNull(Pairing.new().finish(Pairing.scan(bogus)!!, showed = false))
        assertNull(Link.decode("otpair1:too-short"))
    }

    @Test
    fun `an old message played back is ignored`() {
        val now = 1_800_000_000_000L
        assertEquals(true, Msg("want", "d", at = now - 60_000).fresh(now))
        assertEquals(false, Msg("want", "d", at = now - 4 * 60_000).fresh(now))
        assertEquals(false, Msg("want", "d", at = now + 4 * 60_000).fresh(now))
        assertNull("the old format, with no time in it", Msg.unpack("want\nd\n\n\n"))
    }
}
