package com.focuslock.app

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The lock screen itself.
 *  - Back button does nothing. Home/recent are neutralised by LockTask (kiosk)
 *    pinning when Device Owner is set, and by the service/accessibility loop otherwise.
 *  - Only two exits exist: the timer ending, or the emergency dialer.
 */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var reasonView: TextView
    private var lockTaskStarted = false

    private val tick = object : Runnable {
        override fun run() {
            val state = Blocker.currentBlock(this@LockActivity)
            when (state) {
                is BlockState.Full -> {
                    reasonView.text = state.reason
                    countdown.text = "বাকি: ${Blocker.formatRemaining(state.untilMs)}"
                    handler.postDelayed(this, 1000)
                }
                is BlockState.FocusSession -> {
                    val fg = Store.lastForegroundPkg
                    if (fg != null && fg != packageName &&
                        Blocker.isAllowedInFocus(this@LockActivity, fg, state.allowed)
                    ) {
                        // A picked study app is in front — step aside, keep watching.
                        moveTaskToBack(true)
                        handler.postDelayed(this, 1000)
                    } else if (state.allowed.isEmpty()) {
                        reasonView.text = "ফোকাস সেশন চলছে — পুরো ফোন লক"
                        countdown.text = "বাকি: ${Blocker.formatRemaining(state.untilMs)}"
                        handler.postDelayed(this, 1000)
                    } else {
                        reasonView.text = "ফোকাস চলছে — এই অ্যাপটা বাছাই করা হয়নি"
                        countdown.text = "বাকি: ${Blocker.formatRemaining(state.untilMs)}\nনিচে থেকে বাছাই-করা অ্যাপ খোলো"
                        handler.postDelayed(this, 1000)
                    }
                }
                is BlockState.AppBlocked -> {
                    reasonView.text = "এই অ্যাপের আজকের সময় শেষ"
                    countdown.text = "কাল আবার ব্যবহার করা যাবে"
                    handler.postDelayed(this, 2000)
                    // AppBlocked is handled by suspension + sending home; if the user
                    // is no longer in that app, currentBlock() returns None next tick.
                    if (Store.lastForegroundPkg != state.pkg) finishLock()
                }
                BlockState.None -> finishLock()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1F1D"))
            setPadding(64, 64, 64, 64)
        }
        val lockEmoji = TextView(this).apply {
            text = "🔒"; textSize = 64f; gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "FocusLock"; textSize = 32f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        reasonView = TextView(this).apply {
            text = "লক টাইম"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#5EEAD4"))
            setPadding(0, 24, 0, 0)
        }
        countdown = TextView(this).apply {
            text = ""; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 48)
        }
        val callBtn = Button(this).apply {
            text = "📞  জরুরি কল"
            setOnClickListener {
                // Dialer is whitelisted in LockTask, so this opens and returns here after.
                startActivity(Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
        val clockBtn = Button(this).apply {
            text = "⏰  অ্যালার্ম / ঘড়ি"
            setOnClickListener {
                try {
                    startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) {
                    try {
                        startActivity(packageManager.getLaunchIntentForPackage("com.google.android.deskclock")!!)
                    } catch (_: Exception) { }
                }
            }
        }
        root.addView(lockEmoji); root.addView(title); root.addView(reasonView)
        root.addView(countdown); root.addView(callBtn); root.addView(clockBtn)

        // Whitelisted study apps for this focus session (if any were picked):
        // one tap opens the app; leaving it for anything unpicked re-locks instantly.
        val sessionAllowed = Store.focusSessionAllowed(this)
        if (sessionAllowed.isNotEmpty() && Store.focusEndMs(this) > System.currentTimeMillis()) {
            root.addView(TextView(this).apply {
                text = "📚 এই সেশনে যেগুলো চলবে:"
                setTextColor(Color.parseColor("#5EEAD4"))
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 8)
            })
            for (pkg in sessionAllowed.sorted()) {
                root.addView(Button(this).apply {
                    text = appLabel(pkg)
                    setOnClickListener {
                        val launch = packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            Store.lastForegroundPkg = pkg
                            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            if (lockTaskStarted) moveTaskToBack(true) else finish()
                        }
                    }
                })
            }
        }
        setContentView(root)

        enterLockTaskIfOwner()
        handler.post(tick)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun enterLockTaskIfOwner() {
        if (!AdminHelper.isDeviceOwner(this)) return
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val am = getSystemService(ActivityManager::class.java)
            if (dpm.isLockTaskPermitted(packageName) &&
                am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
            ) {
                startLockTask()
                lockTaskStarted = true
                try { dpm.setStatusBarDisabled(AdminHelper.adminComponent(this), true) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Store.logTamper(this, "startLockTask failed: ${e.message}")
        }
    }

    private fun finishLock() {
        handler.removeCallbacks(tick)
        if (lockTaskStarted) {
            try {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                try { dpm.setStatusBarDisabled(AdminHelper.adminComponent(this), false) } catch (_: Exception) {}
                stopLockTask()
            } catch (_: Exception) { }
            lockTaskStarted = false
        }
        finish()
    }

    override fun onBackPressed() {
        // Deliberately nothing. There is no back out of a lock.
        Store.logTamper(this, "Back pressed on lock screen")
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
