package com.lubabs770.motoguard

import android.content.Context

/**
 * The keyholder's command surface, and the only way anything changes.
 *
 * Nothing here executes on the strength of a message arriving. A command from
 * the keyholder's number *requests* an action; the guard then texts six digits
 * to the number **on record** and waits for them to come back. Caller ID is
 * forgeable, receiving someone else's mail is not, so the echo is the proof.
 *
 * Wire format — a leading dot, then the verb:
 *
 *     .<command> [arg]
 *     .<digits>                - confirm the pending request
 *
 * Parsing is forgiving on purpose, because the keyholder is typing on a phone
 * keypad: leading and trailing space is trimmed, runs of whitespace collapse,
 * a space after the dot is tolerated (". open"), and case is ignored — phones
 * love to capitalise the first letter after a full stop.
 *
 *   claim <token>       - take the keyholder role on a virgin device
 *   help                - what to do, in one message
 *   status              - owner / kiosk / PIN / keyholder state
 *   open                - stand down: leave kiosk, keep Device Owner
 *   lock                - re-arm the kiosk
 *   pin <4-8 digits>    - set the at-the-glass PIN
 *   owner <number>      - where the operator's receipts go
 *   handover <number>   - invite a new keyholder; `handover cancel` aborts
 *   release CONFIRM     - un-provision entirely. One way.
 *
 * `help`, `status` and `claim` answer directly. Everything else takes the dance.
 *
 * Anything not well-formed and not from the keyholder's number is dropped in
 * total silence — no reply, no error. A stranger cannot establish that this
 * device is listening.
 *
 * NOTE ON REPLAY: there is deliberately no body-hash replay check anymore. The
 * dance makes one unnecessary — a captured `.open` re-sent later only raises a
 * fresh challenge, which goes to the keyholder's handset, not the replayer's —
 * and it actively got in the way, since `.open` is a thing one sends
 * repeatedly over a week.
 */
object ControlApi {

    const val PREFIX = "."

    private const val PREFS = "control"
    private const val KEY_WINDOW = "window_start"
    private const val KEY_COUNT = "window_count"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MAX_PER_WINDOW = 10

    private val PIN_RE = Regex("\\d{4,8}")
    private val DIGITS_RE = Regex("\\d{6}")

    /** Verbs that change something, and therefore need confirming. */
    private val GUARDED = setOf("open", "lock", "pin", "owner", "handover", "release")

    /**
     * [reply] goes to the sender; empty means stay silent. [notify] is every
     * other message to send — the challenge to the keyholder, receipts to the
     * owner, an invitation to an incoming keyholder.
     */
    data class Result(
        val ok: Boolean,
        val reply: String,
        val notify: List<Pair<String, String>> = emptyList()
    )

    private val SILENT = Result(false, "")

    private const val HELP =
        "moto-guard commands:\n" +
            "\n" +
            ".status - is it locked?\n" +
            ".open - unlock the kiosk\n" +
            ".lock - lock it again\n" +
            ".pin 1234 - set the screen PIN\n" +
            ".handover NUMBER - pass the key on\n" +
            ".release CONFIRM - unmanage the device\n" +
            "\n" +
            "Anything that changes the device: I text you 6 digits. " +
            "Reply with a dot and those digits, like .481920 - within 5 min.\n" +
            "\n" +
            ".status needs no confirming."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    // ---- entry points ---------------------------------------------------

    fun handle(ctx: Context, from: String?, body: String): Result {
        val trimmed = body.trim()
        if (!trimmed.startsWith(PREFIX)) return SILENT

        // Drop the dot, then tolerate a space after it and any run of whitespace
        // between words. The sender is thumbing this into a phone keypad.
        val words = trimmed.removePrefix(PREFIX).trim().split(Regex("\\s+"))
        val cmd = words.getOrNull(0)?.lowercase().orEmpty()
        if (cmd.isEmpty()) return SILENT
        val arg = words.getOrNull(1) ?: ""

        // The only command reachable by someone who is not yet the keyholder —
        // it is how one becomes the keyholder.
        if (cmd == "claim") return claim(ctx, from, arg)

        if (!Keyholder.isEnrolled(ctx)) return SILENT
        if (!Keyholder.isSender(ctx, from)) return SILENT
        if (rateLimited(ctx)) return SILENT

        return when {
            // Six digits in the verb slot is a confirmation. No keyword in front
            // of it — there is nothing else six digits could mean here.
            DIGITS_RE.matches(cmd) -> confirm(ctx, cmd)
            cmd == "help" -> Result(true, HELP)
            cmd == "status" -> Result(true, status(ctx))
            cmd in GUARDED -> request(ctx, cmd, arg)
            else -> Result(false, HELP)
        }
    }

