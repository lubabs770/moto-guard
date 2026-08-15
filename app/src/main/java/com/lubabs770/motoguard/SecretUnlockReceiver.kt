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
        // Constant-time compare over fixed-length SHA-256 digests, matching
        // PinStore.verify. Hashing first is what makes it truly constant-time:
        // MessageDigest.isEqual short-circuits when the two arrays differ in
        // length, so comparing the raw secrets would leak the secret's length via
        // timing. Both digests are 32 bytes, so no length/prefix info leaks. The
        // secret is long+random so timing isn't the weak link anyway — belt & braces.
        val a = sha256(given)
        val b = sha256(Config.ADB_SECRET)
        if (MessageDigest.isEqual(a, b)) {
            Policy.release(ctx)
        }
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
}
