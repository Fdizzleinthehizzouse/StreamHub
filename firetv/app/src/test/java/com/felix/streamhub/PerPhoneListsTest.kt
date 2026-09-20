package com.felix.streamhub

import android.content.ContextWrapper
import com.felix.streamhub.data.Store
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Drives the real ControlServer over HTTP, as two phones would. */
class PerPhoneListsTest {

    private lateinit var store: Store
    private lateinit var server: ControlServer
    private lateinit var base: String

    @Before
    fun start() {
        store = Store(MemoryPrefs())
        store.tmdbKey = "SECRET-TMDB"
        store.omdbKey = "SECRET-OMDB"
        // Loopback only: listening on every interface makes Windows ask for a
        // firewall exception on each fresh JDK.
        server = ControlServer(ContextWrapper(null), store, port = 0, host = "127.0.0.1")
        server.start()
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun stop() = server.stop()

    @Test
    fun twoPhonesEachSeeOnlyTheirOwnWatchlist() {
        val phoneA = pair(DEVICE_A)
        val phoneB = pair(DEVICE_B)

        add(phoneA, 1396, "tv", "Breaking Bad")
        add(phoneB, 136315, "movie", "Dune")

        assertEquals(listOf("tv:1396"), watchlistKeys(phoneA))
        assertEquals(listOf("movie:136315"), watchlistKeys(phoneB))
    }

    @Test
    fun rePairingTheSamePhoneKeepsItsList() {
        val first = pair(DEVICE_A)
        add(first, 1396, "tv", "Breaking Bad")

        val again = pair(DEVICE_A)

        assertFalse("pairing again should mint a fresh token", first == again)
        assertEquals(listOf("tv:1396"), watchlistKeys(again))
    }

    @Test
    fun noResponseCarriesATokenKeyOrDeviceId() {
        val token = pair(DEVICE_A)
        val bodies = listOf(
            call("POST", "/api/watchlist", token, """{"item":{"id":5,"mediaType":"movie","title":"X"}}""").second,
            call("GET", "/api/state", token).second
        )
        for (body in bodies) {
            for (secret in listOf(token, DEVICE_A, "SECRET-TMDB", "SECRET-OMDB", store.controlToken)) {
                assertFalse("leaked $secret in $body", body.contains(secret))
            }
        }
    }

    @Test
    fun profileNamesBelongToOnePhone() {
        val phoneA = pair(DEVICE_A)
        val phoneB = pair(DEVICE_B)

        // Netflix is kept as a place in its list, never a name: its profile
        // screen can't be read, so a name would be worthless.
        val (status, _) = call(
            "POST", "/api/profiles", phoneA,
            """{"profiles":{"disneyplus":"Alice","netflix":"Alice","primevideo":""}}"""
        )
        assertEquals(200, status)

        val a = JSONObject(call("GET", "/api/profiles", phoneA).second)
        val b = JSONObject(call("GET", "/api/profiles", phoneB).second)
        assertEquals("Alice", profileFor(a, "disneyplus"))
        assertEquals(true, a.getBoolean("asked"))
        assertEquals("", profileFor(b, "disneyplus"))
        assertEquals(false, b.getBoolean("asked"))
        assertEquals("", profileFor(a, "netflix"))

        assertEquals(200, call("POST", "/api/profiles", phoneA, """{"profiles":{"netflix":"4"}}""").first)
        val a2 = JSONObject(call("GET", "/api/profiles", phoneA).second)
        assertEquals("4", profileFor(a2, "netflix"))
        assertEquals("", profileFor(b, "netflix"))
    }

    /** The phone's fetch() sends `application/json` with no charset. */
    @Test
    fun accentsFromThePhoneSurviveTheTrip() {
        val token = pair(DEVICE_A)
        call("POST", "/api/profiles", token, """{"profiles":{"disneyplus":"Félix"}}""")
        add(token, 194, "movie", "Amélie")

        assertEquals("Félix", profileFor(JSONObject(call("GET", "/api/profiles", token).second), "disneyplus"))
        val saved = JSONObject(call("GET", "/api/state", token).second)
            .getJSONObject("state").getJSONArray("watchlist").getJSONObject(0).getString("title")
        assertEquals("Amélie", saved)
    }

    private fun profileFor(o: JSONObject, serviceId: String): String {
        val arr = o.getJSONArray("services")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("id") == serviceId }.getString("profile")
    }

    /**
     * Browsing takes what the phone sends straight into a TMDB query, so the
     * server has to be the one deciding what is allowed.
     */
    @Test
    fun browsingOnlyAcceptsGenresAndServicesTheTvKnows() {
        val token = pair(DEVICE_A)
        assertEquals(400, call("GET", "/api/browse?genre=notagenre", token).first)
        assertEquals(400, call("GET", "/api/browse?service=itunes", token).first)
        // A query it does know answers as a page, and says which page it is,
        // so the phone can ask for the next one. (Offline here, so it holds
        // nothing: what matters is the shape and that the page is echoed.)
        val (status, body) = call("GET", "/api/browse?genre=action&sort=new&kind=movie&page=3", token)
        assertEquals(body, 200, status)
        val page = JSONObject(body)
        assertEquals(3, page.getInt("page"))
        assertTrue(body, page.has("results") && page.has("hasMore"))
        assertEquals(401, call("GET", "/api/browse", null).first)
    }

    @Test
    fun pairingWithoutADeviceIdIsRefused() {
        val code = store.ensureControlToken()
        assertEquals(400, call("POST", "/api/pair", null, """{"code":"$code"}""").first)
    }

    // ---- helpers ---------------------------------------------------------

    private fun pair(deviceId: String): String {
        val code = store.ensureControlToken()
        val (status, body) = call("POST", "/api/pair", null, """{"code":"$code","deviceId":"$deviceId"}""")
        assertEquals(body, 200, status)
        return JSONObject(body).getString("token")
    }

    private fun add(token: String, id: Int, type: String, title: String) {
        val (status, body) = call(
            "POST", "/api/watchlist", token,
            """{"item":{"id":$id,"mediaType":"$type","title":"$title"}}"""
        )
        assertEquals(body, 200, status)
    }

    private fun watchlistKeys(token: String): List<String> {
        val (status, body) = call("GET", "/api/state", token)
        assertEquals(body, 200, status)
        val list = JSONObject(body).getJSONObject("state").getJSONArray("watchlist")
        return (0 until list.length()).map { list.getJSONObject(it).getString("key") }
    }

    private fun call(method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("content-type", "application/json")
        token?.let { conn.setRequestProperty("x-streamhub-token", it) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = conn.responseCode
        val stream = if (status < 400) conn.inputStream else conn.errorStream
        return status to (stream?.bufferedReader()?.readText() ?: "")
    }

    private companion object {
        const val DEVICE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"
        const val DEVICE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2"
    }
}
