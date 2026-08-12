package com.lubabs770.motoguard

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Set a new PIN. Reached only from the dashboard (already PIN-gated). */
class ChangePinActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!intent.getBooleanExtra(DashboardActivity.EXTRA_AUTHED, false)) {
            Nav.goHome(this); finish(); return
        }
        setContentView(R.layout.activity_changepin)

        val newPin = findViewById<EditText>(R.id.newPin)
        val confirm = findViewById<EditText>(R.id.confirmPin)
        val msg = findViewById<TextView>(R.id.msg)

        findViewById<Button>(R.id.save).setOnClickListener {
            val a = newPin.text.toString()
            val b = confirm.text.toString()
            when {
                a.length < 4 -> msg.text = "PIN must be at least 4 digits"
                a != b -> msg.text = "PINs don't match"
                else -> {
                    PinStore.setPin(this, a)
                    finish()   // back to dashboard
                }
            }
        }
    }
}
