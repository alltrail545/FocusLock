package com.focuslock.app

import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * FocusLock home. Design system v2 "Classic Glass" (Tamim's pick,
 * 2026-10-28): aurora gradient backdrop, translucent glass cards,
 * floating glass pill nav. Screens stay text-light — explanations
 * live behind each card's ⓘ button (Ui.cardHeader).
 * Bottom navigation with five tabs: Home / Focus / Schedule /
 * Limits / Settings.
 */
class MainActivity : AppCompatActivity() {

    private var currentTab = 0 // 0 Home, 1 Focus, 2 Schedule, 3 Limits, 4 Settings

    // v1.4 settings backup / restore via the system file picker (no storage permission needed)
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)
                        ?.use { it.write(Store.exportJson(this).toByteArray()) }
                    toast("ব্যাকআপ সেভ হয়েছে")
                } catch (e: Exception) {
                    toast("ব্যাকআপ হয়নি: ${e.message}")
                }
            }
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText() ?: ""
                    if (Store.importJson(this, text)) {
                        toast("সেটিংস রিস্টোর হয়েছে")
                        Engine.tick(this)
                        buildUi()
                    } else {
                        toast("ফাইলটা ঠিকমতো পড়া যায়নি")
                    }
                } catch (e: Exception) {
                    toast("রিস্টোর হয়নি: ${e.message}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdminHelper.applyStrictPolicies(this)
        // v1.3 silent engine: no unconditional service start — Engine only
        // runs the foreground service while a lock/focus is actually active.
        Engine.tick(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // v1.5.1 belt-and-braces: release a stranded LockTask pin whenever
        // NO block is active — whether Device Owner is still in place or
        // was removed by the emergency exit. The phone must never stay in
        // kiosk mode with nothing left to justify the pin.
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            if (am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE &&
                Blocker.currentBlock(this) == BlockState.None
            ) {
                stopLockTask()
            }
        } catch (_: Exception) { }
        buildUi() // refresh status + lists
    }

    // ---------------- UI construction ----------------

    private fun buildUi() {
        // v1.15.0: theme + language direction are re-read on every build,
        // so a change in Settings applies the moment the UI rebuilds.
        Themes.apply(Store.themeId(this))
        if (Lang.isRtl(this)) {
            window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.aurora(this@MainActivity)
        }

        // Header (fixed above the scrolling content)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 14),
                Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10))
        }
        header.addView(Ui.appTitle(this, "FocusLock"))
        shell.addView(header)

        // Scrolling tab content
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 4),
                Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        scroll.addView(content)
        shell.addView(scroll)

        when (currentTab) {
            0 -> tabHome(content)
            1 -> tabFocus(content)
            2 -> tabSchedule(content)
            3 -> tabLimits(content)
            else -> tabSettings(content)
        }

        // Floating glass pill navigation (design v2)
        shell.addView(buildNavBar())
        setContentView(shell)
    }

    private fun buildNavBar(): LinearLayout {
        val labels = listOf(
            Lang.t(this, "tab_home"), Lang.t(this, "tab_focus"), Lang.t(this, "tab_schedule"),
            Lang.t(this, "tab_limits"), Lang.t(this, "tab_settings")
        )
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 2),
                Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 14))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Ui.RAISED)
                cornerRadius = Ui.dp(this@MainActivity, 999).toFloat()
                setStroke(Ui.dp(this@MainActivity, 1), Ui.STROKE)
            }
            elevation = Ui.dp(this@MainActivity, 8).toFloat()
            setPadding(Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6),
                Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (i in labels.indices) {
            val active = i == currentTab
            val item = TextView(this).apply {
                text = labels[i]
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 9),
                    Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 9))
                if (active) {
                    setTextColor(android.graphics.Color.WHITE)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(Ui.ACCENT)
                        cornerRadius = Ui.dp(this@MainActivity, 999).toFloat()
                    }
                } else {
                    setTextColor(Ui.MUTED)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Ui.dp(this@MainActivity, 2)
                    marginEnd = Ui.dp(this@MainActivity, 2)
                }
                setOnClickListener {
                    if (currentTab != i) {
                        currentTab = i
                        buildUi()
                    }
                }
            }
            bar.addView(item)
        }
        wrap.addView(bar)
        return wrap
    }

    // ---- Tab 0: Home — minimal permissions, live state, statistics ----
    private fun tabHome(content: LinearLayout) {
        // Screen-time summary is computed ONCE per Home build and shared
        // by the hero card and the statistics card (it walks UsageStats —
        // calling it twice per refresh was wasted work).
        val usageAccess = AdminHelper.hasUsageAccess(this)
        val usage = if (usageAccess) usageSummary() else (0L to emptyList())

        // ---- v1.12.0 UI/UX: status hero — the phone's protection state
        // at a glance: master switch + owner, live lock state with a
        // focus progress bar, and today's headline numbers. ----
        val heroCard = Ui.card(this)
        heroCard.addView(Ui.cardTitle(this, Lang.t(this, "card_status")))
        val heroFocusEnd = Store.focusEndMs(this)
        val heroNow = System.currentTimeMillis()
        heroCard.addView(Ui.body(this,
            "সুরক্ষা ${if (Store.isProtectionOn(this)) "চালু" else "বন্ধ"} · Device Owner " +
                if (AdminHelper.isDeviceOwner(this)) "চালু" else "এখনো হয়নি"))
        if (heroFocusEnd > heroNow) {
            heroCard.addView(Ui.body(this, "ফোকাস চলছে — শেষ হবে ${timeText(heroFocusEnd)}").apply {
                setTextColor(Ui.BRIGHT)
                setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
            })
            val heroStart = Store.focusStartMs(this)
            if (heroStart > 0 && heroStart < heroFocusEnd) {
                val pct = ((heroNow - heroStart) * 100 / (heroFocusEnd - heroStart)).toInt()
                heroCard.addView(Ui.progressBar(this, pct))
            }
        } else {
            val heroSchedule = Blocker.activeSchedule(this)
            if (heroSchedule != null) {
                heroCard.addView(Ui.body(this, "শিডিউল লক চলছে — ${heroSchedule.label}").apply {
                    setTextColor(Ui.BRIGHT)
                    setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
                })
            } else {
                heroCard.addView(Ui.secondary(this, "এখন কোনো লক চলছে না").apply {
                    setPadding(0, Ui.dp(this@MainActivity, 6), 0, 0)
                })
            }
        }
        heroCard.addView(Ui.secondary(this,
            "আজকের ফোকাস: ${Store.focusMinutesToday(this)} মিনিট" +
                if (usageAccess) " · আজকের স্ক্রিন টাইম: ${durText(usage.first)}" else ""))
        val streak = Store.focusStreakDays(this)
        if (streak > 0) {
            heroCard.addView(Ui.secondary(this, "টানা ধারা (স্ট্রিক): $streak দিন"))
        }
        content.addView(heroCard)

        // Minimized (Tamim's request): only the 3 permissions the app truly
        // needs day-to-day. Device Owner belongs to Stage B and lives in
        // Settings; optional features ask for their permission only when used.
        val permCard = Ui.card(this)
        permCard.addView(Ui.cardHeader(this, Lang.t(this, "card_permissions")) {
            showScrollableDialog("পারমিশন", "এখানে শুধু প্রতিদিনের কাজের ৩টা জরুরি পারমিশন। সব পারমিশন সেটিংস একসাথে সেটিংস ট্যাবের আলাদা কার্ডে আছে।")
        })
        permCard.addView(Ui.statusRow(this, "Accessibility (অ্যাপ ব্লকার)", AdminHelper.isAccessibilityOn(this)))
        permCard.addView(Ui.statusRow(this, "Usage Access (লিমিট ও হিসাব)", AdminHelper.hasUsageAccess(this)))
        permCard.addView(Ui.statusRow(this, "Overlay (শেড গার্ড)", AdminHelper.hasOverlayPermission(this)))
        content.addView(permCard)

        val nowCard = Ui.card(this)
        nowCard.addView(Ui.cardTitle(this, Lang.t(this, "card_now")))
        nowCard.addView(Ui.statusRow(this, "Protection", Store.isProtectionOn(this)))
        val focusEnd = Store.focusEndMs(this)
        if (focusEnd > System.currentTimeMillis()) {
            nowCard.addView(Ui.body(this, "ফোকাস চলছে — শেষ হবে ${timeText(focusEnd)}").apply {
                setTextColor(Ui.BRIGHT)
            })
        } else {
            val active = Blocker.activeSchedule(this)
            if (active != null) {
                nowCard.addView(Ui.body(this, "শিডিউল লক চলছে — ${active.label}").apply {
                    setTextColor(Ui.BRIGHT)
                })
            } else {
                nowCard.addView(Ui.secondary(this, "এখন কোনো লক চলছে না"))
            }
        }
        val pending = Store.pendingAction(this)
        if (pending != null) {
            nowCard.addView(Ui.secondary(this, "Pending পরিবর্তন আছে — সেটিংস ট্যাবে দেখো।").apply {
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
            })
        }
        content.addView(nowCard)

        // ---- Statistics & report (Tamim: keep it on Home) ----
        val statsCard = Ui.card(this)
        statsCard.addView(Ui.cardTitle(this, Lang.t(this, "card_today")))
        if (usageAccess) {
            val (totalMs, top) = usage
            statsCard.addView(Ui.body(this, "মোট স্ক্রিন টাইম: ${durText(totalMs)}"))
            for ((label, ms) in top) {
                statsCard.addView(Ui.secondary(this, "$label — ${durText(ms)}"))
            }
        } else {
            statsCard.addView(Ui.secondary(this, "স্ক্রিন টাইমের হিসাব দেখতে Usage Access পারমিশনটা লাগবে (উপরে)।"))
        }
        statsCard.addView(Ui.gap(this, 10))
        statsCard.addView(Ui.body(this, "ফোকাস রিপোর্ট"))
        statsCard.addView(Ui.secondary(this, "আজকের ফোকাস: ${Store.focusMinutesToday(this)} মিনিট"))
        statsCard.addView(Ui.secondary(this, "গত ৭ দিনে মোট ফোকাস: ${durText(Store.focusMinutesLast7Days(this) * 60_000L)}"))
        statsCard.addView(Ui.secondary(this, "টানা ধারা (স্ট্রিক): ${Store.focusStreakDays(this)} দিন"))
        content.addView(statsCard)
    }

    /** Today's total screen time + top-5 apps, from UsageStats. */
    private fun usageSummary(): Pair<Long, List<Pair<String, Long>>> {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val start = (java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }).timeInMillis
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis()
            ) ?: return 0L to emptyList()
            val byPkg = mutableMapOf<String, Long>()
            for (st in stats) {
                if (st.totalTimeInForeground > 0 && st.packageName != packageName) {
                    byPkg[st.packageName] = (byPkg[st.packageName] ?: 0L) + st.totalTimeInForeground
                }
            }
            val total = byPkg.values.sum()
            val top = byPkg.entries.sortedByDescending { it.value }.take(5)
                .map { appLabel(it.key) to it.value }
            total to top
        } catch (e: Exception) {
            0L to emptyList()
        }
    }

    private fun durText(ms: Long): String {
        val mins = (ms / 60000).toInt()
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "$h ঘণ্টা $m মিনিট" else "$m মিনিট"
    }

    // ---- Tab 1: Focus — the main feature ----
    private fun tabFocus(content: LinearLayout) {
        val focusCard = Ui.card(this)
        focusCard.addView(Ui.cardTitle(this, Lang.t(this, "card_focus_session")))
        focusCard.addView(Ui.label(this, "শুরু করলে সময় শেষ হওয়ার আগে বাতিল করা যাবে না"))
        focusCard.addView(Ui.primaryButton(this, "25 মিনিট ফোকাস") { startFocus(25) })
        focusCard.addView(Ui.secondaryButton(this, "1 ঘণ্টা ফোকাস") { startFocus(60) })
        focusCard.addView(Ui.secondaryButton(this, "2 ঘণ্টা ফোকাস") { startFocus(120) })
        focusCard.addView(Ui.secondaryButton(this, "নিজের মতো সময় ফোকাস") { customFocusFlow() })
        focusCard.addView(Ui.secondaryButton(this, "স্টাডি অ্যাপ বাছাই (ফোকাসে শুধু এগুলোই চলবে)") { pickDefaultAllowedFlow() })
        val preset = Store.focusDefaultAllowed(this)
        focusCard.addView(Ui.secondary(this,
            if (preset.isEmpty()) "স্টাডি অ্যাপ বাছাই করা নেই — ফোকাসে পুরো ফোন লক থাকবে।"
            else "বাছাই করা: " + preset.sorted().joinToString(", ") { appLabel(it) }
        ).apply { setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0) })
        Store.focusEndMs(this).takeIf { it > System.currentTimeMillis() }?.let { end ->
            focusCard.addView(Ui.body(this, "ফোকাস চলছে, শেষ হবে: ${timeText(end)}").apply {
                setTextColor(Ui.BRIGHT)
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
            })
            // v1.12.0 UI/UX: elapsed-progress of the running session.
            val startMs = Store.focusStartMs(this)
            if (startMs > 0 && startMs < end) {
                val pct = ((System.currentTimeMillis() - startMs) * 100 / (end - startMs)).toInt()
                focusCard.addView(Ui.progressBar(this, pct))
            }
        }
        content.addView(focusCard)

        // ---- Named focus presets (v1.4): one tap starts the whole setup ----
        val presetCard = Ui.card(this)
        presetCard.addView(Ui.cardHeader(this, Lang.t(this, "card_presets")) {
            showScrollableDialog("আমার প্রিসেট", "নাম দিয়ে প্রিসেট বানালে এক ট্যাপে সেই ফোকাস শুরু হয় — যেমন 'পড়াশোনা', ২ ঘণ্টা, শুধু বইয়ের অ্যাপ চলবে। একই নামে আবার বানালে আগেরটা বদলে যায়। প্রিসেট মোছা সঙ্গে সঙ্গে হয়, চলমান সেশনে কোনো প্রভাব পড়ে না।")
        })
        val presets = Store.focusPresets(this)
        if (presets.isEmpty()) {
            presetCard.addView(Ui.secondary(this, "কোনো প্রিসেট নেই"))
        }
        for (p in presets) {
            presetCard.addView(Ui.body(this, "${p.name} — ${p.minutes} মিনিট"))
            presetCard.addView(Ui.secondary(this,
                if (p.allowed.isEmpty()) "সব অ্যাপ লক থাকবে"
                else "চলবে: " + p.allowed.sorted().joinToString(", ") { appLabel(it) }
            ))
            presetCard.addView(Ui.primaryButton(this, "${p.name} শুরু করো") { startPreset(p) })
            presetCard.addView(Ui.secondaryButton(this, "প্রিসেট মুছো: ${p.name}") {
                Store.saveFocusPresets(this, Store.focusPresets(this).filter { it.name != p.name })
                buildUi()
            })
        }
        presetCard.addView(Ui.secondaryButton(this, "নতুন প্রিসেট বানাও") { addPresetFlow() })
        content.addView(presetCard)

        // ---- Notification silence during focus (v1.4) ----
        val quietCard = Ui.card(this)
        quietCard.addView(Ui.cardHeader(this, Lang.t(this, "card_notifications")) {
            showScrollableDialog("নোটিফিকেশন", "চালু থাকলে ফোকাস চলাকালে Do Not Disturb নিজে থেকে অন হয়, শেষ হলে আগের অবস্থায় ফেরে। অ্যালার্ম বাজবে।")
        })
        quietCard.addView(Ui.statusRow(this, "ফোকাস/লকের সময় নোটিফিকেশন চুপ", Store.silenceDuringLock(this)))
        quietCard.addView(Ui.secondaryButton(this,
            if (Store.silenceDuringLock(this)) "বন্ধ করো" else "চালু করো") { toggleSilence() })
        content.addView(quietCard)
    }

    private fun startPreset(p: FocusPreset) {
        AlertDialog.Builder(this)
            .setTitle("${p.name} — ${p.minutes} মিনিট ফোকাস?")
            .setMessage("শুরু করলে সময় শেষ হওয়ার আগে কোনোভাবেই বাতিল করা যাবে না।")
            .setPositiveButton("শুরু করো") { _, _ ->
                verifyPin {
                    val nowMs = System.currentTimeMillis()
                    Store.setFocusStartMs(this, nowMs)
                    Store.setFocusEndMs(this, nowMs + p.minutes * 60_000L)
                    Store.setFocusSessionAllowed(this, p.allowed)
                    AdminHelper.applyLockTaskPackages(this, p.allowed)
                    Store.logTamper(this, "Focus preset started: ${p.name} (${p.minutes} min)")
                    startActivity(Intent(this, LockActivity::class.java))
                }
            }
            .setNegativeButton("না", null).show()
    }

    private fun addPresetFlow() {
        val nameInput = EditText(this).apply { hint = "প্রিসেটের নাম, যেমন: পড়াশোনা" }
        AlertDialog.Builder(this).setTitle("নতুন প্রিসেট — নাম").setView(nameInput)
            .setPositiveButton("পরের ধাপ") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    toast("একটা নাম দাও")
                    return@setPositiveButton
                }
                val minInput = EditText(this).apply {
                    hint = "কত মিনিট? যেমন: 120"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                AlertDialog.Builder(this).setTitle("নতুন প্রিসেট — সময়").setView(minInput)
                    .setPositiveButton("পরের ধাপ") { _, _ ->
                        val mins = minInput.text.toString().toIntOrNull()
                        if (mins == null || mins < 1 || mins > 24 * 60) {
                            toast("1 থেকে 1440 মিনিটের মধ্যে দাও")
                            return@setPositiveButton
                        }
                        pickAppsMulti("এই প্রিসেটে কোন অ্যাপ চলবে?", Store.focusDefaultAllowed(this)) { selected ->
                            val list = Store.focusPresets(this).filter { it.name != name } +
                                FocusPreset(name, mins, selected)
                            Store.saveFocusPresets(this, list)
                            toast("প্রিসেট তৈরি: $name")
                            buildUi()
                        }
                    }
                    .setNegativeButton("বাতিল", null).show()
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun toggleSilence() {
        val target = !Store.silenceDuringLock(this)
        Store.setSilenceDuringLock(this, target)
        if (target) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                toast("আরেকটা ছোট অনুমতি লাগবে: Do Not Disturb অ্যাক্সেস — সেটিংস পেজ খুলছে")
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } else {
                toast("নোটিফিকেশন সাইলেন্স চালু")
            }
        } else {
            toast("নোটিফিকেশন সাইলেন্স বন্ধ")
        }
        buildUi()
    }

    // ---- Tab 2: Schedule ----
    private fun tabSchedule(content: LinearLayout) {
        val scheduleCard = Ui.card(this)
        scheduleCard.addView(Ui.cardTitle(this, Lang.t(this, "card_schedules")))
        scheduleCard.addView(Ui.label(this, "ঐচ্ছিক — প্রতিদিন একই সময়ে অটো লক"))
        scheduleList(scheduleCard)
        scheduleCard.addView(Ui.secondaryButton(this, "নতুন শিডিউল যোগ করো") { addScheduleFlow() })
        content.addView(scheduleCard)
    }

    // ---- Tab 3: App limits ----
    private fun tabLimits(content: LinearLayout) {
        val limitsCard = Ui.card(this)
        limitsCard.addView(Ui.cardTitle(this, Lang.t(this, "card_app_limits")))
        limitsCard.addView(Ui.label(this, "প্রতিদিনের সীমা — পার হলেই সেদিনের জন্য বন্ধ"))
        appLimitList(limitsCard)
        limitsCard.addView(Ui.secondaryButton(this, "অ্যাপে লিমিট বসাও") { addLimitFlow() })
        content.addView(limitsCard)

        // ---- Blocked websites (v1.4, Chrome) ----
        val siteCard = Ui.card(this)
        siteCard.addView(Ui.cardHeader(this, Lang.t(this, "card_site_block")) {
            showScrollableDialog("ওয়েবসাইট ব্লক", "Device Owner চালু থাকলে Chrome নিজেই ব্লক/Allow তালিকা মানে — ইনকগনিটো বন্ধ থাকে, SafeSearch জোর করে চালু থাকে। Allow তালিকার সাইট কখনো ব্লক হয় না — ব্লক তালিকা বা ক্যাটাগরিতে থাকলেও চলবে।")
        })
        siteCard.addView(Ui.label(this, "যেকোনো ব্রাউজারে এই সাইটগুলো খুললেই সঙ্গে সঙ্গে ব্লক"))
        // ---- v1.9.0: Chrome's own managed blocking (Device Owner layer) ----
        siteCard.addView(
            Ui.statusRow(
                this, "Chrome সরাসরি ব্লক (Device Owner)",
                AdminHelper.isDeviceOwner(this), "চালু", "Owner চালু হলে চালু হবে"
            )
        )
        // ---- v1.12.0 Stage 5: wipe Chrome's data (Device Owner) ----
        // A reset action, not a weakening — but it destroys his Chrome
        // logins, so it is confirm + PIN gated and says so plainly.
        siteCard.addView(Ui.secondaryButton(this, "Chrome-এর সব ডেটা মুছো (কুকি/হিস্ট্রি/লগইন)") {
            AlertDialog.Builder(this)
                .setTitle("Chrome-এর সব ডেটা মুছবে?")
                .setMessage("Chrome-এর কুকি, হিস্ট্রি, ক্যাশ আর সেভ করা লগইন — সব মুছে যাবে। সব সাইট থেকে লগআউট হয়ে যাবে, আবার লগইন করতে হবে। এটা ফেরানো যাবে না।")
                .setPositiveButton("মুছো") { _, _ ->
                    verifyPin {
                        if (AdminHelper.isDeviceOwner(this)) {
                            AdminHelper.clearChromeData(this) { ok ->
                                Store.logTamper(this, "Chrome data clear requested, success=$ok")
                                toast(if (ok) "Chrome-এর ডেটা মোছা হয়েছে" else "ডেটা মোছা যায়নি — আবার চেষ্টা করো")
                            }
                        } else {
                            toast("এটা করতে Device Owner লাগবে — Owner চালু হলে আবার চেষ্টা করো")
                        }
                    }
                }
                .setNegativeButton("বাতিল", null).show()
        })
        val sites = Store.blockedSites(this)
        if (sites.isEmpty()) {
            siteCard.addView(Ui.secondary(this, "কোনো সাইট ব্লক করা নেই।"))
        }
        for (site in sites) {
            siteCard.addView(Ui.body(this, site))
            siteCard.addView(Ui.secondaryButton(this, "তুলে দাও: $site (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("remove_site:$site", "ওয়েবসাইট ব্লক তোলা: $site")
                }
            })
        }
        // ---- category filters (v1.6.0): one tap blocks a whole category ----
        siteCard.addView(Ui.label(this, "ক্যাটাগরি ফিল্টার — এক ট্যাপে পুরো ক্যাটাগরির সব সাইট ব্লক"))
        val enabledCats = Store.siteCategories(this)
        for (cat in SiteCategories.ALL) {
            if (cat.id in enabledCats) {
                siteCard.addView(Ui.body(this, "${cat.labelBn} — চালু আছে (${cat.domains.size}টি সাইট)"))
                siteCard.addView(Ui.secondaryButton(this, "বন্ধ করো: ${cat.labelBn} (24 ঘণ্টা লাগবে)") {
                    verifyPin {
                        requestWeakening("remove_category:${cat.id}", "ক্যাটাগরি ফিল্টার তোলা: ${cat.labelBn}")
                    }
                })
            } else {
                siteCard.addView(Ui.secondaryButton(this, "চালু করো: ${cat.labelBn} (${cat.domains.size}টি সাইট)") {
                    Store.saveSiteCategories(this, Store.siteCategories(this) + cat.id)
                    AdminHelper.syncBrowserRestrictions(this) // v1.9.0: Chrome managed config follows at once
                    Store.logTamper(this, "Site category filter enabled: ${cat.id}")
                    toast("চালু হয়েছে: ${cat.labelBn}")
                    buildUi()
                })
            }
        }
        siteCard.addView(Ui.secondaryButton(this, "সাইট যোগ করো") { addSiteFlow() })

        // ---- Allow list (v1.7.0, Tamim's request) ----
        siteCard.addView(Ui.label(this, "Allow তালিকা — এই সাইটগুলো কখনো ব্লক হবে না, ব্লক/ক্যাটাগরির উপর দিয়েও চলবে"))
        val allowSites = Store.siteAllowList(this)
        if (allowSites.isEmpty()) {
            siteCard.addView(Ui.secondary(this, "Allow তালিকা খালি।"))
        }
        for (site in allowSites) {
            siteCard.addView(Ui.body(this, site))
            siteCard.addView(Ui.secondaryButton(this, "তুলে দাও: $site") {
                Store.saveSiteAllowList(this, Store.siteAllowList(this) - site)
                AdminHelper.syncBrowserRestrictions(this) // v1.9.0: Chrome managed config follows at once
                Store.logTamper(this, "Site removed from allow list: $site")
                toast("তুলে দেওয়া হয়েছে: $site")
                buildUi()
            })
        }
        siteCard.addView(Ui.secondaryButton(this, "Allow সাইট যোগ করো") { addAllowSiteFlow() })
        if (Store.siteAllowOnly(this)) {
            siteCard.addView(Ui.body(this, "শুধু Allow তালিকার সাইট চলছে — বাকি সব সাইট ব্লক"))
            siteCard.addView(Ui.secondaryButton(this, "বন্ধ করো: শুধু-Allow মোড (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("allow_only_off", "শুধু-Allow মোড বন্ধ করা")
                }
            })
        } else {
            siteCard.addView(Ui.secondaryButton(this, "চালু করো: শুধু Allow তালিকার সাইট চলবে") {
                val enable = {
                    Store.setSiteAllowOnly(this, true)
                    AdminHelper.syncBrowserRestrictions(this) // v1.9.0: Chrome gets the "*" block + allow exceptions
                    Store.logTamper(this, "Allow-only website mode enabled")
                    toast("চালু হয়েছে — এখন শুধু Allow তালিকার সাইট খুলবে")
                    buildUi()
                }
                if (Store.siteAllowList(this).isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Allow তালিকা খালি")
                        .setMessage("তালিকা খালি থাকলে কোনো ওয়েবসাইটই খুলবে না। তবু চালু করবে?")
                        .setPositiveButton("হ্যাঁ, চালু করো") { _, _ -> enable() }
                        .setNegativeButton("না", null).show()
                } else {
                    enable()
                }
            })
        }
        content.addView(siteCard)
    }

    private fun addAllowSiteFlow() {
        val input = EditText(this).apply {
            hint = "যেমন: classroom.google.com বা bbc.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("Allow তালিকায় সাইট").setView(input)
            .setPositiveButton("যোগ করো") { _, _ ->
                var host = input.text.toString().trim().lowercase()
                    .removePrefix("https://").removePrefix("http://").removePrefix("www.")
                // v1.8.0 audit: strip a ":port" suffix too — "bbc.com:8080"
                // used to be stored whole and then never matched the
                // browser's bare host.
                host = host.substringBefore("/").substringBefore("?").substringBefore(":")
                if (host.isBlank() || !host.contains(".")) {
                    toast("ঠিকমতো সাইটের নাম দাও, যেমন bbc.com")
                } else {
                    Store.saveSiteAllowList(this, Store.siteAllowList(this) + host)
                    AdminHelper.syncBrowserRestrictions(this) // v1.9.0: Chrome managed config follows at once
                    Store.logTamper(this, "Website allowed: $host")
                    toast("Allow তালিকায় যোগ হয়েছে: $host")
                    buildUi()
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun addSiteFlow() {
        val input = EditText(this).apply {
            hint = "যেমন: facebook.com বা youtube.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle("ওয়েবসাইট ব্লক").setView(input)
            .setPositiveButton("ব্লক করো") { _, _ ->
                var host = input.text.toString().trim().lowercase()
                    .removePrefix("https://").removePrefix("http://").removePrefix("www.")
                // v1.8.0 audit: strip a ":port" suffix too — "bbc.com:8080"
                // used to be stored whole and then never matched the
                // browser's bare host.
                host = host.substringBefore("/").substringBefore("?").substringBefore(":")
                if (host.isBlank() || !host.contains(".")) {
                    toast("ঠিকমতো সাইটের নাম দাও, যেমন facebook.com")
                } else {
                    Store.saveBlockedSites(this, Store.blockedSites(this) + host)
                    AdminHelper.syncBrowserRestrictions(this) // v1.9.0: Chrome managed config follows at once
                    Store.logTamper(this, "Website blocked: $host")
                    toast("ব্লক হয়েছে: $host")
                    buildUi()
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---- Tab 4: Settings — permissions, security, backup, log ----
    /** v1.15.0 (Tamim's ask, same as OwnerControl): language + theme
     *  pickers. Both are pure appearance — no lock behaviour depends on
     *  them, so they change instantly with no PIN. Names/details of the
     *  card itself follow the chosen language; the pickers' own labels
     *  stay readable in every language (native names). */
    private fun appearanceCard(content: LinearLayout) {
        val card = Ui.card(this)
        card.addView(Ui.cardTitle(this, Lang.t(this, "appearance")))
        card.addView(Ui.label(this, Lang.t(this, "language")))
        val curLang = Store.appLang(this)
        for ((code, name) in Lang.LANGS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 7), 0, Ui.dp(this@MainActivity, 7))
                addView(Ui.body(this@MainActivity, name),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.chip(this@MainActivity, if (code == curLang) "●" else "○", code == curLang))
                setOnClickListener {
                    if (code != curLang) { Store.setAppLang(this@MainActivity, code); buildUi() }
                }
            }
            card.addView(row)
        }
        card.addView(Ui.gap(this, 6))
        card.addView(Ui.label(this, Lang.t(this, "theme")))
        val curTheme = Store.themeId(this)
        var rowBox: LinearLayout? = null
        Themes.ALL.forEachIndexed { idx, th ->
            if (idx % 2 == 0) {
                rowBox = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 4))
                }
                card.addView(rowBox)
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6),
                    Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 6))
                val swatch = android.view.View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        if (th.bgStops.size > 1) th.bgStops else intArrayOf(th.bg, th.accent)
                    ).apply { cornerRadius = Ui.dp(this@MainActivity, 12).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 40))
                }
                addView(swatch)
                addView(TextView(this@MainActivity).apply {
                    text = (if (th.id == curTheme) "✓ " else "") + th.displayName
                    textSize = 12f
                    setTextColor(if (th.id == curTheme) Ui.ACCENT else Ui.SECONDARY)
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, Ui.dp(this@MainActivity, 4), 0, 0)
                })
                setOnClickListener {
                    if (th.id != curTheme) {
                        Store.setThemeId(this@MainActivity, th.id)
                        Themes.apply(th.id)
                        buildUi()
                    }
                }
            }
            rowBox?.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(card)
    }

    private fun tabSettings(content: LinearLayout) {
        appearanceCard(content)
        // ALL permission settings in ONE dedicated card (Tamim's request).
        val permCard = Ui.card(this)
        permCard.addView(Ui.cardTitle(this, Lang.t(this, "card_perm_settings")))
        permCard.addView(Ui.label(this, "সব পারমিশন এক জায়গায়"))
        permCard.addView(Ui.statusRow(this, "Accessibility (অ্যাপ ব্লকার)", AdminHelper.isAccessibilityOn(this)))
        if (!AdminHelper.isAccessibilityOn(this)) {
            permCard.addView(Ui.secondaryButton(this, "Accessibility চালু করো") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        permCard.addView(Ui.statusRow(this, "Usage Access (লিমিট ও হিসাব)", AdminHelper.hasUsageAccess(this)))
        if (!AdminHelper.hasUsageAccess(this)) {
            permCard.addView(Ui.secondaryButton(this, "Usage Access দাও") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
        }
        permCard.addView(Ui.statusRow(this, "Overlay (শেড গার্ড)", AdminHelper.hasOverlayPermission(this)))
        if (!AdminHelper.hasOverlayPermission(this)) {
            permCard.addView(Ui.secondaryButton(this, "Overlay Permission দাও") {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            })
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        permCard.addView(Ui.statusRow(this, "Do Not Disturb অ্যাক্সেস (নোটিফিকেশন সাইলেন্স)", nm.isNotificationPolicyAccessGranted))
        if (!nm.isNotificationPolicyAccessGranted) {
            permCard.addView(Ui.secondaryButton(this, "DND অ্যাক্সেস দাও") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            })
        }
        permCard.addView(Ui.statusRow(this, "Device Admin", AdminHelper.isAdminActive(this)))
        if (!AdminHelper.isAdminActive(this)) {
            permCard.addView(Ui.secondaryButton(this, "Device Admin চালু করো") {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminHelper.adminComponent(this@MainActivity))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "FocusLock-এর কড়া সুরক্ষার জন্য দরকার।")
                }
                startActivity(intent)
            })
        }
        permCard.addView(Ui.statusRow(this, "Device Owner (Stage B)", AdminHelper.isDeviceOwner(this)))
        // v1.12.0 UI/UX: one-glance progress over the six checks above.
        val permChecks = listOf(
            AdminHelper.isAccessibilityOn(this),
            AdminHelper.hasUsageAccess(this),
            AdminHelper.hasOverlayPermission(this),
            nm.isNotificationPolicyAccessGranted,
            AdminHelper.isAdminActive(this),
            AdminHelper.isDeviceOwner(this)
        )
        val permDone = permChecks.count { it }
        permCard.addView(Ui.gap(this, 6))
        permCard.addView(Ui.secondary(this, "$permDone/${permChecks.size} অনুমতি চালু"))
        permCard.addView(Ui.progressBar(this, permDone * 100 / permChecks.size))
        content.addView(permCard)

        // ---- v1.8.0: Device Owner activation guide (Dhizuku-style setup) ----
        // Android gives NO app a self-grant permission tap for Device Owner —
        // not even Dhizuku: it too relies on the same one-time shell-level
        // set-device-owner, just triggered via Shizuku instead of a typed
        // command. This card is the honest in-app version of that setup:
        // status + checklist + both command paths + a verify button. The
        // one-tap Shizuku trigger itself is NOT implemented (see the v1.8.0
        // audit report for exactly why and what it would take) — this card
        // never pretends otherwise. SETUP ONLY: it adds no removal path;
        // owner removal stays PIN + 24h in the সুরক্ষা card below.
        if (!AdminHelper.isDeviceOwner(this)) {
            val ownerCard = Ui.card(this)
            ownerCard.addView(Ui.cardHeader(this, "Device Owner চালু করা (Stage B)") {
                showScrollableDialog("Device Owner (Stage B)",
                    "Device Owner চালু হলেই FocusLock সবচেয়ে কড়া হয় — আনইনস্টল, ফোর্স-স্টপ, Settings, তারিখ-সময় বদল সব বন্ধ হয়ে যায়।\n\n" +
                        "চালু করা একবারের কাজ, আর কোনো অ্যাপই শুধু এক ট্যাপে এই অনুমতি পায় না — Android-এর নিয়মে একবার শেল-স্তরের কমান্ড লাগেই (Dhizuku-র ক্ষেত্রেও একই নিয়ম, শুধু কমান্ডটা Shizuku দিয়ে চালানো হয়)।")
            })
            ownerCard.addView(Ui.statusRow(this, "Device Owner অবস্থা", false, "চালু আছে", "এখনো হয়নি"))
            ownerCard.addView(Ui.gap(this, 8))
            ownerCard.addView(Ui.body(this, "চালু করার আগের চেকলিস্ট"))
            ownerCard.addView(Ui.secondary(this,
                "১) আগে পুরো ব্যাকআপ নাও — সেটিংস ব্যাকআপ (নিচের কার্ডে) আর গুরুত্বপূর্ণ ফাইলের কপি।\n" +
                    "২) ফোনের সব অ্যাকাউন্ট (Googleসহ সব) সাময়িকভাবে সরাও — অ্যাকাউন্ট থাকলে Android Device Owner বসাতে দেয় না।\n" +
                    "৩) কমান্ডটা চালাও (নিচে দুটো পথ)।\n" +
                    "৪) চালু হলে অ্যাকাউন্টগুলো আবার যোগ করো, তারপর এখানে 'যাচাই করো' চাপো।"))
            ownerCard.addView(Ui.gap(this, 8))
            ownerCard.addView(Ui.body(this, "কমান্ড"))
            ownerCard.addView(Ui.secondary(this, "adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver"))
            ownerCard.addView(Ui.secondaryButton(this, "কমান্ড কপি করো") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("FocusLock ADB",
                    "adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver"))
                toast("কপি হয়েছে")
            })
            ownerCard.addView(Ui.gap(this, 8))
            ownerCard.addView(Ui.body(this, "কোন পথে চালাবে"))
            ownerCard.addView(Ui.secondary(this,
                "পথ ১ — PC থেকে: ফোন USB দিয়ে কম্পিউটারে লাগিয়ে উপরের কমান্ডটা চালাও।\n" +
                    "পথ ২ — ফোন থেকেই (Termux + Shizuku): Shizuku চালু অবস্থায় Termux-এ rish দিয়ে প্রিভিলেজড শেল খুলে শুধু dpm অংশটা চালাও:\n" +
                    "dpm set-device-owner com.focuslock.app/.AdminReceiver\n" +
                    "ডিবাগিং চালুই থাকবে — FocusLock ইচ্ছা করেই ডিবাগিং বন্ধ করে না, যাতে Termux/Shizuku চলতে পারে।"))
            ownerCard.addView(Ui.primaryButton(this, "যাচাই করো") {
                if (AdminHelper.isDeviceOwner(this@MainActivity)) {
                    // Owner just landed: all restrictions go on together,
                    // through the same path AdminReceiver.onEnabled uses.
                    AdminHelper.applyStrictPolicies(this@MainActivity)
                    Engine.tick(this@MainActivity)
                    toast("Device Owner চালু আছে — সব restriction একসাথে চালু করা হয়েছে")
                } else {
                    toast("এখনো চালু হয়নি — কমান্ডটা চালিয়ে আবার যাচাই করো")
                }
                buildUi()
            })
            content.addView(ownerCard)
        }

        val securityCard = Ui.card(this)
        securityCard.addView(Ui.cardHeader(this, Lang.t(this, "card_security")) {
            showScrollableDialog("সুরক্ষা",
                "জরুরি বের-হওয়ার পথ: Owner সরালে আগে সব restriction উঠবে, suspend/hide করা অ্যাপ ফিরবে, তারপর Owner যাবে — কোনো অ্যাপ আটকে থাকবে না।\n\n" +
                    "অ্যাকাউন্ট ব্লক: চালু থাকলে Settings থেকে Google বা অন্য কোনো অ্যাকাউন্ট যোগ করা বা মোছা যাবে না। অ্যাপের ভেতরের লগইন আগের মতোই চলবে।\n\n" +
                    "লগ: Device Owner চালু থাকলে Android নিজে নিরাপত্তা ও নেটওয়ার্ক লগ রাখে — লক স্ক্রিন খোলার চেষ্টা, ADB ব্যবহার ইত্যাদির প্রমাণ। Network লগে শুধু মোট ইভেন্ট সংখ্যা দেখায়।")
        })
        pendingInfo(securityCard)
        securityCard.addView(Ui.secondaryButton(this, "PIN সেট / বদলাও") { setPinFlow() })
        securityCard.addView(Ui.secondaryButton(this, "Protection Off করার আবেদন (24 ঘণ্টা লাগবে)") {
            verifyPin {
                requestWeakening("protection_off", "Protection Off")
            }
        })
        if (!Store.isProtectionOn(this)) {
            securityCard.addView(Ui.primaryButton(this, "Protection আবার On করো") {
                Store.setProtectionOn(this, true)
                AdminHelper.applyStrictPolicies(this)
                toast("Protection On হয়েছে")
                buildUi()
            })
        }
        securityCard.addView(Ui.gap(this, 8))
        securityCard.addView(Ui.secondary(this,
            "Device Owner (Stage B): " +
                if (AdminHelper.isDeviceOwner(this)) "চালু আছে"
                else "এখনো না — যেদিন বলবে, ব্যাকআপ নিয়ে সেটআপ করব"))
        if (AdminHelper.isDeviceOwner(this)) {
            securityCard.addView(Ui.secondaryButton(this, "Device Owner সরানোর আবেদন (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("remove_device_owner", "Device Owner removal")
                }
            })
        }
        if (Store.isExitFailed(this)) {
            securityCard.addView(Ui.secondary(this,
                "⚠️ Device Owner সরানো অসম্পূর্ণ থেকে গেছে — কোনো restriction বা অ্যাপ এখনো আটকে থাকায় Owner সরানো হয়নি (ইচ্ছা করেই: আটকে রেখে Owner সরলে বিপদ)। নিচে আবার চেষ্টা করো।"))
            securityCard.addView(Ui.secondaryButton(this, "Owner সরানো আবার চেষ্টা করো (PIN লাগবে)") {
                verifyPin {
                    val ok = AdminHelper.safelyRemoveDeviceOwner(this@MainActivity)
                    Store.setExitFailed(this@MainActivity, !ok)
                    toast(if (ok) "Device Owner সরে গেছে" else "আবারো অসম্পূর্ণ — ট্যাম্পার লগে কারণ আছে")
                    buildUi()
                }
            })
        }
        // ---- v1.12.0 Stage 2: account-modify block ----
        // (ownerActive is declared further down at the install card;
        // this card reads the owner state directly.)
        val ownerNow = AdminHelper.isDeviceOwner(this)
        securityCard.addView(Ui.gap(this, 8))
        val accountBlock = Store.accountBlockOn(this)
        securityCard.addView(Ui.statusRow(this, "অ্যাকাউন্ট যোগ/মোছা ব্লক (Device Owner)", accountBlock))
        if (!ownerNow) {
            securityCard.addView(Ui.secondary(this, "Device Owner চালু হলেই কার্যকর হবে"))
        }
        if (!accountBlock) {
            securityCard.addView(Ui.primaryButton(this, "অ্যাকাউন্ট ব্লক চালু করো") {
                Store.setAccountBlockOn(this, true)
                AdminHelper.applyAccountBlock(this)
                Store.logTamper(this, "Account-modify block turned ON")
                toast(if (ownerNow) "অ্যাকাউন্ট ব্লক চালু হয়েছে" else "সংরক্ষিত হয়েছে — Owner চালু হলেই কার্যকর হবে")
                buildUi()
            })
        } else {
            securityCard.addView(Ui.secondaryButton(this, "অ্যাকাউন্ট ব্লক বন্ধ করার আবেদন (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("account_block_off", "অ্যাকাউন্ট ব্লক বন্ধ করা")
                }
            })
        }

        // ---- v1.12.0 Stage 7: Factory Reset Protection ----
        securityCard.addView(Ui.gap(this, 8))
        securityCard.addView(Ui.body(this, "Factory Reset Protection (Device Owner)"))
        val frpEmail = Store.frpEmail(this)
        securityCard.addView(Ui.secondary(this,
            if (frpEmail.isBlank()) {
                "FRP বসানো নেই। সাবধান: ভুল ইমেইল দিলে রিসেটের পর নিজের ফোনেই আটকে যাবে।"
            } else {
                "FRP অ্যাকাউন্ট: $frpEmail"
            } + if (!ownerNow) " (Device Owner চালু হলেই কার্যকর হবে)" else ""))
        if (frpEmail.isBlank()) {
            securityCard.addView(Ui.secondaryButton(this, "FRP ইমেইল বসাও") { frpEmailFlow(frpChange = false) })
        } else {
            securityCard.addView(Ui.secondaryButton(this, "FRP ইমেইল বদলাও (24 ঘণ্টা লাগবে)") {
                verifyPin { frpEmailFlow(frpChange = true) }
            })
            securityCard.addView(Ui.secondaryButton(this, "FRP মুছে দাও (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("frp_change:EMPTY", "Factory Reset Protection মোছা")
                }
            })
        }

        // ---- v1.12.0 Stage 6: security / network logs (tamper evidence) ----
        securityCard.addView(Ui.gap(this, 8))
        securityCard.addView(Ui.body(this, "লগ (Device Owner)"))
        securityCard.addView(Ui.secondaryButton(this, "Security log দেখো") { showSecurityLogs() })
        securityCard.addView(Ui.secondaryButton(this, "Network log সারাংশ") { showNetworkLogSummary() })
        securityCard.addView(Ui.secondaryButton(this, "ADB কমান্ড কপি করো (Stage B-এর জন্য)") {
            val cmd = "adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FocusLock ADB", cmd))
            toast("কপি হয়েছে")
        })
        content.addView(securityCard)

        // ---- v1.10.0: app install / uninstall control (Device Owner) ----
        // Strengthening is immediate (install block ON, adding uninstall
        // protection); every weakening goes through PIN + 24h pending
        // (install_block_off / uninstall_unprotect:<pkg>). Without Device
        // Owner the choices still save — they take effect the moment
        // owner is active, and the card says so plainly.
        val installCard = Ui.card(this)
        installCard.addView(Ui.cardHeader(this, "অ্যাপ ইনস্টল ও আনইনস্টল (Device Owner)") {
            showScrollableDialog("অ্যাপ ইনস্টল ও আনইনস্টল",
                "ইনস্টল ব্লক চালু থাকলে নতুন কোনো অ্যাপই ইনস্টল হবে না — Play Store থেকেও না, APK ফাইল থেকেও না, অন্য অ্যাপের আপডেটও আটকে থাকবে। FocusLock আপডেটের আগে ব্লক বন্ধ করতে হবে (PIN + 24 ঘণ্টা)।\n\n" +
                    "আনইনস্টল-সুরক্ষা: এখানে যোগ করা অ্যাপ আনইনস্টল করা যাবে না। সুরক্ষা তোলাও PIN + 24 ঘণ্টা।")
        })
        val ownerActive = AdminHelper.isDeviceOwner(this)
        val installBlock = Store.installBlockOn(this)
        installCard.addView(Ui.statusRow(this, "নতুন অ্যাপ ইনস্টল ব্লক", installBlock))
        installCard.addView(Ui.secondary(this, when {
            !ownerActive -> "Device Owner চালু হলে কার্যকর হবে — এখন পছন্দগুলো শুধু সংরক্ষিত থাকবে।"
            installBlock -> "কার্যকর আছে — নতুন কোনো অ্যাপ ইনস্টল হবে না (FocusLock-এর আপডেটও না)।"
            else -> "Device Owner চালু আছে — ইনস্টল ব্লক এখন বন্ধ।"
        }))
        if (!installBlock) {
            installCard.addView(Ui.primaryButton(this, "ইনস্টল ব্লক চালু করো") {
                AlertDialog.Builder(this)
                    .setTitle("ইনস্টল ব্লক চালু করবে?")
                    .setMessage("সাবধান: চালু থাকলে নতুন কোনো অ্যাপ ইনস্টল করা যাবে না — Play Store থেকেও না, APK ফাইল থেকেও না, আর অন্য অ্যাপের আপডেটও আটকে থাকবে। এমনকি FocusLock-এর নিজের আপডেটও ইনস্টল হবে না। তাই FocusLock আপডেটের আগে এই ব্লক বন্ধ করতে হবে — বন্ধ করা দুর্বলীকরণ, তাই PIN + 24 ঘণ্টা সময় লাগবে। সত্যিই চালু করবে?")
                    .setPositiveButton("চালু করো") { _, _ ->
                        Store.setInstallBlockOn(this, true)
                        AdminHelper.applyInstallControls(this)
                        Store.logTamper(this, "Install block turned ON")
                        toast(if (ownerActive) "ইনস্টল ব্লক চালু হয়েছে" else "সংরক্ষিত হয়েছে — Owner চালু হলেই কার্যকর হবে")
                        buildUi()
                    }
                    .setNegativeButton("বাতিল", null).show()
            })
        } else {
            installCard.addView(Ui.secondaryButton(this, "ইনস্টল ব্লক বন্ধ করার আবেদন (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("install_block_off", "ইনস্টল ব্লক বন্ধ করা")
                }
            })
        }
        installCard.addView(Ui.gap(this, 8))
        installCard.addView(Ui.body(this, "আনইনস্টল-সুরক্ষিত অ্যাপ"))
        val protectedPkgs = Store.uninstallProtectedPkgs(this).sorted()
        if (protectedPkgs.isEmpty()) {
            installCard.addView(Ui.secondary(this, "কোনো অ্যাপ সুরক্ষিত নেই"))
        } else {
            for (pkg in protectedPkgs) {
                val label = appLabel(pkg)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
                }
                row.addView(TextView(this).apply {
                    text = label
                    textSize = 14f; setTextColor(Ui.TEXT)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = "সুরক্ষা তোলো"; isAllCaps = false; textSize = 12f
                    setTextColor(Ui.MUTED)
                    background = RowButtonStyle.raised(this@MainActivity)
                    setOnClickListener {
                        verifyPin { requestWeakening("uninstall_unprotect:$pkg", "$label-এর আনইনস্টল-সুরক্ষা তোলা") }
                    }
                })
                installCard.addView(row)
            }
        }
        installCard.addView(Ui.secondaryButton(this, "সুরক্ষায় অ্যাপ যোগ করো") {
            // FocusLock itself (already uninstall-blocked as the owner
            // app), the system-critical packages, and apps already
            // protected are not offered — same exclusions AdminHelper
            // enforces when applying.
            val already = Store.uninstallProtectedPkgs(this)
            val apps = pickerApps().filter {
                !AdminHelper.isProtectedPackage(this, it.first) && it.first !in already
            }
            if (apps.isEmpty()) {
                toast("যোগ করার মতো কোনো অ্যাপ পাওয়া যায়নি")
            } else {
                val pkgs = apps.map { it.first }
                val checked = BooleanArray(apps.size) { false }
                AlertDialog.Builder(this).setTitle("কোন অ্যাপগুলো আনইনস্টল-সুরক্ষিত থাকবে?")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, isChecked ->
                        checked[i] = isChecked
                    }
                    .setPositiveButton("যোগ করো") { _, _ ->
                        val selected = pkgs.filterIndexed { i, _ -> checked[i] }.toSet()
                        if (selected.isNotEmpty()) {
                            Store.saveUninstallProtectedPkgs(this, already + selected)
                            AdminHelper.applyInstallControls(this)
                            Store.logTamper(this, "Uninstall protection added: ${selected.joinToString()}")
                            toast("${selected.size}টি অ্যাপ সুরক্ষিত হয়েছে")
                            buildUi()
                        }
                    }
                    .setNegativeButton("বাতিল", null).show()
            }
        })
        content.addView(installCard)

        // ---- v1.11.0: network security (Device Owner) ----
        // Filtered Private DNS is a standing policy (always-on while the
        // flag is set); the network-settings lock is a preference whose
        // restrictions Engine applies only during hard locks. Strengthening
        // is immediate; both OFF paths are weakening (PIN + 24h pending:
        // dns_filter_off / network_lock_off). Without Device Owner the
        // choices still save and the card says so plainly.
        val netCard = Ui.card(this)
        netCard.addView(Ui.cardHeader(this, "নেটওয়ার্ক সুরক্ষা (Device Owner)") {
            showScrollableDialog("নেটওয়ার্ক সুরক্ষা",
                "DNS ফিল্টার: সব অ্যাপের ইন্টারনেট বেছে-নেওয়া ফিল্টার-DNS (Cloudflare Family / AdGuard / CleanBrowsing / নিজের পছন্দ) দিয়ে যায় — প্রাপ্তবয়স্ক ও ক্ষতিকর সাইট শুধু Chrome-এ নয়, সব অ্যাপেই DNS স্তরে আটকে যায়। ফিল্টার চালু থাকা অবস্থায় সমমানের ফ্যামিলি সার্ভিসে বদলানো সাথে সাথে হয়; দুর্বল বা অজানা ফিল্টারে বদলাতে PIN + 24 ঘণ্টা লাগে। কিছু পাবলিক WiFi-এর লগইন পেজ এই ফিল্টার চালু থাকলে খুলতে নাও পারে।\n\n" +
                    "নেটওয়ার্ক লক: ফোকাস সেশন বা শিডিউল-লক চলাকালে WiFi, হটস্পট ও এয়ারপ্লেন মোডের সেটিংস বদলানো যায় না — লক শেষ হলেই আবার খুলে যায়। সংযোগ বন্ধ হয় না, শুধু বদলানো আটকায়; তবে এই সময়ে নতুন WiFi নেটওয়ার্কেও যোগ দেওয়া যায় না।")
        })
        val dnsOn = Store.dnsFilterOn(this)
        netCard.addView(Ui.statusRow(this, "DNS ফিল্টার (পরিবার-বান্ধব)", dnsOn))
        netCard.addView(Ui.secondary(this, when {
            !ownerActive -> "Device Owner চালু হলে কার্যকর হবে — এখন পছন্দগুলো শুধু সংরক্ষিত থাকবে।"
            dnsOn -> "কার্যকর আছে — সব অ্যাপের ইন্টারনেট যাচ্ছে ${AdminHelper.dnsHost(this)} দিয়ে; প্রাপ্তবয়স্ক ও ক্ষতিকর সাইট DNS স্তরেই আটকে যায়।"
            else -> "Device Owner চালু আছে — DNS ফিল্টার এখন বন্ধ।"
        }))
        if (!dnsOn) {
            netCard.addView(Ui.primaryButton(this, "DNS ফিল্টার চালু করো") {
                AlertDialog.Builder(this)
                    .setTitle("DNS ফিল্টার চালু করবে?")
                    .setMessage("চালু হলে ফোনের সব অ্যাপের ইন্টারনেট বেছে-নেওয়া DNS (${AdminHelper.dnsProvider(this).label} — ${AdminHelper.dnsHost(this)}) দিয়ে যাবে — প্রাপ্তবয়স্ক ও ক্ষতিকর সাইট শুধু Chrome-এ নয়, সব অ্যাপেই DNS স্তরে আটকে যাবে। একটা কথা: কিছু পাবলিক WiFi-এর লগইন পেজ এই ফিল্টার চালু থাকলে খুলতে নাও পারে — ওই সময় দরকার হলে ফিল্টার বন্ধ করতে হবে, আর বন্ধ করা দুর্বলীকরণ বলে PIN + 24 ঘণ্টা লাগবে। সত্যিই চালু করবে?")
                    .setPositiveButton("চালু করো") { _, _ ->
                        Store.setDnsFilterOn(this, true)
                        AdminHelper.applyDnsFilter(this)
                        Store.logTamper(this, "DNS filter turned ON (${AdminHelper.dnsHost(this)})")
                        toast(if (ownerActive) "DNS ফিল্টার চালু হয়েছে" else "সংরক্ষিত হয়েছে — Owner চালু হলেই কার্যকর হবে")
                        buildUi()
                    }
                    .setNegativeButton("বাতিল", null).show()
            })
        } else {
            netCard.addView(Ui.secondaryButton(this, "DNS ফিল্টার বন্ধ করার আবেদন (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("dns_filter_off", "DNS ফিল্টার বন্ধ করা")
                }
            })
        }
        // v1.15.0: DNS provider picker (Tamim: AdGuard-ও যেন use করা যায়).
        netCard.addView(Ui.gap(this, 6))
        netCard.addView(Ui.label(this, "DNS সার্ভিস"))
        val curProvider = AdminHelper.dnsProvider(this)
        for (p in AdminHelper.DNS_PROVIDERS) {
            val hostText = if (p.id == "custom") {
                Store.dnsCustomHost(this).ifBlank { "হোস্ট সেট করা হয়নি" }
            } else p.host
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.body(this@MainActivity, p.label))
                    addView(Ui.label(this@MainActivity, hostText))
                }
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.chip(this@MainActivity, if (p.id == curProvider.id) "●" else "○", p.id == curProvider.id))
                setOnClickListener { chooseDnsProvider(p) }
            }
            netCard.addView(row)
        }
        netCard.addView(Ui.gap(this, 8))
        val netLockOn = Store.networkLockOn(this)
        netCard.addView(Ui.statusRow(this, "ফোকাস/লকের সময় WiFi-হটস্পট-এয়ারপ্লেন সেটিংস লক", netLockOn))
        if (!ownerActive) {
            netCard.addView(Ui.secondary(this, "Device Owner চালু হলেই কার্যকর হবে"))
        }
        if (!netLockOn) {
            netCard.addView(Ui.primaryButton(this, "নেটওয়ার্ক লক চালু করো") {
                Store.setNetworkLockOn(this, true)
                // If a hard lock is running right now, one engine tick
                // applies the restrictions immediately.
                Engine.tick(this)
                Store.logTamper(this, "Network lock turned ON")
                toast(if (ownerActive) "নেটওয়ার্ক লক চালু হয়েছে" else "সংরক্ষিত হয়েছে — Owner চালু হলেই কার্যকর হবে")
                buildUi()
            })
        } else {
            netCard.addView(Ui.secondaryButton(this, "নেটওয়ার্ক লক বন্ধ করার আবেদন (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("network_lock_off", "নেটওয়ার্ক লক বন্ধ করা")
                }
            })
        }
        content.addView(netCard)

        // ---- v1.12.0 Stage 1: keyguard + password hardening ----
        // Standing policies while the flag is ON (default): lockscreen
        // camera/widgets off, notification contents hidden on the lock
        // screen, device-PIN quality raised (bites at his NEXT PIN
        // change), screen auto-lock within 5 minutes. OFF = weakening
        // (PIN + 24h pending: keyguard_hardening_off). Without Device
        // Owner the choice still saves and the card says so plainly.
        val keyguardCard = Ui.card(this)
        keyguardCard.addView(Ui.cardHeader(this, "লক স্ক্রিন ও পাসওয়ার্ড (Device Owner)") {
            showScrollableDialog("লক স্ক্রিন ও পাসওয়ার্ড",
                "চালু থাকলে: লক স্ক্রিনে ক্যামেরা ও উইজেট বন্ধ থাকে, লক স্ক্রিনে নোটিফিকেশনের লেখা লুকানো থাকে, ডিভাইস PIN কমপক্ষে ৬ সংখ্যার জটিল হতে হবে (পরের বার PIN বদলানোর সময় থেকে কার্যকর), আর স্ক্রিন নিভলে ৫ মিনিটের মধ্যে অটো-লক হবে।")
        })
        val keyguardOn = Store.keyguardHardeningOn(this)
        keyguardCard.addView(Ui.statusRow(this, "লক স্ক্রিন হার্ডেনিং", keyguardOn))
        if (!ownerActive) {
            keyguardCard.addView(Ui.secondary(this, "Device Owner চালু হলেই কার্যকর হবে"))
        }
        if (!keyguardOn) {
            keyguardCard.addView(Ui.primaryButton(this, "হার্ডেনিং চালু করো") {
                Store.setKeyguardHardeningOn(this, true)
                AdminHelper.applyKeyguardHardening(this)
                Store.logTamper(this, "Keyguard hardening turned ON")
                toast(if (ownerActive) "লক স্ক্রিন হার্ডেনিং চালু হয়েছে" else "সংরক্ষিত হয়েছে — Owner চালু হলেই কার্যকর হবে")
                buildUi()
            })
        } else {
            keyguardCard.addView(Ui.secondaryButton(this, "হার্ডেনিং বন্ধ করার আবেদন (24 ঘণ্টা লাগবে)") {
                verifyPin {
                    requestWeakening("keyguard_hardening_off", "লক স্ক্রিন হার্ডেনিং বন্ধ করা")
                }
            })
        }
        content.addView(keyguardCard)

        // ---- v1.12.0 Stage 3: hide distracting apps ----
        // setApplicationHidden makes an app vanish from the launcher and
        // app lists while hidden. Adding is immediate; bringing one back
        // is weakening ("unhide_app:<pkg>"). FocusLock itself and the
        // system-critical packages are never offered (same exclusions as
        // AdminHelper enforces when applying).
        val hiddenCard = Ui.card(this)
        hiddenCard.addView(Ui.cardHeader(this, "অ্যাপ লুকিয়ে রাখা (Device Owner)") {
            showScrollableDialog("অ্যাপ লুকিয়ে রাখা", "লুকানো অ্যাপ launcher ও অ্যাপ তালিকা থেকে অদৃশ্য হয়ে যায় — খোলাই যায় না। ফিরিয়ে আনা দুর্বলীকরণ, তাই PIN + 24 ঘণ্টা লাগে।")
        })
        if (!ownerActive) {
            hiddenCard.addView(Ui.secondary(this, "Device Owner চালু হলেই কার্যকর হবে"))
        }
        val hiddenPkgs = Store.hiddenApps(this).sorted()
        if (hiddenPkgs.isEmpty()) {
            hiddenCard.addView(Ui.secondary(this, "কোনো অ্যাপ লুকানো নেই।"))
        } else {
            for (pkg in hiddenPkgs) {
                val label = appLabel(pkg)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
                }
                row.addView(TextView(this).apply {
                    text = label
                    textSize = 14f; setTextColor(Ui.TEXT)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = "ফিরিয়ে আনো"; isAllCaps = false; textSize = 12f
                    setTextColor(Ui.MUTED)
                    background = RowButtonStyle.raised(this@MainActivity)
                    setOnClickListener {
                        verifyPin { requestWeakening("unhide_app:$pkg", "$label ফিরিয়ে আনা") }
                    }
                })
                hiddenCard.addView(row)
            }
        }
        hiddenCard.addView(Ui.secondaryButton(this, "লুকানোর জন্য অ্যাপ যোগ করো") {
            val already = Store.hiddenApps(this)
            val apps = pickerApps().filter {
                !AdminHelper.isProtectedPackage(this, it.first) && it.first !in already
            }
            if (apps.isEmpty()) {
                toast("লুকানোর মতো কোনো অ্যাপ পাওয়া যায়নি")
            } else {
                val pkgs = apps.map { it.first }
                val checked = BooleanArray(apps.size) { false }
                AlertDialog.Builder(this).setTitle("কোন অ্যাপগুলো লুকিয়ে রাখবে?")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, isChecked ->
                        checked[i] = isChecked
                    }
                    .setPositiveButton("লুকিয়ে রাখো") { _, _ ->
                        val selected = pkgs.filterIndexed { i, _ -> checked[i] }.toSet()
                        if (selected.isNotEmpty()) {
                            Store.saveHiddenApps(this, already + selected)
                            AdminHelper.applyHiddenApps(this)
                            Store.logTamper(this, "Apps hidden: ${selected.joinToString()}")
                            toast("${selected.size}টি অ্যাপ লুকানো হয়েছে")
                            buildUi()
                        }
                    }
                    .setNegativeButton("বাতিল", null).show()
            }
        })
        content.addView(hiddenCard)

        // ---- Automation API (v1.6.0, PhoneLink) ----
        val autoCard = Ui.card(this)
        autoCard.addView(Ui.cardHeader(this, "অটোমেশন (PhoneLink)") {
            showScrollableDialog("অটোমেশন (PhoneLink)",
                "কমান্ড (explicit broadcast, action: com.focuslock.app.AUTOMATION):\n" +
                    "cmd=block_app (app, mins) · cmd=lock_full (mins) · cmd=focus (preset, mins ঐচ্ছিক) · " +
                    "cmd=unblock (app থাকলে শুধু ওই অ্যাপের টাইম-ব্লক; না থাকলে অটোমেশনের সব ব্লক ও অটোমেশন-সেশন)।\n\n" +
                    "unblock শুধু অটোমেশনের শুরু করা জিনিসই তুলতে পারে — হাতে শুরু করা ফোকাস, শিডিউল আর দৈনিক লিমিট কখনোই না।\n\n" +
                    "Token শুধু hash আকারে থাকে, ব্যাকআপেও যায় না — আনইনস্টল করলে আবার বসাতে হবে।")
        })
        val autoOn = Store.automationTokenHash(this).isNotBlank()
        autoCard.addView(Ui.secondary(this,
            if (autoOn) "API চালু আছে — token মিললেই PhoneLink কমান্ড পাঠাতে পারবে।"
            else "API বন্ধ আছে — token বসালে তবেই চালু হবে।"))
        autoCard.addView(Ui.secondaryButton(this, "Token বসাও / বদলাও (PIN লাগবে)") {
            verifyPin { setAutomationTokenFlow() }
        })
        if (autoOn) {
            autoCard.addView(Ui.secondaryButton(this, "Token সরাও — API বন্ধ করো (PIN লাগবে)") {
                verifyPin {
                    Store.setAutomationTokenHash(this, "")
                    Store.logTamper(this, "Automation token removed — API off")
                    toast("Automation API বন্ধ হয়েছে")
                    buildUi()
                }
            })
        }
        content.addView(autoCard)

        // ---- Settings backup / restore (v1.4) ----
        val backupCard = Ui.card(this)
        backupCard.addView(Ui.cardHeader(this, Lang.t(this, "card_backup")) {
            showScrollableDialog("সেটিংস ব্যাকআপ", "নতুন সংস্করণ বসাতে আনইনস্টল করলে সেটিংস মুছে যায়। ব্যাকআপ ফাইল রাখলে এক ট্যাপে শিডিউল, লিমিট, প্রিসেট, সাইট-ব্লক সব ফিরে আসবে। (PIN ব্যাকআপে থাকে না — আবার সেট করতে হবে।)")
        })
        backupCard.addView(Ui.secondaryButton(this, "ব্যাকআপ নাও (ফাইলে সেভ করো)") {
            exportLauncher.launch("focuslock-backup.json")
        })
        backupCard.addView(Ui.secondaryButton(this, "ব্যাকআপ থেকে রিস্টোর করো") {
            importLauncher.launch(arrayOf("application/json"))
        })
        content.addView(backupCard)

        val logCard = Ui.card(this)
        logCard.addView(Ui.cardTitle(this, Lang.t(this, "card_tamper_log")))
        val log = Store.tamperLog(this)
        logCard.addView(Ui.secondary(this,
            if (log.isBlank()) "এখনো কোনো চেষ্টা হয়নি।"
            else log.lines().takeLast(20).joinToString("\n")
        ))
        content.addView(logCard)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun timeText(ms: Long) =
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))
    private fun minText(mins: Int) = "%02d:%02d".format(mins / 60, mins % 60)

    // ---------------- schedules ----------------

    private fun scheduleList(card: LinearLayout) {
        val list = Store.schedules(this)
        if (list.isEmpty()) {
            card.addView(Ui.secondary(this, "কোনো শিডিউল নেই। যেমন: রাত 11:00 → ভোর 5:00।"))
            return
        }
        val dayNames = listOf("সোম", "মঙ্গল", "বুধ", "বৃহস্পতি", "শুক্র", "শনি", "রবি")
        for (s in list) {
            val days = if (s.daysMask == 127) "প্রতিদিন"
            else (0..6).filter { s.daysMask and (1 shl it) != 0 }.joinToString(", ") { dayNames[it] }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
            }
            row.addView(TextView(this).apply {
                text = "${s.label} — ${minText(s.startMin)} → ${minText(s.endMin)} ($days)"
                textSize = 14f; setTextColor(Ui.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "মুছুন"; isAllCaps = false; textSize = 12f
                setTextColor(Ui.MUTED)
                background = RowButtonStyle.raised(this@MainActivity)
                setOnClickListener {
                    verifyPin {
                        requestWeakening("delete_schedule:${s.id}", "শিডিউল মোছা (${s.label})")
                    }
                }
            })
            card.addView(row)
        }
    }

    private fun addScheduleFlow() {
        val input = EditText(this).apply { hint = "নাম, যেমন: রাতের ঘুম / পড়ার সময়" }
        AlertDialog.Builder(this).setTitle("শিডিউলের নাম").setView(input)
            .setPositiveButton("পরের ধাপ") { _, _ ->
                val label = input.text.toString().ifBlank { "লক টাইম" }
                pickTime("শুরু কখন?") { start ->
                    pickTime("শেষ কখন?") { end ->
                        pickDays { mask ->
                            val s = Schedule(System.currentTimeMillis(), label, start, end, mask)
                            Store.saveSchedules(this, Store.schedules(this) + s)
                            Store.logTamper(this, "Schedule added: $label $start->$end mask=$mask")
                            toast("শিডিউল যোগ হয়েছে — এখন থেকেই কার্যকর")
                            buildUi()
                        }
                    }
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun pickTime(title: String, cb: (Int) -> Unit) {
        val now = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m -> cb(h * 60 + m) },
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false)
            .apply { setTitle(title); show() }
    }

    private fun pickDays(cb: (Int) -> Unit) {
        val names = arrayOf("সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার", "রবিবার")
        val checked = BooleanArray(7) { true }
        AlertDialog.Builder(this).setTitle("কোন কোন দিন?")
            .setMultiChoiceItems(names, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("ঠিক আছে") { _, _ ->
                var mask = 0
                for (i in 0..6) if (checked[i]) mask = mask or (1 shl i)
                cb(if (mask == 0) 127 else mask)
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---------------- focus ----------------

    private fun startFocus(minutes: Int) {
        AlertDialog.Builder(this)
            .setTitle("$minutes মিনিট ফোকাস?")
            .setMessage("শুরু করলে সময় শেষ হওয়ার আগে কোনোভাবেই বাতিল করা যাবে না। পরের ধাপে বাছাই করো এই সেশনে কোন অ্যাপগুলো চলবে — বাকি সব সঙ্গে সঙ্গে ব্লক হয়ে যাবে। নোটিফিকেশন প্যানেলও খুলবে না।")
            .setPositiveButton("পরের ধাপ") { _, _ ->
                pickAppsMulti(
                    "এই সেশনে কোন অ্যাপ চলবে?",
                    Store.focusDefaultAllowed(this)
                ) { selected ->
                    verifyPin {
                        val nowMs = System.currentTimeMillis()
                        val end = nowMs + minutes * 60_000L
                        Store.setFocusStartMs(this, nowMs)
                        Store.setFocusEndMs(this, end)
                        Store.setFocusSessionAllowed(this, selected)
                        AdminHelper.applyLockTaskPackages(this, selected)
                        Store.logTamper(this, "Focus started: $minutes min, allowed=${selected.size} apps")
                        startActivity(Intent(this, LockActivity::class.java))
                    }
                }
            }
            .setNegativeButton("না", null).show()
    }

    private fun customFocusFlow() {
        val input = EditText(this).apply {
            hint = "কত মিনিট? যেমন: 45, 90, 180"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this).setTitle("কাস্টম ফোকাস").setView(input)
            .setPositiveButton("পরের ধাপ") { _, _ ->
                val mins = input.text.toString().toIntOrNull()
                if (mins == null || mins < 1 || mins > 24 * 60) {
                    toast("1 থেকে 1440 মিনিটের মধ্যে দাও")
                } else {
                    startFocus(mins)
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---- focus whitelist picking ----

    private fun launcherApps(): List<String> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName }
        // v1.8.0 audit: labels resolved once per package — sortedBy used to
        // re-call appLabel (a PackageManager lookup) on every comparison.
        return pkgs.map { it to appLabel(it) }
            .sortedBy { it.second.lowercase() }.map { it.first }
    }

    /**
     * v1.7.0 (Tamim's report: "অ্যাপ এর তালিকায় সিস্টেম অ্যাপ দেখায় না"):
     * the old list came only from LAUNCHER entries, so system apps without
     * a launcher entry never appeared. The picker now also includes system
     * apps (label "(সিস্টেম)"), skipping internal plumbing packages that
     * nobody would ever pick (providers, overlays, the "android" package).
     */
    private fun pickerApps(): List<Pair<String, String>> {
        val pm = packageManager
        // v1.8.0 audit: every label is now resolved exactly once (the old
        // code called appLabel inside filters AND sorts AND again when
        // building the result — several PackageManager lookups per app,
        // a visible stall when the picker opens on a full phone). The
        // `seen` set also dedupes within the installed-apps list itself,
        // so a package can never appear twice in the picker.
        val launcherPairs = launcherApps().map { it to appLabel(it) }
        val seen = launcherPairs.map { it.first }.toMutableSet()
        val extraPairs = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter {
                    (it.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                }
                .filter { it.enabled }
                .filter {
                    it.packageName != packageName &&
                        !it.packageName.startsWith("com.android.providers.") &&
                        !it.packageName.contains("overlay") &&
                        it.packageName != "android"
                }
                .map { it.packageName }
                .filter { seen.add(it) }
                .map { it to appLabel(it) }
                .filter { it.second.isNotBlank() }
                .sortedBy { it.second.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
        return launcherPairs + extraPairs.map { it.first to (it.second + " (সিস্টেম)") }
    }

    private fun pickAppsMulti(title: String, preselected: Set<String>, cb: (Set<String>) -> Unit) {
        val apps = pickerApps()
        // v1.8.0 polish: a clear empty state instead of a blank dialog.
        if (apps.isEmpty()) {
            toast("কোনো অ্যাপ পাওয়া যায়নি")
            return
        }
        val pkgs = apps.map { it.first }
        val checked = BooleanArray(apps.size) { i -> pkgs[i] in preselected }
        AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, isChecked ->
                checked[i] = isChecked
            }
            .setPositiveButton("ঠিক আছে") { _, _ ->
                cb(pkgs.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun pickDefaultAllowedFlow() {
        pickAppsMulti("ফোকাসে কোন অ্যাপগুলো চলবে?", Store.focusDefaultAllowed(this)) { selected ->
            Store.saveFocusDefaultAllowed(this, selected)
            Store.logTamper(this, "Focus study preset set: ${selected.size} apps")
            toast(if (selected.isEmpty()) "প্রিসেট খালি — ফোকাসে পুরো ফোন লক থাকবে" else "${selected.size}টি অ্যাপ বাছাই হয়েছে")
            buildUi()
        }
    }

    // ---------------- app limits ----------------

    private fun appLimitList(card: LinearLayout) {
        // Built-in limits (e.g. Termux) are hidden here by design (v1.5.3):
        // compiled in, not listed, not editable.
        val list = Store.appLimits(this).filter { it.pkg !in Blocker.BUILTIN_LIMIT_MINUTES }
        if (list.isEmpty()) {
            card.addView(Ui.secondary(this, "কোনো লিমিট নেই। যেমন: YouTube — দিনে 30 মিনিট।"))
            return
        }
        for (limit in list) {
            val used = Blocker.usageTodayMs(this, limit.pkg) / 60000
            val label = appLabel(limit.pkg)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
            }
            row.addView(TextView(this).apply {
                text = "$label — আজ $used / ${limit.limitMinutes} মিনিট"
                textSize = 14f; setTextColor(Ui.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "তুলে নিন"; isAllCaps = false; textSize = 12f
                setTextColor(Ui.MUTED)
                background = RowButtonStyle.raised(this@MainActivity)
                setOnClickListener {
                    verifyPin { requestWeakening("remove_limit:${limit.pkg}", "$label-এর লিমিট তোলা") }
                }
            })
            card.addView(row)
            // ---- v1.12.0 Stage 4: permission locks for this app ----
            val locks = Store.lockedPermissions(this)[limit.pkg] ?: emptySet()
            if (locks.isNotEmpty()) {
                card.addView(Ui.secondary(this,
                    "তালা দেওয়া পারমিশন: " + locks.sorted().joinToString(", ") { permissionShortName(it) }))
            }
            card.addView(Ui.secondaryButton(this, "পারমিশন তালা: $label") {
                permissionLockFlow(limit.pkg, label)
            })
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun addLimitFlow() {
        // v1.5.1: protected packages (Settings, launcher, dialer, system)
        // are not offered — suspending one could strand the phone.
        // v1.7.0: pickerApps() also lists system apps (marked সিস্টেম).
        val apps = pickerApps().filter { !AdminHelper.isProtectedPackage(this, it.first) }
        // v1.8.0 polish: a clear empty state instead of a blank dialog.
        if (apps.isEmpty()) {
            toast("লিমিট বসানোর মতো কোনো অ্যাপ পাওয়া যায়নি")
            return
        }
        AlertDialog.Builder(this).setTitle("কোন অ্যাপে লিমিট?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                val pkg = apps[which].first
                val input = EditText(this).apply { hint = "দিনে কত মিনিট? যেমন: 30"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                AlertDialog.Builder(this).setTitle("${apps[which].second} — লিমিট").setView(input)
                    .setPositiveButton("বসাও") { _, _ ->
                        val mins = input.text.toString().toIntOrNull()
                        // v1.8.0 audit: cap at a full day — a daily limit
                        // above 1440 minutes can never trigger and only
                        // stored nonsense (and overflowed the same range
                        // AppLimit.parse now enforces on load).
                        if (mins == null || mins !in 1..1440) {
                            toast("1 থেকে 1440 মিনিটের মধ্যে দাও")
                        } else {
                            val existing = Store.appLimits(this).firstOrNull { it.pkg == pkg }
                            if (existing != null && mins > existing.limitMinutes) {
                                // Raising a limit is weakening -> PIN + 24h delay.
                                verifyPin { requestWeakening("raise_limit:$pkg:$mins", "লিমিট বাড়ানো") }
                            } else {
                                val list = Store.appLimits(this).filter { it.pkg != pkg } + AppLimit(pkg, mins)
                                Store.saveAppLimits(this, list)
                                Store.logTamper(this, "Limit set: $pkg = $mins min")
                                toast("লিমিট বসানো হয়েছে")
                                buildUi()
                            }
                        }
                    }
                    .setNegativeButton("বাতিল", null).show()
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---------------- PIN + weakening (24h delay) ----------------

    private fun setPinFlow() {
        val input = EditText(this).apply {
            hint = "4-8 সংখ্যার PIN"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("PIN সেট করো").setView(input)
            .setMessage("এই PIN ছাড়া কোনো সুরক্ষা দুর্বল করা যাবে না। PIN ভুলে গেলে একমাত্র উপায় ফ্যাক্টরি রিসেট।")
            .setPositiveButton("সেট") { _, _ ->
                val pin = input.text.toString()
                // v1.8.0 audit: the dialog itself promises "4-8 সংখ্যার
                // PIN" but only the minimum was enforced — a 20-digit PIN
                // was accepted, contradicting the on-screen rule.
                if (pin.length !in 4..8) {
                    toast("4 থেকে 8 সংখ্যার PIN দাও")
                } else {
                    Store.setPin(this, pin)
                    Store.logTamper(this, "PIN set/changed")
                    toast("PIN সেট হয়েছে")
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    private fun setAutomationTokenFlow() {
        val input = EditText(this).apply {
            hint = "কমপক্ষে ১২ অক্ষরের এলোমেলো কোড"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Automation Token").setView(input)
            .setMessage("এই কোডটাই PhoneLink-এর রুলে বসাবে। কোডটা লম্বা ও এলোমেলো রাখো — ছোট কোড হলে অন্য অ্যাপ অনুমান করে কমান্ড পাঠাতে পারবে। কোড শুধু hash আকারে ফোনে থাকে।")
            .setPositiveButton("সেট") { _, _ ->
                val token = input.text.toString().trim()
                if (token.length < 12) {
                    toast("কমপক্ষে ১২ অক্ষর লাগবে")
                } else {
                    Store.setAutomationTokenHash(this, AutomationReceiver.sha256(token))
                    Store.logTamper(this, "Automation token set/changed")
                    toast("Token সেট হয়েছে — API চালু")
                    buildUi()
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---- v1.12.0 Stage 4: permission locks ----

    /** The dangerous permissions this feature will lock — the ones that
     *  matter for privacy and strictness. The picker intersects this set
     *  with the permissions the target app itself actually requested. */
    private val dangerousPermissions = setOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION"
    )

    private fun permissionShortName(perm: String): String =
        perm.removePrefix("android.permission.")

    private fun dangerousRequestedPermissions(pkg: String): List<String> = try {
        val info = packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        (info.requestedPermissions?.toList() ?: emptyList()).filter { it in dangerousPermissions }
    } catch (e: Exception) { emptyList() }

    private fun permissionLockFlow(pkg: String, label: String) {
        val offered = dangerousRequestedPermissions(pkg)
        if (offered.isEmpty()) {
            toast("এই অ্যাপটা তালা দেওয়ার মতো কোনো বিপজ্জনক পারমিশন চায়নি")
            return
        }
        if (!AdminHelper.isDeviceOwner(this)) {
            toast("পারমিশন তালা Device Owner চালু হলেই কার্যকর হবে — পছন্দটা সেভ হয়ে থাকবে")
        }
        val lockedNow = Store.lockedPermissions(this)[pkg] ?: emptySet()
        val checked = BooleanArray(offered.size) { i -> offered[i] in lockedNow }
        AlertDialog.Builder(this).setTitle("$label — পারমিশন তালা")
            .setMessage("টিক দেওয়া পারমিশন অ্যাপটা পাবে না, আর Device Owner থাকলে Settings থেকেও চালু করা যাবে না। তালা তোলা দুর্বলীকরণ — PIN + 24 ঘণ্টা লাগে, একবারে একটা করে।")
            .setMultiChoiceItems(offered.map { permissionShortName(it) }.toTypedArray(), checked) { _, i, isChecked ->
                checked[i] = isChecked
            }
            .setPositiveButton("ঠিক আছে") { _, _ ->
                val chosen = offered.filterIndexed { i, _ -> checked[i] }.toSet()
                val additions = chosen - lockedNow
                val removals = (lockedNow - chosen).sorted()
                if (additions.isNotEmpty()) {
                    val map = Store.lockedPermissions(this).toMutableMap()
                    map[pkg] = (map[pkg] ?: emptySet()) + additions
                    Store.saveLockedPermissions(this, map)
                    AdminHelper.applyPermissionLocks(this)
                    Store.logTamper(this, "Permission locks added for $pkg: ${additions.joinToString()}")
                    toast("পারমিশন তালা বসানো হয়েছে")
                    buildUi()
                }
                if (removals.isNotEmpty()) {
                    // One weakening at a time: the pending slot is global
                    // (the rule for every weakening in this app). The
                    // others stay locked and can be requested afterwards.
                    val first = removals.first()
                    if (removals.size > 1) {
                        toast("তালা তোলা একবারে একটা করে হয় — বাকি ${removals.size - 1}টা পরে আবার চেষ্টা করো")
                    }
                    verifyPin {
                        requestWeakening(
                            "permission_unlock:$pkg:$first",
                            "$label-এর পারমিশন তালা তোলা: ${permissionShortName(first)}"
                        )
                    }
                }
                if (additions.isEmpty() && removals.isEmpty()) {
                    toast("কোনো বদল হয়নি")
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---- v1.12.0 Stage 7: FRP e-mail flow ----

    /** Setting the FRP account the first time is strengthening
     *  (immediate). Changing it goes through the PIN + 24h pending the
     *  caller already queued verifyPin for — this flow only collects the
     *  new value and files the "frp_change:<email>" request. */
    private fun frpEmailFlow(frpChange: Boolean) {
        val input = EditText(this).apply {
            hint = "তোমার নিজের Google অ্যাকাউন্টের ইমেইল"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(Store.frpEmail(this@MainActivity))
        }
        AlertDialog.Builder(this).setTitle("Factory Reset Protection").setView(input)
            .setMessage("সাবধান: ভুল বা অন্য কারো ইমেইল দিলে ফ্যাক্টরি রিসেটের পর ফোন ওই অ্যাকাউন্ট ছাড়া সেটআপ হবে না — নিজের ফোনেই আটকে যাবে। শুধু নিজের Google অ্যাকাউন্টের ইমেইল দাও।")
            .setPositiveButton("সেভ") { _, _ ->
                val email = input.text.toString().trim().lowercase()
                if (email.isBlank() || !email.contains("@") || !email.contains(".")) {
                    toast("ঠিকমতো ইমেইল দাও")
                } else if (frpChange) {
                    requestWeakening("frp_change:$email", "FRP ইমেইল বদলানো")
                } else {
                    Store.setFrpEmail(this, email)
                    AdminHelper.applyFrpPolicy(this)
                    Store.logTamper(this, "FRP account set")
                    toast("FRP ইমেইল সেভ হয়েছে")
                    buildUi()
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---- v1.12.0 Stage 6: log viewers ----

    private fun showSecurityLogs() {
        if (!AdminHelper.isDeviceOwner(this)) {
            toast("Security log দেখতে Device Owner লাগবে")
            return
        }
        val result = AdminHelper.securityLogLines(this)
        if (result == null) {
            toast("লগ পড়া যায়নি — আবার চেষ্টা করো")
            return
        }
        val (total, lines) = result
        Store.logTamper(this, "Security log viewed: $total events buffered")
        val text = if (lines.isEmpty()) {
            "এখনো কোনো লগ জমা হয়নি — লগ batch আকারে আসে, কিছু সময় পর আবার দেখো।"
        } else {
            "মোট $total টি ইভেন্ট আছে। শেষ ${lines.size}টি:\n\n" + lines.joinToString("\n")
        }
        showScrollableDialog("Security log", text)
    }

    private fun showNetworkLogSummary() {
        if (!AdminHelper.isDeviceOwner(this)) {
            toast("Network log দেখতে Device Owner লাগবে")
            return
        }
        if (Store.networkLogBatchToken(this) < 0) {
            showScrollableDialog(
                "Network log সারাংশ",
                "এখনো কোনো network log batch আসেনি। লগ batch আকারে আসে — কিছুক্ষণ ইন্টারনেট ব্যবহারের পর আবার দেখো।"
            )
            return
        }
        val total = AdminHelper.networkLogCount(this)
        if (total == null) {
            toast("লগ পড়া যায়নি — আবার চেষ্টা করো")
            return
        }
        Store.logTamper(this, "Network log viewed: $total events")
        val text = if (total == 0) {
            "এই batch-এ কোনো ইভেন্ট ছিল না।"
        } else {
            "এই batch-এ মোট $total টি নেটওয়ার্ক ইভেন্ট ছিল। " +
                "(একটা batch একবারই পড়া যায় — এই batch-টা এখন খরচ হয়ে গেছে।)"
        }
        showScrollableDialog("Network log সারাংশ", text)
    }

    /** Long text in a scrollable dialog, in the design-system styling. */
    private fun showScrollableDialog(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Ui.TEXT)
            setPadding(
                Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 12),
                Ui.dp(this@MainActivity, 20), Ui.dp(this@MainActivity, 4)
            )
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.55).toInt()
            )
        }
        AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton("বন্ধ করো", null).show()
    }

    /** v1.15.0: DNS provider switching rules.
     *  - Filter OFF: any provider is just a saved preference.
     *  - Filter ON + family-grade provider: equivalent strength, applies
     *    immediately (re-pointed + read-back verified in applyDnsFilter).
     *  - Filter ON + weaker/unknown filtering (AdGuard Default, Custom):
     *    weakening — PIN + 24h via the dns_provider pending action. */
    private fun chooseDnsProvider(p: AdminHelper.DnsProvider) {
        val dnsOn = Store.dnsFilterOn(this)
        if (p.id == "custom") {
            val input = android.widget.EditText(this).apply {
                hint = "dns.example.com"
                setText(Store.dnsCustomHost(this@MainActivity))
                setTextColor(Ui.TEXT)
                setHintTextColor(Ui.MUTED)
            }
            AlertDialog.Builder(this)
                .setTitle("নিজের DNS হোস্ট")
                .setMessage("Private DNS হোস্টনেমটা লেখো। ফিল্টার চালু থাকা অবস্থায় Custom-এ বদলানো দুর্বলীকরণ — PIN + 24 ঘণ্টা লাগবে।")
                .setView(input)
                .setPositiveButton("সেভ করো") { _, _ ->
                    val host = input.text.toString().trim()
                    if (host.isBlank() || host.contains(" ") || !host.contains(".")) {
                        toast("সঠিক হোস্টনেম লেখো")
                        return@setPositiveButton
                    }
                    if (!dnsOn) {
                        Store.setDnsCustomHost(this, host)
                        Store.setDnsProvider(this, "custom")
                        toast("Custom DNS সেভ হয়েছে — ফিল্টার চালু হলেই কার্যকর")
                        buildUi()
                    } else if (Store.dnsProvider(this) == "custom" && Store.dnsCustomHost(this) == host) {
                        toast("এটাই এখনকার Custom হোস্ট")
                    } else {
                        verifyPin {
                            Store.setPendingDnsCustomHost(this, host)
                            requestWeakening("dns_provider:custom", "DNS সার্ভিস বদলানো (Custom: $host)")
                        }
                    }
                }
                .setNegativeButton("বাতিল", null).show()
            return
        }
        if (Store.dnsProvider(this) == p.id) {
            toast("এই সার্ভিসটাই এখন বেছে নেওয়া আছে")
            return
        }
        when {
            !dnsOn -> {
                Store.setDnsProvider(this, p.id)
                toast("সেভ হয়েছে — ফিল্টার চালু হলেই ${p.label} কার্যকর হবে")
                buildUi()
            }
            p.family -> {
                Store.setDnsProvider(this, p.id)
                AdminHelper.applyDnsFilter(this, force = true)
                Store.logTamper(this, "DNS provider changed to ${p.label} (${p.host})")
                toast("DNS সার্ভিস এখন ${p.label}")
                buildUi()
            }
            else -> verifyPin {
                requestWeakening("dns_provider:${p.id}", "DNS সার্ভিস বদলানো (${p.label})")
            }
        }
    }

    private fun verifyPin(then: () -> Unit) {
        if (!Store.hasPin(this)) {
            toast("আগে PIN সেট করতে হবে")
            setPinFlow()
            return
        }
        val input = EditText(this).apply {
            hint = "PIN দাও"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("PIN দরকার").setView(input)
            .setPositiveButton("যাচাই") { _, _ ->
                if (Store.checkPin(this, input.text.toString())) then()
                else {
                    Store.logTamper(this, "Wrong PIN entered")
                    toast("ভুল PIN")
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    /**
     * The heart of "strict": a weakening change never happens now.
     * It is queued, shown on screen, and only applies 24 hours later.
     * Cancelling a pending weakening is also behind the PIN, and the
     * pending state is always visible in the Security card.
     */
    private fun requestWeakening(action: String, description: String) {
        val executeAt = System.currentTimeMillis() + Store.WEAKEN_DELAY_MS
        Store.setPendingAction(this, action, executeAt)
        Store.logTamper(this, "Weakening requested: $description -> applies at $executeAt")
        AlertDialog.Builder(this)
            .setTitle("আবেদন জমা হয়েছে")
            .setMessage("$description এখনই হবে না। 24 ঘণ্টা পর, ${timeText(executeAt)}-এ কার্যকর হবে। তার আগে মন বদলালে 'Pending বাতিল' দিয়ে থামানো যাবে (PIN লাগবে)।")
            .setPositiveButton("বুঝেছি", null).show()
        buildUi()
    }

    /** v1.8.0 polish: the Pending row used to print the raw internal action
     *  string ("raise_limit:com.example:60") at the user. Same information,
     *  in plain Bangla. */
    private fun pendingDescription(action: String): String = when {
        action == "protection_off" -> "Protection Off করা"
        action == "remove_device_owner" -> "Device Owner সরানো"
        action == "allow_only_off" -> "শুধু-Allow মোড বন্ধ করা"
        action == "install_block_off" -> "ইনস্টল ব্লক বন্ধ করা"
        action == "dns_filter_off" -> "DNS ফিল্টার বন্ধ করা"
        action.startsWith("dns_provider:") -> {
            val id = action.removePrefix("dns_provider:")
            val label = AdminHelper.DNS_PROVIDERS.firstOrNull { it.id == id }?.label ?: id
            "DNS সার্ভিস বদলানো: $label"
        }
        action == "network_lock_off" -> "নেটওয়ার্ক লক বন্ধ করা"
        action == "keyguard_hardening_off" -> "লক স্ক্রিন হার্ডেনিং বন্ধ করা"
        action == "account_block_off" -> "অ্যাকাউন্ট ব্লক বন্ধ করা"
        action.startsWith("unhide_app:") ->
            "অ্যাপ ফিরিয়ে আনা: ${appLabel(action.removePrefix("unhide_app:"))}"
        action.startsWith("permission_unlock:") -> {
            val parts = action.removePrefix("permission_unlock:").split(":")
            if (parts.size == 2) "পারমিশন তালা তোলা: ${appLabel(parts[0])} — ${permissionShortName(parts[1])}"
            else "পারমিশন তালা তোলা"
        }
        action.startsWith("frp_change:") -> {
            val value = action.removePrefix("frp_change:")
            if (value == "EMPTY") "Factory Reset Protection মোছা"
            else "FRP ইমেইল বদলানো: $value"
        }
        action.startsWith("uninstall_unprotect:") ->
            "আনইনস্টল-সুরক্ষা তোলা: ${appLabel(action.removePrefix("uninstall_unprotect:"))}"
        action.startsWith("delete_schedule:") -> "শিডিউল মোছা"
        action.startsWith("raise_limit:") -> {
            val parts = action.removePrefix("raise_limit:").split(":")
            if (parts.size == 2) "লিমিট বাড়ানো: ${appLabel(parts[0])} → ${parts[1]} মিনিট"
            else "লিমিট বাড়ানো"
        }
        action.startsWith("remove_limit:") ->
            "লিমিট তোলা: ${appLabel(action.removePrefix("remove_limit:"))}"
        action.startsWith("remove_site:") ->
            "ওয়েবসাইট ব্লক তোলা: ${action.removePrefix("remove_site:")}"
        action.startsWith("remove_category:") -> {
            val id = action.removePrefix("remove_category:")
            "ক্যাটাগরি ফিল্টার তোলা: ${SiteCategories.byId(id)?.labelBn ?: id}"
        }
        else -> "সুরক্ষা পরিবর্তন"
    }

    private fun pendingInfo(card: LinearLayout) {
        val (action, at) = Store.pendingAction(this) ?: return
        card.addView(Ui.secondary(this, "Pending পরিবর্তন: ${pendingDescription(action)}\nকার্যকর হবে: ${timeText(at)} (${Blocker.formatRemaining(at)} বাকি)"))
        card.addView(Ui.secondaryButton(this, "Pending বাতিল করো") {
            verifyPin {
                Store.clearPendingAction(this)
                Store.logTamper(this, "Pending weakening cancelled")
                toast("Pending বাতিল হয়েছে — সুরক্ষা আগের মতোই আছে")
                buildUi()
            }
        })
    }
}

/** Small raised-pill background for inline row buttons (design system).
 *  v1.8.0 audit: renamed from GradientDrawableHelper — the drawable is a
 *  solid fill with a hairline stroke (the design system bans gradients);
 *  the old name described the opposite of what it draws. */
object RowButtonStyle {
    fun raised(ctx: Context): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Ui.RAISED)
            cornerRadius = Ui.dp(ctx, 999).toFloat()
            setStroke(Ui.dp(ctx, 1), Ui.STROKE)
        }
}
