package com.lubabs770.motoguard

import android.app.Activity
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * The deep tier: every action that changes the device, raised from the glass
 * instead of by text.
 *
 * Reaching this screen with the PIN is not authority. Pressing a button here
 * only *requests* — the guard texts six digits to the keyholder's number, and
 * nothing happens until those digits come back, either typed in below by a
 * keyholder standing here or echoed by text from their handset.
 *
 * So the operator gains nothing by getting this far: the digits go to a phone
 * they do not hold.
 */
class KeyholderActivity : Activity() {

    private lateinit var msg: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!intent.getBooleanExtra(DashboardActivity.EXTRA_AUTHED, false)) {
            Nav.goHome(this); finish(); return
        }
        setContentView(R.layout.activity_keyholder)

        msg = findViewById(R.id.msg)
        val newNumber = findViewById<EditText>(R.id.newNumber)
        val newPin = findViewById<EditText>(R.id.newPin)
        val digits = findViewById<EditText>(R.id.digits)

        findViewById<Button>(R.id.open).setOnClickListener { raise("open", "") }
        findViewById<Button>(R.id.lock).setOnClickListener { raise("lock", "") }

        findViewById<Button>(R.id.setPin).setOnClickListener {
            raise("pin", newPin.text.toString().trim())
            newPin.text.clear()
        }

        findViewById<Button>(R.id.handover).setOnClickListener {
            raise("handover", newNumber.text.toString().trim())
            newNumber.text.clear()
        }

        findViewById<Button>(R.id.release).setOnClickListener {
            // No local confirmation dialog: the real confirmation is the code
            // sent to the keyholder, and a dialog here would only imply the
            // button alone can do something.
            raise("release", "CONFIRM")
        }

        findViewById<Button>(R.id.confirm).setOnClickListener {
            val result = ControlApi.confirmFromGlass(this, digits.text.toString().trim())
            digits.text.clear()
            deliver(result)
            msg.text = result.reply.ifEmpty { "Done." }
            if (result.ok) finish()
        }
    }

    private fun raise(verb: String, arg: String) {
        val result = ControlApi.requestFromGlass(this, verb, arg)
        deliver(result)
        msg.text = if (result.reply.isNotEmpty()) result.reply
        else "Six digits texted to the keyholder. Enter them below within 5 minutes."
    }

    /** Same outbound path as the SMS transport: GSM-safe, multipart when long. */
    private fun deliver(result: ControlApi.Result) {
        for ((to, text) in result.notify) {
            if (to.isEmpty()) continue
            val safe = text.filter { it.code in 32..126 }.take(600)
            try {
                @Suppress("DEPRECATION")
                val sms = SmsManager.getDefault()
                val parts = sms.divideMessage(safe)
                if (parts.size <= 1) sms.sendTextMessage(to, null, safe, null, null)
                else sms.sendMultipartTextMessage(to, null, parts, null, null)
            } catch (_: Exception) { /* no grant yet, or no service */ }
        }
    }
}
