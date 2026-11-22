package co.atomiclabs.otpair

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.phone.SmsRetriever
import kotlinx.coroutines.delay

/**
 * The full-screen request. It comes up over the lock screen, like an incoming call, when the
 * partner asks. Answering needs the phone unlocked. Then the code comes from Android's
 * "Allow OTP Please to read this message?" pop-up for the next text, or the owner copies it
 * from a text that already came and taps Paste code.
 */
class ApprovalActivity : ComponentActivity() {
    private var request by mutableStateOf<Bundle?>(null)
    private var answered by mutableStateOf(false)
    private var note by mutableStateOf<String?>(null)

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
        val code = Otp.find(text)
        if (result.resultCode == RESULT_OK && code != null) {
            send(code, text)
        } else {
            Consent.listen(this) // not allowed, or no code in it: keep waiting for the next text
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepOutOfScreenshots()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // while waiting for the text
        request = intent.extras
        setContent {
            OtpairTheme {
                Surface(Modifier.fillMaxSize(), color = look.bg) {
                    request?.let { Approval(it, answered, note, ::accept, ::paste, { answer(null) }, ::finish) }
                }
            }
            // A text that came in while ringing is offered as soon as the owner has answered.
            val pending = Consent.pending
            LaunchedEffect(answered, pending) {
                if (answered && pending != null) {
                    unlocked {
                        Consent.pending = null
                        consent.launch(pending)
                    }
                }
            }
        }
    }

    // A second request while this one is showing replaces it.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = intent.extras
        if (answered) request?.let { Alerts.ask(this, it, ring = false) }
    }

    /** Someone holding the locked phone can't send a code: Android asks for the fingerprint or PIN first. */
    private fun unlocked(then: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return then()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
        })
    }

    private fun accept() = unlocked {
        answered = true
        request?.let { Alerts.ask(this, it, ring = false) } // stops the ringing
    }

    private fun paste() = unlocked {
        val text = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val code = Otp.find(text)
        if (code == null) {
            note = "There’s no code on the clipboard. Copy it from the text first."
            return@unlocked
        }
        send(code, text.trim().takeIf { it != code }.orEmpty())
    }

    private fun send(code: String, body: String) {
        Toast.makeText(this, "Code sent", Toast.LENGTH_SHORT).show()
        answer(code, body)
    }

    private fun answer(code: String?, body: String = "") {
        request?.let { Decision.answer(this, it, code, body) }
        finish()
    }
}

@Composable
private fun Approval(
    request: Bundle, answered: Boolean, note: String?,
    onAccept: () -> Unit, onPaste: () -> Unit, onDeny: () -> Unit, onGone: () -> Unit,
) {
    val context = LocalContext.current
    val until = request.getLong("until")
    var left by remember(request) { mutableLongStateOf(until - System.currentTimeMillis()) }
    // Closes by itself once the time is up or the request was turned down from the notification.
    LaunchedEffect(request) {
        while (true) {
            left = until - System.currentTimeMillis()
            if (left <= 0 || !Alerts.active(context)) return@LaunchedEffect onGone()
            delay(500)
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            if (answered) Box(Modifier.size(140.dp), Alignment.Center) { Logo(80.dp) } else Ringing()
            Spacer(Modifier.height(32.dp))
            T("YOUR PARTNER IS ASKING", Type.eyebrow, color = look.soft)
            Spacer(Modifier.height(10.dp))
            T(if (answered) "Waiting for the code" else "Send them a code?", Type.display, align = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            if (!answered) {
                T("Only if they’re paying or signing in right now.", Type.body, color = look.soft, align = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().background(look.surface, RoundedCornerShape(24.dp)).padding(20.dp)) {
                    T("When the text arrives", Type.bodyStrong)
                    Spacer(Modifier.height(4.dp))
                    T("Android shows it and asks to let OTP Please read it. Check the amount and the shop, then tap Allow.", Type.small, color = look.soft)
                    Spacer(Modifier.height(16.dp))
                    T("Already got it?", Type.bodyStrong)
                    Spacer(Modifier.height(4.dp))
                    T("Copy the code from the text, then tap Paste code.", Type.small, color = look.soft)
                }
            }
            if (note != null) {
                Spacer(Modifier.height(16.dp))
                T(note, Type.small, color = look.ink, align = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
        }
        val seconds = (left.coerceAtLeast(0) + 999) / 1000
        T("%d:%02d left".format(seconds / 60, seconds % 60), Type.small, color = look.soft)
        Spacer(Modifier.height(16.dp))
        if (answered) {
            PrimaryButton("Paste code", Modifier.fillMaxWidth(), icon = Glyph.Copy, onClick = onPaste)
        } else {
            PrimaryButton("Send a code", Modifier.fillMaxWidth(), icon = Glyph.Check, onClick = onAccept)
        }
        Spacer(Modifier.height(12.dp))
        OutlineButton("Don’t send", Modifier.fillMaxWidth(), onClick = onDeny)
        Spacer(Modifier.height(16.dp))
    }
}

/** The logo with a ring spreading out from it, like a phone ringing. */
@Composable
private fun Ringing() {
    val t by rememberInfiniteTransition(label = "ring").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "t")
    Box(Modifier.size(140.dp), Alignment.Center) {
        for (offset in listOf(0f, 0.5f)) {
            val p = (t + offset) % 1f
            Box(
                Modifier.size(80.dp).graphicsLayer {
                    scaleX = 1f + p * 0.75f
                    scaleY = 1f + p * 0.75f
                    alpha = (1f - p) * 0.35f
                }.background(Coral, CircleShape),
            )
        }
        Logo(80.dp)
    }
}
