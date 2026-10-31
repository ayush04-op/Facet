package app.facet.browser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Bookmark(val title: String, val url: String)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("facet", Context.MODE_PRIVATE)

    fun getStr(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    fun putStr(key: String, v: String) { sp.edit().putString(key, v).apply() }
    fun getBool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    fun putBool(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply() }
    fun getLong(key: String, def: Long): Long = sp.getLong(key, def)
    fun putLong(key: String, v: Long) { sp.edit().putLong(key, v).apply() }
    fun getInt(key: String, def: Int): Int = sp.getInt(key, def)
    fun putInt(key: String, v: Int) { sp.edit().putInt(key, v).apply() }
    fun remove(key: String) { sp.edit().remove(key).apply() }

    /** Id of the workspace whose tabs, bookmarks and notes are active. "personal" always exists. */
    var workspaceId: String
        get() = sp.getString("workspace", "personal") ?: "personal"
        set(v) { sp.edit().putString("workspace", v).apply() }

    /** The personal workspace keeps the original storage keys, so nothing is lost when upgrading. */
    private fun wsKey(base: String): String = if (workspaceId == "personal") base else base + "_" + workspaceId

    /** 0 = grid, 1 = vertical list */
    var tabListMode: Int
        get() = sp.getInt("tab_list_mode", 0)
        set(v) { sp.edit().putInt("tab_list_mode", v).apply() }

    /** 0 = standard, 1 = strict, 2 = custom (the individual switches decide) */
    var protectionLevel: Int
        get() = sp.getInt("protection_level", 1)
        set(v) { sp.edit().putInt("protection_level", v).apply() }

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

    var updateRepo: String
        get() = sp.getString("update_repo", "ayush04-op/Facet") ?: "ayush04-op/Facet"
        set(v) { sp.edit().putString("update_repo", v.trim()).apply() }

    /** Only needed while the repository is private. Stored in app-private storage. */
    var updateToken: String
        get() = sp.getString("update_token", "") ?: ""
        set(v) { sp.edit().putString("update_token", v.trim()).apply() }

    var autoCheckUpdates: Boolean
        get() = sp.getBoolean("auto_check_updates", true)
        set(v) { sp.edit().putBoolean("auto_check_updates", v).apply() }

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) { sp.edit().putLong("last_update_check", v).apply() }

    /** Tag of a newer release found by the last check, or empty. */
    var availableUpdate: String
        get() = sp.getString("available_update", "") ?: ""
        set(v) { sp.edit().putString("available_update", v).apply() }

    var sessionUrls: String
        get() = sp.getString(wsKey("session_urls"), "[]") ?: "[]"
        set(v) { sp.edit().putString(wsKey("session_urls"), v).apply() }

    var sessionSelected: Int
        get() = sp.getInt(wsKey("session_selected"), 0)
        set(v) { sp.edit().putInt(wsKey("session_selected"), v).apply() }

    /** Set while Facet is running normally; still true at the next launch means the last run ended abnormally. */
    var runningFlag: Boolean
        get() = sp.getBoolean("running", false)
        set(v) { sp.edit().putBoolean("running", v).commit() }

    fun bookmarks(): MutableList<Bookmark> {
        val out = ArrayList<Bookmark>()
        try {
            val a = JSONArray(sp.getString(wsKey("bookmarks"), "[]"))
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
        sp.edit().putString(wsKey("bookmarks"), a.toString()).apply()
    }
}
