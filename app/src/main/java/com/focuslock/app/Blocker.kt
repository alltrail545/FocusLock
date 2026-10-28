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
    /** A blocked website is open in the browser (v1.4). Transient, renewed while detected. */
    data class SiteBlocked(val untilMs: Long, val host: String) : BlockState()
}

/** Pure decision engine: given the clock + stored rules + today's usage, should we block? */
object Blocker {

    // v1.14.0: alwaysAllowed() resolves the dialer/clock packages through
    // PackageManager binder calls, and it sits on the hot path (every
    // accessibility event, every engine tick, every lock-screen tick).
    // Uncached, those round-trips added up exactly when the phone was
    // busiest — app launches — and delayed the lock. The default dialer
    // and clock do not change minute to minute, so the resolved set is
    // cached for 60 s.
    @Volatile private var alwaysAllowedCache: Set<String>? = null
    @Volatile private var alwaysAllowedCacheAt = 0L

    /** Packages that always work, even in a full lock: calls and alarms are safety, not entertainment. */
    fun alwaysAllowed(ctx: Context): Set<String> {
        val nowMs = System.currentTimeMillis()
        val cached = alwaysAllowedCache
        if (cached != null && nowMs - alwaysAllowedCacheAt < 60_000) return cached
        val fresh = computeAlwaysAllowed(ctx)
        alwaysAllowedCache = fresh
        alwaysAllowedCacheAt = nowMs
        return fresh
    }

