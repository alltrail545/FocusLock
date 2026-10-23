package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Sees every foreground-app change instantly and slams the lock screen
 * in front of anything blocked. This is the fast path; BlockingService
 * polling is the backstop if accessibility is ever toggled off
 * (Device Owner policies + the tamper log make that visible).
 *
 * v1.1: also guards the notification shade / control panel (SystemUI).
 * During a Full lock or a Focus session, the moment SystemUI comes forward
 * (shade pulled, control panel opened, power menu), we collapse it with a
 * Home global action and put the lock screen back in front.
 * Stage B (Device Owner) additionally disables the status bar outright.
 */
class BlockerAccessibilityService : AccessibilityService() {

    // v1.3 silent engine: this service is system-bound for as long as the
    // user keeps Accessibility on, and it never shows in the system's
    // "Running Apps" sheet. So the 1s enforcement tick lives HERE while
    // idle; Engine starts the foreground BlockingService only for the
    // duration of an actual lock/focus session.
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            Engine.tick(this@BlockerAccessibilityService)
            watchdogHandler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.post(watchdog)
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == "com.android.systemui") {
            val state = Blocker.currentBlock(this)
            if (state is BlockState.Full || state is BlockState.FocusSession) {
                collapsePanelsAndLock()
            }
            return // never record SystemUI as the foreground app
        }

        Store.lastForegroundPkg = pkg
        if (pkg == packageName) return
        if (pkg in Blocker.alwaysAllowed(this)) return

        val state = Blocker.currentBlock(this)
        val shouldBlock = when (state) {
            is BlockState.Full -> true
            // Instant block: the moment a non-picked app comes forward during focus.
            is BlockState.FocusSession -> !Blocker.isAllowedInFocus(this, pkg, state.allowed)
            is BlockState.AppBlocked -> state.pkg == pkg
            BlockState.None -> false
        }
        if (shouldBlock) bringLockForward()
    }

    private fun collapsePanelsAndLock() {
        val now = System.currentTimeMillis()
        if (now - lastShadeGuardMs > 10_000) {
            lastShadeGuardMs = now
            Store.logTamper(this, "Notification shade / panel opened during lock - collapsed")
        }
        // Home collapses the notification shade / quick-settings panel.
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) { }
        bringLockForward()
    }

    private fun bringLockForward() {
        startActivity(
            Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    override fun onInterrupt() { /* nothing to interrupt; we never speak or read content */ }

    companion object {
        private var lastShadeGuardMs = 0L
    }
}
