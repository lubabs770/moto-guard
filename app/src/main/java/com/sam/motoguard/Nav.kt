package com.sam.motoguard

import android.content.Context
import android.content.Intent

/** Send control to a real launcher (any HOME app that isn't us) and leave. */
object Nav {
    fun goHome(ctx: Context) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = ctx.packageManager
            .queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName != ctx.packageName }

        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (other != null) launch.setClassName(other.packageName, other.name)
        ctx.startActivity(launch)
    }
}
