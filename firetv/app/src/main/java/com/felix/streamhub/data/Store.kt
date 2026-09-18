package com.felix.streamhub.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings, watchlist, pinned titles and open history. SharedPreferences is
 * plenty for this much data and survives updates without a migration story.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("streamhub", Context.MODE_PRIVATE)

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

    fun addSession(token: String) {
        val next = sessions().toMutableSet()
        next.add(token)
        // A handful of phones is realistic; the cap stops an attacker growing
        // this without bound.
        prefs.edit().putStringSet(K_SESSIONS, next.take(20).toSet()).apply()
    }

    fun hasSession(token: String): Boolean = sessions().contains(token)

    fun clearSessions() = prefs.edit().remove(K_SESSIONS).apply()

    fun sessionCount(): Int = sessions().size

    private fun sessions(): Set<String> = prefs.getStringSet(K_SESSIONS, emptySet()) ?: emptySet()

    var controlEnabled: Boolean
        get() = prefs.getBoolean(K_CONTROL, true)
        set(v) = prefs.edit().putBoolean(K_CONTROL, v).apply()

    // ---- lists -------------------------------------------------------------

    val watchlist: List<Title> get() = readList(K_WATCHLIST)
    val pinned: List<Title> get() = readList(K_PINNED)
    val history: List<Title> get() = readList(K_HISTORY)

    fun isInWatchlist(t: Title) = watchlist.any { it.key == t.key }
    fun isPinned(t: Title) = pinned.any { it.key == t.key }

    /** @return true if the title is now in the list. */
    fun toggleWatchlist(t: Title): Boolean = toggle(K_WATCHLIST, t)

    fun togglePinned(t: Title): Boolean = toggle(K_PINNED, t, cap = 24)

    fun recordOpen(t: Title, serviceId: String?) {
        val list = history.filter { it.key != t.key }.toMutableList()
        list.add(0, t)
        writeList(K_HISTORY, list.take(120))
        serviceId?.let {
            val counts = prefs.getInt("opens_$it", 0)
            prefs.edit().putInt("opens_$it", counts + 1).apply()
        }
        // Opening something you pinned should float it back to the top.
        val p = pinned.toMutableList()
        val idx = p.indexOfFirst { it.key == t.key }
        if (idx > 0) {
            val entry = p.removeAt(idx)
            p.add(0, entry)
            writeList(K_PINNED, p)
        }
    }

    fun clearHistory() = writeList(K_HISTORY, emptyList())

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

    /** What the phone needs to render its lists. Never includes the API keys. */
    fun snapshotJson(): JSONObject = JSONObject().apply {
        put("region", region)
        put("hasTmdbKey", tmdbKey.isNotBlank())
        put("hasOmdbKey", omdbKey.isNotBlank())
        put("watchlist", JSONArray().also { a -> watchlist.forEach { a.put(it.toJson()) } })
        put("pinned", JSONArray().also { a -> pinned.forEach { a.put(it.toJson()) } })
    }

    private companion object {
        const val K_TMDB = "tmdbKey"
        const val K_OMDB = "omdbKey"
        const val K_REGION = "region"
        const val K_LANG = "language"
        const val K_TOKEN = "controlToken"
        const val K_CONTROL = "controlEnabled"
        const val K_WATCHLIST = "watchlist"
        const val K_PINNED = "pinned"
        const val K_HISTORY = "history"
        const val K_SESSIONS = "sessions"
        const val K_FAILED = "failedPairs"
        const val K_LOCKED = "lockedUntil"
    }
}
