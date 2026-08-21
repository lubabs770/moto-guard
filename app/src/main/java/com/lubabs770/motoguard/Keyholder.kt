package com.lubabs770.motoguard

import android.content.Context
import java.security.SecureRandom

/**
 * Who holds the key — and it is deliberately not the person holding the phone.
 *
 * The guard is an accountability lock: the operator carries the device, a
 * remote *keyholder* controls it by SMS.
 *
 * There is no code here, and that is the point. An earlier design stored a
 * keyholder-chosen secret; it was removed once it became clear the operator can
 * read every inbound text on this device anyway (`content query --uri
 * content://sms/inbox`, `termux-sms-list`), so any code the keyholder ever typed
 * was harvested on first use. The role is now a phone number, and proof of
 * holding that number is a challenge sent to it — see Challenge.
 *
 * Enrollment is trust-on-first-use, once:
 *
 *   1. A fresh install has no keyholder and prints an enrollment token on the
 *      guard screen. Anyone can read it off the glass; that's the point, it has
 *      to be handed to the keyholder somehow.
 *   2. The keyholder texts `MG <token> claim <their-code>`. Their number and
 *      code hash are recorded, the token is destroyed, enrollment closes.
 *   3. Enrollment can never re-open from the glass. Only the current keyholder
 *      can start a handover to a new number.
 *
 * HONEST LIMIT: this binds the operator, it does not defeat them. Whoever holds
 * adb and the app signing key can flash a build with the lock removed, and
 * anyone with the device can wipe it in recovery. What this guarantees is that
 * getting out is a deliberate, total, visible act — not a quiet one.
 */
object Keyholder {

    private const val PREFS = "keyholder"
    private const val K_NUMBER = "number"
    private const val K_OWNER = "owner_number"
    private const val K_ENROLLED = "enrolled"
    private const val K_TOKEN = "enroll_token"
    private const val K_PEND_NUMBER = "pending_number"
    private const val K_PEND_TOKEN = "pending_token"
    private const val K_PEND_AT = "pending_at"

    /** A handover invitation goes stale after a day. */
    const val PENDING_TTL_MS = 24L * 60 * 60 * 1000

    /**
     * Ambiguity-free alphabet — no O/0, I/1/L, S/5, Z/2. The token gets read off
     * a phone screen and spoken down a phone line; every removed lookalike is a
     * failed enrollment that doesn't happen.
     */
    private const val ALPHABET = "ABCDEFGHJKMNPQRTUVWXY346789"
    private const val TOKEN_LEN = 8

    private val rng = SecureRandom()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newToken(): String =
        (1..TOKEN_LEN).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")

    /** Compare on the last 10 digits, so +1 732-664-2170 and 7326642170 match. */
    fun normalize(num: String?): String =
        (num ?: "").filter { it.isDigit() }.takeLast(10)

    /** Length-independent compare. Cheap, so no reason not to. */
    private fun constantEquals(a: String, b: String): Boolean {
        if (b.isEmpty()) return a.isEmpty()
        var diff = a.length xor b.length
        for (i in a.indices) diff = diff or (a[i].code xor b[i % b.length].code)
        return diff == 0
    }

    // ---- state ----------------------------------------------------------

    fun isEnrolled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENROLLED, false)

    fun number(ctx: Context): String? = prefs(ctx).getString(K_NUMBER, null)

    /** For the public screen: enough to recognise, not enough to dial. */
    fun maskedNumber(ctx: Context): String {
        val n = normalize(number(ctx))
        return if (n.length < 10) "unknown" else "${n.take(3)} " + "***" + " ${n.takeLast(4)}"
    }

    /**
     * The one-time enrollment token, minted on first read and shown on the glass
     * until someone claims the role. Null once enrolled — there is no way back
     * to this state from the device.
     */
    fun enrollToken(ctx: Context): String? {
        if (isEnrolled(ctx)) return null
        val p = prefs(ctx)
        p.getString(K_TOKEN, null)?.let { return it }
        val t = newToken()
        p.edit().putString(K_TOKEN, t).apply()
        return t
    }

    fun isSender(ctx: Context, from: String?): Boolean {
        val want = normalize(from)
        return want.length == 10 && want == normalize(number(ctx))
    }

    /**
     * Where the operator's receipts go. A notification sink and nothing else —
     * it authorizes nothing, so it is safe to let the operator set it at the
     * glass. Pointing it at nothing only silences their own copies, and it can
     * never become the keyholder; that takes a handover.
     */
    fun ownerNumber(ctx: Context): String? = prefs(ctx).getString(K_OWNER, null)

    fun setOwnerNumber(ctx: Context, num: String?) {
        val e = prefs(ctx).edit()
        if (num.isNullOrBlank()) e.remove(K_OWNER) else e.putString(K_OWNER, num)
        e.apply()
    }

    /** Claim the role. Closes enrollment and clears any pending handover. */
    fun enroll(ctx: Context, from: String) {
        prefs(ctx).edit()
            .putString(K_NUMBER, from)
            .putBoolean(K_ENROLLED, true)
            .remove(K_TOKEN)
            .remove(K_PEND_NUMBER)
            .remove(K_PEND_TOKEN)
            .remove(K_PEND_AT)
            .apply()
    }

    /**
     * End the arrangement. Wipes the role, the pending handover and the
     * enrollment token, so the next provisioning starts from nothing and mints a
     * FRESH token — the old one must not be reusable by whoever saw it on the
     * glass last time.
     *
     * Called from Policy.release() rather than from its callers, so no future
     * release path can forget it.
     */
    fun reset(ctx: Context) {
        prefs(ctx).edit()
            .remove(K_NUMBER)
            .remove(K_OWNER)
            .remove(K_ENROLLED)
            .remove(K_TOKEN)
            .remove(K_PEND_NUMBER)
            .remove(K_PEND_TOKEN)
            .remove(K_PEND_AT)
            .apply()
    }

    // ---- handover -------------------------------------------------------

    /** Mint an invitation for [toNumber] and return the token to text them. */
    fun startHandover(ctx: Context, toNumber: String): String {
        val t = newToken()
        prefs(ctx).edit()
            .putString(K_PEND_NUMBER, toNumber)
            .putString(K_PEND_TOKEN, t)
            .putLong(K_PEND_AT, System.currentTimeMillis())
            .apply()
        return t
    }

    fun pendingNumber(ctx: Context): String? {
        val p = prefs(ctx)
        val at = p.getLong(K_PEND_AT, 0L)
        if (at == 0L || System.currentTimeMillis() - at > PENDING_TTL_MS) return null
        return p.getString(K_PEND_NUMBER, null)
    }

    fun cancelHandover(ctx: Context) {
        prefs(ctx).edit().remove(K_PEND_NUMBER).remove(K_PEND_TOKEN).remove(K_PEND_AT).apply()
    }

    /** True if [from] + [token] match a live invitation. */
    fun pendingMatches(ctx: Context, from: String?, token: String): Boolean {
        val want = pendingNumber(ctx) ?: return false
        if (normalize(from) != normalize(want)) return false
        val t = prefs(ctx).getString(K_PEND_TOKEN, null) ?: return false
        return constantEquals(token, t)
    }

    /** True if [token] matches the initial, pre-enrollment token. */
    fun enrollTokenMatches(ctx: Context, token: String): Boolean {
        if (isEnrolled(ctx)) return false
        val t = prefs(ctx).getString(K_TOKEN, null) ?: return false
        return constantEquals(token, t)
    }
}
