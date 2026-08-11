# moto-guard

Device Owner kiosk for the headless Moto G7. Neuters the **physical touchscreen**
(guard app is HOME, Settings hidden, status bar off) so nobody at the glass can
reach *Developer Options → Revoke USB debugging* and brick your adb pipeline.
**adb + scrcpy stay fully open** — the guard never touches debugging features.

Two escapes, both un-provision the device cleanly:
- **In-app PIN** — tap the dim 120dp square on the LOCKED screen → enter code.
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
Edit `app/src/main/java/com/sam/motoguard/Config.kt`:
- `PIN_SHA256` — `printf '%s' 'YOURPIN' | sha256sum`  (default is sha256("246813"))
- `ADB_SECRET` — a long random string.

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

### Secrets (keep PIN/secret out of git)
The workflow rewrites `Config.kt` at build time from repo Secrets, so the real
values never get committed. Set them once:
```sh
gh secret set PIN_SHA256 -b "$(printf '%s' 'YOURPIN' | sha256sum | awk '{print $1}')"
gh secret set ADB_SECRET -b 'your-long-random-string'
```
If unset, the committed placeholders are used (fine for a throwaway test build).

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
- PIN: tap the dim square on the LOCKED screen, enter your code.
- adb secret:
```sh
adb shell am broadcast -a com.sam.motoguard.UNLOCK \
  --es secret 'YOUR_ADB_SECRET' com.sam.motoguard/.SecretUnlockReceiver
```

## What it sets (Policy.kt)
HOME = guard · status bar disabled · Settings hidden · DISALLOW_FACTORY_RESET,
SAFE_BOOT, ADD_USER, MOUNT_PHYSICAL_MEDIA.
**Never** DISALLOW_DEBUGGING_FEATURES — that would kill adb.
