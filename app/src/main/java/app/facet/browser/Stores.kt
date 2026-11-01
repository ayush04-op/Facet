package app.facet.browser

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ---------------------------------------------------------------- workspaces

class Workspace(val id: String, var name: String, val kind: Int) {
    companion object {
        const val NORMAL = 0
        const val RESEARCH = 1
        const val TEMPORARY = 2
    }

    val label: String
        get() = when (kind) { RESEARCH -> "Research"; TEMPORARY -> "Temporary"; else -> "Profile" }
}

/** Workspaces keep tabs, bookmarks, cookies and site data apart. Everything stays on the device. */
class WorkspaceStore(private val prefs: Prefs) {

    fun all(): MutableList<Workspace> {
        val out = ArrayList<Workspace>()
        try {
            val a = JSONArray(prefs.getStr("workspaces", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Workspace(o.getString("id"), o.getString("name"), o.optInt("kind", 0)))
            }
        } catch (e: Exception) {
            // start from the default
        }
        if (out.none { it.id == "personal" }) out.add(0, Workspace("personal", "Personal", Workspace.NORMAL))
        return out
    }

    fun save(list: List<Workspace>) {
        val a = JSONArray()
        for (w in list) a.put(JSONObject().put("id", w.id).put("name", w.name).put("kind", w.kind))
        prefs.putStr("workspaces", a.toString())
    }

    fun current(): Workspace = all().firstOrNull { it.id == prefs.workspaceId } ?: all().first()

    fun add(name: String, kind: Int): Workspace {
        val list = all()
        val id = "w" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val w = Workspace(id, name.ifBlank { "Profile" }, kind)
        list.add(w)
        save(list)
        return w
    }

    fun rename(id: String, name: String) {
        val list = all()
        list.firstOrNull { it.id == id }?.name = name.ifBlank { "Profile" }
        save(list)
    }

    /** Removes the workspace and everything stored for it. */
    fun remove(id: String) {
        if (id == "personal") return
        val list = all()
        list.removeAll { it.id == id }
        save(list)
        for (base in listOf("bookmarks", "session_urls", "session_selected", "research_notes", "research_pages")) {
            prefs.remove(base + "_" + id)
        }
    }

    /** Engine storage partition. The personal workspace uses the engine's normal storage. */
    fun contextFor(w: Workspace): String? = when {
        w.id == "personal" -> null
        w.kind == Workspace.TEMPORARY -> "tmp_" + w.id
        else -> "ws_" + w.id
    }
}

// ---------------------------------------------------------------- site permissions

class SitePerm(val host: String, val type: String, var allowed: Boolean, val granted: Long, var lastUsed: Long) {
    companion object {
        const val CAMERA = "camera"
        const val MICROPHONE = "microphone"
        const val LOCATION = "location"
        const val NOTIFICATIONS = "notifications"
        val types = listOf(CAMERA, MICROPHONE, LOCATION, NOTIFICATIONS)

        fun title(type: String): String = when (type) {
            CAMERA -> "Camera"; MICROPHONE -> "Microphone"; LOCATION -> "Location"; else -> "Notifications"
        }
    }
}

class SitePermStore(private val prefs: Prefs) {
    private val key = "site_perms"

    fun all(): MutableList<SitePerm> {
        val out = ArrayList<SitePerm>()
        try {
            val a = JSONArray(prefs.getStr(key, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(SitePerm(o.getString("h"), o.getString("t"), o.optBoolean("a"), o.optLong("g"), o.optLong("u")))
            }
        } catch (e: Exception) {
            // ignore corrupt data
        }
        return out
    }

    private fun save(list: List<SitePerm>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("h", p.host).put("t", p.type).put("a", p.allowed).put("g", p.granted).put("u", p.lastUsed))
        prefs.putStr(key, a.toString())
    }

    fun find(host: String, type: String): SitePerm? = all().firstOrNull { it.host == host && it.type == type }

    fun set(host: String, type: String, allowed: Boolean) {
        val list = all()
        val now = System.currentTimeMillis()
        val existing = list.firstOrNull { it.host == host && it.type == type }
        if (existing != null) {
            list.remove(existing)
            list.add(SitePerm(host, type, allowed, now, now))
        } else {
            list.add(SitePerm(host, type, allowed, now, now))
        }
        save(list)
    }

    fun touch(host: String, type: String) {
        val list = all()
        list.firstOrNull { it.host == host && it.type == type }?.lastUsed = System.currentTimeMillis()
        save(list)
    }

    fun remove(host: String, type: String) {
        val list = all()
        list.removeAll { it.host == host && it.type == type }
        save(list)
    }

