package com.focuslock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADD_USER)
            // Apps allowed on the lock screen (kiosk): us + dialer + clock.
            applyLockTaskPackages(ctx)
            // Keep date/time automatic so schedule math cannot be gamed.
            try { dpm.setAutoTimeRequired(admin, true) } catch (_: Throwable) { }
        } catch (e: Throwable) {
            Store.logTamper(ctx, "applyStrictPolicies failed: ${e.message}")
        }
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
            android.os.UserManager.DISALLOW_ADD_USER
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
