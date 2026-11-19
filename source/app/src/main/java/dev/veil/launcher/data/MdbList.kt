package dev.veil.launcher.data

import android.content.Context
import dev.veil.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * MDBList, signed in with the device code (a "Device Code App": client ID only). Continue Watching is
 * paused playback (/sync/playback) plus Up Next (/upnext). The free tier allows 1,000 requests a day, so
 * those two are only fetched again when /sync/last_activities moved (or every few hours, for new airings).
 */
class MdbList(context: Context) : Tracker {
    override val service = Service.MDBLIST
    private val auth = DeviceAuth(
        context, "MDBList", BuildConfig.MDBLIST_CLIENT_ID,
        deviceUrl = "$API/oauth/device-authorization/", tokenUrl = "$API/oauth/token/",
        scope = "write",
    )
    private val sync = SyncCache(context, "mdblist")

    override val hasKeys get() = auth.hasKeys
    override val isConnected get() = auth.isConnected
    override fun requestDeviceCode() = auth.requestDeviceCode()
    override fun pollDeviceToken(dc: DeviceCode) = auth.pollDeviceToken(dc)
    override fun disconnect() { auth.disconnect(); sync.clear() }

    private fun headers() = mapOf("Authorization" to "Bearer ${auth.accessToken}")

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        auth.refreshIfNeeded()
        var r = Http.call(method, API + path, headers(), body?.toString())
        if (r.first == 401 && auth.refreshIfNeeded(force = true)) r = Http.call(method, API + path, headers(), body?.toString())
        return r
    }

    private fun get(path: String): String {
        val (code, text) = call("GET", path)
        if (code !in 200..299) throw IOException("MDBList returned $code")
        return text
    }

    override suspend fun continueWatching(force: Boolean): List<WatchItem> {
        sync.sync(force, { JSONObject(get("/sync/last_activities")) }, listOf(
            SyncCache.Part("playback", 24 * SyncCache.HOUR, {
                SyncCache.stamps(it, "paused_at", "episode_paused_at", "watched_at", "episode_watched_at")
            }) { get("/sync/playback") },
            // hide_unreleased: Up Next only has aired episodes, so new airings need the max age.
            SyncCache.Part("upnext", 3 * SyncCache.HOUR, {
                SyncCache.stamps(it, "watched_at", "episode_watched_at", "dropped_at")
            }) { get("/upnext?limit=20&hide_unreleased=true&air_date_format=instant") },
        ))
        val playback = sync.get("playback")
        val upnext = sync.get("upnext")
        val paused = parsePlayback(playback)
        val pausedShows = paused.filter { !it.isMovie }.mapNotNull { it.tmdb }.toSet()
        val next = parseUpNext(upnext).filter { it.tmdb == null || it.tmdb !in pausedShows }
        return ContinueWatching.merge(paused, next)
    }

    private fun parsePlayback(text: String?): List<WatchItem> {
        val arr = text?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<WatchItem>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val progress = (j.optDouble("progress", 0.0) / 100.0).toFloat()
            if (progress >= 0.95f) continue
            val t = ContinueWatching.time(j.str("paused_at") ?: j.str("updated_at"))
            val runtime = j.int("runtime")?.takeIf { it > 0 }
            val pid = j.optLong("id").takeIf { it > 0 }
            if (j.str("type") == "movie") {
                val m = j.obj("movie") ?: continue
                out += item(m, isMovie = true).copy(progress = progress, runtime = runtime ?: m.int("runtime"), playbackId = pid, sortTime = t)
            } else {
                val show = j.obj("show") ?: continue
                val ep = j.obj("episode") ?: continue
                out += item(show, isMovie = false).copy(
                    season = ep.int("season") ?: 1, episode = ep.int("episode") ?: ep.int("number") ?: 0,
                    episodeTitle = ep.str("title") ?: ep.str("name"),
                    progress = progress, runtime = runtime ?: ep.int("runtime"), playbackId = pid, sortTime = t,
                )
            }
        }
        return out
    }

    private fun parseUpNext(text: String?): List<WatchItem> {
        val arr = text?.let { runCatching { JSONObject(it).optJSONArray("items") }.getOrNull() } ?: return emptyList()
        val out = ArrayList<WatchItem>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val show = j.obj("show") ?: continue
            val next = j.obj("next_episode") ?: continue
            val season = next.int("season") ?: continue
            val episode = next.int("episode") ?: continue
            val lastWatched = ContinueWatching.time(j.str("last_watched_at"))
            val aired = airTime(next.str("air_date"))
            val isNew = j.optBoolean("is_newly_aired") && now - aired < ContinueWatching.NEW_WINDOW_MS
            out += item(show, isMovie = false).copy(
                season = season, episode = episode, episodeTitle = next.str("title"), runtime = next.int("runtime"),
                upNext = true, isNew = isNew, sortTime = maxOf(lastWatched, if (isNew) aired else 0L),
            )
        }
        return out
    }

    /** A full timestamp, or a bare date (taken as midnight UTC). */
    private fun airTime(s: String?): Long {
        if (s == null) return 0L
        return ContinueWatching.time(s).takeIf { it > 0 }
            ?: runCatching { java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
                .getOrDefault(0L)
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
            serviceId = ids?.str("mdblist"),
        )
    }

    private fun ids(item: WatchItem) = JSONObject().apply {
        item.tmdb?.let { put("tmdb", it) }
        item.imdb?.let { put("imdb", it) }
        item.serviceId?.let { put("mdblist", it) }
    }

    private fun ok(code: Int) = code in 200..299

    /** Clears the paused session for this movie/episode (MDBList's way to drop a Continue Watching entry). */
    private fun clearPlayback(item: WatchItem): Boolean {
        val body = if (item.isMovie) JSONObject().put("movie", JSONObject().put("ids", ids(item)))
        else JSONObject().put("show", JSONObject().put("ids", ids(item)).put("season", item.season).put("episode", item.episode))
        val code = call("POST", "/scrobble/clear", body).first
        return ok(code) || code == 404 // nothing to clear
    }

    override fun markWatched(item: WatchItem): Boolean {
        val now = java.time.Instant.now().toString()
        val body = if (item.isMovie) {
            JSONObject().put("movies", JSONArray().put(JSONObject().put("ids", ids(item)).put("watched_at", now)))
        } else {
            JSONObject().put("shows", JSONArray().put(
                JSONObject().put("ids", ids(item)).put("seasons", JSONArray().put(
                    JSONObject().put("number", item.season).put("episodes", JSONArray().put(
                        JSONObject().put("number", item.episode).put("watched_at", now),
                    )),
                )),
            ))
        }
        val done = ok(call("POST", "/sync/watched", body).first)
        if (done && item.playbackId != null) clearPlayback(item)
        invalidate()
        return done
    }

    /** Clears paused progress; for shows also marks them dropped on MDBList (undo there), like Trakt's hide. */
    override fun removeFromProgress(item: WatchItem): Boolean {
        var done = if (item.playbackId != null) clearPlayback(item) else true
        if (!item.isMovie) {
            val body = JSONObject().put("shows", JSONArray().put(
                JSONObject().put("ids", ids(item)).put("dropped_at", java.time.Instant.now().toString()),
            ))
            done = done && ok(call("POST", "/sync/dropped", body).first)
        }
        invalidate()
        return done
    }

    private fun invalidate() = sync.invalidate()

    companion object {
        const val API = "https://api.mdblist.com"
    }
}
