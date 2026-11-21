package co.atomiclabs.otpair

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Phase { Idle, Listening, Approving, Found, Declined, Missed, Unanswered, Offline }

/** Six digits read best as two groups of three. */
private fun pretty(code: String) = if (code.length == 6) code.chunked(3).joinToString(" ") else code

@Composable
internal fun Home(store: Store, link: Link, resumes: Int, onUnpair: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val codes = remember { mutableStateListOf<Msg>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var left by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(WINDOW_SECONDS) }
    var asked by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf(false) } // the code reached the other phone, which is asking its owner
    var declined by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val listening = job?.isActive == true
    val phase = when {
        codes.isNotEmpty() -> Phase.Found
        declined -> Phase.Declined
        listening && approving -> Phase.Approving
        listening -> Phase.Listening
        offline -> Phase.Offline
        asked && approving -> Phase.Unanswered
        asked -> Phase.Missed
        else -> Phase.Idle
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun ask() {
        codes.clear()
        offline = false
        asked = true
        approving = false
        declined = false
        copied = false
        total = WINDOW_SECONDS
        left = WINDOW_SECONDS
        job = scope.launch {
            val start = SystemClock.elapsedRealtime()
            var end = start + WINDOW_SECONDS * 1000L
            var done = false
            // Counted off the clock, so it is still right after Android pauses the app in the background.
            val clock = launch {
                while (true) {
                    left = ((end - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceIn(0, total)
                    delay(250)
                }
            }
            try {
                // Nothing is sent from the other phone until this request reaches it.
                withContext(Dispatchers.IO) { Relay.publish(link, Msg("want", store.deviceId)) }
                var dropped = false
                var misses = 0
                while (true) {
                    // Android cuts an app's internet soon after it leaves the screen (she switched to the
                    // bank page), so wait here until OTPair is in front again.
                    lifecycle.withResumed { }
                    val began = SystemClock.elapsedRealtime()
                    if (done || began >= end && !dropped) break
                    try {
                        coroutineScope {
                            val listen = launch {
                                Relay.listen(link, "${(began - start) / 1000 + WINDOW_SECONDS}s").collect { msg ->
                                    if (msg.from == store.deviceId) return@collect
                                    when (msg.type) {
                                        // The code is on the other phone, which is ringing: wait for its owner.
                                        "asking" -> if (!approving) {
                                            approving = true
                                            total = APPROVE_SECONDS
                                            end = maxOf(end, SystemClock.elapsedRealtime() + APPROVE_SECONDS * 1000L)
                                        }
                                        "no" -> {
                                            declined = true
                                            done = true
                                        }
                                        "otp" -> if (codes.none { it.code == msg.code && it.body == msg.body }) {
                                            codes.add(0, msg)
                                            copied = false
                                            done = true
                                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        }
                                    }
                                }
                            }
                            // After a drop, look once more even if the time is up: the code may be waiting.
                            while (listen.isActive && !done && SystemClock.elapsedRealtime() < maxOf(end, began + 3000)) delay(250)
                            listen.cancel()
                        }
                        dropped = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A stream that had been running, or one tried while off screen, is reopened.
                        // Two quick failures in a row while on screen mean there is no internet.
                        val quick = SystemClock.elapsedRealtime() - began < 5000 && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        if (quick && ++misses >= 2) throw e
                        if (!quick) misses = 0
                        dropped = true
                    }
                    delay(400)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the kind of error: its text can carry the relay address of this pair.
                Log.w("OTPair", "asking for a code failed: ${e.javaClass.simpleName}")
                offline = true
            } finally {
                clock.cancel()
                job = null
            }
        }
    }

    // Unlocking once covers the next couple of minutes, so "Try again" doesn't ask twice.
    var unlocked by remember { mutableLongStateOf(0L) }
    fun askLocked() {
        if (!store.lock || SystemClock.elapsedRealtime() - unlocked < 120_000 && unlocked != 0L) return ask()
        context.unlockThen("Unlock to get the code") {
            unlocked = SystemClock.elapsedRealtime()
            ask()
        }
    }

    var forgetting by remember { mutableStateOf<Job?>(null) }
    fun copy(msg: Msg) {
        copyCode(context, msg.code)
        copied = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        // A minute is enough to paste it. After that the code comes off the clipboard.
        forgetting?.cancel()
        forgetting = scope.launch {
            delay(60_000)
            lifecycle.withResumed { }
            delay(600) // the clipboard can only be looked at once the window has focus
            forgetCode(context)
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2200)
            copied = false
        }
    }

    // Each phone has one job: the one bank codes arrive on shares them, the other asks for them.
    var sharer by remember(resumes) { mutableStateOf(store.sharing) }
    var settings by remember { mutableStateOf(false) }
    val turnOn = shareSwitch(store) { sharer = store.sharing }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(30.dp)
            Spacer(Modifier.width(10.dp))
            T("OTPair", Type.heading)
            Spacer(Modifier.weight(1f))
            CircleButton(Glyph.Settings, "Settings") { settings = true }
        }

        if (sharer) {
            Shared(store, resumes, Modifier.weight(1f))
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Stage(phase, left, total, codes, listening, copied, ::copy)
            }
        }
        AnimatedContent(
            phase to listening, Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "action",
        ) { (now, live) ->
            Row(Modifier.fillMaxWidth()) {
                when (now) {
                    Phase.Idle -> PrimaryButton("Get OTP", Modifier.weight(1f), onClick = ::askLocked)
                    Phase.Listening, Phase.Approving -> OutlineButton("Stop", Modifier.weight(1f)) { job?.cancel() }
                    Phase.Missed, Phase.Unanswered, Phase.Declined, Phase.Offline -> PrimaryButton("Try again", Modifier.weight(1f), onClick = ::askLocked)
                    Phase.Found -> {
                        if (live) OutlineButton("Stop", Modifier.weight(1f)) { job?.cancel() } else OutlineButton("Look again", Modifier.weight(1f), onClick = ::askLocked)
                        Spacer(Modifier.width(12.dp))
                        PrimaryButton(
                            if (copied) "Copied" else "Copy code", Modifier.weight(1.5f),
                            icon = if (copied) Glyph.Check else Glyph.Copy,
                        ) {
                            codes.firstOrNull()?.let(::copy)
                        }
                    }
                }
            }
        }
    }

    if (settings) {
        Sheet(onDismiss = { settings = false }) { close ->
            T("Settings", Type.title)
            Spacer(Modifier.height(4.dp))
            SettingToggle(
                Glyph.Message, "Share this phone’s codes",
                if (sharer) "On. This phone rings when your partner asks." else "Turn on if bank codes arrive on this phone.",
                sharer,
            ) { want ->
                if (want) {
                    close(turnOn)
                } else {
                    store.sharing = false
                    sharer = false
                }
            }
            Hairline()
            LockRow(store, resumes)
            if (sharer && !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
                Hairline()
                SettingRow(Glyph.Bolt, "Keep sharing reliable", "Let OTPair run in the background, or codes can stop coming through.", onClick = {
                    @Suppress("BatteryLife") // sideloaded by design; receiving texts in the background is the app's whole job
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }) {
                    Icon(Glyph.Forward, null, Modifier.size(20.dp), look.soft)
                }
            }
            Hairline()
            SettingRow(Glyph.Qr, "Pair number", "The same on both phones when they are paired only with each other.") {
                T(link.check.chunked(2).joinToString(" "), Type.heading)
            }
            Hairline()
            SettingRow(Glyph.Unlink, "Unpair this phone", "Stop sharing and getting codes here.", onClick = { close { confirming = true } }) {
                Icon(Glyph.Forward, null, Modifier.size(20.dp), look.soft)
            }
        }
    }

    if (confirming) {
        Sheet(onDismiss = { confirming = false }) { close ->
            T("Unpair this phone?", Type.title)
            Spacer(Modifier.height(10.dp))
            T(
                "It stops sharing and can no longer get codes. Unpair the other phone too, then pair again whenever you like.",
                Type.body, color = look.soft,
            )
            Spacer(Modifier.height(28.dp))
            DarkButton("Unpair", Modifier.fillMaxWidth()) { close(onUnpair) }
            Spacer(Modifier.height(4.dp))
            TextLink("Keep paired", Modifier.align(Alignment.CenterHorizontally)) { close {} }
        }
    }
}

