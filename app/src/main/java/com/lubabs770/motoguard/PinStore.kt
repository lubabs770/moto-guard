package com.lubabs770.motoguard

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The at-the-glass credential. It belongs to the KEYHOLDER, not to whoever is
 * carrying the device — it is set remotely (`MG <code> pin 1234`) or by the
 * keyholder in person, and it opens the on-screen panel.
 *
 * There is deliberately NO default PIN. A fresh install has none and the panel
 * is simply unreachable until the keyholder sets one; shipping a known default
 * would have handed the operator a free key on every re-flash.
 *
 * Stored salted and hashed in app-private storage, which on a non-rooted device
 * needs root even to read. The salt matters more than usual here: the PIN space
 * is four to eight digits, small enough to pre-compute without one.
 *
 * Failures escalate and the lockout is PERSISTED. Whoever is at the glass has
 * unlimited time and can power-cycle at will, so an in-memory counter would be
 * worth nothing.
 */
object PinStore {
    private const val PREFS = "guard"
    private const val K_HASH = "pin_hash"
    private const val K_SALT = "pin_salt"
    private const val K_FAILS = "pin_fails"
    private const val K_UNTIL = "pin_locked_until"

    private const val FREE_TRIES = 4
    private const val BASE_LOCK_MS = 60_000L
    private const val MAX_LOCK_MS = 60L * 60 * 1000

    private val rng = SecureRandom()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun isSet(ctx: Context): Boolean = prefs(ctx).getString(K_HASH, null) != null

    fun setPin(ctx: Context, newPin: String) {
        val salt = ByteArray(16).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit()
            .putString(K_SALT, salt)
            .putString(K_HASH, hash(salt, newPin))
            .putInt(K_FAILS, 0)
            .putLong(K_UNTIL, 0L)
            .apply()
    }

    /**
     * Forget the PIN entirely, along with any lockout. The PIN belongs to the
     * keyholder, so it ends when their role does — leaving it behind would gate
     * the next arrangement's panel on a secret the previous keyholder chose.
     */
    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(K_HASH).remove(K_SALT).remove(K_FAILS).remove(K_UNTIL)
            .apply()
    }

    /** Milliseconds still to wait, or 0 if the wall is open. */
    fun lockedForMs(ctx: Context): Long =
        (prefs(ctx).getLong(K_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Verify and record the attempt. Returns false while locked out without
     * even looking at [pin], so waiting out the timer is the only way forward.
     */
    fun verify(ctx: Context, pin: String): Boolean {
        if (lockedForMs(ctx) > 0) return false
        val p = prefs(ctx)
        val salt = p.getString(K_SALT, null) ?: return false
        val want = p.getString(K_HASH, null) ?: return false

        if (hash(salt, pin) == want) {
            p.edit().putInt(K_FAILS, 0).putLong(K_UNTIL, 0L).apply()
            return true
        }

        val fails = p.getInt(K_FAILS, 0) + 1
        val e = p.edit().putInt(K_FAILS, fails)
        if (fails > FREE_TRIES) {
            // 1 min, 2, 4, 8 ... capped at an hour.
            val shift = (fails - FREE_TRIES - 1).coerceAtMost(6)
            val wait = (BASE_LOCK_MS shl shift).coerceAtMost(MAX_LOCK_MS)
            e.putLong(K_UNTIL, System.currentTimeMillis() + wait)
        }
        e.apply()
        return false
    }
}
