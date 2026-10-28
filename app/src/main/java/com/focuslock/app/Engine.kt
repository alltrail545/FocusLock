package com.focuslock.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Calendar

/**
 * The shared enforcement tick (v1.3 silent engine + v1.4 stability pack).
 *
 * STABILITY (Tamim's report: the lock screen flickered as if focus was
 * toggling on/off when the screen was locked or other things happened):
 *  - The lock screen is launched ONLY through [ensureLockShown], which
 *    refuses to launch when the lock is already showing, when the screen
 *    is off (the lock activity already sits over the keyguard via
 *    showWhenLocked, so nothing needs launching — launching with
 *    turnScreenOn is exactly what caused the on/off flicker), when the
 *    dialer/clock is in front, or more often than once per 1.5 s.
 *  - SINGLE_TOP flag (not CLEAR_TOP): re-delivering an intent to the
 *    running lock activity no longer recreates it.
 *
 * Also here: focus-session completion recording (report), Do Not Disturb
 * during hard locks, and the 5-minute app-limit warning banner.
 */
object Engine {

    private var lastSuspendDay = -1
    private var lockTaskFocusEndApplied = 0L
    private var guardView: View? = null

    // ---- stability ----
    @Volatile private var lastLockLaunchMs = 0L

    // ---- DND state ----
    private var dndApplied = false
    private var dndPreviousFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    // ---- limit-warning state ----
    private val warnedPkgsToday = mutableSetOf<String>()
    private var warnDay = -1
    private var warnView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        try {
            enforce(app)
        } catch (e: Throwable) {
            // v1.8.0 audit: was Exception-only — an Error escaping enforce()
            // would propagate into the callers' handler runnables and could
            // stop their re-post loop, killing enforcement silently.
            try { Store.logTamper(app, "engine tick failed: ${e.message}") } catch (_: Throwable) { }
        }
    }

    private fun enforce(ctx: Context) {
        Store.applyPendingIfDue(ctx)
        recordFinishedFocus(ctx)

        // Midnight rollover: yesterday's suspensions lift, today's limits start fresh.
        // v1.8.0 audit: the key is year-aware — plain DAY_OF_YEAR repeats
        // every year, so a Jan-1 rollover could be mistaken for "same day".
        val calNow = Calendar.getInstance()
        val today = calNow.get(Calendar.YEAR) * 1000 + calNow.get(Calendar.DAY_OF_YEAR)
        if (today != lastSuspendDay) {
            lastSuspendDay = today
            AdminHelper.refreshSuspensions(ctx)
        }
        if (today != warnDay) {
            warnDay = today
            warnedPkgsToday.clear()
        }

        val state = Blocker.currentBlock(ctx)
        // Shade guard follows the hard-lock states only.
        updateShadeGuard(ctx, state is BlockState.Full || state is BlockState.FocusSession)
        // Do Not Disturb follows the hard-lock states too (if the user wants it).
        updateDnd(ctx, state is BlockState.Full || state is BlockState.FocusSession)

        when (state) {
            is BlockState.Full -> {
                AdminHelper.refreshSuspensions(ctx)
                ensureLockShown(ctx)
            }
            is BlockState.FocusSession -> {
                if (lockTaskFocusEndApplied != state.untilMs) {
                    lockTaskFocusEndApplied = state.untilMs
                    AdminHelper.applyLockTaskPackages(ctx, state.allowed)
                }
                val fg = Store.lastForegroundPkg
                if (state.allowed.isEmpty()) {
                    ensureLockShown(ctx) // no apps picked = total lock
                } else if (fg != null && fg != ctx.packageName &&
                    !Blocker.isAllowedInFocus(ctx, fg, state.allowed)
                ) {
                    ensureLockShown(ctx)
                }
            }
            is BlockState.AppBlocked -> {
                AdminHelper.suspendApp(ctx, state.pkg, true)
                ensureLockShown(ctx)
            }
            is BlockState.SiteBlocked -> ensureLockShown(ctx)
            BlockState.None -> {
                if (lockTaskFocusEndApplied != 0L) {
                    lockTaskFocusEndApplied = 0L
                    AdminHelper.applyLockTaskPackages(ctx) // dialer/clock only
                }
                /* LockActivity closes itself when its own tick sees None */
            }
        }

        maybeWarnLimitApproaching(ctx, state)

        // Silent-engine rule: the foreground service exists ONLY during a block.
        if (state == BlockState.None) BlockingService.stop(ctx) else BlockingService.start(ctx)
    }

    /**
     * The ONLY way anything may bring the lock screen forward.
     * Every guard here exists to kill the flicker Tamim reported:
     * no launch if the lock is already up, none while the screen is off,
     * none over the dialer/clock, and never twice within 1.5 s.
     */
    fun ensureLockShown(ctx: Context) {
        if (LockActivity.isShowing) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return // screen off: lock already waits over the keyguard
        val fg = Store.lastForegroundPkg
        if (fg != null && fg != ctx.packageName && fg in Blocker.alwaysAllowed(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastLockLaunchMs < 1500) return
        lastLockLaunchMs = now
        try {
            ctx.startActivity(
                Intent(ctx, LockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } catch (_: Exception) { }
    }

    /** When a focus session's end time passes, log its minutes for the report (once). */
    private fun recordFinishedFocus(ctx: Context) {
        val end = Store.focusEndMs(ctx)
        val now = System.currentTimeMillis()
        if (end > now) {
            // Session still running — nothing to record yet.
        } else if (end > 0) {
            // v1.8.0 audit: the old code recorded only when THIS process had
            // seen the session running (focusEndSeen == end). If the process
            // died and restarted after the session had already ended, the
            // minutes were never recorded and the stale end/start values
            // stayed in the store forever. The stored start/end pair is
            // enough to record exactly once: recording clears both, so a
            // restart can never double-count.
            val start = Store.focusStartMs(ctx)
            if (start > 0 && start < end) {
                Store.recordFocusMinutes(ctx, ((end - start) / 60000).toInt())
            }
            Store.setFocusEndMs(ctx, 0L)
            Store.setFocusStartMs(ctx, 0L)
        }
    }

    // ---------------- Do Not Disturb during hard locks ----------------

    private fun updateDnd(ctx: Context, hardLock: Boolean) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            // v1.8.0 audit: adopt a persisted restore state left by a dead
            // process, so a mid-lock kill can no longer strand the phone on
            // the alarms-only filter after the lock ends.
            if (!dndApplied) {
                Store.dndPreviousFilter(ctx)?.let {
                    dndApplied = true
                    dndPreviousFilter = it
                }
            }
            if (hardLock && Store.silenceDuringLock(ctx)) {
                if (!dndApplied && nm.isNotificationPolicyAccessGranted) {
                    dndPreviousFilter = nm.currentInterruptionFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    dndApplied = true
                    Store.setDndPreviousFilter(ctx, dndPreviousFilter)
                }
            } else if (dndApplied) {
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(dndPreviousFilter)
                }
                dndApplied = false
                Store.setDndPreviousFilter(ctx, null)
            }
        } catch (_: Exception) { }
    }

    // ---------------- 5-minute limit warning banner ----------------

    private fun maybeWarnLimitApproaching(ctx: Context, state: BlockState) {
        if (state is BlockState.AppBlocked) return
        val fg = Store.lastForegroundPkg ?: return
        if (fg == ctx.packageName || fg in warnedPkgsToday) return
        val limitMin = Blocker.effectiveLimitMinutes(ctx, fg) ?: return
        val usedMin = Blocker.usageTodayMs(ctx, fg) / 60000
        val remaining = limitMin - usedMin
        if (remaining in 1..5) {
            warnedPkgsToday += fg
            val label = try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(fg, 0)).toString()
            } catch (_: Exception) { fg }
            showWarnBanner(ctx, "$label — আজকের লিমিট শেষ হতে $remaining মিনিট বাকি")
        }
    }

    private fun showWarnBanner(ctx: Context, text: String) {
        if (!Settings.canDrawOverlays(ctx)) return
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        mainHandler.post {
            if (warnView != null) return@post
            try {
                val statusH = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
                    .let { if (it > 0) ctx.resources.getDimensionPixelSize(it) else 72 }
                val tv = TextView(ctx).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(Ui.TEXT)
                    gravity = Gravity.CENTER
                    setPadding(28, 18, 28, 18)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(Ui.RAISED)
                        cornerRadius = Ui.dp(ctx, 14).toFloat()
                        setStroke(Ui.dp(ctx, 1), Ui.ACCENT)
                    }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = statusH + Ui.dp(ctx, 48)
                }
                wm.addView(tv, params)
                warnView = tv
                mainHandler.postDelayed({
                    try { wm.removeView(tv) } catch (_: Exception) { }
                    if (warnView === tv) warnView = null
                }, 5000)
            } catch (_: Exception) { }
        }
    }

    // ---------------- notification-shade pull guard ----------------

    private fun updateShadeGuard(ctx: Context, show: Boolean) {
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        if (show && guardView == null) {
            if (!Settings.canDrawOverlays(ctx)) return
            try {
                val statusH = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
                    .let { if (it > 0) ctx.resources.getDimensionPixelSize(it) else 72 }
                val strip = View(ctx).apply {
                    setOnTouchListener { _, _ -> true } // eat every touch in the strip
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    statusH + (10 * ctx.resources.displayMetrics.density).toInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP }
                wm.addView(strip, params)
                guardView = strip
            } catch (e: Exception) {
                Store.logTamper(ctx, "shade guard overlay failed: ${e.message}")
            }
        } else if (!show && guardView != null) {
            try { wm.removeView(guardView) } catch (_: Exception) { }
            guardView = null
        }
    }
}
