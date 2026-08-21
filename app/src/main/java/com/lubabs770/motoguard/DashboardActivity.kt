package com.lubabs770.motoguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * The panel, behind the PIN — and deliberately almost empty.
 *
 * Every action that changes the device now goes through the keyholder's
 * confirmation, so there is nothing here for the PIN alone to unlock. What
 * remains is state to read and one setting that grants nothing: where the
 * operator's receipts are sent.
 *
 * The PIN still earns its place. Without it, anyone at the glass could stand
 * here raising requests and pelting the keyholder with confirmation texts.
 */
class DashboardActivity : Activity() {

    companion object { const val EXTRA_AUTHED = "authed" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!intent.getBooleanExtra(EXTRA_AUTHED, false)) {
            Nav.goHome(this); finish(); return
        }
        setContentView(R.layout.activity_dashboard)

        val owner = findViewById<EditText>(R.id.ownerNumber)
        owner.setText(Keyholder.ownerNumber(this) ?: "")

        findViewById<Button>(R.id.saveOwner).setOnClickListener {
            val v = owner.text.toString().trim()
            if (v.isNotEmpty() && Keyholder.normalize(v).length != 10) {
                findViewById<TextView>(R.id.ownerMsg).text = "Enter a phone number, or clear the field"
            } else {
                Keyholder.setOwnerNumber(this, v.ifBlank { null })
                findViewById<TextView>(R.id.ownerMsg).text =
                    if (v.isBlank()) "Receipts off." else "Receipts go to $v."
            }
        }

        findViewById<Button>(R.id.keyholder).setOnClickListener {
            startActivity(
                Intent(this, KeyholderActivity::class.java)
                    .putExtra(EXTRA_AUTHED, true)
            )
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
        findViewById<TextView>(R.id.statusPending).text =
            Challenge.pendingVerb(this)?.let { "Awaiting keyholder confirm: ${it.uppercase()}" } ?: ""
    }
}
