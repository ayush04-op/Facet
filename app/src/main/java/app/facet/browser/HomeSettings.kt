package app.facet.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Everything the person can change about the home page and the glass look. All stored on the device. */
class HomeConfig(private val p: Prefs) {
    var glass: Boolean
        get() = p.getBool("glass_on", true)
        set(v) = p.putBool("glass_on", v)

    /** 0 soft, 1 medium, 2 strong */
    var blur: Int
        get() = p.getInt("glass_blur", 1)
        set(v) = p.putInt("glass_blur", v)

    /** 0..4 generated, 9 = the person's own photo */
    var wallpaper: Int
        get() = p.getInt("wp_kind", 0)
        set(v) = p.putInt("wp_kind", v)

    /** 0 none, 1 light, 2 medium, 3 strong */
    var dim: Int
        get() = p.getInt("wp_dim", 1)
        set(v) = p.putInt("wp_dim", v)

    var aurora: Boolean
        get() = p.getBool("wp_aurora", true)
        set(v) = p.putBool("wp_aurora", v)

    var customStamp: Long
        get() = p.getLong("wp_stamp", 0L)
        set(v) = p.putLong("wp_stamp", v)

    var clock: Boolean
        get() = p.getBool("home_clock", true)
        set(v) = p.putBool("home_clock", v)

    /** 0 follow the system, 1 12-hour, 2 24-hour */
    var hourFormat: Int
        get() = p.getInt("home_hours", 0)
        set(v) = p.putInt("home_hours", v)

    var date: Boolean
        get() = p.getBool("home_date", true)
        set(v) = p.putBool("home_date", v)

    var weather: Boolean
        get() = p.getBool("home_weather", false)
        set(v) = p.putBool("home_weather", v)

    var city: String
        get() = p.getStr("weather_city", "")
        set(v) = p.putStr("weather_city", v)

    var fahrenheit: Boolean
        get() = p.getBool("weather_f", false)
        set(v) = p.putBool("weather_f", v)

    var stats: Boolean
        get() = p.getBool("home_stats", true)
        set(v) = p.putBool("home_stats", v)

    var bookmarks: Boolean
        get() = p.getBool("home_bookmarks", true)
        set(v) = p.putBool("home_bookmarks", v)

    var name: String
        get() = p.getStr("home_name", "")
        set(v) = p.putStr("home_name", v)

    /** index into Palette.accents */
    var accent: Int
        get() = p.getInt("accent", 0)
        set(v) = p.putInt("accent", v)

    val dimAlpha: Int get() = when (dim) { 1 -> 0x26; 2 -> 0x4D; 3 -> 0x80; else -> 0 }
}

/** Wallpaper and glass source for the home page. Decoded once and reused until a setting changes. */
object HomeLook {
    private var key = ""
    private var sharp: Bitmap? = null
    private var blurred: Bitmap? = null

    class Look(val wallpaper: WallpaperView, val glass: GlassSource?)

    fun build(ctx: Context, cfg: HomeConfig, dark: Boolean, amoled: Boolean = false): Look {
        val dm = ctx.resources.displayMetrics
        // Decode at about half the screen: plenty for a background and cheap to keep.
        val w = dm.widthPixels / 2
        val h = dm.heightPixels / 2
        val dim = if (amoled) Math.max(cfg.dimAlpha, 0xB3) else cfg.dimAlpha
        val k = "${cfg.wallpaper}/$dark/${cfg.blur}/${cfg.customStamp}/$w/$h/$dim"
        if (k != key || sharp == null) {
            var s: Bitmap? = if (cfg.wallpaper == 9) Wallpapers.custom(ctx) else null
            if (s == null) s = Wallpapers.preset(if (cfg.wallpaper == 9) 0 else cfg.wallpaper, dark, w, h)
            sharp = s
            val soft = Wallpapers.blurred(s, cfg.blur)
            // the glass must see the same dimming as the wallpaper itself
            if (dim > 0) android.graphics.Canvas(soft).drawColor(withAlpha(0x000000, dim))
            blurred = soft
            key = k
        }
        val view = WallpaperView(ctx, sharp!!, dim)
        val src = if (cfg.glass) GlassSource(blurred!!, dark) else null
        src?.origin = view
        return Look(view, src)
    }

    fun forget() { key = ""; sharp = null; blurred = null }
}

// ---------------------------------------------------------------- weather (opt-in, Open-Meteo)

class WeatherInfo(val temp: Int, val words: String, val place: String, val fahrenheit: Boolean)

object Weather {
    private val ui = Handler(Looper.getMainLooper())

