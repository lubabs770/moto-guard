package com.lubabs770.motoguard

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The wall — also the device HOME, so every home press lands here while
 * provisioned. It shows the operator exactly where they stand and gives them no
 * lever at all:
 *
 *  - read-only state (who holds the key, is the kiosk armed, is a PIN set)
 *  - the enrollment token, while there is still no keyholder
 *  - the PIN box, which opens the panel the KEYHOLDER uses
 *
 * If we are not the device owner, or the keyholder has stood the kiosk down, it
 * self-ejects to a real launcher rather than sitting on a toothless lock.
 */
class GuardActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guard)

        val pin = findViewById<EditText>(R.id.pin)
        val msg = findViewById<TextView>(R.id.msg)

        // Public path — no PIN. One "Open X" button per whitelisted app, built from
        // Policy.launchablePackages() so the launcher always matches the lock-task list.
        buildAppButtons()

        findViewById<Button>(R.id.unlock).setOnClickListener {
            val wait = PinStore.lockedForMs(this)
            if (wait > 0) {
                pin.text.clear()
                msg.text = "Locked. Try again in ${(wait / 1000) + 1}s"
                return@setOnClickListener
            }
            if (PinStore.verify(this, pin.text.toString())) {
                pin.text.clear()
                msg.text = ""
                startActivity(
                    Intent(this, DashboardActivity::class.java)
                        .putExtra(DashboardActivity.EXTRA_AUTHED, true)
                )
            } else {
                pin.text.clear()
                val now = PinStore.lockedForMs(this)
                msg.text = if (now > 0) "Wrong PIN. Locked ${(now / 1000) + 1}s" else "Wrong PIN"
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Released, or stood down by the keyholder — either way the wall is not
        // supposed to hold. Unpin and get out of the way.
        if (!Policy.isOwner(this) || Policy.isStoodDown(this)) {
            try { stopLockTask() } catch (_: Exception) {}
            Nav.goHome(this)
            finish()
            return
        }
        Policy.apply(this)
        enterLockTaskIfNeeded()
        renderState()
    }

    /** Everything on the public screen is a statement of fact, never a control. */
    private fun renderState() {
        val enrolled = Keyholder.isEnrolled(this)

        findViewById<TextView>(R.id.stateKeyholder).text =
            "Keyholder: " + if (enrolled) Keyholder.maskedNumber(this) else "none yet"
        findViewById<TextView>(R.id.stateKiosk).text =
            "Kiosk: " + if (Policy.isStoodDown(this)) "open" else "armed"
        findViewById<TextView>(R.id.statePin).text =
            "PIN: " + if (PinStore.isSet(this)) "set" else "not set"

        // The enrollment token is meant to be read off the glass and handed over.
        // It disappears for good the moment someone claims the role.
        val enrollCard = findViewById<View>(R.id.enrollCard)
        if (enrolled) {
            enrollCard.visibility = View.GONE
        } else {
            enrollCard.visibility = View.VISIBLE
            val token = Keyholder.enrollToken(this) ?: ""
            findViewById<TextView>(R.id.enrollToken).text = token
            findViewById<TextView>(R.id.enrollHow).text =
                "They text this device:\nMG claim $token"
        }

        // No PIN set means no panel exists to open — say so instead of offering
        // a box that can never succeed.
        val hasPin = PinStore.isSet(this)
        findViewById<View>(R.id.pinCard).visibility = if (hasPin) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.noPinHint).visibility =
            if (hasPin) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /** Render one primary "Open <label>" button per launchable whitelisted app. */
    private fun buildAppButtons() {
        val container = findViewById<LinearLayout>(R.id.appsContainer)
        container.removeAllViews()
        for (pkg in Policy.launchablePackages(this)) {
            val b = Button(this).apply {
                text = "Open ${Policy.appLabel(this@GuardActivity, pkg)}"
                isAllCaps = false
                setTextColor(resources.getColor(R.color.onAccent, theme))
                setBackgroundResource(R.drawable.btn_primary)
                setOnClickListener { Policy.launchApp(this@GuardActivity, pkg) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(12) }
            container.addView(b, lp)
        }
    }

    /** Pin the device to the whitelist. Idempotent — no-op if already locked. */
    private fun enterLockTaskIfNeeded() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try { startLockTask() } catch (_: Exception) { /* not whitelisted yet */ }
        }
    }

    @Deprecated("kiosk: back is disabled on the wall")
    override fun onBackPressed() { /* no-op */ }
}