    fun clearHost(host: String) {
        val list = all()
        list.removeAll { it.host == host }
        save(list)
    }

    fun clearAll() = save(emptyList())

    fun hosts(): List<String> = all().map { it.host }.distinct().sorted()

    /** Sites that were allowed access to camera, microphone or location a month or more ago. */
    fun reminders(): List<SitePerm> {
        val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        return all().filter { it.allowed && it.type != SitePerm.NOTIFICATIONS && it.granted < limit }
    }
}

// ---------------------------------------------------------------- history

class HistoryEntry(val url: String, val title: String, val time: Long)

class HistoryStore(private val prefs: Prefs) {
    private val key = "history"
    private var cache: MutableList<HistoryEntry>? = null

    fun all(): MutableList<HistoryEntry> {
        cache?.let { return it }
        val out = ArrayList<HistoryEntry>()
        try {
            val a = JSONArray(prefs.getStr(key, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(HistoryEntry(o.getString("u"), o.optString("t"), o.optLong("w")))
            }
        } catch (e: Exception) {
            // ignore
        }
        cache = out
        return out
    }

    fun add(url: String, title: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        val list = all()
        list.removeAll { it.url == url }
        list.add(0, HistoryEntry(url, title, System.currentTimeMillis()))
        while (list.size > 500) list.removeAt(list.size - 1)
        flush()
    }

    fun clear() {
        all().clear()
        flush()
    }

    private fun flush() {
        val a = JSONArray()
        for (e in all()) a.put(JSONObject().put("u", e.url).put("t", e.title).put("w", e.time))
        prefs.putStr(key, a.toString())
    }
}

// ---------------------------------------------------------------- research

class SavedPage(val url: String, val title: String, val time: Long, val highlights: MutableList<String>)

/** A topic's notes and saved pages with their highlights. Lives only in this workspace, on this device. */
class ResearchStore(private val prefs: Prefs, private val wsId: String) {
    private fun k(base: String) = base + "_" + wsId

    var notes: String
        get() = prefs.getStr(k("research_notes"), "")
        set(v) { prefs.putStr(k("research_notes"), v) }

    fun pages(): MutableList<SavedPage> {
        val out = ArrayList<SavedPage>()
        try {
            val a = JSONArray(prefs.getStr(k("research_pages"), "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val hs = ArrayList<String>()
                val ha = o.optJSONArray("h")
                if (ha != null) for (j in 0 until ha.length()) hs.add(ha.getString(j))
                out.add(SavedPage(o.getString("u"), o.optString("t"), o.optLong("w"), hs))
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    private fun save(list: List<SavedPage>) {
        val a = JSONArray()
        for (p in list) {
            val h = JSONArray()
            for (x in p.highlights) h.put(x)
            a.put(JSONObject().put("u", p.url).put("t", p.title).put("w", p.time).put("h", h))
        }
        prefs.putStr(k("research_pages"), a.toString())
    }

    fun savePage(url: String, title: String) {
        val list = pages()
        if (list.none { it.url == url }) {
            list.add(0, SavedPage(url, title, System.currentTimeMillis(), ArrayList()))
            save(list)
        }
    }

    fun addHighlight(url: String, title: String, text: String) {
        val list = pages()
        var p = list.firstOrNull { it.url == url }
        if (p == null) {
            p = SavedPage(url, title, System.currentTimeMillis(), ArrayList())
            list.add(0, p)
        }
        if (!p.highlights.contains(text)) p.highlights.add(text)
        save(list)
    }

    fun highlightsFor(url: String): List<String> = pages().firstOrNull { it.url == url }?.highlights ?: emptyList()

    fun removePage(url: String) {
        val list = pages()
        list.removeAll { it.url == url }
        save(list)
    }

    fun removeHighlight(url: String, text: String) {
        val list = pages()
        list.firstOrNull { it.url == url }?.highlights?.remove(text)
        save(list)
    }

    fun markdown(topic: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(topic).append("\n\n")
        if (notes.isNotBlank()) sb.append("## Notes\n\n").append(notes.trim()).append("\n\n")
        val ps = pages()
        if (ps.isNotEmpty()) sb.append("## Sources\n\n")
        for (p in ps) {
            sb.append("### [").append(if (p.title.isBlank()) p.url else p.title).append("](").append(p.url).append(")\n\n")
            for (h in p.highlights) sb.append("> ").append(h.replace("\n", " ")).append("\n\n")
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------- secrets

/** Encrypts small secrets (such as an API key) with a key that never leaves the Android Keystore. */
object SecretBox {
    private const val ALIAS = "facet_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val parts = stored.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
