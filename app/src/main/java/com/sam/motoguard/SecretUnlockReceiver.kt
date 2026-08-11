package com.sam.motoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Invisible adb escape hatch. Fires Policy.release() only if the broadcast
 * carries the shared secret. Exported (so `am broadcast` from adb reaches it)
 * but useless without the secret — which lives nowhere on the visible UI.
 *
 *   adb shell am broadcast -a com.sam.motoguard.UNLOCK \
 *     --es secret 'YOUR_ADB_SECRET' com.sam.motoguard/.SecretUnlockReceiver
 */
class SecretUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val given = intent.getStringExtra("secret") ?: return
        // Constant-time-ish compare; secret is long+random so timing isn't the weak link.
        if (given == Config.ADB_SECRET) {
            Policy.release(ctx)
        }
    }
}
