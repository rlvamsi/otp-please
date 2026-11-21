package co.atomiclabs.otpair

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import kotlinx.coroutines.delay

/**
 * The full-screen "Send this code?" page. It comes up over the lock screen, like an incoming
 * call, when the partner asks and a code is here. Send needs the phone unlocked.
 */
class ApprovalActivity : ComponentActivity() {
    private var request by mutableStateOf<Bundle?>(null)

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
        request = intent.extras
        setContent {
            OtpairTheme {
                Surface(Modifier.fillMaxSize(), color = look.bg) {
                    request?.let { Approval(it, ::answer, ::finish) }
                }
            }
        }
    }

    // A second request while this one is showing replaces it.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = intent.extras
    }

    private fun answer(send: Boolean) {
        val now = request ?: return finish()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!send || !keyguard.isKeyguardLocked) {
            Decision.decide(this, now, send)
            finish()
            return
        }
        // Someone holding the locked phone can't send a code: Android asks for the fingerprint or PIN first.
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Decision.decide(this@ApprovalActivity, now, true)
                finish()
            }
        })
    }
}

@Composable
private fun Approval(request: Bundle, onAnswer: (Boolean) -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val until = request.getLong("until")
    var left by remember(request) { mutableLongStateOf(until - System.currentTimeMillis()) }
    // Closes by itself once the time is up or the notification was answered from the shade.
    LaunchedEffect(request) {
        while (true) {
            left = until - System.currentTimeMillis()
            if (left <= 0 || !Alerts.active(context, request.getInt("id"))) return@LaunchedEffect onGone()
            delay(500)
        }
    }
    val sender = request.getString("sender").orEmpty().ifBlank { "Text message" }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Ringing()
            Spacer(Modifier.height(32.dp))
            T("YOUR PARTNER IS ASKING", Type.eyebrow, color = look.soft)
            Spacer(Modifier.height(10.dp))
            T("Send this code?", Type.display, align = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth().background(look.surface, RoundedCornerShape(24.dp)).padding(20.dp)) {
                T(sender.uppercase(), Type.eyebrow, color = look.soft, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                T(request.getString("body").orEmpty(), Type.body)
            }
            Spacer(Modifier.height(16.dp))
            T(
                "Check the amount and the shop. Only send it if your partner is paying right now.",
                Type.small, color = look.soft, align = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
        val seconds = (left.coerceAtLeast(0) + 999) / 1000
        T("%d:%02d left to decide".format(seconds / 60, seconds % 60), Type.small, color = look.soft)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Send code", Modifier.fillMaxWidth(), icon = Glyph.Check) { onAnswer(true) }
        Spacer(Modifier.height(12.dp))
        OutlineButton("Don’t send", Modifier.fillMaxWidth()) { onAnswer(false) }
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
