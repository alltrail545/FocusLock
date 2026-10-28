package com.focuslock.app

/**
 * One-tap website category filters (v1.6.0).
 *
 * Tamim asked for the category filtering idea from Urbandroid's Digital
 * Detox: instead of typing sites one by one, a whole category is blocked
 * with one tap. Each category is a curated domain list; matching is by
 * host (the domain itself or any subdomain), done in
 * BlockerAccessibilityService. Enabling a category is immediate
 * (strengthening); disabling one follows the usual PIN + 24h weakening
 * rule via the "remove_category:" pending action.
 */
object SiteCategories {

    data class Category(val id: String, val labelBn: String, val domains: List<String>)

    val ALL: List<Category> = listOf(
        Category(
            "porn", "পর্নোগ্রাফি",
            listOf(
                "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
                "redtube.com", "youporn.com", "brazzers.com", "onlyfans.com",
                "chaturbate.com", "stripchat.com", "livejasmin.com",
                "bongacams.com", "myfreecams.com", "spankbang.com",
                "eporner.com", "porntrex.com", "beeg.com", "4tube.com",
                "drtuber.com", "rule34.xxx", "nhentai.net", "hanime.tv",
                "hentaihaven.xxx", "xozilla.com", "tnaflix.com"
            )
        ),
        Category(
            "social", "সোশ্যাল মিডিয়া",
            listOf(
                "facebook.com", "instagram.com", "tiktok.com", "twitter.com",
                "x.com", "snapchat.com", "reddit.com", "threads.net",
                "vk.com", "ok.ru", "pinterest.com", "tumblr.com",
                "likee.video", "kwai.com", "gab.com"
            )
        ),
        Category(
            "news", "খবর",
            listOf(
                "bbc.com", "bbc.co.uk", "cnn.com", "aljazeera.com",
                "nytimes.com", "theguardian.com", "reuters.com", "apnews.com",
                "bloomberg.com", "foxnews.com", "nbcnews.com",
                "prothomalo.com", "thedailystar.net", "bdnews24.com",
                "jugantor.com", "kalerkantho.com", "ittefaq.com.bd",
                "samakal.com", "banglanews24.com", "dhakatribune.com",
                "risingbd.com", "jagonews24.com", "daily-sun.com",
                "newagebd.net"
            )
        ),
        Category(
            "gambling", "জুয়া",
            listOf(
                "bet365.com", "1xbet.com", "melbet.com", "parimatch.com",
                "betway.com", "bwin.com", "pokerstars.com", "888casino.com",
                "bovada.com", "stake.com", "bc.game", "rajabets.com",
                "22bet.com", "mostbet.com", "linebet.com", "megapari.com",
                "baji.live", "krikya.com"
            )
        )
    )

    fun byId(id: String): Category? = ALL.firstOrNull { it.id == id }

    fun domainsFor(enabled: Set<String>): List<String> =
        ALL.filter { it.id in enabled }.flatMap { it.domains }

    /**
     * Host-based match: true when [urlText]'s host is [domain] or a
     * subdomain of it. Safer than a raw substring test (which would let
     * "x.com" match "box.com").
     */
    /** The bare host of a URL-bar text ("https://m.facebook.com/x" -> "m.facebook.com"). */
    fun hostOf(urlText: String): String {
        var host = urlText.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
        // v1.8.0 audit: also strip any userinfo ("user@host") and a trailing
        // root dot ("facebook.com." is the same host as "facebook.com") —
        // both used to survive normalisation and slip past exact matching.
        return host.substringBefore("/").substringBefore("?").substringBefore("#")
            .substringBefore(":").substringAfterLast("@").trim().trimEnd('.')
    }

    /**
     * Normalise a stored domain the same way [hostOf] normalises a live
     * host: case, scheme, path/port and a leading "www." all come off.
     * v1.8.0 audit: an allow-list entry stored as "www.bbc.com" (e.g. from
     * a hand-edited backup) never matched the browser's "bbc.com" host, so
     * an allow-listed site was blocked anyway; a category/block entry with
     * stray case or a port had the mirror-image problem.
     */
    private fun normalizeDomain(domain: String): String =
        hostOf(domain).removePrefix("www.")

    fun hostMatches(urlText: String, domain: String): Boolean {
        val host = hostOf(urlText).removePrefix("www.")
        val dom = normalizeDomain(domain)
        if (host.isBlank() || dom.isBlank()) return false
        return host == dom || host.endsWith(".$dom")
    }
}
