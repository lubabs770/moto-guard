# moto-guard

Device Owner **lock-task kiosk** for the headless Moto G7. Pins the foreground to
a whitelist — **only the guard app + the SMS gateway** (`me.capcom.smsgateway`)
may ever come forward, on the physical glass *and* over scrcpy (same shared
display). Settings, launcher, dev-options are unlaunchable, so nobody can reach
*Developer Options → Revoke USB debugging* and brick the adb pipeline.
**adb + scrcpy stay fully open** — they sit below the UI, untouched by lock-task,
and the guard never touches debugging features.

Not "physical screen dead / scrcpy only" — that's impossible on Android 9's one
shared display without root. Instead: both surfaces see the *same* tiny whitelist.

UX: the guard is HOME. It shows a **public "Open SMS Gateway" button** (no PIN —
that's the box's job) plus a **PIN-gated admin** path → **dashboard** (status +
Change PIN + Release + Lock now).

Two escapes, both un-provision the device cleanly:
- **In-app PIN** — enter PIN on the wall → Unlock → dashboard → **Release device**.
  PIN defaults to **0000** on a fresh install, changed in-app behind the current
  PIN (Change PIN). Device state, not a build secret — a re-flash never resets it.
- **Invisible adb secret** — a broadcast carrying a shared secret. No visible UI.

## Threat model (read this)
- ✅ Airtight vs. accidental self-sabotage, casual tampering, and **every adb
  `dpm` command** (a Device Owner cannot be stripped by `remove-active-admin` or
  `pm uninstall`).
- ❌ NOT airtight vs. physical access + recovery. `adb reboot recovery` → wipe (or
  the hardware key combo) sits below the policy layer and factory-resets the DO —
  **which also wipes your trusted adb keys and all config.** That's a deliberate
  nuke, not a fat-finger. Nothing on any Android device blocks it.
- ⚠️ Forget the PIN *and* lose the adb secret → your only way out is that wipe.
  Keep both safe.

## Before you build
Set `ADB_SECRET` as a repo Secret (below). The PIN needs nothing at build time —
it defaults to 0000 and you change it in-app.

## Build — CI only, never local
GitHub Actions builds the APK (`.github/workflows/build.yml`): JDK 17 + Android
SDK 34, `gradle assembleRelease`, artifact `moto-guard-apk`. Runs on every push
and via **Actions → build-apk → Run workflow**. Tagging `vX.Y.Z` also attaches
the APK to a GitHub Release.

Grab the APK:
```sh
gh run download -n moto-guard-apk      # latest run's artifact -> ./*.apk
adb install app-release.apk
```

### Secret (keep the adb escape out of git)
The workflow rewrites `ADB_SECRET` in `Config.kt` at build time from a repo
Secret, so the real value never gets committed:
```sh
gh secret set ADB_SECRET -b 'your-long-random-string'
```
If unset, the committed placeholder is used (fine for a throwaway test build).
The **PIN is not a secret here** — it ships as 0000 and you change it in-app.

### Signing (stable key = updatable installs)
The release APK is signed in CI with a fixed PKCS12 keystore from secrets, so
every build shares one signature and `adb install -r` upgrades in place (an
ephemeral debug key would force an uninstall each time — impossible once the app
is Device Owner). Secrets: `KEYSTORE_B64` (base64 of the .p12), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. Keystore backup lives at `~/moto-guard-signing.p12` —
**lose it and you can't update the installed app** (only a wipe/reinstall fresh).
Without these secrets the build falls back to the debug key.

## Provision (ONE time, order matters)
Device Owner can only be set with **zero accounts** on the device. The Moto ships
a "Verizon Wireless" preloaded-contacts account — remove its source first.
```sh
# 1. clear the blocking account source
adb shell pm uninstall --user 0 com.motorola.contacts.preloadcontacts

# 2. install the guard
adb install app/build/outputs/apk/release/app-release.apk

# 3. make it Device Owner  (fails if ANY account still present)
adb shell dpm set-device-owner com.sam.motoguard/.AdminReceiver
```
Add Google accounts (GV etc.) **after** this — accounts only block at set time.

## Release the device
- PIN: enter it on the wall → Unlock → dashboard → Release device.
- adb secret:
```sh
adb shell am broadcast -a com.sam.motoguard.UNLOCK \
  --es secret 'YOUR_ADB_SECRET' com.sam.motoguard/.SecretUnlockReceiver
```

## What it sets (Policy.kt)
Lock-task whitelist = `{guard, me.capcom.smsgateway}` (`setLockTaskPackages`) with
`LOCK_TASK_FEATURE_HOME | GLOBAL_ACTIONS` · HOME = guard · status bar disabled ·
Settings hidden · DISALLOW_FACTORY_RESET, SAFE_BOOT, ADD_USER, MOUNT_PHYSICAL_MEDIA.
Guard calls `startLockTask()` on resume; Release / adb-secret call `stopLockTask()`
+ un-provision.
**Never** DISALLOW_DEBUGGING_FEATURES — that would kill adb.
