package com.focuslock.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Store.logTamper(context, "Device Admin enabled")
        AdminHelper.applyStrictPolicies(context)
        Engine.tick(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        AdminHelper.applyStrictPolicies(context)
        Engine.tick(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Store.logTamper(context, "Someone tried to DISABLE Device Admin")
        return "FocusLock বন্ধ করা যাবে না। অ্যাপের ভেতর থেকে PIN দিয়ে ২৪ ঘণ্টা অপেক্ষা করে Protection Off করতে হয়।"
    }

    // ---- v1.12.0: security / network log availability (tamper evidence) ----
    // The system calls these when new log material exists. We only record
    // THAT it arrived (and the network batch token — a batch can be
    // retrieved exactly once, with exactly this token); the logs
    // themselves are read on demand from Settings, never in the callback.

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        super.onSecurityLogsAvailable(context, intent)
        try {
            Store.setSecurityLogsAvailableAt(context, System.currentTimeMillis())
            Store.logTamper(context, "Security logs available")
        } catch (_: Throwable) { }
    }

    override fun onNetworkLogsAvailable(
        context: Context,
        intent: Intent,
        batchToken: Long,
        networkLogsCount: Int
    ) {
        super.onNetworkLogsAvailable(context, intent, batchToken, networkLogsCount)
        try {
            Store.setNetworkLogBatchToken(context, batchToken)
            Store.setNetworkLogsAvailableAt(context, System.currentTimeMillis())
            Store.logTamper(context, "Network logs available: $networkLogsCount events (batch $batchToken)")
        } catch (_: Throwable) { }
    }
}
