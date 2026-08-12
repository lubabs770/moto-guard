package com.sam.motoguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * "The app" — reached only through the PIN wall. Shows status and the actions:
 * Change PIN, Release (un-provision), Lock now (back to the wall).
 */
class DashboardActivity : Activity() {

    companion object { const val EXTRA_AUTHED = "authed" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only reachable via the PIN wall (or adb, which is already trusted).
        if (!intent.getBooleanExtra(EXTRA_AUTHED, false)) {
            Nav.goHome(this); finish(); return
        }
        setContentView(R.layout.activity_dashboard)

        findViewById<Button>(R.id.changePin).setOnClickListener {
            startActivity(
                Intent(this, ChangePinActivity::class.java)
                    .putExtra(EXTRA_AUTHED, true)
            )
        }

        findViewById<Button>(R.id.release).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Release device?")
                .setMessage("Removes Device Owner and all restrictions. The device becomes fully unmanaged.")
                .setPositiveButton("Release") { _, _ ->
                    try { stopLockTask() } catch (_: Exception) {}
                    Policy.release(this)
                    Nav.goHome(this)
                    finishAffinity()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.lock).setOnClickListener {
            // Back to the wall.
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val owner = Policy.isOwner(this)
        findViewById<TextView>(R.id.statusOwner).text =
            "Device owner: " + if (owner) "active ✓" else "not set"
        findViewById<TextView>(R.id.statusPin).text =
            "PIN: " + if (PinStore.isDefault(this)) "default 0000 — change it" else "set ✓"
    }
}