// ───────────────────────── The stage ─────────────────────────

/** One card that is always about the code: waiting for it, looking for it, or showing it. */
@Composable
private fun Stage(phase: Phase, left: Int, total: Int, codes: List<Msg>, listening: Boolean, copied: Boolean, onCopy: (Msg) -> Unit) {
    val newest = codes.firstOrNull()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(look.surface)
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 320f))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        T(
            when (phase) {
                Phase.Idle -> "READY"
                Phase.Listening -> "LISTENING"
                Phase.Approving -> "WAITING FOR A YES"
                Phase.Declined -> "NOT SENT"
                Phase.Unanswered -> "NO ANSWER"
                Phase.Found -> newest?.sender?.ifBlank { null }?.uppercase() ?: "TEXT MESSAGE"
                Phase.Missed -> "NOTHING YET"
                Phase.Offline -> "OFFLINE"
            },
            Type.eyebrow, color = look.soft, maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        // Tapping the digits copies them too.
        Box(if (phase == Phase.Found && newest != null) Modifier.clickable(onClickLabel = "Copy code") { onCopy(newest) } else Modifier) {
            Slot(newest?.code, waving = phase == Phase.Listening || phase == Phase.Approving)
        }
        AnimatedVisibility(phase == Phase.Found && newest != null) {
            Tappable(
                { newest?.let(onCopy) }, Modifier.padding(top = 8.dp).height(44.dp), RoundedCornerShape(22.dp),
                color = look.bg, border = BorderStroke(1.dp, look.border),
            ) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (copied) Glyph.Check else Glyph.Copy, null, Modifier.size(18.dp), look.ink)
                    Spacer(Modifier.width(8.dp))
                    T(if (copied) "Copied" else "Copy", Type.smallStrong)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        AnimatedContent(phase, transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(100)) }, label = "words") { now ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val (title, body) = when (now) {
                    Phase.Idle -> "Need a code?" to "If one reached your partner’s phone in the last minute, or arrives in the next, they’re asked to send it to you."
                    Phase.Listening -> "Looking for your code" to "When it reaches your partner’s phone, they’re asked to send it."
                    Phase.Approving -> "Your code is waiting" to "It reached your partner’s phone. It shows up here as soon as they tap Send."
                    Phase.Declined -> "Your partner didn’t send it" to "They chose not to share this code."
                    Phase.Unanswered -> "No answer" to "Your partner didn’t tap Send in time. Try again when they can see their phone."
                    Phase.Found -> null to newest?.body.orEmpty()
                    Phase.Missed -> "No code arrived" to "Nothing reached your partner’s phone in that minute. Ask for the OTP to be sent again, then try once more."
                    Phase.Offline -> "Can’t connect" to "Check your internet, then try again."
                }
                if (title != null) {
                    T(title, Type.heading, align = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                }
                T(body, if (title == null) Type.small else Type.body, color = look.soft, align = TextAlign.Center, maxLines = 5)
            }
        }

        AnimatedVisibility(listening) { Countdown(left, total) }

        if (codes.size > 1) {
            Spacer(Modifier.height(24.dp))
            Hairline()
            codes.drop(1).forEach { msg ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Copy") { onCopy(msg) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(pretty(msg.code), Type.bodyStrong)
                    Spacer(Modifier.width(12.dp))
                    T(msg.sender, Type.small, Modifier.weight(1f), color = look.soft, maxLines = 1)
                    T("Copy", Type.smallStrong.copy(textDecoration = TextDecoration.Underline))
                }
            }
        }
    }
}

