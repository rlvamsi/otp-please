# OTPair

**Never wait for an OTP again.** OTPair pairs two Android phones so that either person can get the other's one-time codes with one tap.

It is built for couples and families who share cards: the card is in one person's name, the OTP lands on that person's phone, and the other person is stuck at checkout waiting for it.

<p>
  <img src="docs/welcome.png" width="200" alt="Welcome screen">
  <img src="docs/private.png" width="200" alt="How a code stays private">
  <img src="docs/sharing.png" width="200" alt="The sharing phone, waiting for a request">
  <img src="docs/code.png" width="200" alt="A code has arrived">
</p>

## How it works

1. **Pair once, in the same room.** One phone shows a QR code, the other scans it. Both phones then show the same four-digit **pair number**. If the numbers match, you are paired with each other and nobody else.
2. **Turn on sharing** on the phone that receives the bank messages. The app first asks you to confirm that you trust the other person completely.
3. **Tap Get OTP** on the other phone. If a code arrived on the sharing phone in the last 60 seconds, or arrives in the next 60, that phone rings.
4. **Tap Send** on the sharing phone. The code appears on the other phone a second later. Tap **Don’t send**, or ignore it, and nothing leaves the phone.

Once paired, each phone shows only its job: the sharing phone says **Sharing is on**, the other phone shows **Get OTP**. The lock, the pair number and Unpair are under **Settings** (the sliders at the top right).

**A code leaves the phone only when your partner asks for it and you say yes.** Under the hood:

