package com.lubabs770.motoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Invisible adb escape hatch — the second transport for ControlApi. Exported (so
 * `am broadcast` from adb reaches it) but useless without the secret, which
 * lives nowhere on the visible UI.
 *
 * Bare form, unchanged, still means "release":
 *
 *   adb shell am broadcast -a com.lubabs770.motoguard.UNLOCK \
 *     --es secret 'YOUR_ADB_SECRET' com.lubabs770.motoguard/.SecretUnlockReceiver
 *
 * With a `cmd` extra it runs any ControlApi command over adb, same grammar as
 * SMS minus the prefix and secret word:
 *
 *   ... --es secret 'YOUR_ADB_SECRET' --es cmd 'open'
 *   ... --es secret 'YOUR_ADB_SECRET' --es cmd 'pin 4821'
 *
 * The reply comes back through setResultData(), so adb sees it inline.
 */
class SecretUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val given = intent.getStringExtra("secret") ?: return
        // Constant-time-ish compare; secret is long+random so timing isn't the weak link.
        if (given != Config.ADB_SECRET) return

        val cmd = intent.getStringExtra("cmd")
        if (cmd.isNullOrBlank()) {
            // Legacy behaviour: a bare authenticated broadcast means release.
            Policy.release(ctx)
            return
        }
        // adb is already a trusted channel, so it skips the SMS secret and reuses
        // the command layer directly — one implementation, two front doors.
        val result = ControlApi.handle(ctx, "${ControlApi.PREFIX} ${Config.SMS_SECRET} $cmd", trusted = true)
        if (isOrderedBroadcast) resultData = result.reply
    }
}
