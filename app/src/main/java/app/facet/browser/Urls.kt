package app.facet.browser

import android.net.Uri
import java.net.URLEncoder

class Engine(val id: String, val name: String, val template: String)

object Urls {
    val engines = listOf(
        Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
        Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
        Engine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
        Engine("google", "Google", "https://www.google.com/search?q=%s")
    )

    fun engine(id: String): Engine {
        for (e in engines) if (e.id == id) return e
        return engines[0]
    }

    private val ipRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")

    /** Turns whatever was typed into a loadable URL: an address, or a search. */
    fun resolve(input: String, engine: Engine): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return s
        val hasSpace = s.contains(' ')
        if (!hasSpace) {
            if (lower.startsWith("localhost") || ipRegex.matches(s)) return "http://$s"
            if (s.contains('.') && !s.endsWith(".")) return "https://$s"
        }
        return engine.template.replace("%s", URLEncoder.encode(s, "UTF-8"))
    }

    fun host(url: String): String {
        val h = try { Uri.parse(url).host } catch (e: Exception) { null }
        if (h.isNullOrEmpty()) return url
        return if (h.startsWith("www.")) h.substring(4) else h
    }

    fun isLocal(host: String?): Boolean {
        if (host == null) return true
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return true
        return ipRegex.matches(host)
    }
}
