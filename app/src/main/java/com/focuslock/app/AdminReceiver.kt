package com.focuslock.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Store.logTamper(context, "Device Admin enabled")
        AdminHelper.applyStrictPolicies(context)
        BlockingService.start(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        AdminHelper.applyStrictPolicies(context)
        BlockingService.start(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Store.logTamper(context, "Someone tried to DISABLE Device Admin")
        return "FocusLock বন্ধ করা যাবে না। অ্যাপের ভেতর থেকে PIN দিয়ে ২৪ ঘণ্টা অপেক্ষা করে Protection Off করতে হয়।"
    }
}
