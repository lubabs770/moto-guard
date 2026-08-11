package com.sam.motoguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

/**
 * The kiosk face + HOME. Re-applies policy on every resume (idempotent) and
 * swallows the back button. A dim, deliberately-unobvious tap target opens the
 * PIN screen.
 */
class GuardActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guard)

        findViewById<View>(R.id.unlockTap).setOnClickListener {
            startActivity(Intent(this, UnlockActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Only lock the screen while we actually own the device. If we've been
        // released (or never provisioned), get out of the way to a real launcher
        // instead of sitting on a toothless LOCKED screen.
        if (!Policy.isOwner(this)) {
            Nav.goHome(this)
            finish()
            return
        }
        Policy.apply(this)
    }

    @Deprecated("kiosk: back is disabled")
    override fun onBackPressed() {
        // no-op — can't back out of the guard
    }
}
