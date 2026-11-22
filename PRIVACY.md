# OTP Please privacy policy

_Last updated: 22 November 2026_

OTP Please lets two phones that you pair with each other pass one-time codes between them. This policy explains what the app handles, in plain words.

## What OTP Please does not do

- It has no accounts, no sign-up, no ads and no analytics.
- It cannot read your text messages. It has no SMS permission. Android shows you a message and asks you before the app gets it, and only after your partner asks for a code.
- It does not sell, rent or share your information with anyone.

## What the app handles

**Codes and the messages they came in.** When your partner asks for a code and you allow a message in Android's pop-up, or paste a code yourself, the app encrypts it on your phone and sends it to your partner's phone. Only the two paired phones hold the key. Nobody else can read it, including us.

**Requests.** Tapping Get OTP sends an encrypted request that wakes your partner's phone.

**The pairing secret and settings**, such as whether sharing is on. These stay on your phone, encrypted with a key held in Android's keystore, and are left out of backups.

## Services the messages pass through

- **ntfy.sh**, a public relay, carries the encrypted requests and codes. It sees scrambled text and when it was sent, and keeps it for about 12 hours. See [ntfy.sh's privacy policy](https://docs.ntfy.sh/privacy/).
- **Firebase Cloud Messaging** (Google) wakes the sharing phone. It sees an encrypted request, a random topic name for your pair and the time. See [Google's privacy policy](https://policies.google.com/privacy).
- **A small Cloudflare worker** run for OTP Please hands each request to Firebase. It keeps nothing. See [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).

## Deleting your data

Unpair in Settings, or uninstall the app, and everything it stored on the phone is gone. Anything on the relay expires within about 12 hours, and it is unreadable without the key on your phones.

## Children

OTP Please is meant for adults who share a payment card.

## Changes and questions

If this policy changes, the new version will be posted here. Questions: open an issue at https://github.com/rlvamsi/otp-please/issues
