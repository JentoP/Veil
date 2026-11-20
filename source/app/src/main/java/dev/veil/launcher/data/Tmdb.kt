package dev.veil.launcher.data

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Looks up artwork (backdrop, title logo, poster) from TMDB, cached in memory. */
class Tmdb(private val key: String) {
    data class Art(val backdrop: String?, val thumb: String?, val logo: String?, val poster: String?)

    private val cache = ConcurrentHashMap<String, Art>()
    val enabled: Boolean get() = key.isNotBlank()

    fun art(isMovie: Boolean, id: Int): Art? {
        if (!enabled) return null
        val type = if (isMovie) "movie" else "tv"
        cache["$type:$id"]?.let { return it }
        val v4 = key.startsWith("eyJ") // v4 read-access token instead of a v3 api key
        val url = "https://api.themoviedb.org/3/$type/$id" +
            "?append_to_response=images&include_image_language=en,null" +
            (if (v4) "" else "&api_key=$key")
        val headers = if (v4) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val (code, text) = runCatching { Http.call("GET", url, headers) }.getOrNull() ?: return null
        if (code != 200) return null
        val j = JSONObject(text)
        val logos = j.obj("images")?.optJSONArray("logos")
        // Best-rated English PNG logo (SVGs can't be drawn without an extra decoder), else any PNG.
        var logo: String? = null
        if (logos != null) {
            var best = -1.0
            for (pass in 0..1) {
                for (i in 0 until logos.length()) {
                    val l = logos.getJSONObject(i)
                    val path = l.str("file_path") ?: continue
                    if (!path.endsWith(".png")) continue
                    if (pass == 0 && l.str("iso_639_1") != "en") continue
                    val score = l.optDouble("vote_average", 0.0) + l.optInt("vote_count", 0) * 0.01
                    if (score > best) { best = score; logo = path }
                }
                if (logo != null) break
            }
        }
        val art = Art(
            // "original" is typically 3840x2160; Coil decodes it at the hero's size, so it stays sharp when the
            // 1080p UI is scaled up to the 4K panel.
            backdrop = j.str("backdrop_path")?.let { "$IMG/original$it" },
            thumb = j.str("backdrop_path")?.let { "$IMG/w780$it" },
            logo = logo?.let { "$IMG/original$it" }, // small PNGs; sharp on the 4K panel
            poster = j.str("poster_path")?.let { "$IMG/w342$it" },
        )
        cache["$type:$id"] = art
        return art
    }

    private val found = ConcurrentHashMap<String, Int>()

    /** TMDB id for an IMDb (or TVDB) id, via TMDB's /find. Cached; null when TMDB doesn't know it. */
    fun find(isMovie: Boolean, imdb: String?, tvdb: Int?): Int? {
        if (!enabled) return null
        val (source, ext) = when {
            imdb != null -> "imdb_id" to imdb
            tvdb != null && !isMovie -> "tvdb_id" to tvdb.toString()
            else -> return null
        }
        val cacheKey = "$isMovie:$source:$ext"
        found[cacheKey]?.let { return it }
        val v4 = key.startsWith("eyJ")
        val url = "https://api.themoviedb.org/3/find/$ext?external_source=$source" + (if (v4) "" else "&api_key=$key")
        val headers = if (v4) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val (code, text) = runCatching { Http.call("GET", url, headers) }.getOrNull() ?: return null
        if (code != 200) return null
        val arr = JSONObject(text).optJSONArray(if (isMovie) "movie_results" else "tv_results") ?: return null
        val id = arr.optJSONObject(0)?.int("id") ?: return null
        found[cacheKey] = id
        return id
    }

    companion object { private const val IMG = "https://image.tmdb.org/t/p" }
}
