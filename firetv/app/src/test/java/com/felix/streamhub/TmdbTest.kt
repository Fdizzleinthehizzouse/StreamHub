package com.felix.streamhub

import com.felix.streamhub.data.Availability
import com.felix.streamhub.data.Tmdb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TmdbTest {

    /** The desktop app's "genres are OR-joined" fix, which the TV app never had. */
    @Test
    fun genresAreOrJoinedSoPicksForYouIsNotAnEmptyIntersection() {
        val p = Tmdb.discoverParams("BE", listOf(8, 337), listOf(18, 80, 878), "popularity.desc", emptyMap())
        assertEquals("18|80|878", p["with_genres"])
        assertFalse(p["with_genres"]!!.contains(','))
        assertEquals("8|337", p["with_watch_providers"])
    }

    @Test
    fun providerRowsAreSubscriptionOnlySoRentalsStayOut() {
        val p = Tmdb.discoverParams("BE", listOf(8), null, "popularity.desc", emptyMap())
        assertEquals("flatrate|free|ads", p["with_watch_monetization_types"])
    }

    @Test
    fun includedBeatsRentAndOtherServicesAreIgnored() {
        val ids = mapOf("netflix" to setOf(8), "primevideo" to setOf(9, 119), "disneyplus" to setOf(337))
        val region = JSONObject("""{
            "flatrate": [{"provider_id": 8}, {"provider_id": 350}],
            "rent": [{"provider_id": 9}, {"provider_id": 8}]
        }""")
        assertEquals(
            listOf(Availability("netflix", included = true), Availability("primevideo", included = false)),
            Tmdb.availabilityFrom(region, ids)
        )
    }

    @Test
    fun aTitleOnNoneOfTheFourHasNoAvailability() {
        val ids = mapOf("netflix" to setOf(8))
        assertEquals(emptyList<Availability>(), Tmdb.availabilityFrom(JSONObject("""{"flatrate":[{"provider_id":350}]}"""), ids))
        assertEquals(emptyList<Availability>(), Tmdb.availabilityFrom(null, ids))
    }
}
