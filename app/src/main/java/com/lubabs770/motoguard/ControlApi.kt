package com.lubabs770.motoguard

import android.content.Context
import java.security.MessageDigest

/**
 * The single command surface — "the API". Every transport (SMS today, the adb
 * broadcast, anything added later) reduces its input to one line of text and
 * hands it here. Auth, rate limiting and replay defence live in this file and
 * nowhere else, so a new transport can never accidentally ship a weaker gate.
 *
 * Wire format, one line:
 *
 *     MG <secret> <command> [arg]
 *
 * Commands:
 *   status          - report owner / kiosk / PIN state
 *   open            - stand down: leave kiosk, restore launcher + Settings,
 *                     KEEP Device Owner. Fully reversible with `lock`.
 *   lock            - re-arm the kiosk
 *   pin <4-8 digits>- reset the PIN (for when it's forgotten)
 *   release CONFIRM - full un-provision, drops Device Owner. One-way: getting
 *                     it back needs a re-provision, which needs zero accounts
 *                     on the device. The literal word CONFIRM is required.
 *
 * A line that doesn't start with the prefix, or carries the wrong secret, is
 * dropped in total silence — no reply, no log — so probing tells an attacker
 * nothing about whether this number is a live target.
 */
object ControlApi {

    /** Cheap first token, so ordinary texts are discarded without touching crypto. */
    const val PREFIX = "MG"

    private const val PREFS = "control"
    private const val KEY_SEEN = "seen"           // recent body hashes (replay defence)
    private const val KEY_WINDOW = "window_start" // rate-limit window, epoch ms
    private const val KEY_COUNT = "window_count"

    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_PER_WINDOW = 5
    private const val SEEN_KEEP = 20
    private const val SEEN_TTL_MS = 30 * 60 * 1000L

    /** [reply] is empty when the caller should stay silent. */
    data class Result(val ok: Boolean, val reply: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Compare on the last 10 digits so formatting differences don't matter. */
    private fun normalize(num: String): String = num.filter { it.isDigit() }.takeLast(10)

    /** First filter only — see the note on Config.SMS_ALLOWED_SENDERS. */
    fun senderAllowed(from: String?): Boolean {
        val want = normalize(from ?: return false)
        if (want.length < 10) return false
        return Config.SMS_ALLOWED_SENDERS.split(",")
            .map { normalize(it.trim()) }
            .any { it.length == 10 && it == want }
    }

    /** Length-independent compare. The secret is long and random, but free is free. */
    private fun secretOk(given: String): Boolean {
        val want = Config.SMS_SECRET
        var diff = given.length xor want.length
        for (i in given.indices) diff = diff or (given[i].code xor want[i % want.length].code)
        return diff == 0
    }

    /**
     * Reject a body we executed in the last SEEN_TTL_MS. Stops a captured message
     * being replayed, and stops a carrier redelivery firing `release` twice —
     * while still letting the same command be sent again later, which matters
     * because `MG <secret> status` is a thing people type repeatedly.
     */
    private fun replayed(ctx: Context, body: String): Boolean {
        val now = System.currentTimeMillis()
        val h = sha256(body)
        val live = prefs(ctx).getString(KEY_SEEN, "")!!
            .split(",")
            .filter { it.isNotEmpty() }
            .mapNotNull { e ->
                val at = e.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
                if (now - at > SEEN_TTL_MS) null else e.substringBefore(':') to at
            }
        if (live.any { it.first == h }) return true
        prefs(ctx).edit()
            .putString(
                KEY_SEEN,
                (live + (h to now)).takeLast(SEEN_KEEP).joinToString(",") { "${it.first}:${it.second}" }
            )
            .apply()
        return false
    }

    private fun rateLimited(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        var start = p.getLong(KEY_WINDOW, 0L)
        var count = p.getInt(KEY_COUNT, 0)
        if (now - start > WINDOW_MS) { start = now; count = 0 }
        count++
        p.edit().putLong(KEY_WINDOW, start).putInt(KEY_COUNT, count).apply()
        return count > MAX_PER_WINDOW
    }

    /**
     * [trusted] transports (adb, which already has full control of the device)
     * skip replay defence and rate limiting — those exist to blunt an attacker
     * who can only inject text messages, and they'd only get in adb's way.
     */
    fun handle(ctx: Context, body: String, trusted: Boolean = false): Result {
        val words = body.trim().split(Regex("\\s+"))
        val silent = Result(false, "")

        if (words.size < 3) return silent
        if (!words[0].equals(PREFIX, ignoreCase = true)) return silent
        if (!secretOk(words[1])) return silent

        // Authenticated from here on, so replies are safe to send.
        if (!trusted && replayed(ctx, body)) return Result(false, "moto-guard: duplicate ignored")
        if (!trusted && rateLimited(ctx)) return Result(false, "moto-guard: rate limited, wait 10 min")

        val arg = words.getOrNull(3) ?: ""
        return when (words[2].lowercase()) {
            "status" -> Result(true, status(ctx))

            "open" -> {
                if (!Policy.isOwner(ctx)) return Result(false, "moto-guard: not device owner, nothing to open")
                Policy.standDown(ctx)
                Result(true, "moto-guard: kiosk OFF, device owner kept. Send LOCK to re-arm.")
            }

            "lock" -> {
                if (!Policy.isOwner(ctx)) return Result(false, "moto-guard: not device owner, cannot lock")
                Policy.rearm(ctx)
                Result(true, "moto-guard: kiosk re-armed.")
            }

            "pin" -> {
                if (!arg.matches(Regex("\\d{4,8}"))) {
                    Result(false, "moto-guard: pin needs 4-8 digits")
                } else {
                    PinStore.setPin(ctx, arg)
                    Result(true, "moto-guard: PIN reset. Enter it on the guard screen.")
                }
            }

            "release" -> {
                if (arg != "CONFIRM") {
                    Result(false, "moto-guard: release needs the word CONFIRM. This drops Device Owner permanently.")
                } else {
                    Policy.release(ctx)
                    Result(true, "moto-guard: RELEASED. Device unmanaged. Re-provision needs adb + zero accounts.")
                }
            }

            else -> Result(false, "moto-guard: unknown command. status|open|lock|pin|release")
        }
    }

    private fun status(ctx: Context): String {
        val owner = if (Policy.isOwner(ctx)) "yes" else "no"
        val kiosk = if (Policy.isStoodDown(ctx)) "stood down" else "armed"
        val pin = if (PinStore.isDefault(ctx)) "default 0000" else "set"
        return "moto-guard: owner=$owner kiosk=$kiosk pin=$pin"
    }
}
