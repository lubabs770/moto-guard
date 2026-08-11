package com.sam.motoguard

import java.security.MessageDigest

/**
 * The two secrets. CHANGE BOTH before you provision.
 *
 * PIN_SHA256  — SHA-256 hex of your release PIN. Regenerate with:
 *                 printf '%s' 'YOURPIN' | sha256sum
 * ADB_SECRET  — shared secret for the invisible adb escape hatch:
 *                 adb shell am broadcast -a com.sam.motoguard.UNLOCK \
 *                   --es secret 'THIS_STRING' com.sam.motoguard/.SecretUnlockReceiver
 */
object Config {
    // Default below is sha256("246813") — replace it.
    const val PIN_SHA256 = "59c25b00bc10f4b29e431c41429b1ef3ec1e8463de50a5b88be4e26ebcf5d633"
    const val ADB_SECRET = "CHANGE_ME_LONG_RANDOM_STRING"

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun pinOk(entered: String): Boolean = sha256(entered) == PIN_SHA256
}
