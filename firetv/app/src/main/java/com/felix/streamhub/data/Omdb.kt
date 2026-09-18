package com.felix.streamhub.data

/**
 * Rotten Tomatoes, IMDb and Metacritic, which TMDB does not carry. Optional -
 * with no key the UI just shows the TMDB score.
 *
 * Letterboxd is absent on purpose: their API is invite-only and they state they
 * do not grant access for personal or recommendation projects, so there is no
 * legitimate way to pull their ratings into an app like this.
 */
class Omdb(private val store: Store) {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Ratings>()

    suspend fun ratings(imdbId: String?): Ratings? {
        val key = store.omdbKey
        if (key.isBlank() || imdbId.isNullOrBlank()) return null
        cache[imdbId]?.let { return it }

        val url = "https://www.omdbapi.com/?apikey=$key&i=$imdbId&tomatoes=true"
        val json = runCatching { Http.getJson(url) }.getOrNull() ?: return null
        if (json.optString("Response") == "False") return null

        val list = json.optJSONArray("Ratings").objects()
        fun find(source: String): String? =
            list.firstOrNull { it.optString("Source") == source }?.optStringOrNull("Value")

        fun na(s: String?): String? = if (s.isNullOrEmpty() || s == "N/A") null else s

        val rt = find("Rotten Tomatoes")?.removeSuffix("%")?.toIntOrNull()
        val imdb = find("Internet Movie Database")?.substringBefore("/")?.toDoubleOrNull()
            ?: na(json.optStringOrNull("imdbRating"))?.toDoubleOrNull()
        val mc = find("Metacritic")?.substringBefore("/")?.toIntOrNull()
            ?: na(json.optStringOrNull("Metascore"))?.toIntOrNull()

        val result = Ratings(
            rottenTomatoes = rt,
            imdb = imdb,
            metacritic = mc,
            rated = na(json.optStringOrNull("Rated")),
            awards = na(json.optStringOrNull("Awards"))
        )
        cache[imdbId] = result
        return result
    }
}