    /** Tier-3 screen: same dance, raised from the glass instead of a text. */
    fun requestFromGlass(ctx: Context, verb: String, arg: String): Result =
        if (!Keyholder.isEnrolled(ctx)) Result(false, "No keyholder enrolled yet.")
        else request(ctx, verb, arg)

    /** Tier-3 screen: the keyholder types the digits they were texted. */
    fun confirmFromGlass(ctx: Context, digits: String): Result = confirm(ctx, digits)

    // ---- the dance ------------------------------------------------------

    /**
     * Validate, then raise a challenge. Validation happens first so a typo
     * doesn't burn a challenge or spend the keyholder's rate-limit window.
     */
    private fun request(ctx: Context, verb: String, arg: String): Result {
        when (verb) {
            "pin" -> if (!PIN_RE.matches(arg))
                return Result(false, "moto-guard: pin needs 4 to 8 digits")
            "owner" -> if (arg.isNotEmpty() && Keyholder.normalize(arg).length != 10)
                return Result(false, "moto-guard: owner needs a phone number, or nothing to clear it")
            "handover" -> if (!arg.equals("cancel", true) && Keyholder.normalize(arg).length != 10)
                return Result(false, "moto-guard: handover needs a phone number, or the word cancel")
            "release" -> if (arg != "CONFIRM")
                return Result(false, "moto-guard: release needs the word CONFIRM, like .release CONFIRM. It drops device owner for good.")
            "open", "lock" -> if (!Policy.isOwner(ctx))
                return Result(false, "moto-guard: not device owner, nothing to do")
        }

        // `handover cancel` and `owner` are housekeeping on the keyholder's own
        // record; they change nothing about the lock, so they skip the dance.
        if (verb == "handover" && arg.equals("cancel", true)) return execute(ctx, verb, arg)
        if (verb == "owner") return execute(ctx, verb, arg)

        if (Challenge.throttled(ctx)) return SILENT

        val to = Keyholder.number(ctx) ?: return SILENT

        // A challenge still sitting here unanswered means the last request was
        // never confirmed — the fingerprint of someone forging the number, since
        // they can raise a challenge but never receive it.
        val stale = Challenge.reapUnanswered(ctx)
        val digits = Challenge.issue(ctx, verb, arg)

        return Result(
            true,
            "",   // the challenge IS the reply, and it goes to the number on record
            listOf(to to challengeText(verb, digits)) +
                ownerReceipt(ctx, requestedText(verb)) +
                suspicion(ctx, stale)
        )
    }

    private fun confirm(ctx: Context, digits: String): Result {
        if (!DIGITS_RE.matches(digits)) {
            val warn = Challenge.noteUnconfirmed(ctx)
            return Result(false, "moto-guard: that is not a 6 digit code", suspicion(ctx, warn))
        }
        val (verb, arg) = Challenge.consume(ctx, digits)
            ?: return Result(
                false,
                "moto-guard: wrong or expired code. Send the command again for a fresh one.",
                suspicion(ctx, Challenge.noteUnconfirmed(ctx))
            )
        return execute(ctx, verb, arg)
    }

    /**
     * Warn the keyholder that requests they did not make are arriving. Sent at
     * most once an hour so a probe can't turn the guard into a flood of its own.
     */
    private fun suspicion(ctx: Context, warn: Boolean): List<Pair<String, String>> {
        if (!warn) return emptyList()
        val to = Keyholder.number(ctx) ?: return emptyList()
        return listOf(
            to to "moto-guard: several requests from your number went unconfirmed. " +
                "If that was not you, someone is forging your number. Nothing was changed."
        )
    }

    // ---- the verbs ------------------------------------------------------

