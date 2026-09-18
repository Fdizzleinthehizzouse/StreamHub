package com.felix.streamhub.data

/**
 * Builds the home rows. Two honest sources, same as the desktop app:
 *
 *  - your own StreamHub activity: the genres of what you open and save, fed
 *    into TMDB discover and filtered to the four services you pay for
 *  - what the providers themselves carry, via TMDB/JustWatch
 *
 * There is no way to read your real Netflix or Disney+ "continue watching"
 * rows - none of them expose an API, and scraping a signed-in session would
 * mean handling your credentials. Pinned titles stand in for that.
 */
class Recommender(private val tmdb: Tmdb, private val store: Store) {

    /** Rows for one phone. Taste comes only from that phone's own lists. */
    suspend fun buildHome(deviceId: String): List<Row> {
        val rows = mutableListOf<Row>()
        val seen = HashSet<String>()

        // The phone renders its own "Continue watching" strip from `pinned`, so
        // emitting one here too showed it twice. Still marked as seen so the
        // other rows do not repeat those titles.
        store.pinned(deviceId).forEach { seen += it.key }

        val providerIds = runCatching { tmdb.providerIds() }.getOrDefault(emptyMap())
        val allProviders = providerIds.values.flatten()

        // --- because you opened X ------------------------------------------
        store.history(deviceId).firstOrNull()?.let { last ->
            runCatching {
                val detail = tmdb.details(last.mediaType, last.id)
                val recs = detail.recommendations.filterNot { seen.contains(it.key) }.take(18)
                if (recs.isNotEmpty()) {
                    rows += Row("because", "Because you opened ${detail.title.title}", "More in the same vein", recs)
                }
            }
        }

        // --- picks for you ---------------------------------------------------
        val topGenres = tasteGenres(deviceId)
        if (topGenres.isNotEmpty() && allProviders.isNotEmpty()) {
            runCatching {
                val movies = tmdb.discover("movie", allProviders, topGenres)
                val shows = tmdb.discover("tv", allProviders, topGenres)
                val items = interleave(movies, shows).filterNot { seen.contains(it.key) }.distinctBy { it.key }.take(20)
                if (items.isNotEmpty()) {
                    rows += Row("foryou", "Picks for you", "From what you have been opening", items)
                }
            }
        }

        // --- popular on each service ----------------------------------------
        for (svc in Services.ALL) {
            val ids = providerIds[svc.id].orEmpty()
            if (ids.isEmpty()) continue
            runCatching {
                val movies = tmdb.discover("movie", ids)
                val shows = tmdb.discover("tv", ids)
                val items = interleave(movies, shows).distinctBy { it.key }.take(20)
                if (items.isNotEmpty()) {
                    rows += Row("popular-${svc.id}", "Popular on ${svc.name}", null, items)
                }
            }
        }

        // --- trending, and the fallback for a fresh install -------------------
        runCatching {
            val items = tmdb.trending().distinctBy { it.key }.take(20)
            if (items.isNotEmpty()) rows += Row("trending", "Trending this week", null, items)
        }

        return rows
    }

    suspend fun browseService(serviceId: String): List<Row> {
        val ids = runCatching { tmdb.providerIds() }.getOrDefault(emptyMap())[serviceId].orEmpty()
        if (ids.isEmpty()) return emptyList()

        val rows = mutableListOf<Row>()
        runCatching { rows += Row("pm", "Popular films", null, tmdb.discover("movie", ids).take(20)) }
        runCatching { rows += Row("ps", "Popular series", null, tmdb.discover("tv", ids).take(20)) }
        runCatching {
            rows += Row(
                "tm", "Highest rated films", null,
                tmdb.discover("movie", ids, sortBy = "vote_average.desc", extra = mapOf("vote_count.gte" to "400")).take(20)
            )
        }
        runCatching {
            rows += Row(
                "ts", "Highest rated series", null,
                tmdb.discover("tv", ids, sortBy = "vote_average.desc", extra = mapOf("vote_count.gte" to "300")).take(20)
            )
        }
        return rows.filter { it.items.isNotEmpty() }
    }

    /** Genre ids weighted by how recently and how often they show up in your activity. */
    private fun tasteGenres(deviceId: String): List<Int> {
        val weights = HashMap<Int, Double>()
        fun add(t: Title, w: Double) {
            t.genreIds.forEach { weights[it] = (weights[it] ?: 0.0) + w }
        }
        store.history(deviceId).forEachIndexed { i, t -> add(t, maxOf(1.0, 12.0 - i * 0.4)) }
        store.watchlist(deviceId).forEach { add(it, 6.0) }
        store.pinned(deviceId).forEach { add(it, 8.0) }

        return weights.entries.sortedByDescending { it.value }.take(4).map { it.key }
    }

    private fun interleave(a: List<Title>, b: List<Title>): List<Title> {
        val out = mutableListOf<Title>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { out += it }
            b.getOrNull(i)?.let { out += it }
        }
        return out
    }
}
