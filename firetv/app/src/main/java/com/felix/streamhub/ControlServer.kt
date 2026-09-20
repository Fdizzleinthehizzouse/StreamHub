package com.felix.streamhub

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.felix.streamhub.data.*
import com.felix.streamhub.picker.BlindPlay
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
            uri == "/api/browse" -> doBrowse(mapOf("service" to q("service"), "genre" to q("genre"), "sort" to q("sort"), "kind" to q("kind"), "page" to q("page")))
            uri == "/api/details" -> doDetails(q("mediaType"), q("id"))
            uri == "/api/watchlist" -> doToggle(session, deviceId, pinned = false)
            uri == "/api/pinned" -> doToggle(session, deviceId, pinned = true)
            uri == "/api/play" -> doPlay(session, deviceId)
            uri == "/api/profiles" -> doProfiles(session, deviceId)
            uri == "/api/remote" -> doRemote(session)
            uri == "/api/autoplay" -> doAutoplayStatus(deviceId)
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

        val genres = JSONArray()
        for (g in Genres.ALL) genres.put(JSONObject().put("id", g.id).put("name", g.name))

        return JSONObject()
            .put("services", services)
            .put("genres", genres)
            .put("state", store.snapshotJson(deviceId))
            .put(
                "tv",
                JSONObject()
                    .put("host", "this-tv")
                    .put("tvApp", true)
                    .put("adbConnected", false)
                    .put("hasToken", true)
                    .put("services", tvServices)
                    // Whether the phone's remote pad can press keys right now.
                    .put("keys", TvKeys.helperRunning())
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

        val results = onTheFour(items)
            ?: return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Couldn’t check where these stream. Try again."))
        return json(Response.Status.OK, JSONObject().put("results", results))
    }

    /**
     * Browsing: one page of films and series from the four services, narrowed
     * to one service and/or genre, ordered as asked. Paged, because TMDB hands
     * out 20 at a time and a row of 20 was all the phone could ever show -
     * searching found titles that browsing never reached.
     */
    private fun doBrowse(params: Map<String, String?>): Response {
        if (!tmdb.hasKey()) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Add your TMDB key in Settings first."))
        val genreId = params["genre"]?.ifBlank { null }
        val genre = genreId?.let { Genres.byId(it) ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Unknown genre.")) }
        val serviceId = params["service"]?.ifBlank { null }
        if (serviceId != null && Services.byId(serviceId) == null) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Unknown service."))
        }
        val sort = params["sort"]?.takeIf { it in SORTS } ?: "popular"
        val kind = params["kind"]?.takeIf { it == "movie" || it == "tv" }
        val page = params["page"]?.toIntOrNull()?.coerceIn(1, Tmdb.MAX_PAGE) ?: 1

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val loaded = runCatching {
            runBlocking {
                val ids = tmdb.providerIds()
                val providers = if (serviceId != null) ids[serviceId].orEmpty().toList() else ids.values.flatten()
                if (providers.isEmpty()) return@runBlocking emptyList<Tmdb.Page>()
                coroutineScope {
                    listOf("movie", "tv")
                        .filter { kind == null || kind == it }
                        // A genre with no match on one side (Kids has no film
                        // genre, Horror no series one) simply has no such page.
                        .filter { genre == null || (if (it == "tv") genre.tv else genre.movie) != null }
                        .map { type ->
                            val genres = if (type == "tv") genre?.tv else genre?.movie
                            val (sortBy, extra) = Tmdb.sortParams(sort, type, today)
                            async { tmdb.discoverPage(type, providers, genres, sortBy, extra, page) }
                        }.awaitAll()
                }
            }
        }.getOrElse { return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", it.message ?: "Could not load that.")) }

        val items = interleaveAll(loaded.map { it.items })
        val results = onTheFour(items, limit = BROWSE_CHECK_LIMIT)
            ?: return json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "Couldn’t check where these stream. Try again."))
        return json(
            Response.Status.OK,
            JSONObject()
                .put("title", genre?.name ?: Services.byId(serviceId ?: "")?.name ?: "Everything")
                .put("results", results)
                .put("page", page)
                .put("hasMore", loaded.any { it.hasMore })
        )
    }

    /**
     * Each title tagged with which of the four carry it; titles none carry are
     * dropped. TMDB search and discover say nothing about this per title, so
     * each is looked up (in parallel; cached for 30 minutes).
     * @return null if no lookup succeeded at all, rather than an empty list
     *   that would read as "nothing is on your services".
     */
    private fun onTheFour(items: List<Title>, limit: Int = SEARCH_CHECK_LIMIT): JSONArray? {
        val checked = runBlocking {
            runCatching { tmdb.providerIds() } // once, not once per parallel lookup
            coroutineScope {
                items.distinctBy { it.key }.take(limit).map { t ->
                    async { t to runCatching { tmdb.availability(t.mediaType, t.id) }.getOrNull() }
                }.awaitAll()
            }
        }
        if (checked.isNotEmpty() && checked.all { it.second == null }) return null
        val results = JSONArray()
        for ((t, avail) in checked) {
            if (avail.isNullOrEmpty()) continue
            results.put(t.toJson().put("availableOn", availabilityJson(avail)))
        }
        return results
    }

    private fun interleave(a: List<Title>, b: List<Title>): List<Title> = interleaveAll(listOf(a, b))

    /** Films and series alternating, so neither kind fills the top of a page. */
    private fun interleaveAll(lists: List<List<Title>>): List<Title> {
        val longest = lists.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
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

        // Armed before launching, so no screen can appear before we look.
        // The title is only typed where the search link leaves the box empty,
        // and only driven to playback where the screens can be read and keys
        // can be pressed.
        val typed = titleText?.takeIf { svc.search?.typeQuery == true }
        // The year tells same-named titles apart ("Road House" 1989 / 2024).
        // Never autoplay what isn't included in the subscription: on a rental,
        // OK could land on Rent or Buy.
        // HBO Max's and Netflix's screens can't be read: BlindPlay drives them
        // by key presses, and checks afterwards as far as each service allows.
        val netflixPlace = ProfilePickers.Netflix.place(store.profileNames(deviceId)[ProfilePickers.Netflix.SERVICE_ID])
        val blind = BlindPlay.canAutoplay(serviceId, netflixPlace)
        val autoplay = titleText?.takeIf { blind || (ProfilePickerService.canAutoplay(serviceId) && TvKeys.helperRunning()) }
            ?.takeIf { item != null && includedOn(serviceId, Title.fromJson(item)) }
            ?.let { ProfilePickers.Wanted(it, item?.optIntOrNull("year"), isMovie = item?.optString("mediaType") == "movie") }
        val relaunch = { main.post { AppLauncher.launch(context, serviceId, titleText, contentId) }; Unit }
        val armed = if (blind && autoplay != null) {
            BlindPlay.start(deviceId, serviceId, autoplay, netflixPlace)
        } else {
            ProfilePickerService.arm(serviceId, deviceId, store.profileNames(deviceId)[serviceId], typed, autoplay, relaunch)
        }

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
                json(
                    Response.Status.OK,
                    JSONObject().put("ok", true).put("kind", kind).put("service", svc.name)
                        // The phone follows /api/autoplay; it never claims "playing" itself.
                        .put("autoplay", armed && autoplay != null)
                        .put("state", store.snapshotJson(deviceId))
                )
            }
            is AppLauncher.Result.Failed ->
                json(Response.Status.OK, JSONObject().put("ok", false).put("error", r.reason))
            null ->
                json(Response.Status.OK, JSONObject().put("ok", false).put("error", "Timed out starting ${svc.name}."))
        }
    }

    /** Whether [t] is included in the subscription on [serviceId] here (not rent/buy). Unknown counts as no. */
    private fun includedOn(serviceId: String, t: Title): Boolean =
        t.id > 0 && runCatching {
            runBlocking { tmdb.availability(t.mediaType, t.id) }.any { it.serviceId == serviceId && it.included }
        }.getOrDefault(false)

    /** Progress of this phone's latest title, as the TV helper sees it. Another phone's is not shown. */
    private fun doAutoplayStatus(deviceId: String): Response {
        val s = ProfilePickerService.status?.takeIf { it.deviceId == deviceId }
            ?: return json(Response.Status.OK, JSONObject().put("state", "none"))
        return json(
            Response.Status.OK,
            JSONObject().put("state", s.state.name.lowercase()).put("message", s.message).put("service", s.serviceId)
        )
    }

    /**
     * The phone's remote buttons. Real key presses via [TvKeys]; Back and Home
     * fall back to the accessibility service if ADB is unavailable. Answers
     * honestly when nothing was pressed.
     */
    private fun doRemote(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "POST only."))
        }
        val key = TvKeys.Key.of(readBody(session).optString("key"))
            ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Unknown button."))
        AppLauncher.wakeScreen(context)

        fun ok() = json(Response.Status.OK, JSONObject().put("ok", true))
        fun fail(why: String) = json(Response.Status.OK, JSONObject().put("ok", false).put("error", why))

        return when (val r = TvKeys.press(key)) {
            TvKeys.Result.Pressed -> ok()
            is TvKeys.Result.Unavailable -> {
                val fallback = if (key == TvKeys.Key.BACK || key == TvKeys.Key.HOME) ProfilePickerService.remote(key.name.lowercase()) else null
                if (fallback == true) ok() else fail(r.reason)
            }
        }
    }

    /**
     * Which profile is this phone's on each service that can be auto-picked.
     * Only services with a recognizer are offered; the others cannot work.
     */
    private fun doProfiles(session: IHTTPSession, deviceId: String): Response {
        val netflix = ProfilePickers.Netflix.SERVICE_ID
        if (session.method == Method.POST) {
            val given = readBody(session).optJSONObject("profiles") ?: JSONObject()
            val names = ProfilePickers.ALL.associate { p ->
                p.serviceId to given.optString(p.serviceId, "").trim().take(60)
            }.toMutableMap()
            // Netflix's profile names can't be read, so this phone's is kept as
            // its place in the list - a number, or nothing.
            names[netflix] = ProfilePickers.Netflix.place(given.optString(netflix, ""))?.toString() ?: ""
            store.setProfileNames(deviceId, names.filterValues { it.isNotEmpty() })
        }
        val names = store.profileNames(deviceId)
        val services = JSONArray()
        for (p in ProfilePickers.ALL) {
            services.put(
                JSONObject()
                    .put("id", p.serviceId)
                    .put("name", Services.byId(p.serviceId)?.name ?: p.serviceId)
                    .put("kind", "name")
                    .put("profile", names[p.serviceId] ?: "")
            )
        }
        services.put(
            JSONObject()
                .put("id", netflix)
                .put("name", Services.byId(netflix)?.name ?: netflix)
                .put("kind", "place")
                .put("places", ProfilePickers.Netflix.MAX_PLACE)
                .put("profile", names[netflix] ?: "")
        )
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

        /** A browse page asks TMDB for films and series, so it holds twice as many. */
        const val BROWSE_CHECK_LIMIT = 40

        val SORTS = setOf("popular", "new", "rated")

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
