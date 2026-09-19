package com.felix.streamhub.data

import android.graphics.Color

/**
 * The four services, plus every Android package name each of them has shipped
 * under. Names are matched against TMDB's provider list at runtime rather than
 * hardcoding provider ids, because those change whenever a service rebrands.
 */
data class Service(
    val id: String,
    val name: String,
    val short: String,
    /** Solid button background - dark enough for white text to be readable. */
    val color: Int,
    /** Brighter, used for the glow behind a service tile. */
    val accent: Int,
    val packages: List<String>,
    val tmdbNames: List<String>,
    val titleUrl: (String) -> String,
    /**
     * The service's own search, opened inside its app. Null: no search link
     * works, so the app's home screen is the best there is.
     */
    val search: Search?
)

/**
 * @property link builds the link from the title.
 * @property carriesQuery the link itself puts the title into the search.
 * @property typeQuery the link opens an empty search page whose box the
 *   accessibility service can type into (see picker/ProfilePickers.kt).
 */
data class Search(val link: (String) -> String, val carriesQuery: Boolean, val typeQuery: Boolean = false)

object Services {

    val ALL: List<Service> = listOf(
        Service(
            id = "netflix",
            name = "Netflix",
            short = "N",
            color = Color.parseColor("#c1121f"),
            accent = Color.parseColor("#e50914"),
            packages = listOf("com.netflix.ninja", "com.netflix.mediaclient"),
            tmdbNames = listOf("Netflix", "Netflix basic with Ads", "Netflix Standard with Ads"),
            titleUrl = { id -> "https://www.netflix.com/title/$id" },
            search = null
        ),
        Service(
            id = "disneyplus",
            name = "Disney+",
            short = "D+",
            color = Color.parseColor("#0e2a6e"),
            accent = Color.parseColor("#2f7bff"),
            packages = listOf("com.disney.disneyplus", "com.disney.disneyplus.androidtv"),
            tmdbNames = listOf("Disney Plus", "Disney+", "Disney Plus Standard with Ads"),
            titleUrl = { id -> "https://www.disneyplus.com/video/$id" },
            // Opens Disney+'s search page on a real Fire TV; ?q=, ?query= and
            // /search/<q> were all ignored, so the title is typed in instead.
            search = Search({ "disneyplus://www.disneyplus.com/search" }, carriesQuery = false, typeQuery = true)
        ),
        Service(
            id = "hbomax",
            name = "HBO Max",
            short = "HBO",
            color = Color.parseColor("#4b1fa8"),
            accent = Color.parseColor("#8b5cf6"),
            packages = listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.max"),
            tmdbNames = listOf("HBO Max", "Max", "Max Amazon Channel", "HBO Max Amazon Channel"),
            titleUrl = { id -> "https://www.hbomax.com/video/watch/$id" },
            // Opens HBO Max's search page with an empty box on a real Fire TV:
            // ?q= is ignored, /search/<q> shows "Content Not Available", and its
            // screen exposes nothing to type into. You type with the remote.
            search = Search({ "https://play.max.com/search" }, carriesQuery = false)
        ),
        Service(
            id = "primevideo",
            name = "Prime Video",
            short = "PV",
            color = Color.parseColor("#0f4c81"),
            accent = Color.parseColor("#00a8e1"),
            packages = listOf("com.amazon.avod", "com.amazon.firebat", "com.amazon.avod.thirdpartyclient"),
            tmdbNames = listOf("Amazon Prime Video", "Prime Video", "Amazon Video"),
            titleUrl = { asin -> "https://www.amazon.com/gp/video/detail/$asin" },
            // Routed by com.amazon.firebat's DeepLinkRoutingActivity straight to
            // its search results, on a real Fire TV.
            search = Search({ q -> "https://app.primevideo.com/search?phrase=${enc(q)}" }, carriesQuery = true)
        )
    )

    fun byId(id: String): Service? = ALL.firstOrNull { it.id == id }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
