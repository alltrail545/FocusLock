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
            return Schedule(
                p[0].toLongOrNull() ?: return null,
                p[1],
                p[2].toIntOrNull() ?: return null,
                p[3].toIntOrNull() ?: return null,
                p[4].toIntOrNull() ?: 127,
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
            return AppLimit(p[0], p[1].toIntOrNull() ?: return null)
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
            return FocusPreset(
                p[0],
                p[1].toIntOrNull() ?: return null,
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
        if (System.currentTimeMillis() < at) return
        when {
            action == "protection_off" -> setProtectionOn(ctx, false)
            action.startsWith("delete_schedule:") -> {
                val id = action.removePrefix("delete_schedule:").toLongOrNull() ?: return
                saveSchedules(ctx, schedules(ctx).filter { it.id != id })
            }
            action.startsWith("raise_limit:") -> {
                val parts = action.removePrefix("raise_limit:").split(":")
                if (parts.size == 2) {
                    val mins = parts[1].toIntOrNull() ?: return
                    val list = appLimits(ctx).map {
                        if (it.pkg == parts[0]) it.copy(limitMinutes = mins) else it
                    }
                    saveAppLimits(ctx, list)
                }
            }
            action.startsWith("remove_site:") -> {
                val host = action.removePrefix("remove_site:")
                saveBlockedSites(ctx, blockedSites(ctx).filter { it != host })
            }
            action.startsWith("remove_limit:") -> {
                val pkg = action.removePrefix("remove_limit:")
                saveAppLimits(ctx, appLimits(ctx).filter { it.pkg != pkg })
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
        val ser = map.entries.sortedBy { it.key }
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
        return o.toString(2)
    }

    fun importJson(ctx: Context, json: String): Boolean {
        return try {
            val o = org.json.JSONObject(json)
            if (o.has("protection_on")) setProtectionOn(ctx, o.getBoolean("protection_on"))
            if (o.has("silence_during_lock")) setSilenceDuringLock(ctx, o.getBoolean("silence_during_lock"))
            if (o.has("schedules")) {
                val arr = o.getJSONArray("schedules")
                saveSchedules(ctx, (0 until arr.length()).mapNotNull { Schedule.parse(arr.getString(it)) })
            }
            if (o.has("app_limits")) {
                val arr = o.getJSONArray("app_limits")
                saveAppLimits(ctx, (0 until arr.length()).mapNotNull { AppLimit.parse(arr.getString(it)) })
            }
            if (o.has("focus_default_allowed")) {
                val arr = o.getJSONArray("focus_default_allowed")
                saveFocusDefaultAllowed(ctx, (0 until arr.length()).map { arr.getString(it) }.toSet())
            }
            if (o.has("focus_presets")) {
                val arr = o.getJSONArray("focus_presets")
                saveFocusPresets(ctx, (0 until arr.length()).mapNotNull { FocusPreset.parse(arr.getString(it)) })
            }
            if (o.has("blocked_sites")) {
                val arr = o.getJSONArray("blocked_sites")
                saveBlockedSites(ctx, (0 until arr.length()).map { arr.getString(it) })
            }
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
}
