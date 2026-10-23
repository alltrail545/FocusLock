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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdminHelper.applyStrictPolicies(this)
        BlockingService.start(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        buildUi() // refresh status + lists
    }

    // ---------------- UI construction ----------------

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(title("🔒 FocusLock"))
        root.addView(sub("একদম কড়া স্ক্রিন-টাইম লক — নির্ধারিত সময়ে চাইলেও ফোন খোলা যাবে না।"))
        root.addView(section("সেটআপ অবস্থা"))
        root.addView(statusLines())
        setupButtons()

        // Tamim's main use (2026-10-23): no fixed daily schedule — he locks the
        // phone ON DEMAND, any time of day or night, whenever he needs to.
        // So Focus is the hero section, schedules are secondary.
        root.addView(section("⏱ ফোকাস সেশন (শুরু করলে বাতিল হবে না)"))
        root.addView(button("25 মিনিট ফোকাস") { startFocus(25) })
        root.addView(button("1 ঘণ্টা ফোকাস") { startFocus(60) })
        root.addView(button("2 ঘণ্টা ফোকাস") { startFocus(120) })
        root.addView(button("🕐 নিজের মতো সময় ফোকাস") { customFocusFlow() })
        root.addView(button("📚 স্টাডি অ্যাপ বাছাই (ফোকাসে শুধু এগুলোই চলবে)") { pickDefaultAllowedFlow() })
        val preset = Store.focusDefaultAllowed(this)
        root.addView(sub(
            if (preset.isEmpty()) "স্টাডি অ্যাপ বাছাই করা নেই — ফোকাসে পুরো ফোন লক থাকবে।"
            else "বাছাই করা: " + preset.sorted().joinToString(", ") { appLabel(it) }
        ))
        Store.focusEndMs(this).takeIf { it > System.currentTimeMillis() }?.let {
            root.addView(sub("ফোকাস চলছে, শেষ হবে: ${timeText(it)}"))
        }

        root.addView(section("📅 লক শিডিউল (ঐচ্ছিক — প্রতিদিন একই সময়ে)"))
        scheduleList()
        root.addView(button("＋ নতুন শিডিউল যোগ করো") { addScheduleFlow() })

        root.addView(section("📱 অ্যাপ লিমিট (প্রতিদিন)"))
        appLimitList()
        root.addView(button("＋ অ্যাপে লিমিট বসাও") { addLimitFlow() })

        root.addView(section("🛡 সুরক্ষা"))
        pendingInfo()
        root.addView(button("PIN সেট / বদলাও") { setPinFlow() })
        root.addView(button("Protection Off করার আবেদন (24 ঘণ্টা লাগবে)") {
            verifyPin {
                requestWeakening("protection_off", "Protection Off")
            }
        })
        if (!Store.isProtectionOn(this)) {
            root.addView(button("Protection আবার On করো") {
                Store.setProtectionOn(this, true)
                AdminHelper.applyStrictPolicies(this)
                toast("Protection On হয়েছে")
                buildUi()
            })
        }

        root.addView(section("📜 ট্যাম্পার লগ"))
        val log = Store.tamperLog(this)
        root.addView(sub(if (log.isBlank()) "এখনো কোনো চেষ্টা হয়নি।" else log.lines().takeLast(20).joinToString("\n")))
    }

    private fun title(t: String) = TextView(this).apply { text = t; textSize = 28f }
    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setPadding(0, 48, 0, 12)
    }
    private fun sub(t: String) = TextView(this).apply { text = t; textSize = 15f }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun timeText(ms: Long) =
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))
    private fun minText(mins: Int) = "%02d:%02d".format(mins / 60, mins % 60)

    private fun statusLines(): TextView {
        val owner = AdminHelper.isDeviceOwner(this)
        val admin = AdminHelper.isAdminActive(this)
        val acc = AdminHelper.isAccessibilityOn(this)
        val usage = AdminHelper.hasUsageAccess(this)
        val overlay = AdminHelper.hasOverlayPermission(this)
        val prot = Store.isProtectionOn(this)
        fun yn(b: Boolean) = if (b) "✅" else "❌"
        return sub(
            """
            ${yn(owner)} Device Owner (সবচেয়ে কড়া — আনইনস্টল আটকানো)
            ${yn(admin)} Device Admin
            ${yn(acc)} Accessibility (অ্যাপ ব্লকার)
            ${yn(usage)} Usage Access (অ্যাপ লিমিটের হিসাব)
            ${yn(overlay)} Overlay Permission
            ${yn(prot)} Protection ${if (prot) "On" else "Off"}
            """.trimIndent()
        )
    }

    private fun setupButtons() {
        root.addView(button("Device Admin চালু করো") {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminHelper.adminComponent(this@MainActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "FocusLock-এর কড়া সুরক্ষার জন্য দরকার।")
            }
            startActivity(intent)
        })
        root.addView(button("Accessibility চালু করো") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("Usage Access দাও") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(button("Overlay Permission দাও") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(button("📋 ADB কমান্ড কপি করো (Device Owner-এর জন্য)") {
            val cmd = "adb shell dpm set-device-owner com.focuslock.app/.AdminReceiver"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FocusLock ADB", cmd))
            toast("কপি হয়েছে। PC-তে ADB দিয়ে একবার চালাতে হবে — গাইড README-তে আছে।")
        })
    }

    // ---------------- schedules ----------------

    private fun scheduleList() {
        val list = Store.schedules(this)
        if (list.isEmpty()) {
            root.addView(sub("কোনো শিডিউল নেই। যেমন: রাত 11:00 → ভোর 5:00।"))
            return
        }
        val dayNames = listOf("সোম", "মঙ্গল", "বুধ", "বৃহস্পতি", "শুক্র", "শনি", "রবি")
        for (s in list) {
            val days = if (s.daysMask == 127) "প্রতিদিন"
            else (0..6).filter { s.daysMask and (1 shl it) != 0 }.joinToString(", ") { dayNames[it] }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "${s.label} — ${minText(s.startMin)} → ${minText(s.endMin)} ($days)"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "মুছুন"
                setOnClickListener {
                    verifyPin {
                        requestWeakening("delete_schedule:${s.id}", "শিডিউল মোছা (${s.label})")
                    }
                }
            })
            root.addView(row)
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
            .setMessage("শুরু করলে সময় শেষ হওয়ার আগে কোনোভাবেই বাতিল করা যাবে না। পরের ধাপে বাছাই করো এই সেশনে কোন অ্যাপগুলো চলবে — বাকি সব সঙ্গে সঙ্গে ব্লক হয়ে যাবে।")
            .setPositiveButton("পরের ধাপ") { _, _ ->
                pickAppsMulti(
                    "এই সেশনে কোন অ্যাপ চলবে?",
                    Store.focusDefaultAllowed(this)
                ) { selected ->
                    verifyPin {
                        val end = System.currentTimeMillis() + minutes * 60_000L
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

    // ---- focus whitelist picking ----

    private fun launcherApps(): List<String> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.distinct()
            .filter { it != packageName }
            .sortedBy { appLabel(it) }
    }

    private fun pickAppsMulti(title: String, preselected: Set<String>, cb: (Set<String>) -> Unit) {
        val apps = launcherApps()
        val checked = BooleanArray(apps.size) { i -> apps[i] in preselected }
        AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(apps.map { appLabel(it) }.toTypedArray(), checked) { _, i, isChecked ->
                checked[i] = isChecked
            }
            .setPositiveButton("ঠিক আছে") { _, _ ->
                cb(apps.filterIndexed { i, _ -> checked[i] }.toSet())
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

    // ---------------- app limits ----------------

    private fun appLimitList() {
        val list = Store.appLimits(this)
        if (list.isEmpty()) {
            root.addView(sub("কোনো লিমিট নেই। যেমন: YouTube — দিনে 30 মিনিট।"))
            return
        }
        for (limit in list) {
            val used = Blocker.usageTodayMs(this, limit.pkg) / 60000
            val label = appLabel(limit.pkg)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = "$label — আজ $used / ${limit.limitMinutes} মিনিট"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "তুলে নিন"
                setOnClickListener {
                    verifyPin { requestWeakening("remove_limit:${limit.pkg}", "$label-এর লিমিট তোলা") }
                }
            })
            root.addView(row)
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun addLimitFlow() {
        val apps = launcherApps()
        AlertDialog.Builder(this).setTitle("কোন অ্যাপে লিমিট?")
            .setItems(apps.map { appLabel(it) }.toTypedArray()) { _, which ->
                val pkg = apps[which]
                val input = EditText(this).apply { hint = "দিনে কত মিনিট? যেমন: 30"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                AlertDialog.Builder(this).setTitle("${appLabel(pkg)} — লিমিট").setView(input)
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
     * It is queued, shown on screen, and only applies 24 hours later —
     * and only if the user does not cancel it... no, cancelling a pending
     * weakening is also behind the PIN, and the pending state is always visible.
     */
    private fun requestWeakening(action: String, description: String) {
        val executeAt = System.currentTimeMillis() + Store.WEAKEN_DELAY_MS
        Store.setPendingAction(this, action, executeAt)
        Store.logTamper(this, "Weakening requested: $description -> applies at $executeAt")
        AlertDialog.Builder(this)
            .setTitle("আবেদন জমা হয়েছে")
            .setMessage("$description এখনই হবে না। 24 ঘণ্টা পর, ${timeText(executeAt)}-এ কার্যকর হবে। তার আগে মন বদলালে নিচের 'Pending বাতিল' দিয়ে থামানো যাবে (PIN লাগবে)।")
            .setPositiveButton("বুঝেছি", null).show()
        buildUi()
    }

    private fun pendingInfo() {
        val (action, at) = Store.pendingAction(this) ?: return
        root.addView(sub("⏳ Pending পরিবর্তন: $action\nকার্যকর হবে: ${timeText(at)} (${Blocker.formatRemaining(at)} বাকি)"))
        root.addView(button("Pending বাতিল করো") {
            verifyPin {
                Store.clearPendingAction(this)
                Store.logTamper(this, "Pending weakening cancelled")
                toast("Pending বাতিল হয়েছে — সুরক্ষা আগের মতোই আছে")
                buildUi()
            }
        })
    }
}
