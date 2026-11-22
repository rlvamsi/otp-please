# Putting OTP Please on the Play Store

The Play Store version never reads text messages, so it needs none of Google's restricted SMS permissions. Two things replace them:

- **Android's message pop-up** ([SMS User Consent](https://developers.google.com/identity/sms-retriever/user-consent/overview)). After a request, Android offers the next text with a code in it and asks the owner to allow it. If the text came first, the owner copies the code and taps **Paste code**.
- **A Firebase push** wakes the sharing phone when the partner taps Get OTP, through a small Cloudflare worker ([`worker/wake.js`](../worker/wake.js)).

## 1. Firebase (wakes the sharing phone)

1. In the [Firebase console](https://console.firebase.google.com), create a project (Analytics off).
2. Add an Android app with the package name `co.atomiclabs.otpair`.
3. Download `google-services.json` into the project folder, next to `build.gradle.kts`. The build picks it up by itself. It holds no secrets, but it is gitignored so forks use their own project.
4. Project settings → **Service accounts** → **Generate new private key**. This JSON file *is* a secret: keep it out of git, and delete your copy once step 2 is done.

## 2. The worker (passes a request to Firebase)

```bash
cd worker
npx wrangler login
npx wrangler deploy
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT < ~/Downloads/your-service-account.json
```

`deploy` prints the worker's address, like `https://otp-please-wake.<you>.workers.dev`. Put it in `WAKE` in [`Relay.kt`](../src/main/kotlin/co/atomiclabs/otpair/Relay.kt). Check the worker any time with `node worker/wake.test.mjs`, and end to end with `node tools/partner.mjs "otpair1:…" wake https://otp-please-wake.<you>.workers.dev` (the sharing phone should ring).

The free Cloudflare plan allows 100,000 requests a day; each Get OTP is one.

## 3. Build the bundle

Play wants an app bundle signed with your upload key. Create the key and `keystore.properties` as in the README's **Publishing a build**, then:

```bash
./gradlew bundleRelease
```

The bundle is `build/outputs/bundle/release/otp-please-release.aab`. Raise `versionCode` in `build.gradle.kts` for every upload.

Phones with a hand-installed copy must uninstall it before installing from Play (the signing keys differ), then pair again.

## 4. Play Console

A personal developer account (one-time US$25) must run a **closed test with at least 12 testers for 14 days in a row** before it can apply for production. Organisation accounts skip this but need a D-U-N-S number.

1. Create the app: name **OTP Please**, free, app (not game).
2. **Testing → Closed testing**: upload the bundle, add a Google Group or email list of 12+ testers, and share the opt-in link. Testers must stay opted in for the 14 days.
3. Fill in **App content** (answers below) and the **store listing**.
4. After 14 days, **Apply for production access**, then roll out.

### App content answers

| Form | Answer |
| --- | --- |
| Privacy policy | `https://github.com/rlvamsi/otp-please/blob/main/PRIVACY.md` (the file must be on `main` first) |
| App access | Needs two phones paired in person. Reviewer note: "Install on two devices. On one, tap Show my code; on the other, Scan a code. Choose 'Yes, they come here' on the phone that receives texts. Tap Get OTP on the other phone; the first rings. Tap Send a code, then send that phone any text containing a code such as 'Your OTP is 123456'." |
| Ads | No ads |
| Content rating | Utility. No violence, sexual content, gambling, user-to-user chat with strangers, or location sharing. |
| Target audience | 18 and over |
| Data safety | No data collected and no data shared. Codes and requests are end-to-end encrypted between the two paired phones, which the form exempts from disclosure. Data is encrypted in transit: yes. Users can delete their data: yes (unpair or uninstall). |
| Financial features | The app doesn't provide financial features. |
| Health, government, news | No |
| Full-screen intent | Not a calling or alarm app. The app works without it and asks the user to allow it in settings (the "Let requests ring like a call" card). |
| Foreground service | None used |

Google may still ask questions: the app's subject is OTPs. Lead with the consent story in any reply: no SMS permission, Android's own pop-up shows each message, the owner unlocks and approves every single code, and the sharing switch warns against turning it on for strangers.

### Store listing

**App name:** OTP Please

**Short description** (80 characters max):

> Get your partner's OTP on your phone with one tap. Encrypted, no account.

**Full description:**

> The card is in your partner's name, so the OTP goes to their phone, and you're stuck at checkout waiting for it. OTP Please fixes that.
>
> Tap Get OTP. Your partner's phone rings like a call. They tap Send a code, Android shows them the bank's message, and once they allow it the code appears on your phone a second later.
>
> • Pair once, in person, by scanning a QR code
> • Every code needs a yes on your partner's phone, unlocked
> • OTP Please can't read your messages: Android asks before showing it a single text
> • End-to-end encrypted, so only your two phones can read a code
> • No account, no sign-up, no ads
> • Open source: github.com/rlvamsi/otp-please
>
> Only pair with someone you trust completely, like the person you share a card with. Never turn on sharing because someone on a call or chat asked you to.

**Category:** Tools. **Contact email:** required by Play; use one you're happy to show publicly.

**Graphics:** a 512×512 icon, a 1024×500 feature graphic, and 2 to 8 phone screenshots. Real screenshots at the right size are in `docs/`: welcome, private, sharing, code, and the sharing phone's ringing.png and waiting.png.
