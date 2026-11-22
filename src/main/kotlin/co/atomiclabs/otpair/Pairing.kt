package co.atomiclabs.otpair

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.ResultPoint
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Context.allows(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.openAppSettings() =
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

/** Whether this phone has a PIN, pattern, password or fingerprint to ask for. */
internal fun Context.canLock() = Build.VERSION.SDK_INT >= 28 && getSystemService(KeyguardManager::class.java).isDeviceSecure

/**
 * Asks for the fingerprint, face or screen lock, and runs [then] only once it is given.
 * A phone with no screen lock has nothing to ask, so [then] runs straight away.
 */
// ponytail: Android 8 (API 26 and 27) has no system prompt for this, so the lock does nothing
// there. Use KeyguardManager.createConfirmDeviceCredentialIntent if those phones matter.
internal fun Context.unlockThen(title: String, then: () -> Unit) {
    if (Build.VERSION.SDK_INT < 28 || !canLock()) return then()
    val prompt = BiometricPrompt.Builder(this).setTitle(title).apply {
        if (Build.VERSION.SDK_INT >= 30) {
            setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            setDeviceCredentialAllowed(true)
        }
    }.build()
    prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = then()
    })
}

// ───────────────────────── Show the code ─────────────────────────

@Composable
internal fun ShowCode(store: Store, onPaired: (Link) -> Unit, onBack: () -> Unit) {
    val pending = remember { Pairing.new() }
    BackHandler(onBack = onBack)

    // Wait for the other phone's hello, which carries its public key. The first phone to scan
    // becomes the partner; answering on the new link tells it the pairing worked.
    LaunchedEffect(pending) {
        while (true) {
            try {
                val link = Relay.listen(Pairing.meet(pending.public), "3m")
                    .mapNotNull { hello ->
                        hello.takeIf { it.type == "hello" && it.from != store.deviceId }
                            ?.let { Pairing.key(it.code) }?.let { pending.finish(it, showed = true) }
                    }
                    .first()
                withContext(Dispatchers.IO) { Relay.publish(link, Msg("hello", store.deviceId)) }
                onPaired(link)
                return@LaunchedEffect
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(2000) // offline or the stream dropped: keep waiting
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Box(Modifier.height(64.dp), Alignment.CenterStart) {
            CircleButton(Glyph.Back, "Back", Modifier.offset(x = (-4).dp), onClick = onBack)
        }
        Spacer(Modifier.height(12.dp))
        T("Scan this from your partner’s phone", Type.title)
        Spacer(Modifier.height(10.dp))
        T("Open OTP Please there and tap Scan partner’s code.", Type.body, color = look.soft)
        Spacer(Modifier.height(28.dp))

        val card = RoundedCornerShape(32.dp)
        Box(
            Modifier.fillMaxWidth()
                .shadow(28.dp, card, ambientColor = Color(0x14000000), spotColor = Color(0x2E000000))
                .background(Color.White, card).border(1.dp, Color(0xFFEBEBEB), card).padding(28.dp),
        ) {
            Qr(pending.qr(), Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Pulse()
            Spacer(Modifier.width(14.dp))
            Column {
                T("Waiting for the other phone", Type.bodyStrong)
                T("This screen moves on by itself once it’s scanned.", Type.small, color = look.soft)
            }
        }
        Spacer(Modifier.height(20.dp))
        Hairline()
        Spacer(Modifier.height(20.dp))
        Row {
            Icon(Glyph.Lock, null, Modifier.size(20.dp), look.soft)
            Spacer(Modifier.width(14.dp))
            T("Hold it up to your partner’s phone, in the same room. It works once, and can’t be screenshotted or sent.", Type.small, color = look.soft)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A coral dot that breathes: something is still happening. */
@Composable
private fun Pulse() {
    val beat by rememberInfiniteTransition(label = "pulse")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "beat")
    Canvas(Modifier.size(20.dp)) {
        drawCircle(Coral.copy(alpha = 0.35f * (1f - beat)), size.minDimension / 2 * (0.4f + 0.6f * beat))
        drawCircle(Coral, 4.5.dp.toPx())
    }
}

private val QrInk = Color(0xFF222222)

/**
 * The pairing code. Always dark on white, in both themes, so every camera can read it.
 * The mark in the middle hides a few modules; error correction "H" is there to absorb that.
 */
@Composable
internal fun Qr(text: String, modifier: Modifier = Modifier) {
    val cells = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H),
        )
    }
    Box(modifier.aspectRatio(1f), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val n = cells.width
            val c = size.width / n
            val hole = n * 0.13f // half-width of the patch kept clear for the mark
            for (y in 0 until n) for (x in 0 until n) {
                val inEye = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
                val inHole = kotlin.math.abs(x + 0.5f - n / 2f) < hole && kotlin.math.abs(y + 0.5f - n / 2f) < hole
                if (cells[x, y] && !inEye && !inHole) {
                    drawRoundRect(QrInk, Offset((x + 0.04f) * c, (y + 0.04f) * c), Size(c * 0.92f, c * 0.92f), CornerRadius(c * 0.32f))
                }
            }
            for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                drawRoundRect(QrInk, Offset((ox + 0.5f) * c, (oy + 0.5f) * c), Size(6 * c, 6 * c), CornerRadius(c * 1.9f), Stroke(c))
                drawRoundRect(QrInk, Offset((ox + 2f) * c, (oy + 2f) * c), Size(3 * c, 3 * c), CornerRadius(c * 0.95f))
            }
        }
        Logo(46.dp)
    }
}

