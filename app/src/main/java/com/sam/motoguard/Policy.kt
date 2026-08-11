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

    private val hiddenApps = listOf(
        "com.android.settings"          // the "Revoke USB debugging" nuke lives here
    )

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
        dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)

        // Relinquish Device Owner entirely. After this the device is normal again.
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(ctx.packageName)
    }
}
