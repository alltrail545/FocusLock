package com.focuslock.app

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/** A daily recurring lock window. Times are minutes from midnight (local). */
data class Schedule(
    val id: Long,
    val label: String,
    val startMin: Int,
    val endMin: Int,
    /** Bit 0 = Monday ... bit 6 = Sunday. 127 = every day. */
    val daysMask: Int,
    val enabled: Boolean = true
) {
    fun serialize(): String =
        listOf(id, label.replace("|", " ").replace(";", " "), startMin, endMin, daysMask, if (enabled) 1 else 0)
            .joinToString("|")

    companion object {
        fun parse(s: String): Schedule? {
            val p = s.split("|")
            if (p.size != 6) return null
            // v1.8.0 audit: range-validate — a corrupt/imported row with
            // times outside a day (e.g. 25:70) used to load as a schedule
            // whose window math could never fire (or fired wrongly).
            val start = p[2].toIntOrNull() ?: return null
            val end = p[3].toIntOrNull() ?: return null
            if (start !in 0..1439 || end !in 0..1439) return null
            return Schedule(
                p[0].toLongOrNull() ?: return null,
                p[1],
                start,
                end,
                (p[4].toIntOrNull() ?: 127).coerceIn(0, 127),
                p[5] == "1"
            )
        }
    }
}

data class AppLimit(val pkg: String, val limitMinutes: Int) {
    fun serialize(): String = "$pkg|$limitMinutes"
    companion object {
        fun parse(s: String): AppLimit? {
            val p = s.split("|")
            if (p.size != 2) return null
            if (p[0].isBlank()) return null
            // v1.8.0 audit: a limit of 0 or negative minutes (corrupt or
            // hand-edited backup) made usedMin >= limit true at 0 usage —
            // the app was blocked all day from the first tick. More than a
            // day's minutes can never be a real daily limit either.
            val mins = p[1].toIntOrNull() ?: return null
            if (mins !in 1..1440) return null
            return AppLimit(p[0], mins)
        }
    }
}

/** A named one-tap focus preset (v1.4): name + minutes + picked apps. */
data class FocusPreset(val name: String, val minutes: Int, val allowed: Set<String>) {
    fun serialize(): String =
        listOf(name.replace("|", " ").replace(";", " "), minutes, allowed.joinToString(","))
            .joinToString("|")

    companion object {
        fun parse(s: String): FocusPreset? {
            val p = s.split("|")
            if (p.size != 3) return null
            // v1.8.0 audit: same range rule the preset UI enforces (1..1440);
            // a 0/negative-minutes preset would start an already-expired
            // session, an absurd one would lock for days.
            val mins = p[1].toIntOrNull() ?: return null
            if (mins !in 1..1440) return null
            return FocusPreset(
                p[0],
                mins,
                if (p[2].isBlank()) emptySet() else p[2].split(",").filter { it.isNotBlank() }.toSet()
            )
        }
    }
}

/**
 * All persistent state. SharedPreferences only — no account, no cloud,
 * nothing leaves the phone. This app is personal by design.
 */
