package dev.veil.launcher.data

import android.content.Context
import dev.veil.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * WeTrakr (api.wetrakr.com), signed in with its device flow: a client ID only, no secret. It is close to
 * RFC 8628 but not the same wire format (JSON bodies, the code sent as "code", the answer in the status
 * code, refresh at /oauth/token/refresh), so it has its own sign-in instead of [DeviceAuth].
 *
 * Continue Watching = the "playing" list (movies and episodes left halfway) plus the "watching" shows with
 * their next episode, each fetched again only when its stamps in /sync/last_activities moved, as WeTrakr's
 * sync guide asks (see [SyncCache]).
 */
class WeTrakr(context: Context) : Tracker {
    override val service = Service.WETRAKR
    private val clientId = BuildConfig.WETRAKR_CLIENT_ID
    private val prefs = context.getSharedPreferences("auth_WeTrakr", Context.MODE_PRIVATE)
    private val sync = SyncCache(context, "wetrakr")

    override val hasKeys get() = clientId.isNotBlank()
    override val isConnected get() = prefs.getString("access", null) != null
    override fun disconnect() { prefs.edit().clear().apply(); sync.clear() }

    private fun headers(token: Boolean) = buildMap {
        put("wetrakr-api-key", clientId)
        put("wetrakr-api-version", "1")
        if (token) put("Authorization", "Bearer ${prefs.getString("access", "")}")
    }

    private fun post(path: String, body: JSONObject, token: Boolean = false) =
        Http.call("POST", API + path, headers(token), body.toString())

    // ---- Sign-in ----

