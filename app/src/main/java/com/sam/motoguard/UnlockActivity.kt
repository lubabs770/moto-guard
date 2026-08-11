package com.sam.motoguard

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Gated by the current PIN (default 0000). Two actions, both behind that PIN:
 *   - Release   : un-provision the device (Policy.release).
 *   - Change PIN: verify current PIN, then set a new one (PinStore).
 */
class UnlockActivity : Activity() {

    private var fails = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)

        val title = findViewById<TextView>(R.id.title)
        val pin = findViewById<EditText>(R.id.pin)
        val newPin = findViewById<EditText>(R.id.newPin)
        val confirm = findViewById<EditText>(R.id.confirmPin)
        val release = findViewById<Button>(R.id.release)
        val change = findViewById<Button>(R.id.changePin)
        val save = findViewById<Button>(R.id.save)
        val msg = findViewById<TextView>(R.id.msg)

        if (PinStore.isDefault(this)) msg.text = "PIN is still default 0000 — change it."

        fun backoff(btn: View) {
            fails++
            pin.text.clear()
            msg.text = "Wrong code ($fails)"
            if (fails >= 5) {
                btn.isEnabled = false
                btn.postDelayed({ btn.isEnabled = true; fails = 0 }, 30_000)
            }
        }

        release.setOnClickListener {
            if (PinStore.verify(this, pin.text.toString())) {
                Policy.release(this)
                // Un-provisioned — bounce to a real launcher and tear down our task
                // so no dead LOCKED screen lingers.
                Nav.goHome(this)
                finishAffinity()
            } else backoff(it)
        }

        // First tap: verify current PIN, reveal the new-PIN fields.
        change.setOnClickListener {
            if (!PinStore.verify(this, pin.text.toString())) { backoff(it); return@setOnClickListener }
            title.text = "Set a new PIN"
            pin.visibility = View.GONE
            release.visibility = View.GONE
            change.visibility = View.GONE
            newPin.visibility = View.VISIBLE
            confirm.visibility = View.VISIBLE
            save.visibility = View.VISIBLE
            msg.text = ""
        }

        save.setOnClickListener {
            val a = newPin.text.toString()
            val b = confirm.text.toString()
            when {
                a.length < 4 -> msg.text = "PIN must be at least 4 digits"
                a != b -> msg.text = "PINs don't match"
                else -> {
                    PinStore.setPin(this, a)
                    msg.text = "PIN updated."
                    finish()
                }
            }
        }
    }
}
