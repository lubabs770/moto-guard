package com.lubabs770.motoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.security.MessageDigest

/**
 * Invisible adb escape hatch. Fires Policy.release() only if the broadcast
 * carries the shared secret. Exported (so `am broadcast` from adb reaches it)
 * but useless without the secret — which lives nowhere on the visible UI.
 *
 *   adb shell am broadcast -a com.lubabs770.motoguard.UNLOCK \
 *     --es secret 'YOUR_ADB_SECRET' com.lubabs770.motoguard/.SecretUnlockReceiver
 */
class SecretUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val given = intent.getStringExtra("secret") ?: return
        // Constant-time compare: MessageDigest.isEqual won't short-circuit on the
        // first mismatched byte, so it leaks no length/prefix info via timing. The
        // secret is long+random so timing isn't the weak link anyway — belt & braces.
        val a = given.toByteArray(Charsets.UTF_8)
        val b = Config.ADB_SECRET.toByteArray(Charsets.UTF_8)
        if (MessageDigest.isEqual(a, b)) {
            Policy.release(ctx)
        }
    }
}
