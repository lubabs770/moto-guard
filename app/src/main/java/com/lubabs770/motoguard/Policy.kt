package com.lubabs770.motoguard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager

/**
 * All Device Owner policy in one place.
 *
 * DELIBERATELY ABSENT: UserManager.DISALLOW_DEBUGGING_FEATURES.
 * Setting that would disable Developer Options wholesale and sever adb — the
 * exact pipeline this device runs on. The guard locks the *touchscreen human*,
 * never adb. Do not add it.
 */
object Policy {

    private const val PREFS = "guard"
    private const val KEY_STOOD_DOWN = "stood_down"

    /** Termux — persistent ssh foothold (sshd on :8022 over Tailscale). Whitelisted
     *  so its UI can foreground for setup/maintenance. Its sshd is app-uid (not adb),
     *  so it can't touch device policy — safe to expose. */
    const val TERMUX_PKG = "com.termux"

    private val hiddenApps = listOf(
        "com.android.settings"          // the "Revoke USB debugging" nuke lives here
    )

    /** Lock-task whitelist: only these packages may hold the foreground. */
    private fun lockTaskPackages(ctx: Context) = arrayOf(ctx.packageName, TERMUX_PKG)

    /**
     * The whitelisted apps the guard offers as public "Open X" launchers — the
     * whitelist minus ourselves, minus anything not actually installed/launchable.
     * Single source of truth: the launcher UI is derived from lockTaskPackages().
     */
    fun launchablePackages(ctx: Context): List<String> =
        lockTaskPackages(ctx)
            .filter { it != ctx.packageName }
            .filter { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    private val restrictions = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA
    )

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isOwner(ctx: Context): Boolean = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /**
     * Idempotent — safe to call on every launch and on boot.
     *
     * No-op while stood down (see standDown), so a reboot or a stray
     * GuardActivity.onResume can't silently re-kiosk the device out from under
     * whoever was let in remotely. Only rearm() ends a stand-down.
     */
    fun apply(ctx: Context) {
        if (!isOwner(ctx)) return
        if (isStoodDown(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)

        // Self-grant the SMS control channel's permissions. RECEIVE_SMS/SEND_SMS
        // are runtime permissions and this device is headless — nobody is at the
        // glass to tap Allow. Device Owner can grant them to any app, us included.
        grantSelf(ctx, dpm, admin, Manifest.permission.RECEIVE_SMS)
        grantSelf(ctx, dpm, admin, Manifest.permission.SEND_SMS)

        // Guard app becomes the permanent HOME, so the home button can't escape.
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(
            admin, home, ComponentName(ctx, GuardActivity::class.java)
        )

        dpm.setStatusBarDisabled(admin, true)   // no pulldown -> no quick-settings -> no Settings

        // Kiosk core: pin the foreground to the whitelist. Anything not here
        // (Settings, launcher, dev-options) cannot come forward, on glass OR scrcpy.
        // adb itself sits below the UI, so it's untouched. HOME feature lets the
        // guard stay reachable as the home target; GLOBAL_ACTIONS keeps power menu.
        dpm.setLockTaskPackages(admin, lockTaskPackages(ctx))
        dpm.setLockTaskFeatures(
            admin,
            DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        )

        for (r in restrictions) dpm.addUserRestriction(admin, r)
        for (p in hiddenApps) dpm.setApplicationHidden(admin, p, true)
    }

    /**
     * The sanctioned escape: full un-provision, and the end of the arrangement.
     *
     * Reachable only through the keyholder's confirmation, and it takes the
     * whole arrangement down with it — Device Owner, the keyholder, the PIN, any
     * pending challenge or handover. Enrollment reopens with a fresh token, so a
     * re-provisioned device starts from nothing rather than silently restoring a
     * keyholder who may no longer be involved.
     *
     * The wipe lives here, not in the callers, so no future release path can
     * forget it.
     */
    fun release(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)

        setStoodDown(ctx, false)
        for (p in hiddenApps) dpm.setApplicationHidden(admin, p, false)
        for (r in restrictions) dpm.clearUserRestriction(admin, r)
        dpm.setStatusBarDisabled(admin, false)
        dpm.setLockTaskPackages(admin, emptyArray())   // drop the whitelist
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)

        // Relinquish Device Owner entirely. After this the device is normal again.
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(ctx.packageName)

        // Ordered last on purpose: if the DPM work above throws, the keyholder
        // keeps their role and can simply try again, rather than being locked
        // out of a device that is still managed.
        Challenge.clear(ctx)
        PinStore.clear(ctx)
        Keyholder.reset(ctx)
    }

    private fun grantSelf(
        ctx: Context, dpm: DevicePolicyManager, admin: ComponentName, perm: String
    ) {
        try {
            dpm.setPermissionGrantState(
                admin, ctx.packageName, perm,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        } catch (_: Exception) { /* permission not in the manifest on this build */ }
    }

    /** True while a remote `open` has suspended the kiosk. Survives reboot. */
    fun isStoodDown(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STOOD_DOWN, false)

    private fun setStoodDown(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_STOOD_DOWN, v).apply()

    /**
     * The reversible escape: drop the kiosk but KEEP Device Owner.
     *
     * Everything release() does except clearDeviceOwnerApp() — the whitelist,
     * the HOME pin, the status bar and the hidden Settings all come back with
     * rearm(). Use this to let a human in; use release() only when the device
     * should stop being managed for good.
     */
    fun standDown(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)

        setStoodDown(ctx, true)

        for (p in hiddenApps) dpm.setApplicationHidden(admin, p, false)
        dpm.setStatusBarDisabled(admin, false)
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)

        // Dropping the whitelist ends lock task on most builds, but not reliably
        // on API 28. Bounce through the guard, which is still ours and can call
        // stopLockTask() from the foreground, then hand control to a launcher.
        try {
            ctx.startActivity(
                Intent(ctx, GuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { /* not pinned / no foreground; policy already relaxed */ }
    }

    /** End a stand-down and put the kiosk back exactly as it was. */
    fun rearm(ctx: Context) {
        setStoodDown(ctx, false)
        apply(ctx)
        try {
            ctx.startActivity(
                Intent(ctx, GuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    /** Bring a whitelisted app forward. It's whitelisted, so it stays inside lock-task. */
    fun launchApp(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** Human label for a package, falling back to the package name. */
    fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
