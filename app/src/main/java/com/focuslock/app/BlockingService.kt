package com.focuslock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Lock-time backstop service (v1.3 silent-engine redesign).
 *
 * This foreground service now runs ONLY while a lock / focus session is
 * actually active — Engine starts it when a block begins and stops it the
 * moment the block ends. While idle there is no foreground service, no
 * persistent notification and no entry in the system's "Running Apps"
 * sheet; the AccessibilityService watchdog (Engine.tick) covers the idle
 * state and starts this service the second a block begins.
 */
class BlockingService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            // v1.8.0 audit: re-post in finally, same reasoning as the
            // accessibility watchdog — one stray throw must not end the
            // 1s enforcement loop for the rest of a lock.
            try {
                Engine.tick(this@BlockingService)
            } catch (_: Throwable) { }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // v1.8.0 audit: startForeground can throw on Android 14+
        // (special-use FGS restrictions / OEM quirks); an uncaught throw
        // here killed the service in onCreate, before its first tick.
        try {
            startInForeground()
        } catch (e: Exception) {
            try { Store.logTamper(this, "startForeground failed: ${e.message}") } catch (_: Throwable) { }
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tick)
        super.onDestroy()
        // No self-restart here anymore: if a block is still active, the
        // accessibility watchdog's next Engine.tick restarts this service
        // within a second. If no block is active, staying stopped is the point.
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "focuslock_engine"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "FocusLock", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("FocusLock")
            .setContentText("লক চালু আছে")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, notif)
        }
    }

    companion object {
        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            if (isRunning) return
            val intent = Intent(ctx, BlockingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
            } catch (_: Exception) {
                // Background FGS-start restrictions: the accessibility
                // watchdog retries on its next tick, so a miss self-heals.
            }
        }

        fun stop(ctx: Context) {
            if (!isRunning) return
            ctx.stopService(Intent(ctx, BlockingService::class.java))
        }
    }
}