    override fun requestDeviceCode(): DeviceCode {
        val (code, text) = post("/oauth/device/code", JSONObject().put("client_id", clientId))
        if (code == 401 || code == 423) throw BadClientId("WeTrakr") // unknown or suspended app
        if (code == 429) throw IOException("WeTrakr: too many codes from this network, wait 15 minutes")
        if (code != 200) throw IOException("WeTrakr returned $code")
        val j = JSONObject(text)
        val userCode = j.getString("user_code")
        return DeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = userCode,
            url = j.str("verification_url") ?: "https://wetrakr.com/activate",
            qrUrl = "https://wetrakr.com/activate?code=" + DeviceAuth.enc(userCode),
            interval = j.optInt("interval", 5),
            expiresIn = j.optInt("expires_in", 600),
        )
    }

    override fun pollDeviceToken(dc: DeviceCode): Poll {
        val (code, text) = post("/oauth/device/token", JSONObject().put("code", dc.deviceCode).put("client_id", clientId))
        return when {
            code == 200 -> { saveTokens(JSONObject(text)); Poll.AUTHORIZED }
            code == 400 && errorOf(text) == "authorization_pending" -> Poll.PENDING
            code == 429 -> Poll.SLOW_DOWN
            code >= 500 -> Poll.PENDING
            else -> Poll.FAILED // 404 invalid, 409 used, 410 expired, 418 denied
        }
    }

    private fun errorOf(text: String) = runCatching { JSONObject(text).str("error") }.getOrNull()

    private fun saveTokens(j: JSONObject) {
        val expiresIn = j.optLong("expires_in", 7 * 86_400L)
        prefs.edit()
            .putString("access", j.getString("access_token"))
            .putString("refresh", j.str("new_refresh_token") ?: j.str("refresh_token") ?: prefs.getString("refresh", null))
            .putLong("expires", System.currentTimeMillis() + expiresIn * 1000)
            .apply()
    }

    /** Access tokens last 7 days, refresh tokens 180 (rotated on every refresh). */
    @Synchronized
    private fun refreshIfNeeded(force: Boolean = false): Boolean {
        if (!force && System.currentTimeMillis() < prefs.getLong("expires", 0) - 10 * 60_000) return true
        val refresh = prefs.getString("refresh", null) ?: return !force
        val (code, text) = runCatching {
            post("/oauth/token/refresh", JSONObject().put("refresh_token", refresh))
        }.getOrNull() ?: return false
        if (code == 200) { saveTokens(JSONObject(text)); return true }
        if (code == 401) prefs.edit().clear().apply() // will never work again: sign in again
        return false
    }

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        refreshIfNeeded()
        var r = Http.call(method, API + path, headers(true), body?.toString())
        if (r.first == 401 && refreshIfNeeded(force = true)) r = Http.call(method, API + path, headers(true), body?.toString())
        return r
    }

    private fun get(path: String): String {
        val (code, text) = call("GET", path)
        if (code !in 200..299) throw IOException("WeTrakr returned $code")
        return text
    }

    // ---- Continue Watching ----

    override suspend fun continueWatching(force: Boolean): List<WatchItem> {
        sync.sync(force, { JSONObject(get("/sync/last_activities")) }, listOf(
            SyncCache.Part("movies", 24 * SyncCache.HOUR, { SyncCache.stamps(it, "movies.last_playback_at") }) {
                get("/sync/tracking/playing/movies?limit=20")
            },
            SyncCache.Part("episodes", 24 * SyncCache.HOUR, { SyncCache.stamps(it, "episodes.last_playback_at") }) {
                get("/sync/tracking/playing/episodes?limit=20")
            },
            SyncCache.Part("watching", WATCHING_MAX_AGE, {
                SyncCache.stamps(
                    it, "shows.last_tracking_watching_at", "shows.last_tracking_hold_at", "shows.last_tracking_dropped_at",
                    "shows.last_tracking_watched_at", "shows.last_tracking_removed_at", "episodes.last_tracking_watched_at",
                    "episodes.last_tracking_removed_at", "episodes.last_tracking_ignored_at",
                )
            }) {
                get("/sync/tracking/watching/shows?limit=30&sort_by=my_last_watched&extended=episode_level_1,interactions")
            },
        ))
        val movies = sync.get("movies")
        val episodes = sync.get("episodes")
        val watching = sync.get("watching")
        val paused = parsePlaying(movies, isMovie = true) + parsePlaying(episodes, isMovie = false)
        val pausedShows = paused.filter { !it.isMovie }.mapNotNull { it.serviceId }.toSet()
        val next = parseWatching(watching).filter { it.serviceId !in pausedShows }
        return ContinueWatching.merge(paused, next)
    }

    private fun array(text: String?) = text?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun parsePlaying(text: String?, isMovie: Boolean): List<WatchItem> {
        val arr = array(text)
        val out = ArrayList<WatchItem>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val pb = j.obj("playback")
            val progress = ((pb?.optDouble("progress_percent", 0.0) ?: 0.0) / 100.0).toFloat()
            if (progress >= 0.95f) continue
            val t = ContinueWatching.time(pb?.str("tracked_at") ?: j.str("tracked_at"))
            val runtime = pb?.int("runtime_seconds")?.takeIf { it > 0 }?.let { (it + 59) / 60 } ?: j.int("runtime")
            val playId = j.optLong("id").takeIf { it > 0 }
            if (isMovie) {
                out += item(j, isMovie = true).copy(progress = progress, runtime = runtime, playbackId = playId, sortTime = t)
            } else {
                val show = j.obj("show") ?: continue
                out += item(show, isMovie = false).copy(
                    season = j.int("season_number") ?: 1, episode = j.int("number") ?: 0,
                    episodeTitle = j.str("title"), progress = progress, runtime = runtime, playbackId = playId, sortTime = t,
                )
            }
        }
        return out
    }

    private fun parseWatching(text: String?): List<WatchItem> {
        val arr = array(text)
        val out = ArrayList<WatchItem>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val next = j.obj("next_watching_episode") ?: continue
            val season = next.int("season_number") ?: continue
            val episode = next.int("number") ?: continue
            val aired = ContinueWatching.time(next.str("air_datetime") ?: next.str("air_date"))
            if (aired == 0L || aired > now) continue // not aired yet: kept, and shown once it airs
            val last = j.obj("interactions")?.obj("user")?.obj("tracking")?.obj("last")
            val lastWatched = ContinueWatching.time(last?.str("last_watched_at") ?: last?.str("tracked_at") ?: j.str("tracked_at"))
            val isNew = aired in (lastWatched + 1)..now && now - aired < ContinueWatching.NEW_WINDOW_MS
            out += item(j, isMovie = false).copy(
                season = season, episode = episode, episodeTitle = next.str("title"), runtime = next.int("runtime"),
                upNext = true, isNew = isNew, sortTime = maxOf(lastWatched, if (isNew) aired else 0L),
            )
        }
        return out
    }

    private fun item(o: JSONObject, isMovie: Boolean): WatchItem {
        val ids = o.obj("ids")
        return WatchItem(
            isMovie = isMovie,
            title = o.str("title") ?: "Untitled",
            traktId = null,
            imdb = ids?.str("imdb"),
            tmdb = ids?.int("tmdb"),
            tvdb = ids?.int("tvdb"),
            serviceId = o.optLong("id").takeIf { it > 0 }?.toString(),
        )
    }

    // ---- Hold-menu actions ----

    /** The movie or show by its WeTrakr id, else by its external ids. */
    private fun ref(item: WatchItem) = JSONObject().apply {
        val id = item.serviceId?.toLongOrNull()
        if (id != null) put("id", id)
        else put("ids", JSONObject().apply {
            item.tmdb?.let { put("tmdb", it) }
            item.imdb?.let { put("imdb", it) }
            item.tvdb?.let { put("tvdb", it) }
        })
    }

    /** A 200 can still mean nothing happened: then the item is under notFound or errored. */
    private fun worked(r: Pair<Int, String>): Boolean {
        if (r.first !in 200..299) return false
        val j = runCatching { JSONObject(r.second) }.getOrNull() ?: return true
        return listOf("notFound", "errored").none { k ->
            val o = j.obj(k) ?: return@none false
            o.keys().asSequence().any { (o.optJSONArray(it)?.length() ?: 0) > 0 }
        }
    }

    private fun tracking(item: WatchItem, status: String, path: String = "/sync/tracking"): Boolean {
        val body = if (item.isMovie) {
            JSONObject().put("movies", JSONArray().put(ref(item).put("status", status)))
        } else if (status == "hold") {
            JSONObject().put("shows", JSONArray().put(ref(item).put("status", status)))
        } else {
            JSONObject().put("shows", JSONArray().put(
                ref(item).put("seasons", JSONArray().put(
                    JSONObject().put("number", item.season).put("episodes", JSONArray().put(
                        JSONObject().put("number", item.episode).put("status", status),
                    )),
                )),
            ))
        }
        return worked(call("POST", path, body))
    }

    /** Drops the halfway position without logging a play (the title goes back to where it was). */
    private fun clearPlayback(item: WatchItem): Boolean {
        val body = if (item.isMovie) JSONObject().put("movie", ref(item))
        else JSONObject().put("show", ref(item)).put("episode", JSONObject().put("season", item.season).put("number", item.episode))
        val code = runCatching { call("DELETE", "/scrobble/playing", body).first }.getOrDefault(0)
        if (code in 200..299) return true
        return tracking(item, "paused", "/sync/tracking/remove") || code == 404
    }

    override fun markWatched(item: WatchItem): Boolean {
        // A play logged after a pause takes the title out of the playing list by itself.
        val done = tracking(item, "watched")
        invalidate()
        return done
    }

    /** Clears the halfway position; for shows also puts them on hold on WeTrakr (undo there), like Simkl. */
    override fun removeFromProgress(item: WatchItem): Boolean {
        var done = if (item.playbackId != null) clearPlayback(item) else true
        if (!item.isMovie) done = tracking(item, "hold") && done
        invalidate()
        return done
    }

    private fun invalidate() = sync.invalidate()

    companion object {
        const val API = "https://api.wetrakr.com"
        /**
         * Next episodes that haven't aired yet are kept and shown once they air. Not yet confirmed that WeTrakr
         * sends them (it may only send aired ones), so new airings are also picked up every 6 hours.
         */
        private const val WATCHING_MAX_AGE = 6 * SyncCache.HOUR
    }
}
