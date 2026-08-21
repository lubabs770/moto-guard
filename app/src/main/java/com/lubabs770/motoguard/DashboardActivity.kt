package com.lubabs770.motoguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * The panel, behind the PIN. This is the KEYHOLDER's console for when they have
 * the device in hand — it mirrors the everyday SMS commands and nothing more.
 *
 * Anything that could end the arrangement — rotating the code, handing the role
 * on, un-provisioning — is NOT here. Those live one tier deeper, in
 * KeyholderActivity, behind the keyholder's code rather than the PIN, so that a
 * PIN shoulder-surfed or ground down by patient guessing buys the kiosk being
 * opened and nothing irreversible.
 */
class DashboardActivity : Activity() {

    companion object { const val EXTRA_AUTHED = "authed" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        findViewById<Button>(R.id.keyholder).setOnClickListener {
            startActivity(
                Intent(this, KeyholderActivity::class.java)
                    .putExtra(EXTRA_AUTHED, true)
            )
        }

        findViewById<Button>(R.id.standDown).setOnClickListener {
            // Same reversible escape as `open` over SMS: kiosk off, owner kept.
            Policy.standDown(this)
            finish()
        }

        findViewById<Button>(R.id.lock).setOnClickListener {
            finish()   // back to the wall
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.statusOwner).text =
            "Device owner: " + if (Policy.isOwner(this)) "active" else "not set"
        findViewById<TextView>(R.id.statusKiosk).text =
            "Kiosk: " + if (Policy.isStoodDown(this)) "open" else "armed"
        findViewById<TextView>(R.id.statusKeyholder).text =
            "Keyholder: " + if (Keyholder.isEnrolled(this)) Keyholder.maskedNumber(this) else "none yet"
    }
}
