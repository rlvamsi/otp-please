package co.atomiclabs.otpair

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/** How long Get OTP waits for the other phone to start ringing. */
internal const val WINDOW_SECONDS = 60

/** How long the owner has to send a code once asked: as long as Android's consent pop-up listens for a text. */
internal const val APPROVE_SECONDS = 300

/** The only network hop: sealed messages go through a public ntfy server, which never sees a key. */
object Relay {
    // Any ntfy server works. To use your own, change this one line.
    private const val BASE = "https://ntfy.sh"

    // The small worker in worker/ that hands a sealed request to Firebase, which wakes the sharing
    // phone. Put its address here once it is deployed (docs/PLAY.md). Empty: nothing rings.
    private const val WAKE = ""

    /** Blocking. Throws IOException when the relay can't be reached. */
    fun publish(link: Link, msg: Msg) =
        post("$BASE/${link.topic}", link.seal(msg.pack()), "Firebase" to "no") // ntfy: don't fan out through Google's push service

    /**
     * Rings the other phone through Firebase. Only the sealed message and the pair's Firebase
     * topic leave this phone. Blocking; throws IOException when the worker can't be reached.
     */
    fun wake(link: Link, msg: Msg) {
        if (WAKE.isEmpty()) {
            Log.w("OtpPlease", "Relay.WAKE is empty, so the other phone can't be woken")
            return
        }
        post(WAKE, JSONObject().put("to", link.push).put("m", link.seal(msg.pack())).toString(), "Content-Type" to "application/json")
    }

    private fun post(url: String, body: String, header: Pair<String, String>) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty(header.first, header.second)
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("answered ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Messages from the last [since] (e.g. "60s"), then every new one for as long as the flow is
     * collected. Anything that isn't sealed with this link's key, or was written more than a few
     * minutes ago, is dropped. Fails with IOException when the relay can't be reached or the
     * stream drops.
     */
    fun listen(link: Link, since: String): Flow<Msg> = callbackFlow {
        val conn = URL("$BASE/${link.topic}/json?since=$since").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 120_000 // the server sends a keepalive line well inside this
        thread {
            try {
                conn.inputStream.bufferedReader().forEachLine { line ->
                    val event = JSONObject(line)
                    if (event.optString("event") == "message") {
                        link.open(event.optString("message"))?.let(Msg::unpack)?.takeIf { it.fresh() }?.let { trySend(it) }
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { conn.disconnect() }
    }.flowOn(Dispatchers.IO)
}

/** Everything the app remembers. Private to the app and excluded from backups. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("otpair", Context.MODE_PRIVATE)

    /** The pairing secret, kept encrypted: see [Vault]. */
    var link: Link?
        get() = prefs.getString("link", null)?.let(Vault::open)?.let(Link::decode)
        set(value) = prefs.edit().putString("link", value?.let { Vault.seal(it.encode()) }).apply()

    /** Whether OTPs arriving on this phone are made available to the partner. */
    var sharing: Boolean
        get() = prefs.getBoolean("sharing", false)
        set(value) = prefs.edit().putBoolean("sharing", value).apply()

    /** Once someone has reached pairing, the intro pages are skipped next time. */
    var seenIntro: Boolean
        get() = prefs.getBoolean("seenIntro", false)
        set(value) = prefs.edit().putBoolean("seenIntro", value).apply()

    /** Whether Get OTP asks for the fingerprint or screen lock first. */
    var lock: Boolean
        get() = prefs.getBoolean("lock", true)
        set(value) = prefs.edit().putBoolean("lock", value).apply()

    /** When this phone last sent a code to the partner, and who the text message was from. */
    var lastShared: Pair<Long, String>?
        get() = prefs.getLong("sharedAt", 0).takeIf { it > 0 }?.let { it to prefs.getString("sharedFrom", "").orEmpty() }
        set(value) = prefs.edit().putLong("sharedAt", value?.first ?: 0).putString("sharedFrom", value?.second).apply()

    /** Lets a phone ignore its own messages when both partners share. */
    val deviceId: String
        get() = prefs.getString("device", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
}

/**
 * Encrypts the pairing secret before it is written to the phone's storage, with a key that
 * lives in Android's keystore and cannot be copied out of the phone. A copy of the app's files
 * is useless without it.
 */
private object Vault {
    private const val NAME = "otpair"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(NAME, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(NAME, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()))
    }

    /** Null when the text can't be opened: the phone then simply counts as not paired. */
    fun open(sealed: String): String? = try {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    } catch (e: Exception) {
        null
    }
}

/**
 * Nothing is sent without the owner's say-so. When the partner asks, this phone rings like an
 * incoming call until the owner answers. Sharing does not work while these notifications are blocked.
 */
object Alerts {
    private const val CHANNEL = "ask"
    private const val ID = 1 // one request at a time: a new one replaces the last

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** Called when sharing is switched on, so the channel shows up in the phone's settings. */
    fun prepare(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Your partner asks for a code", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings until you choose to send a code or not."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC // says only that a code is wanted
        }
        manager(context).createNotificationChannel(channel)
        manager(context).deleteNotificationChannel("sent") // from the version that sent codes without asking
    }

    fun allowed(context: Context): Boolean {
        val manager = manager(context)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Whether the request can take over the screen like a call. Without it, it is a ringing notification. */
    fun fullScreen(context: Context) = Build.VERSION.SDK_INT < 34 || manager(context).canUseFullScreenIntent()

    /** [request] says which pair asked and until when: see [Wake]. [ring] is false once the owner has answered. */
    fun ask(context: Context, request: Bundle, ring: Boolean = true) {
        prepare(context)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val screen = PendingIntent.getActivity(context, ID, Intent(context, ApprovalActivity::class.java).putExtras(request), flags)
        val deny = PendingIntent.getBroadcast(context, ID, Intent(context, Decision::class.java).putExtras(request), flags)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFE82E54.toInt())
            .setContentTitle("Your partner is asking for a code")
            .setContentText(if (ring) "Tap to send one." else "Waiting for the code.")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(screen)
            .apply { if (ring) setFullScreenIntent(screen, true) }
            .setOnlyAlertOnce(!ring)
            .setOngoing(true)
            .setTimeoutAfter((request.getLong("until") - System.currentTimeMillis()).coerceAtLeast(1000))
            .addAction(Notification.Action.Builder(null, "Don’t send", deny).build())
            .build()
        if (ring) notification.flags = notification.flags or Notification.FLAG_INSISTENT // keeps ringing, like a call
        manager(context).notify(ID, notification)
    }

    fun active(context: Context) = manager(context).activeNotifications.any { it.id == ID }

    fun dismiss(context: Context) = manager(context).cancel(ID)
}

/**
 * Don't send, from the notification; and both answers from [ApprovalActivity]. Only this app can
 * trigger it.
 */
class Decision : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        answer(context, intent.extras ?: Bundle(), null) { done.finish() }
    }

    companion object {
        /** Sends [code] (and the text it came in, [body], if there is one), or "no" when [code] is null. */
        fun answer(context: Context, request: Bundle, code: String?, body: String = "", then: () -> Unit = {}) {
            val app = context.applicationContext
            Alerts.dismiss(app)
            thread {
                try {
                    val store = Store(app)
                    val link = store.link
                    // Too late, unpaired, re-paired, or sharing switched off since the request came in.
                    val current = link != null && link.topic == request.getString("topic") && store.sharing &&
                        System.currentTimeMillis() < request.getLong("until")
                    if (current && link != null) {
                        val msg = if (code != null) Msg("otp", store.deviceId, code, "", body) else Msg("no", store.deviceId)
                        try {
                            Relay.publish(link, msg)
                        } catch (e: IOException) {
                            Relay.publish(link, msg)
                        }
                        if (code != null) store.lastShared = msg.at to ""
                    }
                } catch (e: Exception) {
                    Log.w("OtpPlease", "answering the request failed: ${e.javaClass.simpleName}")
                } finally {
                    then()
                }
            }
        }
    }
}

/** Firebase wakes this phone with the partner's sealed request. Google can't read it. */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        message.data["m"]?.let { Wake.ring(this, it) }
    }
}

