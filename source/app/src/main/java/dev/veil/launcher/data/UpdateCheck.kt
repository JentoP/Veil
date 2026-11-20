package dev.veil.launcher.data

import org.json.JSONArray
import java.time.Instant

/**
 * "Is there a newer Veil on GitHub?" One unauthenticated request to GitHub's public API for the latest
 * commit that changed source/ (README or website edits don't count). No account, ID or device info is sent.
 */
object UpdateCheck {
    const val REPO = "trbrnt/Veil"

    data class Update(val sha: String, val time: Long, val title: String)

    fun latest(): Update? {
        val (code, text) = runCatching {
            Http.call(
                "GET", "https://api.github.com/repos/$REPO/commits?path=source&per_page=1",
                mapOf("Accept" to "application/vnd.github+json"),
            )
        }.getOrNull() ?: return null
        if (code != 200) return null
        val c = runCatching { JSONArray(text).getJSONObject(0) }.getOrNull() ?: return null
        val commit = c.obj("commit") ?: return null
        val date = commit.obj("committer")?.str("date") ?: commit.obj("author")?.str("date") ?: return null
        val time = runCatching { Instant.parse(date).toEpochMilli() }.getOrNull() ?: return null
        val title = commit.str("message")?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return Update(c.str("sha") ?: return null, time, title)
    }
}
