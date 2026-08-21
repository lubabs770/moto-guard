package com.lubabs770.motoguard

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The PIN wall — also the device HOME, so every home press lands here while
 * provisioned. Correct PIN opens the dashboard ("the app"). If we're NOT the
 * device owner (released / never provisioned) it self-ejects to a real launcher
 * rather than sitting on a toothless lock.
 */
class GuardActivity : Activity() {

    private var fails = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guard)

        val pin = findViewById<EditText>(R.id.pin)
        val msg = findViewById<TextView>(R.id.msg)

        // Public path — no PIN. One "Open X" button per whitelisted app, built from
        // Policy.launchablePackages() so the launcher always matches the lock-task list.
        buildAppButtons()

        findViewById<Button>(R.id.unlock).setOnClickListener { btn ->
            if (PinStore.verify(this, pin.text.toString())) {
                pin.text.clear()
                msg.text = ""
                fails = 0
                startActivity(
                    Intent(this, DashboardActivity::class.java)
                        .putExtra(DashboardActivity.EXTRA_AUTHED, true)
                )
            } else {
                fails++
                pin.text.clear()
                msg.text = "Wrong PIN ($fails)"
                if (fails >= 5) {
                    btn.isEnabled = false
                    btn.postDelayed({ btn.isEnabled = true; fails = 0 }, 30_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Released (no longer owner) or stood down by a remote `open` — either way
        // the wall is not supposed to hold. Unpin and get out of the way.
        if (!Policy.isOwner(this) || Policy.isStoodDown(this)) {
            try { stopLockTask() } catch (_: Exception) {}
            Nav.goHome(this)
            finish()
            return
        }
        Policy.apply(this)
        enterLockTaskIfNeeded()
        findViewById<TextView>(R.id.msg).text =
            if (PinStore.isDefault(this)) "PIN is still default 0000 — change it." else ""
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
