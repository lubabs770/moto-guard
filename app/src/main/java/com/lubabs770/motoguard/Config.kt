package com.lubabs770.motoguard

/**
 * Build-time secrets for the remote escape hatches. CHANGE before you trust
 * them. Injected in CI from repo Secrets, so the real values never land in git
 * (see .github/workflows/build.yml).
 *
 *   adb shell am broadcast -a com.lubabs770.motoguard.UNLOCK \
 *     --es secret 'ADB_SECRET' com.lubabs770.motoguard/.SecretUnlockReceiver
 *
 * The PIN is NOT here — it's runtime state (default 0000, settable in-app). See PinStore.
 */
object Config {
    /** adb broadcast escape hatch. */
    const val ADB_SECRET = "CHANGE_ME_LONG_RANDOM_STRING"

    /**
     * Shared secret for the SMS control channel. MUST be different from
     * ADB_SECRET — this one travels over the air in cleartext SMS, so treat it
     * as burnable and rotate it (new build) after anyone else has used it.
     * Keep it GSM-7 safe: A-Z a-z 0-9 only, no punctuation the carrier may mangle.
     */
    const val SMS_SECRET = "CHANGE_ME_LONG_RANDOM_STRING_TOO"

    /**
     * Comma-separated numbers allowed to issue SMS commands. Matched on the last
     * 10 digits, so +1 732-664-2170 / 17326642170 / 7326642170 all match.
     *
     * Sender IDs are trivially spoofable — this list is a cheap first filter,
     * NOT the security boundary. SMS_SECRET is the boundary.
     */
    const val SMS_ALLOWED_SENDERS = "+17326642170"
}
