package dev.veil.launcher.data

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Standard OAuth 2.0 device-code sign-in (RFC 8628) for a public app: only a client ID, no secret.
 * Used by Simkl and MDBList (Trakt has its own, older wire format). Tokens stay in app-private storage
 * under the service's own name, so signing out of one never touches another.
 */
internal class DeviceAuth(
    context: Context,
    private val name: String,
    private val clientId: String,
    private val deviceUrl: String,
    private val tokenUrl: String,
    private val scope: String,
) {
    private val prefs = context.getSharedPreferences("auth_$name", Context.MODE_PRIVATE)

    val hasKeys: Boolean get() = clientId.isNotBlank()
    val isConnected: Boolean get() = prefs.getString("access", null) != null
    val accessToken: String get() = prefs.getString("access", "").orEmpty()

    fun disconnect() = prefs.edit().clear().apply()

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun post(url: String, body: String) =
        Http.call("POST", url, mapOf("Content-Type" to "application/x-www-form-urlencoded"), body)

    fun requestDeviceCode(): DeviceCode {
        val (code, text) = post(deviceUrl, form("client_id" to clientId, "scope" to scope))
        if (code == 400 || code == 401 || code == 403) {
            val err = runCatching { JSONObject(text).str("error") }.getOrNull()
            if (code != 400 || err == "invalid_client" || err == "unauthorized_client") throw BadClientId(name)
        }
        if (code != 200) throw IOException("$name returned $code")
        val j = JSONObject(text)
        val userCode = j.getString("user_code")
        val url = j.str("verification_uri") ?: j.str("verification_url") ?: throw IOException("$name sent no sign-in page")
        return DeviceCode(
            deviceCode = j.getString("device_code"),
            userCode = userCode,
            url = url,
            qrUrl = j.str("verification_uri_complete")
                ?: (url + (if ('?' in url) "&" else "?") + "user_code=" + enc(userCode)),
            interval = j.optInt("interval", 5),
            expiresIn = j.optInt("expires_in", 600),
        )
    }

    fun pollDeviceToken(dc: DeviceCode): Poll {
        val (code, text) = post(
            tokenUrl,
            form(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to dc.deviceCode,
                "client_id" to clientId,
            ),
        )
        if (code == 200) { saveTokens(JSONObject(text)); return Poll.AUTHORIZED }
        return when (runCatching { JSONObject(text).str("error") }.getOrNull()) {
            "authorization_pending" -> Poll.PENDING
            "slow_down" -> Poll.SLOW_DOWN
            "access_denied", "expired_token", "invalid_client", "invalid_grant" -> Poll.FAILED
            else -> if (code == 429) Poll.SLOW_DOWN else if (code >= 500) Poll.PENDING else Poll.FAILED
        }
    }

    private fun saveTokens(j: JSONObject) {
        val expiresIn = j.optLong("expires_in", 7 * 86_400L)
        prefs.edit()
            .putString("access", j.getString("access_token"))
            .putString("refresh", j.str("refresh_token") ?: prefs.getString("refresh", null))
            .putLong("expires", System.currentTimeMillis() + expiresIn * 1000)
            .apply()
    }

    /** Refreshes the access token if it's about to expire. Signs out if the refresh token was revoked. */
    @Synchronized
    fun refreshIfNeeded(force: Boolean = false): Boolean {
        if (!force && System.currentTimeMillis() < prefs.getLong("expires", 0) - 5 * 60_000) return true
        val refresh = prefs.getString("refresh", null) ?: return !force
        val (code, text) = runCatching {
            post(tokenUrl, form("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId))
        }.getOrNull() ?: return false
        if (code == 200) { saveTokens(JSONObject(text)); return true }
        if (code == 400 || code == 401) disconnect()
        return false
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
