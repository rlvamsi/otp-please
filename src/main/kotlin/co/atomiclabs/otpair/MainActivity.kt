package co.atomiclabs.otpair

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepOutOfScreenshots()
        val store = Store(this)
        setContent { OtpairTheme { App(store, resumes) } }
    }

    // Permissions change while the user is away in system settings.
    override fun onResume() {
        super.onResume()
        resumes++
        Wake.follow(Store(this))
    }
}

/**
 * The pairing code and every OTP stay out of screenshots, screen recordings, screen sharing and
 * the recent-apps preview. Left off in debug builds so the README pictures can be taken.
 */
internal fun Activity.keepOutOfScreenshots() {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

/**
 * Not paired: intro, then show a code or scan one. Just paired: one setup question. Paired: home.
 * Screens live in Onboarding.kt, Pairing.kt and Home.kt; the look they share is in Theme.kt.
 */
@Composable
private fun App(store: Store, resumes: Int) {
    var link by remember { mutableStateOf(store.link) }
    var route by rememberSaveable { mutableStateOf("intro") }
    val paired = { it: Link ->
        store.link = it
        link = it
        route = "setup"
    }
    val screen = when {
        link == null -> if (route == "show" || route == "scan") route else "intro"
        route == "setup" -> "setup"
        else -> "home"
    }

    Surface(Modifier.fillMaxSize(), color = look.bg) {
        AnimatedContent(
            screen,
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 80)) + slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it / 28 }) togetherWith
                    fadeOut(tween(140))
            },
            label = "screen",
        ) { now ->
            when (now) {
                "home" -> {
                    val current = remember { link } // still here while this screen fades out after unpairing
                    if (current != null) {
                        Home(store, current, resumes, onUnpair = {
                            store.link = null
                            store.sharing = false
                            store.lastShared = null
                            link = null
                            route = "intro"
                        })
                    }
                }
                "setup" -> {
                    val current = remember { link }
                    if (current != null) PairedSetup(store, current) { route = "home" }
                }
                "show" -> ShowCode(store, paired, onBack = { route = "intro" })
                "scan" -> Scanner(store, resumes, paired, onBack = { route = "intro" })
                else -> Onboarding(
                    skipIntro = store.seenIntro,
                    onShow = { store.seenIntro = true; route = "show" },
                    onScan = { store.seenIntro = true; route = "scan" },
                )
            }
        }
    }
}
