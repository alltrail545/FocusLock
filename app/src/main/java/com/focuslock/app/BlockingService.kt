package com.focuslock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The always-on enforcement loop (1s tick):
 *  - applies due 24h-delayed changes
 *  - refreshes app suspensions (Device Owner)
 *  - if a full lock or the foreground app is blocked, brings LockActivity forward
 * Runs as a foreground service so Android does not kill it in the background.
 * With Device Owner the app also cannot be force-stopped from Settings.
 */
class BlockingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastSuspendDay = -1
    private var lockTaskFocusEndApplied = 0L

    // ---- notification-shade pull guard (top strip overlay) ----
    private var guardView: View? = null

    /**
     * A thin touch-eating strip over the status-bar area, shown only while a
     * Full lock or Focus session is active. The pull-down gesture for the
     * notification shade / control panel starts exactly there, so consuming
     * touches in that strip stops the shade from ever opening (Stage A).
     * Stage B (Device Owner) disables the status bar itself, which is stronger;
     * this strip is the no-root, no-reset layer. Needs overlay permission.
     */
    private fun updateShadeGuard(show: Boolean) {
        val wm = getSystemService(WindowManager::class.java) ?: return
        if (show && guardView == null) {
            if (!Settings.canDrawOverlays(this)) return
            try {
                val statusH = resources.getIdentifier("status_bar_height", "dimen", "android")
                    .let { if (it > 0) resources.getDimensionPixelSize(it) else 72 }
                val strip = View(this).apply {
                    setOnTouchListener { _, _ -> true } // eat every touch in the strip
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    statusH + (10 * resources.displayMetrics.density).toInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP }
                wm.addView(strip, params)
                guardView = strip
            } catch (e: Exception) {
                Store.logTamper(this, "shade guard overlay failed: ${e.message}")
            }
        } else if (!show && guardView != null) {
            try { wm.removeView(guardView) } catch (_: Exception) { }
            guardView = null
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                enforce()
            } catch (e: Exception) {
                Store.logTamper(this@BlockingService, "enforce() failed: ${e.message}")
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun enforce() {
        Store.applyPendingIfDue(this)

        // Midnight rollover: yesterday's suspensions lift, today's limits start fresh.
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        if (today != lastSuspendDay) {
            lastSuspendDay = today
            AdminHelper.refreshSuspensions(this)
        }

        val state = Blocker.currentBlock(this)
        // Shade guard follows the hard-lock states only.
        updateShadeGuard(state is BlockState.Full || state is BlockState.FocusSession)
        when (state) {
            is BlockState.Full -> {
                AdminHelper.refreshSuspensions(this)
                bringLockForward()
            }
            is BlockState.FocusSession -> {
                // Point LockTask (Device Owner) at this session's picked apps once.
                if (lockTaskFocusEndApplied != state.untilMs) {
                    lockTaskFocusEndApplied = state.untilMs
                    AdminHelper.applyLockTaskPackages(this, state.allowed)
                }
                // Instant block — but only when a non-picked app is actually in front.
                // When a picked study app is in front, leave the user alone in it.
                val fg = Store.lastForegroundPkg
                if (state.allowed.isEmpty()) {
                    bringLockForward() // no apps picked = total lock
                } else if (fg != null && fg != packageName &&
                    !Blocker.isAllowedInFocus(this, fg, state.allowed)
                ) {
                    bringLockForward()
                }
            }
            is BlockState.AppBlocked -> {
                AdminHelper.suspendApp(this, state.pkg, true)
                bringLockForward()
            }
            BlockState.None -> {
                if (lockTaskFocusEndApplied != 0L) {
                    lockTaskFocusEndApplied = 0L
                    AdminHelper.applyLockTaskPackages(this) // back to dialer/clock only
                }
                /* LockActivity closes itself when its own tick sees None */
            }
        }
    }

    private fun bringLockForward() {
        // Do not relaunch over the emergency dialer / clock while they are in front.
        val fg = Store.lastForegroundPkg
        if (fg != null && fg in Blocker.alwaysAllowed(this) && fg != packageName) {
            // Dialer/clock are LockTask-whitelisted; during a full lock LockActivity
            // is the pinned app, so returning from the dialer lands back on the lock.
            return
        }
        startActivity(
            Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the system kills us, come straight back.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        updateShadeGuard(false)
        super.onDestroy()
        // Self-restart backstop (Device Owner also prevents force-stop entirely).
        start(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channelId = "focuslock_engine"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "FocusLock সুরক্ষা", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("FocusLock সুরক্ষা চালু আছে")
            .setContentText("নির্ধারিত সময়ে ফোন লক থাকবে।")
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
        fun start(ctx: Context) {
            val intent = Intent(ctx, BlockingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }
    }
}
