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

    /** Suspend (temporarily disable) an app that crossed its daily limit. Device Owner only. */
    fun suspendApp(ctx: Context, pkg: String, suspended: Boolean) {
        if (!isDeviceOwner(ctx)) return
        try {
            dpm(ctx).setPackagesSuspended(adminComponent(ctx), arrayOf(pkg), suspended)
        } catch (e: Exception) {
            Store.logTamper(ctx, "suspendApp($pkg) failed: ${e.message}")
        }
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
