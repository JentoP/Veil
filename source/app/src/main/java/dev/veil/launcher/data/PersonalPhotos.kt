package dev.veil.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

internal object PersonalPhotos {
    private const val CACHE_COUNT = 12
    private const val REFRESH_INTERVAL = 24 * 60 * 60 * 1_000L
    private val imagePath = Regex("photos/[0-9]{4}\\.jpg")
    private val refreshLock = Mutex()
    private var lastAttemptUrl = ""
    private var lastAttempt = 0L

    fun cached(context: Context, url: String): List<File> =
        snapshots(directory(context, url)).firstOrNull()?.listFiles()
            ?.filter { it.isFile && it.extension == "jpg" }?.sortedBy { it.name }.orEmpty()

    suspend fun refresh(context: Context, url: String): List<File> = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val folder = directory(context, url)
            val now = System.currentTimeMillis()
            val previous = snapshots(folder)
            if (previous.firstOrNull()?.let { now - it.name.toLong() in 0 until REFRESH_INTERVAL } == true ||
                (lastAttemptUrl == url && now - lastAttempt in 0 until 15 * 60_000L)
            ) return@withLock cached(context, url)
            lastAttemptUrl = url
            lastAttempt = now
            val staging = File(folder, "staging")
            try {
                val manifest = URL(url)
                require(manifest.protocol == "https" && manifest.userInfo == null && manifest.ref == null &&
                    manifest.path.endsWith("/album.json"))
                val data = JSONObject(read(manifest, 1024 * 1024).toString(Charsets.UTF_8))
                require(data.getInt("version") == 1)
                val photos = data.getJSONArray("photos")
                require(photos.length() in 1..500)
                val sources = List(photos.length()) { i -> photos.getJSONObject(i).getString("file") }
                require(sources.all(imagePath::matches))
                val selected = sources.distinct().shuffled(Random(now / REFRESH_INTERVAL)).take(CACHE_COUNT)

                staging.deleteRecursively()
                check(staging.mkdirs())
                for ((index, path) in selected.withIndex()) {
                    ensureActive()
                    val bytes = read(URL(manifest, path), 4 * 1024 * 1024)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..1920 && bounds.outHeight in 1..1920)
                    File(staging, "%02d.jpg".format(java.util.Locale.ROOT, index)).writeBytes(bytes)
                }
                ensureActive()
                check(staging.renameTo(File(folder, now.toString())))
                // Keep the previous snapshot while either Ambient window may still be using it.
                snapshots(folder).drop(2).forEach { it.deleteRecursively() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                lastAttempt = 0L
                throw cancelled
            } catch (_: IOException) {
                // Offline: keep the completed snapshot.
            } catch (_: IllegalStateException) {
                // Storage unavailable.
            } catch (_: IllegalArgumentException) {
                // Invalid album or image: retain the previous snapshot.
            } catch (_: org.json.JSONException) {
                // Invalid manifest.
            } finally {
                staging.deleteRecursively()
            }
            cached(context, url)
        }
    }

    private fun directory(context: Context, url: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "ambient-photos/$key")
    }

    private fun snapshots(folder: File): List<File> =
        folder.listFiles()?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.sortedByDescending { it.name.toLong() }.orEmpty()

    private fun read(url: URL, maxBytes: Int): ByteArray {
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200 || connection.contentLength > maxBytes) throw IOException("Album unavailable")
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw IOException("Album file too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
