package com.sam.motoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-assert policy after reboot (belt-and-suspenders; GuardActivity-as-HOME also does). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Policy.apply(ctx)
        }
    }
}