    fun cached(p: Prefs): WeatherInfo? {
        val raw = p.getStr("weather_cache", "")
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            WeatherInfo(o.getInt("t"), o.getString("w"), o.getString("p"), o.getBoolean("f"))
        } catch (e: Exception) { null }
    }

    private fun stale(p: Prefs): Boolean = System.currentTimeMillis() - p.getLong("weather_time", 0L) > 30 * 60 * 1000L

    /** Fetches only when the person turned weather on and has chosen a place; at most every half hour. */
    fun refresh(p: Prefs, cfg: HomeConfig, force: Boolean = false, done: (WeatherInfo?) -> Unit) {
        val lat = p.getStr("weather_lat", "")
        val lon = p.getStr("weather_lon", "")
        if (!cfg.weather || lat.isEmpty() || lon.isEmpty()) { done(null); return }
        val have = cached(p)
        if (!force && have != null && have.fahrenheit == cfg.fahrenheit && !stale(p)) { done(have); return }
        Thread {
            try {
                val unit = if (cfg.fahrenheit) "fahrenheit" else "celsius"
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&temperature_unit=$unit"
                val body = get(url)
                val cur = JSONObject(body).getJSONObject("current")
                val info = WeatherInfo(Math.round(cur.getDouble("temperature_2m")).toInt(), words(cur.getInt("weather_code")), cfg.city, cfg.fahrenheit)
                p.putStr("weather_cache", JSONObject().put("t", info.temp).put("w", info.words).put("p", info.place).put("f", info.fahrenheit).toString())
                p.putLong("weather_time", System.currentTimeMillis())
                ui.post { done(info) }
            } catch (e: Exception) {
                ui.post { done(have) }
            }
        }.start()
    }

    /** Looks a place up by name and remembers its coordinates. */
    fun setPlace(p: Prefs, cfg: HomeConfig, query: String, done: (String?) -> Unit) {
        Thread {
            try {
                val body = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + URLEncoder.encode(query, "UTF-8"))
                val r = JSONObject(body).optJSONArray("results")?.optJSONObject(0)
                if (r == null) { ui.post { done(null) }; return@Thread }
                val name = r.optString("name", query)
                p.putStr("weather_lat", r.getDouble("latitude").toString())
                p.putStr("weather_lon", r.getDouble("longitude").toString())
                cfg.city = name
                p.putLong("weather_time", 0L)
                ui.post { done(name) }
            } catch (e: Exception) {
                ui.post { done(null) }
            }
        }.start()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 15000
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun words(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Mostly clear"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Thunderstorm"
        else -> "Weather"
    }
}

// ---------------------------------------------------------------- settings page

fun homePage(h: SettingsHost): View {
    val cfg = HomeConfig(h.prefs)
    lateinit var view: PageView
    fun changed() { h.homeChanged(); view.refresh() }
    view = PageView(h.context, h.colors, "Home page", { h.back() }) { b ->
        b.section("Look")
        b.toggle("Liquid glass", "Frosted, see-through panels over your wallpaper", cfg.glass) { cfg.glass = it; HomeLook.forget(); changed() }
        if (cfg.glass) {
            b.choice("Soft blur", null, cfg.blur == 0) { cfg.blur = 0; HomeLook.forget(); changed() }
            b.choice("Medium blur", null, cfg.blur == 1) { cfg.blur = 1; HomeLook.forget(); changed() }
            b.choice("Strong blur", null, cfg.blur == 2) { cfg.blur = 2; HomeLook.forget(); changed() }
        }
        b.section("Accent colour")
        for ((i, a) in Palette.accents.withIndex()) {
            b.choice(a.name, null, cfg.accent == i) { cfg.accent = i; h.applyTheme(h.prefs.theme) }
        }
        b.section("Wallpaper")
        for ((i, n) in Wallpapers.names.withIndex()) {
            b.choice(n, null, cfg.wallpaper == i) { cfg.wallpaper = i; HomeLook.forget(); changed() }
        }
        b.choice("Your photo", if (cfg.wallpaper == 9) "In use. Tap to pick another." else "Choose from your gallery", cfg.wallpaper == 9) { h.pickWallpaper() }
        b.section("Wallpaper dimming")
        for ((i, n) in listOf("None", "Light", "Medium", "Strong").withIndex()) {
            b.choice(n, null, cfg.dim == i) { cfg.dim = i; HomeLook.forget(); changed() }
        }
        b.toggle("Moving aurora", "Slow drifting colour over the wallpaper", cfg.aurora) { cfg.aurora = it; changed() }

        b.section("Clock")
        b.toggle("Show the time", null, cfg.clock) { cfg.clock = it; changed() }
        if (cfg.clock) {
            b.choice("Follow the system", "12 or 24 hour", cfg.hourFormat == 0) { cfg.hourFormat = 0; changed() }
            b.choice("12-hour", null, cfg.hourFormat == 1) { cfg.hourFormat = 1; changed() }
            b.choice("24-hour", null, cfg.hourFormat == 2) { cfg.hourFormat = 2; changed() }
            b.toggle("Show the date", null, cfg.date) { cfg.date = it; changed() }
        }

        b.section("Weather")
        b.toggle("Show the weather", "Off by default. Looks up the place you choose on Open-Meteo, nothing else.", cfg.weather) { cfg.weather = it; changed() }
        if (cfg.weather) {
            b.row("Place", if (cfg.city.isEmpty()) "Not set" else cfg.city) {
                h.input("Place", "City name", cfg.city, false) { q ->
                    if (q.isNotBlank()) Weather.setPlace(h.prefs, cfg, q.trim()) { found ->
                        if (found == null) h.toast("Couldn't find that place") else { h.toast(found); changed() }
                    }
                }
            }
            b.choice("Celsius", null, !cfg.fahrenheit) { cfg.fahrenheit = false; changed() }
            b.choice("Fahrenheit", null, cfg.fahrenheit) { cfg.fahrenheit = true; changed() }
            b.note("Only the place name is sent when you set it, and its coordinates when the weather is refreshed, at most every half hour.")
        }

        b.section("Page")
        b.row("Your name", if (cfg.name.isEmpty()) "Shown in a greeting. Not set." else cfg.name) {
            h.input("Your name", "Optional", cfg.name, false) { cfg.name = it.trim(); changed() }
        }
        b.toggle("Protection counters", null, cfg.stats) { cfg.stats = it; changed() }
        b.toggle("Bookmarks", null, cfg.bookmarks) { cfg.bookmarks = it; changed() }
    }
    return view
}
