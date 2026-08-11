# CLAUDE.md — moto-guard

Device Owner (DO) kiosk for a **headless Moto G7** (Android 9 / API 28). Purpose:
neuter the **physical touchscreen** so nobody at the glass can reach *Developer
Options → Revoke USB debugging* (which would brick the adb/scrcpy pipeline the
device is administered over). Personal repo (`lubabs770`), private.

## Golden rules
1. **NEVER add `UserManager.DISALLOW_DEBUGGING_FEATURES`** (or otherwise disable
   Developer Options / USB debugging) in `Policy.kt`. That severs adb — the exact
   pipeline this device lives on. The guard locks the *human at the glass*, never
   adb. This is the one landmine.
2. **Never build locally.** No Android toolchain is assumed on dev machines. The
   APK is built **only** in GitHub Actions (`.github/workflows/build.yml`).
   Download with `gh run download -n moto-guard-apk`.
3. **Keep secrets out of git.** `ADB_SECRET` is injected in CI from the repo
   Secret of the same name. The **PIN is not a secret** — it's runtime state
   (default `0000`, changed in-app via `PinStore`); never bake it into the binary.

## Architecture (approach "B" — soft lockdown, not hard lock-task)
- `GuardActivity` — **PIN wall** + registered as **HOME** (home button can't escape);
  back disabled; re-applies policy on resume; self-ejects to a real launcher when
  not owner. Correct PIN → `DashboardActivity`.
- `DashboardActivity` / `ChangePinActivity` — "the app" behind the wall: status,
  Change PIN, Release, Lock now. Guarded by an `authed` extra.
- `Policy.kt` — all DO policy: guard = HOME, status bar disabled, `com.android.settings`
  hidden, restrictions (FACTORY_RESET, SAFE_BOOT, ADD_USER, MOUNT_PHYSICAL_MEDIA).
  `apply()` is idempotent; `release()` un-provisions cleanly.
- `PinStore.kt` — hashed PIN in app-private prefs. Default 0000. Change behind current PIN.
- `UnlockActivity` — PIN-gated **Release** (un-provision) + **Change PIN**.
- `SecretUnlockReceiver` — invisible adb escape: `am broadcast` carrying `ADB_SECRET`.
- `BootReceiver` — re-assert policy on boot.
- Plain `android.*` only — **no AppCompat/androidx** (keeps the APK + build tiny).

Lock-task was rejected on purpose: a hard pin would cage scrcpy too (it's virtual
touch on the same display). Soft lockdown keeps adb/scrcpy fully free.

## Threat model — the floor
Airtight vs. accidental self-sabotage, casual tampering, and **all adb `dpm`
commands** (a DO can't be stripped by `remove-active-admin` or `pm uninstall`).
**NOT** airtight vs. physical access + recovery: `adb reboot recovery` → wipe (or
the hardware key combo) factory-resets below the policy layer and **also wipes the
trusted adb keys + all device config**. Nothing on Android blocks that. Forget the
PIN *and* lose `ADB_SECRET` → wipe is the only way out.

## Provisioning (one time, order matters)
DO can only be set with **zero accounts** on the device. The Moto ships a
"Verizon Wireless" preloaded-contacts account — remove its source first:
```sh
adb shell pm uninstall --user 0 com.motorola.contacts.preloadcontacts
adb install app-release.apk
adb shell dpm set-device-owner com.sam.motoguard/.AdminReceiver
```
Add Google accounts (GV etc.) **after** — accounts only block at set time.

## Escapes
- PIN: enter on the wall → Unlock → dashboard → Release (default 0000).
- adb secret: `adb shell am broadcast -a com.sam.motoguard.UNLOCK --es secret 'ADB_SECRET' com.sam.motoguard/.SecretUnlockReceiver`
