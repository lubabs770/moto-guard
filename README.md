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
  by SMS and can hand the role to someone else. Needs no code and no apps.

**There is no code.** The keyholder memorizes nothing and installs nothing —
which matters, because the keyholder here carries a filtered phone with no
browser, no app store and no apps. All they ever do is send and read texts.

### How a command is authorized

A text arriving from the keyholder's number proves nothing: caller ID is
forgeable by anyone with an SMS gateway. So a command does not execute. It raises
a **challenge** — six digits, minted on the device and texted to the number *on
record*, never to whoever sent the command:

```
keyholder ──".open"────────────────▶ guard
keyholder ◀──"confirm OPEN? Reply .481920 within 5 min"── guard
owner     ◀──"keyholder requested OPEN, awaiting their confirmation"── guard
keyholder ──".481920"──────────────▶ guard      → kiosk opens
owner     ◀──"kiosk opened"── guard
```

Only someone who can actually receive mail at that number sees the digits.
Echoing them back is the proof. They expire in 5 minutes, and any echo burns the
challenge — right or wrong — so guessing gets one try in a million.

### Enrollment (once)

1. A fresh install has no keyholder. The guard screen shows an **enrollment
   token** — eight characters, ambiguity-free alphabet, meant to be read off the
   glass and handed over.
2. The keyholder texts the device: `.claim <TOKEN>`
3. Their number is recorded, the token is destroyed, and enrollment closes. **It
   cannot re-open from the device.** Only the current keyholder can start a
   handover.

### Handover

```
.handover +15551234567     # invite; you stay keyholder until they claim
.handover cancel           # abort
```
The invitee is texted a fresh token and has 24 hours to send `.claim <TOKEN>`.
When they do, the outgoing keyholder is told. The same flow is available at the
glass under **Keyholder actions**.

## Commands

Sent as `.<command> [arg]` from the keyholder's number; a confirmation is just
`.<digits>`. Parsing is forgiving — leading and trailing space is trimmed, runs of
whitespace collapse, a space after the dot is fine, and case is ignored, since
phone keyboards capitalise after a full stop.

| Command | Effect | Confirm? | Reversible |
|---|---|---|---|
| `help` | what to do, in one message | no | — |
| `status` | owner / kiosk / PIN / keyholder state | no | — |
| `open` | **stand down**: leave kiosk, restore launcher + Settings + status bar, **keep Device Owner** | yes | yes, `lock` |
| `lock` | re-arm the kiosk | yes | yes, `open` |
| `pin <4-8 digits>` | set the at-the-glass PIN | yes | yes |
| `owner <number>` | where the operator's receipts go | no | yes |
| `handover <number>` | transfer the role | yes | see above |
| `release CONFIRM` | full un-provision, drops Device Owner | yes | **no** |

`open` is the everyday one. A stand-down persists across reboot —
`Policy.apply()` is a no-op while it's active — so a power cycle cannot silently
re-kiosk the device out from under whoever was let in.

Anything that isn't a well-formed, authenticated command is dropped in **total
silence**: no reply, no error, not even a rejection. A stranger texting the
device cannot establish that it is listening. `help` is for the keyholder, and it
fits in one message — there is nothing to learn but the verbs and the dance.

## The three tiers on the glass

| Tier | Gate | What's there |
|---|---|---|
| Public | none | Read-only state, enrollment token, "Open &lt;app&gt;" buttons |
| Panel | PIN | Status, and where the operator's receipts go. Nothing that changes the device. |
| Keyholder actions | the **dance** | Open, lock, set PIN, handover, release — each one texts the keyholder six digits |

Buttons in the deep tier only *request*. Reaching that screen with the PIN grants
nothing, because the digits that authorize go to a handset the operator does not
hold. That is why the panel can afford to be almost empty.

The PIN still earns its place: without it anyone at the glass could stand there
raising requests and pelting the keyholder with confirmation texts.

The PIN is set remotely (`.pin 1234`), and **there is no default**. A fresh
install has no PIN and no panel, so re-flashing the APK never yields a known key.

PIN failures escalate: four free tries, then 1 minute, 2, 4, 8, capped at an
hour, and the lockout is **persisted** — whoever is at the glass has unlimited
time and can power-cycle at will.

A PIN shoulder-surfed or ground down by patient guessing therefore buys a view of
the status screen, and nothing else.

## Threat model — read this part

The adversary this defends against is **the operator**: the person holding the
device, who does not want to be able to unlock it.

**What holds.** There is no shared secret to steal, because there is no shared
secret. The six digits that authorize an action are generated on the Moto and
sent *outward* to the keyholder's handset; they are never received here, never
written to the SMS provider (`SmsManager` sends are not recorded there), never
logged, and the only copy lives in app-private prefs, which needs root to read.
The operator can forge a command from the keyholder's number all day — the
challenge still goes to a phone they do not have. There is no PIN default, no
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
- **Inbound texts are readable on the device**, and that is *why* the design
  works the way it does. The route is Termux, not adb — verified on the Moto
  2026-08-21:

  | caller | `READ_SMS` | can dump the inbox |
  |---|---|---|
  | `adb shell` (uid 2000) | no | no — `SecurityException` from `SmsProvider` |
  | `com.termux.api` | `granted=true` | **yes**, via `termux-sms-list` |

  So `adb shell content query --uri content://sms/inbox` fails here, though it
  succeeds on devices whose platform grants shell `READ_SMS` — do not rely on the
  denial. The operator's real read path is ssh into Termux and `termux-sms-list`.
  Note that **de-whitelisting Termux would not close it**: lock-task governs what
  can come to the foreground, not what an app daemon may read, and its sshd runs
  either way. An earlier version of this app had the keyholder choose a shared
  code; that code was harvestable from the inbox the first time it was used,
  which is what killed it. Nothing secret travels inbound anymore.

What this design does guarantee is that getting out is **deliberate, total, and
visible** — a wipe or a re-flash, never a quiet override. And because every
request texts the keyholder, an attempt is an alert by construction.

## Living with the motosms pipeline

This device is also the SMS relay for a separate project (`motosms`), so the two
share an inbox. They do not collide:

- `motosms watch` polls `termux-sms-list` — the same Termux `READ_SMS` grant
  discussed above — and dispatches only messages whose id is newer than its
  stored cursor **and** whose sender is in `~/motosms/allow`.
  Keyholder commands come from a number that is not on that list, so they are
  logged as dropped and never reach the handler. The guard sees them by an
  entirely different route — the `SMS_RECEIVED` broadcast.
- **Never add the keyholder to `~/motosms/allow`.** That file feeds a handler
  running with permissions disabled; an entry there is a shell on the workstation,
  not a moto-guard permission. The guard does not need it and never will.
- The guard is the more robust listener of the two: `SMS_RECEIVED` is delivered
  by the telephony framework to every `RECEIVE_SMS` holder, independent of the
  default SMS app — so an outage of the messaging app does not deafen it.
- **Do not add an inbox purge.** Besides being pointless now that nothing secret
  travels inbound, `motosms watch` seeds its cursor from the newest inbox row; if
  that row has just been deleted the cursor comes back lower on restart and old
  messages get re-dispatched.

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
it would mean taking that role over — a large change for no gain, since a command
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
