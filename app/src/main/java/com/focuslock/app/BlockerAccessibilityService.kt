package com.focuslock.app

import android.accessibilityservice.AccessibilityService
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
        val type = event?.eventType ?: return
        val pkg = event.packageName?.toString() ?: return

        // v1.4 website blocking: watch the browser's URL bar (Chrome).
        if (pkg == CHROME_PKG &&
            (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            checkBlockedSite()
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

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
        if (shouldBlock) Engine.ensureLockShown(this)
    }

    private fun collapsePanelsAndLock() {
        val now = System.currentTimeMillis()
        if (now - lastShadeGuardMs > 10_000) {
            lastShadeGuardMs = now
            Store.logTamper(this, "Notification shade / panel opened during lock - collapsed")
        }
        // Home collapses the notification shade / quick-settings panel.
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) { }
        Engine.ensureLockShown(this)
    }

    // ---- v1.4: blocked websites (Chrome URL bar, read via window content) ----
    private fun checkBlockedSite() {
        val sites = Store.blockedSites(this)
        if (sites.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSiteCheckMs < 400) return
        lastSiteCheckMs = now
        val root = rootInActiveWindow ?: return
        val url = try {
            root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                .firstOrNull()?.text?.toString()?.lowercase()
        } catch (_: Exception) { null } ?: return
        val hit = sites.firstOrNull { url.contains(it) } ?: return
        Store.siteBlockHost = hit
        Store.siteBlockUntilMs = now + 3000
        if (now - lastSiteActionMs > 3000) {
            lastSiteActionMs = now
            Store.logTamper(this, "Blocked website opened in Chrome: $hit")
            // Navigate away from the page behind the lock screen.
            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) { }
        }
        Engine.ensureLockShown(this)
    }

    override fun onInterrupt() { /* nothing to interrupt; we never speak or read content */ }

    companion object {
        private const val CHROME_PKG = "com.android.chrome"
        private var lastShadeGuardMs = 0L
        private var lastSiteCheckMs = 0L
        private var lastSiteActionMs = 0L
    }
}
