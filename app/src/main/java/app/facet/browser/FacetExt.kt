package app.facet.browser

import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/** Talks to Facet's built-in extension (reader view, highlights, page text). Everything stays on the device. */
object FacetExt {
    private const val NATIVE = "facet"

    interface Listener {
        fun onHello(tabId: Long, url: String, title: String)
        fun onHighlight(tabId: Long, url: String, title: String, text: String)
        fun onText(tabId: Long, url: String, title: String, text: String)
        fun onReaderSettings(size: Int, width: Int, theme: Int)
        fun onToast(text: String)
        fun onReaderClosed(tabId: Long)
    }

    var listener: Listener? = null
    private var extension: WebExtension? = null
    private val ports = HashMap<Long, WebExtension.Port>()

    val ready: Boolean get() = extension != null

    fun install(rt: GeckoRuntime, onReady: () -> Unit) {
        rt.webExtensionController
            .ensureBuiltIn("resource://android/assets/facet-ext/", "facet@facet.app")
            .accept({ ext ->
                extension = ext
                onReady()
            }, { _ ->
                // The browser still works without the extension; reader view and highlights are simply unavailable.
            })
    }

    /** Must be called for every session; harmless before the extension is ready. */
    fun attach(session: GeckoSession, tabId: Long) {
        val ext = extension ?: return
        session.webExtensionController.setMessageDelegate(ext, delegate(tabId), NATIVE)
    }

    fun send(tabId: Long, message: JSONObject): Boolean {
        val p = ports[tabId] ?: return false
        return try {
            p.postMessage(message)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun forget(tabId: Long) { ports.remove(tabId) }

    private fun delegate(tabId: Long) = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            ports[tabId] = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    handle(tabId, message as? JSONObject)
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (ports[tabId] === port) ports.remove(tabId)
                }
            })
        }
    }

    private fun handle(tabId: Long, m: JSONObject?) {
        if (m == null) return
        val l = listener ?: return
        when (m.optString("type")) {
            "hello" -> l.onHello(tabId, m.optString("url"), m.optString("title"))
            "highlight" -> l.onHighlight(tabId, m.optString("url"), m.optString("title"), m.optString("text"))
            "text" -> l.onText(tabId, m.optString("url"), m.optString("title"), m.optString("text"))
            "readerSettings" -> l.onReaderSettings(m.optInt("size", 19), m.optInt("width", 1), m.optInt("theme", 0))
            "toast" -> l.onToast(m.optString("text"))
            "readerClosed" -> l.onReaderClosed(tabId)
        }
    }
}
