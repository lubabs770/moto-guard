package com.lubabs770.motoguard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * The deep tier: the three actions that can end the arrangement. Each one
 * re-checks the keyholder's SMS code, which the operator is not supposed to
 * know — reaching this screen with the PIN is not enough on its own.
 *
 * Everything here has an SMS twin (`code`, `handover`, `release`); this exists
 * for the keyholder standing in front of the device, or for the day the SIM is
 * dead and SMS is not an option.
 */
class KeyholderActivity : Activity() {

    private lateinit var code: EditText
    private lateinit var msg: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!intent.getBooleanExtra(DashboardActivity.EXTRA_AUTHED, false)) {
            Nav.goHome(this); finish(); return
        }
        setContentView(R.layout.activity_keyholder)

        code = findViewById(R.id.code)
        msg = findViewById(R.id.msg)

        val newCode = findViewById<EditText>(R.id.newCode)
        val newNumber = findViewById<EditText>(R.id.newNumber)

        findViewById<Button>(R.id.rotate).setOnClickListener {
            if (!authed()) return@setOnClickListener
            val v = newCode.text.toString()
            if (!Regex("[A-Za-z0-9]{6,32}").matches(v)) {
                msg.text = "New code: 6-32 letters or digits, no punctuation"
                return@setOnClickListener
            }
            Keyholder.setCode(this, v)
            newCode.text.clear()
            code.text.clear()
            msg.text = "Code rotated. The old one is dead."
        }

        findViewById<Button>(R.id.handover).setOnClickListener {
            if (!authed()) return@setOnClickListener
            val n = newNumber.text.toString()
            if (Keyholder.normalize(n).length != 10) {
                msg.text = "Enter the new keyholder's phone number"
                return@setOnClickListener
            }
            val token = Keyholder.startHandover(this, n)
            newNumber.text.clear()
            code.text.clear()
            // Shown, not texted: at the glass there may be no service, and the
            // outgoing keyholder can pass it on however they like.
            msg.text = "Invite token for $n (valid 24h):\n$token\n" +
                "They text: MG $token claim THEIRCODE"
        }

        findViewById<Button>(R.id.release).setOnClickListener {
            if (!authed()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Release device?")
                .setMessage(
                    "Removes Device Owner and every restriction. The device becomes " +
                        "fully unmanaged and the keyholder role ends. This cannot be undone " +
                        "without re-provisioning over adb, which needs zero accounts on the device."
                )
                .setPositiveButton("Release") { _, _ ->
                    try { stopLockTask() } catch (_: Exception) {}
                    Policy.release(this)
                    Nav.goHome(this)
                    finishAffinity()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun authed(): Boolean {
        if (Keyholder.verifyCode(this, code.text.toString())) return true
        code.text.clear()
        msg.text = "Wrong keyholder code"
        return false
    }
}
