package com.focuslock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

        // v1.4 website blocking: watch ANY browser's URL bar (Tamim's
        // phone has several browsers). The browser set is resolved from
        // the system (every app that can open web links) + a known list.
        if (pkg in browsers() &&
            (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            checkBlockedSite(pkg)
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        if (pkg == "com.android.systemui") {
            val state = Blocker.currentBlock(this)
            if (state is BlockState.Full || state is BlockState.FocusSession) {
                collapsePanelsAndLock()
            }
            return // never record SystemUI as the foreground app
        }

        // v1.4.1 flicker fix: NEVER record our own package as "the foreground
        // app". The moment the lock screen opened, this line used to overwrite
        // the blocked app with com.focuslock.app; Blocker then saw an allowed
        // app in front, returned None, and the lock closed itself — blocked
        // app back in front, lock again, forever (Tamim's video). From now on
        // lastForegroundPkg is the last EXTERNAL app, so a block stays latched
        // until the user genuinely leaves for another app / the launcher.
        if (pkg == packageName) return
        Store.lastForegroundPkg = pkg
        if (pkg in Blocker.alwaysAllowed(this)) return

        val state = Blocker.currentBlock(this)
        val shouldBlock = when (state) {
            is BlockState.Full -> true
            // Instant block: the moment a non-picked app comes forward during focus.
            is BlockState.FocusSession -> !Blocker.isAllowedInFocus(this, pkg, state.allowed)
            is BlockState.AppBlocked -> state.pkg == pkg
            // A blocked website is showing: keep the lock in front (the
            // 3-second state renews only while the page stays detected).
            is BlockState.SiteBlocked -> true
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

    // ---- v1.4: blocked websites, ANY browser ----
    // URL extraction is layered: (1) known URL-bar view ids per browser,
    // (2) a generic tree scan for a node whose view id mentions url /
    // omnibox / location, or an address-field holding a domain-like text.
    private fun checkBlockedSite(browserPkg: String) {
        val sites = Store.blockedSites(this)
        // v1.6.0: enabled category filters contribute their domains too —
        // matched by host so lookalike domains cannot slip through (or
        // innocent ones get caught) by substring accidents.
        val catDomains = SiteCategories.domainsFor(Store.siteCategories(this))
        if (sites.isEmpty() && catDomains.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSiteCheckMs < 400) return
        lastSiteCheckMs = now
        val root = rootInActiveWindow ?: return
        val url = extractUrl(root) ?: return
        val hit = sites.firstOrNull { url.contains(it) }
            ?: catDomains.firstOrNull { SiteCategories.hostMatches(url, it) }
            ?: return
        Store.siteBlockHost = hit
        Store.siteBlockPkg = browserPkg
        Store.siteBlockUntilMs = now + 3000
        if (now - lastSiteActionMs > 3000) {
            lastSiteActionMs = now
            Store.logTamper(this, "Blocked website opened in a browser: $hit")
            // Navigate away from the page behind the lock screen.
            try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) { }
        }
        Engine.ensureLockShown(this)
    }

    private fun extractUrl(root: AccessibilityNodeInfo): String? {
        for (id in URL_BAR_IDS) {
            try {
                val text = root.findAccessibilityNodeInfosByViewId(id)
                    .firstOrNull()?.text?.toString()?.trim()?.lowercase()
                if (!text.isNullOrBlank()) return text
            } catch (_: Exception) { }
        }
        return scanForUrl(root, 0)
    }

    private val domainLike =
        Regex("""^[a-z0-9][a-z0-9.\-]*\.[a-z]{2,}([/?#:]\S*)?$""")

    private fun scanForUrl(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 14) return null
        try {
            val idName = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim()?.lowercase()
            if (!text.isNullOrBlank() && text.length < 300 && !text.contains(" ")) {
                val idSaysUrl = idName.contains("url") || idName.contains("omnibox") ||
                    idName.contains("location")
                if (idSaysUrl && text.contains(".")) return text
                val isEdit = node.className?.toString()?.contains("EditText") == true
                if (isEdit && domainLike.matches(text)) return text
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = scanForUrl(child, depth + 1)
                if (found != null) return found
            }
        } catch (_: Exception) { }
        return null
    }

    /** Every app on this phone that can open a web link, plus known browsers. */
    private fun browsers(): Set<String> {
        val now = System.currentTimeMillis()
        if (browserPkgs.isNotEmpty() && now - browserPkgsAt < 60_000) return browserPkgs
        browserPkgsAt = now
        browserPkgs = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }.toSet() + KNOWN_BROWSERS - packageName
        } catch (_: Exception) {
            KNOWN_BROWSERS
        }
        return browserPkgs
    }

    override fun onInterrupt() { /* nothing to interrupt; we never speak or read content */ }

    companion object {
        private var lastShadeGuardMs = 0L
        private var lastSiteCheckMs = 0L
        private var lastSiteActionMs = 0L

        @Volatile private var browserPkgs: Set<String> = emptySet()
        @Volatile private var browserPkgsAt = 0L

        private val KNOWN_BROWSERS = setOf(
            "com.android.chrome", "com.brave.browser", "com.microsoft.emmx",
            "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native",
            "org.mozilla.firefox", "com.transsion.phoenix", "com.kiwibrowser.browser",
            "com.vivaldi.browser", "com.duckduckgo.mobile.android", "com.UCMobile.intl"
        )

        private val URL_BAR_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.microsoft.emmx:id/url_bar",
            "com.kiwibrowser.browser:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.transsion.phoenix:id/url_bar"
        )
    }
}
