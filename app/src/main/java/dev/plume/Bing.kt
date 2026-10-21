package dev.plume

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Bing picture of the day, as a choice in the wallpaper list. Nothing goes online unless someone picks it.
 * The home screen asks for a lookup at most once a day while Plume is open: there is no service and no alarm.
 * One JPEG is kept on disk and replaced when the picture changes. Choosing another wallpaper deletes it. Offline,
 * or if Bing stops answering, the last picture stays and nothing is shown. Only the picture shows: no title.
 */
internal object Bing {
    /** The wallpaper choice, next to "res:…", "file:…" and "folder:…". */
    const val CHOICE = "bing"

    /** Called on the main thread when a new picture is on disk. The home screen sets it while it is on screen. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    // A thread of its own that lives only while there is something to do. It doesn't depend on the home screen,
    // which Android may recreate (theme change) while a picture downloads.
    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val inFlight = AtomicBoolean(false)
    private val lock = Any() // a download finishing and a wallpaper being dropped never overlap

    fun file(c: Context) = File(c.filesDir, "bing.jpg")

    private fun used(prefs: SharedPreferences) = prefs.getString("wp_light", null) == CHOICE || prefs.getString("wp_dark", null) == CHOICE

    /** The biggest size that fits the screen: 1080p, 1440p or 4K. At 4K the picture is about 950 KB. */
    private fun size(widthPx: Int) = when {
        widthPx >= 3000 -> 3840 to 2160
        widthPx >= 2200 -> 2560 to 1440
        else -> 1920 to 1080
    }

    /**
     * Looks up the picture if a wallpaper uses it and it was not looked up today: a minute apart while there is no
     * picture yet, an hour apart after a failure. [force] looks it up now (the picture was just chosen).
     */
    fun refresh(c: Context, prefs: SharedPreferences, widthPx: Int, force: Boolean = false) {
        if (!used(prefs)) return
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val retry = if (file(c).exists()) 3_600_000L else 60_000L
        if (!force && (prefs.getLong("bing_day", 0) == today || now - prefs.getLong("bing_try", 0) < retry)) return
        prefs.edit().putLong("bing_try", now).apply()
        if (!inFlight.compareAndSet(false, true)) return
        worker.execute {
            try {
                val token = download(c, widthPx, prefs.getString("bing_hash", null))
                synchronized(lock) {
                    if (!used(prefs)) file(c).delete() // another wallpaper was chosen meanwhile: keep nothing
                    else {
                        prefs.edit().putLong("bing_day", today).apply { if (token != null) putString("bing_hash", token) }.apply()
                        if (token != null) main.post { onChanged?.invoke() }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Plume", "Bing picture failed ($e)") // logged only: yesterday's picture stays
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** No wallpaper uses it any more: the picture and what Plume remembers about it go, at once. */
    fun dropIfUnused(c: Context, prefs: SharedPreferences) = synchronized(lock) {
        if (used(prefs)) return@synchronized
        file(c).delete()
        prefs.edit().remove("bing_hash").remove("bing_day").remove("bing_try").apply()
        if (!inFlight.get()) File(c.filesDir, "bing.part").delete()
    }

    /** Returns the new picture's token, or null when it is the one on disk. Throws when the network or Bing fails. */
    private fun download(c: Context, widthPx: Int, have: String?): String? {
        val (w, h) = size(widthPx)
        val l = Locale.getDefault()
        val market = if (l.country.isEmpty()) "en-US" else "${l.language}-${l.country}"
        // HPImageArchive.aspx is not documented by Microsoft. It can change or stop without notice. If the picture
        // stops coming, look here first: Plume then keeps the last picture and shows no error.
        val image = JSONObject(connect("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&uhd=1&uhdwidth=$w&uhdheight=$h&mkt=$market")
            .inputStream.use { it.readBytes().decodeToString() }).getJSONArray("images").getJSONObject(0)
        val token = image.getString("hsh") + w // the size is part of it, so a bigger screen fetches again
        if (token == have && file(c).exists()) return null
        val part = File(c.filesDir, "bing.part")
        try {
            val conn = connect("https://www.bing.com" + image.getString("url"))
            val copied = conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            // A body cut short is not a picture, even if its header decodes.
            if (conn.contentEncoding == null && conn.contentLengthLong.let { it >= 0 && it != copied }) throw IOException("cut short")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(part.path, bounds)
            if (bounds.outWidth <= 0 || !part.renameTo(file(c))) throw IOException("not a picture")
        } finally {
            part.delete() // gone already after a successful rename
        }
        return token
    }

    /** The picture's size for [fitWallpaper], so the home screen decodes it at the size it was fetched at. */
    fun wallpaperSize(widthPx: Int) = size(widthPx)

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 20000
    }
}
