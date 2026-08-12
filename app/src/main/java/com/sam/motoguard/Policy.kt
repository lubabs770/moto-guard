package com.sam.motoguard

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

    /** The SMS gateway — the reason this box exists. Whitelisted alongside us. */
    const val SMS_PKG = "me.capcom.smsgateway"

    /** Termux — persistent ssh foothold (sshd on :8022 over Tailscale). Whitelisted
     *  so its UI can foreground for setup/maintenance. Its sshd is app-uid (not adb),
     *  so it can't touch device policy — safe to expose. */
    const val TERMUX_PKG = "com.termux"

    private val hiddenApps = listOf(
        "com.android.settings"          // the "Revoke USB debugging" nuke lives here
    )

    /** Lock-task whitelist: only these packages may hold the foreground. */
    private fun lockTaskPackages(ctx: Context) = arrayOf(ctx.packageName, SMS_PKG, TERMUX_PKG)

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

    /** Idempotent — safe to call on every launch and on boot. */
    fun apply(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)

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

    /** The sanctioned escape: full un-provision. Called only behind the PIN / secret. */
    fun release(ctx: Context) {
        if (!isOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = AdminReceiver.component(ctx)

        for (p in hiddenApps) dpm.setApplicationHidden(admin, p, false)
        for (r in restrictions) dpm.clearUserRestriction(admin, r)
        dpm.setStatusBarDisabled(admin, false)
        dpm.setLockTaskPackages(admin, emptyArray())   // drop the whitelist
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)

        // Relinquish Device Owner entirely. After this the device is normal again.
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(ctx.packageName)
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