/** Six dots that become the digits. While listening, a wave runs through them. */
@Composable
private fun Slot(code: String?, waving: Boolean) {
    AnimatedContent(
        code, Modifier.fillMaxWidth().height(72.dp),
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
        contentAlignment = Alignment.Center, label = "slot",
    ) { shown ->
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            if (shown == null) WaveDots(waving) else Digits(shown)
        }
    }
}

@Composable
private fun WaveDots(waving: Boolean) {
    val t = if (waving) {
        rememberInfiniteTransition(label = "wave")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "time").value
    } else {
        -1f
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(6) { i ->
            if (i == 3) Spacer(Modifier.width(14.dp))
            val turn = (((t - i * 0.09f) % 1f) + 1f) % 1f
            val lift = if (t >= 0f && turn < 0.45f) sin(turn / 0.45f * PI).toFloat() else 0f
            Box(
                Modifier.padding(horizontal = 6.dp).size(12.dp)
                    .graphicsLayer { translationY = -9.dp.toPx() * lift }
                    .background(lerp(look.border, Coral, lift), CircleShape),
            )
        }
    }
}

@Composable
private fun Digits(code: String) {
    val shown = remember(code) { Animatable(0f) }
    LaunchedEffect(code) { shown.animateTo(1f, tween(700, easing = LinearOutSlowInEasing)) }
    val style = Type.display.copy(fontSize = if (code.length > 6) 44.sp else 54.sp, lineHeight = 62.sp, letterSpacing = 0.sp)
    Row(Modifier.clearAndSetSemantics { contentDescription = code.toList().joinToString(" ") }) {
        code.forEachIndexed { i, digit ->
            if (code.length == 6 && i == 3) Spacer(Modifier.width(14.dp))
            // Each digit rises in a beat after the one before it.
            T(digit.toString(), style, Modifier.graphicsLayer {
                val p = ((shown.value * (code.length + 3) - i) / 3f).coerceIn(0f, 1f)
                alpha = p
                translationY = (1f - p) * 16.dp.toPx()
            })
        }
    }
}

