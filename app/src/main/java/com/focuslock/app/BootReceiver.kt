package com.focuslock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.logTamper(context, "Boot / package replaced: ${intent.action}")
        AdminHelper.applyStrictPolicies(context)
        // v1.3: Engine starts the service only if a lock is active right now.
        Engine.tick(context)
    }
}
