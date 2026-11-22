package co.atomiclabs.otpair

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** How long a code is held for the partner, and how long the partner waits for one. */
internal const val WINDOW_SECONDS = 60

/** How long the owner has to tap Send once asked. Bank codes usually stay valid longer than this. */
internal const val APPROVE_SECONDS = 180

/** The only network hop: sealed messages go through a public ntfy server, which never sees a key. */
object Relay {
    // Any ntfy server works. To use your own, change this one line.
    private const val BASE = "https://ntfy.sh"

    /** Blocking. Throws IOException when the relay can't be reached. */
    fun publish(link: Link, msg: Msg) {
        val conn = URL("$BASE/${link.topic}").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Firebase", "no") // don't fan out through Google's push service
            conn.outputStream.use { it.write(link.seal(msg.pack()).toByteArray()) }
            if (conn.responseCode !in 200..299) throw IOException("relay answered ${conn.responseCode}")
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
 * Nothing is sent without the owner's say-so. When the partner asks and a code is here, this
 * phone rings like an incoming call until the owner taps Send or Don't send. Sharing does not
 * work while these notifications are blocked.
 */
object Alerts {
    private const val CHANNEL = "ask"

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** Called when sharing is switched on, so the channel shows up in the phone's settings. */
    fun prepare(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Your partner asks for a code", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings until you choose to send the code or not."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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

    /** [request] holds the code and everything needed to send it: see [ShareJob]. */
    fun ask(context: Context, request: Bundle) {
        prepare(context)
        val id = request.getInt("id")
        val from = request.getString("sender").orEmpty().ifBlank { "a text message" }
        val screen = PendingIntent.getActivity(
            context, id, Intent(context, ApprovalActivity::class.java).putExtras(request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun decide(send: Boolean) = PendingIntent.getBroadcast(
            context, id * 2 + if (send) 1 else 0,
            Intent(context, Decision::class.java).setAction(if (send) Decision.SEND else Decision.DENY).putExtras(request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = "Your partner is asking for a code"
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFE82E54.toInt())
            .setContentTitle(title)
            .setContentText("Unlock to choose whether to send it.")
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFE82E54.toInt())
            .setContentTitle(title)
            .setContentText("From $from. Send it?")
            .setStyle(Notification.BigTextStyle().bigText("From $from:\n${request.getString("body").orEmpty()}"))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .setOngoing(true)
            .setTimeoutAfter((request.getLong("until") - System.currentTimeMillis()).coerceAtLeast(1000))
            .addAction(
                Notification.Action.Builder(null, "Send", decide(true))
                    .apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }
                    .build(),
            )
            .addAction(Notification.Action.Builder(null, "Don’t send", decide(false)).build())
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT // keeps ringing, like a call
        manager(context).notify(id, notification)
    }

    fun active(context: Context, id: Int) = manager(context).activeNotifications.any { it.id == id }

    fun dismiss(context: Context, id: Int) = manager(context).cancel(id)
}

/**
 * Send and Don't send, from the notification or from [ApprovalActivity]. Only this app can
 * trigger it. The code travels in the request itself, so it works even if the app was closed
 * in between.
 */
class Decision : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        decide(context, intent.extras ?: Bundle(), intent.action == SEND) { done.finish() }
    }

    companion object {
        const val SEND = "co.atomiclabs.otpair.SEND"
        const val DENY = "co.atomiclabs.otpair.DENY"

        fun decide(context: Context, request: Bundle, send: Boolean, then: () -> Unit = {}) {
            val app = context.applicationContext
            Alerts.dismiss(app, request.getInt("id"))
            thread {
                try {
                    val store = Store(app)
                    val link = store.link
                    // Too late, unpaired, re-paired, or sharing switched off since the request came in.
                    val current = link != null && link.topic == request.getString("topic") && store.sharing &&
                        System.currentTimeMillis() < request.getLong("until")
                    if (current && link != null) {
                        val msg = if (send) {
                            Msg("otp", store.deviceId, request.getString("code").orEmpty(), request.getString("sender").orEmpty(), request.getString("body").orEmpty())
                        } else {
                            Msg("no", store.deviceId)
                        }
                        try {
                            Relay.publish(link, msg)
                        } catch (e: IOException) {
                            Relay.publish(link, msg)
                        }
                        if (send) store.lastShared = msg.at to msg.sender
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

/**
 * Android starts this for every incoming text message, even when the app isn't running.
 * An OTP is not sent anywhere from here: it is handed to [ShareJob], which gives it to the
 * partner only if the partner asks.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val store = Store(context)
        if (!store.sharing || !Alerts.allowed(context) || store.link == null) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val code = Otp.extract(body) ?: return // not an OTP: nothing more happens
        ShareJob.hold(context, code, parts.firstOrNull()?.displayOriginatingAddress.orEmpty(), body)
    }
}

/**
 * Keeps one code on this phone for a minute. If the partner asked for a code in the minute
 * before it arrived, or asks in the minute after, this phone rings and the owner decides
 * whether to send it ([Alerts.ask]). Otherwise the code is forgotten and never leaves the phone.
 */
class ShareJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val holding = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val store = Store(this)
        val link = store.link ?: return false
        val extras = params.extras
        val until = extras.getLong("until")

        holding[params.jobId] = scope.launch {
            try {
                while (true) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) break
                    try {
                        // "60s" also finds a request made just before the text message arrived.
                        val asked = withTimeoutOrNull(left) {
                            Relay.listen(link, "${WINDOW_SECONDS}s").first { it.type == "want" && it.from != store.deviceId }
                        }
                        // Sharing may have been switched off, or the phone unpaired, during the wait.
                        val still = Store(this@ShareJob)
                        if (asked != null && still.sharing && still.link?.topic == link.topic && Alerts.allowed(this@ShareJob)) {
                            val request = Bundle().apply {
                                putInt("id", params.jobId)
                                putString("topic", link.topic)
                                putString("code", extras.getString("code"))
                                putString("sender", extras.getString("sender"))
                                putString("body", extras.getString("body"))
                                putLong("until", System.currentTimeMillis() + APPROVE_SECONDS * 1000L)
                            }
                            Alerts.ask(this@ShareJob, request)
                            // Lets the partner's screen say the code is here and waiting for a yes.
                            try {
                                Relay.publish(link, Msg("asking", store.deviceId))
                            } catch (e: IOException) {
                                Log.w("OtpPlease", "telling the partner failed: ${e.javaClass.simpleName}")
                            }
                        }
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Only the kind of error: its text can carry the relay address of this pair.
                        Log.w("OtpPlease", "waiting for the partner to ask: ${e.javaClass.simpleName}")
                        delay(1500) // offline or the stream dropped: keep waiting out the minute
                    }
                }
            } finally {
                holding.remove(params.jobId)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        holding.remove(params.jobId)?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun hold(context: Context, code: String, sender: String, body: String) {
            val extras = PersistableBundle().apply {
                putString("code", code)
                putString("sender", sender)
                putString("body", body)
                putLong("until", System.currentTimeMillis() + WINDOW_SECONDS * 1000L)
            }
            val id = (System.nanoTime() and 0x3FFFFFFF).toInt()
            val job = JobInfo.Builder(id, ComponentName(context, ShareJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .apply {
                    // ponytail: expedited jobs start at once and survive battery saving on Android 12+.
                    // Older phones get an ordinary job, which Doze can delay; use a foreground service
                    // there if codes go missing.
                    if (Build.VERSION.SDK_INT >= 31) setExpedited(true) else setOverrideDeadline(0)
                }
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
