package dev.plume

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors
import org.json.JSONObject

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString("theme", "light")) {
    "dark" -> true
    "auto" -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 7..18
    else -> false
}

class MainActivity : Activity() {

    private class App(val pkg: String, val label: String, val launch: Intent, val icon: Drawable)
    private class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?)

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    // Posters get their own threads: one slow image server must not hold up the rest (or the row data).
    private val posterIo = Executors.newFixedThreadPool(3)
    private val loading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val ui = Handler(Looper.getMainLooper())
    private val posters = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Kept across activity rebuilds (theme switch), so the new home screen shows its apps at once.
    private var apps = APPS ?: emptyList()
    private var appsDirty = APPS == null
    private var watchKey: String? = null
    private var lastTop: View? = null // last focused view in each home row, so Up/Down return to it
    private var lastApp: View? = null
    private var lastCard: View? = null
    private var editing = false
    private var grabbed: View? = null
    private var holdFired = false
    private var dark = false
    private var fg = Color.BLACK
    private var ground = Color.WHITE
    private var cardRest = intArrayOf(Color.WHITE, Color.WHITE)
    private var cardLit = cardRest

    private lateinit var appRow: LinearLayout
    private lateinit var watchRow: LinearLayout
    private lateinit var watchTitle: TextView
    private val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    private lateinit var watchScroll: View
    private lateinit var allApps: View
    private lateinit var allAppsGrid: GridLayout
    private lateinit var wifi: ImageView

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
            i.data?.schemeSpecificPart?.let { GLYPHS.remove(it) } // an update may bring a new icon
        }
    }

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            ui.post {
                showNet(caps)
                // Online again (e.g. Wi-Fi coming up after boot): fetch any posters that failed meanwhile.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryPosters()
            }
        }

        override fun onLost(n: Network) {
            ui.post { showNet(null) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        appRow = findViewById(R.id.appRow)
        watchRow = findViewById(R.id.watchRow)
        watchTitle = findViewById(R.id.watchTitle)
        watchScroll = findViewById(R.id.watchScroll)
        allApps = findViewById(R.id.allApps)
        allAppsGrid = findViewById(R.id.allAppsGrid)
        wifi = findViewById(R.id.wifi)
        applyTheme()
        loadWallpaper()

        // Top bar: network, All apps, Android settings, launcher settings. Round, lit on focus.
        for ((id, action) in listOf<Pair<Int, () -> Unit>>(
            R.id.wifi to { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            R.id.allAppsButton to { openAllApps() },
            R.id.systemSettings to { openSettings(Settings.ACTION_SETTINGS) },
            R.id.launcherSettings to { openLauncherSettings() },
        )) findViewById<ImageView>(id).apply {
            setColorFilter(fg)
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            background = ring
            setOnFocusChangeListener { v, focused ->
                if (focused) lastTop = v
                ring.setColor(if (!focused) 0 else if (dark) 0xFF2A2A32.toInt() else Color.WHITE)
                animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                    .setDuration(160).setInterpolator(DECELERATE).start()
            }
            setOnClickListener { action() }
        }
        if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().remove("reopen_settings").apply()
            ui.post { openLauncherSettings(focus = 1) }
        } else if (!homeServiceOn() && !prefs.getBoolean("setup_shown", false)) {
            // Fresh install: open settings on the Home button row, the one step that has to happen by hand.
            prefs.edit().putBoolean("setup_shown", true).apply()
            ui.post { openLauncherSettings(focus = HOME_BUTTON_ROW) }
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        // Application context: ConnectivityManager keeps its first instance (and that instance's context) in a
        // static callback handler forever, so an Activity context here leaks the whole first home screen.
        applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, ui)

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureHomeService()
        if (wantDark() != dark) {
            recreate()
            return
        }
        // Only rebuild what changed, so focus stays where it was when returning from an app.
        if (appsDirty) refreshApps() else if (appRow.childCount == 0) renderApps()
        refreshWatchNext()
        showWeather()
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)
        ui.postDelayed(themeTick, 60_000L)
        // Gallery chosen but its paintings missing (updated from a version that bundled them): fetch them.
        if (galleryOn() && !paintingsReady(this)) downloadPaintings {}
    }

    /** Auto theme flips at 7:00 and 19:00 even while the home screen just sits there. */
    private val themeTick = object : Runnable {
        override fun run() {
            if (wantDark() != dark) recreate() else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        refreshWatchNext()
    }

    @Deprecated("Only used for the default-home role request")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == ROLE_REQUEST && settingsScrim.visibility == View.VISIBLE) renderSettings(DEFAULT_HOME_ROW)
    }

    override fun onPause() {
        ui.removeCallbacks(weatherTick)
        ui.removeCallbacks(themeTick)
        if (editing) endEdit()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback)
        io.shutdownNow()
        posterIo.shutdownNow()
        super.onDestroy()
    }

    /**
     * Android drops our Home service from the enabled accessibility services whenever the app is
     * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission).
     */
    private fun ensureHomeService() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED || homeServiceOn()) return
        val me = ComponentName(this, HomeOverride::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isEmpty()) me else "$on:$me")
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Compared as components: Settings may store us in short form (dev.plume/.HomeOverride). */
    private fun homeServiceOn(): Boolean {
        val me = ComponentName(this, HomeOverride::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** The Home key service is an accessibility service, which Android only lets the user switch on. */
    private fun turnOnHomeButton() {
        if (homeServiceOn()) return
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) return renderSettings(HOME_BUTTON_ROW)
        Toast.makeText(this, "Turn on \"Plume: Home button\" on the next screen", Toast.LENGTH_LONG).show()
        openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    private fun isDefaultHome() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /**
     * Asks Android to make us the default home app (its own confirmation dialog). On Google TV the system
     * launcher still wins Home intents, which is why the Home button service exists; on other Android TVs
     * this alone is enough.
     */
    private fun makeDefaultHome() {
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        } else if (!isDefaultHome()) {
            openSettings(Settings.ACTION_HOME_SETTINGS)
        }
    }

    /** Home pressed while already home: close whatever is open and go back to the start of the apps row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (editing) endEdit()
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        watchScroll.scrollTo(0, 0)
        appRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes overlays")
    override fun onBackPressed() {
        when {
            settingsScrim.visibility == View.VISIBLE -> closeLauncherSettings()
            allApps.visibility == View.VISIBLE -> closeAllApps()
        }
    }

    // ---------- theme ----------

    private fun wantDark() = prefersDark(prefs)

    private fun applyTheme() {
        dark = wantDark()
        fg = if (dark) 0xFFF5F5F7.toInt() else 0xFF111214.toInt()
        // Dark cards are matte black glass on a true-black ground: lit faintly from the top and fading to
        // near-black, no outline (outlines read as a grey grid). Focus brightens the glass, never to grey.
        // Light cards are frosted grey glass, so they still read on white wallpapers; focus turns them white.
        cardRest = if (dark) intArrayOf(0xFF16161B.toInt(), 0xFF060607.toInt()) else intArrayOf(0xD9E2E5E9.toInt(), 0xD9D8DCE1.toInt())
        cardLit = if (dark) intArrayOf(0xFF26262E.toInt(), 0xFF0C0C0F.toInt()) else intArrayOf(Color.WHITE, Color.WHITE)
        // Must match the colour each wallpaper fades into, or the bottom edge shows a seam.
        ground = if (dark) Color.BLACK else 0xFFF3F5F7.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(ground)
        allApps.setBackgroundColor(ground)
        for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.watchTitle, R.id.allAppsTitle, R.id.rowLabel, R.id.weather)) {
            findViewById<TextView>(id).setTextColor(fg)
        }
        wifi.setColorFilter(fg)
    }

    // ---------- wallpapers ----------
    // Bundled ones have their fade baked in. Your own go in Android/data/dev.plume/files/wallpapers/
    // (adb push, no permission needed) and get the fade painted once when loaded.
    // Each theme remembers its own pick. Decoded off the UI thread as RGB_565, max 1080p (~4 MB).

    private fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** A plain choice first (just the theme's base colour, no image), then pictures. */
    private fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            BUNDLED_WALLPAPERS.keys.map { it to "res:$it" } +
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList())

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    private fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    private fun defaultWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

    private fun currentWallpaper(forDark: Boolean = dark) =
        prefs.getString(if (forDark) "wp_dark" else "wp_light", null) ?: defaultWallpaper(forDark)

    private fun wallpaperName(choice: String) = choice.substringAfter(':').substringBeforeLast('.')

    private fun loadWallpaper() {
        val view = findViewById<ImageView>(R.id.wallpaper)
        val choice = currentWallpaper()
        if (choice.startsWith("plain:")) { // the root's ground colour shows through
            view.setImageDrawable(null)
            return
        }
        val fallback = defaultWallpaper(dark)
        io.execute {
            val bmp = try {
                decodeWallpaper(choice)
            } catch (e: Exception) {
                null
            } ?: decodeWallpaper(fallback)
            ui.post { view.setImageBitmap(bmp) }
        }
    }

    private fun decodeWallpaper(choice: String): Bitmap? {
        BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            return BitmapFactory.decodeResource(resources, res, opts)
        }
        val file = File(wallpaperDir(), choice.removePrefix("file:"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        // Same fade as the bundled ones: clear above 28% height, into the background colour by 50%.
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.RGB_565, true)
        val h = bmp.height.toFloat()
        val base = ground and 0x00FFFFFF
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, Paint().apply {
            shader = LinearGradient(0f, h * 0.28f, 0f, h * 0.5f, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    private fun pickWallpaper(forDark: Boolean) {
        val choices = wallpaperChoices(forDark)
        val current = choices.indexOfFirst { it.second == currentWallpaper(forDark) }
        AlertDialog.Builder(this)
            .setTitle(if (forDark) "Dark mode wallpaper" else "Light mode wallpaper")
            .setSingleChoiceItems(choices.map { it.first }.toTypedArray(), current) { d, i ->
                prefs.edit().putString(if (forDark) "wp_dark" else "wp_light", choices[i].second).apply()
                d.dismiss()
                if (forDark == dark) loadWallpaper()
                renderSettings(if (forDark) 3 else 2)
            }
            .show()
    }

    // ---------- launcher settings panel (top-bar button; the Settings tile is Android's) ----------

    private val settingsScrim by lazy { findViewById<View>(R.id.settingsScrim) }
    private val settingsList by lazy { findViewById<LinearLayout>(R.id.settingsList) }

    private fun openLauncherSettings(focus: Int = 0) {
        findViewById<View>(R.id.settingsPanel).setBackgroundColor(if (dark) 0xFF09090B.toInt() else 0xFFF7F8FA.toInt())
        findViewById<TextView>(R.id.settingsTitle).setTextColor(fg)
        settingsScrim.visibility = View.VISIBLE
        homeFocusable(false)
        renderSettings(focus)
    }

    private fun closeLauncherSettings() {
        settingsScrim.visibility = View.GONE
        homeFocusable(true)
        findViewById<View>(R.id.launcherSettings).requestFocus()
    }

    private fun renderSettings(focus: Int) {
        val theme = prefs.getString("theme", "light")!!
        val sort = prefs.getString("sort", "manual")!!
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!.size
        val weather = prefs.getString("weather", "off")!!
        val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
        val rows = listOf<Triple<String, String, () -> Unit>>(
            Triple("Edit apps row", "") { closeLauncherSettings(); startEdit(grab = null) },
            Triple("Theme", theme.replaceFirstChar { it.uppercase() }) {
                prefs.edit().putString("theme", mapOf("light" to "dark", "dark" to "auto", "auto" to "light")[theme])
                    .putBoolean("reopen_settings", true).apply()
                recreate() // colours are applied once per activity; reopen the panel after
            },
            Triple("Light mode wallpaper", wallpaperName(currentWallpaper(false))) { pickWallpaper(false) },
            Triple("Dark mode wallpaper", wallpaperName(currentWallpaper(true))) { pickWallpaper(true) },
            Triple("Sort apps", if (sort == "manual") "Manual" else "Most used") {
                prefs.edit().putString("sort", if (sort == "manual") "usage" else "manual").apply()
                renderApps()
                renderSettings(4)
            },
            Triple("Restore hidden shows", if (hidden == 0) "None hidden" else "$hidden hidden") {
                prefs.edit().remove("hidden_watch").apply()
                watchKey = null
                refreshWatchNext()
                renderSettings(5)
            },
            Triple("Continue Watching apps", if (cwOff == 0) "All on" else "$cwOff off") { pickWatchSources() },
            Triple("Weather", mapOf("off" to "Off", "c" to "°C", "f" to "°F")[weather]!!) {
                prefs.edit().putString("weather", mapOf("off" to "c", "c" to "f", "f" to "off")[weather]).apply()
                showWeather()
                ui.removeCallbacks(weatherTick)
                ui.post(weatherTick)
                renderSettings(7)
            },
            Triple("Screensaver", when {
                downloading -> "Downloading Gallery…"
                galleryOn() -> "Gallery"
                else -> "${screensaverName()} · Gallery $PAINTINGS_MB"
            }) {
                toggleGallery()
                renderSettings(SCREENSAVER_ROW)
            },
            Triple("Test screensaver", "") { testScreensaver() },
            Triple("Home button", if (homeServiceOn()) "On" else "Off: press to turn on") { turnOnHomeButton() },
            Triple("Default launcher", if (isDefaultHome()) "Plume" else "Press to set") { makeDefaultHome() },
        )
        settingsList.removeAllViews()
        rows.forEachIndexed { i, (title, value, action) ->
            settingsList.addView(settingsRow(title, value, action), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
                topMargin = dp(4)
            })
        }
        // Keep focus inside the panel: Up from the first row and Down from the last go nowhere.
        settingsList.getChildAt(0).let { it.nextFocusUpId = it.id }
        settingsList.getChildAt(rows.size - 1).let { it.nextFocusDownId = it.id }
        settingsList.getChildAt(focus.coerceIn(0, rows.size - 1)).requestFocus()
    }

    private fun settingsRow(title: String, value: String, action: () -> Unit): View {
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply { cornerRadius = dp(8).toFloat() }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = bg
            isFocusable = true
            isClickable = true
            addView(label(title, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(value, 13f).apply { alpha = 0.6f })
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                bg.colors = if (!focused) intArrayOf(0, 0) else if (dark) cardLit else intArrayOf(Color.WHITE, Color.WHITE)
            }
        }
    }

    private fun showNet(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        // Dim only the icon: the focus ring is the background and should stay full strength.
        wifi.imageAlpha = if (caps == null) 90 else 255
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- apps row ----------

    /** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
    private fun refreshApps() {
        appsDirty = false
        io.execute {
            val list = try {
                loadApps()
            } catch (e: Exception) {
                return@execute
            }
            ui.post {
                apps = list
                APPS = list
                renderApps()
            }
        }
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != TV_SETTINGS }
            .distinctBy { it.packageName }
            .map { a ->
                App(
                    a.packageName,
                    a.loadLabel(pm).toString(),
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(ComponentName(a.packageName, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    a.loadIcon(pm),
                )
            } + consoleInputs()
    }

    /**
     * Devices on HDMI that name themselves over CEC (the PS5 shows up as "PlayStation 5"), as app tiles.
     * Opening one switches the TV to that HDMI input, which also wakes the device over CEC.
     */
    private fun consoleInputs(): List<App> = try {
        getSystemService(TvInputManager::class.java).tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId != null }
            .map { input ->
                val label = input.loadLabel(this).toString()
                val ps = label.contains("PlayStation", ignoreCase = true)
                App(
                    HDMI + input.parentId,
                    label,
                    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.parentId))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    if (ps) getDrawable(R.drawable.logo_playstation)!! else input.loadIcon(this) ?: getDrawable(R.drawable.ic_apps)!!,
                )
            }
            .distinctBy { it.pkg }
    } catch (e: Exception) {
        emptyList()
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) = refreshApps()
        override fun onInputRemoved(id: String) = refreshApps()
    }

    private fun sortedApps(): List<App> {
        // Streaming apps first by default, then alphabetical; manual order or usage overrides.
        val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() }
        if (prefs.getString("sort", "manual") == "usage") {
            return apps.sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
        }
        val order = prefs.getString("order", "")!!.split(',')
        // A console not yet placed by hand goes first, so it's visible without scrolling.
        return apps.sortedWith(compareBy<App> {
            order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
        }.then(byDefault))
    }

    /**
     * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
     * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
     */
    private fun renderApps() {
        val focusedTag = currentFocus?.tag
        // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
        val takeFocus = currentFocus == null || currentFocus?.parent === appRow
        val gap = dp(GRID_GAP)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * (ROW_TILES - 1)) / ROW_TILES
        appRow.removeAllViews()
        val tiles = sortedApps().map { appTile(it, labeled = false) }
        if (tiles.isEmpty()) return
        tiles.forEachIndexed { i, t ->
            t.id = View.generateViewId()
            appRow.addView(t, LinearLayout.LayoutParams(width, dp(76)).apply { if (i > 0) marginStart = gap })
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, focused ->
                own.onFocusChange(v, focused)
                if (focused) lastApp = v
                showRowLabel(v, focused)
            }
        }
        if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
        if (!prefs.getBoolean("tip_move", false)) {
            prefs.edit().putBoolean("tip_move", true).apply()
            Toast.makeText(this, "Tip: hold OK on an app to rearrange the row", Toast.LENGTH_LONG).show()
        }
    }

    /** The home row shows marks only (the name appears under the focused tile); the All apps grid labels them. */
    private fun appTile(a: App, labeled: Boolean = true): View {
        val logo = LOGOS[a.pkg]
        val custom = customIcon(a.pkg)
        val name = a.label.takeIf { labeled }
        val t = when {
            custom != null -> tile(name, custom, false, a.pkg) { launch(a) }
            a.pkg.startsWith(HDMI) -> tile(name, a.icon, true, a.pkg) { launch(a) }
            logo != null -> tile(name.takeIf { !logo.second }, getDrawable(logo.first)!!, true, a.pkg,
                wordmarkInset = if (!logo.second) null else dp(if (labeled) 28 else 14)) { launch(a) }
            else -> cachedGlyph(a)?.let { tile(name, it, true, a.pkg) { launch(a) } }
                ?: tile(name, a.icon.mutate().apply { colorFilter = GREYSCALE }, false, a.pkg) { launch(a) }
        }
        t.contentDescription = a.label
        return t
    }

    /** Slides the shared name label under the focused row tile; fades it out once focus leaves the row. */
    private fun showRowLabel(v: View, focused: Boolean) {
        val label = findViewById<TextView>(R.id.rowLabel)
        if (focused && !editing) {
            label.text = v.contentDescription
            // Posted: on first render focus lands before layout, when every width is still 0.
            v.post { label.translationX = v.left + v.width / 2f - label.width / 2f }
            label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
        } else {
            ui.post { if (currentFocus?.parent !== appRow || editing) label.animate().alpha(0f).setDuration(120).start() }
        }
    }

    /** White card: logo on top, name underneath. With no label the mark sits alone, centred, at one size. */
    private fun tile(label: String?, icon: Drawable, mono: Boolean, tag: String, wordmarkInset: Int? = null, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val img = ImageView(this).apply {
            setImageDrawable(icon.mutate())
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (mono) setColorFilter(fg)
        }
        val text = label?.let {
            label(it, 13f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), 0)
            }
        }
        // Same card as light mode; in dark the marks rest slightly dimmed and brighten with focus.
        val restLogo = if (dark) 0.8f else 1f
        val restText = if (dark) 0.65f else 1f
        img.alpha = restLogo
        text?.alpha = restText
        style(col, dp(8), card = true) { focused ->
            if (!dark) return@style
            img.animate().alpha(if (focused) 1f else restLogo).setDuration(160).start()
            text?.animate()?.alpha(if (focused) 1f else restText)?.setDuration(160)?.start()
        }
        if (text == null) {
            // Icons share one height. Wordmarks sit in a square viewport, so fit them by width instead
            // (crop the empty top and bottom) or they shrink to a sliver.
            if (wordmarkInset != null) {
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setPadding(wordmarkInset, 0, wordmarkInset, 0)
            } else {
                img.setPadding(dp(16), dp(20), dp(16), dp(20))
            }
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(26)))
            col.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        col.tag = tag
        col.setOnClickListener { onClick() }
        return col
    }

    /**
     * A mono mark from an adaptive icon's foreground layer (its symbol, without the coloured backplate),
     * cropped to the safe zone. Brightness becomes opacity: the light symbol becomes the mark, and dark
     * details in it (a note inside a circle) become holes, so the mono tint keeps them. Null for old
     * single-layer icons and for photo-like layers that fill the square, which keep the greyscale look.
     */
    private fun glyph(icon: Drawable): Bitmap? {
        val fg = (icon as? AdaptiveIconDrawable)?.foreground ?: return null
        val size = dp(96) // large, so a small symbol is still sharp after trimming and scaling up
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Layers are 108dp with the symbol inside the middle 72dp; drawing at 1.5x crops to that.
        fg.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
        fg.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        if (px.count { Color.alpha(it) > 200 } > px.size * 0.7) return null // a picture, not a symbol
        val alpha = FloatArray(px.size) { i ->
            val c = px[i]
            Color.alpha(c) * (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }
        val peak = alpha.max().takeIf { it > 0f } ?: return null
        // Stretch so the brightest part is fully opaque: an orange symbol shouldn't look dimmer than a white one.
        for (i in px.indices) px[i] = Color.argb((alpha[i] * 255f / peak).toInt().coerceAtMost(255), 255, 255, 255)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        // Trim to the visible mark (as a square, centred), so small symbols fill the tile like the logos do.
        val on = px.indices.filter { Color.alpha(px[it]) > 40 }.ifEmpty { return null }
        val l = on.minOf { it % size }; val r = on.maxOf { it % size }
        val t = on.minOf { it / size }; val b = on.maxOf { it / size }
        val side = maxOf(r - l, b - t) + 1
        val x = ((l + r + 1 - side) / 2).coerceIn(0, size - side)
        val y = ((t + b + 1 - side) / 2).coerceIn(0, size - side)
        return Bitmap.createBitmap(bmp, x, y, side, side)
    }

    /** glyph() walks every pixel, too slow to redo on each row rebuild; kept per app until the app changes. */
    private fun cachedGlyph(a: App): Drawable? {
        val bmp = if (a.pkg in GLYPHS) GLYPHS[a.pkg] else glyph(a.icon).also { GLYPHS[a.pkg] = it }
        return bmp?.let { BitmapDrawable(resources, it) }
    }

    /** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
    private fun customIcon(pkg: String): Drawable? =
        listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
            try {
                assets.open(path).use { Drawable.createFromStream(it, null) }
            } catch (e: IOException) {
                null
            }
        }

    // ---------- holding OK, and editing the apps row ----------

    /**
     * Hold detection lives here, not in View long-click: this TV's remote doesn't reliably trigger the
     * framework's long-press timer. We treat the first auto-repeat of OK as "held"; the long-click
     * listener in style() is a fallback for remotes that send no repeats. [holdFired] stops both firing.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) holdFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!holdFired) currentFocus?.let { fireHold(it) }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
        // The settings panel is a vertical list: don't let Left/Right leak focus to the home screen behind it.
        if (settingsScrim.visibility == View.VISIBLE &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) return true
        if (editing && editKey(e, confirm)) return true
        if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
            currentFocus?.let { homeNav(it, e.keyCode) }?.let { it.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    /**
     * The home screen is three rows: top bar, apps, Continue Watching. Left/Right stay inside a row and
     * stop at its ends; Up/Down move between rows and land where you last were in that row, not on
     * whatever happens to sit nearest on screen. Returns the view to focus (itself = stay), or null to
     * let Android handle the key.
     */
    private fun homeNav(v: View, key: Int): View? {
        fun alive(x: View?) = x?.takeIf { it.isAttachedToWindow }
        val apps = alive(lastApp)?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
        val cards = if (watchScroll.visibility != View.VISIBLE) null
            else alive(lastCard) ?: (watchRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
        return when {
            v.parent === wifi.parent -> when (key) { // top bar
                KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: v
                KeyEvent.KEYCODE_DPAD_UP -> v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (v.id == R.id.wifi) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (v.id == R.id.launcherSettings) v else null
                else -> null
            }
            v.parent === appRow -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> top
                KeyEvent.KEYCODE_DPAD_DOWN -> cards ?: v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (appRow.indexOfChild(v) == 0) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (appRow.indexOfChild(v) == appRow.childCount - 1) v else null
                else -> null
            }
            v.getTag(R.id.watchRow) is Prog -> {
                val i = watchRow.indexOfChild(v.parent as View)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> apps ?: v
                    KeyEvent.KEYCODE_DPAD_DOWN -> v
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) v else null
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == watchRow.childCount - 1) v else null
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
    private fun homeFocusable(on: Boolean) {
        findViewById<ViewGroup>(R.id.content).descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun fireHold(v: View) {
        holdFired = true
        v.cancelLongPress()
        v.isPressed = false
        onHold(v)
    }

    /** What holding OK does depends on what's focused. */
    private fun onHold(v: View) {
        val tag = v.tag as? String
        when {
            allApps.visibility == View.VISIBLE && tag != null -> { // grid: pin to the front of the row
                saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
                closeAllApps()
                appRow.getChildAt(0)?.requestFocus() // show the app just pinned
            }
            v.parent === appRow -> startEdit(grab = v)
            v.getTag(R.id.watchRow) is Prog -> hideWatch(v.getTag(R.id.watchRow) as Prog)
        }
    }

    private fun appTileCount() = appRow.childCount

    private fun startEdit(grab: View?) {
        editing = true
        appsTitle.text = "Editing apps row:  OK pick up / drop  ·  ◀ ▶ move  ·  Back done"
        appsTitle.visibility = View.VISIBLE
        (grab ?: appRow.getChildAt(0)).requestFocus()
        setGrabbed(grab)
    }

    private fun setGrabbed(v: View?) {
        grabbed?.alpha = 1f
        grabbed = v
        v?.alpha = 0.6f
    }

    private fun endEdit() {
        setGrabbed(null)
        editing = false
        appsTitle.visibility = View.GONE
        val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
    }

    /** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
    private fun editKey(e: KeyEvent, confirm: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) endEdit()
            return true
        }
        val v = currentFocus ?: return false
        val i = appRow.indexOfChild(v)
        if (confirm) {
            if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) setGrabbed(if (grabbed === v) null else v)
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (j !in 0 until appTileCount()) return true
                if (grabbed === v) {
                    // Swap layout params too, so the first tile keeps no leading margin.
                    val other = appRow.getChildAt(j)
                    val lp = v.layoutParams
                    v.layoutParams = other.layoutParams
                    other.layoutParams = lp
                    appRow.removeViewAt(i)
                    appRow.addView(v, j)
                    v.requestFocus()
                    return true
                }
                return false // not holding anything: normal focus movement within the row
            }
        }
        return false
    }

    private fun saveOrder(pkgs: List<String>) {
        prefs.edit().putString("order", pkgs.joinToString(",")).putString("sort", "manual").apply()
    }

    private fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).apply()
        if (prefs.getString("sort", "manual") == "usage") appsDirty = true
        try {
            startActivity(a.launch)
        } catch (e: Exception) {
            packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
        }
    }

    // ---------- all apps ----------

    private fun openAllApps() {
        allAppsGrid.removeAllViews()
        val gap = dp(10)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * 5) / 6
        for (a in sortedApps()) {
            val t = appTile(a) // holding OK here pins it to the row (see onHold)
            allAppsGrid.addView(t, GridLayout.LayoutParams().apply {
                this.width = width
                height = dp(76)
                setMargins(0, 0, gap, gap)
            })
        }
        allApps.visibility = View.VISIBLE
        homeFocusable(false)
        allAppsGrid.getChildAt(0)?.requestFocus()
    }

    private fun closeAllApps() {
        allApps.visibility = View.GONE
        homeFocusable(true)
        renderApps()
        findViewById<View>(R.id.allAppsButton).requestFocus() // back where it was opened from
    }

    // ---------- screensaver ----------
    // Gallery on: remember whatever screensaver was set (Aerial Views here) and switch to ours.
    // Gallery off: put that one back. Needs the adb-granted WRITE_SECURE_SETTINGS, like the Home service.

    private fun gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

    private fun currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

    private fun galleryOn() = currentScreensaver() == gallery()

    private fun screensaverName(): String {
        val pkg = ComponentName.unflattenFromString(currentScreensaver())?.packageName ?: return "Off"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            "System default"
        }
    }

    private fun toggleGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Needs a one-time adb permission: pm grant dev.plume android.permission.WRITE_SECURE_SETTINGS", Toast.LENGTH_LONG).show()
            return
        }
        val cr = contentResolver
        if (galleryOn()) {
            Settings.Secure.putString(cr, "screensaver_components", prefs.getString("prev_screensaver", "") ?: "")
            paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
        } else if (!paintingsReady(this)) {
            downloadPaintings { toggleGallery() }
        } else {
            prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
            Settings.Secure.putString(cr, "screensaver_components", gallery())
            Settings.Secure.putInt(cr, "screensaver_enabled", 1)
        }
    }

    @Volatile private var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    private fun downloadPaintings(then: () -> Unit) {
        if (downloading) return
        downloading = true
        Toast.makeText(this, "Downloading Gallery paintings ($PAINTINGS_MB)…", Toast.LENGTH_SHORT).show()
        Thread {
            val part = File(filesDir, "paintings.part").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in PAINTING_FILES) openStream(PAINTINGS_URL + f).use { input ->
                    File(part, f).outputStream().use { input.copyTo(it) }
                }
                paintingsDir(this).deleteRecursively()
                part.renameTo(paintingsDir(this))
            } catch (e: Exception) {
                android.util.Log.w("Plume", "paintings download failed ($e)")
                part.deleteRecursively()
                false
            }
            ui.post {
                downloading = false
                if (ok) then() else Toast.makeText(this, "Couldn't download the paintings. Check the connection and try again.", Toast.LENGTH_LONG).show()
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(SCREENSAVER_ROW)
            }
        }.start()
    }

    /** Starts whichever screensaver is set, through SystemUI's "dream now" entry point. */
    private fun testScreensaver() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            Toast.makeText(this, "This TV can't start the screensaver on demand", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- weather (under the date) ----------
    // Location comes from the public IP (ipwho.is): a TV has no GPS and Android TV no location service.
    // Weather is Open-Meteo. Both are free and keyless. We always fetch Celsius and convert, so
    // switching units needs no refetch. The last reading is kept in prefs and shows instantly.

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, 30 * 60_000L)
        }
    }

    private fun refreshWeather() {
        if (prefs.getString("weather", "off") == "off") return
        if (System.currentTimeMillis() - prefs.getLong("wx_time", 0) < 25 * 60_000L) return
        io.execute {
            try {
                if (System.currentTimeMillis() - prefs.getLong("geo_time", 0) > 6 * 3600_000L) {
                    val geo = getJson("https://ipwho.is/?fields=success,city,latitude,longitude")
                    if (geo.optBoolean("success")) prefs.edit()
                        .putString("geo_city", geo.getString("city"))
                        .putFloat("geo_lat", geo.getDouble("latitude").toFloat())
                        .putFloat("geo_lon", geo.getDouble("longitude").toFloat())
                        .putLong("geo_time", System.currentTimeMillis()).apply()
                }
                if (!prefs.contains("geo_lat")) return@execute
                val now = getJson("https://api.open-meteo.com/v1/forecast?latitude=${prefs.getFloat("geo_lat", 0f)}" +
                    "&longitude=${prefs.getFloat("geo_lon", 0f)}&current=temperature_2m,weather_code").getJSONObject("current")
                prefs.edit()
                    .putFloat("wx_temp", now.getDouble("temperature_2m").toFloat())
                    .putInt("wx_code", now.getInt("weather_code"))
                    .putLong("wx_time", System.currentTimeMillis()).apply()
                ui.post { showWeather() }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "weather failed ($e)") // keep the last reading; next tick retries
            }
        }
    }

    private fun showWeather() {
        val view = findViewById<TextView>(R.id.weather)
        val unit = prefs.getString("weather", "off")
        if (unit == "off" || !prefs.contains("wx_temp")) {
            view.visibility = View.GONE
            return
        }
        val c = prefs.getFloat("wx_temp", 0f)
        val temp = Math.round(if (unit == "f") c * 9 / 5 + 32 else c)
        view.text = listOfNotNull("$temp° ${weatherWord(prefs.getInt("wx_code", 0))}".trim(), prefs.getString("geo_city", null))
            .joinToString("  ·  ")
        view.visibility = View.VISIBLE
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    private fun weatherWord(code: Int) = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }

    private fun getJson(url: String) = JSONObject(openStream(url).use { it.readBytes().decodeToString() })

    // ---------- continue watching (Android TV Watch Next) ----------

    private fun refreshWatchNext() {
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showWatchNext(emptyList())
            return
        }
        io.execute {
            val list = try {
                queryWatchNext()
            } catch (e: Exception) {
                emptyList()
            }
            ui.post { showWatchNext(list) }
        }
    }

    /**
     * Two sources, same columns:
     * 1. The system Watch Next list (Netflix etc.), newest engagement first.
     * 2. Any app's own home-screen channel named "Continue Watching". Stremio publishes one (with
     *    progress and an autoPlay resume link) instead of using Watch Next, so signing in to Stremio
     *    on the TV is all it takes. Channel rows carry no timestamps, so they keep the app's order.
     */
    private fun queryWatchNext(): List<Prog> {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
        val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden)
            .sortedByDescending { it.first }.map { it.second }
        val channels = continueChannels().flatMap { id ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden).map { it.second }
        }
        val all = watchNext + channels
        publishingPkgs = all.mapNotNull { it.pkg }.toSet()
        val off = prefs.getStringSet("cw_off", emptySet())!!
        return all.filter { it.pkg !in off }.distinctBy { it.intent ?: it.title }.take(20)
    }

    /** Apps last seen publishing, so the settings list includes them even without the permission check. */
    @Volatile private var publishingPkgs = emptySet<String>()

    /**
     * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
     * actually publishing now. Built from installed apps each time, so new apps appear on their own.
     */
    private fun watchSources(): List<App> = apps.filter { a ->
        a.pkg in publishingPkgs || try {
            packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(WRITE_EPG) == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }.sortedBy { it.label.lowercase() }

    private fun pickWatchSources() {
        val sources = watchSources()
        if (sources.isEmpty()) {
            Toast.makeText(this, "No installed apps support Continue Watching", Toast.LENGTH_SHORT).show()
            return
        }
        val off = prefs.getStringSet("cw_off", emptySet())!!.toMutableSet()
        AlertDialog.Builder(this)
            .setTitle("Continue Watching apps")
            .setMultiChoiceItems(sources.map { it.label }.toTypedArray(), sources.map { it.pkg !in off }.toBooleanArray()) { _, i, on ->
                if (on) off -= sources[i].pkg else off += sources[i].pkg
                prefs.edit().putStringSet("cw_off", off.toSet()).apply()
            }
            .setPositiveButton("Done", null)
            .setOnDismissListener {
                watchKey = null
                refreshWatchNext()
                renderSettings(6)
            }
            .show()
    }

    private fun continueChannels(): List<Long> {
        val ids = ArrayList<Long>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val name = c.getColumnIndex("display_name")
            val pkg = c.getColumnIndex("package_name")
            val id = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                if (c.getString(name).equals("Continue Watching", ignoreCase = true) && c.getString(pkg) != packageName) {
                    ids += c.getLong(id)
                }
            }
        }
        return ids
    }

    /** Pairs of (last engagement time, item); time is 0 for channel rows, which don't have it. */
    private fun readPrograms(uri: Uri, hidden: Set<String>): List<Pair<Long, Prog>> {
        val out = ArrayList<Pair<Long, Prog>>()
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
            fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                if (str("browsable") == "0") continue
                val title = str("title") ?: continue
                if (hideKey(str("package_name"), title) in hidden) continue
                val season = str("season_display_number")
                val episode = str("episode_display_number")
                val duration = num("duration_millis")
                val position = num("last_playback_position_millis")
                val sub = listOfNotNull(
                    if (season != null && episode != null) "S$season E$episode" else null,
                    // Under a second in means "not really started": no "time left", no bar.
                    if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
                ).joinToString(" - ")
                val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                val prog = Prog(title, sub, progress, str("thumbnail_uri") ?: str("poster_art_uri"), str("intent_uri"), str("package_name"))
                out += num("last_engagement_time_utc_millis") to prog
            }
        }
        return out
    }

    private fun hideKey(pkg: String?, title: String) = "$pkg/$title"

    /** We can't delete another app's Watch Next row, so hiding is a local filter. */
    private fun hideWatch(p: Prog) {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!! + hideKey(p.pkg, p.title)
        prefs.edit().putStringSet("hidden_watch", hidden).apply()
        Toast.makeText(this, "Hidden from Continue Watching", Toast.LENGTH_SHORT).show()
        refreshWatchNext()
    }

    /** Small source-app mark for the details line: black logo when we have one, else greyscale icon. */
    private fun sourceIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(fg) } }
        return try {
            packageManager.getApplicationIcon(pkg).mutate().apply { colorFilter = GREYSCALE }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun timeLeft(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} hr ${min % 60} min left" else "$min min left"
    }

    private fun showWatchNext(list: List<Prog>) {
        val key = list.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.image}" }
        if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
            retryPosters()
            return
        }
        watchKey = key
        val focusedAt = watchRow.indexOfChild(currentFocus?.parent as? View)
        watchRow.removeAllViews()
        val visible = if (list.isEmpty()) View.GONE else View.VISIBLE
        watchTitle.visibility = visible
        watchScroll.visibility = visible
        // ~16 ms per card here and only four show at once: build them four per frame, not all 20 in one.
        fun addCards(from: Int) {
            if (watchKey != key) return // a newer list replaced this one
            list.subList(from, minOf(from + 4, list.size)).forEach { watchRow.addView(watchCard(it, watchRow.childCount > 0)) }
            if (from + 4 < list.size) ui.post { addCards(from + 4) }
        }
        addCards(0)
        if (focusedAt >= 0) {
            ((watchRow.getChildAt(focusedAt.coerceAtMost(watchRow.childCount - 1)) as? LinearLayout)?.getChildAt(0)
                ?: appRow.getChildAt(0))?.requestFocus()
        }
    }

    /**
     * Four posters across, each two apps-row columns wide. Progress sits on the artwork's bottom edge;
     * title and details (source, episode, time left) go underneath.
     */
    private fun watchCard(p: Prog, gapBefore: Boolean): View {
        val gap = dp(GRID_GAP)
        val w = (resources.displayMetrics.widthPixels - dp(96) - gap * 3) / 4
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (gapBefore) marginStart = gap
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(if (dark) 0xFF111113.toInt() else 0xFFDDE1E5.toInt())
        }
        // Long titles scroll while their poster is focused instead of staying cut off.
        val title = label(p.title, 14f).apply {
            setPadding(0, dp(6), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
        }
        val frame = FrameLayout(this)
        style(frame, dp(8), card = true, lift = 1.1f) { focused ->
            title.isSelected = focused
            if (focused) lastCard = frame
        }
        frame.contentDescription = p.title
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)
        frame.setOnClickListener { open(p) }
        frame.setTag(R.id.watchRow, p) // holding OK hides it (see onHold)
        // 2:1 keeps logos and badges that providers bake into 16:9 art.
        col.addView(frame, LinearLayout.LayoutParams(w, w / 2))
        // Thin bar under the poster, card width. Kept (invisible) when there's no progress so titles line up.
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = p.progress
            progressDrawable = progressBarDrawable()
            visibility = if (p.progress > 0) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(8) })
        col.addView(title)
        val icon = sourceIcon(p.pkg)
        if (p.sub.isNotEmpty() || icon != null) {
            col.addView(label(p.sub, 12f).apply {
                alpha = 0.8f
                setPadding(0, dp(2), 0, 0)
                icon?.let {
                    it.setBounds(0, 0, dp(12), dp(12))
                    setCompoundDrawablesRelative(it, null, null, null)
                    compoundDrawablePadding = dp(6)
                }
            })
        }
        loadPoster(img, p.image)
        return col
    }

    private fun progressBarDrawable(): Drawable {
        fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
        return LayerDrawable(arrayOf(
            bar(if (dark) 0x40FFFFFF else 0x26000000),
            ClipDrawable(bar(fg), Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun open(p: Prog) {
        try {
            startActivity(Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startActivity(it) }
        }
    }

    private fun loadPoster(v: ImageView, uri: String?) {
        if (uri == null) return
        posters.get(uri)?.let { v.setImageBitmap(it); return }
        v.tag = uri
        if (!loading.add(uri)) return // already on its way
        posterIo.execute {
            val bmp = try {
                decode(uri)
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "poster failed: $uri ($e)") // retried by retryPosters()
                null
            } finally {
                loading.remove(uri)
            } ?: return@execute
            posters.put(uri, bmp)
            ui.post { if (v.tag == uri) v.setImageBitmap(bmp) }
        }
    }

    /** Cards whose poster never arrived (offline, server timeout) try again; loaded ones are left alone. */
    private fun retryPosters() {
        for (i in 0 until watchRow.childCount) {
            val img = ((watchRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
            if (img.drawable == null) loadPoster(img, img.tag as? String)
        }
    }

    /** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
    private fun decode(uri: String): Bitmap? {
        val bytes = openStream(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dp(220)) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun openStream(uri: String): InputStream =
        if (uri.startsWith("http")) {
            (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.inputStream
        } else {
            contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
        }

    // ---------- shared view bits ----------

    /**
     * tvOS-style focus, no outline: the focused card lifts (scale + shadow). Light mode adds a soft
     * top sheen; dark mode brightens the glass gradient instead (a white sheen would haze it grey).
     * Only transform/alpha/colour change, so it stays on the GPU and costs no re-layout.
     */
    private fun style(v: View, radius: Int, card: Boolean, lift: Float = 1.08f, onFocus: ((Boolean) -> Unit)? = null) {
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius.toFloat()
            alpha = 0
        }
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (card) cardRest else intArrayOf(0, 0)).apply {
            cornerRadius = radius.toFloat()
        }
        v.background = bg
        if (!dark) v.foreground = sheen
        v.clipToOutline = true
        v.outlineSpotShadowColor = Color.BLACK
        v.outlineAmbientShadowColor = Color.BLACK
        v.elevation = if (card) dp(2).toFloat() else 0f
        v.isFocusable = true
        v.isClickable = true
        v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
        v.setOnFocusChangeListener { _, focused ->
            sheen.alpha = if (focused) 255 else 0
            if (card) bg.colors = if (focused) cardLit else cardRest
            v.animate()
                .scaleX(if (focused) lift else 1f).scaleY(if (focused) lift else 1f)
                .translationZ(if (focused) dp(14).toFloat() else 0f)
                .setDuration(160).setInterpolator(DECELERATE).start()
            onFocus?.invoke(focused)
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        const val SCREENSAVER_ROW = 8 // positions in the settings list (see renderSettings)
        const val HOME_BUTTON_ROW = 10
        const val DEFAULT_HOME_ROW = 11
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val ROW_TILES = 8
        const val GRID_GAP = 16 // dp; shared by the apps row and the cards so their columns line up
        val BUNDLED_WALLPAPERS = linkedMapOf(
            "Rockies (day)" to R.drawable.wallpaper,
            "Rockies (dusk)" to R.drawable.wallpaper_dark,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val GREYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val DECELERATE = android.view.animation.DecelerateInterpolator(1.5f)
        @Volatile var APPS: List<App>? = null
        /** Mono marks made by glyph(), by package; null = icon has no usable symbol. Main thread only. */
        val GLYPHS = HashMap<String, Bitmap?>()

        val DEFAULT_ORDER = listOf(
            "com.netflix.ninja",
            "com.stremio.one",
            "io.gh.reisxd.tizentube.cobalt",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
        )

        /** Monochrome brand marks (Simple Icons, CC0). `true` = wordmark, shown without a label. */
        val LOGOS = mapOf(
            "com.netflix.ninja" to (R.drawable.logo_netflix to false),
            "com.stremio.one" to (R.drawable.logo_stremio to false),
            "io.gh.reisxd.tizentube.cobalt" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tv" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tvmusic" to (R.drawable.logo_youtubemusic to false),
            "com.amazon.amazonvideo.livingroom" to (R.drawable.logo_primevideo to true),
            "com.spotify.tv.android" to (R.drawable.logo_spotify to false),
            "org.videolan.vlc" to (R.drawable.logo_vlcmediaplayer to false),
            "com.android.vending" to (R.drawable.logo_googleplay to false),
        )
    }
}
