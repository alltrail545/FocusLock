package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Sees every foreground-app change instantly and slams the lock screen
 * in front of anything blocked. This is the fast path; BlockingService
 * polling is the backstop if accessibility is ever toggled off
 * (Device Owner policies + the tamper log make that visible).
 */
class BlockerAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
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
        if (shouldBlock) {
            startActivity(
                Intent(this, LockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }

    override fun onInterrupt() { /* nothing to interrupt; we never speak or read content */ }
}
