package com.focuslock.app

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.Calendar

/**
 * The shared enforcement tick, v1.3 "silent engine" redesign (Tamim's
 * complaint: the XOS "Running Apps" sheet kept showing FocusLock with a
 * Stop button because the foreground service ran 24/7).
 *
 * New model:
 *  - While NO lock is active, there is NO foreground service at all —
 *    no persistent notification, no "Running Apps" card, no battery warning.
 *    The always-bound AccessibilityService runs this tick every second
 *    (accessibility services are system-bound and never appear in that UI).
 *  - The foreground service starts ONLY while a lock/focus is actually
 *    active (when the user is locked anyway) and stops itself the moment
 *    the block ends.
 */
object Engine {

    private var lastSuspendDay = -1
    private var lockTaskFocusEndApplied = 0L

    // ---- notification-shade pull guard (top strip overlay) ----
    private var guardView: View? = null

    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        try {
            enforce(app)
        } catch (e: Exception) {
            Store.logTamper(app, "engine tick failed: ${e.message}")
        }
    }

    private fun enforce(ctx: Context) {
        Store.applyPendingIfDue(ctx)

        // Midnight rollover: yesterday's suspensions lift, today's limits start fresh.
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (today != lastSuspendDay) {
            lastSuspendDay = today
            AdminHelper.refreshSuspensions(ctx)
        }

        val state = Blocker.currentBlock(ctx)
        // Shade guard follows the hard-lock states only.
        updateShadeGuard(ctx, state is BlockState.Full || state is BlockState.FocusSession)
        when (state) {
            is BlockState.Full -> {
                AdminHelper.refreshSuspensions(ctx)
                bringLockForward(ctx)
            }
            is BlockState.FocusSession -> {
                if (lockTaskFocusEndApplied != state.untilMs) {
                    lockTaskFocusEndApplied = state.untilMs
                    AdminHelper.applyLockTaskPackages(ctx, state.allowed)
                }
                val fg = Store.lastForegroundPkg
                if (state.allowed.isEmpty()) {
                    bringLockForward(ctx) // no apps picked = total lock
                } else if (fg != null && fg != ctx.packageName &&
                    !Blocker.isAllowedInFocus(ctx, fg, state.allowed)
                ) {
                    bringLockForward(ctx)
                }
            }
            is BlockState.AppBlocked -> {
                AdminHelper.suspendApp(ctx, state.pkg, true)
                bringLockForward(ctx)
            }
            BlockState.None -> {
                if (lockTaskFocusEndApplied != 0L) {
                    lockTaskFocusEndApplied = 0L
                    AdminHelper.applyLockTaskPackages(ctx) // dialer/clock only
                }
                /* LockActivity closes itself when its own tick sees None */
            }
        }

        // Silent-engine rule: the foreground service exists ONLY during a block.
        if (state == BlockState.None) BlockingService.stop(ctx) else BlockingService.start(ctx)
    }

    private fun bringLockForward(ctx: Context) {
        // Do not relaunch over the emergency dialer / clock while they are in front.
        val fg = Store.lastForegroundPkg
        if (fg != null && fg in Blocker.alwaysAllowed(ctx) && fg != ctx.packageName) {
            return
        }
        ctx.startActivity(
            Intent(ctx, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    /**
     * A thin touch-eating strip over the status-bar area, shown only while a
     * Full lock or Focus session is active. The pull-down gesture for the
     * notification shade / control panel starts exactly there, so consuming
     * touches in that strip stops the shade from ever opening (Stage A).
     * Needs overlay permission.
     */
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