object Wake {
    /**
     * While this phone shares, it follows its pair's Firebase topic. Does nothing when the app
     * was built without Firebase (no google-services.json).
     */
    // ponytail: never unfollows. After unpairing or switching sharing off, a wake is simply ignored by [ring].
    fun follow(store: Store) {
        val link = store.link ?: return
        if (!store.sharing) return
        try {
            FirebaseMessaging.getInstance().subscribeToTopic(link.push)
        } catch (e: IllegalStateException) {
            Log.w("OtpPlease", "Firebase isn't set up in this build, so this phone can't be woken")
        }
    }

    /** Rings only for a fresh "want" sealed with this pair's key, and only while sharing is on. Blocking. */
    fun ring(context: Context, sealed: String) {
        val store = Store(context)
        val link = store.link ?: return
        val msg = link.open(sealed)?.let(Msg::unpack) ?: return
        if (msg.type != "want" || !msg.fresh() || msg.from == store.deviceId || !store.sharing || !Alerts.allowed(context)) return

        Consent.listen(context) // a text arriving from now on can be offered while the phone rings
        Alerts.ask(context, Bundle().apply {
            putString("topic", link.topic)
            putLong("until", System.currentTimeMillis() + APPROVE_SECONDS * 1000L)
        })
        // Lets the partner's screen say this phone is ringing.
        try {
            Relay.publish(link, Msg("asking", store.deviceId))
        } catch (e: IOException) {
            Log.w("OtpPlease", "telling the partner failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * Android's one-message pop-up: "Allow OTP Please to read this message?". For five minutes after
 * [listen], the next text that contains a code (from a sender not in contacts) is offered, and the
 * owner sees the whole message before allowing it. The app never has the SMS permission.
 */
object Consent {
    /** The pop-up, waiting until [ApprovalActivity] is in front to show it. */
    var pending by mutableStateOf<Intent?>(null)

    fun listen(context: Context) {
        SmsRetriever.getClient(context).startSmsUserConsent(null)
    }
}

/** Only Google Play services can deliver this (see the manifest), so other apps can't fake one. */
class ConsentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val status = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_STATUS, Status::class.java)
        if (status?.statusCode == CommonStatusCodes.SUCCESS) {
            Consent.pending = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
        }
    }
}
