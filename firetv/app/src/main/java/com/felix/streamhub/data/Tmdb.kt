package com.felix.streamhub.data

import org.json.JSONObject

/**
 * TMDB v3. Attribution, required by their terms:
 *   "This product uses the TMDB API but is not endorsed or certified by TMDB."
 * Watch-provider availability comes from JustWatch via TMDB and is credited to
 * JustWatch in the UI.
 */
class Tmdb(private val store: Store) {

    private val base = "https://api.themoviedb.org/3"
    private var providerIdCache: Map<String, Set<Int>>? = null

    private val key get() = store.tmdbKey
    private val region get() = store.region
    private val language get() = store.language

    private fun url(path: String, params: Map<String, String?> = emptyMap()): String {
        val sb = StringBuilder(base).append(path).append("?api_key=").append(key)
        if (!params.containsKey("language")) sb.append("&language=").append(language)
        params.forEach { (k, v) ->
            if (!v.isNullOrEmpty()) {
                sb.append('&').append(k).append('=')
                    .append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        return sb.toString()
    }

    fun hasKey(): Boolean = key.isNotBlank()

    // ---- providers ---------------------------------------------------------

    /**
     * Resolve our four services to the provider ids TMDB uses in this region.
     * Matched by name so a rebrand does not silently break availability.
     */
    suspend fun providerIds(): Map<String, Set<Int>> {
        providerIdCache?.let { return it }

        val all = mutableListOf<JSONObject>()
        for (type in listOf("movie", "tv")) {
            runCatching {
                val res = Http.getJson(url("/watch/providers/$type", mapOf("watch_region" to region)))
                all += res.optJSONArray("results").objects()
            }
        }

        val out = HashMap<String, Set<Int>>()
        for (svc in Services.ALL) {
            val ids = LinkedHashSet<Int>()
            for (wanted in svc.tmdbNames) {
                for (p in all) {
                    if (p.optString("provider_name").equals(wanted, ignoreCase = true)) {
                        ids += p.optInt("provider_id")
                    }
                }
            }
            if (ids.isEmpty()) {
                val primary = svc.tmdbNames.first().lowercase()
                for (p in all) {
                    if (p.optString("provider_name").lowercase().startsWith(primary)) {
                        ids += p.optInt("provider_id")
                    }
                }
            }
            out[svc.id] = ids
        }
        providerIdCache = out
        return out
    }

    fun invalidateProviders() {
        providerIdCache = null
        Http.clearCache()
    }

    // ---- search & details --------------------------------------------------

    suspend fun search(query: String): List<Title> {
        val res = Http.getJson(url("/search/multi", mapOf("query" to query, "include_adult" to "false")))
        return res.optJSONArray("results").objects()
            .filter { it.optString("media_type") == "movie" || it.optString("media_type") == "tv" }
            .map { Title.fromTmdb(it) }
    }

    /** Which of the four services carry one title here. Search results say nothing about it. */
    suspend fun availability(mediaType: String, id: Int): List<Availability> {
        val res = Http.getJson(url("/$mediaType/$id/watch/providers"))
        return availabilityFrom(res.optJSONObject("results")?.optJSONObject(region), providerIds())
    }

    suspend fun details(mediaType: String, id: Int): TitleDetail {
        val append = if (mediaType == "tv")
            "credits,watch/providers,recommendations,external_ids"
        else
            "credits,watch/providers,recommendations,external_ids"

        val d = Http.getJson(url("/$mediaType/$id", mapOf("append_to_response" to append)))

        val regionProviders = d.optJSONObject("watch/providers")
            ?.optJSONObject("results")
            ?.optJSONObject(region)
        val availableOn = availabilityFrom(regionProviders, runCatching { providerIds() }.getOrDefault(emptyMap()))

        val crew = d.optJSONObject("credits")?.optJSONArray("crew").objects()
        var directors = crew.filter { it.optString("job") == "Director" }.map { it.optString("name") }
        if (directors.isEmpty()) {
            directors = d.optJSONArray("created_by").objects().map { it.optString("name") }
        }

        val cast = d.optJSONObject("credits")?.optJSONArray("cast").objects().take(10).map {
            CastMember(it.optString("name"), it.optStringOrNull("character"), it.optStringOrNull("profile_path"))
        }

        val runtime = d.optIntOrNull("runtime")
            ?: d.optJSONArray("episode_run_time").toIntList().firstOrNull()

        return TitleDetail(
            title = Title.fromTmdb(d, mediaType).copy(
                genreIds = d.optJSONArray("genres").objects().map { it.optInt("id") }
            ),
            runtime = runtime,
            seasons = d.optIntOrNull("number_of_seasons"),
            episodes = d.optIntOrNull("number_of_episodes"),
            genres = d.optJSONArray("genres").objects().map { it.optString("name") },
            directors = directors,
            cast = cast,
            imdbId = d.optJSONObject("external_ids")?.optStringOrNull("imdb_id") ?: d.optStringOrNull("imdb_id"),
            availableOn = availableOn,
            recommendations = d.optJSONObject("recommendations")?.optJSONArray("results").objects()
                .take(18).map { Title.fromTmdb(it) },
            ratings = null // filled in by Omdb, which is optional
        )
    }

    // ---- discovery ---------------------------------------------------------

    suspend fun discover(
        mediaType: String,
        providerIds: Collection<Int>? = null,
        genres: Collection<Int>? = null,
        sortBy: String = "popularity.desc",
        extra: Map<String, String> = emptyMap()
    ): List<Title> {
        val params = discoverParams(region, providerIds, genres, sortBy, extra)
        val res = Http.getJson(url("/discover/$mediaType", params))
        return res.optJSONArray("results").objects().map { Title.fromTmdb(it, mediaType) }
    }

    suspend fun trending(): List<Title> {
        val res = Http.getJson(url("/trending/all/week"))
        return res.optJSONArray("results").objects()
            .filter { it.optString("media_type") == "movie" || it.optString("media_type") == "tv" }
            .map { Title.fromTmdb(it) }
    }

    companion object {
        fun image(path: String?, size: String = "w342"): String? =
            if (path.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/$size$path"

        /**
         * In TMDB discover a comma means AND and a pipe means OR. Genres joined
         * with "," asked for titles in every one of your top genres at once, so
         * "Picks for you" rarely showed. (Fixed long ago in the desktop app and
         * never here.) Monetization is limited to subscription-style so rentals
         * stay out of the rows.
         */
        fun discoverParams(
            region: String,
            providerIds: Collection<Int>?,
            genres: Collection<Int>?,
            sortBy: String,
            extra: Map<String, String>
        ): Map<String, String?> {
            val params = HashMap<String, String?>()
            params["watch_region"] = region
            params["include_adult"] = "false"
            params["vote_count.gte"] = "25"
            params["sort_by"] = sortBy
            if (!providerIds.isNullOrEmpty()) {
                params["with_watch_providers"] = providerIds.joinToString("|")
                params["with_watch_monetization_types"] = "flatrate|free|ads"
            }
            if (!genres.isNullOrEmpty()) params["with_genres"] = genres.joinToString("|")
            params.putAll(extra)
            return params
        }

        /** Subscription beats rent: a service that has it included is listed as included. */
        fun availabilityFrom(regionProviders: JSONObject?, ids: Map<String, Set<Int>>): List<Availability> {
            val flatrate = regionProviders?.optJSONArray("flatrate").objects().map { it.optInt("provider_id") }.toSet()
            val rentBuy = (regionProviders?.optJSONArray("rent").objects() +
                    regionProviders?.optJSONArray("buy").objects()).map { it.optInt("provider_id") }.toSet()
            return Services.ALL.mapNotNull { svc ->
                val mine = ids[svc.id].orEmpty()
                when {
                    mine.any { it in flatrate } -> Availability(svc.id, included = true)
                    mine.any { it in rentBuy } -> Availability(svc.id, included = false)
                    else -> null
                }
            }
        }
    }
}