    private fun computeAlwaysAllowed(ctx: Context): Set<String> {
        val set = mutableSetOf(ctx.packageName)
        // v1.10.0 bug fix (Tamim's report: dialer/clock would not open during
        // focus): this list used to be ONLY the hardcoded names below, while
        // AdminHelper resolved this phone's REAL default dialer/clock at
        // runtime. On the Infinix (Transsion/XOS) the real packages are not
        // in the hardcoded list, so the accessibility guard slammed the lock
        // straight back over them. Now the runtime-resolved packages are
        // included too — the same ones the LockTask whitelist already used.
        try { AdminHelper.dialerPackage(ctx)?.let { set += it } } catch (_: Throwable) { }
        try { set += AdminHelper.clockPackages(ctx) } catch (_: Throwable) { }
        // The apps that actually handle the dial / alarms intents on THIS
        // phone, whatever their package names are.
        try {
            val dial = android.content.Intent(android.content.Intent.ACTION_DIAL)
            ctx.packageManager.resolveActivity(dial, 0)?.activityInfo?.packageName?.let { set += it }
        } catch (_: Throwable) { }
        try {
            val alarms = android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
            ctx.packageManager.resolveActivity(alarms, 0)?.activityInfo?.packageName?.let { set += it }
        } catch (_: Throwable) { }
        // Defensive hardcoded names (common + Transsion/XOS packages).
        set += listOf(
            "com.android.dialer", "com.google.android.dialer",
            "com.android.incallui", "com.samsung.android.dialer",
            "com.transsion.dialer", "com.infinix.dialer",
            "com.google.android.deskclock", "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.transsion.clock", "com.infinix.clock"
            // NOTE: Settings is deliberately NOT allowed — in Stage A (no Device Owner)
            // an open Settings screen would be a bypass route, so it gets blocked too.
            // SystemUI is also NOT in this set (v1.1): Tamim asked for the notification
            // shade / control panel to be blocked during a lock. The accessibility
            // service collapses the shade the moment SystemUI comes forward in a
            // Full lock or Focus session, and a top-strip overlay guards the pull.
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

    /**
     * Today's foreground time for a package, from UsageStats.
     * v1.4.2 stability: the raw platform read is NOT trusted directly.
     * queryUsageStats(INTERVAL_DAILY) can return several bucket entries per
     * package, and on XOS the readings fluctuate between calls — the old
     * firstOrNull pick could dip under the limit on random ticks, which
     * dropped the app-limit lock for a second at a time (lock flicker).
     * Now: aggregate read + bucket-sum fallback (take the max of both),
     * then Store's monotonic per-day floor — within a day, real usage
     * never decreases, so a low read is a glitch, not a fact.
     */
    fun usageTodayMs(ctx: Context, pkg: String): Long {
        val raw = rawUsageTodayMs(ctx, pkg)
        return Store.usageFloorMs(ctx, pkg, raw)
    }

    private fun rawUsageTodayMs(ctx: Context, pkg: String): Long {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val start = (Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }).timeInMillis
            val now = System.currentTimeMillis()
            var best = 0L
            try {
                val agg = usm.queryAndAggregateUsageStats(start, now)
                best = maxOf(best, agg[pkg]?.totalTimeInForeground ?: 0L)
            } catch (_: Exception) { }
            try {
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                if (stats != null) {
                    best = maxOf(best, stats.filter { it.packageName == pkg }
                        .sumOf { it.totalTimeInForeground })
                }
            } catch (_: Exception) { }
            best
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Limits compiled into the app itself (Tamim's explicit request, v1.5.3).
     * They are NOT user limits: they never appear in the Limits tab, cannot
     * be edited, raised or removed in-app (not even via the 24h weakening
     * flow), and are not part of backup/restore. Termux gets 10 min/day —
     * Tamim still needs it daily to run Shizuku and to administer this app,
     * just not for long.
     */
    val BUILTIN_LIMIT_MINUTES: Map<String, Int> = mapOf(
        "com.termux" to 10
    )

    /** The limit that actually applies to [pkg]: a built-in wins over any user entry. */
    fun effectiveLimitMinutes(ctx: Context, pkg: String): Int? =
        BUILTIN_LIMIT_MINUTES[pkg]
            ?: Store.appLimits(ctx).firstOrNull { it.pkg == pkg }?.limitMinutes

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
        // 2b) A blocked website detected in the browser (v1.4).
        // v1.4.1 latch: the 3-second detection lease cannot renew while the
        // lock screen covers the browser (its URL bar is unreadable then),
        // so the block ALSO stays while the lock is up for this site and
        // that same browser is still the last external app. It clears the
        // moment the user leaves the browser (Home / another app changes
        // lastForegroundPkg) — the same latch the AppBlocked path uses.
        val nowMs = System.currentTimeMillis()
        val siteLatched = LockActivity.isShowing && Store.siteBlockHost.isNotBlank() &&
            Store.siteBlockPkg.isNotBlank() && Store.lastForegroundPkg == Store.siteBlockPkg
        if (Store.siteBlockHost.isNotBlank() &&
            (Store.siteBlockUntilMs > nowMs || siteLatched)
        ) {
            return BlockState.SiteBlocked(maxOf(Store.siteBlockUntilMs, nowMs + 1000), Store.siteBlockHost)
        }
        // 3) Per-app daily limit for the app currently in front.
        val fg = Store.lastForegroundPkg
        if (fg != null && fg !in alwaysAllowed(ctx)) {
            val limitMin = effectiveLimitMinutes(ctx, fg)
            if (limitMin != null) {
                // v1.4.2 day latch: once this app crossed its limit today,
                // the block holds for the rest of the day — a fluctuating
                // usage re-read can never unlock it mid-day.
                val latch = Store.appBlockLatch(ctx)
                if (latch != null && latch.first == fg) {
                    return BlockState.AppBlocked(fg, fg)
                }
                val usedMin = usageTodayMs(ctx, fg) / 60000
                if (usedMin >= limitMin) {
                    Store.latchAppBlock(ctx, fg, limitMin)
                    return BlockState.AppBlocked(fg, fg)
                }
            }
            // 3b) Automation timed block (v1.6.0): PhoneLink asked for this
            // app to stay blocked until a fixed clock time, independent of
            // any daily limit.
            val timedUntil = Store.timedAppBlocks(ctx)[fg]
            if (timedUntil != null && timedUntil > System.currentTimeMillis()) {
                return BlockState.AppBlocked(fg, fg)
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
