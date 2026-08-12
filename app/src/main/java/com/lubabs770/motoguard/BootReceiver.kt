package com.lubabs770.motoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot handler. Three jobs, in order:
 *   1. Kick Tailscale so the tunnel actually comes up. Android always-on VPN alone
 *      proved unreliable on this device — after some reboots it registered with the
 *      coordination server but brought up no datapath (rx 0, unreachable off-LAN).
 *      Launching its activity forces the VpnService to connect.
 *   2. Re-assert DO policy (belt-and-suspenders; GuardActivity-as-HOME also does).
 *   3. Bring GuardActivity to the front so lock-task re-engages (Tailscale keeps
 *      connecting as a background service behind the kiosk).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Policy.launchApp(ctx, Policy.TAILSCALE_PKG)   // force the tunnel up
        Policy.apply(ctx)                             // re-assert policy

        // Land on the guard so lock-task re-engages (Tailscale stays in the bg).
        ctx.startActivity(
            Intent(ctx, GuardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
