package app.facet.browser

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Release(
    val tag: String,
    val title: String,
    val notes: String,
    val published: String,
    val assetApiUrl: String,
    val assetName: String,
    val size: Long
)

class UpdateException(message: String) : Exception(message)

/** Checks GitHub Releases, downloads the APK and hands it to the system package installer. */
object Updater {
    /** Last install result reported by [InstallReceiver], for the updates page. */
    @Volatile var installListener: ((ok: Boolean, message: String) -> Unit)? = null

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "Facet-Updater")
        if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    /** Blocking. Returns null when there is no release yet. Call off the main thread. */
    fun latest(repo: String, token: String): Release? {
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(repo)) throw UpdateException("Repository must look like owner/name")
        val conn = open("https://api.github.com/repos/$repo/releases/latest", token, "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                200 -> {}
                404 -> {
                    if (token.isEmpty()) throw UpdateException("No release found. If the repository is private, add an access token.")
                    return null
                }
                401, 403 -> throw UpdateException("GitHub refused the request (code $code). Check the access token.")
                else -> throw UpdateException("GitHub answered with code $code")
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            var url = ""
            var name = ""
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("url")
                        name = a.optString("name")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (url.isEmpty()) throw UpdateException("The latest release has no APK attached")
            return Release(
                o.optString("tag_name"), o.optString("name"), o.optString("body"),
                o.optString("published_at"), url, name, size
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Dotted numeric comparison: "v2.0" > "1.0". */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(s: String) = s.trim().trimStart('v', 'V').split('.', '-')
            .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Blocking download with progress (bytes done, total). Call off the main thread. */
    fun download(ctx: Context, r: Release, token: String, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Facet-update.apk")

        var url = r.assetApiUrl
        var conn: HttpURLConnection
        var hops = 0
        var auth = token
        while (true) {
            conn = open(url, auth, "application/octet-stream")
            val code = conn.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val next = conn.getHeaderField("Location") ?: throw UpdateException("Bad redirect from GitHub")
                conn.disconnect()
                url = next
                auth = "" // never send the token to the storage host
                if (++hops > 5) throw UpdateException("Too many redirects")
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw UpdateException("Download failed (code $code)")
            }
            break
        }
        try {
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else r.size
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(32 * 1024)
                    var lastTick = 0L
                    while (true) {
                        if (cancelled()) { out.delete(); throw UpdateException("Cancelled") }
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 120) { lastTick = now; progress(done, total) }
                    }
                }
            }
            progress(done, total)
            if (total > 0 && done < total) throw UpdateException("Download was cut short")
        } finally {
            conn.disconnect()
        }
        return out
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Streams the APK into a PackageInstaller session; the system then asks the user to confirm. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("facet.apk", 0, apk.length()).use { os ->
                    input.copyTo(os)
                    session.fsync(os)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pi.intentSender)
        }
    }
}
