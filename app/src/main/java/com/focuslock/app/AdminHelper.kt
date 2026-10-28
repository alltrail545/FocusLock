package com.focuslock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * Device Owner is what makes this app truly strict. Set once over ADB:
 *   adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver
 * (Phone must have NO Google/other account added yet, or be factory-reset fresh.)
 *
 * With Device Owner we can:
 *  - block uninstall of FocusLock itself
 *  - block safe boot, factory reset from Settings, date/time changes, USB debugging
 *  - suspend apps that cross their daily limit (they look disabled until tomorrow)
 *  - pin the phone into LockTask (kiosk) mode during a full lock
 * Without it, the accessibility + overlay layers still block — but a determined
 * user can uninstall the app. That is exactly why Tamim chose the strict mode.
 */
object AdminHelper {

    fun adminComponent(ctx: Context): ComponentName =
        ComponentName(ctx, AdminReceiver::class.java)

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(ctx: Context): Boolean =
        try { dpm(ctx).isDeviceOwnerApp(ctx.packageName) } catch (e: Exception) { false }

    fun isAdminActive(ctx: Context): Boolean =
        try { dpm(ctx).isAdminActive(adminComponent(ctx)) } catch (e: Exception) { false }

    /** Default system dialer package (emergency calls stay reachable during a lock). */
    fun dialerPackage(ctx: Context): String? =
        try { (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage }
        catch (e: Exception) { null }

    fun clockPackages(ctx: Context): List<String> {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
        val pkgs = ctx.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
        return pkgs.ifEmpty { listOf("com.google.android.deskclock", "com.android.deskclock") }
    }

    /** Apply all standing strict policies. Safe to call repeatedly; no-ops without Device Owner. */
    fun applyStrictPolicies(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = adminComponent(ctx)
        try {
            // This app cannot be uninstalled, force-stopped or cleared while owner is set.
            dpm.setUninstallBlocked(admin, ctx.packageName, true)
            // The classic bypass routes, all closed:
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CONFIG_DATE_TIME)
            // NOTE (Tamim's call, v1.5.2): DISALLOW_DEBUGGING_FEATURES is
            // deliberately NOT set. Tamim runs Shizuku + Termux + Phone Link
            // on debugging access, and activation itself is done from Termux.
            // The in-app exit (PIN + 24h) never needed ADB, so debugging
            // stays available for his own tooling.
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADD_USER)
            // Apps allowed on the lock screen (kiosk): us + dialer + clock.
            applyLockTaskPackages(ctx)
            // Keep date/time automatic so schedule math cannot be gamed.
            try { dpm.setAutoTimeRequired(admin, true) } catch (_: Throwable) { }
        } catch (e: Throwable) {
            Store.logTamper(ctx, "applyStrictPolicies failed: ${e.message}")
        }
        // v1.9.0: standing policies now include Chrome's managed config.
        syncBrowserRestrictions(ctx)
        // v1.10.0: standing policies also include the install block and
        // the per-app uninstall protections (forced re-apply here; the
        // per-tick path inside is state-guarded).
        applyInstallControls(ctx, force = true)
        // v1.11.0: and the filtered Private DNS, if Tamim enabled it
        // (forced re-apply; the network-settings lock is NOT a standing
        // policy — it follows the live lock state in Engine's tick).
        applyDnsFilter(ctx, force = true)
    }

    /**
     * LockTask whitelist = FocusLock + dialer + clock + [extra]
     * (during a focus session, [extra] is Tamim's picked study apps, so only
     * those can run inside kiosk mode and Android itself refuses the rest).
     */
    fun applyLockTaskPackages(ctx: Context, extra: Set<String> = emptySet()) {
        if (!isDeviceOwner(ctx)) return
        try {
            val allowed = (listOf(ctx.packageName) +
                listOfNotNull(dialerPackage(ctx)) + clockPackages(ctx) + extra)
                .distinct().toTypedArray()
            dpm(ctx).setLockTaskPackages(adminComponent(ctx), allowed)
        } catch (e: Throwable) {
            Store.logTamper(ctx, "applyLockTaskPackages failed: ${e.message}")
        }
    }

    // ---- v1.9.0: Chrome managed configuration (Device Owner only) ----

    /** The one browser whose managed configuration we drive in this version. */
    const val CHROME_PACKAGE = "com.android.chrome"

    /** Chrome's URL lists are documented to accept at most 1000 entries. */
    private const val MAX_URL_LIST_ENTRIES = 1000

    /**
     * Plain-host form for Chrome's URL lists: scheme/path/port/userinfo and
     * a leading "www." come off the same way [SiteCategories.hostOf]
     * normalises a live URL host. Blank or dot-less leftovers are dropped —
     * they can never be valid hosts for Chrome's lists.
     */
    private fun chromeHost(raw: String): String? {
        val host = SiteCategories.hostOf(raw).removePrefix("www.")
        return host.takeIf { it.isNotBlank() && it.contains(".") }
    }

    private fun isPackageInstalled(ctx: Context, pkg: String): Boolean =
        try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Throwable) { false }

    /**
     * Browser-level managed blocking: an ADDITIONAL layer on top of the
     * accessibility URL blocking (which keeps working exactly as before,
     * for every browser). When this app is Device Owner, Chrome itself is
     * told — via its managed configuration — to disable Incognito, force
     * Google SafeSearch, and honour our URL block/allow lists natively,
     * so a blocked site never even loads and no URL-bar reading is needed
     * for Chrome.
     *
     * The lists come from the SAME stores the accessibility path uses
     * (blocked sites + enabled category domains; the allow list), whose
     * weakening already follows the PIN + 24h rule — this function adds no
     * new way to weaken anything: it only ever re-applies what the lists
     * currently say. Allow-only mode maps onto Chrome as a "*" blocklist
     * entry, which Chrome's URLAllowlist then carves exceptions out of —
     * the exact mirror of the accessibility behaviour.
     *
     * Everything is built from the current lists on each call, but the
     * DPM write happens only when the content signature changes (see
     * [Store.chromeRestrictionsSignature]), so Engine.tick can call this
     * every second safely. Device Owner absent, or Chrome absent: silent
     * no-op — the restrictions, if ever applied, are never cleared from a
     * non-owner state. Any failure is tamper-logged at most once per
     * change and never crashes or weakens enforcement.
     */
    fun syncBrowserRestrictions(ctx: Context) {
        var signature: String? = null
        try {
            if (!isDeviceOwner(ctx)) return
            if (!isPackageInstalled(ctx, CHROME_PACKAGE)) return

            val block = LinkedHashSet<String>()
            if (Store.siteAllowOnly(ctx)) block += "*"
            for (site in Store.blockedSites(ctx)) chromeHost(site)?.let { block += it }
            for (domain in SiteCategories.domainsFor(Store.siteCategories(ctx))) {
                chromeHost(domain)?.let { block += it }
            }
            val blockList = block.take(MAX_URL_LIST_ENTRIES)
            val allowList = Store.siteAllowList(ctx)
                .mapNotNull { chromeHost(it) }
                .distinct()
                .take(MAX_URL_LIST_ENTRIES)

            // Content signature from the list hashCodes (List.hashCode is
            // content-based) — stable across processes for equal contents,
            // different as soon as any entry is added, removed or reordered.
            val sig = "v1:${blockList.hashCode()}:${blockList.size}:" +
                "${allowList.hashCode()}:${allowList.size}:" +
                "${Store.siteAllowOnly(ctx)}"
            signature = sig
            if (Store.chromeRestrictionsSignature(ctx) == sig) return

            val bundle = Bundle().apply {
                putInt("IncognitoModeAvailability", 1) // 1 = incognito disabled
                putBoolean("ForceGoogleSafeSearch", true)
                putStringArray("URLBlocklist", blockList.toTypedArray())
                putStringArray("URLAllowlist", allowList.toTypedArray())
            }
            dpm(ctx).setApplicationRestrictions(adminComponent(ctx), CHROME_PACKAGE, bundle)
            Store.setChromeRestrictionsSignature(ctx, sig)
            Store.setChromeRestrictionsFailedSignature(ctx, null)
        } catch (e: Throwable) {
            // Log at most once per failing change — never once per tick.
            try {
                val key = signature ?: "uncomputed"
                if (Store.chromeRestrictionsFailedSignature(ctx) != key) {
                    Store.setChromeRestrictionsFailedSignature(ctx, key)
                    Store.logTamper(ctx, "syncBrowserRestrictions failed: ${e.message}")
                }
            } catch (_: Throwable) { }
        }
    }

    // ---- v1.10.0: install block + per-app uninstall protection ----

    /** Both v1.10.0 Device Owner controls in one call. Safe anywhere:
     *  no-op without Device Owner, every failure caught and tamper-logged,
     *  and — thanks to the applied-state tracking in [Store] — no DPM
     *  calls at all while nothing has changed, so the 1s engine tick can
     *  call this freely. [force] re-applies unconditionally (used by
     *  [applyStrictPolicies]: app start, owner verification). */
    fun applyInstallControls(ctx: Context, force: Boolean = false) {
        applyInstallBlock(ctx, force)
        applyUninstallProtections(ctx, force)
    }

    /**
     * Global install block: while [Store.installBlockOn] is set, the user
     * cannot install ANY app — Play Store, other stores, or sideloaded
     * APKs. Both restrictions go on/off together; exactly these two are
     * cleared when the block is off, nothing else. The applied marker is
     * written only after the DPM calls succeed, so a failure is retried
     * on the next call instead of being mistaken for done.
     */
    fun applyInstallBlock(ctx: Context, force: Boolean = false) {
        try {
            if (!isDeviceOwner(ctx)) return
            val want = Store.installBlockOn(ctx)
            if (!force && Store.installBlockApplied(ctx) == want) return
            val dpm = dpm(ctx)
            val admin = adminComponent(ctx)
            if (want) {
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            } else {
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }
            Store.setInstallBlockApplied(ctx, want)
        } catch (e: Throwable) {
            // Marker deliberately untouched: the change is retried later,
            // never silently dropped and never crashes enforcement.
            try { Store.logTamper(ctx, "applyInstallBlock failed: ${e.message}") } catch (_: Throwable) { }
        }
    }

    /**
     * Per-app uninstall protection: every package in
     * [Store.uninstallProtectedPkgs] gets setUninstallBlocked(true);
     * packages that leave the set (removal applies only after the PIN +
     * 24h pending) get it lifted again. Only the DIFF against the
     * verifiably-applied set is pushed to DPM, and each push is verified
     * with isUninstallBlocked before it is recorded as applied.
     * FocusLock itself and the system-critical packages are excluded —
     * the owner app is already uninstall-blocked, and blocking Settings /
     * launcher / dialer / clock / installer uninstall is meaningless or
     * dangerous.
     */
    fun applyUninstallProtections(ctx: Context, force: Boolean = false) {
        try {
            if (!isDeviceOwner(ctx)) return
            val dpm = dpm(ctx)
            val admin = adminComponent(ctx)
            val desired = Store.uninstallProtectedPkgs(ctx)
                .filter { it != ctx.packageName && !isProtectedPackage(ctx, it) }
                .toSet()
            var applied = Store.uninstallProtectionsAppliedPkgs(ctx)
            // Lift protection that is no longer wanted.
            for (pkg in (applied - desired).toList()) {
                if (setUninstallBlockedVerified(ctx, dpm, admin, pkg, false)) {
                    applied = applied - pkg
                }
            }
            // Apply protection that is wanted but not yet verifiably on.
            val toBlock = if (force) desired else desired - applied
            for (pkg in toBlock) {
                if (setUninstallBlockedVerified(ctx, dpm, admin, pkg, true)) {
                    applied = applied + pkg
                }
            }
            if (applied != Store.uninstallProtectionsAppliedPkgs(ctx)) {
                Store.saveUninstallProtectionsAppliedPkgs(ctx, applied)
            }
        } catch (e: Throwable) {
            try { Store.logTamper(ctx, "applyUninstallProtections failed: ${e.message}") } catch (_: Throwable) { }
        }
    }

    /** setUninstallBlocked + read-back verification, in one guarded step.
     *  A package that is no longer installed holds no uninstall state, so
     *  it counts as done either way (nothing to protect, nothing to lift)
     *  instead of failing and retrying forever. */
    private fun setUninstallBlockedVerified(
        ctx: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        pkg: String,
        blocked: Boolean
    ): Boolean {
        if (!isPackageInstalled(ctx, pkg)) return true
        return try {
            dpm.setUninstallBlocked(admin, pkg, blocked)
            dpm.isUninstallBlocked(admin, pkg) == blocked
        } catch (e: Throwable) {
            try {
                Store.logTamper(ctx, "setUninstallBlocked($pkg, $blocked) failed: ${e.message}")
            } catch (_: Throwable) { }
            false
        }
    }

    // ---- v1.11.0: filtered Private DNS + network-settings lock ----

    /** Cloudflare's family filter: adult + malware domains fail to resolve
     *  at DNS level, for EVERY app on the phone — not just Chrome. */
    const val DNS_FILTER_HOST = "family.cloudflare-dns.com"

    // Settings.Global keys for Android Private DNS. The literals are used
    // (they are exactly the values of Settings.Global.PRIVATE_DNS_MODE /
    // PRIVATE_DNS_SPECIFIER) so this compiles against any SDK stub.
    private const val KEY_PRIVATE_DNS_MODE = "private_dns_mode"
    private const val KEY_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"
    private const val DNS_MODE_HOSTNAME = "hostname"
    private const val DNS_MODE_OFF = "opportunistic"

    /**
     * Filtered DNS, always-on while [Store.dnsFilterOn] is set: as Device
     * Owner we point Android's Private DNS at [DNS_FILTER_HOST] via
     * setGlobalSetting, which the user then cannot change in Settings.
     * OFF resets to the platform default (opportunistic, no fixed host).
     *
     * The write is verified by reading Settings.Global back; the applied
     * marker in [Store] is written ONLY on a verified match, so a failed
     * or partial write is retried on the next engine tick instead of being
     * mistaken for done — and an unchanged state costs zero DPM calls.
     * No-op without Device Owner; every failure is caught + tamper-logged.
     */
    fun applyDnsFilter(ctx: Context, force: Boolean = false) {
        try {
            if (!isDeviceOwner(ctx)) return
            val want = Store.dnsFilterOn(ctx)
            if (!force && Store.dnsFilterApplied(ctx) == want) return
            val dpm = dpm(ctx)
            val admin = adminComponent(ctx)
            val mode = if (want) DNS_MODE_HOSTNAME else DNS_MODE_OFF
            val spec = if (want) DNS_FILTER_HOST else ""
            dpm.setGlobalSetting(admin, KEY_PRIVATE_DNS_MODE, mode)
            dpm.setGlobalSetting(admin, KEY_PRIVATE_DNS_SPECIFIER, spec)
            val cr = ctx.contentResolver
            val gotMode = try { Settings.Global.getString(cr, KEY_PRIVATE_DNS_MODE) } catch (_: Throwable) { null }
            val gotSpec = try { Settings.Global.getString(cr, KEY_PRIVATE_DNS_SPECIFIER) } catch (_: Throwable) { null }
            if (gotMode == mode && (gotSpec ?: "") == spec) {
                Store.setDnsFilterApplied(ctx, want)
            } else {
                // Marker deliberately untouched: retried on the next tick.
                try {
                    Store.logTamper(ctx, "applyDnsFilter verify mismatch: want $mode/$spec, got $gotMode/$gotSpec")
                } catch (_: Throwable) { }
            }
        } catch (e: Throwable) {
            try { Store.logTamper(ctx, "applyDnsFilter failed: ${e.message}") } catch (_: Throwable) { }
        }
    }

    /**
     * The three network restrictions of the v1.11.0 lock. The airplane-mode
     * entry is the literal restriction key "no_airplane_mode":
     * addUserRestriction takes the UserManager constant STRING itself, and
     * that literal is exactly the value of the airplane-mode restriction
     * key — identical on the wire either way.
     *
     * NOTE (intended strictness): DISALLOW_CONFIG_WIFI blocks CHANGING
     * WiFi configuration — which includes joining a NEW network while the
     * lock runs. It does not switch WiFi or mobile data off; the current
     * connection keeps working, it just cannot be changed mid-lock.
     */
    private val NETWORK_LOCK_RESTRICTIONS = listOf(
        android.os.UserManager.DISALLOW_CONFIG_WIFI,
        android.os.UserManager.DISALLOW_CONFIG_TETHERING,
        "no_airplane_mode"
    )

    /**
     * Network-settings lock during hard locks: while [hardLock] (a Full
     * schedule lock or a focus session) AND [Store.networkLockOn], the
     * user cannot change WiFi / tethering / airplane-mode settings — three
     * classic mid-lock escape routes. The moment the state leaves hard
     * lock, exactly those three are lifted again.
     *
     * Applied state lives in a PERSISTED Store marker: if the process
     * dies mid-lock, the next tick still knows the restrictions may be in
     * force and lifts/restores them correctly (the v1.8.0 DND lesson).
     * Verification reads the restrictions back through UserManager; the
     * marker moves only on a verified match. No-op without Device Owner.
     */
    fun applyNetworkLock(ctx: Context, hardLock: Boolean) {
        try {
            if (!isDeviceOwner(ctx)) return
            val want = hardLock && Store.networkLockOn(ctx)
            if (Store.networkLockApplied(ctx) == want) return
            val dpm = dpm(ctx)
            val admin = adminComponent(ctx)
            if (want) {
                for (r in NETWORK_LOCK_RESTRICTIONS) dpm.addUserRestriction(admin, r)
            } else {
                for (r in NETWORK_LOCK_RESTRICTIONS) dpm.clearUserRestriction(admin, r)
            }
            val um = ctx.getSystemService(android.os.UserManager::class.java)
            val verified = NETWORK_LOCK_RESTRICTIONS.all { r ->
                (try { um?.hasUserRestriction(r) == true } catch (_: Throwable) { false }) == want
            }
            if (verified) {
                Store.setNetworkLockApplied(ctx, want)
            } else {
                // Marker deliberately untouched: retried on the next tick.
                try {
                    Store.logTamper(ctx, "applyNetworkLock verify mismatch: want=$want")
                } catch (_: Throwable) { }
            }
        } catch (e: Throwable) {
            try { Store.logTamper(ctx, "applyNetworkLock failed: ${e.message}") } catch (_: Throwable) { }
        }
    }

    // ---- v1.5.1 safety: packages that must NEVER be suspended ----
    // Suspending one of these in Device Owner mode could strand the phone:
    // no launcher to go home to, no Settings to recover with, no dialer.
    // The audit Tamim brought was right about this hole.
    private val protectedPackages = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.shell",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.termux" // recovery tool — never take it away
    )

    private var homePkgCache: String? = null
    private fun homePackage(ctx: Context): String? {
        if (homePkgCache != null) return homePkgCache
        homePkgCache = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        } catch (_: Exception) { null }
        return homePkgCache
    }

    fun isProtectedPackage(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName) return true
        if (pkg in protectedPackages) return true
        // Whatever THIS phone uses as launcher / dialer / clock is protected,
        // whatever its OEM package name happens to be.
        if (pkg == homePackage(ctx)) return true
        if (pkg == dialerPackage(ctx)) return true
        if (pkg in clockPackages(ctx)) return true
        return false
    }

    /** Suspend (temporarily disable) an app that crossed its daily limit. Device Owner only. */
    fun suspendApp(ctx: Context, pkg: String, suspended: Boolean) {
        if (!isDeviceOwner(ctx)) return
        if (isProtectedPackage(ctx, pkg)) {
            Store.logTamper(ctx, "Refused to suspend protected package: $pkg")
            return
        }
        try {
            val failed = dpm(ctx).setPackagesSuspended(adminComponent(ctx), arrayOf(pkg), suspended)
            if (failed.isEmpty()) {
                // Ledger: the emergency exit restores from this list, so a
                // suspension can never be orphaned by a deleted limit.
                if (suspended) Store.ledgerAdd(ctx, pkg) else Store.ledgerRemove(ctx, pkg)
            } else {
                Store.logTamper(ctx, "suspendApp($pkg, $suspended) failed for: ${failed.joinToString()}")
            }
        } catch (e: Exception) {
            Store.logTamper(ctx, "suspendApp($pkg) failed: ${e.message}")
        }
    }

    /**
     * Emergency exit (v1.5, Stage B; v1.5.1 VERIFIED cleanup): remove
     * Device Owner safely. The rule, from Tamim's safety audit: ownership
     * is revoked ONLY after every critical cleanup is verified done —
     * restrictions actually off, uninstall-block actually lifted, every
     * managed package actually restored. Any failure ABORTS the exit
     * (owner stays, so a retry is possible) and is surfaced in the UI via
     * Store's exit-failed flag, never just swallowed.
     *
     * Order: status bar + LockTask whitelist → restrictions (verified) →
     * own uninstall-block (verified) → packages from the persistent
     * ledger ∪ current limits (verified per package) → clearDeviceOwnerApp
     * (verified) → removeActiveAdmin.
     * Returns true only when the device is verifiably owner-free.
     */
    fun safelyRemoveDeviceOwner(ctx: Context): Boolean {
        val dpm = dpm(ctx)
        val admin = adminComponent(ctx)
        if (!isDeviceOwner(ctx)) {
            if (isAdminActive(ctx)) {
                try { dpm.removeActiveAdmin(admin) } catch (_: Throwable) { }
                Store.logTamper(ctx, "Device Admin removed (was not owner)")
            }
            return true
        }

        // Stage 1: release the visible holds (verified indirectly below).
        try { dpm.setStatusBarDisabled(admin, false) } catch (_: Throwable) { }
        try { dpm.setLockTaskPackages(admin, emptyArray()) } catch (_: Throwable) { }

        // Stage 2: clear every restriction we set — then VERIFY none remains.
        val restrictions = listOf(
            android.os.UserManager.DISALLOW_SAFE_BOOT,
            android.os.UserManager.DISALLOW_FACTORY_RESET,
            android.os.UserManager.DISALLOW_CONFIG_DATE_TIME,
            android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
            android.os.UserManager.DISALLOW_ADD_USER,
            // v1.10.0: the install block must not survive owner removal.
            android.os.UserManager.DISALLOW_INSTALL_APPS,
            android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            // v1.11.0: nor may the network-settings lock (same literal
            // airplane-mode key as NETWORK_LOCK_RESTRICTIONS).
            android.os.UserManager.DISALLOW_CONFIG_WIFI,
            android.os.UserManager.DISALLOW_CONFIG_TETHERING,
            "no_airplane_mode"
        )
        for (r in restrictions) {
            try { dpm.clearUserRestriction(admin, r) } catch (_: Throwable) { }
        }
        val um = ctx.getSystemService(android.os.UserManager::class.java)
        val stillOn = restrictions.filter {
            try { um?.hasUserRestriction(it) == true } catch (_: Throwable) { false }
        }
        if (stillOn.isNotEmpty()) {
            Store.logTamper(ctx, "Emergency exit ABORTED — restrictions still active: $stillOn")
            return false
        }

        // Stage 3: lift our own uninstall-block — VERIFIED by state.
        try { dpm.setUninstallBlocked(admin, ctx.packageName, false) } catch (_: Throwable) { }
        val uninstallStillBlocked =
            try { dpm.isUninstallBlocked(admin, ctx.packageName) } catch (_: Throwable) { false }
        if (uninstallStillBlocked) {
            Store.logTamper(ctx, "Emergency exit ABORTED — uninstall still blocked")
            return false
        }

        // Stage 3b (v1.10.0): lift every per-app uninstall protection —
        // VERIFIED per package, same abort rule: a protected app left
        // blocked with no owner would be stranded.
        val protectedPkgs =
            (Store.uninstallProtectedPkgs(ctx) + Store.uninstallProtectionsAppliedPkgs(ctx)).distinct()
        for (pkg in protectedPkgs) {
            try { dpm.setUninstallBlocked(admin, pkg, false) } catch (_: Throwable) { }
            val stillBlocked =
                try { dpm.isUninstallBlocked(admin, pkg) } catch (_: Throwable) { false }
            if (stillBlocked) {
                Store.logTamper(ctx, "Emergency exit ABORTED — uninstall protection still on for $pkg")
                return false
            }
        }

        // Stage 3c (v1.11.0): reset Private DNS to the platform default —
        // VERIFIED by read-back, same abort rule: an ownerless phone must
        // not be stranded on a DNS setting the user can no longer change.
        try {
            dpm.setGlobalSetting(admin, KEY_PRIVATE_DNS_MODE, DNS_MODE_OFF)
            dpm.setGlobalSetting(admin, KEY_PRIVATE_DNS_SPECIFIER, "")
        } catch (_: Throwable) { }
        val dnsModeNow = try {
            Settings.Global.getString(ctx.contentResolver, KEY_PRIVATE_DNS_MODE)
        } catch (_: Throwable) { null }
        val dnsSpecNow = try {
            Settings.Global.getString(ctx.contentResolver, KEY_PRIVATE_DNS_SPECIFIER)
        } catch (_: Throwable) { null }
        if (dnsModeNow != DNS_MODE_OFF || (dnsSpecNow ?: "") != "") {
            Store.logTamper(ctx, "Emergency exit ABORTED — Private DNS not reset (mode=$dnsModeNow, spec=$dnsSpecNow)")
            return false
        }

        // Stage 4: restore every managed package (ledger ∪ current limits),
        // VERIFIED per package. A package that cannot be restored aborts the
        // exit — leaving it suspended/hidden with no owner would strand it.
        val pkgs = (Store.ledgerPackages(ctx) + Store.appLimits(ctx).map { it.pkg }).distinct()
        for (pkg in pkgs) {
            val failed = try {
                dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
            } catch (_: Throwable) { arrayOf(pkg) }
            try { dpm.setApplicationHidden(admin, pkg, false) } catch (_: Throwable) { }
            val stillHidden =
                try { dpm.isApplicationHidden(admin, pkg) } catch (_: Throwable) { false }
            if (failed.isNotEmpty() || stillHidden) {
                Store.logTamper(ctx, "Emergency exit ABORTED — could not restore $pkg")
                return false
            }
            Store.ledgerRemove(ctx, pkg)
        }

        // Stage 5: only now revoke ownership — VERIFIED.
        try { dpm.clearDeviceOwnerApp(ctx.packageName) } catch (_: Throwable) { }
        if (isDeviceOwner(ctx)) {
            Store.logTamper(ctx, "Emergency exit ABORTED — clearDeviceOwnerApp did not take effect")
            return false
        }
        try { dpm.removeActiveAdmin(admin) } catch (_: Throwable) { }
        // v1.10.0: the DPM-side install controls are verifiably gone, so
        // forget that they were ever applied. The DESIRED settings
        // (installBlockOn, the protected list) stay saved — if Device
        // Owner is ever set again, applyStrictPolicies re-applies them.
        try {
            Store.setInstallBlockApplied(ctx, null)
            Store.saveUninstallProtectionsAppliedPkgs(ctx, emptySet())
            // v1.11.0: same for the DNS / network markers — both verifiably
            // gone above (Stage 2 + Stage 3c). The DESIRED settings
            // (dnsFilterOn, networkLockOn) stay saved, like v1.10.0's.
            Store.setDnsFilterApplied(ctx, null)
            Store.setNetworkLockApplied(ctx, false)
        } catch (_: Throwable) { }
        Store.logTamper(ctx, "Device Owner removed via in-app emergency exit (verified cleanup)")
        return true
    }

    /** At midnight rollover, un-suspend everything whose limit has reset. */
    fun refreshSuspensions(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        for (limit in Store.appLimits(ctx)) {
            val over = Blocker.usageTodayMs(ctx, limit.pkg) / 60000 >= limit.limitMinutes
            suspendApp(ctx, limit.pkg, over)
        }
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val mode = (ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager)
                .unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), ctx.packageName
                )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) { false }
    }

    fun hasOverlayPermission(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun isAccessibilityOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("${ctx.packageName}/${BlockerAccessibilityService::class.java.name}")
    }
}
