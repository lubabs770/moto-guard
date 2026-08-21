package com.lubabs770.motoguard

import android.content.Context

/**
 * The keyholder's command surface. Everything the role can do, it does from
 * here — there is no second, quieter path. The old adb escape hatch is gone on
 * purpose: an escape only the operator could reach would have made the whole
 * arrangement decorative.
 *
 * Wire format, one line:
 *
 *     MG <credential> <command> [arg]
 *
 * <credential> is the enrollment token for `claim`, and the keyholder's own code
 * for everything else.
 *
 *   claim <code>            - take the keyholder role (see Keyholder)
 *   help                    - list the commands
 *   status                  - owner / kiosk / PIN / keyholder state
 *   open                    - stand down: leave kiosk, keep Device Owner
 *   lock                    - re-arm the kiosk
 *   pin <4-8 digits>        - set the at-the-glass PIN
 *   code <new>              - rotate the SMS code
 *   handover <number>       - invite a new keyholder; `handover cancel` aborts
 *   release CONFIRM         - un-provision entirely. One way.
 *
 * Anything that isn't a well-formed, authenticated command is dropped in total
 * silence — no reply, no error. A stranger texting this device cannot even
 * establish that it is listening.
 */
object ControlApi {

    /** Cheap first token, so ordinary texts die before any hashing happens. */
    const val PREFIX = "MG"

    private const val PREFS = "control"
    private const val KEY_SEEN = "seen"
    private const val KEY_WINDOW = "window_start"
    private const val KEY_COUNT = "window_count"

    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_PER_WINDOW = 6
    private const val SEEN_KEEP = 20
    private const val SEEN_TTL_MS = 30 * 60 * 1000L

    private val CODE_RE = Regex("[A-Za-z0-9]{6,32}")
    private val PIN_RE = Regex("\\d{4,8}")

    /**
     * [reply] goes back to the sender; empty means stay silent. [notify] is an
     * optional second message to a different number — used when a handover has
     * to reach both the outgoing and incoming keyholder.
     */
    data class Result(
        val ok: Boolean,
        val reply: String,
        val notify: Pair<String, String>? = null
    )

    private val SILENT = Result(false, "")

    private const val HELP =
        "moto-guard: send MG CODE then one of: help / status / open / lock / " +
            "pin 1234 / code NEWCODE / handover +15551234567 / release CONFIRM. " +
            "open = kiosk off, device owner kept. lock = re-arm."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Reject a body already executed in the last SEEN_TTL_MS. Blocks a captured
     * message being replayed and stops a carrier redelivery firing `release`
     * twice, while still letting the same command be sent again later — `status`
     * is a thing people send repeatedly.
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
                (live + (h to now)).takeLast(SEEN_KEEP)
                    .joinToString(",") { "${it.first}:${it.second}" }
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

    fun handle(ctx: Context, from: String?, body: String): Result {
        val words = body.trim().split(Regex("\\s+"))
        if (words.size < 3) return SILENT
        if (!words[0].equals(PREFIX, ignoreCase = true)) return SILENT

        val cred = words[1]
        val cmd = words[2].lowercase()
        val arg = words.getOrNull(3) ?: ""

        // `claim` is the only command reachable by someone who is not yet the
        // keyholder — it is how one becomes the keyholder.
        if (cmd == "claim") return claim(ctx, from, cred, arg)

        // Everything else: right number AND right code. The number alone proves
        // nothing (caller ID is forgeable) and the code alone proves nothing
        // (it may have been read off a screen); both together is the gate.
        if (!Keyholder.isEnrolled(ctx)) return SILENT
        if (!Keyholder.isSender(ctx, from)) return SILENT
        if (!Keyholder.verifyCode(ctx, cred)) return SILENT

        if (replayed(ctx, body)) return Result(false, "moto-guard: duplicate ignored")
        if (rateLimited(ctx)) return Result(false, "moto-guard: rate limited, wait 10 min")

        return when (cmd) {
            "help" -> Result(true, HELP)

            "status" -> Result(true, status(ctx))

            "open" -> {
                if (!Policy.isOwner(ctx)) Result(false, "moto-guard: not device owner, nothing to open")
                else {
                    Policy.standDown(ctx)
                    Result(true, "moto-guard: kiosk OFF, device owner kept. Send LOCK to re-arm.")
                }
            }

            "lock" -> {
                if (!Policy.isOwner(ctx)) Result(false, "moto-guard: not device owner, cannot lock")
                else {
                    Policy.rearm(ctx)
                    Result(true, "moto-guard: kiosk re-armed.")
                }
            }

            "pin" -> {
                if (!PIN_RE.matches(arg)) Result(false, "moto-guard: pin needs 4 to 8 digits")
                else {
                    PinStore.setPin(ctx, arg)
                    Result(true, "moto-guard: PIN set. It opens the on-screen panel.")
                }
            }

            "code" -> {
                if (!CODE_RE.matches(arg)) {
                    Result(false, "moto-guard: code needs 6 to 32 letters or digits, no punctuation")
                } else {
                    Keyholder.setCode(ctx, arg)
                    Result(true, "moto-guard: code rotated. The old one is dead.")
                }
            }

            "handover" -> handover(ctx, arg)

            "release" -> {
                if (arg != "CONFIRM") {
                    Result(false, "moto-guard: release needs the word CONFIRM. It drops device owner for good.")
                } else {
                    Policy.release(ctx)
                    Result(true, "moto-guard: RELEASED. Device unmanaged. Re-provisioning needs adb and zero accounts.")
                }
            }

            else -> Result(false, HELP)
        }
    }

