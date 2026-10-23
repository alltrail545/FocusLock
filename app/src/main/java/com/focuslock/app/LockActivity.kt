package com.focuslock.app

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The lock screen itself, styled with the FocusLock design system.
 *  - Back button does nothing. Home/recent are neutralised by LockTask (kiosk)
 *    pinning when Device Owner is set, and by the service/accessibility loop otherwise.
 *  - The notification shade / control panel is guarded three ways: the top-strip
 *    overlay in BlockingService, the accessibility collapse on SystemUI, and
 *    setStatusBarDisabled under Device Owner.
 *  - Only two exits exist: the timer ending, or the emergency dialer.
 */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var countdown: TextView
    private lateinit var reasonView: TextView
    private var lockTaskStarted = false

    // v1.4.2 genuine-leave gate: Android sets this via onUserLeaveHint when
    // the user deliberately leaves (Home / Recents). Stray window events
    // alone can no longer pose as "the user left" and drop the lock.
    @Volatile private var userLeaveHint = false
    /** Which package this lock instance is holding (app-limit / site blocks). */
    private var lockedPkg: String? = null
    private var homePkgCache: String? = null

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userLeaveHint = true
    }

    private fun homePackage(): String? {
        if (homePkgCache != null) return homePkgCache
        homePkgCache = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        } catch (_: Exception) { null }
        return homePkgCache
    }

    /**
     * Has the user GENUINELY left the blocked app? A foreground change to a
     * real app (dialer, clock, another app opened on purpose) counts at once.
     * A change to the LAUNCHER counts only with a user-leave hint — XOS fires
     * extra window events around activity launches, and in v1.4.1 such noise
     * could still kill the lock for a second (flicker).
     */
    private fun userGenuinelyLeft(): Boolean {
        val lp = lockedPkg ?: return true
        val fg = Store.lastForegroundPkg ?: return false
        if (fg == lp || fg == packageName) return false
        if (fg == homePackage() && !userLeaveHint) return false
        return true
    }

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
                    val interactive = getSystemService(android.os.PowerManager::class.java)
                        ?.isInteractive ?: true
                    if (interactive && fg != null && fg != packageName &&
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
                        countdown.text = "বাকি: ${Blocker.formatRemaining(state.untilMs)}"
                        handler.postDelayed(this, 1000)
                    }
                }
                is BlockState.SiteBlocked -> {
                    lockedPkg = Store.siteBlockPkg
                    reasonView.text = "এই ওয়েবসাইটটা ব্লক করা: ${state.host}"
                    countdown.text = "Home চেপে বেরিয়ে যাও — এই সাইটটা খোলা যাবে না"
                    handler.postDelayed(this, 500)
                    // v1.4.2: exit only on a genuine leave (see userGenuinelyLeft).
                    if (userGenuinelyLeft()) finishLock()
                }
                is BlockState.AppBlocked -> {
                    lockedPkg = state.pkg
                    reasonView.text = "এই অ্যাপের আজকের সময় শেষ"
                    countdown.text = "কাল আবার ব্যবহার করা যাবে"
                    handler.postDelayed(this, 2000)
                    if (userGenuinelyLeft()) finishLock()
                }
                BlockState.None -> {
                    // v1.4.2: for app/site locks a lone None reading is NOT
                    // proof the block ended (usage stats fluctuate; stray
                    // window events happen) — only a genuine leave ends it.
                    // Hard locks (lockedPkg == null: Full/Focus) still finish
                    // the moment their time is up.
                    if (lockedPkg == null || userGenuinelyLeft()) finishLock()
                    else handler.postDelayed(this, 1000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.4 stability: ONLY show-when-locked. No turn-screen-on (that is
        // what lit the screen back up and made the lock look like it was
        // toggling), no keep-screen-on, no keyguard dismiss — the activity
        // simply waits over the keyguard, already in place at unlock.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        setShowWhenLocked(true)
        setTurnScreenOn(false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@LockActivity, 24), Ui.dp(this@LockActivity, 24),
                Ui.dp(this@LockActivity, 24), Ui.dp(this@LockActivity, 24))
        }

        val card = Ui.card(this)
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(Ui.label(this, "FOCUSLOCK").apply { gravity = Gravity.CENTER })
        reasonView = TextView(this).apply {
            textSize = 20f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, Ui.dp(this@LockActivity, 8), 0, 0)
            text = "লক টাইম"
        }
        countdown = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Ui.BRIGHT)
            setPadding(0, Ui.dp(this@LockActivity, 6), 0, 0)
            text = ""
        }
        card.addView(reasonView)
        card.addView(countdown)
        root.addView(card)

        root.addView(Ui.secondaryButton(this, "জরুরি কল") {
            // Dialer is whitelisted in LockTask, so this opens and returns here after.
            startActivity(Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        })
        root.addView(Ui.secondaryButton(this, "অ্যালার্ম / ঘড়ি") {
            try {
                startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                try {
                    startActivity(packageManager.getLaunchIntentForPackage("com.google.android.deskclock")!!)
                } catch (_: Exception) { }
            }
        })

        // Whitelisted study apps for this focus session (if any were picked):
        // one tap opens the app; leaving it for anything unpicked re-locks instantly.
        val sessionAllowed = Store.focusSessionAllowed(this)
        if (sessionAllowed.isNotEmpty() && Store.focusEndMs(this) > System.currentTimeMillis()) {
            root.addView(Ui.gap(this, 16))
            root.addView(Ui.label(this, "এই সেশনে যেগুলো চলবে").apply { gravity = Gravity.CENTER })
            for (pkg in sessionAllowed.sorted()) {
                root.addView(Ui.secondaryButton(this, appLabel(pkg)) {
                    val launch = packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        Store.lastForegroundPkg = pkg
                        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        if (lockTaskStarted) moveTaskToBack(true) else finish()
                    }
                })
            }
        }
        setContentView(root)

        enterLockTaskIfOwner()
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            // Something (usually the shade) took focus over the lock. Best-effort
            // collapse; the accessibility guard + overlay strip do the heavy lifting.
            try {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (_: Exception) { }
        }
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
        isShowing = false
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        /** True while the lock screen is the resumed (visible) activity. */
        @Volatile var isShowing = false
    }
}
