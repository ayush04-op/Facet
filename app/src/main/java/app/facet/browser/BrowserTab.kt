package app.facet.browser

import org.mozilla.geckoview.GeckoSession

class BrowserTab(val id: Long, val isPrivate: Boolean) {
    /** The engine session behind this tab. Created lazily, the first time the tab is shown. */
    var session: GeckoSession? = null
    var title: String = ""
    var url: String = ""
    var progress: Int = 0
    var desktop: Boolean = false
    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var secure: Boolean = false

    /** True while the tab is showing Facet's new tab page instead of a website. */
    var showsNewTab: Boolean = true

    /** URL to load the first time this tab's session is opened (restored sessions). */
    var pendingUrl: String? = null

    /** Name of the tab group this tab belongs to, or null. Groups live inside one workspace. */
    var group: String? = null

    /** Hosts of requests blocked on the current page, with counts. Reset on every navigation. */
    val blockedHosts = HashMap<String, Int>()

    /** Opened by a page (target=_blank): Back closes it once there is no history left. */
    var closeOnBack: Boolean = false
}
