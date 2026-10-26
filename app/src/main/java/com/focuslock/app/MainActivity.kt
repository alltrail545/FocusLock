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
 * FocusLock home, on Tamim's design system (2026-10-23):
 * near-black green-tinted background, flat bento cards, emerald accent,
 * no gradients, no emoji in any control or label.
 * v1.2: BOTTOM NAVIGATION BAR with five tabs (Tamim's request):
 * Home / Focus / Schedule / Limits / Settings.
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
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // Header (fixed above the scrolling content)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 14),
                Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 10))
        }
        header.addView(Ui.appTitle(this, "FocusLock"))
        header.addView(Ui.label(this, "একদম কড়া স্ক্রিন-টাইম লক").apply {
            setPadding(0, Ui.dp(this@MainActivity, 2), 0, 0)
        })
        shell.addView(header)

        // Scrolling tab content
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 4),
                Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
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

        // Hairline + bottom navigation bar
        shell.addView(android.view.View(this).apply {
            setBackgroundColor(Ui.STROKE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 1))
        })
        shell.addView(buildNavBar())
        setContentView(shell)
    }

    private fun buildNavBar(): LinearLayout {
        val labels = listOf("হোম", "ফোকাস", "শিডিউল", "লিমিট", "সেটিংস")
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.CARD)
            setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 10),
                Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12))
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
                setPadding(Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8),
                    Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 8))
                if (active) {
                    setTextColor(Ui.BRIGHT)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(Ui.RAISED)
                        cornerRadius = Ui.dp(this@MainActivity, 999).toFloat()
                        setStroke(Ui.dp(this@MainActivity, 1), Ui.ACCENT)
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
        return bar
    }

    // ---- Tab 0: Home — minimal permissions, live state, statistics ----
    private fun tabHome(content: LinearLayout) {
        // Minimized (Tamim's request): only the 3 permissions the app truly
        // needs day-to-day. Device Owner belongs to Stage B and lives in
        // Settings; optional features ask for their permission only when used.
        val permCard = Ui.card(this)
        permCard.addView(Ui.cardTitle(this, "পারমিশন"))
        permCard.addView(Ui.label(this, "মাত্র ৩টা জরুরি পারমিশন"))
        permCard.addView(Ui.statusRow(this, "Accessibility (অ্যাপ ব্লকার)", AdminHelper.isAccessibilityOn(this)))
        permCard.addView(Ui.statusRow(this, "Usage Access (লিমিট ও হিসাব)", AdminHelper.hasUsageAccess(this)))
        permCard.addView(Ui.statusRow(this, "Overlay (শেড গার্ড)", AdminHelper.hasOverlayPermission(this)))
        permCard.addView(Ui.secondary(this, "সব পারমিশন সেটিংস একসাথে — সেটিংস ট্যাবে আলাদা কার্ডে।"))
        content.addView(permCard)

        val nowCard = Ui.card(this)
        nowCard.addView(Ui.cardTitle(this, "এখনকার অবস্থা"))
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
                nowCard.addView(Ui.secondary(this, "এখন কোনো লক চলছে না। ফোকাস ট্যাব থেকে যেকোনো সময় লক শুরু করা যাবে।"))
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
        statsCard.addView(Ui.cardTitle(this, "আজকের হিসাব"))
        if (AdminHelper.hasUsageAccess(this)) {
            val (totalMs, top) = usageSummary()
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
        focusCard.addView(Ui.cardTitle(this, "ফোকাস সেশন"))
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
        Store.focusEndMs(this).takeIf { it > System.currentTimeMillis() }?.let {
            focusCard.addView(Ui.body(this, "ফোকাস চলছে, শেষ হবে: ${timeText(it)}").apply {
                setTextColor(Ui.BRIGHT)
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
            })
        }
        content.addView(focusCard)

        // ---- Named focus presets (v1.4): one tap starts the whole setup ----
        val presetCard = Ui.card(this)
        presetCard.addView(Ui.cardTitle(this, "আমার প্রিসেট"))
        val presets = Store.focusPresets(this)
        if (presets.isEmpty()) {
            presetCard.addView(Ui.secondary(this, "কোনো প্রিসেট নেই। নাম দিয়ে প্রিসেট বানালে এক ট্যাপে সেই ফোকাস শুরু হবে — যেমন পড়াশোনা, ২ ঘণ্টা, বইয়ের অ্যাপ।"))
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
        quietCard.addView(Ui.cardTitle(this, "নোটিফিকেশন"))
        quietCard.addView(Ui.statusRow(this, "ফোকাস/লকের সময় নোটিফিকেশন চুপ", Store.silenceDuringLock(this)))
        quietCard.addView(Ui.secondary(this, "চালু থাকলে ফোকাস চলাকালে Do Not Disturb নিজে থেকে অন হয়, শেষ হলে আগের অবস্থায় ফেরে। অ্যালার্ম বাজবে।"))
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
        scheduleCard.addView(Ui.cardTitle(this, "লক শিডিউল"))
        scheduleCard.addView(Ui.label(this, "ঐচ্ছিক — প্রতিদিন একই সময়ে অটো লক"))
        scheduleList(scheduleCard)
        scheduleCard.addView(Ui.secondaryButton(this, "নতুন শিডিউল যোগ করো") { addScheduleFlow() })
        content.addView(scheduleCard)
    }

    // ---- Tab 3: App limits ----
    private fun tabLimits(content: LinearLayout) {
        val limitsCard = Ui.card(this)
        limitsCard.addView(Ui.cardTitle(this, "অ্যাপ লিমিট"))
        limitsCard.addView(Ui.label(this, "প্রতিদিনের সীমা — পার হলেই সেদিনের জন্য বন্ধ"))
        appLimitList(limitsCard)
        limitsCard.addView(Ui.secondaryButton(this, "অ্যাপে লিমিট বসাও") { addLimitFlow() })
        content.addView(limitsCard)

        // ---- Blocked websites (v1.4, Chrome) ----
        val siteCard = Ui.card(this)
        siteCard.addView(Ui.cardTitle(this, "ওয়েবসাইট ব্লক"))
        siteCard.addView(Ui.label(this, "যেকোনো ব্রাউজারে এই সাইটগুলো খুললেই সঙ্গে সঙ্গে ব্লক"))
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
                host = host.substringBefore("/").substringBefore("?")
                if (host.isBlank() || !host.contains(".")) {
                    toast("ঠিকমতো সাইটের নাম দাও, যেমন bbc.com")
                } else {
                    Store.saveSiteAllowList(this, Store.siteAllowList(this) + host)
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
                host = host.substringBefore("/").substringBefore("?")
                if (host.isBlank() || !host.contains(".")) {
                    toast("ঠিকমতো সাইটের নাম দাও, যেমন facebook.com")
                } else {
                    Store.saveBlockedSites(this, Store.blockedSites(this) + host)
                    Store.logTamper(this, "Website blocked: $host")
                    toast("ব্লক হয়েছে: $host")
                    buildUi()
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    // ---- Tab 4: Settings — permissions, security, backup, log ----
    private fun tabSettings(content: LinearLayout) {
        // ALL permission settings in ONE dedicated card (Tamim's request).
        val permCard = Ui.card(this)
        permCard.addView(Ui.cardTitle(this, "পারমিশন সেটিংস"))
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
        content.addView(permCard)

        val securityCard = Ui.card(this)
        securityCard.addView(Ui.cardTitle(this, "সুরক্ষা"))
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
            securityCard.addView(Ui.secondary(this,
                "জরুরি বের-হওয়ার পথ: Owner সরালে আগে সব restriction উঠবে, suspend/hide করা অ্যাপ ফিরবে, তারপর Owner যাবে — কোনো অ্যাপ আটকে থাকবে না।"))
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
        securityCard.addView(Ui.secondaryButton(this, "ADB কমান্ড কপি করো (Stage B-এর জন্য)") {
            val cmd = "adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FocusLock ADB", cmd))
            toast("কপি হয়েছে")
        })
        content.addView(securityCard)

        // ---- Automation API (v1.6.0, PhoneLink) ----
        val autoCard = Ui.card(this)
        autoCard.addView(Ui.cardTitle(this, "অটোমেশন (PhoneLink)"))
        val autoOn = Store.automationTokenHash(this).isNotBlank()
        autoCard.addView(Ui.secondary(this,
            if (autoOn) "API চালু আছে — token মিললেই PhoneLink কমান্ড পাঠাতে পারবে।"
            else "API বন্ধ আছে — token বসালে তবেই চালু হবে।"))
        autoCard.addView(Ui.secondary(this,
            "কমান্ড (explicit broadcast, action: com.focuslock.app.AUTOMATION): " +
                "cmd=block_app (app, mins) · cmd=lock_full (mins) · cmd=focus (preset, mins ঐচ্ছিক) · " +
                "cmd=unblock (app থাকলে শুধু ওই অ্যাপের টাইম-ব্লক; না থাকলে অটোমেশনের সব ব্লক ও অটোমেশন-সেশন)। " +
                "unblock শুধু অটোমেশনের শুরু করা জিনিসই তুলতে পারে — হাতে শুরু করা ফোকাস, শিডিউল আর দৈনিক লিমিট কখনোই না। " +
                "Token শুধু hash আকারে থাকে, ব্যাকআপেও যায় না — আনইনস্টল করলে আবার বসাতে হবে।"))
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
        backupCard.addView(Ui.cardTitle(this, "সেটিংস ব্যাকআপ"))
        backupCard.addView(Ui.secondary(this, "নতুন সংস্করণ বসাতে আনইনস্টল করলে সেটিংস মুছে যায়। ব্যাকআপ ফাইল রাখলে এক ট্যাপে শিডিউল, লিমিট, প্রিসেট, সাইট-ব্লক সব ফিরে আসবে। (PIN ব্যাকআপে থাকে না — আবার সেট করতে হবে।)"))
        backupCard.addView(Ui.secondaryButton(this, "ব্যাকআপ নাও (ফাইলে সেভ করো)") {
            exportLauncher.launch("focuslock-backup.json")
        })
        backupCard.addView(Ui.secondaryButton(this, "ব্যাকআপ থেকে রিস্টোর করো") {
            importLauncher.launch(arrayOf("application/json"))
        })
        content.addView(backupCard)

        val logCard = Ui.card(this)
        logCard.addView(Ui.cardTitle(this, "ট্যাম্পার লগ"))
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
                background = GradientDrawableHelper.raised(this@MainActivity)
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
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName }
            .sortedBy { appLabel(it) }
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
        val launcher = launcherApps()
        val result = launcher.map { it to appLabel(it) }.toMutableList()
        val seen = launcher.toSet()
        val extras = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName !in seen && it.packageName != packageName }
                .filter {
                    (it.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                }
                .filter { it.enabled }
                .filter {
                    !it.packageName.startsWith("com.android.providers.") &&
                        !it.packageName.contains("overlay") &&
                        it.packageName != "android"
                }
                .filter { appLabel(it.packageName).isNotBlank() }
                .map { it.packageName }
                .sortedBy { appLabel(it) }
        } catch (e: Exception) {
            emptyList()
        }
        for (pkg in extras) result.add(pkg to (appLabel(pkg) + " (সিস্টেম)"))
        return result
    }

    private fun pickAppsMulti(title: String, preselected: Set<String>, cb: (Set<String>) -> Unit) {
        val apps = pickerApps()
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
                background = GradientDrawableHelper.raised(this@MainActivity)
                setOnClickListener {
                    verifyPin { requestWeakening("remove_limit:${limit.pkg}", "$label-এর লিমিট তোলা") }
                }
            })
            card.addView(row)
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
        AlertDialog.Builder(this).setTitle("কোন অ্যাপে লিমিট?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                val pkg = apps[which].first
                val input = EditText(this).apply { hint = "দিনে কত মিনিট? যেমন: 30"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                AlertDialog.Builder(this).setTitle("${apps[which].second} — লিমিট").setView(input)
                    .setPositiveButton("বসাও") { _, _ ->
                        val mins = input.text.toString().toIntOrNull()
                        if (mins == null || mins <= 0) {
                            toast("সঠিক মিনিট দাও")
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
                if (pin.length < 4) {
                    toast("কমপক্ষে 4 সংখ্যা লাগবে")
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

    private fun pendingInfo(card: LinearLayout) {
        val (action, at) = Store.pendingAction(this) ?: return
        card.addView(Ui.secondary(this, "Pending পরিবর্তন: $action\nকার্যকর হবে: ${timeText(at)} (${Blocker.formatRemaining(at)} বাকি)"))
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

/** Small raised-pill background for inline row buttons (design system). */
object GradientDrawableHelper {
    fun raised(ctx: Context): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(Ui.RAISED)
            cornerRadius = Ui.dp(ctx, 14).toFloat()
            setStroke(Ui.dp(ctx, 1), Ui.STROKE)
        }
}