@Composable
private fun Countdown(left: Int, total: Int) {
    val fill = remember { Animatable(left / total.toFloat()) }
    LaunchedEffect(left, total) { fill.animateTo((left - 1).coerceAtLeast(0) / total.toFloat(), tween(1000, easing = LinearEasing)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).background(look.border, CircleShape)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill.value).background(Coral, CircleShape))
        }
        Spacer(Modifier.height(10.dp))
        T(if (left > 60) "%d:%02d left".format(left / 60, left % 60) else "$left seconds left", Type.small, color = look.soft)
    }
}

private const val CLIP = "OTPair code"

/** Takes the code off the clipboard, unless something else has been copied since. */
private fun forgetCode(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (Build.VERSION.SDK_INT >= 28 && clipboard.primaryClipDescription?.label?.toString() == CLIP) clipboard.clearPrimaryClip()
}

private fun copyCode(context: Context, code: String) {
    val clip = ClipData.newPlainText(CLIP, code)
    if (Build.VERSION.SDK_INT >= 33) {
        // Keeps the code out of the clipboard preview and keyboard suggestions.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

// ───────────────────────── This phone ─────────────────────────

@Composable
private fun SettingRow(icon: ImageVector, title: String, detail: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), look.ink)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            T(title, Type.body)
            T(detail, Type.small, color = look.soft)
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
private fun SettingToggle(icon: ImageVector, title: String, detail: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), look.ink)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            T(title, Type.body)
            T(detail, Type.small, color = look.soft)
        }
        Spacer(Modifier.width(16.dp))
        Toggle(on)
    }
}

/**
 * The sharing phone's whole screen: is sharing working, and if not, the one thing to fix.
 * Its real work happens when it rings ([ApprovalActivity]).
 */
