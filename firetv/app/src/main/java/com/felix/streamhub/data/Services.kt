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
    val searchUrl: (String) -> String
)

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
            searchUrl = { q -> "https://www.netflix.com/search?q=${enc(q)}" }
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
            searchUrl = { q -> "https://www.disneyplus.com/search?q=${enc(q)}" }
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
            searchUrl = { q -> "https://www.hbomax.com/search?q=${enc(q)}" }
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
            searchUrl = { q -> "https://www.primevideo.com/search/ref=atv_nb_sr?phrase=${enc(q)}" }
        )
    )

    fun byId(id: String): Service? = ALL.firstOrNull { it.id == id }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
