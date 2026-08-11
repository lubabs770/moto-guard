package com.sam.motoguard

/**
 * Build-time secret for the invisible adb escape hatch. CHANGE before you trust
 * it. Injected in CI from the ADB_SECRET repo secret, so the real value never
 * lands in git (see .github/workflows/build.yml).
 *
 *   adb shell am broadcast -a com.sam.motoguard.UNLOCK \
 *     --es secret 'THIS_STRING' com.sam.motoguard/.SecretUnlockReceiver
 *
 * The PIN is NOT here — it's runtime state (default 0000, settable in-app). See PinStore.
 */
object Config {
    const val ADB_SECRET = "CHANGE_ME_LONG_RANDOM_STRING"
}
