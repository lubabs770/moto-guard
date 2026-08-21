package com.lubabs770.motoguard

import android.content.Context
import java.security.SecureRandom

/**
 * The confirmation dance, and the reason this lock works at all.
 *
 * A command arriving from the keyholder's number proves nothing — caller ID is
 * forgeable by anyone with an SMS gateway, which is everyone. So a command does
 * not execute. It raises a *challenge*: six digits, minted here and texted to
 * the keyholder's number **on record**, never to whoever sent the command. Only
 * a sender who can actually receive mail at that number sees them, and echoing
 * them back is what executes the verb.
 *
 * That asymmetry is the whole design. Inbound texts on this device are trivially
 * readable — `content query --uri content://sms/inbox`, `termux-sms-list` — but
 * these digits never arrive here. They go out, to a handset the operator does
 * not hold, and `SmsManager` sends are not recorded in the SMS provider. The
 * only copy on this device is in app-private prefs, which needs root to read.
 *
 * One challenge is live at a time and a new command supersedes it. Any echo
 * attempt burns it, right or wrong, so guessing gets exactly one try at a
 * million.
 */
object Challenge {

    private const val PREFS = "challenge"
    private const val K_DIGITS = "digits"
    private const val K_VERB = "verb"
    private const val K_ARG = "arg"
    private const val K_AT = "at"

    // Issue-rate window: blunts someone using the guard to flood the keyholder.
    private const val K_WIN_AT = "window_at"
    private const val K_WIN_N = "window_n"

    // Unconfirmed-challenge window: how we notice a spoofer probing.
    private const val K_UNCONF_AT = "unconfirmed_at"
    private const val K_UNCONF_N = "unconfirmed_n"
    private const val K_WARNED = "warned"

    const val TTL_MS = 5 * 60 * 1000L
    private const val ISSUE_WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_PER_WINDOW = 3
    private const val UNCONF_WINDOW_MS = 60 * 60 * 1000L
    private const val UNCONF_WARN_AT = 3

    private val rng = SecureRandom()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Six digits, zero-padded, uniform. */
    private fun mint(): String = "%06d".format(rng.nextInt(1_000_000))

    private fun constantEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /** A live, unexpired challenge, or null. */
    fun pendingVerb(ctx: Context): String? {
        val p = prefs(ctx)
        val at = p.getLong(K_AT, 0L)
        if (at == 0L || System.currentTimeMillis() - at > TTL_MS) return null
        return p.getString(K_VERB, null)
    }

    /**
     * True when the guard should stay silent rather than raise another
     * challenge. Without this, anyone who can forge the keyholder's number could
     * use the device as a megaphone pointed at their real handset.
     */
    fun throttled(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val start = p.getLong(K_WIN_AT, 0L)
        val n = if (now - start > ISSUE_WINDOW_MS) 0 else p.getInt(K_WIN_N, 0)
        return n >= MAX_PER_WINDOW
    }

    /**
     * Mint a challenge for [verb]/[arg], replacing any live one. Returns the six
     * digits — the caller must send them ONLY to the number on record.
     */
    fun issue(ctx: Context, verb: String, arg: String): String {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val start = p.getLong(K_WIN_AT, 0L)
        val fresh = now - start > ISSUE_WINDOW_MS
        val digits = mint()

        p.edit()
            .putString(K_DIGITS, digits)
            .putString(K_VERB, verb)
            .putString(K_ARG, arg)
            .putLong(K_AT, now)
            .putLong(K_WIN_AT, if (fresh) now else start)
            .putInt(K_WIN_N, if (fresh) 1 else p.getInt(K_WIN_N, 0) + 1)
            .apply()
        return digits
    }

    /**
     * Spend the challenge. Returns verb to arg on a correct echo, null otherwise.
     * The challenge is cleared either way — one attempt, no grinding.
     */
    fun consume(ctx: Context, digits: String): Pair<String, String>? {
        val p = prefs(ctx)
        val at = p.getLong(K_AT, 0L)
        val want = p.getString(K_DIGITS, null)
        val verb = p.getString(K_VERB, null)
        val arg = p.getString(K_ARG, "") ?: ""
        clear(ctx)

        if (want == null || verb == null) return null
        if (at == 0L || System.currentTimeMillis() - at > TTL_MS) return null
        if (!constantEquals(digits, want)) return null
        return verb to arg
    }

    /**
     * Clear an outstanding challenge nobody ever answered, and say whether that
     * is now worth warning about.
     *
     * This is the only way a spoofer is detected. Someone forging the
     * keyholder's number can raise a challenge but never confirm it — there is
     * no wrong echo to catch, just silence — so the signal is an unanswered
     * challenge found sitting there when the next one is raised.
     *
     * The caller must act on the returned flag; the warning is marked as spent
     * either way, so swallowing it loses it.
     */
    fun reapUnanswered(ctx: Context): Boolean {
        if (prefs(ctx).getLong(K_AT, 0L) == 0L) return false
        clear(ctx)
        return noteUnconfirmed(ctx)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(K_DIGITS).remove(K_VERB).remove(K_ARG).remove(K_AT)
            .apply()
    }

    /**
     * Record a challenge that went unanswered or was answered wrongly, and say
     * whether this is the moment to warn the keyholder. Warns once per hour, so
     * a probe produces a single "that wasn't you" rather than a flood of its own.
     */
    fun noteUnconfirmed(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val start = p.getLong(K_UNCONF_AT, 0L)
        val fresh = now - start > UNCONF_WINDOW_MS
        val n = (if (fresh) 0 else p.getInt(K_UNCONF_N, 0)) + 1
        val warned = !fresh && p.getBoolean(K_WARNED, false)
        val shouldWarn = n >= UNCONF_WARN_AT && !warned

        p.edit()
            .putLong(K_UNCONF_AT, if (fresh) now else start)
            .putInt(K_UNCONF_N, n)
            .putBoolean(K_WARNED, warned || shouldWarn)
            .apply()
        return shouldWarn
    }
}