@Composable
private fun Shared(store: Store, resumes: Int, modifier: Modifier) {
    val context = LocalContext.current
    var on by remember(resumes) { mutableStateOf(store.sharing) }
    var asks by remember { mutableIntStateOf(0) } // goes up each time a permission question closes
    val sms = remember(resumes, asks) { context.allows(Manifest.permission.RECEIVE_SMS) }
    val alerts = remember(resumes, asks) { Alerts.allowed(context) }
    val fullScreen = remember(resumes) { Alerts.fullScreen(context) }
    val last = remember(resumes) { store.lastShared }
    val turnOn = shareSwitch(store) {
        on = store.sharing
        asks++
    }
    val working = on && sms && alerts

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Box(Modifier.size(112.dp).background(look.surface, CircleShape), Alignment.Center) {
                        Icon(Glyph.Bell, null, Modifier.size(44.dp), look.ink)
                    }
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp, y = (-4).dp).size(26.dp)
                            .background(look.bg, CircleShape).padding(4.dp)
                            .background(if (working) look.good else look.faint, CircleShape),
                    )
                }
                Spacer(Modifier.height(28.dp))
                T(
                    when {
                        working -> "Sharing is on"
                        on -> "Sharing is paused"
                        else -> "Sharing is off"
                    },
                    Type.display, align = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                T(
                    when {
                        working -> "When your partner asks for a code, this phone rings. Nothing is sent until you tap Send."
                        on -> "Fix the one thing below and it starts again."
                        else -> "Codes that arrive here stay here. Your partner can’t ask for them."
                    },
                    Type.body, color = look.soft, align = TextAlign.Center,
                )
                if (working && last != null) {
                    Spacer(Modifier.height(12.dp))
                    T(
                        "Last code sent ${ago(last.first)}${last.second.ifBlank { null }?.let { ", from $it" }.orEmpty()}.",
                        Type.small, color = look.soft, align = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(28.dp))
                if (on && !sms) {
                    Callout(Glyph.Alert, "Android is blocking message access", "Open settings", { context.openAppSettings() }) {
                        T("Apps installed outside the Play Store need one extra step.", Type.small, color = look.soft)
                        Spacer(Modifier.height(14.dp))
                        listOf(
                            "Tap Open settings below.",
                            "Tap ⋮ at the top right, then Allow restricted settings.",
                            "Open Permissions, then SMS, and choose Allow.",
                            "Come back here.",
                        ).forEachIndexed { i, step ->
                            Row(Modifier.padding(bottom = 10.dp)) {
                                Box(Modifier.size(22.dp).background(look.bg, CircleShape).border(1.dp, look.border, CircleShape), Alignment.Center) {
                                    T("${i + 1}", Type.eyebrow.copy(letterSpacing = 0.sp))
                                }
                                Spacer(Modifier.width(12.dp))
                                T(step, Type.small)
                            }
                        }
                    }
                } else if (on && !alerts) {
                    Callout(Glyph.Bell, "Notifications are off", "Open settings", {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) {
                        T("This phone rings when your partner asks. It can’t do that, so no code can be shared.", Type.small, color = look.soft)
                    }
                } else if (working && !fullScreen) {
                    Callout(Glyph.Bell, "Let requests ring like a call", "Open settings", {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                    }) {
                        T(
                            "So you can’t miss your partner asking, allow full-screen notifications. A request then takes over the screen, even when the phone is locked.",
                            Type.small, color = look.soft,
                        )
                    }
                }
            }
        }
        Hairline()
        Row(
            Modifier.fillMaxWidth().toggleable(on, role = Role.Switch) { want ->
                if (want) {
                    turnOn()
                } else {
                    store.sharing = false
                    on = false
                }
            }.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("Share this phone’s codes", Type.bodyStrong, Modifier.weight(1f))
            Toggle(on)
        }
    }
}

private fun ago(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
}

/** Get OTP can ask for the fingerprint or screen lock first, so an unlocked phone left lying around can't fetch codes. */
@Composable
private fun LockRow(store: Store, resumes: Int) {
    val context = LocalContext.current
    val can = remember(resumes) { context.canLock() }
    var lock by remember { mutableStateOf(store.lock) }
    SettingToggle(
        Glyph.Finger, "Lock Get OTP",
        if (can) "Asks for your fingerprint or screen lock before fetching a code." else "Set a screen lock on this phone to use this.",
        lock && can, enabled = can,
    ) { want ->
        if (want) {
            store.lock = true
            lock = true
        } else {
            // Turning the lock off takes the same proof the lock asks for.
            context.unlockThen("Unlock to turn this off") {
                store.lock = false
                lock = false
            }
        }
    }
}

@Composable
private fun Callout(icon: ImageVector, title: String, action: String, onAction: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 20.dp).fillMaxWidth().background(look.surface, RoundedCornerShape(20.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), look.ink)
            Spacer(Modifier.width(10.dp))
            T(title, Type.bodyStrong)
        }
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(16.dp))
        DarkButton(action, Modifier.height(48.dp), onClick = onAction)
    }
}