// ───────────────────────── Scan the code ─────────────────────────

/** Four rounded corners of a viewfinder. */
internal fun DrawScope.brackets(color: Color, inset: Float, arm: Float, radius: Float, width: Float) {
    val l = inset
    val t = inset
    val r = size.width - inset
    val b = size.height - inset
    val path = Path().apply {
        moveTo(l, t + arm); lineTo(l, t + radius); quadraticTo(l, t, l + radius, t); lineTo(l + arm, t)
        moveTo(r - arm, t); lineTo(r - radius, t); quadraticTo(r, t, r, t + radius); lineTo(r, t + arm)
        moveTo(r, b - arm); lineTo(r, b - radius); quadraticTo(r, b, r - radius, b); lineTo(r - arm, b)
        moveTo(l + arm, b); lineTo(l + radius, b); quadraticTo(l, b, l, b - radius); lineTo(l, b - arm)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
}

@Composable
internal fun Scanner(store: Store, resumes: Int, onPaired: (Link) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember(resumes) { mutableStateOf(context.allows(Manifest.permission.CAMERA)) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }
    BackHandler(onBack = onBack)

    // The camera is dark in both themes: keep the clock and battery icons white while it's up.
    DisposableEffect(Unit) {
        val activity = context as ComponentActivity
        val clear = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        activity.enableEdgeToEdge(clear, clear)
        onDispose { activity.enableEdgeToEdge() }
    }

    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    fun scanned(text: String) {
        if (busy) return
        val theirs = Pairing.scan(text)
        if (theirs == null) {
            hint = "That isn’t an OTP Please code. Scan the one inside OTP Please on the other phone."
            return
        }
        busy = true
        hint = null
        scope.launch {
            // Hello carries this phone's public key. The other phone answers on the new link, which
            // proves both hold the same secret. No answer means it paired with a different phone.
            val mine = Pairing.new()
            val link = mine.finish(theirs, showed = false)
            val sent = link != null && withContext(Dispatchers.IO) {
                runCatching { Relay.publish(Pairing.meet(theirs), Msg("hello", store.deviceId, mine.code())) }.isSuccess
            }
            val answered = sent && runCatching {
                withTimeoutOrNull(15_000) { Relay.listen(link!!, "60s").first { it.type == "hello" && it.from != store.deviceId } } != null
            }.getOrDefault(false)
            when {
                answered -> onPaired(link!!)
                sent -> hint = "That code didn’t pair. Ask your partner to go back and show a new one."
                link == null -> hint = "That isn’t an OTP Please code. Scan the one inside OTP Please on the other phone."
                else -> hint = "Couldn’t connect. Check your internet and scan again."
            }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) Camera(::scanned)

        val sweep by rememberInfiniteTransition(label = "scan")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "line")
        Column(
            // Drawn into its own layer so the viewfinder can punch a clear window through the shade.
            Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind { drawRect(Color.Black.copy(alpha = if (granted) 0.62f else 1f)) }
                .safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(64.dp), Alignment.CenterStart) {
                CircleButton(Glyph.Close, "Close", Modifier.offset(x = (-4).dp), onDark = true, onClick = onBack)
            }
            Spacer(Modifier.weight(0.5f))
            T("Scan your partner’s code", Type.title, color = Color.White, align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            T("Ask them to open OTP Please and tap Show my code.", Type.body, color = Color(0xCCFFFFFF), align = TextAlign.Center)
            Spacer(Modifier.height(32.dp))

            Box(
                Modifier.fillMaxWidth(0.84f).aspectRatio(1f).drawBehind {
                    val corner = 32.dp.toPx()
                    if (granted) {
                        drawRoundRect(Color.Black, cornerRadius = CornerRadius(corner), blendMode = BlendMode.Clear)
                        if (!busy) {
                            val y = 28.dp.toPx() + (size.height - 56.dp.toPx()) * sweep
                            drawLine(Coral, Offset(28.dp.toPx(), y), Offset(size.width - 28.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
                        }
                    } else {
                        drawRoundRect(Color(0xFF1C1C1C), cornerRadius = CornerRadius(corner))
                    }
                    brackets(Color.White, inset = 2.dp.toPx(), arm = 36.dp.toPx(), radius = corner - 2.dp.toPx(), width = 4.dp.toPx())
                },
                Alignment.Center,
            ) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
                    !granted -> Column(Modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Glyph.Camera, null, Modifier.size(32.dp), Color.White)
                        Spacer(Modifier.height(16.dp))
                        T("The camera is off for OTP Please", Type.bodyStrong, color = Color.White, align = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        T("Allow it in settings to scan the code.", Type.small, color = Color(0xB3FFFFFF), align = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Tappable({ context.openAppSettings() }, Modifier.height(48.dp), color = Color.White) {
                            T("Open settings", Type.button, Modifier.padding(horizontal = 22.dp), color = Color(0xFF222222))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(64.dp), Alignment.TopCenter) {
                val message = if (busy) "Pairing…" else hint
                if (message != null) {
                    T(
                        message, Type.small,
                        Modifier.background(Color(0xFF2B2B2B), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                        color = Color.White, align = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Camera(onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onText)
    val camera = remember {
        BarcodeView(context).apply {
            isUseTextureView = true // fills the screen edge to edge and plays well with Compose layers
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    result.text?.let { current(it) }
                }

                override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
            })
        }
    }
    AndroidView({ camera }, Modifier.fillMaxSize())
    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) camera.resume()
            if (event == Lifecycle.Event.ON_PAUSE) camera.pause()
        }
        owner.lifecycle.addObserver(watch)
        onDispose {
            owner.lifecycle.removeObserver(watch)
            camera.pause()
        }
    }
}

// ───────────────────────── Just paired ─────────────────────────

/** The one question that decides how this phone is used. Asked once, right after pairing. */
@Composable
internal fun PairedSetup(store: Store, link: Link, onDone: () -> Unit) {
    var shares by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val startSharing = shareSwitch(store, onDone)
    BackHandler(onBack = onDone)

    val join = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250)
        join.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 180f))
        tick.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(56.dp))
            Box {
                Logo(76.dp, join = join.value)
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp).size(30.dp)
                        .graphicsLayer {
                            scaleX = tick.value
                            scaleY = tick.value
                        }
                        .background(look.bg, CircleShape).padding(3.dp).background(look.good, CircleShape),
                    Alignment.Center,
                ) {
                    Icon(Glyph.Check, null, Modifier.size(14.dp), Color.White)
                }
            }
            Spacer(Modifier.height(28.dp))
            T("You’re paired.", Type.display)
            Spacer(Modifier.height(10.dp))
            T("One last thing, so this phone knows its job.", Type.body, color = look.soft)
            Spacer(Modifier.height(20.dp))
            PairNumber(link)
            Spacer(Modifier.height(32.dp))
            T("Do bank codes come to this phone?", Type.heading)
            Spacer(Modifier.height(16.dp))
            Choice(
                Glyph.Message, "Yes, they come here",
                "Share them with your partner. Android will ask to let OTP Please notify you.",
                shares == true,
            ) { shares = true }
            Spacer(Modifier.height(12.dp))
            Choice(
                Glyph.Phone, "No, they go to my partner",
                "You’ll ask for codes from this phone. Nothing here is shared.",
                shares == false,
            ) { shares = false }
            Spacer(Modifier.height(16.dp))
            T("You can change this any time.", Type.small, color = look.soft)
            Spacer(Modifier.height(24.dp))
        }
        Hairline()
        PrimaryButton("Continue", Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), enabled = shares != null) {
            if (shares == true) startSharing() else onDone()
        }
    }
}

