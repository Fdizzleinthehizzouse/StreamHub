package com.felix.streamhub.data

/**
 * The genres the phone offers to browse by. TMDB numbers genres separately
 * for films and series (Action is 28 for films, "Action & Adventure" 10759 for
 * series), so each entry maps to both; null means that side has no match.
 */
data class Genre(val id: String, val name: String, val movie: List<Int>?, val tv: List<Int>?)

object Genres {
    val ALL = listOf(
        Genre("action", "Action", listOf(28), listOf(10759)),
        Genre("comedy", "Comedy", listOf(35), listOf(35)),
        Genre("drama", "Drama", listOf(18), listOf(18)),
        Genre("scifi", "Sci-fi & fantasy", listOf(878, 14), listOf(10765)),
        Genre("crime", "Crime", listOf(80), listOf(80)),
        Genre("thriller", "Thriller", listOf(53), null),
        Genre("mystery", "Mystery", listOf(9648), listOf(9648)),
        Genre("horror", "Horror", listOf(27), null),
        Genre("romance", "Romance", listOf(10749), null),
        Genre("animation", "Animation", listOf(16), listOf(16)),
        Genre("family", "Family", listOf(10751), listOf(10751)),
        Genre("kids", "Kids", null, listOf(10762)),
        Genre("documentary", "Documentary", listOf(99), listOf(99))
    )

    fun byId(id: String?): Genre? = ALL.firstOrNull { it.id == id }
}
