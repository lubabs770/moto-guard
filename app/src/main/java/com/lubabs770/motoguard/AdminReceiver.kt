package com.lubabs770.motoguard

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    }
}
