package com.focuslock.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * FocusLock design system — exactly as specified by Tamim (2026-10-23).
 * Flat, calm, premium bento cards. No gradients, no shadows (beyond a whisper
 * of elevation on cards), no emoji in any control or label.
 */
object Ui {
    val BG = Color.parseColor("#101415")
    val CARD = Color.parseColor("#1C2326")
    val RAISED = Color.parseColor("#232B2E")
    val STROKE = Color.parseColor("#2A3336")
    val ACCENT = Color.parseColor("#10B981")
    val BRIGHT = Color.parseColor("#2FD68F")
    val TEXT = Color.parseColor("#D9EAE0")
    val SECONDARY = Color.parseColor("#8FA79B")
    val MUTED = Color.parseColor("#7E938A")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke) setStroke(dp(ctx, 1), STROKE)
        }

    /** Bento card: 20 radius, hairline stroke, 16 padding. Add to parent yourself. */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 20, stroke = true)
        setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 16) }
        elevation = dp(ctx, 1).toFloat() // the one subtle shadow the spec allows
    }

    fun appTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 20f; setTextColor(TEXT)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun cardTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 20f; setTextColor(TEXT)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, 0, 0, dp(ctx, 10))
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

    /** v1.12.0 UI/UX: a light haptic tick on tap, guarded — feedback is
     *  a nicety, never a failure path. Shared by both button styles. */
    private fun View.hapticTick() {
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        } catch (_: Throwable) { }
    }

    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(BG)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        background = rounded(ctx, ACCENT, 14)
        // v1.12.0 UI/UX: 48dp minimum touch target + haptic tick.
        minHeight = dp(ctx, 48)
        setOnClickListener { hapticTick(); onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    fun secondaryButton(ctx: Context, text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; textSize = 14f; isAllCaps = false
        setTextColor(TEXT)
        background = rounded(ctx, RAISED, 14, stroke = true)
        // v1.12.0 UI/UX: 48dp minimum touch target + haptic tick.
        minHeight = dp(ctx, 48)
        setOnClickListener { hapticTick(); onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8) }
    }

    /** Small pill chip: fully round. Active = emerald, inactive = raised/muted. */
    fun chip(ctx: Context, text: String, active: Boolean) = TextView(ctx).apply {
        this.text = text; textSize = 12f
        if (active) {
            setTextColor(BG)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = rounded(ctx, ACCENT, 999)
        } else {
            setTextColor(MUTED)
            background = rounded(ctx, RAISED, 999, stroke = true)
        }
        setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        // v1.12.0 UI/UX: consistent chip height (~32dp), same look.
        minHeight = dp(ctx, 32)
        gravity = android.view.Gravity.CENTER
    }

    /**
     * v1.12.0 UI/UX: a slim progress bar built from plain Views in the
     * design-system palette — track RAISED with hairline stroke, fill
     * ACCENT, 8dp tall, fully rounded. [percent] is clamped to 0..100.
     * (A stock ProgressBar widget fights the flat bento styling; this
     * cannot drift off-palette.)
     */
    fun progressBar(ctx: Context, percent: Int): LinearLayout {
        val p = percent.coerceIn(0, 100)
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(ctx, RAISED, 999, stroke = true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            ).apply {
                topMargin = dp(ctx, 6)
                bottomMargin = dp(ctx, 4)
            }
        }
        if (p > 0) {
            track.addView(View(ctx).apply {
                background = rounded(ctx, ACCENT, 999)
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

    /** One status row: name on the left, chip on the right. */
    fun statusRow(ctx: Context, name: String, on: Boolean, onText: String = "চালু", offText: String = "বন্ধ"): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            addView(body(ctx, name), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(ctx, if (on) onText else offText, on))
        }

    fun gap(ctx: Context, hDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp))
    }
}