    /**
     * Two ways in, and only two: the pre-enrollment token on a virgin install,
     * or a live handover invitation addressed to this exact number.
     */
    private fun claim(ctx: Context, from: String?, token: String, code: String): Result {
        if (from.isNullOrEmpty()) return SILENT
        if (!CODE_RE.matches(code)) {
            // Only answer if the token was right — otherwise stay invisible.
            val tokenOk = Keyholder.enrollTokenMatches(ctx, token) ||
                Keyholder.pendingMatches(ctx, from, token)
            return if (tokenOk) {
                Result(false, "moto-guard: pick a code of 6 to 32 letters or digits, no punctuation. Send: MG $token claim YOURCODE")
            } else SILENT
        }

        if (Keyholder.enrollTokenMatches(ctx, token)) {
            Keyholder.enroll(ctx, from, code)
            return Result(
                true,
                "moto-guard: you are the keyholder. Your code is set and nobody else knows it. " +
                    "Send MG YOURCODE pin 1234 to set the on-screen PIN, or MG YOURCODE help."
            )
        }

        if (Keyholder.pendingMatches(ctx, from, token)) {
            val outgoing = Keyholder.number(ctx)
            Keyholder.enroll(ctx, from, code)
            return Result(
                true,
                "moto-guard: handover complete, you are the keyholder. Send MG YOURCODE help.",
                notify = outgoing?.let { it to "moto-guard: handover complete. You are no longer the keyholder." }
            )
        }

        return SILENT
    }

    private fun handover(ctx: Context, arg: String): Result {
        if (arg.equals("cancel", ignoreCase = true)) {
            Keyholder.cancelHandover(ctx)
            return Result(true, "moto-guard: handover cancelled. You are still the keyholder.")
        }
        if (Keyholder.normalize(arg).length != 10) {
            return Result(false, "moto-guard: handover needs a phone number, or the word cancel")
        }
        val token = Keyholder.startHandover(ctx, arg)
        return Result(
            true,
            "moto-guard: invited $arg. They have 24h to claim it. You stay keyholder until they do.",
            notify = arg to "moto-guard: you have been offered the keyholder role. " +
                "To accept, text this number: MG $token claim YOURCODE " +
                "(your code: 6 to 32 letters or digits, chosen by you, nobody else sees it)."
        )
    }

    private fun status(ctx: Context): String {
        val owner = if (Policy.isOwner(ctx)) "yes" else "no"
        val kiosk = if (Policy.isStoodDown(ctx)) "open" else "armed"
        val pin = if (PinStore.isSet(ctx)) "set" else "not set"
        val pending = Keyholder.pendingNumber(ctx)
        val tail = if (pending != null) " handover pending to $pending." else ""
        return "moto-guard: owner=$owner kiosk=$kiosk pin=$pin keyholder=${Keyholder.maskedNumber(ctx)}.$tail"
    }
}
