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
            Engine.tick(this@BlockingService)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startInForeground()
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