/** The same four digits on both phones mean they are paired with each other and nobody else. */
@Composable
internal fun PairNumber(link: Link) {
    Row(
        Modifier.fillMaxWidth().background(look.surface, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T("Pair number", Type.bodyStrong)
            T("Both phones should show the same one.", Type.small, color = look.soft)
        }
        Spacer(Modifier.width(16.dp))
        T(link.check.chunked(2).joinToString(" "), Type.title)
    }
}

/** What Android has to allow before this phone can share: ringing its owner. Texts are never read without asking. */
internal val sharePermissions =
    listOfNotNull(Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 })

/**
 * Turning sharing on goes the same way wherever it starts: a plain warning first, then Android's
 * permissions. Returns the function that begins it; [done] runs once sharing is switched on.
 * If Android says no, sharing stays paused and the home screen explains how to allow it.
 */
@Composable
internal fun shareSwitch(store: Store, done: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var warning by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { done() }
    fun allow() {
        store.sharing = true
        Alerts.prepare(context)
        Wake.follow(store)
        val missing = sharePermissions.filterNot(context::allows)
        if (missing.isEmpty()) done() else ask.launch(missing.toTypedArray())
    }

    if (warning) {
        Sheet(onDismiss = { warning = false }) { close ->
            T("Share only with someone you trust completely", Type.title)
            Spacer(Modifier.height(20.dp))
            for ((icon, line) in listOf(
                Glyph.Message to "Your partner will be able to ask for the codes that banks and other services text to this phone.",
                Glyph.Alert to "Never turn this on because someone on a call or chat asked you to. Banks, police and support staff never will.",
                Glyph.Bell to "Nothing is sent on its own. When your partner asks, this phone rings, and a code goes only if you allow it.",
            )) {
                Row(Modifier.padding(bottom = 16.dp)) {
                    Icon(icon, null, Modifier.size(22.dp), look.ink)
                    Spacer(Modifier.width(14.dp))
                    T(line, Type.body, color = look.soft)
                }
            }
            Spacer(Modifier.height(12.dp))
            DarkButton("I understand, turn on", Modifier.fillMaxWidth()) { close(::allow) }
            Spacer(Modifier.height(4.dp))
            TextLink("Not now", Modifier.align(Alignment.CenterHorizontally)) { close {} }
        }
    }
    return { if (store.sharing) allow() else warning = true }
}

@Composable
private fun Choice(icon: ImageVector, title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Tappable(
        onClick,
        Modifier.fillMaxWidth().semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        RoundedCornerShape(20.dp),
        color = if (selected) look.surface else Color.Transparent,
        border = if (selected) BorderStroke(2.dp, look.ink) else BorderStroke(1.dp, look.border),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), look.ink)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                T(title, Type.bodyStrong)
                Spacer(Modifier.height(2.dp))
                T(detail, Type.small, color = look.soft)
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier.size(24.dp)
                    .then(if (selected) Modifier.background(look.ink, CircleShape) else Modifier.border(1.5.dp, look.faint, CircleShape)),
                Alignment.Center,
            ) {
                if (selected) Icon(Glyph.Check, null, Modifier.size(14.dp), look.bg)
            }
        }
    }
}
