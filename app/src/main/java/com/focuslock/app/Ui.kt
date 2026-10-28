package com.focuslock.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * FocusLock design system v2 — "Classic Glass" (Tamim's pick, 2026-10-28,
 * superseding the 2026-10-23 emerald bento system at his request).
 * Liquid Glass look on an aurora gradient: translucent white glass cards
 * with bright refractive edges and soft elevation, white text, pink-violet
 * accent. Screens stay text-light (Tamim's standing rule): explanations
 * live behind the ⓘ info button, not on the surface.
 */
object Ui {
    // v1.15.0: every token now reads the CURRENT theme (Themes.apply is
    // called at each UI entry point from Store.themeId). The classic_glass
    // theme's values are exactly the old constants, so the default look
    // is unchanged; all existing Ui.X references follow the theme.
    /** Solid fallback behind the aurora gradient. */
    val BG: Int get() = Themes.current.bg
    /** Glass card fill. */
    val CARD: Int get() = Themes.current.card
    /** Stronger glass: raised surfaces, tracks, inactive chips. */
    val RAISED: Int get() = Themes.current.raised
    /** Bright glass edge. */
    val STROKE: Int get() = Themes.current.stroke
    /** Accent (progress, active states). */
    val ACCENT: Int get() = Themes.current.accent
    /** Secondary highlight colour. */
    val BRIGHT: Int get() = Themes.current.bright
    val TEXT: Int get() = Themes.current.text
    val SECONDARY: Int get() = Themes.current.secondary
    val MUTED: Int get() = Themes.current.muted

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke) setStroke(dp(ctx, 1), STROKE)
        }

    /** Theme gradient fill (primary actions, progress). */
    private fun accentGradient(ctx: Context, radiusDp: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Themes.current.gradientStart, Themes.current.gradientEnd)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    /**
     * The aurora backdrop: a diagonal indigo → violet → pink gradient with
     * two soft radial glows (pink top-right, blue bottom-left). Falls back
     * to solid [BG] if anything goes wrong — a background must never crash.
     */
    fun aurora(ctx: Context): android.graphics.drawable.Drawable {
        return try {
            val t = Themes.current
            val w = ctx.resources.displayMetrics.widthPixels.toFloat()
            val base = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                t.bgStops
            )
            if (t.isLight || t.bgStops.size == 1) {
                return base
            }
            fun glow(color: Int, alpha: Int, cx: Float, cy: Float) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(cx, cy)
                gradientRadius = w * 1.05f
                colors = intArrayOf(
                    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
                )
            }
            LayerDrawable(arrayOf(base, glow(t.accent, 110, 0.88f, 0.10f), glow(t.bright, 90, 0.08f, 0.92f)))
        } catch (_: Throwable) {
            android.graphics.drawable.ColorDrawable(BG)
        }
    }

    /** Glass card: translucent white, bright edge, 24 radius, 18 padding. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 24, stroke = true)
        setPadding(dp(ctx, 18), dp(ctx, 18), dp(ctx, 18), dp(ctx, 18))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 16) }
        elevation = dp(ctx, 6).toFloat()
    }

    fun appTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 22f; setTextColor(TEXT)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun cardTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 19f; setTextColor(TEXT)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, 0, 0, dp(ctx, 10))
    }

    /**
     * Card title row with a small glass ⓘ button at the end — the home
     * for explanations under Tamim's text-diet rule. [onInfo] shows the
     * explanation (the caller owns the dialog).
     */
    fun cardHeader(ctx: Context, title: String, onInfo: (() -> Unit)? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(ctx, 10))
            addView(TextView(ctx).apply {
                text = title; textSize = 19f; setTextColor(TEXT)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (onInfo != null) addView(infoButton(ctx, onInfo))
        }

    /** Small round glass "i" button. */
    fun infoButton(ctx: Context, onClick: () -> Unit) = TextView(ctx).apply {
        text = "i"
        textSize = 13f
        setTextColor(TEXT)
        typeface = Typeface.create("serif", Typeface.BOLD or Typeface.ITALIC)
        gravity = Gravity.CENTER
        background = rounded(ctx, RAISED, 999, stroke = true)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
        setOnClickListener { onClick() }
    }

    fun body(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 14f; setTextColor(TEXT)
    }

    fun secondary(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 14f; setTextColor(SECONDARY)
    }

    fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 12f; setTextColor(MUTED)
    }

    /** A light haptic tick on tap, guarded — feedback is a nicety,
     *  never a failure path. Shared by both button styles. */
    private fun View.hapticTick() {
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        } catch (_: Throwable) { }
    }

    /** Primary action: violet → pink gradient pill, white bold text. */
    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        background = accentGradient(ctx, 999)
        minHeight = dp(ctx, 48)
        setOnClickListener { hapticTick(); onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    /** Secondary action: glass pill with a bright edge. */
    fun secondaryButton(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(TEXT)
        background = rounded(ctx, RAISED, 999, stroke = true)
        minHeight = dp(ctx, 48)
        setOnClickListener { hapticTick(); onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    /** Small pill chip: fully round. Active = accent gradient, inactive = glass. */
    fun chip(ctx: Context, text: String, active: Boolean) = TextView(ctx).apply {
        this.text = text; textSize = 12f
        if (active) {
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = accentGradient(ctx, 999)
        } else {
            setTextColor(SECONDARY)
            background = rounded(ctx, RAISED, 999, stroke = true)
        }
        setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        minHeight = dp(ctx, 32)
        gravity = Gravity.CENTER
    }

    /**
     * Slim glass progress bar: translucent track with a bright edge,
     * violet → pink gradient fill, 10dp tall, fully rounded.
     * [percent] is clamped to 0..100.
     */
    fun progressBar(ctx: Context, percent: Int): LinearLayout {
        val p = percent.coerceIn(0, 100)
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(ctx, RAISED, 999, stroke = true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 10)
            ).apply {
                topMargin = dp(ctx, 6)
                bottomMargin = dp(ctx, 4)
            }
        }
        if (p > 0) {
            track.addView(View(ctx).apply {
                background = accentGradient(ctx, 999)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p.toFloat())
            })
        }
        if (p < 100) {
            track.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - p).toFloat())
            })
        }
        return track
    }

    /** One status row: name on the left, chip on the right. Default
     *  on/off chip texts follow the app language (v1.15.0). */
    fun statusRow(ctx: Context, name: String, on: Boolean, onText: String? = null, offText: String? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            addView(body(ctx, name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(ctx, if (on) (onText ?: Lang.t(ctx, "on")) else (offText ?: Lang.t(ctx, "off")), on))
        }

    fun gap(ctx: Context, hDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp))
    }
}
