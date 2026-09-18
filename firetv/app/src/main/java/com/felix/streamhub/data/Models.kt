package com.felix.streamhub.data

import org.json.JSONArray
import org.json.JSONObject

/** One film or series, in the shape the whole app passes around. */
data class Title(
    val id: Int,
    val mediaType: String,          // "movie" | "tv"
    val title: String,
    val year: Int?,
    val poster: String?,            // TMDB path, e.g. /abc.jpg
    val backdrop: String?,
    val overview: String,
    val score: Int?,                // 0..100
    val genreIds: List<Int> = emptyList()
) {
    val key: String get() = "$mediaType:$id"

    fun toJson(): JSONObject = JSONObject().apply {
        // The phone matches watchlist and pinned entries on `key`; without it
        // every "is this saved?" check silently answers no.
        put("key", key)
        put("id", id)
        put("mediaType", mediaType)
        put("title", title)
        year?.let { put("year", it) }
        poster?.let { put("poster", it) }
        backdrop?.let { put("backdrop", it) }
        put("overview", overview)
        score?.let { put("score", it) }
        put("genreIds", JSONArray(genreIds))
    }

    companion object {
        /** Bounded: this parses whatever a paired phone sends, and it gets stored. */
        fun fromJson(o: JSONObject): Title = Title(
            id = o.optInt("id"),
            mediaType = if (o.optString("mediaType") == "tv") "tv" else "movie",
            title = o.optString("title").take(200),
            year = o.optIntOrNull("year"),
            poster = o.optStringOrNull("poster")?.take(200),
            backdrop = o.optStringOrNull("backdrop")?.take(200),
            overview = o.optString("overview", "").take(2000),
            score = o.optIntOrNull("score"),
            genreIds = o.optJSONArray("genreIds").toIntList().take(16)
        )

        /** Normalise a raw TMDB result, which names things differently per media type. */
        fun fromTmdb(o: JSONObject, forcedType: String? = null): Title {
            val type = forcedType
                ?: o.optStringOrNull("media_type")
                ?: if (o.has("first_air_date") || o.has("name")) "tv" else "movie"
            val date = o.optStringOrNull("release_date") ?: o.optStringOrNull("first_air_date") ?: ""
            val vote = o.optDouble("vote_average", -1.0)
            return Title(
                id = o.optInt("id"),
                mediaType = type,
                title = o.optStringOrNull("title") ?: o.optStringOrNull("name") ?: "",
                year = date.take(4).toIntOrNull(),
                poster = o.optStringOrNull("poster_path"),
                backdrop = o.optStringOrNull("backdrop_path"),
                overview = o.optString("overview", ""),
                score = if (vote >= 0) Math.round(vote * 10).toInt() else null,
                genreIds = o.optJSONArray("genre_ids").toIntList()
            )
        }
    }
}

data class CastMember(val name: String, val character: String?, val profile: String?)

data class Ratings(
    val rottenTomatoes: Int? = null,
    val imdb: Double? = null,
    val metacritic: Int? = null,
    val rated: String? = null,
    val awards: String? = null
)

/** Which of our four services carries a title, and on what terms. */
data class Availability(val serviceId: String, val included: Boolean)

data class TitleDetail(
    val title: Title,
    val runtime: Int?,
    val seasons: Int?,
    val episodes: Int?,
    val genres: List<String>,
    val directors: List<String>,
    val cast: List<CastMember>,
    val imdbId: String?,
    val availableOn: List<Availability>,
    val recommendations: List<Title>,
    val ratings: Ratings?
)

data class Row(val id: String, val title: String, val subtitle: String?, val items: List<Title>)

// ---- small JSON helpers ----------------------------------------------------

fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return if (v.isEmpty() || v == "null") null else v
}

fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return (0 until length()).map { optInt(it) }
}

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