    private fun execute(ctx: Context, verb: String, arg: String): Result = when (verb) {
        "open" -> {
            Policy.standDown(ctx)
            Result(true, "moto-guard: kiosk OFF, device owner kept. Send .lock to re-arm.",
                ownerReceipt(ctx, "kiosk opened"))
        }

        "lock" -> {
            Policy.rearm(ctx)
            Result(true, "moto-guard: kiosk re-armed.", ownerReceipt(ctx, "kiosk re-armed"))
        }

        "pin" -> {
            PinStore.setPin(ctx, arg)
            Result(true, "moto-guard: PIN set. It opens the on-screen panel.",
                ownerReceipt(ctx, "PIN changed"))
        }

        "owner" -> {
            Keyholder.setOwnerNumber(ctx, arg.ifBlank { null })
            Result(true, if (arg.isBlank()) "moto-guard: owner notifications off."
                         else "moto-guard: receipts now go to $arg.")
        }

        "handover" -> {
            if (arg.equals("cancel", true)) {
                Keyholder.cancelHandover(ctx)
                Result(true, "moto-guard: handover cancelled. You are still the keyholder.")
            } else {
                val token = Keyholder.startHandover(ctx, arg)
                Result(
                    true,
                    "moto-guard: invited $arg. They have 24h to claim it. You stay keyholder until they do.",
                    listOf(
                        arg to "moto-guard: you have been offered the keyholder role. " +
                            "To accept, text this number: .claim $token"
                    ) + ownerReceipt(ctx, "new keyholder invited")
                )
            }
        }

        "release" -> {
            Policy.release(ctx)
            Result(true, "moto-guard: RELEASED. Device unmanaged. Re-provisioning needs adb and zero accounts.",
                ownerReceipt(ctx, "device RELEASED, no longer managed"))
        }

        else -> Result(false, HELP)
    }

    /**
     * Two ways in, and only two: the pre-enrollment token on a virgin install,
     * or a live handover invitation addressed to this exact number.
     */
    private fun claim(ctx: Context, from: String?, token: String): Result {
        if (from.isNullOrEmpty() || token.isEmpty()) return SILENT

        if (Keyholder.enrollTokenMatches(ctx, token)) {
            Keyholder.enroll(ctx, from)
            return Result(
                true,
                "moto-guard: you are the keyholder. You need no code and nothing installed - " +
                    "when you send a command I text you 6 digits to confirm it. Send .help",
                ownerReceipt(ctx, "keyholder enrolled: ${Keyholder.maskedNumber(ctx)}")
            )
        }

        if (Keyholder.pendingMatches(ctx, from, token)) {
            val outgoing = Keyholder.number(ctx)
            Keyholder.enroll(ctx, from)
            Challenge.clear(ctx)   // any challenge raised by the old keyholder dies with the role
            return Result(
                true,
                "moto-guard: handover complete, you are the keyholder. Send .help",
                listOfNotNull(
                    outgoing?.let { it to "moto-guard: handover complete. You are no longer the keyholder." }
                ) + ownerReceipt(ctx, "keyholder handed over to ${Keyholder.maskedNumber(ctx)}")
            )
        }

        return SILENT
    }

    // ---- message text ---------------------------------------------------
    //
    // The challenge and the owner's receipt are built in SEPARATE functions and
    // must stay that way. The receipt must never carry the six digits: an owner
    // who could read them would have a complete bypass — forge a command from
    // the keyholder's number, read the digits off their own receipt, echo them
    // back forged. Keeping the two apart means no future edit can leak one into
    // the other by reusing a string.

    private fun challengeText(verb: String, digits: String): String =
        "moto-guard: confirm ${verb.uppercase()}?\n" +
            "\n" +
            "Reply .$digits within 5 min.\n" +
            "\n" +
            "If you did not ask for this, ignore it - someone is forging your number."

    private fun requestedText(verb: String): String =
        "keyholder requested ${verb.uppercase()}, awaiting their confirmation"

    /** Receipts to the operator. Verbs only — never an argument, never a digit. */
    private fun ownerReceipt(ctx: Context, what: String): List<Pair<String, String>> {
        val to = Keyholder.ownerNumber(ctx) ?: return emptyList()
        return listOf(to to "moto-guard: $what.")
    }

    private fun status(ctx: Context): String {
        val owner = if (Policy.isOwner(ctx)) "yes" else "no"
        val kiosk = if (Policy.isStoodDown(ctx)) "open" else "armed"
        val pin = if (PinStore.isSet(ctx)) "set" else "not set"
        val pending = Keyholder.pendingNumber(ctx)
        val waiting = Challenge.pendingVerb(ctx)
        val tail = buildString {
            if (pending != null) append(" Handover pending to $pending.")
            if (waiting != null) append(" Awaiting your confirm for ${waiting.uppercase()}.")
        }
        return "moto-guard: owner=$owner kiosk=$kiosk pin=$pin keyholder=${Keyholder.maskedNumber(ctx)}.$tail"
    }
}
