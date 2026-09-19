package com.felix.streamhub

import com.felix.streamhub.data.Genres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenresTest {

    // TMDB's genre ids (GET /genre/movie/list and /genre/tv/list).
    private val movieIds = setOf(28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37)
    private val tvIds = setOf(10759, 16, 35, 80, 99, 18, 10751, 10762, 9648, 10763, 10764, 10765, 10766, 10767, 10768, 37)

    @Test
    fun everyGenreUsesRealTmdbIdsOnTheRightSide() {
        for (g in Genres.ALL) {
            g.movie?.forEach { assertTrue("${g.id}: $it is not a film genre", it in movieIds) }
            g.tv?.forEach { assertTrue("${g.id}: $it is not a series genre", it in tvIds) }
            assertTrue("${g.id} matches nothing", !g.movie.isNullOrEmpty() || !g.tv.isNullOrEmpty())
        }
    }

    @Test
    fun idsAreUnique() {
        assertEquals(Genres.ALL.size, Genres.ALL.map { it.id }.toSet().size)
    }
}
