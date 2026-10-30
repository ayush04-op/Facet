package app.facet.browser

import android.content.Context

/** Host-based ad and tracker blocklist, loaded from assets/blocklist.txt. */
class Blocklist(private val hosts: HashSet<String>) {

    fun isBlocked(host: String): Boolean {
        var h = host.lowercase()
        while (true) {
            if (hosts.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0 || i == h.length - 1) return false
            h = h.substring(i + 1)
        }
    }

    companion object {
        fun load(ctx: Context): Blocklist {
            val set = HashSet<String>()
            try {
                ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val h = line.trim().lowercase()
                        if (h.isNotEmpty() && !h.startsWith("#")) set.add(h)
                    }
                }
            } catch (e: Exception) {
                // no list: blocking silently does nothing rather than crashing
            }
            return Blocklist(set)
        }
    }
}
