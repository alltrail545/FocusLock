package com.focuslock.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget (v1.4): one-tap focus start (25 min / 1 hour) using the
 * saved study-app preset, plus a button that opens the app. Starting focus
 * from the widget follows the same rules as in-app: PIN is not asked on the
 * home screen itself (the launcher is already unlocked), and once started
 * the session cannot be cancelled early.
 */
class FocusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_focus)
            views.setOnClickPendingIntent(R.id.widget_25, actionIntent(ctx, 25))
            views.setOnClickPendingIntent(R.id.widget_60, actionIntent(ctx, 60))
            val open = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.widget_open,
                PendingIntent.getActivity(
                    ctx, 90, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            mgr.updateAppWidget(id, views)
        }
    }

    private fun actionIntent(ctx: Context, minutes: Int): PendingIntent {
        val intent = Intent(ctx, FocusWidgetActions::class.java).apply {
            action = FocusWidgetActions.ACTION_START_FOCUS
            putExtra(FocusWidgetActions.EXTRA_MINUTES, minutes)
        }
        return PendingIntent.getBroadcast(
            ctx, minutes, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class FocusWidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START_FOCUS) return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 25)
        val now = System.currentTimeMillis()
        val allowed = Store.focusDefaultAllowed(context)
        Store.setFocusStartMs(context, now)
        Store.setFocusEndMs(context, now + minutes * 60_000L)
        Store.setFocusSessionAllowed(context, allowed)
        AdminHelper.applyLockTaskPackages(context, allowed)
        Store.logTamper(context, "Focus started from widget: $minutes min")
        Engine.ensureLockShown(context)
    }

    companion object {
        const val ACTION_START_FOCUS = "com.focuslock.app.WIDGET_START_FOCUS"
        const val EXTRA_MINUTES = "minutes"
    }
}
