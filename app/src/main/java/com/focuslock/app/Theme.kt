package com.focuslock.app

import android.graphics.Color

/**
 * v1.15.0: the 12-theme table (same set as OwnerControl, Tamim's ask).
 * A theme is colours ONLY — layout and behaviour never change with it.
 * Ui reads the CURRENT theme through getters, so every existing
 * Ui.CARD / Ui.TEXT call site follows the chosen theme automatically.
 * Default = classic_glass, whose tokens are exactly the v1.13.0
 * Classic Glass values, so the default look is unchanged.
 */
data class AppTheme(
    val id: String,
    val displayName: String,
    val bgStops: IntArray,
    val bg: Int,
    val card: Int,
    val raised: Int,
    val stroke: Int,
    val accent: Int,
    val gradientStart: Int,
    val gradientEnd: Int,
    val bright: Int,
    val text: Int,
    val secondary: Int,
    val muted: Int,
    val isLight: Boolean
)

object Themes {
    private fun c(hex: String) = Color.parseColor(hex)
    private fun glassCard() = Color.argb(43, 255, 255, 255)
    private fun glassRaised() = Color.argb(64, 255, 255, 255)
    private fun glassStroke() = Color.argb(115, 255, 255, 255)

    val ALL: List<AppTheme> = listOf(
        AppTheme("classic_glass", "Classic Glass",
            intArrayOf(c("#160A33"), c("#3B1470"), c("#7C2D92"), c("#C2439D"), c("#241259")),
            c("#160B36"), glassCard(), glassRaised(), glassStroke(),
            c("#E879C6"), c("#8B5CF6"), c("#EC6BD8"), c("#A5C8FF"),
            c("#FFFFFF"), c("#DCD6F7"), c("#ACA3D9"), false),
        AppTheme("emerald_glass", "Emerald Glass",
            intArrayOf(c("#04150F"), c("#06392A"), c("#0B6B4F"), c("#12A594"), c("#052E22")),
            c("#04150F"), glassCard(), glassRaised(), glassStroke(),
            c("#34D399"), c("#059669"), c("#2DD4BF"), c("#A7F3D0"),
            c("#FFFFFF"), c("#D1FAE5"), c("#9DB8AD"), false),
        AppTheme("warm_orange_glass", "Warm Orange Glass",
            intArrayOf(c("#1A0B02"), c("#431407"), c("#9A3412"), c("#EA580C"), c("#7C2D12")),
            c("#1A0B02"), glassCard(), glassRaised(), glassStroke(),
            c("#FB923C"), c("#EA580C"), c("#FACC15"), c("#FED7AA"),
            c("#FFFFFF"), c("#FFEDD5"), c("#D6B49A"), false),
        AppTheme("ocean_glass", "Ocean Glass",
            intArrayOf(c("#020617"), c("#082F49"), c("#075985"), c("#0284C7"), c("#0C4A6E")),
            c("#020617"), glassCard(), glassRaised(), glassStroke(),
            c("#38BDF8"), c("#0369A1"), c("#22D3EE"), c("#BAE6FD"),
            c("#FFFFFF"), c("#E0F2FE"), c("#9FB6CC"), false),
        AppTheme("sunset_glass", "Sunset Glass",
            intArrayOf(c("#1E1033"), c("#581C87"), c("#BE185D"), c("#F97316"), c("#451A03")),
            c("#1E1033"), glassCard(), glassRaised(), glassStroke(),
            c("#F472B6"), c("#DB2777"), c("#FB923C"), c("#FBCFE8"),
            c("#FFFFFF"), c("#FCE7F3"), c("#C4A8C0"), false),
        AppTheme("rose_glass", "Rose Glass",
            intArrayOf(c("#1F0510"), c("#500724"), c("#9D174D"), c("#DB2777"), c("#3B0716")),
            c("#1F0510"), glassCard(), glassRaised(), glassStroke(),
            c("#FB7185"), c("#E11D48"), c("#E879C6"), c("#FECDD3"),
            c("#FFFFFF"), c("#FFE4E6"), c("#D3A6B3"), false),
        AppTheme("midnight_amoled", "Midnight AMOLED",
            intArrayOf(c("#000000")),
            c("#000000"), Color.argb(255, 18, 18, 18), Color.argb(255, 32, 32, 32),
            Color.argb(255, 45, 45, 45),
            c("#A78BFA"), c("#7C3AED"), c("#818CF8"), c("#C4B5FD"),
            c("#FFFFFF"), c("#D4D4D8"), c("#8E8E99"), false),
        AppTheme("dark_slate", "Dark Slate",
            intArrayOf(c("#0F172A"), c("#1E293B")),
            c("#0F172A"), Color.argb(255, 30, 41, 59), Color.argb(255, 43, 55, 75),
            Color.argb(255, 71, 85, 105),
            c("#60A5FA"), c("#2563EB"), c("#38BDF8"), c("#BFDBFE"),
            c("#F8FAFC"), c("#CBD5E1"), c("#94A3B8"), false),
        AppTheme("light_clean", "Light Clean",
            intArrayOf(c("#F8FAFC"), c("#EEF2F7")),
            c("#F8FAFC"), Color.argb(255, 255, 255, 255), Color.argb(255, 226, 232, 240),
            Color.argb(255, 203, 213, 225),
            c("#7C3AED"), c("#7C3AED"), c("#DB2777"), c("#2563EB"),
            c("#0F172A"), c("#334155"), c("#64748B"), true),
        AppTheme("solar_light", "Solar Light",
            intArrayOf(c("#FFFBEB"), c("#FEF3C7")),
            c("#FFFBEB"), Color.argb(255, 255, 255, 255), Color.argb(255, 254, 243, 199),
            Color.argb(255, 252, 211, 77),
            c("#D97706"), c("#D97706"), c("#EA580C"), c("#B45309"),
            c("#451A03"), c("#78350F"), c("#A16207"), true),
        AppTheme("cyber_neon", "Cyber Neon",
            intArrayOf(c("#020A05"), c("#03150C")),
            c("#020A05"), Color.argb(255, 6, 30, 18), Color.argb(255, 10, 46, 28),
            Color.argb(255, 0, 120, 60),
            c("#00FF88"), c("#00C853"), c("#00E5FF"), c("#69F0AE"),
            c("#E6FFF2"), c("#A7F3D0"), c("#5E8C74"), false),
        AppTheme("material_you", "Material You",
            intArrayOf(c("#0B1F1A"), c("#134E4A"), c("#155E63")),
            c("#0B1F1A"), glassCard(), glassRaised(), glassStroke(),
            c("#5EEAD4"), c("#0D9488"), c("#93C5FD"), c("#99F6E4"),
            c("#FFFFFF"), c("#CCFBF1"), c("#9AB8B2"), false)
    )

    @Volatile
    var current: AppTheme = ALL[0]

    fun byId(id: String): AppTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** Call at every UI entry point so the whole app shares one theme. */
    fun apply(id: String) { current = byId(id) }
}
