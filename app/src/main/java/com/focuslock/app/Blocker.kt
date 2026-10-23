package com.focuslock.app

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

sealed class BlockState {
    object None : BlockState()
    /** Whole phone locked until [untilMs]. [reason] is shown on the lock screen. */
    data class Full(val untilMs: Long, val reason: String) : BlockState()
    /**
     * Focus session running: ONLY [allowed] apps (plus dialer/clock) may be used.
     * Anything else is blocked the instant it comes to the foreground.
     * An empty [allowed] set means a total lock, exactly like [Full].
     */
    data class FocusSession(val untilMs: Long, val allowed: Set<String>) : BlockState()
    /** One app over its daily limit. */
    data class AppBlocked(val pkg: String, val label: String) : BlockState()
}

/** Pure decision engine: given the clock + stored rules + today's usage, should we block? */
object Blocker {

    /** Packages that always work, even in a full lock: calls and alarms are safety, not entertainment. */
    fun alwaysAllowed(ctx: Context): Set<String> {
        val set = mutableSetOf(ctx.packageName)
        // System dialer / clock are resolved at runtime in AdminHelper + LockActivity;
        // here we also whitelist the common system packages defensively.
        set += listOf(
            "com.android.dialer", "com.google.android.dialer",
            "com.android.incallui", "com.samsung.android.dialer",
            "com.google.android.deskclock", "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.android.systemui"
            // NOTE: Settings is deliberately NOT allowed — in Stage A (no Device Owner)
            // an open Settings screen would be a bypass route, so it gets blocked too.
        )
        return set
    }

    fun minutesOfDay(now: Calendar): Int = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    /** Calendar.MONDAY=2 ... SUNDAY=1 -> our mask bit: Mon=0 ... Sun=6 */
    private fun dayBit(now: Calendar): Int = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun activeSchedule(ctx: Context, now: Calendar = Calendar.getInstance()): Schedule? {
        val mins = minutesOfDay(now)
        val todayBit = 1 shl dayBit(now)
        val yesterdayBit = 1 shl ((dayBit(now) + 6) % 7)
        for (s in Store.schedules(ctx)) {
            if (!s.enabled) continue
            if (s.startMin == s.endMin) continue
            if (s.startMin < s.endMin) {
                // same-day window
                if (s.daysMask and todayBit != 0 && mins in s.startMin until s.endMin) return s
            } else {
                // overnight window, e.g. 23:00 -> 05:00
                if (s.daysMask and todayBit != 0 && mins >= s.startMin) return s
                if (s.daysMask and yesterdayBit != 0 && mins < s.endMin) return s
            }
        }
        return null
    }

    /** When does this schedule end, as epoch ms? Handles overnight windows. */
    fun scheduleEndMs(s: Schedule, now: Calendar = Calendar.getInstance()): Long {
        val end = now.clone() as Calendar
        val mins = minutesOfDay(now)
        end.set(Calendar.HOUR_OF_DAY, s.endMin / 60)
        end.set(Calendar.MINUTE, s.endMin % 60)
        end.set(Calendar.SECOND, 0)
        end.set(Calendar.MILLISECOND, 0)
        if (s.startMin > s.endMin && mins >= s.startMin) {
            end.add(Calendar.DAY_OF_YEAR, 1) // overnight: ends tomorrow morning
        }
        if (end.timeInMillis <= now.timeInMillis) end.add(Calendar.DAY_OF_YEAR, 1)
        return end.timeInMillis
    }

    /** Today's foreground time for a package, from UsageStats. 0 if usage access not granted. */
    fun usageTodayMs(ctx: Context, pkg: String): Long {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = (Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }).timeInMillis
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis()
            ) ?: return 0L
            stats.firstOrNull { it.packageName == pkg }?.totalTimeInForeground ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun currentBlock(ctx: Context): BlockState {
        if (!Store.isProtectionOn(ctx)) return BlockState.None
        Store.applyPendingIfDue(ctx)

        // 1) Focus session beats everything.
        val focusEnd = Store.focusEndMs(ctx)
        if (focusEnd > System.currentTimeMillis()) {
            return BlockState.FocusSession(focusEnd, Store.focusSessionAllowed(ctx))
        }
        // 2) Scheduled full lock.
        activeSchedule(ctx)?.let { s ->
            return BlockState.Full(scheduleEndMs(s), s.label.ifBlank { "লক টাইম" })
        }
        // 3) Per-app daily limit for the app currently in front.
        val fg = Store.lastForegroundPkg
        if (fg != null && fg !in alwaysAllowed(ctx)) {
            val limit = Store.appLimits(ctx).firstOrNull { it.pkg == fg }
            if (limit != null) {
                val usedMin = usageTodayMs(ctx, fg) / 60000
                if (usedMin >= limit.limitMinutes) {
                    return BlockState.AppBlocked(fg, fg)
                }
            }
        }
        return BlockState.None
    }

    /** May [pkg] run during this focus session? Own app, dialer and clock always may. */
    fun isAllowedInFocus(ctx: Context, pkg: String, allowed: Set<String>): Boolean =
        pkg in alwaysAllowed(ctx) || pkg in allowed

    fun formatRemaining(untilMs: Long): String {
        var sec = ((untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val h = sec / 3600; sec %= 3600
        val m = sec / 60; val s = sec % 60
        return if (h > 0) "%d ঘণ্টা %02d মিনিট".format(h, m)
        else if (m > 0) "%d মিনিট %02d সেকেন্ড".format(m, s)
        else "%d সেকেন্ড".format(s)
    }
}
