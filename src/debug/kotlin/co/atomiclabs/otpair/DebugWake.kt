package co.atomiclabs.otpair

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Debug builds only. Does what a Firebase wake does, for testing on an emulator:
 *   adb shell am broadcast -n co.atomiclabs.otpair/.DebugWake --es m "$(node tools/partner.mjs "$LINK" sealwant)"
 */
class DebugWake : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sealed = intent.getStringExtra("m") ?: return
        val done = goAsync()
        thread {
            try {
                Wake.ring(context.applicationContext, sealed)
            } finally {
                done.finish()
            }
        }
    }
}