- When a text message arrives, Android wakes OTPair. If the message looks like an OTP, the app holds the code on the phone for one minute and watches for a request from the paired phone. Messages that are not OTPs are ignored.
- Tapping **Get OTP** sends that request. If nobody asks within the minute, the code is forgotten and nothing is sent, and the sharing phone stays silent.
- When the request and a code meet, the sharing phone rings like an incoming call: "Your partner is asking for a code", with the message text so you can check the amount and the shop. If the screen is off or locked, a full-screen **Send this code?** page takes over the display. It keeps ringing until you answer, for up to three minutes.
- Only after **Send** does the phone encrypt the code (AES-256-GCM) and send it. Send needs the phone unlocked. **Don’t send** tells the other phone "not sent" and nothing else.
- Sharing does not work with notifications turned off, so a request can never go unseen.
- Requests and codes travel through [ntfy.sh](https://ntfy.sh), a free public relay. The channel name and the encryption key both come from the pairing secret, so the relay only ever sees scrambled text.

There is no account, no server to run, and no analytics.

## Install

OTPair is not on the Play Store. Google allows apps to read text messages only by special exception, so you build it and install the APK yourself, on both phones.

```bash
./gradlew assembleRelease
```

The APK is written to `build/outputs/apk/release/otpair-release.apk`. Building needs JDK 17 or 21 and the Android SDK (platform 36).

On the phone that will **share** its codes, allow notifications when the app asks. If the app says so, also let it show full-screen notifications, so a request can take over the screen like a call. Android also adds one hurdle for apps installed outside the Play Store. If the SMS permission is refused, the app walks you through it:

1. Open the app's **App info** page.
2. Tap **⋮** at the top right, then **Allow restricted settings**.
3. Open **Permissions → SMS → Allow**.

Also let the app run in the background when it asks. On Xiaomi, Vivo, iQOO, Oppo, OnePlus and some Samsung phones, additionally enable **Autostart** (or set battery use to **Unrestricted**) for OTPair, or the phone may stop it from waking up.

## Security, honestly

- **Only the two paired phones can read a code.** The relay, your network and anyone watching the relay see only encrypted text. They can see *that* a request or a code was sent, and when.
- **A photo of the QR code is not a key.** The QR code holds only a public key. The two phones work out the real secret between themselves (ECDH on P-256), and it is never shown or sent. A pair is exactly two phones: if someone else scans the code first, your partner's phone fails to pair instead of quietly joining. The pairing screen cannot be screenshotted in release builds.
- **Check the pair number.** It is the same on both phones only when they hold the same secret. If your partner's number is different, someone else got in first: unpair and pair again.
- **Nothing is sent without a yes.** Every code needs a tap on **Send** on the sharing phone, and Send needs that phone unlocked, so someone holding it locked cannot approve. You see which bank, how much and where before you decide. The sharing screen shows when the last code was sent and who the message was from.
- **Get OTP is locked.** By default it asks for a fingerprint, face or the phone's PIN (Android 9 or newer, on a phone with a screen lock), so someone who picks up the unlocked phone cannot pull a code. You can turn this off in Settings.
- **Only codes that were asked for ever reach the relay.** They stay there encrypted for about 12 hours, then expire. Old messages played back are ignored: every message carries the time it was written and is refused after three minutes.
- **The secret is encrypted on the phone** with a key held in the Android Keystore, and is excluded from cloud backups and phone-to-phone transfers. A rooted or compromised phone could still use it.
- **A copied code is cleared from the clipboard after a minute**, if OTPair is still open.
- **Your partner sees the full text of each OTP message**, not just the code, so they can check the amount and merchant.
- **Your partner can ask for any OTP, from any sender**, but each one still needs your Send.
- Banks tell you never to share OTPs. OTPair is for people who have already decided to share a card with one person they trust completely. Do not pair with anyone else, and never install it on someone's phone without their knowledge.

## Limits

- **The sharing phone has to be with someone.** If nobody taps Send within three minutes, no code is sent. Do Not Disturb can silence the ringing.
- **Keep OTPair open while you wait.** Android cuts an app's internet shortly after it leaves the screen. If you switch to the bank page and come back, OTPair picks the code up as soon as it is in front again.
- **Both phones need roughly the right time.** Messages more than three minutes off are refused. Phones set to automatic time are fine.
- **The sharing phone needs Android 12 or newer to be dependable.** Older versions can delay the one-minute watch when the phone is dozing.
- **Text messages only.** Codes sent over RCS chat, WhatsApp or email are not picked up.
- **The relay is someone else's free service.** If ntfy.sh is down, sharing pauses. To use your own ntfy server, change `BASE` in [`Relay.kt`](src/main/kotlin/co/atomiclabs/otpair/Relay.kt).
- **OTP detection is a heuristic** tuned on Indian bank messages (a keyword such as "OTP" or "code", plus the nearest 4 to 8 digit number). Unusual formats may be missed; add a case to [`CoreTest.kt`](src/test/kotlin/co/atomiclabs/otpair/CoreTest.kt) and adjust `Otp` in [`Core.kt`](src/main/kotlin/co/atomiclabs/otpair/Core.kt).
- **Do not raise `targetSdk` above 36.** On Android 17, apps that target API 37 or higher receive OTP messages three hours late.
- Android's permission prompt says "send and view SMS messages". OTPair only asks for the receive permission and cannot send texts; the wording is Android's label for the whole SMS group.

## Publishing a build

Without a signing key of your own, `assembleRelease` signs the APK with your computer's debug key. That is fine for your own two phones. Before you give the APK to anyone else, sign it with a key only you hold, so that nobody can pass off a changed copy as an update.

Create the key once (you choose the passwords; keep the file and the passwords safe, because losing them means you can never update the app):

```bash
keytool -genkeypair -v -keystore otpair.jks -alias otpair -keyalg RSA -keysize 4096 -validity 10000
```

Then write a `keystore.properties` file next to `build.gradle.kts`:

```properties
storeFile=otpair.jks
storePassword=…
keyAlias=otpair
keyPassword=…
```

Both files are in `.gitignore`. Never commit them. Publish the key's SHA-256 fingerprint next to your downloads so people can check an APK is yours:

```bash
keytool -list -v -keystore otpair.jks -alias otpair
```

Moving from the debug key to your own key means uninstalling the app and pairing again.

## Development

| File | What it holds |
| --- | --- |
| [`Core.kt`](src/main/kotlin/co/atomiclabs/otpair/Core.kt) | OTP detection, pairing, encryption, the message format. Pure Kotlin, unit tested. |
| [`Relay.kt`](src/main/kotlin/co/atomiclabs/otpair/Relay.kt) | The relay calls, stored settings (the secret is encrypted), the SMS receiver, the job that holds a code until it is asked for, the ringing request and the Send / Don’t send handling. |
| [`Theme.kt`](src/main/kotlin/co/atomiclabs/otpair/Theme.kt) | Colours, type, icons, buttons. |
| [`Onboarding.kt`](src/main/kotlin/co/atomiclabs/otpair/Onboarding.kt), [`Pairing.kt`](src/main/kotlin/co/atomiclabs/otpair/Pairing.kt), [`Home.kt`](src/main/kotlin/co/atomiclabs/otpair/Home.kt), [`Approval.kt`](src/main/kotlin/co/atomiclabs/otpair/Approval.kt) | The screens (Jetpack Compose). Approval.kt is the full-screen "Send this code?" page. |
| [`MainActivity.kt`](src/main/kotlin/co/atomiclabs/otpair/MainActivity.kt) | Which screen is showing. |
| [`CoreTest.kt`](src/test/kotlin/co/atomiclabs/otpair/CoreTest.kt) | Tests for detection, pairing, encryption and replayed messages. |

```bash
./gradlew testDebugUnitTest
```

To try it with a single phone or emulator, [`tools/partner.mjs`](tools/partner.mjs) plays the other phone from your computer. Use a debug build (release builds block screenshots), tap **Show my code**, and read the text out of the QR code (`otpair2:…`):

```bash
node tools/partner.mjs "otpair2:…" pair                           # pair; prints the link (otpair1:…) and the pair number
node tools/partner.mjs "otpair1:…" want                           # ask the phone for a code (it rings if one arrives)
node tools/partner.mjs "otpair1:…" asking                         # as the sharing phone: "the code is here, waiting for Send"
node tools/partner.mjs "otpair1:…" otp 482913 HDFCBK "OTP is 482913"   # as the sharing phone: Send
node tools/partner.mjs "otpair1:…" no                             # as the sharing phone: Don’t send
node tools/partner.mjs "otpair1:…" read 60s                       # read what is on the relay
```

## License

[MIT](LICENSE). The app bundles the [DM Sans](https://github.com/googlefonts/dm-fonts) typeface under the [SIL Open Font License](licenses/DM-Sans-OFL.txt), and its icons are redrawn from [Lucide](https://lucide.dev) under the [ISC License](licenses/Lucide-ISC.txt).
