package com.sam.motoguard

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * PIN entry. Correct code -> Policy.release() -> device is un-provisioned and
 * fully normal again. This is the in-app escape.
 */
class UnlockActivity : Activity() {

    private var fails = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)

        val pin = findViewById<EditText>(R.id.pin)
        val msg = findViewById<TextView>(R.id.msg)

        findViewById<Button>(R.id.release).setOnClickListener {
            if (Config.pinOk(pin.text.toString())) {
                Policy.release(this)
                msg.text = "Released. Device is now unmanaged."
                finish()
            } else {
                fails++
                pin.text.clear()
                // Simple linear backoff to blunt brute force at the glass.
                msg.text = "Wrong code ($fails)"
                if (fails >= 5) {
                    it.isEnabled = false
                    it.postDelayed({ it.isEnabled = true }, 30_000)
                }
            }
        }
    }
}
