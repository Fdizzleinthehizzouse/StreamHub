package com.felix.streamhub

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.felix.streamhub.data.Store
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** SharedPreferences held in a map, so Store runs off-device unchanged. */
private class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = map[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
