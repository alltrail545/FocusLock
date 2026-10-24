package com.focuslock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.security.MessageDigest

/**
 * Automation API (v1.6.0) — built for Tamim's own automation app PhoneLink
 * (com.mthtamim.phonelink), usable by any sender that knows the token.
 *
 * Contract — an EXPLICIT broadcast addressed to this package:
 *   action : com.focuslock.app.AUTOMATION
 *   extras : token  (String, required — SHA-256 must match the hash stored
 *                    in Settings; the plain token is never stored)
 *            cmd    (String, required)
 *            app    (String package name — block_app / unblock)
 *            mins   (Int, 1..1440 — block_app / lock_full; focus override)
 *            preset (String preset name — focus)
 *
 * Commands: block_app, lock_full, focus, unblock.
 *
 * Strictness contract (Tamim's standing rules): commands may only START
 * things. The single exception is `unblock`, which can end ONLY state that
 * automation itself started — timed app blocks and automation sessions.
 * A manual focus session, a schedule or a daily limit can never be lifted
 * through this API.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val storedHash = Store.automationTokenHash(ctx)
        if (storedHash.isBlank()) return // API stays off until a token is set
        val token = intent.getStringExtra("token") ?: ""
        if (token.isBlank() || sha256(token) != storedHash) {
            Store.logTamper(ctx, "Automation command rejected (bad token)")
            return
        }
        val now = System.currentTimeMillis()
        when (intent.getStringExtra("cmd")) {
            "block_app" -> {
                val pkg = intent.getStringExtra("app")?.trim().orEmpty()
                val mins = intent.getIntExtra("mins", 0)
                if (pkg.isBlank() || pkg == ctx.packageName || mins !in 1..1440) {
                    Store.logTamper(ctx, "Automation block_app ignored (bad extras)")
                    return
                }
                Store.saveTimedAppBlocks(
                    ctx, Store.timedAppBlocks(ctx) + (pkg to now + mins * 60_000L)
                )
                Store.logTamper(ctx, "Automation: $pkg blocked for $mins min")
                Engine.tick(ctx)
            }
            "lock_full" -> {
                val mins = intent.getIntExtra("mins", 0)
                startSession(ctx, mins, emptySet(), "lock_full")
            }
            "focus" -> {
                val presetName = intent.getStringExtra("preset")?.trim().orEmpty()
                val preset = Store.focusPresets(ctx).firstOrNull { it.name == presetName }
                if (preset == null) {
                    Store.logTamper(ctx, "Automation focus ignored (unknown preset: $presetName)")
                    return
                }
                val override = intent.getIntExtra("mins", 0)
                val mins = if (override in 1..1440) override else preset.minutes
                startSession(ctx, mins, preset.allowed, "focus preset '$presetName'")
            }
            "unblock" -> {
                val pkg = intent.getStringExtra("app")?.trim().orEmpty()
                if (pkg.isNotBlank()) {
                    Store.saveTimedAppBlocks(ctx, Store.timedAppBlocks(ctx) - pkg)
                    Store.logTamper(ctx, "Automation: timed block lifted for $pkg")
                } else {
                    Store.saveTimedAppBlocks(ctx, emptyMap())
                    if (Store.isAutoSession(ctx) && Store.focusEndMs(ctx) > now) {
                        Store.setFocusEndMs(ctx, 0L)
                        Store.setFocusStartMs(ctx, 0L)
                        Store.setAutoSession(ctx, false)
                        AdminHelper.applyLockTaskPackages(ctx)
                        Store.logTamper(ctx, "Automation: automation session ended early by unblock")
                    } else {
                        Store.logTamper(ctx, "Automation: unblock cleared timed blocks (no automation session running)")
                    }
                }
                Engine.tick(ctx)
            }
            else -> Store.logTamper(ctx, "Automation: unknown cmd ignored")
        }
    }

    private fun startSession(ctx: Context, mins: Int, allowed: Set<String>, kind: String) {
        val now = System.currentTimeMillis()
        if (Store.focusEndMs(ctx) > now) {
            Store.logTamper(ctx, "Automation $kind ignored (a focus session is already running)")
            return
        }
        if (mins !in 1..1440) {
            Store.logTamper(ctx, "Automation $kind ignored (bad mins: $mins)")
            return
        }
        Store.setFocusStartMs(ctx, now)
        Store.setFocusEndMs(ctx, now + mins * 60_000L)
        Store.setFocusSessionAllowed(ctx, allowed)
        Store.setAutoSession(ctx, true)
        AdminHelper.applyLockTaskPackages(ctx, allowed)
        Store.logTamper(ctx, "Automation: $kind started for $mins min")
        Engine.tick(ctx)
        Engine.ensureLockShown(ctx)
    }

    companion object {
        const val ACTION = "com.focuslock.app.AUTOMATION"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
