# moto-guard

A Device Owner **lock-task kiosk** for a headless Android 9 device (built for a
Motorola Moto G7, administered over adb/scrcpy).

It pins the foreground to a **whitelist**: only the guard app plus a configured
set of allowed apps can ever come forward, on the physical screen and over
scrcpy alike. Settings, the launcher, and Developer Options are unreachable, so
no one at the glass can revoke USB debugging and cut the adb pipeline. adb and
scrcpy stay fully open — they operate below the UI and the guard never touches
debugging features.

The whitelist is defined in one place — `Policy.kt` → `lockTaskPackages`. Out of
the box it's the guard, an SMS gateway (`me.capcom.smsgateway`), and a terminal
(`com.termux`). Edit that list to fit your box.

## How it works
The guard is the device HOME. Its screen has two zones:

- **Public** — one "Open &lt;app&gt;" button per whitelisted app, built at runtime
  from the whitelist. No PIN; this is the device's day job.
- **Admin** — a PIN unlocks the **dashboard**: device status, Change PIN, Release
  (un-provision), Lock now.

The PIN is runtime state (hashed, app-private), default **0000**, changed in-app
behind the current PIN. It is not a build secret and survives re-flashing the APK.

Policy applied as Device Owner:
- `setLockTaskPackages(whitelist)` + `setLockTaskFeatures(HOME | GLOBAL_ACTIONS)`;
  the guard calls `startLockTask()` on resume.
- Guard = HOME, status bar disabled, `com.android.settings` hidden.
- User restrictions: `DISALLOW_FACTORY_RESET`, `SAFE_BOOT`, `ADD_USER`,
  `MOUNT_PHYSICAL_MEDIA`.

## Escapes
Both un-provision the device cleanly (`stopLockTask()` + clear whitelist + clear
Device Owner):

- **PIN** — enter it on the guard → dashboard → Release.
- **adb secret** — a broadcast carrying a shared secret, no visible UI:
  ```sh
  adb shell am broadcast -a com.lubabs770.motoguard.UNLOCK \
    --es secret 'YOUR_ADB_SECRET' com.lubabs770.motoguard/.SecretUnlockReceiver
  ```

A Device Owner cannot be stripped by `dpm remove-active-admin` or `pm uninstall`.
Forget the PIN *and* lose the adb secret, and the only way out is a recovery
wipe — which also erases the trusted adb keys and all config. Keep both safe.

## Threat model
- **Holds** against accidental self-sabotage, casual tampering at the glass, and
  every adb `dpm` command.
- **Does not hold** against physical access + recovery: `adb reboot recovery` →
  wipe (or the hardware key combo) factory-resets below the policy layer. No
  Android device blocks that; it's a deliberate nuke, not a fat-finger.

## Build — CI only
The APK is built in GitHub Actions (JDK 17 + Android SDK 34, `gradle
assembleRelease`); it is never built locally.

- `build.yml` — builds on every push; attaches the APK to a Release on pushed tags.
- `release-apk` — **Actions → release-apk → Run workflow**, enter a version
  (e.g. `v0.3.0`); builds and publishes a GitHub Release with the APK.

Grab an APK:
```sh
gh run download -n moto-guard-apk      # latest CI artifact
# or download the asset from a Release
```

### Secrets
Repo Secrets, kept out of git:

- `ADB_SECRET` — the adb escape secret, injected into `Config.kt` at build time.
  ```sh
  gh secret set ADB_SECRET -b 'your-long-random-string'
  ```
- `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — a stable
  PKCS12 signing key. One fixed signature means `adb install -r` upgrades in
  place, which a Device Owner app requires (it can't be uninstalled to swap a key).
  Without them the build falls back to the debug key.

The PIN is never a build input — it ships as 0000 and is changed on-device.

## Provision (once, order matters)
Device Owner can only be set with **zero accounts** on the device. If the device
ships a preloaded account (e.g. a carrier contacts account), disable its source
package first, then re-enable it after:
```sh
adb shell pm disable-user --user 0 <account-source-package>
adb install app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner com.lubabs770.motoguard/.AdminReceiver
adb shell pm enable <account-source-package>
adb shell am start -n com.lubabs770.motoguard/.GuardActivity   # arm
```
Add other accounts **after** provisioning — they only block at set time.

## Do not
Never set `UserManager.DISALLOW_DEBUGGING_FEATURES`. It disables Developer
Options wholesale and severs adb — the pipeline this device runs on. The guard
locks the human at the glass, never adb.
