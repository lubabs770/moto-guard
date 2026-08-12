package com.lubabs770.motoguard

import android.content.Context
import java.security.MessageDigest

/**
 * Runtime PIN, stored (hashed) in app-private prefs. Defaults to "0000" on a
 * fresh install; change it in-app behind the current PIN. Not a build-time
 * secret — it's device state, so re-flashing the APK never resets it.
 *
 * app-private storage means an attacker needs root to even read the hash; the
 * PIN space is small so hashing is just to avoid storing it in cleartext.
 */
object PinStore {
    private const val PREFS = "guard"
    private const val KEY = "pin_hash"
    private const val DEFAULT_PIN = "0000"

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True while the PIN is still the factory 0000 (nothing set yet). */
    fun isDefault(ctx: Context): Boolean = prefs(ctx).getString(KEY, null) == null

    private fun currentHash(ctx: Context): String =
        prefs(ctx).getString(KEY, null) ?: sha256(DEFAULT_PIN)

    fun verify(ctx: Context, pin: String): Boolean = sha256(pin) == currentHash(ctx)

    fun setPin(ctx: Context, newPin: String) {
        prefs(ctx).edit().putString(KEY, sha256(newPin)).apply()
    }
}
