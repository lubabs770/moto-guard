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

### PIN
Enter it on the guard → dashboard → **Release** (un-provisions) or **Lock now**.

### The control API
One command layer (`ControlApi.kt`), two transports. Same grammar, same auth,
same commands — a new transport can't ship a weaker gate because the gate isn't
in the transport.

| Command | Effect | Reversible |
|---|---|---|
| `status` | report owner / kiosk / PIN state | — |
| `open` | **stand down**: leave kiosk, restore launcher + Settings + status bar, **keep Device Owner** | yes, `lock` |
| `lock` | re-arm the kiosk | yes, `open` |
| `pin <4-8 digits>` | reset the PIN (for when it's forgotten) | yes, set it again |
| `release CONFIRM` | full un-provision, drops Device Owner | **no** |

`open` is the one you want when a human needs the phone back. `release` is the
nuke: a released device can only be re-provisioned over adb with zero accounts
on it, which usually means a wipe.

A stand-down persists across reboot — `Policy.apply()` is a no-op while it's
active, so a power cycle can't silently re-kiosk the device out from under
whoever was let in. Only `lock` (or the dashboard) ends it.

**Over SMS** — text the device from a number on the allow-list:

```
MG <SMS_SECRET> open
MG <SMS_SECRET> pin 4821
MG <SMS_SECRET> status
MG <SMS_SECRET> release CONFIRM
```

You get a one-line SMS back. Anything else in the inbox is ignored without a
trace: wrong prefix or wrong secret produces no reply at all, so probing can't
even confirm the number is a live target.

**Over adb** — the original bare broadcast still means "release"; add `cmd` to
run any other command, and the reply comes back in the result data:

```sh
adb shell am broadcast -a com.lubabs770.motoguard.UNLOCK \
  --es secret 'YOUR_ADB_SECRET' --es cmd 'open' \
  com.lubabs770.motoguard/.SecretUnlockReceiver
```

### SMS channel security
The threat here is real: a text message is an unauthenticated, world-reachable
input, and the commands behind it un-manage the device. Layers, outermost first:

1. **`BROADCAST_SMS` on the receiver.** Only the system holds that permission, so
   no other app on the device can forge an inbound-SMS intent.
2. **Sender allow-list** (`Config.SMS_ALLOWED_SENDERS`, matched on the last 10
   digits). Caller ID is trivially spoofable, so this is a cheap first filter and
   *not* the boundary.
3. **Shared secret** (`Config.SMS_SECRET`) — the actual boundary. Make it long and
   random. It rides the air in cleartext, so treat it as **burnable**: rotate it
   (new build) after anyone else has held it, and never reuse `ADB_SECRET` for it.
4. **Replay defence** — a body already executed in the last 30 minutes is
   rejected, so a captured message can't be re-sent and a carrier redelivery
   can't fire `release` twice.
5. **Rate limit** — 5 accepted commands per 10 minutes.
6. **`release` needs the literal word `CONFIRM`.**

The adb transport skips 4 and 5: adb already owns the device, so those defences
would only get in its way.

**Why SMS and not MMS.** `SMS_RECEIVED` is broadcast to every app holding
`RECEIVE_SMS`, so the guard listens without disturbing the device's real SMS app.
Inbound MMS is delivered only to the *default* SMS app (`WAP_PUSH_DELIVER`), so
accepting MMS would mean displacing Google Messages on the relay — a large change
for no gain, since a PIN fits in a text.

`RECEIVE_SMS` and `SEND_SMS` are runtime permissions and this device is headless,
so `Policy.apply()` self-grants them via `setPermissionGrantState` as Device
Owner. Nobody has to be at the glass to tap Allow.

A Device Owner cannot be stripped by `dpm remove-active-admin` or `pm uninstall`.
Forget the PIN *and* lose the adb secret *and* the SMS secret, and the only way
out is a recovery wipe — which also erases the trusted adb keys and all config.
Keep them safe.

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
- `SMS_SECRET` — the SMS control-channel secret. Must differ from `ADB_SECRET`.
  Keep it alphanumeric (GSM-7 safe) so no carrier mangles it in transit.
  ```sh
  gh secret set SMS_SECRET -b "$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
  ```
- `SMS_ALLOWED_SENDERS` — comma-separated numbers permitted to send commands.
  ```sh
  gh secret set SMS_ALLOWED_SENDERS -b '+15551234567,+15559876543'
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
