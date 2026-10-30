package app.facet.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Bookmark(val title: String, val url: String)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("facet", Context.MODE_PRIVATE)

    var blockTrackers: Boolean
        get() = sp.getBoolean("block_trackers", true)
        set(v) { sp.edit().putBoolean("block_trackers", v).apply() }

    var blockThirdPartyCookies: Boolean
        get() = sp.getBoolean("block_3p_cookies", true)
        set(v) { sp.edit().putBoolean("block_3p_cookies", v).apply() }

    var httpsUpgrade: Boolean
        get() = sp.getBoolean("https_upgrade", true)
        set(v) { sp.edit().putBoolean("https_upgrade", v).apply() }

    var javascript: Boolean
        get() = sp.getBoolean("javascript", true)
        set(v) { sp.edit().putBoolean("javascript", v).apply() }

    var safeBrowsing: Boolean
        get() = sp.getBoolean("safe_browsing", true)
        set(v) { sp.edit().putBoolean("safe_browsing", v).apply() }

    var restoreTabs: Boolean
        get() = sp.getBoolean("restore_tabs", true)
        set(v) { sp.edit().putBoolean("restore_tabs", v).apply() }

    /** 0 = follow system, 1 = light, 2 = dark */
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var searchEngine: String
        get() = sp.getString("search_engine", "ddg") ?: "ddg"
        set(v) { sp.edit().putString("search_engine", v).apply() }

    var trackersBlocked: Long
        get() = sp.getLong("stat_blocked", 0L)
        set(v) { sp.edit().putLong("stat_blocked", v).apply() }

    var httpsUpgraded: Long
        get() = sp.getLong("stat_upgraded", 0L)
        set(v) { sp.edit().putLong("stat_upgraded", v).apply() }

    var sessionUrls: String
        get() = sp.getString("session_urls", "[]") ?: "[]"
        set(v) { sp.edit().putString("session_urls", v).apply() }

    var sessionSelected: Int
        get() = sp.getInt("session_selected", 0)
        set(v) { sp.edit().putInt("session_selected", v).apply() }

    fun bookmarks(): MutableList<Bookmark> {
        val out = ArrayList<Bookmark>()
        try {
            val a = JSONArray(sp.getString("bookmarks", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Bookmark(o.optString("t"), o.optString("u")))
            }
        } catch (e: Exception) {
            // corrupt data: start empty
        }
        return out
    }

    fun saveBookmarks(list: List<Bookmark>) {
        val a = JSONArray()
        for (b in list) {
            a.put(JSONObject().put("t", b.title).put("u", b.url))
        }
        sp.edit().putString("bookmarks", a.toString()).apply()
    }
}
