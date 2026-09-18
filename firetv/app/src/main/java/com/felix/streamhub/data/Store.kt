package com.felix.streamhub.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings, and each paired phone's watchlist, pinned titles and open history.
 * SharedPreferences is plenty for this much data and survives updates without
 * a migration story.
 */
class Store(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences("streamhub", Context.MODE_PRIVATE))

    // ---- settings ----------------------------------------------------------

    var tmdbKey: String
        get() = prefs.getString(K_TMDB, "") ?: ""
        set(v) = prefs.edit().putString(K_TMDB, v.trim()).apply()

    var omdbKey: String
        get() = prefs.getString(K_OMDB, "") ?: ""
        set(v) = prefs.edit().putString(K_OMDB, v.trim()).apply()

    var region: String
        get() = prefs.getString(K_REGION, "BE") ?: "BE"
        set(v) = prefs.edit().putString(K_REGION, v.trim().uppercase()).apply()

    var language: String
        get() = prefs.getString(K_LANG, "en-US") ?: "en-US"
        set(v) = prefs.edit().putString(K_LANG, v).apply()

    /** Shared secret with your phone, so only paired devices can drive the TV. */
    var controlToken: String
        get() = prefs.getString(K_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_TOKEN, v).apply()

    /**
     * Mint the pairing token on the TV itself. Generated here, shown on the
     * Setup screen, and typed into the PC once - so no caller can propose one.
     * Six characters from an unambiguous alphabet - no 0/O/1/I - because it is
     * read off a television and typed with thumbs. That is about a billion
     * combinations, and guessing is rate-limited and locked out anyway.
     */
    fun ensureControlToken(): String {
        val existing = controlToken
        if (existing.isNotBlank()) return existing
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = java.security.SecureRandom()
        val fresh = (1..6).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        controlToken = fresh
        return fresh
    }

    /** Throw the current token away, kick off every paired phone, mint a new code. */
    fun rotateControlToken(): String {
        clearSessions()
        failedPairs = 0
        lockedUntil = 0L
        controlToken = ""
        return ensureControlToken()
    }

    // ---- paired phones -----------------------------------------------------
    //
    // Persisted, because Fire TV kills backgrounded apps as a matter of course
    // and an in-memory set meant re-pairing by hand every time that happened.

    /** Wrong-code attempts and any lockout, kept here so rotating the code clears them. */
    var failedPairs: Int
        get() = prefs.getInt(K_FAILED, 0)
        set(v) = prefs.edit().putInt(K_FAILED, v).apply()

    var lockedUntil: Long
        get() = prefs.getLong(K_LOCKED, 0L)
        set(v) = prefs.edit().putLong(K_LOCKED, v).apply()

    // A session is token -> device id. The token is the credential and changes
    // on every pairing; the device id is who the lists belong to and does not.
    // Keying lists on the token would hand a re-paired phone an empty list.

    fun addSession(token: String, deviceId: String) {
        val next = sessions()
        next.put(token, deviceId)
        // A handful of phones is realistic; the cap stops an attacker growing
        // this without bound.
        while (next.length() > 20) next.remove(next.keys().next())
        prefs.edit().putString(K_SESSIONS, next.toString()).apply()
    }

    fun deviceFor(token: String): String? =
        if (token.isEmpty()) null else sessions().optStringOrNull(token)

    fun clearSessions() = prefs.edit().remove(K_SESSIONS).apply()

    fun sessionCount(): Int = sessions().length()

    private fun sessions(): JSONObject =
        runCatching { JSONObject(prefs.getString(K_SESSIONS, null) ?: "{}") }.getOrDefault(JSONObject())

    var controlEnabled: Boolean
        get() = prefs.getBoolean(K_CONTROL, true)
        set(v) = prefs.edit().putBoolean(K_CONTROL, v).apply()

    // ---- lists, one set per paired phone -----------------------------------
    //
    // Each phone's lists live under their own keys, so two phones saving at the
    // same moment never read-modify-write the same value. deviceId is checked
    // by isValidDeviceId before it gets anywhere near a key name.

    fun watchlist(deviceId: String): List<Title> = readList(key(deviceId, K_WATCHLIST))
    fun pinned(deviceId: String): List<Title> = readList(key(deviceId, K_PINNED))
    fun history(deviceId: String): List<Title> = readList(key(deviceId, K_HISTORY))

    /** @return true if the title is now in the list. */
    fun toggleWatchlist(deviceId: String, t: Title): Boolean = toggle(key(deviceId, K_WATCHLIST), t)

    fun togglePinned(deviceId: String, t: Title): Boolean = toggle(key(deviceId, K_PINNED), t, cap = 24)

    fun recordOpen(deviceId: String, t: Title, serviceId: String?) {
        val list = history(deviceId).filter { it.key != t.key }.toMutableList()
        list.add(0, t)
        writeList(key(deviceId, K_HISTORY), list.take(120))
        serviceId?.let {
            val counts = prefs.getInt("opens_$it", 0)
            prefs.edit().putInt("opens_$it", counts + 1).apply()
        }
        // Opening something you pinned should float it back to the top.
        val p = pinned(deviceId).toMutableList()
        val idx = p.indexOfFirst { it.key == t.key }
        if (idx > 0) {
            val entry = p.removeAt(idx)
            p.add(0, entry)
            writeList(key(deviceId, K_PINNED), p)
        }
    }

    private fun key(deviceId: String, list: String): String {
        require(isValidDeviceId(deviceId)) { "bad device id" }
        return "dev.$deviceId.$list"
    }

    // ---- plumbing ----------------------------------------------------------

    private fun toggle(key: String, t: Title, cap: Int = 500): Boolean {
        val list = readList(key).toMutableList()
        val idx = list.indexOfFirst { it.key == t.key }
        return if (idx >= 0) {
            list.removeAt(idx)
            writeList(key, list)
            false
        } else {
            list.add(0, t)
            writeList(key, list.take(cap))
            true
        }
    }

    private fun readList(key: String): List<Title> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Title::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun writeList(key: String, list: List<Title>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    /**
     * What this phone needs to render its own lists. Never includes the API
     * keys, the pairing token or the device id.
     */
    fun snapshotJson(deviceId: String): JSONObject = JSONObject().apply {
        put("region", region)
        put("hasTmdbKey", tmdbKey.isNotBlank())
        put("hasOmdbKey", omdbKey.isNotBlank())
        put("watchlist", JSONArray().also { a -> watchlist(deviceId).forEach { a.put(it.toJson()) } })
        put("pinned", JSONArray().also { a -> pinned(deviceId).forEach { a.put(it.toJson()) } })
    }

    companion object {
        /**
         * The phone generates its id; this is the only gate on it. It becomes
         * part of a preferences key, so the shape is strict.
         */
        fun isValidDeviceId(id: String): Boolean = DEVICE_ID.matches(id)

        private val DEVICE_ID = Regex("^[a-f0-9]{32}$")

        private const val K_TMDB = "tmdbKey"
        private const val K_OMDB = "omdbKey"
        private const val K_REGION = "region"
        private const val K_LANG = "language"
        private const val K_TOKEN = "controlToken"
        private const val K_CONTROL = "controlEnabled"
        private const val K_WATCHLIST = "watchlist"
        private const val K_PINNED = "pinned"
        private const val K_HISTORY = "history"
        private const val K_SESSIONS = "sessions"
        private const val K_FAILED = "failedPairs"
        private const val K_LOCKED = "lockedUntil"
    }
}
