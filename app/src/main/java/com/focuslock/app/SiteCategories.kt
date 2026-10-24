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
    fun hostMatches(urlText: String, domain: String): Boolean {
        var host = urlText.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
        host = host.substringBefore("/").substringBefore("?").substringBefore("#")
            .substringBefore(":").trim()
        if (host.isBlank()) return false
        return host == domain || host.endsWith(".$domain")
    }
}
