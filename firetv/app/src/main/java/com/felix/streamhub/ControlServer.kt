package com.felix.streamhub

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.felix.streamhub.data.*
import com.felix.streamhub.picker.ProfilePickerService
import com.felix.streamhub.picker.ProfilePickers
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Everything the phone talks to.
 *
 * The TV serves the remote page itself and answers its questions, so your phone
 * only ever speaks to the TV. No computer in the middle, nothing on your phone
 * to install - it is a web address on your own wifi.
 *
 * Pairing: the TV mints its own code (shown on the TV screen) and never accepts
 * one offered by a caller. The phone sends it once and gets a long-lived token
 * back, which it keeps in browser storage.
 */
class ControlServer(
    private val context: Context,
    private val store: Store,
    port: Int = ControlService.PORT,
    host: String? = null
) : NanoHTTPD(host, port) {

    private val main = Handler(Looper.getMainLooper())
    private val tmdb = Tmdb(store)
    private val omdb = Omdb(store)
    private val recommender = Recommender(tmdb, store)

    // Sessions and the pairing lockout live in the store, not in memory. Fire TV
    // kills backgrounded apps routinely - an in-memory set meant re-pairing by
    // hand every time - and keeping the lockout there lets "New pairing code" on
    // the TV actually clear it.

    // Bounds concurrent work. NanoHTTPD spawns a thread per connection, so
    // without this a few thousand idle sockets can exhaust the TV's memory and
    // kill the accept loop for good.
    private val inflight = java.util.concurrent.Semaphore(24)

    override fun serve(session: IHTTPSession): Response {
        if (!inflight.tryAcquire()) {
            return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Busy, try again."))
        }
        return try {
            runCatching { route(session) }.getOrElse { e ->
                json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "error"))
            }
        } finally {
            inflight.release()
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = if (session.uri == "/" || session.uri.isBlank()) "/index.html" else session.uri

        // --- the phone's own page, served from the app's assets --------------
        ASSETS[uri]?.let { mime -> return asset("remote$uri", mime) }

        if (uri == "/ping") {
            return json(Response.Status.OK, JSONObject().put("app", "StreamHubTV").put("version", 3))
        }

        if (!uri.startsWith("/api/")) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }

        // Refuse anything oversized before parsing it.
        val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (declared > ControlService.MAX_BODY_BYTES) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Body too large."))
        }

        if (uri == "/api/pair") return doPair(session)

        // Everything below needs a paired phone. Header only - accepting the
        // token from the body would make these reachable from any web page.
        val presented = session.headers["x-streamhub-token"] ?: ""
        // Which phone is asking. Every list read or written below belongs to it.
        val deviceId = store.deviceFor(presented)
            ?: return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Not paired."))

        val params = session.parameters
        fun q(name: String): String? = params[name]?.firstOrNull()

        return when {
            uri == "/api/state" -> json(Response.Status.OK, stateJson(deviceId))
            uri == "/api/home" -> doHome(deviceId)
            uri == "/api/search" -> doSearch(q("q"))
            uri == "/api/details" -> doDetails(q("mediaType"), q("id"))
            uri == "/api/watchlist" -> doToggle(session, deviceId, pinned = false)
            uri == "/api/pinned" -> doToggle(session, deviceId, pinned = true)
            uri == "/api/play" -> doPlay(session, deviceId)
            uri == "/api/profiles" -> doProfiles(session, deviceId)
            uri == "/api/settings" -> doSettings(session)
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
    }

    // ---- pairing -----------------------------------------------------------

    // Synchronized so the attempt counter cannot be raced, the lockout is
    // visible to every thread, and the 400ms penalty is a real global rate
    // limit rather than one sleeping thread per attacker connection.
    @Synchronized
    private fun doPair(session: IHTTPSession): Response {
        // A plain <img src="...api/pair"> from any web page could otherwise
        // burn attempts and lock the owner out of pairing.
        if (session.method != Method.POST) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "POST only."))
        }
        val body = readBody(session)
        val now = System.currentTimeMillis()

        if (store.lockedUntil > now) {
            val secs = (store.lockedUntil - now) / 1000
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Too many wrong codes. Wait ${secs}s, or press New pairing code on the TV."))
        }

        // Checked before the code, so a malformed request cannot burn attempts.
        // The phone keeps this id across re-pairings; it is never sent back.
        val deviceId = body.optString("deviceId", "")
        if (!Store.isValidDeviceId(deviceId)) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Reload the page and try again."))
        }

        val given = body.optString("code", "").trim().uppercase()
        val expected = store.ensureControlToken()

        if (given.length != expected.length || !constantTimeEquals(given, expected)) {
            store.failedPairs += 1
            if (store.failedPairs >= 10) {
                store.lockedUntil = now + 5 * 60 * 1000
                store.failedPairs = 0
            }
            Thread.sleep(400) // a deliberate cost per guess
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Wrong code."))
        }

        store.failedPairs = 0
        store.lockedUntil = 0L
        val token = java.math.BigInteger(1, java.security.SecureRandom().generateSeed(32)).toString(16)
        store.addSession(token, deviceId)
        return json(Response.Status.OK, JSONObject().put("token", token))
    }

    /** Rotating the code on the TV throws every paired phone off. */
    @Synchronized
    fun revokeAll() {
        store.clearSessions()
        store.failedPairs = 0
        store.lockedUntil = 0L
    }

    // ---- data --------------------------------------------------------------

    private fun stateJson(deviceId: String): JSONObject {
        val services = JSONArray()
        for (s in Services.ALL) {
            services.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("short", s.short)
                    .put("color", String.format("#%06X", 0xFFFFFF and s.color))
                    .put("accent", String.format("#%06X", 0xFFFFFF and s.accent))
                    .put("installed", AppLauncher.isInstalled(context, s.id))
            )
        }
        val tvServices = JSONObject()
        for (s in Services.ALL) tvServices.put(s.id, AppLauncher.isInstalled(context, s.id))

        return JSONObject()
            .put("services", services)
            .put("state", store.snapshotJson(deviceId))
            .put(
                "tv",
                JSONObject()
                    .put("host", "this-tv")
                    .put("tvApp", true)
                    .put("adbConnected", false)
                    .put("hasToken", true)
                    .put("services", tvServices)
            )
    }

    private fun doHome(deviceId: String): Response {
        if (!tmdb.hasKey()) return json(Response.Status.OK, JSONObject().put("rows", JSONArray()).put("needsKey", true))
        val rows = runCatching { runBlocking { recommender.buildHome(deviceId) } }.getOrDefault(emptyList())
        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("subtitle", r.subtitle)
                    .put("items", titlesJson(r.items))
            )
        }
        return json(Response.Status.OK, JSONObject().put("rows", arr))
    }

    private fun doSearch(query: String?): Response {
        if (query.isNullOrBlank()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Empty query."))
        if (!tmdb.hasKey()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Add your TMDB key in Settings first."))
        val items = runCatching { runBlocking { tmdb.search(query) } }
            .getOrElse { return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", it.message ?: "Search failed")) }

        // Search results say nothing about where a title streams, so each is
        // looked up (in parallel; cached for 30 minutes) and anything none of
        // the four carry is dropped.
        val checked = runBlocking {
            runCatching { tmdb.providerIds() } // once, not once per parallel lookup
            coroutineScope {
                items.take(SEARCH_CHECK_LIMIT).map { t ->
                    async { t to runCatching { tmdb.availability(t.mediaType, t.id) }.getOrNull() }
                }.awaitAll()
            }
        }
        if (checked.isNotEmpty() && checked.all { it.second == null }) {
            return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Couldn’t check where these stream. Try again."))
        }
        val results = JSONArray()
        for ((t, avail) in checked) {
            if (avail.isNullOrEmpty()) continue
            results.put(t.toJson().put("availableOn", availabilityJson(avail)))
        }
        return json(Response.Status.OK, JSONObject().put("results", results))
    }

    private fun availabilityJson(list: List<Availability>): JSONArray {
        val arr = JSONArray()
        for (a in list) arr.put(JSONObject().put("serviceId", a.serviceId).put("kind", if (a.included) "included" else "rent"))
        return arr
    }

    private fun doDetails(mediaType: String?, id: String?): Response {
        if (mediaType != "movie" && mediaType != "tv") return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Unknown media type."))
        val numeric = id?.toIntOrNull() ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid id."))

        val d = runCatching {
            runBlocking {
                val base = tmdb.details(mediaType, numeric)
                base.copy(ratings = runCatching { omdb.ratings(base.imdbId) }.getOrNull())
            }
        }.getOrElse { return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", it.message ?: "Could not load that title.")) }

        val avail = availabilityJson(d.availableOn)

        val cast = JSONArray()
        for (c in d.cast) {
            cast.put(JSONObject().put("name", c.name).put("character", c.character).put("profile", c.profile))
        }

        val ratings = d.ratings?.let {
            JSONObject()
                .put("rottenTomatoes", it.rottenTomatoes ?: JSONObject.NULL)
                .put("imdb", it.imdb ?: JSONObject.NULL)
                .put("metacritic", it.metacritic ?: JSONObject.NULL)
                .put("rated", it.rated ?: JSONObject.NULL)
        } ?: JSONObject.NULL

        val out = d.title.toJson()
            .put("runtime", d.runtime ?: JSONObject.NULL)
            .put("seasons", d.seasons ?: JSONObject.NULL)
            .put("episodes", d.episodes ?: JSONObject.NULL)
            .put("genres", JSONArray(d.genres))
            .put("directors", JSONArray(d.directors))
            .put("cast", cast)
            .put("imdbId", d.imdbId ?: JSONObject.NULL)
            .put("availableOn", avail)
            .put("recommendations", titlesJson(d.recommendations))
            .put("ratings", ratings)

        return json(Response.Status.OK, out)
    }

    private fun doToggle(session: IHTTPSession, deviceId: String, pinned: Boolean): Response {
        val item = readBody(session).optJSONObject("item")
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "No item."))
        // fromJson never throws - it happily builds an empty Title - so the
        // shape has to be checked here or blank entries get saved forever.
        val t = Title.fromJson(item)
        if (t.id <= 0 || (t.mediaType != "movie" && t.mediaType != "tv") || t.title.isBlank()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Bad item."))
        }
        if (pinned) store.togglePinned(deviceId, t) else store.toggleWatchlist(deviceId, t)
        return json(Response.Status.OK, store.snapshotJson(deviceId))
    }

    /** The whole point: open the real app on this TV, at the chosen title. */
    private fun doPlay(session: IHTTPSession, deviceId: String): Response {
        val body = readBody(session)
        val serviceId = body.optString("serviceId")
        val item = body.optJSONObject("item")
        val titleText = item?.optString("title")?.ifBlank { null } ?: body.optString("title").ifBlank { null }
        val contentId = body.optString("contentId").ifBlank { null }

        // There used to be a "find this anywhere" route with no service. It
        // relied on Fire TV's universal search, which apps cannot open; on a
        // real TV it landed in the Silk browser instead.
        val svc = Services.byId(serviceId)
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Pick one of the services."))

        AppLauncher.wakeScreen(context)

        // Armed before launching, so neither screen can appear before we look.
        // The title is only typed where the search link leaves the box empty.
        val typed = titleText?.takeIf { svc.search?.typeQuery == true }
        ProfilePickerService.arm(serviceId, store.profileNames(deviceId)[serviceId], typed)

        // Starting another app has to happen on the main thread.
        var result: AppLauncher.Result? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            result = AppLauncher.launch(context, serviceId, titleText, contentId)
            latch.countDown()
        }
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)

        item?.let { runCatching { store.recordOpen(deviceId, Title.fromJson(it), serviceId) } }

        return when (val r = result) {
            is AppLauncher.Result.Launched -> {
                // Only claim the title will be typed if something can type it.
                val kind = if (r.kind == "search-page" && typed != null && ProfilePickerService.isRunning) "search-typing" else r.kind
                json(Response.Status.OK, JSONObject().put("ok", true).put("kind", kind).put("service", svc.name).put("state", store.snapshotJson(deviceId)))
            }
            is AppLauncher.Result.Failed ->
                json(Response.Status.OK, JSONObject().put("ok", false).put("error", r.reason))
            null ->
                json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Timed out starting ${svc.name}."))
        }
    }

    /**
     * Which profile is this phone's on each service that can be auto-picked.
     * Only services with a recognizer are offered; the others cannot work.
     */
    private fun doProfiles(session: IHTTPSession, deviceId: String): Response {
        if (session.method == Method.POST) {
            val given = readBody(session).optJSONObject("profiles") ?: JSONObject()
            val names = ProfilePickers.ALL.associate { p ->
                p.serviceId to given.optString(p.serviceId, "").trim().take(60)
            }.filterValues { it.isNotEmpty() }
            store.setProfileNames(deviceId, names)
        }
        val names = store.profileNames(deviceId)
        val services = JSONArray()
        for (p in ProfilePickers.ALL) {
            services.put(
                JSONObject()
                    .put("id", p.serviceId)
                    .put("name", Services.byId(p.serviceId)?.name ?: p.serviceId)
                    .put("profile", names[p.serviceId] ?: "")
            )
        }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("asked", store.profilesAsked(deviceId))
                // Honest about whether it can work yet: the TV-side switch is adb-only.
                .put("enabled", ProfilePickerService.isRunning)
                .put("services", services)
        )
    }

    /** So the API key is typed on a phone keyboard, never with a TV remote. */
    private fun doSettings(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            return json(
                Response.Status.OK,
                JSONObject()
                    .put("region", store.region)
                    .put("hasTmdbKey", store.tmdbKey.isNotBlank())
                    .put("hasOmdbKey", store.omdbKey.isNotBlank())
            )
        }
        val body = readBody(session)
        body.optStringOrNull("tmdbKey")?.let { store.tmdbKey = it }
        body.optStringOrNull("omdbKey")?.let { store.omdbKey = it }
        body.optStringOrNull("region")?.let { store.region = it }
        Http.clearCache()
        tmdb.invalidateProviders()
        return json(
            Response.Status.OK,
            JSONObject().put("ok", true).put("region", store.region)
                .put("hasTmdbKey", store.tmdbKey.isNotBlank())
                .put("hasOmdbKey", store.omdbKey.isNotBlank())
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun titlesJson(list: List<Title>): JSONArray {
        val arr = JSONArray()
        for (t in list) arr.put(t.toJson())
        return arr
    }

    private fun asset(path: String, mime: String): Response {
        return try {
            val bytes = context.assets.open(path).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            newFixedLengthResponse(Response.Status.OK, mime, java.io.ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
                addHeader("Cache-Control", "no-cache")
                addHeader("X-Content-Type-Options", "nosniff")
            }
        } catch (e: Exception) {
            json(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
        }
    }

    /**
     * Decoded as UTF-8 here, not by NanoHTTPD's parseBody: with no charset in
     * the content-type - which is exactly what the phone's fetch() sends -
     * parseBody decodes as ASCII, and on a real TV "Félix" arrived as
     * "F��lix". Every accented profile name or title was mangled.
     */
    private fun readBody(session: IHTTPSession): JSONObject {
        val len = session.headers["content-length"]?.toIntOrNull() ?: return JSONObject()
        if (len <= 0 || len > ControlService.MAX_BODY_BYTES) return JSONObject()
        val bytes = ByteArray(len)
        runCatching { java.io.DataInputStream(session.inputStream).readFully(bytes) }
            .onFailure { return JSONObject() }
        return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    private fun json(status: Response.Status, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", obj.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private companion object {
        /** One availability lookup per result; TMDB's first page is plenty. */
        const val SEARCH_CHECK_LIMIT = 20

        val ASSETS = mapOf(
            "/index.html" to "text/html; charset=utf-8",
            "/app.js" to "text/javascript; charset=utf-8",
            "/styles.css" to "text/css; charset=utf-8",
            "/manifest.webmanifest" to "application/manifest+json",
            "/sw.js" to "text/javascript; charset=utf-8",
            "/icon.svg" to "image/svg+xml"
        )
    }
}
