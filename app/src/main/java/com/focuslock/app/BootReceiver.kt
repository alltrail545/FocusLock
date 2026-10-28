package com.focuslock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // v1.8.0 audit: boot is the critical enforcement path — never let
        // one step's failure (e.g. the log write) skip the steps after it.
        try { Store.logTamper(context, "Boot / package replaced: ${intent.action}") } catch (_: Throwable) { }
        try { AdminHelper.applyStrictPolicies(context) } catch (_: Throwable) { }
        // v1.3: Engine starts the service only if a lock is active right now.
        try { Engine.tick(context) } catch (_: Throwable) { }
    }
}
