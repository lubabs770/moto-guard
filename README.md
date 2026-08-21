# moto-guard

A Device Owner **lock-task kiosk** for a headless Android 9 device (built for a
Motorola Moto G7, administered over adb/scrcpy), with a **keyholder lock** on
top: the person carrying the device is not the person who can unlock it.

It pins the foreground to a **whitelist**: only the guard app plus a configured
set of allowed apps can ever come forward, on the physical screen and over
scrcpy alike. Settings, the launcher, and Developer Options are unreachable.

The whitelist is defined in one place — `Policy.kt` → `lockTaskPackages`. Edit
that list to fit your box.

## The arrangement

There are two roles and they are deliberately not the same person.

- **Operator** — carries the device. Sees status, opens whitelisted apps, and
  has no way to unlock anything.
- **Keyholder** — a phone number, somewhere else. Controls the device entirely
  by SMS, chooses their own code, and can hand the role to someone else.

The keyholder's code is chosen **by them, over the air, after the APK is
installed**. It is never a build constant, never a CI secret, never in git. The
person who builds and flashes the APK does not learn it.

### Enrollment (once)

1. A fresh install has no keyholder. The guard screen shows an **enrollment
   token** — eight characters, ambiguity-free alphabet, meant to be read off the
   glass and handed over.
2. The keyholder texts the device:
   ```
   MG <TOKEN> claim THEIRCODE
   ```
3. Their number and a salted hash of their code are recorded, the token is
   destroyed, and enrollment closes. **It cannot re-open from the device.** Only
   the current keyholder can start a handover.

### Handover

```
MG <code> handover +15551234567     # invite; you stay keyholder until they claim
MG <code> handover cancel           # abort
```
The invitee is texted a fresh token and has 24 hours to send
`MG <TOKEN> claim THEIRCODE`. When they do, the outgoing keyholder is told.
The same flow is available at the glass under **Keyholder actions**, which shows
the token instead of texting it — for when there's no service.

## Commands

Sent as `MG <code> <command> [arg]` from the keyholder's number.

| Command | Effect | Reversible |
|---|---|---|
| `help` | list the commands | — |
| `status` | owner / kiosk / PIN / keyholder state | — |
| `open` | **stand down**: leave kiosk, restore launcher + Settings + status bar, **keep Device Owner** | yes, `lock` |
| `lock` | re-arm the kiosk | yes, `open` |
| `pin <4-8 digits>` | set the at-the-glass PIN | yes |
| `code <new>` | rotate the SMS code | yes |
| `handover <number>` | transfer the role | see above |
| `release CONFIRM` | full un-provision, drops Device Owner | **no** |

`open` is the everyday one. A stand-down persists across reboot —
`Policy.apply()` is a no-op while it's active — so a power cycle cannot silently
re-kiosk the device out from under whoever was let in.

Anything that isn't a well-formed, authenticated command is dropped in **total
silence**: no reply, no error, not even for a wrong code. A stranger texting the
device cannot establish that it is listening. `help` is for the person who holds
the code and forgot the verbs.

## The three tiers on the glass

| Tier | Gate | What's there |
|---|---|---|
| Public | none | Read-only state, enrollment token, "Open &lt;app&gt;" buttons |
| Panel | PIN | Change PIN, open the kiosk, status |
| Keyholder actions | keyholder **code** | Rotate code, handover, release |

The PIN belongs to the keyholder, not the operator — it is set remotely
(`MG <code> pin 1234`) or in person, and **there is no default**. A fresh install
has no PIN and no panel, so re-flashing the APK never yields a known key.

PIN failures escalate: four free tries, then 1 minute, 2, 4, 8, capped at an
hour, and the lockout is **persisted** — whoever is at the glass has unlimited
time and can power-cycle at will.

The deep tier is gated on the code rather than the PIN on purpose: a PIN
shoulder-surfed or ground down by patient guessing buys the kiosk being opened,
and nothing irreversible.

## Threat model — read this part

The adversary this defends against is **the operator**: the person holding the
device, who does not want to be able to unlock it.

**What holds.** The code exists nowhere they can read it — not in git, not in CI,
not in the APK, and app-private storage needs root. There is no PIN default, no
build-time allow-list, and **no adb escape hatch** (there used to be one; it was
removed deliberately — an unlock the operator could reach would make the
keyholder decorative). Every route out goes through the keyholder.

**What does not hold, and cannot.**

- **Whoever holds the signing key and adb can flash a modified build.** `adb
  install -r` with a version that skips the check reinstalls over the top. If the
  operator controls this repo and the keystore, the lock binds them by agreement,
  not by force.
- **Physical access plus recovery wins.** `adb reboot recovery` → wipe, or the
  hardware key combo, factory-resets below the policy layer. No Android device
  blocks that.
- **Inbound texts are readable on the device.** This is the sharp one. Anything
  with adb or a whitelisted terminal can read the SMS database —
  `adb shell content query --uri content://sms/inbox`, or `termux-sms-list` if
  Termux is whitelisted. Keeping the messaging app out of the lock-task list does
  **not** prevent this; lock-task only governs what can come to the foreground,
  not what a shell can read. **So an operator with adb learns the code the first
  time the keyholder uses it.**

That last point is the real limit of the plain-code scheme, and the fix is
rolling one-time codes (a hash chain: each code dies as it is used, so reading
the inbox teaches you nothing about the next one). `Keyholder.verifyCode` is the
only place that would change.

What this design does guarantee is that getting out is **deliberate, total, and
visible** — a wipe or a re-flash, never a quiet override.

## Policy applied as Device Owner

- `setLockTaskPackages(whitelist)` + `setLockTaskFeatures(HOME | GLOBAL_ACTIONS)`;
  the guard calls `startLockTask()` on resume.
- Guard = HOME, status bar disabled, `com.android.settings` hidden.
- User restrictions: `DISALLOW_FACTORY_RESET`, `SAFE_BOOT`, `ADD_USER`,
  `MOUNT_PHYSICAL_MEDIA`.
- `RECEIVE_SMS` / `SEND_SMS` self-granted via `setPermissionGrantState` — the
  device is headless and nobody is at the glass to tap Allow.

**Why SMS and not MMS.** `SMS_RECEIVED` is broadcast to every app holding
`RECEIVE_SMS`, so the guard listens without displacing the device's real SMS app.
Inbound MMS reaches only the *default* SMS app (`WAP_PUSH_DELIVER`), so accepting
it would mean taking that role over — a large change for no gain, since a code
fits in a text.

## Build — CI only
The APK is built in GitHub Actions (JDK 17 + Android SDK 34, `gradle
assembleRelease`); it is never built locally.

- `build.yml` — builds on every push; attaches the APK to a Release on pushed tags.
- `release-apk` — **Actions → release-apk → Run workflow**, enter a version.

```sh
gh run download -n moto-guard-apk      # latest CI artifact
```

### Secrets
Only the signing key. Nothing about the lock is a build input.

- `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — a stable
  PKCS12 signing key. One fixed signature means `adb install -r` upgrades in
  place, which a Device Owner app requires (it can't be uninstalled to swap a key).
  Without them the build falls back to the debug key.

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

Then read the enrollment token off the screen and give it to the keyholder.

## Do not
Never set `UserManager.DISALLOW_DEBUGGING_FEATURES`. It disables Developer
Options wholesale and severs adb — the pipeline this device runs on.
