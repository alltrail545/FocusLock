package com.focuslock.app

import android.content.Context

/**
 * v1.15.0: app language for the MAIN screens — the five tabs, the main
 * card titles, status chips and the lock screen (same phase-1 scope as
 * OwnerControl). Deep flows (long setup guides, legal-style warnings,
 * dialogs) stay in Bangla for now; translating safety-critical long texts
 * is a separate careful pass. Bangla remains the default and the
 * fallback for any missing key.
 */
object Lang {
    val LANGS: List<Pair<String, String>> = listOf(
        "bn" to "বাংলা", "en" to "English", "hi" to "हिन्दी",
        "ur" to "اردو", "ar" to "العربية", "es" to "Español",
        "pt" to "Português", "id" to "Bahasa Indonesia"
    )

    fun code(ctx: Context): String = Store.appLang(ctx)

    fun isRtl(ctx: Context): Boolean = code(ctx) in listOf("ar", "ur")

    fun t(ctx: Context, key: String): String {
        val lang = code(ctx)
        return TABLE[key]?.get(lang) ?: TABLE[key]?.get("bn") ?: key
    }

    private val TABLE: Map<String, Map<String, String>> = mapOf(
        "tab_home" to mapOf("bn" to "হোম", "en" to "Home", "hi" to "होम", "ur" to "ہوم", "ar" to "الرئيسية", "es" to "Inicio", "pt" to "Início", "id" to "Beranda"),
        "tab_focus" to mapOf("bn" to "ফোকাস", "en" to "Focus", "hi" to "फ़ोकस", "ur" to "فوکس", "ar" to "التركيز", "es" to "Enfoque", "pt" to "Foco", "id" to "Fokus"),
        "tab_schedule" to mapOf("bn" to "শিডিউল", "en" to "Schedule", "hi" to "शेड्यूल", "ur" to "شیڈول", "ar" to "الجدول", "es" to "Horario", "pt" to "Agenda", "id" to "Jadwal"),
        "tab_limits" to mapOf("bn" to "লিমিট", "en" to "Limits", "hi" to "सीमा", "ur" to "حد", "ar" to "الحدود", "es" to "Límites", "pt" to "Limites", "id" to "Batas"),
        "tab_settings" to mapOf("bn" to "সেটিংস", "en" to "Settings", "hi" to "सेटिंग्स", "ur" to "ترتیبات", "ar" to "الإعدادات", "es" to "Ajustes", "pt" to "Configurações", "id" to "Pengaturan"),
        "card_status" to mapOf("bn" to "অবস্থা", "en" to "Status", "hi" to "स्थिति", "ur" to "حالت", "ar" to "الحالة", "es" to "Estado", "pt" to "Status", "id" to "Status"),
        "card_permissions" to mapOf("bn" to "পারমিশন", "en" to "Permissions", "hi" to "अनुमतियाँ", "ur" to "اجازتیں", "ar" to "الأذونات", "es" to "Permisos", "pt" to "Permissões", "id" to "Izin"),
        "card_now" to mapOf("bn" to "এখনকার অবস্থা", "en" to "Right now", "hi" to "अभी की स्थिति", "ur" to "ابھی کی حالت", "ar" to "الآن", "es" to "Ahora mismo", "pt" to "Agora", "id" to "Saat ini"),
        "card_today" to mapOf("bn" to "আজকের হিসাব", "en" to "Today's usage", "hi" to "आज का उपयोग", "ur" to "آج کا استعمال", "ar" to "استخدام اليوم", "es" to "Uso de hoy", "pt" to "Uso de hoje", "id" to "Penggunaan hari ini"),
        "card_focus_session" to mapOf("bn" to "ফোকাস সেশন", "en" to "Focus session", "hi" to "फ़ोकस सत्र", "ur" to "فوکس سیشن", "ar" to "جلسة التركيز", "es" to "Sesión de enfoque", "pt" to "Sessão de foco", "id" to "Sesi fokus"),
        "card_presets" to mapOf("bn" to "আমার প্রিসেট", "en" to "My presets", "hi" to "मेरे प्रीसेट", "ur" to "میرے پری سیٹ", "ar" to "إعداداتي المسبقة", "es" to "Mis preajustes", "pt" to "Meus predefinidos", "id" to "Preset saya"),
        "card_notifications" to mapOf("bn" to "নোটিফিকেশন", "en" to "Notifications", "hi" to "सूचनाएँ", "ur" to "اطلاعات", "ar" to "الإشعارات", "es" to "Notificaciones", "pt" to "Notificações", "id" to "Notifikasi"),
        "card_schedules" to mapOf("bn" to "লক শিডিউল", "en" to "Lock schedules", "hi" to "लॉक शेड्यूल", "ur" to "لاک شیڈول", "ar" to "جداول القفل", "es" to "Horarios de bloqueo", "pt" to "Agendas de bloqueio", "id" to "Jadwal kunci"),
        "card_app_limits" to mapOf("bn" to "অ্যাপ লিমিট", "en" to "App limits", "hi" to "ऐप सीमा", "ur" to "ایپ کی حد", "ar" to "حدود التطبيقات", "es" to "Límites de apps", "pt" to "Limites de apps", "id" to "Batas aplikasi"),
        "card_site_block" to mapOf("bn" to "ওয়েবসাইট ব্লক", "en" to "Website blocking", "hi" to "वेबसाइट ब्लॉक", "ur" to "ویب سائٹ بلاک", "ar" to "حظر المواقع", "es" to "Bloqueo de sitios", "pt" to "Bloqueio de sites", "id" to "Blokir situs"),
        "card_perm_settings" to mapOf("bn" to "পারমিশন সেটিংস", "en" to "Permission settings", "hi" to "अनुमति सेटिंग्स", "ur" to "اجازت کی ترتیبات", "ar" to "إعدادات الأذونات", "es" to "Ajustes de permisos", "pt" to "Config. de permissões", "id" to "Pengaturan izin"),
        "card_security" to mapOf("bn" to "সুরক্ষা", "en" to "Security", "hi" to "सुरक्षा", "ur" to "حفاظت", "ar" to "الأمان", "es" to "Seguridad", "pt" to "Segurança", "id" to "Keamanan"),
        "card_backup" to mapOf("bn" to "সেটিংস ব্যাকআপ", "en" to "Settings backup", "hi" to "सेटिंग्स बैकअप", "ur" to "ترتیبات کا بیک اپ", "ar" to "نسخ الإعدادات", "es" to "Copia de ajustes", "pt" to "Backup de configurações", "id" to "Cadangan pengaturan"),
        "card_tamper_log" to mapOf("bn" to "ট্যাম্পার লগ", "en" to "Tamper log", "hi" to "छेड़छाड़ लॉग", "ur" to "چھेड़ چھاڑ لاگ", "ar" to "سجل العبث", "es" to "Registro de manipulación", "pt" to "Registro de violações", "id" to "Log gangguan"),
        "appearance" to mapOf("bn" to "চেহারা ও ভাষা", "en" to "Appearance & language", "hi" to "रूप और भाषा", "ur" to "ظاہری شکل اور زبان", "ar" to "المظهر واللغة", "es" to "Apariencia e idioma", "pt" to "Aparência e idioma", "id" to "Tampilan & bahasa"),
        "language" to mapOf("bn" to "ভাষা", "en" to "Language", "hi" to "भाषा", "ur" to "زبان", "ar" to "اللغة", "es" to "Idioma", "pt" to "Idioma", "id" to "Bahasa"),
        "theme" to mapOf("bn" to "থিম", "en" to "Theme", "hi" to "थीम", "ur" to "تھیم", "ar" to "السمة", "es" to "Tema", "pt" to "Tema", "id" to "Tema"),
        "on" to mapOf("bn" to "চালু", "en" to "On", "hi" to "चालू", "ur" to "آن", "ar" to "مفعّل", "es" to "Activo", "pt" to "Ligado", "id" to "Aktif"),
        "off" to mapOf("bn" to "বন্ধ", "en" to "Off", "hi" to "बंद", "ur" to "آف", "ar" to "مغلق", "es" to "Inactivo", "pt" to "Desligado", "id" to "Nonaktif"),
        "lock_remaining" to mapOf("bn" to "বাকি", "en" to "Remaining", "hi" to "शेष", "ur" to "باقی", "ar" to "المتبقي", "es" to "Restante", "pt" to "Restante", "id" to "Sisa"),
        "lock_focus_running" to mapOf("bn" to "ফোকাস সেশন চলছে — পুরো ফোন লক", "en" to "Focus session running — phone locked", "hi" to "फ़ोकस सत्र चल रहा है — फ़ोन लॉक है", "ur" to "فوکس سیشن جاری ہے — فون لاک ہے", "ar" to "جلسة التركيز جارية — الهاتف مقفل", "es" to "Sesión de enfoque en curso — teléfono bloqueado", "pt" to "Sessão de foco em andamento — telefone bloqueado", "id" to "Sesi fokus berjalan — ponsel terkunci"),
        "lock_focus_not_picked" to mapOf("bn" to "ফোকাস চলছে — এই অ্যাপটা বাছাই করা হয়নি", "en" to "Focus is on — this app was not picked", "hi" to "फ़ोकस चालू है — यह ऐप चुना नहीं गया", "ur" to "فوکس آن ہے — یہ ایپ منتخب نہیں", "ar" to "التركيز مفعّل — لم يتم اختيار هذا التطبيق", "es" to "Enfoque activo — esta app no fue elegida", "pt" to "Foco ativo — este app não foi escolhido", "id" to "Fokus aktif — aplikasi ini tidak dipilih"),
        "lock_site_blocked" to mapOf("bn" to "এই ওয়েবসাইটটা ব্লক করা", "en" to "This website is blocked", "hi" to "यह वेबसाइट ब्लॉक है", "ur" to "یہ ویب سائٹ بلاک ہے", "ar" to "هذا الموقع محظور", "es" to "Este sitio está bloqueado", "pt" to "Este site está bloqueado", "id" to "Situs ini diblokir"),
        "lock_press_home" to mapOf("bn" to "Home চেপে বেরিয়ে যাও — এই সাইটটা খোলা যাবে না", "en" to "Press Home to leave — this site can't be opened", "hi" to "Home दबाकर बाहर जाएँ — यह साइट नहीं खुलेगी", "ur" to "Home دبا کر باہر جائیں — یہ سائٹ نہیں کھلے گی", "ar" to "اضغط الرئيسية للمغادرة — لا يمكن فتح هذا الموقع", "es" to "Pulsa Inicio para salir — este sitio no se puede abrir", "pt" to "Pressione Início para sair — este site não pode ser aberto", "id" to "Tekan Beranda untuk keluar — situs ini tidak bisa dibuka"),
        "lock_app_time_over" to mapOf("bn" to "এই অ্যাপের আজকের সময় শেষ", "en" to "Today's time for this app is over", "hi" to "इस ऐप का आज का समय समाप्त", "ur" to "اس ایپ کا آج کا وقت ختم", "ar" to "انتهى وقت هذا التطبيق اليوم", "es" to "Se acabó el tiempo de hoy para esta app", "pt" to "O tempo de hoje deste app acabou", "id" to "Waktu aplikasi ini hari ini habis"),
        "lock_tomorrow" to mapOf("bn" to "কাল আবার ব্যবহার করা যাবে", "en" to "You can use it again tomorrow", "hi" to "कल फिर उपयोग किया जा सकेगा", "ur" to "کل دوبارہ استعمال کیا جا سکے گا", "ar" to "يمكنك استخدامه غداً من جديد", "es" to "Podrás usarla de nuevo mañana", "pt" to "Você poderá usá-lo novamente amanhã", "id" to "Bisa digunakan lagi besok"),
        "lock_time" to mapOf("bn" to "লক টাইম", "en" to "Lock time", "hi" to "लॉक टाइम", "ur" to "لاک ٹائم", "ar" to "وقت القفل", "es" to "Hora de bloqueo", "pt" to "Hora de bloqueio", "id" to "Waktu kunci"),
        "lock_emergency" to mapOf("bn" to "জরুরি কল", "en" to "Emergency call", "hi" to "आपातकालीन कॉल", "ur" to "ایمرجنسی کال", "ar" to "مكالمة طوارئ", "es" to "Llamada de emergencia", "pt" to "Chamada de emergência", "id" to "Panggilan darurat"),
        "lock_alarm" to mapOf("bn" to "অ্যালার্ম / ঘড়ি", "en" to "Alarm / Clock", "hi" to "अलार्म / घड़ी", "ur" to "الارم / گھڑی", "ar" to "المنبه / الساعة", "es" to "Alarma / Reloj", "pt" to "Alarme / Relógio", "id" to "Alarm / Jam"),
        "lock_session_apps" to mapOf("bn" to "এই সেশনে যেগুলো চলবে", "en" to "Apps allowed in this session", "hi" to "इस सत्र में चलने वाले ऐप", "ur" to "اس سیشن میں چلنے والی ایپس", "ar" to "التطبيقات المسموحة في هذه الجلسة", "es" to "Apps permitidas en esta sesión", "pt" to "Apps permitidos nesta sessão", "id" to "Aplikasi yang diizinkan di sesi ini")
    )
}