object Store {
    private const val PREFS = "focuslock_store"
    /** Weakening changes (protection OFF, deleting a schedule, raising a limit) take this long. */
    const val WEAKEN_DELAY_MS = 24L * 60 * 60 * 1000

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- protection master switch ----
    fun isProtectionOn(ctx: Context): Boolean = prefs(ctx).getBoolean("protection_on", true)
    fun setProtectionOn(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean("protection_on", on).apply()

    // ---- schedules ----
    fun schedules(ctx: Context): List<Schedule> =
        (prefs(ctx).getString("schedules", "") ?: "")
            .split(";").filter { it.isNotBlank() }.mapNotNull { Schedule.parse(it) }

    fun saveSchedules(ctx: Context, list: List<Schedule>) =
        prefs(ctx).edit().putString("schedules", list.joinToString(";") { it.serialize() }).apply()

    // ---- app limits ----
    fun appLimits(ctx: Context): List<AppLimit> =
        (prefs(ctx).getString("app_limits", "") ?: "")
            .split(";").filter { it.isNotBlank() }.mapNotNull { AppLimit.parse(it) }

    fun saveAppLimits(ctx: Context, list: List<AppLimit>) =
        prefs(ctx).edit().putString("app_limits", list.joinToString(";") { it.serialize() }).apply()

    // ---- focus session (absolute end time; survives reboot checks) ----
    fun focusEndMs(ctx: Context): Long = prefs(ctx).getLong("focus_end_ms", 0L)
    fun setFocusEndMs(ctx: Context, end: Long) =
        prefs(ctx).edit().putLong("focus_end_ms", end).apply()

    // ---- focus whitelist: ONLY these apps may run during a focus session ----
    // Tamim's requirement (2026-10-23): pick the study apps he needs; the moment
    // focus starts, everything not picked is blocked instantly.
    private fun getPkgSet(ctx: Context, key: String): Set<String> =
        (prefs(ctx).getString(key, "") ?: "").split(";").filter { it.isNotBlank() }.toSet()

    private fun putPkgSet(ctx: Context, key: String, set: Set<String>) =
        prefs(ctx).edit().putString(key, set.joinToString(";")).apply()

    /** Saved study preset so he does not re-pick apps every session. */
    fun focusDefaultAllowed(ctx: Context): Set<String> = getPkgSet(ctx, "focus_default_allowed")
    fun saveFocusDefaultAllowed(ctx: Context, set: Set<String>) =
        putPkgSet(ctx, "focus_default_allowed", set)

    /** The whitelist actually in force for the running session (may differ from the preset). */
    fun focusSessionAllowed(ctx: Context): Set<String> = getPkgSet(ctx, "focus_session_allowed")
    fun setFocusSessionAllowed(ctx: Context, set: Set<String>) =
        putPkgSet(ctx, "focus_session_allowed", set)

    // ---- PIN (salted SHA-256; the raw PIN is never stored) ----
    fun hasPin(ctx: Context): Boolean = prefs(ctx).contains("pin_hash")
    fun setPin(ctx: Context, pin: String) {
        val salt = "focuslock-v1"
        prefs(ctx).edit().putString("pin_hash", sha256("$salt:$pin")).apply()
    }
    fun checkPin(ctx: Context, pin: String): Boolean {
        val stored = prefs(ctx).getString("pin_hash", null) ?: return false
        return stored == sha256("focuslock-v1:$pin")
    }
    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- pending weakening change (24h delay) ----
    /** action string, e.g. "protection_off" or "delete_schedule:<id>" or "raise_limit:<pkg>:<mins>" */
    fun pendingAction(ctx: Context): Pair<String, Long>? {
        val a = prefs(ctx).getString("pending_action", null) ?: return null
        val at = prefs(ctx).getLong("pending_execute_at", 0L)
        return a to at
    }
    fun setPendingAction(ctx: Context, action: String, executeAt: Long) =
        prefs(ctx).edit().putString("pending_action", action)
            .putLong("pending_execute_at", executeAt).apply()
    fun clearPendingAction(ctx: Context) =
        prefs(ctx).edit().remove("pending_action").remove("pending_execute_at").apply()

    /** Runs the pending action if its 24h delay has passed. Called by the blocking engine. */
    fun applyPendingIfDue(ctx: Context) {
        val (action, at) = pendingAction(ctx) ?: return
        // v1.8.0 audit: a pending row whose execute-time was lost (corrupt
        // prefs) read as at=0 — "long past due" — and the weakening change
        // applied IMMEDIATELY, silently bypassing the 24h rule. An invalid
        // pending row is dropped, never applied.
        if (at <= 0L) {
            logTamper(ctx, "Invalid pending change dropped (no execute time): $action")
            clearPendingAction(ctx)
            return
        }
        if (System.currentTimeMillis() < at) return
        when {
            action == "protection_off" -> setProtectionOn(ctx, false)
            action == "remove_device_owner" -> {
                // v1.5.1: verified teardown — if it aborts, owner stays and
                // the failure is surfaced in Settings with a retry button.
                val ok = AdminHelper.safelyRemoveDeviceOwner(ctx)
                setExitFailed(ctx, !ok)
            }
            action.startsWith("delete_schedule:") -> {
                // v1.8.0 audit: this used to `return` on an unparsable id,
                // which skipped clearPendingAction below — the dead pending
                // row was retried on EVERY engine tick forever. A malformed
                // action is now skipped and the row is cleared as usual.
                val id = action.removePrefix("delete_schedule:").toLongOrNull()
                if (id != null) {
                    saveSchedules(ctx, schedules(ctx).filter { it.id != id })
                }
            }
            action.startsWith("raise_limit:") -> {
                val parts = action.removePrefix("raise_limit:").split(":")
                if (parts.size == 2) {
                    // Same stuck-pending fix as delete_schedule above, plus
                    // the 1..1440 range rule for a daily limit.
                    val mins = parts[1].toIntOrNull()
                    if (mins != null && mins in 1..1440) {
                        val list = appLimits(ctx).map {
                            if (it.pkg == parts[0]) it.copy(limitMinutes = mins) else it
                        }
                        saveAppLimits(ctx, list)
                        // v1.5.1: if the app is suspended but now under its new
                        // limit, lift the suspension together with the raise.
                        AdminHelper.refreshSuspensions(ctx)
                    }
                }
            }
            action.startsWith("remove_site:") -> {
                val host = action.removePrefix("remove_site:")
                saveBlockedSites(ctx, blockedSites(ctx).filter { it != host })
            }
            action.startsWith("remove_category:") -> {
                val id = action.removePrefix("remove_category:")
                saveSiteCategories(ctx, siteCategories(ctx) - id)
            }
            action == "allow_only_off" -> setSiteAllowOnly(ctx, false)
            action.startsWith("remove_limit:") -> {
                val pkg = action.removePrefix("remove_limit:")
                saveAppLimits(ctx, appLimits(ctx).filter { it.pkg != pkg })
                // v1.5.1: never orphan a suspension — when a limit goes,
                // a currently-suspended app is restored with it (the audit
                // Tamim brought caught this: the old code just deleted the
                // limit and left the app suspended forever).
                AdminHelper.suspendApp(ctx, pkg, false)
            }
        }
        logTamper(ctx, "Pending change applied after 24h: $action")
        clearPendingAction(ctx)
    }

    // ---- v1.4 additions ----

    /** When the running focus session started (for the focus report). */
    fun focusStartMs(ctx: Context): Long = prefs(ctx).getLong("focus_start_ms", 0L)
    fun setFocusStartMs(ctx: Context, ms: Long) =
        prefs(ctx).edit().putLong("focus_start_ms", ms).apply()

    /** Silence notifications (Do Not Disturb) while a focus session / full lock runs. */
    fun silenceDuringLock(ctx: Context): Boolean = prefs(ctx).getBoolean("silence_during_lock", true)
    fun setSilenceDuringLock(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean("silence_during_lock", on).apply()

    // ---- named focus presets ----
    fun focusPresets(ctx: Context): List<FocusPreset> =
        (prefs(ctx).getString("focus_presets", "") ?: "")
            .split(";").filter { it.isNotBlank() }.mapNotNull { FocusPreset.parse(it) }

    fun saveFocusPresets(ctx: Context, list: List<FocusPreset>) =
        prefs(ctx).edit().putString("focus_presets", list.joinToString(";") { it.serialize() }).apply()

    // ---- blocked websites (checked in Chrome via accessibility, v1.4) ----
    fun blockedSites(ctx: Context): List<String> =
        (prefs(ctx).getString("blocked_sites", "") ?: "")
            .split(";").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    fun saveBlockedSites(ctx: Context, list: List<String>) =
        prefs(ctx).edit().putString("blocked_sites",
            list.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().joinToString(";")).apply()

    // ---- automation API (v1.6.0, built for PhoneLink com.mthtamim.phonelink) ----
    /** SHA-256 hex of the automation token; empty = API off. Never the plain token. */
    fun automationTokenHash(ctx: Context): String =
        prefs(ctx).getString("automation_token_hash", "") ?: ""

    fun setAutomationTokenHash(ctx: Context, hash: String) =
        prefs(ctx).edit().putString("automation_token_hash", hash).apply()

    /** Timed per-app blocks set by automation: package -> unblock time (ms). */
    fun timedAppBlocks(ctx: Context): Map<String, Long> =
        (prefs(ctx).getString("timed_app_blocks", "") ?: "")
            .split(";").filter { it.isNotBlank() }.mapNotNull {
                val p = it.split("=")
                if (p.size == 2 && p[0].isNotBlank()) {
                    val until = p[1].toLongOrNull() ?: return@mapNotNull null
                    p[0] to until
                } else null
            }.toMap()

    fun saveTimedAppBlocks(ctx: Context, map: Map<String, Long>) =
        prefs(ctx).edit().putString("timed_app_blocks",
            map.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()

    /** True while the running focus session was started by automation. */
    fun isAutoSession(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_session", false)
    fun setAutoSession(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean("auto_session", on).apply()

    // ---- website category filters (v1.6.0): enabled category ids ----
    fun siteCategories(ctx: Context): Set<String> =
        (prefs(ctx).getString("site_categories", "") ?: "")
            .split(";").map { it.trim() }.filter { it.isNotBlank() }.toSet()

    fun saveSiteCategories(ctx: Context, ids: Set<String>) =
        prefs(ctx).edit().putString("site_categories", ids.joinToString(";")).apply()

    // ---- website allow list (v1.7.0, Tamim's request) ----
    /** Sites that are NEVER blocked: they override the block list and the
     *  category filters. Stored like blockedSites (";"-joined hosts). */
    fun siteAllowList(ctx: Context): List<String> =
        (prefs(ctx).getString("site_allow_list", "") ?: "")
            .split(";").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    fun saveSiteAllowList(ctx: Context, list: List<String>) =
        prefs(ctx).edit().putString("site_allow_list",
            list.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().joinToString(";")).apply()

    /** Allow-only mode: ONLY allow-list sites may open; every other site is
     *  blocked. An empty allow list + this mode = no website opens at all.
     *  Turning it ON is immediate; turning it OFF is weakening (PIN + 24h
     *  via the "allow_only_off" pending action). */
    fun siteAllowOnly(ctx: Context): Boolean = prefs(ctx).getBoolean("site_allow_only", false)
    fun setSiteAllowOnly(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean("site_allow_only", on).apply()


    // ---- v1.8.0: persisted DND restore state ----
    // Engine's "DND was applied, restore this filter afterwards" state used
    // to live only in memory: if the process died mid-lock (XOS kills
    // background apps), the phone stayed on the alarms-only filter forever
    // after the lock ended, with nothing left to restore it. Persisted now.
    fun dndPreviousFilter(ctx: Context): Int? {
        val p = prefs(ctx)
        if (!p.getBoolean("dnd_applied", false)) return null
        return p.getInt("dnd_prev_filter", android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    fun setDndPreviousFilter(ctx: Context, filter: Int?) {
        val ed = prefs(ctx).edit()
        if (filter == null) {
            ed.remove("dnd_applied").remove("dnd_prev_filter")
        } else {
            ed.putBoolean("dnd_applied", true).putInt("dnd_prev_filter", filter)
        }
        ed.apply()
    }

    /** Transient: a blocked site is being shown the lock screen until this time. */
    @Volatile var siteBlockUntilMs: Long = 0L
    @Volatile var siteBlockHost: String = ""
    /** Which browser the blocked site was detected in (v1.4.1 latch). */
    @Volatile var siteBlockPkg: String = ""

    // ---- focus history for the report: "yyyy-MM-dd=minutes;..." ----
    private fun dayKey(offsetDays: Int = 0): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
    }

    fun recordFocusMinutes(ctx: Context, minutes: Int) {
        if (minutes <= 0) return
        val map = focusHistory(ctx).toMutableMap()
        val key = dayKey()
        map[key] = (map[key] ?: 0) + minutes
        // v1.8.0 audit: history grew one entry per day, forever — the stored
        // string (and every parse of it) grew unbounded. Only the last 400
        // days can matter (7-day totals + any realistic streak).
        val ser = map.entries.sortedBy { it.key }.takeLast(400)
            .joinToString(";") { "${it.key}=${it.value}" }
        prefs(ctx).edit().putString("focus_history", ser).apply()
    }

    fun focusHistory(ctx: Context): Map<String, Int> =
        (prefs(ctx).getString("focus_history", "") ?: "")
            .split(";").filter { it.contains("=") }
            .associate { part ->
                val kv = part.split("=", limit = 2)
                kv[0] to (kv[1].toIntOrNull() ?: 0)
            }

    fun focusMinutesToday(ctx: Context): Int = focusHistory(ctx)[dayKey()] ?: 0

    fun focusMinutesLast7Days(ctx: Context): Int =
        (0..6).sumOf { focusHistory(ctx)[dayKey(-it)] ?: 0 }

    /** Consecutive days (today or yesterday backwards) with at least one focus session. */
    fun focusStreakDays(ctx: Context): Int {
        val hist = focusHistory(ctx)
        var streak = 0
        var offset = if ((hist[dayKey()] ?: 0) > 0) 0 else -1
        while ((hist[dayKey(offset)] ?: 0) > 0) {
            streak++
            offset--
        }
        return streak
    }

    // ---- settings backup / restore (v1.4): JSON via the system file picker ----
    fun exportJson(ctx: Context): String {
        val o = org.json.JSONObject()
        o.put("version", 1)
        o.put("protection_on", isProtectionOn(ctx))
        o.put("silence_during_lock", silenceDuringLock(ctx))
        o.put("schedules", org.json.JSONArray(schedules(ctx).map { it.serialize() }))
        o.put("app_limits", org.json.JSONArray(appLimits(ctx).map { it.serialize() }))
        o.put("focus_default_allowed", org.json.JSONArray(focusDefaultAllowed(ctx).toList()))
        o.put("focus_presets", org.json.JSONArray(focusPresets(ctx).map { it.serialize() }))
        o.put("blocked_sites", org.json.JSONArray(blockedSites(ctx)))
        o.put("site_categories", org.json.JSONArray(siteCategories(ctx).toList()))
        o.put("site_allow_list", org.json.JSONArray(siteAllowList(ctx)))
        o.put("site_allow_only", siteAllowOnly(ctx))
        return o.toString(2)
    }

    fun importJson(ctx: Context, json: String): Boolean {
        // v1.8.0 audit: the file comes from a user-picked document — cap its
        // size so a huge/corrupt pick cannot OOM the restore, and read every
        // value type-safely. The old code used getBoolean/getString, which
        // THROW on a wrong-typed value mid-import: sections before the bad
        // one were already saved, then the import reported failure — a
        // half-applied restore. Now a wrong-typed section is skipped and
        // every other section still applies cleanly.
        if (json.length > 1_000_000) return false
        return try {
            val o = org.json.JSONObject(json)
            fun bool(key: String): Boolean? =
                if (o.opt(key) is Boolean) o.getBoolean(key) else null
            fun strings(key: String): List<String>? {
                val arr = o.optJSONArray(key) ?: return null
                return (0 until arr.length()).mapNotNull {
                    arr.optString(it).takeIf { s -> s.isNotBlank() }
                }
            }
            bool("protection_on")?.let { setProtectionOn(ctx, it) }
            bool("silence_during_lock")?.let { setSilenceDuringLock(ctx, it) }
            strings("schedules")?.let { saveSchedules(ctx, it.mapNotNull { s -> Schedule.parse(s) }) }
            strings("app_limits")?.let { saveAppLimits(ctx, it.mapNotNull { s -> AppLimit.parse(s) }) }
            strings("focus_default_allowed")?.let { saveFocusDefaultAllowed(ctx, it.toSet()) }
            strings("focus_presets")?.let { saveFocusPresets(ctx, it.mapNotNull { s -> FocusPreset.parse(s) }) }
            strings("blocked_sites")?.let { saveBlockedSites(ctx, it) }
            strings("site_categories")?.let { saveSiteCategories(ctx, it.toSet()) }
            strings("site_allow_list")?.let { saveSiteAllowList(ctx, it) }
            bool("site_allow_only")?.let { setSiteAllowOnly(ctx, it) }
            logTamper(ctx, "Settings restored from backup file")
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- tamper / event log (last 100 lines, shown in the app) ----
    fun logTamper(ctx: Context, msg: String) {
        val old = prefs(ctx).getString("tamper_log", "") ?: ""
        val line = "${System.currentTimeMillis()}  $msg"
        val lines = (old.split("\n").filter { it.isNotBlank() } + line).takeLast(100)
        prefs(ctx).edit().putString("tamper_log", lines.joinToString("\n")).apply()
    }
    fun tamperLog(ctx: Context): String = prefs(ctx).getString("tamper_log", "") ?: ""

    // ---- last foreground package (written by the accessibility service) ----
    @Volatile var lastForegroundPkg: String? = null

    // ---- v1.4.2: stable usage floor + app-block day latch ----
    // XOS usage-stats reads fluctuate between calls (multi-bucket replies);
    // a single low read used to drop an app-limit lock for a second and the
    // lock screen flickered off and on. Within one day real usage can only
    // grow, so we persist the highest value seen today and never go below it.
    fun usageFloorMs(ctx: Context, pkg: String, rawMs: Long): Long {
        val p = prefs(ctx)
        val today = dayKey()
        if (p.getString("usage_day", "") != today) {
            // New day: forget yesterday's floors and any latched block.
            val ed = p.edit().putString("usage_day", today)
                .remove("appblock_pkg").remove("appblock_day").remove("appblock_limit")
            for (key in p.all.keys.filter { it.startsWith("usage_max_") }) ed.remove(key)
            ed.apply()
        }
        val key = "usage_max_$pkg"
        val stored = p.getLong(key, 0L)
        val best = maxOf(stored, rawMs)
        if (best > stored) p.edit().putLong(key, best).apply()
        return best
    }

    /** Once an app crosses its daily limit, its block is latched for the rest of the day. */
    fun latchAppBlock(ctx: Context, pkg: String, limitMinutes: Int) {
        prefs(ctx).edit()
            .putString("appblock_pkg", pkg)
            .putString("appblock_day", dayKey())
            .putInt("appblock_limit", limitMinutes)
            .apply()
    }

    // ---- v1.5.1: managed-package ledger + exit state ----
    // Every package FocusLock has suspended as Device Owner and not yet
    // verifiably restored. The emergency exit restores from THIS list (plus
    // current limits), so deleting a limit can never orphan a suspension.
    fun ledgerPackages(ctx: Context): Set<String> = getPkgSet(ctx, "managed_ledger")
    fun ledgerAdd(ctx: Context, pkg: String) =
        putPkgSet(ctx, "managed_ledger", ledgerPackages(ctx) + pkg)
    fun ledgerRemove(ctx: Context, pkg: String) =
        putPkgSet(ctx, "managed_ledger", ledgerPackages(ctx) - pkg)

    /** True when a Device Owner removal was attempted but aborted mid-cleanup. */
    fun setExitFailed(ctx: Context, failed: Boolean) =
        prefs(ctx).edit().putBoolean("exit_failed", failed).apply()
    fun isExitFailed(ctx: Context): Boolean = prefs(ctx).getBoolean("exit_failed", false)

    /** The latched (pkg, limit) — valid only today, and only while that app's limit is unchanged. */
    fun appBlockLatch(ctx: Context): Pair<String, Int>? {
        val p = prefs(ctx)
        if (p.getString("appblock_day", "") != dayKey()) return null
        val pkg = p.getString("appblock_pkg", null) ?: return null
        val limit = p.getInt("appblock_limit", -1)
        if (limit < 0) return null
        // Built-in limits (v1.5.3) latch too — they live outside appLimits.
        val current = Blocker.BUILTIN_LIMIT_MINUTES[pkg]
            ?: appLimits(ctx).firstOrNull { it.pkg == pkg }?.limitMinutes
            ?: return null
        if (current != limit) return null
        return pkg to limit
    }
}
